package com.pvphelper;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Клавиши-переключатели фич. В 1.21.11 категория клавиш — это объект
 * {@link KeyMapping.Category}, а не строка, поэтому регистрируем свою.
 */
public final class KeyBindings {

    private static final KeyMapping.Category CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("pvphelper", "main"));

    private static KeyMapping key(String name, int glfwKey) {
        return new KeyMapping("key.pvphelper." + name, InputConstants.Type.KEYSYM, glfwKey, CATEGORY);
    }

    public static final KeyMapping TOGGLE_AUTO_TOTEM = key("toggle_auto_totem", GLFW.GLFW_KEY_KP_1);
    public static final KeyMapping TOGGLE_AUTO_TOOL  = key("toggle_auto_tool",  GLFW.GLFW_KEY_KP_2);
    public static final KeyMapping TOGGLE_AIM_ASSIST = key("toggle_aim_assist", GLFW.GLFW_KEY_KP_3);
    /** Переключатель (не удержание!) наводки прицела для метательных. */
    public static final KeyMapping TOGGLE_THROW_AIM  = key("toggle_throw_aim",  GLFW.GLFW_KEY_V);
    public static final KeyMapping TOGGLE_AUTO_FISH  = key("toggle_auto_fish",  GLFW.GLFW_KEY_KP_4);
    public static final KeyMapping TOGGLE_AUTO_FARM  = key("toggle_auto_farm",  GLFW.GLFW_KEY_KP_5);
    public static final KeyMapping TOGGLE_MELEE_AIM  = key("toggle_melee_aim",  GLFW.GLFW_KEY_KP_6);
    public static final KeyMapping TOGGLE_COMBAT     = key("toggle_combat",     GLFW.GLFW_KEY_KP_7);
    /** Смена режима боевого ассиста (криты / крит-пакеты / полный заряд). */
    public static final KeyMapping CYCLE_COMBAT_MODE = key("cycle_combat_mode", GLFW.GLFW_KEY_KP_0);
    public static final KeyMapping TOGGLE_AUTO_EAT   = key("toggle_auto_eat",   GLFW.GLFW_KEY_KP_8);
    public static final KeyMapping TOGGLE_AUTO_REFILL= key("toggle_auto_refill",GLFW.GLFW_KEY_KP_9);
    public static final KeyMapping PEARL_THROW       = key("pearl_throw",       GLFW.GLFW_KEY_G);
    /** ТНТ-комбо: рельса + вагонетка с ТНТ + стенка + выстрел из лука. */
    public static final KeyMapping CART_BOMB         = key("cart_bomb",         GLFW.GLFW_KEY_H);
    /** Авто-мост: тумблер построения моста под ногами. */
    public static final KeyMapping TOGGLE_BRIDGE     = key("toggle_bridge",     GLFW.GLFW_KEY_B);
    /** Тумблер списка координат игроков в HUD. */
    public static final KeyMapping TOGGLE_PLAYER_ESP = key("toggle_player_esp", GLFW.GLFW_KEY_KP_DECIMAL);
    /** Тумблер авто-воды (MLG). */
    public static final KeyMapping TOGGLE_AUTO_WATER = key("toggle_auto_water", GLFW.GLFW_KEY_KP_SUBTRACT);

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_AUTO_TOTEM);
        event.register(TOGGLE_AUTO_TOOL);
        event.register(TOGGLE_AIM_ASSIST);
        event.register(TOGGLE_THROW_AIM);
        event.register(TOGGLE_AUTO_FISH);
        event.register(TOGGLE_AUTO_FARM);
        event.register(TOGGLE_MELEE_AIM);
        event.register(TOGGLE_COMBAT);
        event.register(CYCLE_COMBAT_MODE);
        event.register(TOGGLE_AUTO_EAT);
        event.register(TOGGLE_AUTO_REFILL);
        event.register(PEARL_THROW);
        event.register(CART_BOMB);
        event.register(TOGGLE_BRIDGE);
        event.register(TOGGLE_PLAYER_ESP);
        event.register(TOGGLE_AUTO_WATER);
    }

    private KeyBindings() {}
}
