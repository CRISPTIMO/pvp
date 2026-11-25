package com.pvphelper;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * PvP Helper — клиентский мод-помощник для PvP на Minecraft 1.21.11 (Forge).
 * Фичи: авто-тотем, авто-инструмент, помощь в прицеливании.
 */
@Mod(PvpHelper.MODID)
public final class PvpHelper {
    public static final String MODID = "pvphelper";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PvpHelper(FMLJavaModLoadingContext context) {
        var modBusGroup = context.getModBusGroup();

        // Конфиг клиентский — храним настройки в config/pvphelper-client.toml.
        context.registerConfig(ModConfig.Type.CLIENT, PvpConfig.SPEC);

        // Весь функционал чисто клиентский. На выделенном сервере не грузим клиентские классы.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientBootstrap.init(modBusGroup);
            LOGGER.info("PvP Helper: клиентская часть инициализирована");
        }
    }
}
