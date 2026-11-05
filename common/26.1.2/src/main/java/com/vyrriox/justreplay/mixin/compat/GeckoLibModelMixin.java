package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ReplayCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GeckoLib entities animate on their tick count plus the partial tick, so during a replay they run at x1 whatever the
 * speed and restart after a seek. The frame time they get is replaced by the replay clock. The whole frame time is
 * replaced rather than the tick, because the clock already holds the partial tick. {@code GeoEntity.getTick} is left
 * alone: GeckoLib's spawn animations compare it with an age. Client thread only. Optional: does nothing when GeckoLib
 * is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "software.bernie.geckolib.model.GeoModel", remap = false)
public abstract class GeckoLibModelMixin {
    @Unique
    private static final String justreplay$HANDLE_ANIMATIONS =
            "handleAnimations(Lsoftware/bernie/geckolib/animatable/GeoAnimatable;JLsoftware/bernie/geckolib/animation/AnimationState;F)V";
    @Unique
    private static Class<?> justreplay$replacedEntity;
    @Unique
    private static boolean justreplay$onEntity;

    @Inject(method = justreplay$HANDLE_ANIMATIONS, at = @At("HEAD"), require = 0, remap = false)
    private void justreplay$readAnimatable(@Coerce Object animatable, long instanceId, @Coerce Object state, float partialTick, CallbackInfo ci) {
        justreplay$onEntity = animatable instanceof Entity || justreplay$isReplacedEntity(animatable);
    }

    @ModifyVariable(method = justreplay$HANDLE_ANIMATIONS, at = @At("STORE"), ordinal = 0, require = 0, remap = false)
    private double justreplay$replayFrameTime(double frameTime) {
        if (!justreplay$onEntity || !Minecraft.getInstance().isSameThread()) {
            return frameTime;
        }
        return ReplayCompat.geckoEntityFrameTime(frameTime);
    }

    @Unique
    private static boolean justreplay$isReplacedEntity(Object animatable) {
        if (justreplay$replacedEntity == null) {
            try {
                justreplay$replacedEntity = Class.forName("software.bernie.geckolib.animatable.GeoReplacedEntity", false,
                        animatable.getClass().getClassLoader());
            } catch (ReflectiveOperationException | LinkageError e) {
                justreplay$replacedEntity = Void.class;
            }
        }
        return justreplay$replacedEntity.isInstance(animatable);
    }
}
