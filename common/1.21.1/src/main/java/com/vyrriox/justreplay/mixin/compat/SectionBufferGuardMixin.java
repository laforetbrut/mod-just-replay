package com.vyrriox.justreplay.mixin.compat;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.vyrriox.justreplay.mixin.VertexBufferAccessor;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A replay rebuilds the level renderer's sections when it starts and on seeks. A mod that renders extra views of the
 * level with its own list of visible sections (Drop the Beam's camera screens) can then hand the renderer sections
 * whose buffers were released or never uploaded, and drawing one crashes the game. While a replay runs, such a buffer
 * is skipped for that frame instead; the section is drawn again once it is compiled. Not applied with Sodium or
 * Embeddium, which draw sections their own way (see CompatMixinPlugin).
 *
 * @author vyrriox
 */
@Mixin(LevelRenderer.class)
public abstract class SectionBufferGuardMixin {
    private static boolean justreplay$unusable(VertexBuffer buffer) {
        return ReplaySession.isRunning() && (buffer.isInvalid() || ((VertexBufferAccessor) buffer).justreplay$mode() == null);
    }

    @Redirect(method = "renderSectionLayer", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;bind()V"))
    private void justreplay$bind(VertexBuffer buffer) {
        if (!justreplay$unusable(buffer)) {
            buffer.bind();
        }
    }

    @Redirect(method = "renderSectionLayer", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V"))
    private void justreplay$draw(VertexBuffer buffer) {
        if (!justreplay$unusable(buffer)) {
            buffer.draw();
        }
    }
}
