package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Хот-свап элитры: падаешь в пропасть — элитра сама надевается вместо
 * нагрудника и раскрывается. После приземления (или в воде) нагрудник
 * возвращается на место, элитра — обратно в свой слот.
 */
public final class ElytraSwap {

    public static boolean enabled = true; // управляется конфигом (без клавиши)

    // Слот нагрудника в меню InventoryMenu.
    private static final int CHEST_MENU_SLOT = 6;
    // Порог падения: скорость вниз и накопленная дистанция.
    private static final double FALL_VELOCITY = -0.5;
    private static final double FALL_DISTANCE = 2.5;

    // Запуск вверх (винд-чардж/буст): рывок вверх в воздухе. После него даём паузу
    // на то, чтобы взять булаву — если взял, элитру не надеваем (мейс-слэм).
    private static final double LAUNCH_UP_SPEED = 0.5;   // больше обычного прыжка (~0.42)
    private static final int LAUNCH_GRACE_TICKS = 40;    // 2 сек паузы перед элитрой

    /** Инвентарный слот, где лежит снятый нагрудник (= откуда взяли элитру); -1 = свапа не было. */
    private static int storedSlot = -1;
    private static int launchGrace = 0; // тиков паузы элитры после запуска вверх

    private ElytraSwap() {}

    public static void tick(Minecraft mc) {
        if (!enabled || !PvpConfig.AUTO_ELYTRA_ENABLED.get()) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || mc.level == null || mc.screen != null) return;

        // Засекли запуск вверх — взводим 2-секундную паузу перед авто-элитрой.
        if (!player.onGround() && player.getDeltaMovement().y > LAUNCH_UP_SPEED) {
            launchGrace = LAUNCH_GRACE_TICKS;
        } else if (launchGrace > 0) {
            launchGrace--;
        }

        boolean wearingElytra = player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);

        // Мы свапали и уже приземлились/в воде — вернуть нагрудник.
        if (storedSlot >= 0 && wearingElytra && !player.isFallFlying()
                && (player.onGround() || player.isInWater())) {
            swapChest(mc, player, storedSlot);
            storedSlot = -1;
            msg(mc, "§aнагрудник обратно");
            return;
        }

        // Мейс-слэм булавой/копьём — падаем намеренно, элитру не трогаем.
        boolean maceSlam = com.pvphelper.util.HeldItems.isMaceOrSpear(player.getMainHandItem());

        if (wearingElytra) {
            // Элитра уже на груди — при падении раскрываем (если не мейс-слэм).
            if (falling(player) && !maceSlam) tryOpen(player);
            return;
        }

        // После запуска вверх (винд-чардж) ждём паузу: успеешь взять булаву — мейс-слэм,
        // элитру не надеваем. Не взял за это время — надеваем как обычно.
        if (!falling(player) || maceSlam || launchGrace > 0) return;

        // Приоритет воды: если вода применима и под нами есть земля — пусть спасает
        // MLG-водой, элитру не надеваем. Над пропастью (земли нет) — надеваем элитру.
        if (AutoWaterBucket.isAvailable(player) && groundWithin(mc, player, 32.0)) return;

        // Ищем целую элитру в инвентаре (прочность > 1, иначе не полетит).
        int slot = findElytra(player);
        if (slot < 0) return;

        swapChest(mc, player, slot); // элитра на грудь, нагрудник — в её слот
        storedSlot = slot;
        tryOpen(player);
        msg(mc, "§eэлитра!");
    }

    /** Есть ли твёрдая земля под ногами в пределах dist блоков (для приоритета воды). */
    private static boolean groundWithin(Minecraft mc, LocalPlayer player, double dist) {
        net.minecraft.world.phys.Vec3 from = player.position();
        net.minecraft.world.phys.Vec3 to = from.add(0, -dist, 0);
        var ctx = new net.minecraft.world.level.ClipContext(from, to,
            net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE, player);
        return mc.level.clip(ctx).getType() == net.minecraft.world.phys.HitResult.Type.BLOCK;
    }

    /** Реально падаем: в воздухе, без транспорта, летим вниз достаточно быстро. */
    private static boolean falling(LocalPlayer p) {
        return !p.onGround()
            && !p.isInWater()
            && !p.onClimbable()
            && p.getVehicle() == null
            && !p.getAbilities().flying
            && p.getDeltaMovement().y < FALL_VELOCITY
            && p.fallDistance > FALL_DISTANCE;
    }

    /** Раскрыть элитру (эквивалент нажатия прыжка в воздухе). */
    private static void tryOpen(LocalPlayer player) {
        if (player.isFallFlying()) return;
        // Клиентское предсказание (может вернуть false сразу после свапа — слот
        // груди ещё не обновился), но пакет шлём В ЛЮБОМ случае: сервер сам
        // проверит элитру и запустит полёт. Зовётся каждый тик, пока не полетели.
        player.tryToStartFallFlying();
        if (player.connection != null) {
            player.connection.send(new ServerboundPlayerCommandPacket(
                player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
    }

    private static int findElytra(LocalPlayer player) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.is(Items.ELYTRA) && (s.getMaxDamage() - s.getDamageValue()) > 1) {
                return i;
            }
        }
        return -1;
    }

    /** Меняет местами содержимое инвентарного слота и слота нагрудника (3 клика PICKUP). */
    private static void swapChest(Minecraft mc, LocalPlayer player, int invSlot) {
        int menuSlot = inventoryToMenuSlot(invSlot);
        if (menuSlot < 0) return;
        int id = player.inventoryMenu.containerId;
        mc.gameMode.handleInventoryMouseClick(id, menuSlot, 0, ClickType.PICKUP, player);
        mc.gameMode.handleInventoryMouseClick(id, CHEST_MENU_SLOT, 0, ClickType.PICKUP, player);
        mc.gameMode.handleInventoryMouseClick(id, menuSlot, 0, ClickType.PICKUP, player);
    }

    /** Инвентарный индекс -> слот меню InventoryMenu (хотбар 0-8 -> 36-44). */
    private static int inventoryToMenuSlot(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) return 36 + invIndex;
        if (invIndex >= 9 && invIndex <= 35) return invIndex;
        return -1;
    }

    private static void msg(Minecraft mc, String text) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§6[Элитра] §f" + text), true);
        }
    }
}
