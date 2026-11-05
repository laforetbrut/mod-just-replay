package com.vyrriox.justreplay.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads whether a vertex buffer was ever uploaded (its draw mode is set by the first upload).
 *
 * @author vyrriox
 */
@Mixin(VertexBuffer.class)
public interface VertexBufferAccessor {
    @Accessor("mode")
    VertexFormat.Mode justreplay$mode();
}
