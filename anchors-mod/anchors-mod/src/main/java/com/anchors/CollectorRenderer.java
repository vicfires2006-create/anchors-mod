package com.anchors;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Рисует Коллекционера моделью человека с его скином. Только для клиента. */
public class CollectorRenderer extends HumanoidMobRenderer<CollectorEntity, HumanoidModel<CollectorEntity>> {

    // Скин лежит рядом с текстурами предметов (так не нужна отдельная папка на GitHub)
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(AnchorsMod.MOD_ID, "textures/item/collector.png");

    /** Во сколько раз выше обычного человека. */
    private static final float SCALE = 1.25F;

    public CollectorRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(CollectorEntity entity) {
        return TEXTURE;
    }

    @Override
    protected void scale(CollectorEntity entity, PoseStack poseStack, float partialTick) {
        poseStack.scale(SCALE, SCALE, SCALE);
    }
}
