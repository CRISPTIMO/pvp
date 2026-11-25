package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Авто-тотем: если в левой руке (offhand) нет тотема бессмертия,
 * автоматически перекладывает тотем из инвентаря в offhand через
 * симуляцию кликов в меню инвентаря (работает даже с закрытым GUI).
 */
public final class AutoTotem {

    public static boolean enabled = true;

    // Слот offhand в меню InventoryMenu игрока.
    private static final int OFFHAND_MENU_SLOT = 45;

    // Чтобы предупреждение «тотемы кончились» не спамило каждый тик.
    private static boolean warnedNoTotems = false;

    private AutoTotem() {}

    public static void tick(Minecraft mc) {
        if (!enabled || !PvpConfig.AUTO_TOTEM_ENABLED.get()) return;

        LocalPlayer player = mc.player;
        MultiPlayerGameMode gm = mc.gameMode;
        if (player == null || gm == null) return;

        // Не вмешиваемся, когда открыт сторонний контейнер (сундук, крафт и т.п.).
        if (mc.screen != null) return;

        // Уже есть тотем в руке — ничего не делаем.
        if (player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            warnedNoTotems = false; // тотем снова есть — предупреждение перевзводим
            return;
        }

        int invIndex = findTotem(player);
        if (invIndex < 0) {
            // Тотемов больше нет — громко предупреждаем (один раз, не спамим).
            if (!warnedNoTotems) {
                warnedNoTotems = true;
                com.pvphelper.util.Sfx.alarm();
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§6[Тотем] §c§lтотемы кончились!"), false);
            }
            return;
        }
        warnedNoTotems = false;

        int menuSlot = inventoryToMenuSlot(invIndex);
        if (menuSlot < 0) return;

        int containerId = player.inventoryMenu.containerId;

        // Перекладываем: взять тотем -> положить в offhand -> вернуть остаток на место.
        gm.handleInventoryMouseClick(containerId, menuSlot, 0, ClickType.PICKUP, player);
        gm.handleInventoryMouseClick(containerId, OFFHAND_MENU_SLOT, 0, ClickType.PICKUP, player);
        gm.handleInventoryMouseClick(containerId, menuSlot, 0, ClickType.PICKUP, player);
    }

    private static int findTotem(LocalPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Преобразует индекс в Inventory игрока в номер слота меню InventoryMenu.
     * Хотбар 0-8 -> 36-44, основной 9-35 -> 9-35, offhand 40 -> 45.
     */
    private static int inventoryToMenuSlot(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) return 36 + invIndex;   // хотбар
        if (invIndex >= 9 && invIndex <= 35) return invIndex;        // основной инвентарь
        if (invIndex == 40) return OFFHAND_MENU_SLOT;                // offhand
        return -1;
    }
}
