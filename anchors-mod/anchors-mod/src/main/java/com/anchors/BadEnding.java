package com.anchors;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;

/**
 * Плохая концовка: время вышло.
 * Игрок замирает, на экране "Время вышло. Он тебя нашёл." и отсчёт от 10.
 * Коллекционер в Т-позе плывёт к нему по воздуху, играет тема. Потом игрока выкидывает из мира,
 * и зайти обратно уже нельзя (если LOCK_WORLD включён).
 */
public final class BadEnding {

    private static final int COUNTDOWN_SECONDS = 10;
    private static final int TOTAL_TICKS = COUNTDOWN_SECONDS * 20;

    /** С какого расстояния начинает приближаться Коллекционер и на каком останавливается (в блоках). */
    private static final double START_DISTANCE = 20.0;
    private static final double END_DISTANCE = 2.5;

    /** true = после концовки мир закрывается насовсем. false = игрока только выкидывает, но войти можно. */
    private static final boolean LOCK_WORLD = true;

    /** Метка на Коллекционере из сцены: если игра закрылась посреди сцены, он при загрузке исчезает. */
    public static final String TAG_SCENE = "anchors_scene_collector";

    private static final class Scene {
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;
        final int startTick;
        final boolean test;
        UUID collectorId;

        Scene(ServerPlayer player, int startTick, boolean test) {
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.yaw = player.getYRot();
            this.pitch = player.getXRot();
            this.startTick = startTick;
            this.test = test;
        }
    }

    private static final Map<UUID, Scene> SCENES = new HashMap<>();

    private BadEnding() {
    }

    public static boolean isActive() {
        return !SCENES.isEmpty();
    }

    /** Запускает сцену для всех игроков в мире. test = true: проверка, мир потом не закрывается. */
    public static void start(MinecraftServer server, boolean test) {
        int now = server.getTickCount();

        if (!test && LOCK_WORLD) {
            // запоминаем сразу, чтобы выход из игры посреди сцены не помог
            FadingData.get(server).setWorldLost(true);
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (SCENES.containsKey(player.getUUID())) continue;

            Scene scene = new Scene(player, now, test);
            ServerLevel level = player.serverLevel();

            CollectorEntity collector = ModEntities.COLLECTOR.get().create(level);
            if (collector != null) {
                collector.getPersistentData().putBoolean(TAG_SCENE, true);
                collector.setNoAi(true);
                collector.setNoGravity(true);
                collector.noPhysics = true;
                collector.setSilent(true);
                scene.collectorId = collector.getUUID();
                SCENES.put(player.getUUID(), scene);
                positionCollector(collector, scene, 0);
                level.addFreshEntity(collector);
            } else {
                SCENES.put(player.getUUID(), scene);
            }

            // игрок замирает
            int duration = TOTAL_TICKS + 60;
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, 6, false, false, false));
            player.addEffect(new MobEffectInstance(MobEffects.JUMP, duration, 128, false, false, false));

            // тема Коллекционера и надпись с отсчётом
            player.playNotifySound(ModSounds.COLLECTOR_THEME.get(), SoundSource.MASTER, 1.0F, 1.0F);
            player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 30, 0));
            player.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.literal("Время вышло. Он тебя нашёл.").withStyle(ChatFormatting.RED)));
            sendNumber(player, COUNTDOWN_SECONDS);
        }
    }

    /** Вызывается каждый тик сервера. */
    public static void tick(MinecraftServer server) {
        if (SCENES.isEmpty()) return;
        int now = server.getTickCount();

        Iterator<Map.Entry<UUID, Scene>> it = SCENES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Scene> entry = it.next();
            Scene scene = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            int elapsed = now - scene.startTick;

            if (player == null) {
                // игрок вышел сам: убираем Коллекционера
                removeCollector(server, scene);
                it.remove();
                continue;
            }

            ServerLevel level = player.serverLevel();

            // экран замирает: держим игрока на месте и не даём повернуть голову
            if (elapsed % 2 == 0) {
                player.connection.teleport(scene.x, scene.y, scene.z, scene.yaw, scene.pitch);
            }

            // Коллекционер медленно приближается
            Entity entity = scene.collectorId == null ? null : level.getEntity(scene.collectorId);
            if (entity instanceof CollectorEntity collector) {
                positionCollector(collector, scene, elapsed);
            }

            // отсчёт: 10, 9, 8 ...
            if (elapsed > 0 && elapsed < TOTAL_TICKS && elapsed % 20 == 0) {
                sendNumber(player, COUNTDOWN_SECONDS - elapsed / 20);
            }

            // конец
            if (elapsed >= TOTAL_TICKS) {
                removeCollector(server, scene);
                it.remove();
                player.connection.disconnect(Component.literal(scene.test
                        ? "Это была проверка плохой концовки. Мир не закрыт, можно зайти снова."
                        : "Коллекционер забрал тебя. Этот мир больше не откроется."));
            }
        }
    }

    public static void clear() {
        SCENES.clear();
    }

    // ---------------------------------------------------------------------

    /** Ставит Коллекционера по прямой перед игроком; чем дольше сцена, тем ближе (сначала медленно, потом быстрее). */
    private static void positionCollector(CollectorEntity collector, Scene scene, int elapsed) {
        double progress = Mth.clamp(elapsed / (double) TOTAL_TICKS, 0.0, 1.0);
        double distance = START_DISTANCE - (START_DISTANCE - END_DISTANCE) * Math.pow(progress, 1.4);

        double yawRad = Math.toRadians(scene.yaw);
        double x = scene.x - Math.sin(yawRad) * distance;
        double z = scene.z + Math.cos(yawRad) * distance;
        double y = scene.y + 0.7 + 0.18 * Math.sin(elapsed * 0.12); // парит и чуть покачивается

        float faceYaw = scene.yaw + 180.0F; // смотрит на игрока
        collector.moveTo(x, y, z, faceYaw, 0.0F);
        collector.setYHeadRot(faceYaw);
        collector.setYBodyRot(faceYaw);
    }

    private static void sendNumber(ServerPlayer player, int number) {
        player.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal(String.valueOf(number)).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
    }

    private static void removeCollector(MinecraftServer server, Scene scene) {
        if (scene.collectorId == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(scene.collectorId);
            if (entity != null) {
                entity.discard();
                return;
            }
        }
    }
}
