package com.anchors;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Сцена хорошей концовки: спокойная мелодия и три "игрока", которые заходят в чат,
 * благодарят и уходят гулять по освобождённому миру.
 */
public final class EndingScene {

    // Имена трёх душ в чате. Можно заменить на любые.
    private static final String MOTHER = "Мать";
    private static final String SON = "Сын";
    private static final String DAUGHTER = "Дочка";

    private record ChatEvent(int dueTick, Component text) {}

    private static final List<ChatEvent> EVENTS = new ArrayList<>();
    private static int musicStart = -1;
    private static int musicEnd = -1;

    // Спокойная мелодия: числа это полутона от базовой ноты, 99 = пауза. Одна нота раз в полсекунды.
    private static final int[] MELODY = {
            0, 4, 7, 4, 9, 7, 4, 2,
            0, 4, 9, 7, 12, 9, 7, 4,
            2, 7, 9, 7, 4, 2, 0, 4,
            7, 4, 2, 0, -3, 0, 4, 99
    };
    private static final int MUSIC_SECONDS = 85;

    private EndingScene() {
    }

    public static void start(MinecraftServer server) {
        int now = server.getTickCount();
        EVENTS.clear();

        joined(now, 4, MOTHER);
        say(now, 7, MOTHER, "Тихо... Я слышу колыбельную. Шкатулка снова играет, и теперь только для нас. Спасибо тебе. Я так долго ждала, что кто-нибудь дойдёт до конца.");

        joined(now, 14, SON);
        say(now, 17, SON, "Фонарь горит ровно, и тени больше не двигаются неправильно. Ты показал нам дорогу домой. Спасибо.");

        joined(now, 25, DAUGHTER);
        say(now, 28, DAUGHTER, "Мне больше не страшно! Ты не бросил мою куклу, и она не дала тебя в обиду. Спасибо-спасибо-спасибо!");

        say(now, 37, MOTHER, "Посмотрите, как всё стало красиво. Дома целые, цветы вернулись, а небо снова светлое. Мы так давно этого не видели.");
        say(now, 46, SON, "Можно, мы пойдём гулять по миру? Я хочу пройти по всем тропам, которые так и не успел найти.");
        say(now, 55, DAUGHTER, "Давайте посмотрим на всё вместе! Спасибо, что освободил нас. Мы будем помнить тебя.");
        say(now, 64, MOTHER, "Живи долго, и пусть тебе всегда будет светло.");

        left(now, 69, MOTHER);
        left(now, 70, SON);
        left(now, 71, DAUGHTER);

        musicStart = now;
        musicEnd = now + MUSIC_SECONDS * 20;
    }

    /** Вызывается каждый тик сервера. */
    public static void tick(MinecraftServer server) {
        int now = server.getTickCount();

        if (!EVENTS.isEmpty()) {
            Iterator<ChatEvent> it = EVENTS.iterator();
            while (it.hasNext()) {
                ChatEvent event = it.next();
                if (event.dueTick() <= now) {
                    server.getPlayerList().broadcastSystemMessage(event.text(), false);
                    it.remove();
                }
            }
        }

        if (musicEnd > now && now % 10 == 0) {
            playNote(server, (now - musicStart) / 10);
        }
    }

    public static void clear() {
        EVENTS.clear();
        musicStart = -1;
        musicEnd = -1;
    }

    private static void joined(int now, int seconds, String name) {
        // Жёлтое сообщение, как у настоящего входа игрока
        Component text = Component.translatable("multiplayer.player.joined", Component.literal(name))
                .withStyle(ChatFormatting.YELLOW);
        EVENTS.add(new ChatEvent(now + seconds * 20, text));
    }

    private static void left(int now, int seconds, String name) {
        Component text = Component.translatable("multiplayer.player.left", Component.literal(name))
                .withStyle(ChatFormatting.YELLOW);
        EVENTS.add(new ChatEvent(now + seconds * 20, text));
    }

    private static void say(int now, int seconds, String name, String message) {
        Component text = Component.translatable("chat.type.text", Component.literal(name), Component.literal(message));
        EVENTS.add(new ChatEvent(now + seconds * 20, text));
    }

    private static void playNote(MinecraftServer server, int step) {
        int note = MELODY[step % MELODY.length];
        if (note == 99) return;

        float pitch = (float) Math.pow(2.0, note / 12.0);
        SoundEvent harp = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("block.note_block.harp"));
        SoundEvent bell = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("block.note_block.bell"));

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (harp != null) {
                player.playNotifySound(harp, SoundSource.AMBIENT, 0.6F, pitch);
            }
            // каждые восемь нот добавляется тихий колокольчик
            if (bell != null && step % 8 == 0) {
                player.playNotifySound(bell, SoundSource.AMBIENT, 0.35F, Math.min(2.0F, pitch));
            }
        }
    }
}
