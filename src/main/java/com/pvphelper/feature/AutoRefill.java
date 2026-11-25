package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Авто-рефилл хотбара: если стак в слоте хотбара кончился (стал пустым),
 * автоматически подставляет в этот же слот такой же предмет из инвентаря,
 * если он там есть. Полезно, когда во время боя/стройки заканчиваются блоки.
 */
public final class AutoRefill {

    public static boolean enabled = false;

    // Что лежало в каждом слоте хотбара в прошлом тике (чтобы знать, что закончилось).
    private static final Item[] lastItem = new Item[9];

    private AutoRefill() {}

    public static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) return;

        boolean active = enabled && PvpConfig.AUTO_REFILL_ENABLED.get();
        // Пока открыт инвентарь (или фича выкл) — НЕ рефилим, но память слотов
        // держим актуальной: если игрок сам убрал предмет из слота, он запомнится
        // как «пусто» и после закрытия инвентаря НЕ будет возвращён.
        if (!active || mc.screen != null) {
            for (int i = 0; i < 9; i++) {
                ItemStack s = player.getInventory().getItem(i);
                lastItem[i] = s.isEmpty() ? null : s.getItem();
            }
            return;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack cur = player.getInventory().getItem(i);
            if (cur.isEmpty() && lastItem[i] != null) {
                refill(mc, player, i, lastItem[i]);
                // не сбрасываем lastItem — на след. тике обновим по факту
            }
            ItemStack after = player.getInventory().getItem(i);
            lastItem[i] = after.isEmpty() ? null : after.getItem();
        }
    }

    private static void refill(Minecraft mc, LocalPlayer player, int hotbarSlot, Item item) {
        // Ищем такой же предмет в основном инвентаре (слоты меню 9-35).
        for (int menuSlot = 9; menuSlot <= 35; menuSlot++) {
            ItemStack s = player.getInventory().getItem(menuSlot);
            if (s.is(item)) {
                int containerId = player.inventoryMenu.containerId;
                mc.gameMode.handleInventoryMouseClick(containerId, menuSlot, hotbarSlot, ClickType.SWAP, player);
                return;
            }
        }
    }
}
