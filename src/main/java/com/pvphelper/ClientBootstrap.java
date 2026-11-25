package com.pvphelper;

import com.pvphelper.feature.AimAssist;
import com.pvphelper.feature.CombatAssist;
import com.pvphelper.feature.AutoEat;
import com.pvphelper.feature.AutoFarm;
import com.pvphelper.feature.AutoFish;
import com.pvphelper.feature.AutoRefill;
import com.pvphelper.feature.AutoTool;
import com.pvphelper.feature.AutoTotem;
import com.pvphelper.feature.AutoWaterBucket;
import com.pvphelper.feature.BridgeAssist;
import com.pvphelper.feature.CartBomb;
import com.pvphelper.feature.DurabilityWarning;
import com.pvphelper.feature.ElytraSwap;
import com.pvphelper.feature.HitMark;
import com.pvphelper.feature.MeleeAssist;
import com.pvphelper.feature.PearlThrow;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * Регистрация всех клиентских слушателей событий. Вынесено отдельно,
 * чтобы клиентские классы не грузились на выделенном сервере.
 */
public final class ClientBootstrap {

    private ClientBootstrap() {}

    public static void init(BusGroup modBusGroup) {
        // Клавиши — событие мод-шины (в EventBus 7 регистрируем прямо через .BUS).
        RegisterKeyMappingsEvent.BUS.addListener(KeyBindings::register);

        // Игровые события — глобальная игровая шина через .BUS каждого события.
        TickEvent.ClientTickEvent.Post.BUS.addListener(ClientBootstrap::onClientTick);
        PlayerInteractEvent.LeftClickBlock.BUS.addListener(ClientBootstrap::onLeftClickBlock);
        PlayerInteractEvent.LeftClickEmpty.BUS.addListener(ClientBootstrap::onLeftClickEmpty);
        AttackEntityEvent.BUS.addListener(ClientBootstrap::onAttackEntity);
        InputEvent.InteractionKeyMappingTriggered.BUS.addListener(ClientBootstrap::onInteractionKey);
        AddGuiOverlayLayersEvent.BUS.addListener(HudOverlay::onAddLayers);
        // Запасной путь отрисовки HUD (Chat-оверлей стреляет каждый кадр).
        net.minecraftforge.client.event.CustomizeGuiOverlayEvent.Chat.BUS
            .addListener(HudOverlay::onChatOverlay);
        // Каждый кадр перед рендером мира — переставляем glowing-флаг меток
        // (сервер сбрасывает его синхронизацией; тик тут не успевает).
        net.minecraftforge.client.event.ViewportEvent.ComputeCameraAngles.BUS
            .addListener(ClientBootstrap::onComputeCameraAngles);
    }

