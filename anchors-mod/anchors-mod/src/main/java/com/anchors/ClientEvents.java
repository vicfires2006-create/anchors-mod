package com.anchors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Тёмные ночи. Работает только на клиенте (у каждого игрока на своём экране).
 *
 * Хочешь сделать ночь светлее или темнее? Меняй три числа ниже.
 */
public class ClientEvents {

    /** Насколько затемнён экран глубокой ночью БЕЗ фонаря (0.0 = не затемнять, 1.0 = полная чернота). */
    private static final float DARKNESS_WITHOUT_LANTERN = 0.55F;

    /** То же, но когда в руке включённый фонарь. */
    private static final float DARKNESS_WITH_LANTERN = 0.25F;

    /** Как далеко видно ночью без фонаря (в блоках). Дальше всё тонет в чёрном тумане. */
    private static final float NIGHT_FOG_DISTANCE = 28.0F;

    /** Выключить и музыкальные пластинки из проигрывателя? (false = пластинки играют как обычно) */
    private static final boolean MUTE_MUSIC_DISCS = false;

    /** 0 = день или закат, 1 = глубокая ночь. */
    private static float nightFactor(ClientLevel level, float partialTick) {
        float cos = Mth.cos(level.getTimeOfDay(partialTick) * ((float) Math.PI * 2.0F));
        return Mth.clamp(-cos * 1.5F, 0.0F, 1.0F);
    }

    /** Действуют ли тёмные ночи сейчас: только в обычном мире, не в режиме наблюдателя. */
    private static boolean applies(Minecraft mc) {
        return mc.level != null
                && mc.player != null
                && mc.level.dimension() == Level.OVERWORLD
                && !mc.player.isSpectator();
    }

    @Mod.EventBusSubscriber(modid = AnchorsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModBus {
        @SubscribeEvent
        public static void registerOverlays(RegisterGuiOverlaysEvent event) {
            // Чёрная полупрозрачная накладка на мир, под всем интерфейсом (хотбар и чат остаются яркими)
            event.registerBelowAll("night_darkness", (gui, graphics, partialTick, width, height) -> {
                Minecraft mc = Minecraft.getInstance();
                if (!applies(mc)) return;

                float night = nightFactor(mc.level, partialTick);
                if (night <= 0.0F) return;

                boolean lantern = OldLanternItem.holdsActive(mc.player);
                float darkness = night * (lantern ? DARKNESS_WITH_LANTERN : DARKNESS_WITHOUT_LANTERN);

                int alpha = Mth.clamp((int) (darkness * 255.0F), 0, 255);
                if (alpha <= 0) return;

                graphics.fill(0, 0, width, height, alpha << 24);
            });
        }
    }

    @Mod.EventBusSubscriber(modid = AnchorsMod.MOD_ID, value = Dist.CLIENT)
    public static class ForgeBus {

        /** Вся оригинальная фоновая музыка Minecraft отключена: в меню, в мире, в пещерах, под водой. */
        @SubscribeEvent
        public static void onPlaySound(PlaySoundEvent event) {
            SoundInstance sound = event.getSound();
            if (sound == null) return;

            SoundSource source = sound.getSource();
            if (source == SoundSource.MUSIC || (MUTE_MUSIC_DISCS && source == SoundSource.RECORDS)) {
                event.setSound(null);
            }
        }

        /** Ночью без фонаря: густой чёрный туман, видно только вблизи. */
        @SubscribeEvent
        public static void onRenderFog(ViewportEvent.RenderFog event) {
            Minecraft mc = Minecraft.getInstance();
            if (!applies(mc)) return;
            if (event.getType() != FogType.NONE) return;
            if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) return;
            if (OldLanternItem.holdsActive(mc.player)) return;

            float night = nightFactor(mc.level, (float) event.getPartialTick());
            if (night <= 0.0F) return;

            event.setFarPlaneDistance(Mth.lerp(night, event.getFarPlaneDistance(), NIGHT_FOG_DISTANCE));
            event.setNearPlaneDistance(Mth.lerp(night, event.getNearPlaneDistance(), 0.0F));
            event.setCanceled(true); // без этого Minecraft не применяет изменения тумана
        }

        /** Цвет тумана ночью без фонаря: чёрный. */
        @SubscribeEvent
        public static void onFogColor(ViewportEvent.ComputeFogColor event) {
            Minecraft mc = Minecraft.getInstance();
            if (!applies(mc)) return;
            if (OldLanternItem.holdsActive(mc.player)) return;

            float night = nightFactor(mc.level, (float) event.getPartialTick());
            if (night <= 0.0F) return;

            float keep = 1.0F - night * 0.95F;
            event.setRed(event.getRed() * keep);
            event.setGreen(event.getGreen() * keep);
            event.setBlue(event.getBlue() * keep);
        }
    }
}
