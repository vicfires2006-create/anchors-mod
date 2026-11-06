package com.anchors;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * Пока шкатулка играет в руке, все враждебные существа в небольшом радиусе замирают:
 * не двигаются, не атакуют и не отвечают, даже если их ударить.
 * Как только шкатулка выключена (или игрок отошёл), они оживают.
 */
public final class MusicBoxFreeze {

    /** Радиус действия шкатулки (в блоках). */
    private static final double RADIUS = 8.0;

    /** Через сколько тиков без обновления существо размораживается. */
    private static final int RELEASE_AFTER_TICKS = 12;

    private static final String TAG_FROZEN = "anchors_frozen";

    private static final Map<Mob, Integer> FROZEN = new HashMap<>();

    private MusicBoxFreeze() {
    }

    /** Замораживает всех врагов рядом с игроком. Вызывается каждые несколько тиков, пока шкатулка играет. */
    public static void freezeAround(ServerPlayer player, ServerLevel level, int nowTick) {
        AABB area = player.getBoundingBox().inflate(RADIUS);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, area)) {
            if (!(mob instanceof Enemy)) continue;

            boolean ours = FROZEN.containsKey(mob);
            if (mob.isNoAi() && !ours) continue; // существо и так без ИИ (не наша работа)

            mob.setNoAi(true);
            mob.setTarget(null);
            mob.getNavigation().stop();
            mob.getPersistentData().putBoolean(TAG_FROZEN, true);
            FROZEN.put(mob, nowTick);
        }
    }

    /** Размораживает тех, кого давно не обновляли (шкатулку выключили или игрок ушёл). */
    public static void release(int nowTick) {
        Iterator<Map.Entry<Mob, Integer>> it = FROZEN.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Mob, Integer> entry = it.next();
            Mob mob = entry.getKey();
            if (mob.isRemoved() || nowTick - entry.getValue() > RELEASE_AFTER_TICKS) {
                if (!mob.isRemoved()) {
                    unfreeze(mob);
                }
                it.remove();
            }
        }
    }

    /** Размораживает всех (перед остановкой сервера). */
    public static void releaseAll() {
        for (Mob mob : FROZEN.keySet()) {
            if (!mob.isRemoved()) {
                unfreeze(mob);
            }
        }
        FROZEN.clear();
    }

    /** Если существо загрузилось из сохранения замороженным (например, игра закрылась в этот момент), размораживаем. */
    public static void releaseSaved(Entity entity) {
        if (entity instanceof Mob mob && mob.getPersistentData().getBoolean(TAG_FROZEN)) {
            unfreeze(mob);
        }
    }

    private static void unfreeze(Mob mob) {
        mob.setNoAi(false);
        mob.getPersistentData().remove(TAG_FROZEN);
    }
}
