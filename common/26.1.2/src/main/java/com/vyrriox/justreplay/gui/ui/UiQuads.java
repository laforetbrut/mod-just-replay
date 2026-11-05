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
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;

/**
 * A batch of textured, per vertex coloured quads drawn through the GUI render state list. Replaces the immediate
 * mode Tesselator drawing of 1.21.1: the vertices are recorded, then submitted once as one GUI element.
 *
 * @author vyrriox
 */
public final class UiQuads implements GuiElementRenderState {
    private final Matrix3x2f pose;
    private final TextureSetup textureSetup;
    private final @Nullable ScreenRectangle scissor;
    private float[] data = new float[5 * 4 * 16];
    private int count;
    private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    private ScreenRectangle bounds;

    private UiQuads(Matrix3x2f pose, TextureSetup setup, @Nullable ScreenRectangle scissor) {
        this.pose = pose;
        this.textureSetup = setup;
        this.scissor = scissor;
    }

    /** Starts a batch sampling {@code texture} with the pose and scissor of the extractor at this moment. */
    public static UiQuads begin(GuiGraphicsExtractor g, Identifier texture) {
        AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(texture);
        return new UiQuads(new Matrix3x2f(g.pose()), TextureSetup.singleTexture(t.getTextureView(), t.getSampler()), g.peekScissorStack());
    }

    /** Appends one vertex; four vertices make a quad. */
    public UiQuads v(float x, float y, float u, float v, int argb) {
        if (count * 5 + 5 > data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        int o = count++ * 5;
        data[o] = x;
        data[o + 1] = y;
        data[o + 2] = u;
        data[o + 3] = v;
        data[o + 4] = Float.intBitsToFloat(argb);
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
        ScreenRectangle raw = new ScreenRectangle((int) Math.floor(minX), (int) Math.floor(minY),
                (int) Math.ceil(maxX - minX) + 1, (int) Math.ceil(maxY - minY) + 1).transformMaxBounds(pose);
        bounds = scissor != null ? scissor.intersection(raw) : raw;
        g.submitGuiElementRenderState(this);
    }

    @Override
    public void buildVertices(VertexConsumer out) {
        for (int i = 0; i < count; i++) {
            int o = i * 5;
            out.addVertexWith2DPose(pose, data[o], data[o + 1]).setUv(data[o + 2], data[o + 3]).setColor(Float.floatToRawIntBits(data[o + 4]));
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
