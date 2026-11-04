package com.anchors;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * "Угасание" мира. Хранится вместе с миром (сохраняется при выходе из игры).
 */
public class FadingData extends SavedData {
    private static final String NAME = "anchors_fading";

    // Пороги стадий. Хочешь, чтобы мир угасал медленнее или быстрее: меняй эти числа.
    private static final int[] STAGE_THRESHOLDS = {100, 300, 600, 1000};

    private double fading;

    public static FadingData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(FadingData::load, FadingData::new, NAME);
    }

    public static FadingData load(CompoundTag tag) {
        FadingData data = new FadingData();
        data.fading = tag.getDouble("Fading");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putDouble("Fading", fading);
        return tag;
    }

    public double getFading() {
        return fading;
    }

    public void add(double amount) {
        set(fading + amount);
    }

    public void set(double value) {
        this.fading = Math.max(0, value);
        setDirty();
    }

    /** 0 = мир в порядке, 1..4 = стадии угасания. */
    public int getStage() {
        int stage = 0;
        for (int i = 0; i < STAGE_THRESHOLDS.length; i++) {
            if (fading >= STAGE_THRESHOLDS[i]) {
                stage = i + 1;
            }
        }
        return stage;
    }
}
