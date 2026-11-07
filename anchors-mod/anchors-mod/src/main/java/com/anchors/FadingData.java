package com.anchors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
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
    private boolean worldLost;
    private boolean crossPlaced;

    /** Блоки, которые глюки по-настоящему удалили (деревья и постройки). После ритуала они возвращаются. */
    private final List<BlockPos> removedPositions = new ArrayList<>();
    private final List<BlockState> removedStates = new ArrayList<>();
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
        data.worldLost = tag.getBoolean("WorldLost");
        data.crossPlaced = tag.getBoolean("CrossPlaced");

        ListTag palette = tag.getList("RemovedPalette", Tag.TAG_COMPOUND);
        List<BlockState> paletteStates = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) {
            paletteStates.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), palette.getCompound(i)));
        }
        int[] ids = tag.getIntArray("RemovedIds");
        long[] positions = tag.getLongArray("RemovedPos");
        for (int i = 0; i < ids.length && i < positions.length; i++) {
            if (ids[i] >= 0 && ids[i] < paletteStates.size()) {
                data.removedPositions.add(BlockPos.of(positions[i]));
                data.removedStates.add(paletteStates.get(ids[i]));
            }
        }
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
        tag.putBoolean("WorldLost", worldLost);
        tag.putBoolean("CrossPlaced", crossPlaced);

        ListTag palette = new ListTag();
        Map<BlockState, Integer> paletteIndex = new HashMap<>();
        int[] ids = new int[removedPositions.size()];
        long[] positions = new long[removedPositions.size()];
        for (int i = 0; i < removedPositions.size(); i++) {
            BlockState state = removedStates.get(i);
            Integer index = paletteIndex.get(state);
            if (index == null) {
                index = palette.size();
                paletteIndex.put(state, index);
                palette.add(NbtUtils.writeBlockState(state));
            }
            ids[i] = index;
            positions[i] = removedPositions.get(i).asLong();
        }
        tag.put("RemovedPalette", palette);
        tag.putIntArray("RemovedIds", ids);
        tag.putLongArray("RemovedPos", positions);
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

    // ---------- Перевёрнутый крест в раме над алтарём ----------

    public boolean isCrossPlaced() {
        return crossPlaced;
    }

    public void setCrossPlaced(boolean placed) {
        this.crossPlaced = placed;
        setDirty();
    }

    // ---------- Настоящие удаления глюками ----------

    public int getRemovedCount() {
        return removedPositions.size();
    }

    /** Запоминает блок, который сейчас будет удалён (чтобы вернуть его после ритуала). */
    public void addRemoved(BlockPos pos, BlockState state) {
        removedPositions.add(pos.immutable());
        removedStates.add(state);
        setDirty();
    }

    /** Возвращает все удалённые блоки на место (только там, где сейчас пусто). */
    public void restoreRemoved(ServerLevel level) {
        for (int i = 0; i < removedPositions.size(); i++) {
            BlockPos pos = removedPositions.get(i);
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (level.getBlockState(pos).isAir()) {
                level.setBlock(pos, removedStates.get(i), 2);
            }
        }
        removedPositions.clear();
        removedStates.clear();
        setDirty();
    }

    // ---------- Плохая концовка ----------

    /** Коллекционер забрал мир: войти в него больше нельзя. */
    public boolean isWorldLost() {
        return worldLost;
    }

    public void setWorldLost(boolean lost) {
        this.worldLost = lost;
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
