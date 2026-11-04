package com.anchors;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * Тряпичная кукла. Лежит в инвентаре (в любом слоте) и ждёт.
 * Если игрок получает смертельный удар, кукла принимает его на себя.
 * С каждым разом она трескается и всё больше похожа на хозяина. После третьего раза рассыпается.
 * Сама логика спасения находится в ModEvents.onLivingDamage.
 */
public class RagDollItem extends Item {
    public static final int MAX_CRACKS = 3;
    private static final String TAG_CRACKS = "Cracks";

    public RagDollItem(Properties properties) {
        super(properties);
    }

    public static int getCracks(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getInt(TAG_CRACKS) : 0;
    }

    public static void setCracks(ItemStack stack, int cracks) {
        stack.getOrCreateTag().putInt(TAG_CRACKS, cracks);
    }

    /** Ищет куклу в инвентаре игрока (основные слоты и вторая рука). */
    public static ItemStack find(Player player) {
        Inventory inv = player.getInventory();
        for (ItemStack stack : inv.items) {
            if (stack.getItem() instanceof RagDollItem) return stack;
        }
        for (ItemStack stack : inv.offhand) {
            if (stack.getItem() instanceof RagDollItem) return stack;
        }
        return ItemStack.EMPTY;
    }

    /** Название меняется по мере того, как кукла трескается. */
    @Override
    public Component getName(ItemStack stack) {
        int cracks = getCracks(stack);
        return Component.translatable(cracks <= 0
                ? "item.anchors.rag_doll"
                : "item.anchors.rag_doll.cracked_" + Math.min(cracks, MAX_CRACKS - 1));
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.anchors.rag_doll.desc")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tooltip.add(Component.translatable("item.anchors.rag_doll.cracks", getCracks(stack), MAX_CRACKS)
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
