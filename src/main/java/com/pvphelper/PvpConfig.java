package com.pvphelper;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Настройки мода. Хранятся в config/pvphelper-client.toml.
 * Все фичи по умолчанию выключены — включаются клавишами (см. {@link KeyBindings}).
 */
public final class PvpConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue AUTO_TOTEM_ENABLED;
    public static final ForgeConfigSpec.BooleanValue AUTO_TOOL_ENABLED;
    public static final ForgeConfigSpec.BooleanValue AIM_ASSIST_ENABLED;
    public static final ForgeConfigSpec.BooleanValue AUTO_FISH_ENABLED;
    public static final ForgeConfigSpec.BooleanValue AUTO_FARM_ENABLED;
    public static final ForgeConfigSpec.BooleanValue AUTO_EAT_ENABLED;
    public static final ForgeConfigSpec.BooleanValue DURABILITY_WARN_ENABLED;
    public static final ForgeConfigSpec.BooleanValue MELEE_AIM_ENABLED;
    public static final ForgeConfigSpec.BooleanValue AUTO_CRIT_ENABLED;
    public static final ForgeConfigSpec.BooleanValue AUTO_ATTACK_ENABLED;
    public static final ForgeConfigSpec.BooleanValue AUTO_REFILL_ENABLED;
    public static final ForgeConfigSpec.BooleanValue HUD_ENABLED;
    public static final ForgeConfigSpec.BooleanValue SOUNDS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue CART_BOMB_ENABLED;
    public static final ForgeConfigSpec.BooleanValue HIT_MARK_ENABLED;
    public static final ForgeConfigSpec.IntValue HIT_MARK_SECONDS;
    public static final ForgeConfigSpec.BooleanValue AUTO_ELYTRA_ENABLED;
    public static final ForgeConfigSpec.BooleanValue BRIDGE_ENABLED;
    public static final ForgeConfigSpec.BooleanValue AUTO_WATER_ENABLED;
    public static final ForgeConfigSpec.BooleanValue PLAYER_ESP_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> FRIENDS;
    public static final ForgeConfigSpec.BooleanValue RELAY_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> RELAY_URL;
    public static final ForgeConfigSpec.ConfigValue<String> RELAY_TOKEN;
    public static final ForgeConfigSpec.IntValue AUTO_EAT_GAPPLE_HP;

    // Aim assist
    public static final ForgeConfigSpec.DoubleValue AIM_MAX_RANGE;
    public static final ForgeConfigSpec.BooleanValue AIM_TARGET_PLAYERS;
    public static final ForgeConfigSpec.BooleanValue AIM_TARGET_MOBS;

    // PvP-ассист параметры
    public static final ForgeConfigSpec.IntValue AUTO_EAT_HUNGER;
    public static final ForgeConfigSpec.IntValue AUTO_EAT_COMBAT_PAUSE;
    public static final ForgeConfigSpec.IntValue DURABILITY_WARN_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue MELEE_RANGE;
    public static final ForgeConfigSpec.BooleanValue MELEE_TARGET_MOBS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("features");
        AUTO_TOTEM_ENABLED = b
            .comment("Автозамена тотема в левой руке из инвентаря")
            .define("autoTotem", true);
        AUTO_TOOL_ENABLED = b
            .comment("Автовыбор лучшего инструмента при ломании блока / ударе моба")
            .define("autoTool", true);
        AIM_ASSIST_ENABLED = b
            .comment("Помощь в прицеливании для лука / яиц / снежков")
            .define("aimAssist", true);
        AUTO_FISH_ENABLED = b
            .comment("Авто-рыбалка (заброс/подсечка/перезаброс, режимы по Shift+ЛКМ)")
            .define("autoFish", true);
        AUTO_FARM_ENABLED = b
            .comment("Авто-пересадка семян после ломания урожая")
            .define("autoFarm", true);
        AUTO_EAT_ENABLED = b
            .comment("Авто-еда при низкой сытости")
            .define("autoEat", true);
        DURABILITY_WARN_ENABLED = b
            .comment("Предупреждение о низкой прочности брони/инструментов")
            .define("durabilityWarn", true);
        MELEE_AIM_ENABLED = b
            .comment("Наводка на игрока в ближнем бою (пока зажата атака)")
            .define("meleeAim", true);
        AUTO_CRIT_ENABLED = b
            .comment("Разрешить режим боя CRIT: удар засчитывается критом стоя на земле (packet-hop). Палится анти-читами!")
            .define("autoCrit", true);
        AUTO_ATTACK_ENABLED = b
            .comment("Разрешить режим боя FULL: авто-удар по цели при полной зарядке шкалы атаки")
            .define("autoAttack", true);
        AUTO_REFILL_ENABLED = b
            .comment("Авто-подстановка нового стака в опустевший слот хотбара")
            .define("autoRefill", true);
        HUD_ENABLED = b
            .comment("HUD-индикатор активных фич в углу экрана")
            .define("hud", true);
        SOUNDS_ENABLED = b
            .comment("Звуковые сигналы (переключение фич, поклёвка, тревоги)")
            .define("sounds", true);
        CART_BOMB_ENABLED = b
            .comment("ТНТ-комбо по бинду: рельса + вагонетка с ТНТ + стенка + выстрел из лука")
            .define("cartBomb", true);
        HIT_MARK_ENABLED = b
            .comment("Красная подсветка игрока после твоего удара (виден сквозь стены)")
            .define("hitMark", true);
        AUTO_ELYTRA_ENABLED = b
            .comment("Хот-свап элитры: при падении надеть и раскрыть, после приземления вернуть нагрудник")
            .define("autoElytra", true);
        BRIDGE_ENABLED = b
            .comment("Авто-мост: пока включён (клавиша B), ставит блоки под ноги и вперёд по ходу")
            .define("bridge", true);
        AUTO_WATER_ENABLED = b
            .comment("Авто-вода (MLG): при падении ставит воду под себя и забирает после приземления")
            .define("autoWater", true);
        PLAYER_ESP_ENABLED = b
            .comment("HUD: список координат видимых игроков по никам (клавиша Numpad .)")
            .define("playerCoords", true);
        FRIENDS = b
            .comment("Ники своих (команда): подсвечиваются зелёным, показываются первыми в списке координат")
            .defineList("friends",
                java.util.List.of("Zuus", "crisptimo", "tu4kys", "gucci228975",
                    "Weks_Hitler", "OnePunchMen1112", "the_shazyyy"),
                o -> o instanceof String);
        b.pop();

        b.push("aim");
        AIM_MAX_RANGE = b
            .comment("Максимальная дальность захвата цели для лука/метательных (блоки)")
            .defineInRange("maxRange", 48.0, 4.0, 128.0);
        AIM_TARGET_PLAYERS = b.define("targetPlayers", true);
        AIM_TARGET_MOBS = b.define("targetMobs", true);
        MELEE_RANGE = b
            .comment("Дальность наводки в ближнем бою (блоки)")
            .defineInRange("meleeRange", 5.0, 2.0, 12.0);
        MELEE_TARGET_MOBS = b
            .comment("Наводка ближнего боя работает и по мобам (не только по игрокам)")
            .define("meleeTargetMobs", true);
        b.pop();

        b.push("relay");
        RELAY_ENABLED = b
            .comment("Синхронизация координат команды через ваш сервер-релей (координаты где угодно)")
            .define("enabled", true);
        RELAY_URL = b
            .comment("Адрес релея, напр. http://123.45.67.89:8080")
            .define("url", "http://193.164.16.54:8080");
        RELAY_TOKEN = b
            .comment("Секретный токен команды (должен совпадать с TOKEN на сервере)")
            .define("token", "SHTRAFOSTAN-7fK92mLpQ4xZ");
        b.pop();

        b.push("pvpAssist");
        AUTO_EAT_HUNGER = b
            .comment("Порог сытости, при/ниже которого начинается авто-еда (0-20; 14 = после ~3 «ножек»)")
            .defineInRange("autoEatHunger", 14, 0, 20);
        AUTO_EAT_COMBAT_PAUSE = b
            .comment("Пауза авто-еды после боевого контакта, секунд (0 = есть даже в бою)")
            .defineInRange("autoEatCombatPause", 10, 0, 60);
        HIT_MARK_SECONDS = b
            .comment("Длительность красной подсветки ударенного игрока, секунд")
            .defineInRange("hitMarkSeconds", 30, 3, 300);
        AUTO_EAT_GAPPLE_HP = b
            .comment("В бою при ХП <= этого значения авто-съесть золотое яблоко (0 = выкл; 10 = 5 сердец)")
            .defineInRange("gappleHealth", 10, 0, 20);
        DURABILITY_WARN_THRESHOLD = b
            .comment("Порог прочности для предупреждения")
            .defineInRange("durabilityThreshold", 20, 1, 200);
        b.pop();

        SPEC = b.build();
    }

    private PvpConfig() {}
}
