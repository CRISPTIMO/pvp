package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import com.pvphelper.PvpHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Метка цели: существо, которое ты ударил, подсвечивается КРАСНОЙ обводкой
 * (как от спектральной стрелы) на N секунд — видно сквозь стены.
 *
 * Чисто клиентское: ставим glowing-флаг локально и держим цель в клиентской
 * скорборд-команде с красным цветом (цвет обводки в MC берётся из команды).
 *
 * ВРЕМЕННО (для теста): метит ЛЮБЫХ живых существ, а не только игроков —
 * чтобы можно было проверить подсветку на мобах. Плюс лог в onHit для
 * диагностики (долетает ли удар до фичи).
 */
public final class HitMark {

    private static final String TEAM_NAME = "pvph_mark";

    // id сущности -> оставшиеся тики подсветки
    private static final Map<Integer, Integer> marked = new HashMap<>();
    // id сущности -> имя команды, в которой цель была до метки (null = без команды)
    private static final Map<Integer, String> prevTeam = new HashMap<>();

    // Бит 6 в DATA_SHARED_FLAGS — флаг свечения. На клиенте его надо ставить
    // напрямую (setGlowingTag на клиенте не работает — см. заметку в applyGlowFrame).
    private static final int GLOWING_FLAG = 6;
    private static Method setSharedFlag;
    private static boolean reflectFailed = false;

    private HitMark() {}

    /** Зовётся из AttackEntityEvent: мы ударили существо. */
    public static void onHit(Entity target) {
        if (!PvpConfig.HIT_MARK_ENABLED.get()) return;
        if (!(target instanceof LivingEntity)) return; // ВРЕМЕННО: любые живые, не только игроки
        // Своих (у кого есть мод / в команде) не метим.
        if (target instanceof net.minecraft.world.entity.player.Player p
                && Friends.isFriend(p.getScoreboardName())) return;
        marked.put(target.getId(), PvpConfig.HIT_MARK_SECONDS.get() * 20);
    }

    public static void tick(Minecraft mc) {
        if (marked.isEmpty()) return;
        if (mc.level == null) { marked.clear(); prevTeam.clear(); return; }

        Iterator<Map.Entry<Integer, Integer>> it = marked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> e = it.next();
            Entity ent = mc.level.getEntity(e.getKey());
            int left = e.getValue() - 1;

            if (left <= 0 || !(ent instanceof LivingEntity le) || !le.isAlive()
                    || !PvpConfig.HIT_MARK_ENABLED.get()) {
                if (ent != null) unmark(mc, ent);
                prevTeam.remove(e.getKey());
                it.remove();
                continue;
            }
            e.setValue(left);
            apply(mc, ent);
        }
    }

    /**
     * Ставит glowing КАЖДЫЙ КАДР перед отрисовкой мира (ViewportEvent).
     * ВАЖНО: вызывать setGlowingTag() на клиенте БЕСПОЛЕЗНО — он делает
     * setSharedFlag(6, isCurrentlyGlowing()), а isCurrentlyGlowing() на клиенте
     * читает тот же флаг 6 (ещё false) и ставит его обратно в false. Поэтому
     * ставим синхро-флаг 6 напрямую через protected setSharedFlag (рефлексия).
     * Сервер перетирает флаг синхронизацией — потому переставляем каждый кадр.
     */
    public static void applyGlowFrame(Minecraft mc) {
        if (marked.isEmpty() || mc.level == null) return;
        for (Integer id : marked.keySet()) {
            Entity ent = mc.level.getEntity(id);
            if (ent instanceof LivingEntity le && le.isAlive()) {
                setGlowDirect(ent, true);
            }
        }
    }

    /**
     * Прямая установка/снятие синхро-флага свечения (бит 6) на клиенте через
     * рефлексию. setGlowingTag на клиенте не годится НИ для включения, НИ для
     * выключения (см. applyGlowFrame) — потому и ставим, и снимаем этим методом.
     */
    private static void setGlowDirect(Entity ent, boolean on) {
        if (reflectFailed) return;
        try {
            if (setSharedFlag == null) {
                setSharedFlag = Entity.class.getDeclaredMethod("setSharedFlag", int.class, boolean.class);
                setSharedFlag.setAccessible(true);
            }
            setSharedFlag.invoke(ent, GLOWING_FLAG, on);
        } catch (Throwable t) {
            reflectFailed = true;
            PvpHelper.LOGGER.error("HitMark: не удалось изменить glow-флаг рефлексией", t);
        }
    }

    /** Держит цель в красной клиентской команде (цвет обводки). Само свечение — applyGlowFrame. */
    private static void apply(Minecraft mc, Entity ent) {
        setGlowDirect(ent, true);

        Scoreboard sb = mc.level.getScoreboard();
        PlayerTeam mark = sb.getPlayerTeam(TEAM_NAME);
        if (mark == null) {
            mark = sb.addPlayerTeam(TEAM_NAME);
            mark.setColor(ChatFormatting.RED);
        }
        String name = ent.getScoreboardName();
        PlayerTeam current = sb.getPlayersTeam(name);
        if (current != mark) {
            prevTeam.putIfAbsent(ent.getId(), current != null ? current.getName() : null);
            sb.addPlayerToTeam(name, mark);
        }
    }

    /** Снимает подсветку и возвращает цель в прежнюю команду. */
    private static void unmark(Minecraft mc, Entity ent) {
        setGlowDirect(ent, false); // setGlowingTag(false) на клиенте не гасит флаг

        Scoreboard sb = mc.level.getScoreboard();
        String name = ent.getScoreboardName();
        PlayerTeam mark = sb.getPlayerTeam(TEAM_NAME);
        if (mark != null && sb.getPlayersTeam(name) == mark) {
            sb.removePlayerFromTeam(name, mark);
        }
        String prev = prevTeam.get(ent.getId());
        if (prev != null) {
            PlayerTeam old = sb.getPlayerTeam(prev);
            if (old != null) sb.addPlayerToTeam(name, old);
        }
    }
}