    private static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        HudOverlay.ensureRegistered(); // страховка, если событие слоёв ушло до загрузки мода
        ToggleStore.load();            // при первом тике поднимаем сохранённые тумблеры
        handleToggles(mc);
        ToggleStore.saveIfChanged();   // сохраняем, если что-то переключили
        AutoTotem.tick(mc);
        AimAssist.tick(mc);
        AutoFish.tick(mc);
        AutoFarm.tick(mc);
        AutoEat.tick(mc);
        DurabilityWarning.tick(mc);
        MeleeAssist.tick(mc);
        CombatAssist.tick(mc);
        AutoRefill.tick(mc);
        CartBomb.tick(mc);
        HitMark.tick(mc);
        ElytraSwap.tick(mc);
        BridgeAssist.tick(mc);
        AutoWaterBucket.tick(mc);
        com.pvphelper.feature.TeamRelay.tick(mc);
    }

    /** Каждый кадр перед отрисовкой мира: держим glowing-флаг на метках. */
    private static void onComputeCameraAngles(net.minecraftforge.client.event.ViewportEvent.ComputeCameraAngles event) {
        HitMark.applyGlowFrame(Minecraft.getInstance());
    }

    /**
     * Клавиша атаки сработала (ДО отправки пакета удара). Возврат true отменяет
     * действие — так глушим слабые/некритовые клики (отмена AttackEntityEvent
     * для этого не годится: пакет к тому моменту уже ушёл на сервер).
     */
    private static boolean onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.isAttack()) {
            return CombatAssist.onAttackPressed(mc);
        }
        return false;
    }

    // Возврат true отменяет событие (EventBus 7 использует Predicate-слушатели).
    private static boolean onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Minecraft mc = Minecraft.getInstance();
        // Shift+ЛКМ с удочкой в руке — переключение режима рыбалки (не атакуем).
        if (tryCycleFishMode(mc)) {
            return true;
        }
        // В бою (недавний удар или враг рядом — как у авто-еды) с мечом в руке НЕ ломаем
        // блоки: в узком пространстве это сбивало заряд атаки и триггерило свап на инструмент.
        boolean inCombat = AutoEat.isInCombat();
        if (inCombat && mc.player != null
                && mc.player.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS)) {
            return true;
        }
        // Авто-инструмент не трогаем в бою (чтоб не свапал на кирку во время драки).
        if (!inCombat) {
            AutoTool.onMineBlock(mc, event.getLevel().getBlockState(event.getPos()));
        }
        AutoFarm.onLeftClickBlock(mc, event.getPos(), event.getLevel().getBlockState(event.getPos()));
        return false;
    }

    private static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        // Shift+ЛКМ по воздуху с удочкой — тоже переключает режим.
        tryCycleFishMode(Minecraft.getInstance());
    }

    // Возврат true отменяет удар (EventBus 7).
    private static boolean onAttackEntity(AttackEntityEvent event) {
        Minecraft mc = Minecraft.getInstance();
        // Своих (у кого мод / в списке команды) не бьём даже ручным кликом.
        if (event.getTarget() instanceof net.minecraft.world.entity.player.Player tp
                && com.pvphelper.feature.Friends.isFriend(tp.getScoreboardName())) {
            return true; // отменяем удар по своему
        }
        AutoEat.noteCombat(); // мы бьём кого-то — значит, в бою: авто-еда на паузу
        HitMark.onHit(event.getTarget()); // красная метка на ударенного игрока
        AutoTool.onAttackEntity(mc);
        return false;
    }

    /** Возвращает true, если это был жест смены режима рыбалки. */
    private static boolean tryCycleFishMode(Minecraft mc) {
        if (mc.player == null) return false;
        boolean rodInHand = mc.player.getMainHandItem().is(Items.FISHING_ROD);
        if (rodInHand && mc.player.isShiftKeyDown() && AutoFish.enabled) {
            AutoFish.cycleMode(mc);
            return true;
        }
        return false;
    }

    /** Обработка клавиш-переключателей + сообщение в чат о новом состоянии. */
    private static void handleToggles(Minecraft mc) {
        if (KeyBindings.TOGGLE_AUTO_TOTEM.consumeClick()) {
            AutoTotem.enabled = !AutoTotem.enabled;
            notify(mc, "Авто-тотем", AutoTotem.enabled);
        }
        if (KeyBindings.TOGGLE_AUTO_TOOL.consumeClick()) {
            AutoTool.enabled = !AutoTool.enabled;
            notify(mc, "Авто-инструмент", AutoTool.enabled);
        }
        if (KeyBindings.TOGGLE_AIM_ASSIST.consumeClick()) {
            AimAssist.enabled = !AimAssist.enabled;
            notify(mc, "Помощь прицела", AimAssist.enabled);
        }
        if (KeyBindings.TOGGLE_THROW_AIM.consumeClick()) {
            AimAssist.throwAimEnabled = !AimAssist.throwAimEnabled;
            notify(mc, "Наводка метательных", AimAssist.throwAimEnabled);
        }
        if (KeyBindings.TOGGLE_AUTO_FISH.consumeClick()) {
            AutoFish.enabled = !AutoFish.enabled;
            AutoFish.reset();
            notify(mc, "Авто-рыбалка (" + AutoFish.mode.label() + ")", AutoFish.enabled);
            if (AutoFish.enabled && mc.player != null) {
                mc.player.displayClientMessage(Component.literal(
                    "§6[Рыбалка] §7закинь удочку вручную — дальше мод сам"), false);
            }
        }
        if (KeyBindings.TOGGLE_AUTO_FARM.consumeClick()) {
            AutoFarm.enabled = !AutoFarm.enabled;
            notify(mc, "Авто-ферма", AutoFarm.enabled);
        }
        if (KeyBindings.TOGGLE_MELEE_AIM.consumeClick()) {
            MeleeAssist.aimEnabled = !MeleeAssist.aimEnabled;
            notify(mc, "Наводка ближнего боя", MeleeAssist.aimEnabled);
        }
        if (KeyBindings.TOGGLE_COMBAT.consumeClick()) {
            CombatAssist.toggle(mc);
        }
        if (KeyBindings.CYCLE_COMBAT_MODE.consumeClick()) {
            CombatAssist.cycleMode(mc);
        }
        if (KeyBindings.TOGGLE_AUTO_EAT.consumeClick()) {
            AutoEat.enabled = !AutoEat.enabled;
            notify(mc, "Авто-еда", AutoEat.enabled);
        }
        if (KeyBindings.TOGGLE_AUTO_REFILL.consumeClick()) {
            AutoRefill.enabled = !AutoRefill.enabled;
            notify(mc, "Авто-рефилл", AutoRefill.enabled);
        }
        while (KeyBindings.PEARL_THROW.consumeClick()) {
            PearlThrow.throwPearl(mc);
        }
        while (KeyBindings.CART_BOMB.consumeClick()) {
            CartBomb.trigger(mc);
        }
        if (KeyBindings.TOGGLE_BRIDGE.consumeClick()) {
            BridgeAssist.enabled = !BridgeAssist.enabled;
            notify(mc, "Авто-мост", BridgeAssist.enabled);
        }
        if (KeyBindings.TOGGLE_PLAYER_ESP.consumeClick()) {
            HudOverlay.playerEsp = !HudOverlay.playerEsp;
            notify(mc, "Координаты игроков", HudOverlay.playerEsp);
        }
        if (KeyBindings.TOGGLE_AUTO_WATER.consumeClick()) {
            AutoWaterBucket.enabled = !AutoWaterBucket.enabled;
            notify(mc, "Авто-вода (MLG)", AutoWaterBucket.enabled);
        }
    }

    private static void notify(Minecraft mc, String name, boolean on) {
        if (mc.player == null) return;
        String state = on ? "§aВКЛ" : "§cВЫКЛ";
        mc.player.displayClientMessage(Component.literal("§c[ШТРАФОСТАН] §f" + name + ": " + state), true);
        if (on) com.pvphelper.util.Sfx.toggleOn(); else com.pvphelper.util.Sfx.toggleOff();
    }
}
