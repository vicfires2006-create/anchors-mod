package com.anchors;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Всё, что мод помнит о мире (сохраняется вместе с миром):
 * Угасание, запущен ли таймер, где построены постройки, где алтарь и как идёт ритуал.
 */
public class FadingData extends SavedData {
    private static final String NAME = "anchors_fading";

    // Пороги стадий. Хочешь, чтобы мир угасал медленнее или быстрее: меняй эти числа.
    private static final int[] STAGE_THRESHOLDS = {100, 300, 600, 1000};

    /** Запись о построенной постройке: какого вида (номер в AbandonedStructures.Kind) и где. */
    public record StructureRecord(int kind, BlockPos pos) {}

    private double fading;
    private boolean timerStarted;
    private boolean ritualDone;
    private int ritualProgress;
    private BlockPos altarPos;
    private final List<StructureRecord> structures = new ArrayList<>();

    public static FadingData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(FadingData::load, FadingData::new, NAME);
    }

    public static FadingData load(CompoundTag tag) {
        FadingData data = new FadingData();
        data.fading = tag.getDouble("Fading");
        data.timerStarted = tag.getBoolean("TimerStarted");
        data.ritualDone = tag.getBoolean("RitualDone");
        data.ritualProgress = tag.getInt("RitualProgress");
        if (tag.contains("AltarPos")) {
            data.altarPos = BlockPos.of(tag.getLong("AltarPos"));
        }
        ListTag list = tag.getList("Structures", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            data.structures.add(new StructureRecord(entry.getInt("Kind"), BlockPos.of(entry.getLong("Pos"))));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putDouble("Fading", fading);
        tag.putBoolean("TimerStarted", timerStarted);
        tag.putBoolean("RitualDone", ritualDone);
        tag.putInt("RitualProgress", ritualProgress);
        if (altarPos != null) {
            tag.putLong("AltarPos", altarPos.asLong());
        }
        ListTag list = new ListTag();
        for (StructureRecord record : structures) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("Kind", record.kind());
            entry.putLong("Pos", record.pos().asLong());
            list.add(entry);
        }
        tag.put("Structures", list);
        return tag;
    }

    // ---------- Угасание ----------

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

    // ---------- Таймер (включается, когда кто-то впервые берёт якорь) ----------

    public boolean isTimerStarted() {
        return timerStarted;
    }

    public void startTimer() {
        this.timerStarted = true;
        setDirty();
    }

    // ---------- Ритуал ----------

    public boolean isRitualDone() {
        return ritualDone;
    }

    public void setRitualDone(boolean done) {
        this.ritualDone = done;
        setDirty();
    }

    /** Сколько якорей уже лежит на алтаре (0..3). */
    public int getRitualProgress() {
        return ritualProgress;
    }

    public void setRitualProgress(int progress) {
        this.ritualProgress = progress;
        setDirty();
    }

    public BlockPos getAltarPos() {
        return altarPos;
    }

    public void setAltarPos(BlockPos pos) {
        this.altarPos = pos;
        setDirty();
    }

    // ---------- Постройки ----------

    /** Запоминает постройку. Старая постройка такого же вида забывается. */
    public void addStructure(int kind, BlockPos pos) {
        structures.removeIf(record -> record.kind() == kind);
        structures.add(new StructureRecord(kind, pos));
        setDirty();
    }

    public List<StructureRecord> getStructures() {
        return new ArrayList<>(structures);
    }
}
