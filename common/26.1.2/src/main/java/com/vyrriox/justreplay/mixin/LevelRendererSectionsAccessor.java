package com.vyrriox.justreplay.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The level renderer's section grid, the occlusion graph built over it and the sections it draws, for
 * {@link com.vyrriox.justreplay.replay.SectionRepair}.
 *
 * @author vyrriox
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererSectionsAccessor {
    @Accessor("viewArea")
    ViewArea justreplay$viewArea();

    @Accessor("sectionOcclusionGraph")
    SectionOcclusionGraph justreplay$graph();

    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> justreplay$visibleSections();
}
