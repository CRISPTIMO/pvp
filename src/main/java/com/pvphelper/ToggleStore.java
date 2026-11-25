package com.pvphelper;

import com.pvphelper.feature.AimAssist;
import com.pvphelper.feature.AutoEat;
import com.pvphelper.feature.AutoFarm;
import com.pvphelper.feature.AutoFish;
import com.pvphelper.feature.AutoRefill;
import com.pvphelper.feature.AutoTool;
import com.pvphelper.feature.AutoTotem;
import com.pvphelper.feature.AutoWaterBucket;
import com.pvphelper.feature.BridgeAssist;
import com.pvphelper.feature.CombatAssist;
import com.pvphelper.feature.MeleeAssist;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Сохранение состояния тумблеров фич (клавиши Numpad и т.п.) между сессиями,
 * чтобы не включать всё заново после перезапуска. Пишем в
 * config/pvphelper-toggles.properties. Загрузка — один раз при старте клиента,
 * сохранение — после любого переключения.
 */
public final class ToggleStore {

    private static final Path FILE =
        FMLPaths.CONFIGDIR.get().resolve("pvphelper-toggles.properties");

    private static boolean loaded = false;
    private static String lastSignature = "";

    private ToggleStore() {}

    /** Строка-снимок всех состояний — для дешёвого детекта изменений. */
    private static String signature() {
        return AutoTotem.enabled + "|" + AutoTool.enabled + "|" + AimAssist.enabled + "|"
            + AimAssist.throwAimEnabled + "|" + AutoFish.enabled + "|" + AutoFarm.enabled + "|"
            + MeleeAssist.aimEnabled + "|" + CombatAssist.enabled + "|" + AutoEat.enabled + "|"
            + AutoRefill.enabled + "|" + BridgeAssist.enabled + "|" + HudOverlay.playerEsp + "|"
            + AutoWaterBucket.enabled + "|"
            + AutoFish.mode.name() + "|" + CombatAssist.mode.name();
    }

    /** Сохраняет файл, только если что-то поменялось (зовётся каждый тик — дёшево). */
    public static void saveIfChanged() {
        if (!loaded) return;
        String sig = signature();
        if (!sig.equals(lastSignature)) {
            save();
            lastSignature = sig;
        }
    }

    /** Загружает сохранённые состояния тумблеров (один раз). */
    public static void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(FILE)) return;

        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
        } catch (IOException e) {
            PvpHelper.LOGGER.warn("ToggleStore: не удалось прочитать {}: {}", FILE, e.getMessage());
            return;
        }

        AutoTotem.enabled     = bool(p, "autoTotem",   AutoTotem.enabled);
        AutoTool.enabled      = bool(p, "autoTool",    AutoTool.enabled);
        AimAssist.enabled     = bool(p, "aimAssist",   AimAssist.enabled);
        AimAssist.throwAimEnabled = bool(p, "throwAim", AimAssist.throwAimEnabled);
        AutoFish.enabled      = bool(p, "autoFish",    AutoFish.enabled);
        AutoFarm.enabled      = bool(p, "autoFarm",    AutoFarm.enabled);
        MeleeAssist.aimEnabled= bool(p, "meleeAim",    MeleeAssist.aimEnabled);
        CombatAssist.enabled  = bool(p, "combat",      CombatAssist.enabled);
        AutoEat.enabled       = bool(p, "autoEat",     AutoEat.enabled);
        AutoRefill.enabled    = bool(p, "autoRefill",  AutoRefill.enabled);
        BridgeAssist.enabled  = bool(p, "bridge",      BridgeAssist.enabled);
        HudOverlay.playerEsp  = bool(p, "playerEsp",   HudOverlay.playerEsp);
        AutoWaterBucket.enabled = bool(p, "autoWater", AutoWaterBucket.enabled);

        AutoFish.mode    = enumVal(p, "fishMode",   AutoFish.Mode.class,   AutoFish.mode);
        CombatAssist.mode= enumVal(p, "combatMode", CombatAssist.Mode.class, CombatAssist.mode);

        lastSignature = signature(); // чтобы сразу после загрузки не пересохранять
    }

    /** Сохраняет текущие состояния тумблеров. Зовётся после переключения. */
    public static void save() {
        Properties p = new Properties();
        p.setProperty("autoTotem",  Boolean.toString(AutoTotem.enabled));
        p.setProperty("autoTool",   Boolean.toString(AutoTool.enabled));
        p.setProperty("aimAssist",  Boolean.toString(AimAssist.enabled));
        p.setProperty("throwAim",   Boolean.toString(AimAssist.throwAimEnabled));
        p.setProperty("autoFish",   Boolean.toString(AutoFish.enabled));
        p.setProperty("autoFarm",   Boolean.toString(AutoFarm.enabled));
        p.setProperty("meleeAim",   Boolean.toString(MeleeAssist.aimEnabled));
        p.setProperty("combat",     Boolean.toString(CombatAssist.enabled));
        p.setProperty("autoEat",    Boolean.toString(AutoEat.enabled));
        p.setProperty("autoRefill", Boolean.toString(AutoRefill.enabled));
        p.setProperty("bridge",     Boolean.toString(BridgeAssist.enabled));
        p.setProperty("playerEsp",  Boolean.toString(HudOverlay.playerEsp));
        p.setProperty("autoWater",  Boolean.toString(AutoWaterBucket.enabled));
        p.setProperty("fishMode",   AutoFish.mode.name());
        p.setProperty("combatMode", CombatAssist.mode.name());

        try (OutputStream out = Files.newOutputStream(FILE)) {
            p.store(out, "ШТРАФОСТАН TOOLS — состояние тумблеров фич");
        } catch (IOException e) {
            PvpHelper.LOGGER.warn("ToggleStore: не удалось записать {}: {}", FILE, e.getMessage());
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    private static <E extends Enum<E>> E enumVal(Properties p, String key, Class<E> type, E def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
