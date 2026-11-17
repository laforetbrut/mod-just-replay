package com.vyrriox.justreplay.mixin;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The section grid an occlusion graph was last reset for.
 *
 * @author vyrriox
 */
@Mixin(SectionOcclusionGraph.class)
public interface SectionOcclusionGraphAccessor {
    @Accessor("viewArea")
    net.minecraft.client.renderer.ViewArea justreplay$viewArea();
}
