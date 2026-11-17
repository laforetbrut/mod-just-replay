package com.vyrriox.justreplay.forge.mixin;

import com.vyrriox.justreplay.forge.ForgeClient;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.gui.overlay.ForgeLayer;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forge has no event before a HUD layer draws, and its layers are private. Every named layer, vanilla or from a mod,
 * passes through this add method, so that is where the clean HUD check goes. The event that lets mods add layers runs
 * once per mod in load order, so our layers go in after it to stay above all others.
 *
 * @author vyrriox
 */
@Mixin(value = ForgeLayeredDraw.class, remap = false)
public abstract class ForgeLayeredDrawMixin {
    @ModifyVariable(method = "add(Lnet/minecraft/resources/Identifier;Lnet/minecraftforge/client/gui/overlay/ForgeLayer;)Lnet/minecraftforge/client/gui/overlay/ForgeLayeredDraw;",
            at = @At("HEAD"), argsOnly = true)
    private ForgeLayer justreplay$cleanHud(ForgeLayer layer, Identifier name) {
        return ForgeClient.cleanHud(name, layer);
    }

    @Inject(method = "resolveLayers", at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/client/event/ForgeEventFactoryClient;onComputeLayerOrder(Lnet/minecraftforge/client/gui/overlay/ForgeLayeredDraw;)V",
            shift = At.Shift.AFTER))
    private void justreplay$topLayers(CallbackInfoReturnable<ForgeLayeredDraw> cir) {
        ForgeClient.addTopLayers((ForgeLayeredDraw) (Object) this);
    }
}
