package com.pvphelper.util;

import com.pvphelper.PvpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * Звуковые сигналы мода (клиентские, слышит только игрок).
 * Единый набор, чтобы события узнавались на слух:
 *  - toggleOn/toggleOff — включение/выключение фичи (плинг вверх/вниз);
 *  - mode  — смена режима (короткий клик);
 *  - bite  — поклёвка;
 *  - alarm — тревога «кончились тотемы» (колокол);
 *  - deny  — «нельзя/кончилось» (виллджер "нет").
 * Отключаются конфигом sounds.
 */
public final class Sfx {

    private Sfx() {}

    private static void play(SoundEvent sound, float volume, float pitch) {
        if (!PvpConfig.SOUNDS_ENABLED.get()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.playSound(sound, volume, pitch);
    }

    public static void toggleOn()  { play(SoundEvents.NOTE_BLOCK_PLING.value(), 0.7f, 1.6f); }
    public static void toggleOff() { play(SoundEvents.NOTE_BLOCK_PLING.value(), 0.7f, 0.6f); }
    public static void mode()      { play(SoundEvents.NOTE_BLOCK_HAT.value(),   0.7f, 1.4f); }
    public static void bite()      { play(SoundEvents.EXPERIENCE_ORB_PICKUP,    0.6f, 1.4f); }
    public static void alarm()     { play(SoundEvents.NOTE_BLOCK_BELL.value(),  1.0f, 0.6f); }
    public static void deny()      { play(SoundEvents.VILLAGER_NO,              0.8f, 1.0f); }
}
