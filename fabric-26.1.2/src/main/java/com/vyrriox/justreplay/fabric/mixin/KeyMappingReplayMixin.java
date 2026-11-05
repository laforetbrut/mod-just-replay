package com.vyrriox.justreplay.fabric.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.vyrriox.justreplay.fabric.FabricReplayKeys;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * Keeps the playback keys out of the vanilla key map and hands them their presses, see {@link FabricReplayKeys}.
 *
 * @author vyrriox
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingReplayMixin {
    @Shadow
    @Final
    private static Map<InputConstants.Key, List<KeyMapping>> MAP;

    @Inject(method = "resetMapping", at = @At("RETURN"))
    private static void justreplay$hideReplayKeys(CallbackInfo ci) {
        FabricReplayKeys.hide(MAP);
    }

    @Inject(method = "click", at = @At("HEAD"))
    private static void justreplay$click(InputConstants.Key key, CallbackInfo ci) {
        FabricReplayKeys.click(key);
    }

    @Inject(method = "set", at = @At("HEAD"))
    private static void justreplay$set(InputConstants.Key key, boolean down, CallbackInfo ci) {
        FabricReplayKeys.set(key, down);
    }
}
