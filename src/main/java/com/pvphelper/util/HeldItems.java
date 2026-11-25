package com.pvphelper.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Помощники распознавания предметов в руке. */
public final class HeldItems {

    private HeldItems() {}

    /**
     * Булава или (модовое) копьё. При них авто-вода и авто-элитра не должны
     * срабатывать — игрок падает НАМЕРЕННО ради мейс-слэма (чем выше падение,
     * тем больше урон булавы), и спасалки только помешают.
     * Копьё определяем по имени предмета (в ваниле копья нет — это модовые).
     */
    public static boolean isMaceOrSpear(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.is(Items.MACE)) return true;
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return path.contains("spear");
    }
}
