package com.pvphelper.feature;

import com.pvphelper.PvpConfig;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Реестр «своих» (команда). База берётся из конфига (PvpConfig.FRIENDS),
 * плюс можно добавлять/убирать ники в рантайме (клиентская команда трекинга).
 * Сверка ников — без учёта регистра.
 */
public final class Friends {

    // Ники, добавленные в этой сессии поверх конфига.
    private static final Set<String> runtimeAdded = new LinkedHashSet<>();

    private Friends() {}

    public static boolean isFriend(String name) {
        if (name == null) return false;
        // Главное: у кого есть мод (шлёт координаты в релей) — тот свой.
        if (TeamRelay.knows(name)) return true;
        // Плюс ручной список из конфига и добавленные в рантайме (фолбэк без релея).
        for (String f : PvpConfig.FRIENDS.get()) {
            if (f.equalsIgnoreCase(name)) return true;
        }
        for (String f : runtimeAdded) {
            if (f.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** Добавить ник в трекинг на время сессии. true, если реально добавлен. */
    public static boolean add(String name) {
        if (name == null || name.isBlank() || isFriend(name)) return false;
        return runtimeAdded.add(name);
    }

    /** Убрать ник, добавленный в рантайме. */
    public static boolean remove(String name) {
        return runtimeAdded.removeIf(f -> f.equalsIgnoreCase(name));
    }
}
