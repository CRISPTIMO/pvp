package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Авто-вода (MLG): при падении с опасной высоты, когда до земли остаётся
 * несколько блоков, ставит воду под себя (гасит урон), а после приземления
 * забирает её обратно пустым ведром. Слот и взгляд возвращаются СРАЗУ (как у
 * броска перла) — рука не залипает на ведре. Забор наводится на точную позицию
 * воды, поэтому работает даже если сдвинул камеру или отошёл на шаг.
 * Тумблер — клавиша (по умолчанию Numpad -).
 */
public final class AutoWaterBucket {

    public static boolean enabled = true;

    private static final double BUCKET_REACH = 4.3;  // досягаемость ведра от глаз
    private static final double MIN_FALL = 3.0;      // ниже — фолл-дамага нет
    private static final double FALL_SPEED = -0.5;
    private static final int PICKUP_TIMEOUT = 80;    // тиков на забор после приземления

    private static boolean placed = false;    // вода поставлена, ждём забора
    private static BlockPos waterPos = null;   // где лежит наша вода
    private static int pickupTicks = 0;

    private AutoWaterBucket() {}

    /** Вода как MLG сейчас применима: включена, есть ведро в хотбаре, не мейс-слэм. */
    public static boolean isAvailable(LocalPlayer player) {
        return enabled && PvpConfig.AUTO_WATER_ENABLED.get()
            && findInHotbar(player, false) >= 0
            && !com.pvphelper.util.HeldItems.isMaceOrSpear(player.getMainHandItem());
    }

    public static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || mc.level == null || mc.screen != null) return;
        if (!enabled || !PvpConfig.AUTO_WATER_ENABLED.get()) return;

        if (placed) {
            handlePickup(mc, player);
            return;
        }

        // Мейс-слэм булавой/копьём — падаем намеренно, воду не льём.
        if (com.pvphelper.util.HeldItems.isMaceOrSpear(player.getMainHandItem())) return;

        // Фаза 1: падаем быстро и с опасной высоты, не в воде.
        if (player.getDeltaMovement().y > FALL_SPEED || player.fallDistance < MIN_FALL) return;
        if (player.isInWater()) return;

        BlockHitResult ground = groundBelow(mc, player);
        if (ground == null) return;                       // земля вне досягаемости ведра
        int slot = findInHotbar(player, false);
        if (slot < 0) return;                             // нет ведра с водой в хотбаре

        waterPos = ground.getBlockPos().above();          // вода ляжет над грунтом
        aimAndUse(mc, player, slot, ground.getLocation());// целимся в грунт, льём, слот назад
        placed = true;
        pickupTicks = 0;
        player.displayClientMessage(Component.literal("§b[Вода] §fMLG!"), true);
    }

    private static void handlePickup(Minecraft mc, LocalPlayer player) {
        // Ждём приземления (или что уже в воде).
        boolean landed = player.onGround() || player.isInWater() || player.fallDistance < 1.0;
        if (!landed) return;

        // Воды уже нет (забрали / утекла) — завершаем и возвращаемся в ожидание.
        if (waterPos == null || !mc.level.getFluidState(waterPos).isSource()) {
            reset();
            return;
        }
        if (++pickupTicks > PICKUP_TIMEOUT) { reset(); return; } // не смогли — не залипаем

        int bucket = findInHotbar(player, true);
        if (bucket < 0) return; // нет пустого ведра в хотбаре — ждём (вдруг появится)

        aimAndUse(mc, player, bucket, Vec3.atCenterOf(waterPos)); // целимся в воду, забираем
    }

    /**
     * Наводится на точку, использует предмет из слота и СРАЗУ возвращает
     * прежние слот и взгляд (одним тиком, как бросок перла).
     */
    private static void aimAndUse(Minecraft mc, LocalPlayer player, int slot, Vec3 point) {
        float savedYaw = player.getYRot();
        float savedPitch = player.getXRot();
        int savedSlot = player.getInventory().getSelectedSlot();

        Vec3 eye = player.getEyePosition(1.0f);
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setXRot((float) Math.max(-90.0, Math.min(90.0, -Math.toDegrees(Math.atan2(dy, horiz)))));

        selectSlot(mc, slot);
        mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);

        selectSlot(mc, savedSlot);   // вернуть слот сразу
        player.setXRot(savedPitch);  // вернуть взгляд сразу
        player.setYRot(savedYaw);
    }

    /** Луч вниз от глаз в пределах досягаемости ведра: первая твёрдая поверхность. */
    private static BlockHitResult groundBelow(Minecraft mc, LocalPlayer player) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 to = eye.add(0, -BUCKET_REACH, 0);
        ClipContext ctx = new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        BlockHitResult hit = mc.level.clip(ctx);
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /** Ведро в ХОТБАРЕ: empty=true — пустое, иначе с водой. -1 если нет. */
    private static int findInHotbar(LocalPlayer player, boolean empty) {
        var item = empty ? Items.BUCKET : Items.WATER_BUCKET;
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    private static void reset() {
        placed = false;
        waterPos = null;
        pickupTicks = 0;
    }

    private static void selectSlot(Minecraft mc, int slot) {
        LocalPlayer player = mc.player;
        if (player == null || slot < 0 || player.getInventory().getSelectedSlot() == slot) return;
        player.getInventory().setSelectedSlot(slot);
        if (player.connection != null) {
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
    }
}
