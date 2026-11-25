package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Авто-мост (bridging): пока тумблер включён, мод ставит блоки под ноги и
 * на клетку вперёд по ходу движения, чтобы не упасть. Блок берётся из
 * хотбара, слот возвращается. Ставит через клик по грани соседнего блока
 * (как обычная установка), опору ищет среди 6 соседей целевой клетки.
 */
public final class BridgeAssist {

    public static boolean enabled = false; // тумблер (клавиша B)

    // Не чаще одной установки в этот интервал тиков — против спама пакетов.
    private static final int PLACE_COOLDOWN = 2;
    private static int cooldown = 0;
    private static int restoreSlot = -1;
    private static int placedFrom = -1;

    private BridgeAssist() {}

    public static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || mc.gameMode == null || mc.screen != null) return;

        // Отложенный возврат слота с прошлого тика.
        if (restoreSlot >= 0) {
            if (player.getInventory().getSelectedSlot() == placedFrom) selectSlot(mc, restoreSlot);
            restoreSlot = -1;
            placedFrom = -1;
        }

        if (!enabled || !PvpConfig.BRIDGE_ENABLED.get()) return;
        if (cooldown > 0) { cooldown--; return; }

        int slot = findBlock(player);
        if (slot < 0) return;

        // Клетка прямо под ногами.
        BlockPos underFoot = player.blockPosition().below();
        if (tryPlace(mc, player, level, underFoot, slot)) return;

        // Клетка под следующим шагом по направлению движения (или взгляда, если стоим).
        Direction dir = moveDirection(player);
        BlockPos ahead = player.blockPosition().relative(dir).below();
        tryPlace(mc, player, level, ahead, slot);
    }

    /** Пытается поставить блок в target, если она пустая и есть опора-сосед. */
    private static boolean tryPlace(Minecraft mc, LocalPlayer player, Level level,
                                    BlockPos target, int slot) {
        if (!level.getBlockState(target).isAir()) return false;

        // Ищем соседний непустой блок, по грани которого можно кликнуть.
        for (Direction d : Direction.values()) {
            BlockPos support = target.relative(d);
            if (level.getBlockState(support).isAir()) continue;
            if (level.getBlockState(support).getCollisionShape(level, support).isEmpty()) continue;

            Direction face = d.getOpposite();       // грань опоры, обращённая к target
            Vec3 hit = Vec3.atCenterOf(support).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            BlockHitResult brh = new BlockHitResult(hit, face, support, false);

            int prev = player.getInventory().getSelectedSlot();
            selectSlot(mc, slot);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, brh);
            player.swing(InteractionHand.MAIN_HAND);

            if (prev != slot && restoreSlot < 0) { restoreSlot = prev; placedFrom = slot; }
            cooldown = PLACE_COOLDOWN;
            return true;
        }
        return false;
    }

    /** Направление горизонтального движения; если почти стоим — по взгляду. */
    private static Direction moveDirection(LocalPlayer player) {
        Vec3 v = player.getDeltaMovement();
        if (v.x * v.x + v.z * v.z < 1.0e-4) return player.getDirection();
        return Direction.fromYRot(Math.toDegrees(Math.atan2(-v.x, v.z)));
    }

    /** Первый подходящий полный блок в хотбаре, -1 если нет. */
    private static int findBlock(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (isPlaceable(player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    /** Обычный полный блок (не рельса/ТНТ/тотем и т.п.). */
    private static boolean isPlaceable(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) return false;
        if (s.is(Items.TNT)) return false;
        var state = bi.getBlock().defaultBlockState();
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
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
