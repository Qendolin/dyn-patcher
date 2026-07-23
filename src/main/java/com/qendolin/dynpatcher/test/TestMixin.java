package com.qendolin.dynpatcher.test;

import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(SkyRenderer.class)
public class TestMixin {

    @ModifyConstant(method = "renderSun", constant = @Constant(floatValue = 30.0f))
    private static float changeSunSize(float org) {
        return 100.0f;
    }
}
