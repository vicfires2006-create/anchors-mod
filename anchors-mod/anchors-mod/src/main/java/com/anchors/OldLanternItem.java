package com.anchors;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * Старый фонарь: ПКМ включает/выключает.
 * Пока он включён и лежит в руке, он даёт силу, но увеличивает "Угасание" мира.
 */
public class OldLanternItem extends Item {
    private static final String TAG_ACTIVE = "Active";

    public OldLanternItem(Properties properties) {
        super(properties);
    }

    public static boolean isActive(ItemStack stack) {
        if (!(stack.getItem() instanceof OldLanternItem)) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(TAG_ACTIVE);
    }

    /** Держит ли игрок включённый фонарь в любой руке. */
    public static boolean holdsActive(Player player) {
        return isActive(player.getMainHandItem()) || isActive(player.getOffhandItem());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        boolean nowActive = !isActive(stack);
        stack.getOrCreateTag().putBoolean(TAG_ACTIVE, nowActive);

        level.playSound(null, player.blockPosition(),
                nowActive ? SoundEvents.FIRECHARGE_USE : SoundEvents.FIRE_EXTINGUISH,
                SoundSource.PLAYERS, 0.7F, 0.8F);

        if (!level.isClientSide) {
            player.displayClientMessage(
                    Component.translatable(nowActive ? "message.anchors.lantern_on" : "message.anchors.lantern_off"),
                    true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Блеск (как у зачарованных предметов), пока фонарь включён. */
    @Override
    public boolean isFoil(ItemStack stack) {
        return isActive(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.anchors.old_lantern.desc")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
