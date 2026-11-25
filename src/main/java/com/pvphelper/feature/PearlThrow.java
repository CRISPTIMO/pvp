package com.pvphelper.feature;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Быстрый бросок жемчуга Края по биндингу: берёт перл (из левой руки,
 * хотбара или инвентаря) в главную руку, бросает и возвращает в руку
 * прежний предмет. Всё за один тик — без ручного переключения слотов.
 */
public final class PearlThrow {

    private PearlThrow() {}

    /** Вызывается при нажатии бинда. */
    public static void throwPearl(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || mc.screen != null) return;

        // 1) Перл в левой руке — просто бросаем оттуда, ничего не трогая.
        if (player.getOffhandItem().is(Items.ENDER_PEARL)) {
            mc.gameMode.useItem(player, InteractionHand.OFF_HAND);
            return;
        }

        // 2) Перл в хотбаре — временно выбираем слот, бросаем, возвращаем.
        int hotbar = findInHotbar(player);
        if (hotbar >= 0) {
            int prev = player.getInventory().getSelectedSlot();
            selectSlot(mc, hotbar);
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            selectSlot(mc, prev);
            return;
        }

        // 3) Перл в основном инвентаре — свапаем в текущий слот, бросаем, свапаем назад.
        int invSlot = findInInventory(player);
        if (invSlot >= 0) {
            int cur = player.getInventory().getSelectedSlot();
            int containerId = player.inventoryMenu.containerId;
            mc.gameMode.handleInventoryMouseClick(containerId, invSlot, cur, ClickType.SWAP, player);
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            mc.gameMode.handleInventoryMouseClick(containerId, invSlot, cur, ClickType.SWAP, player);
            return;
        }

        // Перлов нигде нет — говорим об этом, а не молчим.
        com.pvphelper.util.Sfx.deny();
        player.displayClientMessage(
            net.minecraft.network.chat.Component.literal("§6[Перл] §cперлы кончились!"), true);
    }

    private static int findInHotbar(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).is(Items.ENDER_PEARL)) return i;
        }
        return -1;
    }

    private static int findInInventory(LocalPlayer player) {
        for (int i = 9; i <= 35; i++) {
            if (player.getInventory().getItem(i).is(Items.ENDER_PEARL)) return i;
        }
        return -1;
    }

    private static void selectSlot(Minecraft mc, int slot) {
        LocalPlayer player = mc.player;
        if (player.getInventory().getSelectedSlot() == slot) return;
        player.getInventory().setSelectedSlot(slot);
        if (player.connection != null) {
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
    }
}
