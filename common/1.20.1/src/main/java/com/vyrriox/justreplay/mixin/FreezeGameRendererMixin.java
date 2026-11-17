package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.replay.TimeControl;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In a frozen level the rain, the boss darkening and the item activation animation hold, and a camera on a frozen
 * entity stays on its last tick.
 *
 * @author vyrriox
 */
@Mixin(GameRenderer.class)
public abstract class FreezeGameRendererMixin {
    /** Everything from the rain to the end of the method is what a freeze holds. */
    @Inject(method = "tick", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;tickRain(Lnet/minecraft/client/Camera;)V"))
    private void justreplay$holdEffects(CallbackInfo ci) {
        if (TimeControl.isFrozen()) {
            ci.cancel();
        }
    }

    @ModifyArg(method = "renderLevel", index = 4, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V"))
    private float justreplay$cameraPartialTick(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        Entity entity = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        return entity != null && TimeControl.isEntityFrozen(entity) ? 1.0F : partialTick;
    }
}
