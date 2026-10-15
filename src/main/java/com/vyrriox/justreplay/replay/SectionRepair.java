package com.vyrriox.justreplay.replay;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.mixin.LevelRendererSectionsAccessor;
import com.vyrriox.justreplay.mixin.SectionOcclusionGraphAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;

/**
 * Keeps the level renderer's occlusion graph on its current section grid during a replay.
 * <p>
 * A replay changes the view distance as the recorded server and the replay host send theirs, and the level renderer
 * rebuilds its grid when it notices. A mod that renders extra views by swapping its own occlusion graph and visible
 * sections into the level renderer (Drop the Beam's camera screens) can be in the middle of such a render then: the
 * rebuild resets the mod's graph, and the renderer's own graph keeps pointing to the released grid. Nothing of the
 * terrain is drawn from then on, or the game crashes on a released buffer. Checked before every frame, the graph is
 * reset on the current grid when that happened.
 *
 * @author vyrriox
 */
public final class SectionRepair {
    private static int repairs;

    private SectionRepair() {
    }

    public static void beforeFrame(Minecraft mc) {
        if (!ReplaySession.isRunning() || mc.level == null) {
            return;
        }
        LevelRendererSectionsAccessor renderer = (LevelRendererSectionsAccessor) mc.levelRenderer;
        ViewArea area = renderer.justreplay$viewArea();
        SectionOcclusionGraph graph = renderer.justreplay$graph();
        if (area == null || graph == null || ((SectionOcclusionGraphAccessor) graph).justreplay$viewArea() == area) {
            return;
        }
        graph.waitAndReset(area);
        renderer.justreplay$visibleSections().clear();
        if (repairs++ < 5) {
            JustReplay.LOGGER.info("Level renderer sections were left on a released grid by another view; reset on the current one");
        }
    }
}
