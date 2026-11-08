package com.anchors;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Время от времени Коллекционер показывается где-то неподалёку, прячась за деревом или стеной,
 * и молча смотрит на игрока. Стоит игроку посмотреть прямо на него, он пропадает.
 * Чем сильнее Угасание, тем чаще он появляется.
 */
public final class CollectorSightings {

    /** Метка на Коллекционере из такого появления: если игра закрылась, при загрузке он исчезает. */
    public static final String TAG_SIGHTING = "anchors_sighting_collector";

    /** Шанс появления за секунду в промилле, по стадиям Угасания 1, 2, 3, 4. */
    private static final int[] CHANCE_PERMILLE = {3, 6, 10, 16};
    /** Минимум между появлениями для одного игрока (тики). */
    private static final int COOLDOWN_TICKS = 20 * 60;
    /** Сколько он стоит, если игрок так и не посмотрел на него (тики). */
    private static final int MAX_LIFETIME_TICKS = 20 * 45;
    /** Ближе этого расстояния он пропадает сам (в блоках). */
    private static final double VANISH_DISTANCE = 9.0;
    /** Насколько точно нужно смотреть на него (скалярное произведение; 0.965 примерно 15 градусов). */
    private static final double LOOK_THRESHOLD = 0.965;

    private record Sighting(UUID collectorId, int spawnTick) {}

    private static final Map<UUID, Sighting> ACTIVE = new HashMap<>();
    private static final Map<UUID, Integer> READY_AT = new HashMap<>();

    private CollectorSightings() {
    }

    public static boolean isTracked(UUID collectorId) {
        for (Sighting sighting : ACTIVE.values()) {
            if (sighting.collectorId().equals(collectorId)) return true;
        }
        return false;
    }

    /** Раз в секунду для каждого игрока: иногда показывает Коллекционера. */
    public static void secondTick(ServerPlayer player, ServerLevel level, int stage) {
        if (stage < 1) return;
        if (level.dimension() != Level.OVERWORLD) return;
        if (BadEnding.isActive()) return;

        UUID id = player.getUUID();
        if (ACTIVE.containsKey(id)) return;
        int tick = level.getServer().getTickCount();
        if (tick < READY_AT.getOrDefault(id, 0)) return;

        int permille = CHANCE_PERMILLE[Math.min(stage, 4) - 1];
        if (level.random.nextInt(1000) >= permille) return;

        spawn(player, level, tick);
    }

    /** Показывает Коллекционера прямо сейчас (для проверки командой /sighting). Возвращает false, если не нашлось места. */
    public static boolean spawnNow(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        UUID id = player.getUUID();
        if (ACTIVE.containsKey(id)) return true;
        return spawn(player, level, level.getServer().getTickCount());
    }

