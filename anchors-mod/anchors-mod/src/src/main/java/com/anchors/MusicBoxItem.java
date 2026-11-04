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
 * Музыкальная шкатулка: ПКМ включает/выключает.
 * Пока она играет в руке, монстры рядом успокаиваются и не нападают,
 * но Угасание мира растёт, а мелодию слышит не только игрок.
 */
public class MusicBoxItem extends Item {
    private static final String TAG_ACTIVE = "Active";

    public MusicBoxItem(Properties properties) {
        super(properties);
    }

    public static boolean isActive(ItemStack stack) {
        if (!(stack.getItem() instanceof MusicBoxItem)) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(TAG_ACTIVE);
    }

    public static boolean holdsActive(Player player) {
        return isActive(player.getMainHandItem()) || isActive(player.getOffhandItem());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        boolean nowActive = !isActive(stack);
        stack.getOrCreateTag().putBoolean(TAG_ACTIVE, nowActive);

        level.playSound(null, player.blockPosition(), SoundEvents.LEVER_CLICK,
                SoundSource.PLAYERS, 0.8F, nowActive ? 0.7F : 0.5F);

        if (!level.isClientSide) {
            player.displayClientMessage(
                    Component.translatable(nowActive ? "message.anchors.music_box_on" : "message.anchors.music_box_off"),
                    true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return isActive(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.anchors.music_box.desc")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
