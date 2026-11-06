package com.anchors;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;

/** Модель Коллекционера: человек в Т-позе (руки разведены в стороны, ноги вместе), не ходит, а парит. */
public class CollectorModel extends HumanoidModel<CollectorEntity> {

    public CollectorModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(CollectorEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        // руки строго в стороны (поворот на 90 градусов)
        float quarterTurn = (float) (Math.PI / 2.0);
        this.rightArm.xRot = 0.0F;
        this.rightArm.yRot = 0.0F;
        this.rightArm.zRot = quarterTurn;
        this.leftArm.xRot = 0.0F;
        this.leftArm.yRot = 0.0F;
        this.leftArm.zRot = -quarterTurn;

        // ноги вместе и прямо
        this.rightLeg.xRot = 0.0F;
        this.rightLeg.yRot = 0.0F;
        this.rightLeg.zRot = 0.0F;
        this.leftLeg.xRot = 0.0F;
        this.leftLeg.yRot = 0.0F;
        this.leftLeg.zRot = 0.0F;
    }
}