    /** Каждые два тика: поворачивает Коллекционера к игроку и убирает, если на него посмотрели. */
    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty() || server.getTickCount() % 2 != 0) return;
        int now = server.getTickCount();

        Iterator<Map.Entry<UUID, Sighting>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Sighting> entry = it.next();
            Sighting sighting = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());

            if (player == null) {
                discard(server, sighting.collectorId());
                it.remove();
                continue;
            }

            ServerLevel level = player.serverLevel();
            Entity entity = level.getEntity(sighting.collectorId());
            if (!(entity instanceof CollectorEntity collector) || collector.isRemoved()) {
                READY_AT.put(entry.getKey(), now + COOLDOWN_TICKS);
                it.remove();
                continue;
            }

            faceTowards(collector, player);

            boolean seen = isLookingAt(player, collector);
            boolean tooClose = collector.distanceTo(player) < VANISH_DISTANCE;
            boolean expired = now - sighting.spawnTick() > MAX_LIFETIME_TICKS;

            if (seen || tooClose || expired || BadEnding.isActive()) {
                vanish(player, level, collector);
                READY_AT.put(entry.getKey(), now + COOLDOWN_TICKS);
                it.remove();
            }
        }
    }

    /** Убирает Коллекционера игрока, который вышел из игры. */
    public static void removeFor(ServerPlayer player) {
        Sighting sighting = ACTIVE.remove(player.getUUID());
        if (sighting != null && player.getServer() != null) {
            discard(player.getServer(), sighting.collectorId());
        }
        READY_AT.remove(player.getUUID());
    }

    /** Убирает всех (после ритуала: мир освобождён). */
    public static void removeAll(MinecraftServer server) {
        for (Sighting sighting : ACTIVE.values()) {
            discard(server, sighting.collectorId());
        }
        ACTIVE.clear();
    }

    public static void clear() {
        ACTIVE.clear();
        READY_AT.clear();
    }

    // ---------------------------------------------------------------------

    private static boolean spawn(ServerPlayer player, ServerLevel level, int tick) {
        RandomSource random = level.random;
        Vec3 look = player.getLookAngle();
        double lookAngle = Math.atan2(look.z, look.x);

        for (int attempt = 0; attempt < 14; attempt++) {
            // сбоку от взгляда игрока: сначала почти не видно, но стоит повернуть голову, и он там
            double side = random.nextBoolean() ? 1.0 : -1.0;
            double angle = lookAngle + Math.toRadians(55 + random.nextDouble() * 35) * side;
            double distance = 22 + random.nextDouble() * 18;
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);

            BlockPos ground = GlitchEffects.surfaceAt(level, x, z);
            if (ground == null) continue;

            // он прячется: первые попытки берём только места, где между ним и игроком есть укрытие
            boolean covered = hasCoverToward(level, ground.above(), player);
            if (!covered && attempt < 8) continue;

            CollectorEntity collector = ModEntities.COLLECTOR.get().create(level);
            if (collector == null) return false;
            collector.getPersistentData().putBoolean(TAG_SIGHTING, true);
            collector.setNoAi(true);
            collector.setSilent(true);
            collector.moveTo(x + 0.5, ground.getY() + 1.0, z + 0.5, 0.0F, 0.0F);
            faceTowards(collector, player);

            ACTIVE.put(player.getUUID(), new Sighting(collector.getUUID(), tick));
            level.addFreshEntity(collector);
            return true;
        }
        return false;
    }

    /** Есть ли в одном-двух блоках от точки, в сторону игрока, дерево или стена (на высоте груди и головы). */
    private static boolean hasCoverToward(ServerLevel level, BlockPos spot, ServerPlayer player) {
        double dx = player.getX() - (spot.getX() + 0.5);
        double dz = player.getZ() - (spot.getZ() + 0.5);
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0) return false;
        dx /= length;
        dz /= length;

        for (int step = 1; step <= 2; step++) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos pos = BlockPos.containing(spot.getX() + 0.5 + dx * step, spot.getY() + dy, spot.getZ() + 0.5 + dz * step);
                if (level.hasChunkAt(pos) && !level.getBlockState(pos).isAir()) return true;
            }
        }
        return false;
    }

    private static void faceTowards(CollectorEntity collector, ServerPlayer player) {
        double dx = player.getX() - collector.getX();
        double dz = player.getZ() - collector.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        collector.setYRot(yaw);
        collector.setYHeadRot(yaw);
        collector.setYBodyRot(yaw);
    }

    /** Смотрит ли игрок прямо на Коллекционера (и ничто не мешает видеть). */
    private static boolean isLookingAt(ServerPlayer player, CollectorEntity collector) {
        Vec3 toTarget = collector.getEyePosition().subtract(player.getEyePosition()).normalize();
        double dot = player.getLookAngle().dot(toTarget);
        return dot > LOOK_THRESHOLD && player.hasLineOfSight(collector);
    }

    private static void vanish(ServerPlayer player, ServerLevel level, CollectorEntity collector) {
        level.sendParticles(player, ParticleTypes.LARGE_SMOKE, false,
                collector.getX(), collector.getY() + 1.2, collector.getZ(), 25, 0.4, 0.9, 0.4, 0.02);
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("entity.enderman.teleport"));
        if (sound != null) {
            player.playNotifySound(sound, SoundSource.AMBIENT, 0.5F, 0.5F);
        }
        collector.discard();
    }

    private static void discard(MinecraftServer server, UUID collectorId) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(collectorId);
            if (entity != null) {
                entity.discard();
                return;
            }
        }
    }
}
