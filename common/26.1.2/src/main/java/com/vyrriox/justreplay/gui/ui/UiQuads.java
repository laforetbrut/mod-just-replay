package com.vyrriox.justreplay.gui.ui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

/**
 * A batch of textured, per vertex coloured quads drawn through the GUI render state list. Replaces the immediate
 * mode Tesselator drawing of 1.21.1: the vertices are recorded, then submitted once as one GUI element.
 *
 * <p>The GUI renderer only keeps the order of elements whose bounds overlap, so the bounds must cover every pixel the
 * quads can touch: too small and a neighbour may paint over them, too large only costs a draw call.
 *
 * @author vyrriox
 */
public final class UiQuads implements GuiElementRenderState {
    private static final int STRIDE = 4;

    private final Matrix3x2f pose;
    private final TextureSetup textureSetup;
    private final @Nullable ScreenRectangle scissor;
    /** x, y, u, v of each vertex. */
    private float[] coords;
    private int[] colors;
    private int count;
    private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    private @Nullable ScreenRectangle bounds;

    private UiQuads(Matrix3x2f pose, TextureSetup setup, @Nullable ScreenRectangle scissor, int quads) {
        this.pose = pose;
        this.textureSetup = setup;
        this.scissor = scissor;
        this.coords = new float[quads * 4 * STRIDE];
        this.colors = new int[quads * 4];
    }

    /**
     * Starts a batch sampling {@code texture} with the pose and scissor of the extractor at this moment.
     *
     * @param quads expected number of quads; a batch grows when it gets more
     */
    public static UiQuads begin(GuiGraphicsExtractor g, Identifier texture, int quads) {
        AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(texture);
        return new UiQuads(new Matrix3x2f(g.pose()), TextureSetup.singleTexture(t.getTextureView(), t.getSampler()), g.peekScissorStack(), Math.max(1, quads));
    }

    /** Appends one vertex; four vertices make a quad. */
    public UiQuads v(float x, float y, float u, float v, int argb) {
        if (count == colors.length) {
            colors = Arrays.copyOf(colors, count * 2);
            coords = Arrays.copyOf(coords, count * 2 * STRIDE);
        }
        int o = count * STRIDE;
        coords[o] = x;
        coords[o + 1] = y;
        coords[o + 2] = u;
        coords[o + 3] = v;
        colors[count++] = argb;
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        return this;
    }

    /** Hands the batch to the GUI renderer; nothing is drawn for an empty batch. */
    public void submit(GuiGraphicsExtractor g) {
        if (count < 4) {
            return;
        }
        count -= count % 4;
        ScreenRectangle raw = transformedBounds();
        // A null intersection means the batch is scissored away: the render state drops an element without bounds.
        bounds = scissor != null ? scissor.intersection(raw) : raw;
        g.submitGuiElementRenderState(this);
    }

    /** Whole GUI pixels around the four posed corners, rounded outwards after the pose (it may be fractional). */
    private ScreenRectangle transformedBounds() {
        Vector2f p = new Vector2f();
        float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = -Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            pose.transformPosition((i & 1) == 0 ? minX : maxX, (i & 2) == 0 ? minY : maxY, p);
            left = Math.min(left, p.x);
            top = Math.min(top, p.y);
            right = Math.max(right, p.x);
            bottom = Math.max(bottom, p.y);
        }
        int x = Mth.floor(left), y = Mth.floor(top);
        return new ScreenRectangle(x, y, Mth.ceil(right) - x, Mth.ceil(bottom) - y);
    }

    @Override
    public void buildVertices(VertexConsumer out) {
        for (int i = 0; i < count; i++) {
            int o = i * STRIDE;
            out.addVertexWith2DPose(pose, coords[o], coords[o + 1]).setUv(coords[o + 2], coords[o + 3]).setColor(colors[i]);
        }
    }

    @Override
    public RenderPipeline pipeline() {
        return RenderPipelines.GUI_TEXTURED;
    }

    @Override
    public TextureSetup textureSetup() {
        return textureSetup;
    }

    @Override
    public @Nullable ScreenRectangle scissorArea() {
        return scissor;
    }

    @Override
    public @Nullable ScreenRectangle bounds() {
        return bounds;
    }
}
