package com.vyrriox.justreplay.gui.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vyrriox.justreplay.JustReplay;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Low level drawing of the glass interface: anti-aliased rounded rectangles, outlines, soft shadows and icons.
 *
 * <p>Shapes are drawn in real framebuffer pixels whatever the GUI scale: each corner radius gets a small texture
 * generated once (a rounded square with analytic anti-aliasing), drawn as a nine-slice in a single draw call with
 * the colour (and an optional vertical gradient) carried by the vertices.
 *
 * @author vyrriox
 */
public final class Paint {
    private static final int FILL = 0;
    private static final int RING = 1;
    private static final int SOFT = 2;

    private static final Long2ObjectOpenHashMap<ResourceLocation> SHAPES = new Long2ObjectOpenHashMap<>();
    /** Scratch arrays of the nine-slice, render thread only. */
    private static final float[] XS = new float[4];
    private static final float[] YS = new float[4];
    private static final float[] US = new float[6];
    private static final float[] VS = new float[6];
    private static final int[] ROW_COLORS = new int[4];

    private Paint() {
    }

    public static double scale() {
        return Minecraft.getInstance().getWindow().getGuiScale();
    }

    /** Thickness of a hairline border in real pixels. */
    private static int hairline(double s) {
        return Math.max(1, (int) Math.round(s * 0.5));
    }

    // ---------------------------------------------------------------- shapes

    public static void rect(GuiGraphics g, float x, float y, float w, float h, float radius, int color) {
        rect(g, x, y, w, h, radius, color, color);
    }

    /** Rounded rectangle with a vertical gradient from {@code top} to {@code bottom}. */
    public static void rect(GuiGraphics g, float x, float y, float w, float h, float radius, int top, int bottom) {
        if (w <= 0 || h <= 0 || (top >>> 24) == 0 && (bottom >>> 24) == 0) {
            return;
        }
        double s = scale();
        int x0 = (int) Math.round(x * s), y0 = (int) Math.round(y * s);
        int x1 = (int) Math.round((x + w) * s), y1 = (int) Math.round((y + h) * s);
        int min = Math.min(x1 - x0, y1 - y0);
        if (min <= 0) {
            return;
        }
        int r = (int) Math.min(Math.round(radius * s), min / 2);
        draw(g, shape(FILL, r, r, 0), r, x0, y0, x1, y1, s, top, bottom);
    }

    /** Hairline border inside the rectangle. */
    public static void outline(GuiGraphics g, float x, float y, float w, float h, float radius, int color) {
        outline(g, x, y, w, h, radius, color, color);
    }

    public static void outline(GuiGraphics g, float x, float y, float w, float h, float radius, int top, int bottom) {
        if (w <= 0 || h <= 0) {
            return;
        }
        double s = scale();
        int x0 = (int) Math.round(x * s), y0 = (int) Math.round(y * s);
        int x1 = (int) Math.round((x + w) * s), y1 = (int) Math.round((y + h) * s);
        int min = Math.min(x1 - x0, y1 - y0);
        int t = hairline(s);
        if (min < 2 * t + 1) {
            draw(g, shape(FILL, 0, 0, 0), 0, x0, y0, x1, y1, s, top, bottom);
            return;
        }
        int r = (int) Math.min(Math.round(radius * s), min / 2);
        int c = Math.max(r, t);
        draw(g, shape(RING, c, r, t), c, x0, y0, x1, y1, s, top, bottom);
    }

    /** Soft shadow spreading {@code blur} GUI pixels around the rectangle. */
    public static void shadow(GuiGraphics g, float x, float y, float w, float h, float radius, float blur, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        double s = scale();
        int b = Math.max(1, (int) Math.round(blur * s));
        int x0 = (int) Math.round(x * s), y0 = (int) Math.round(y * s);
        int x1 = (int) Math.round((x + w) * s), y1 = (int) Math.round((y + h) * s);
        int min = Math.min(x1 - x0, y1 - y0);
        if (min <= 0) {
            return;
        }
        int r = (int) Math.min(Math.round(radius * s), min / 2);
        int c = r + b;
        draw(g, shape(SOFT, c, c, b), c, x0 - b, y0 - b, x1 + b, y1 + b, s, color, color);
    }

    public static void circle(GuiGraphics g, float cx, float cy, float radius, int color) {
        rect(g, cx - radius, cy - radius, radius * 2, radius * 2, radius, color);
    }

    /** Floating glass surface: shadow, smoked tint, light glass gradient and a lit border. */
    public static void glass(GuiGraphics g, float x, float y, float w, float h, float radius) {
        shadow(g, x, y + 1, w, h, radius, 5, Theme.SHADOW);
        surface(g, x, y, w, h, radius, 1.0F);
    }

    /** Glass without the shadow; {@code alpha} fades the whole surface. */
    public static void surface(GuiGraphics g, float x, float y, float w, float h, float radius, float alpha) {
        rect(g, x, y, w, h, radius, Theme.fade(Glass.FILL_TOP, alpha), Theme.fade(Glass.FILL_BOTTOM, alpha));
        outline(g, x, y, w, h, radius, Theme.fade(Glass.EDGE_TOP, alpha), Theme.fade(Glass.EDGE_BOTTOM, alpha));
    }

    /** Pre-composited colours of the default glass (tint under a light layer), one draw instead of two. */
    private static final class Glass {
        static final int FILL_TOP = over(Theme.TINT, Theme.GLASS_TOP);
        static final int FILL_BOTTOM = over(Theme.TINT, Theme.GLASS_BOTTOM);
        static final int EDGE_TOP = 0x66FFFFFF;
        static final int EDGE_BOTTOM = 0x26FFFFFF;
    }

    /** Single colour equivalent to drawing {@code layer} over {@code base}. */
    public static int over(int base, int layer) {
        float a1 = (base >>> 24) / 255.0F, a2 = (layer >>> 24) / 255.0F;
        float a = 1.0F - (1.0F - a1) * (1.0F - a2);
        if (a <= 0.0F) {
            return 0;
        }
        int r = (int) (((layer >> 16 & 0xFF) * a2 + (base >> 16 & 0xFF) * a1 * (1.0F - a2)) / a);
        int gr = (int) (((layer >> 8 & 0xFF) * a2 + (base >> 8 & 0xFF) * a1 * (1.0F - a2)) / a);
        int b = (int) (((layer & 0xFF) * a2 + (base & 0xFF) * a1 * (1.0F - a2)) / a);
        return Math.round(a * 255.0F) << 24 | r << 16 | gr << 8 | b;
    }

    // ---------------------------------------------------------------- icons and text

    /** Draws an icon in a square of {@code size} GUI pixels, tinted with {@code color}. */
    public static void icon(GuiGraphics g, Icon icon, float x, float y, float size, int color) {
        if (icon == null || (color >>> 24) == 0) {
            return;
        }
        double s = scale();
        int p = Math.max(4, (int) Math.round(size * s));
        int x0 = (int) Math.round(x * s), y0 = (int) Math.round(y * s);
        Icons.Atlas atlas = Icons.atlas(p);
        int col = icon.ordinal() % Icons.COLUMNS, row = icon.ordinal() / Icons.COLUMNS;
        float u0 = col * p / (float) atlas.width(), v0 = row * p / (float) atlas.height();
        float u1 = (col + 1) * p / (float) atlas.width(), v1 = (row + 1) * p / (float) atlas.height();
        float inv = (float) (1.0 / s);
        prepare(atlas.location());
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float ax = x0 * inv, ay = y0 * inv, bx = (x0 + p) * inv, by = (y0 + p) * inv;
        b.addVertex(m, ax, ay, 0).setUv(u0, v0).setColor(color);
        b.addVertex(m, ax, by, 0).setUv(u0, v1).setColor(color);
        b.addVertex(m, bx, by, 0).setUv(u1, v1).setColor(color);
        b.addVertex(m, bx, ay, 0).setUv(u1, v0).setColor(color);
        finish(b);
    }

    /** Icon centred in a box. */
    public static void iconCentered(GuiGraphics g, Icon icon, float x, float y, float w, float h, float size, int color) {
        icon(g, icon, x + (w - size) / 2.0F, y + (h - size) / 2.0F, size, color);
    }

    /** Text cut with an ellipsis to fit {@code maxWidth}. */
    public static String ellipsize(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        int dots = font.width("...");
        if (maxWidth <= dots) {
            return "";
        }
        return font.plainSubstrByWidth(text, maxWidth - dots) + "...";
    }

    /** Glass tooltip near the mouse, kept inside the screen. */
    public static void tooltip(GuiGraphics g, Font font, List<FormattedCharSequence> lines, int mouseX, int mouseY, float alpha) {
        if (lines.isEmpty() || alpha <= 0.01F) {
            return;
        }
        int w = 0;
        for (FormattedCharSequence line : lines) {
            w = Math.max(w, font.width(line));
        }
        int h = lines.size() * 10 - 2;
        int x = mouseX + 10;
        int y = mouseY - 4 - h;
        if (x + w + 6 > g.guiWidth() - 2) {
            x = Math.max(4, mouseX - 10 - w - 6);
        }
        if (y < 4) {
            y = mouseY + 14;
        }
        if (y + h + 6 > g.guiHeight() - 2) {
            y = g.guiHeight() - h - 8;
        }
        g.pose().pushPose();
        g.pose().translate(0, (1.0F - alpha) * 3.0F, 400);
        shadow(g, x - 4, y - 4, w + 8, h + 8, Theme.RADIUS_S, 4, Theme.fade(Theme.SHADOW, alpha));
        rect(g, x - 4, y - 4, w + 8, h + 8, Theme.RADIUS_S, Theme.fade(0xE6161B24, alpha), Theme.fade(0xE60F131A, alpha));
        outline(g, x - 4, y - 4, w + 8, h + 8, Theme.RADIUS_S, Theme.fade(0x59FFFFFF, alpha), Theme.fade(0x1FFFFFFF, alpha));
        int ty = y;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, x, ty, Theme.text(Theme.TEXT, alpha), false);
            ty += 10;
        }
        g.pose().popPose();
    }

    public static void tooltip(GuiGraphics g, Font font, Component text, int mouseX, int mouseY) {
        tooltip(g, font, font.split(text, 220), mouseX, mouseY, 1.0F);
    }

    // ---------------------------------------------------------------- textures

    private static void prepare(ResourceLocation texture) {
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
    }

    private static void finish(BufferBuilder b) {
        MeshData mesh = b.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /**
     * Nine-slice of a shape texture of size 2c+1: the corners map 1:1 to real pixels, the edges and the centre
     * stretch the middle texel row and column.
     */
    private static void draw(GuiGraphics g, ResourceLocation texture, int c, int x0, int y0, int x1, int y1, double s,
                             int top, int bottom) {
        int n = 2 * c + 1;
        float inv = (float) (1.0 / s);
        XS[0] = x0;
        XS[1] = x0 + c;
        XS[2] = x1 - c;
        XS[3] = x1;
        YS[0] = y0;
        YS[1] = y0 + c;
        YS[2] = y1 - c;
        YS[3] = y1;
        float mid = (c + 0.5F) / n;
        US[0] = 0.0F;
        US[1] = c / (float) n;
        US[2] = mid;
        US[3] = mid;
        US[4] = (c + 1) / (float) n;
        US[5] = 1.0F;
        System.arraycopy(US, 0, VS, 0, 6);
        float height = Math.max(1, y1 - y0);
        for (int i = 0; i < 4; i++) {
            ROW_COLORS[i] = top == bottom ? top : Theme.mix(top, bottom, (YS[i] - y0) / height);
        }
        prepare(texture);
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int j = 0; j < 3; j++) {
            if (YS[j + 1] <= YS[j]) {
                continue;
            }
            float ya = YS[j] * inv, yb = YS[j + 1] * inv;
            float va = VS[2 * j], vb = VS[2 * j + 1];
            int ca = ROW_COLORS[j], cb = ROW_COLORS[j + 1];
            for (int i = 0; i < 3; i++) {
                if (XS[i + 1] <= XS[i]) {
                    continue;
                }
                float xa = XS[i] * inv, xb = XS[i + 1] * inv;
                float ua = US[2 * i], ub = US[2 * i + 1];
                b.addVertex(m, xa, ya, 0).setUv(ua, va).setColor(ca);
                b.addVertex(m, xa, yb, 0).setUv(ua, vb).setColor(cb);
                b.addVertex(m, xb, yb, 0).setUv(ub, vb).setColor(cb);
                b.addVertex(m, xb, ya, 0).setUv(ub, va).setColor(ca);
            }
        }
        finish(b);
    }

    /**
     * Texture of a rounded square of size 2c+1 with corner radius {@code r}: filled, a ring of thickness
     * {@code param}, or a soft blob fading over {@code param} pixels.
     */
    private static ResourceLocation shape(int kind, int c, int r, int param) {
        long key = (long) kind << 48 | (long) c << 32 | (long) r << 16 | param;
        ResourceLocation cached = SHAPES.get(key);
        if (cached != null) {
            return cached;
        }
        int n = 2 * c + 1;
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, n, n, false);
        float half = n / 2.0F;
        float inner = half - r;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float qx = Math.abs(i + 0.5F - half) - inner;
                float qy = Math.abs(j + 0.5F - half) - inner;
                float ox = Math.max(qx, 0.0F), oy = Math.max(qy, 0.0F);
                float sd = (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0.0F) - r;
                float a;
                if (kind == FILL) {
                    a = Anim.clamp01(0.5F - sd);
                } else if (kind == RING) {
                    a = Anim.clamp01(0.5F - sd) - Anim.clamp01(0.5F - (sd + param));
                } else {
                    float t = Anim.clamp01(-sd / (2.0F * param));
                    a = t * t * (3.0F - 2.0F * t);
                }
                int alpha = Math.round(Anim.clamp01(a) * 255.0F);
                image.setPixelRGBA(i, j, alpha << 24 | 0xFFFFFF);
            }
        }
        DynamicTexture texture = new DynamicTexture(image);
        texture.setFilter(true, false);
        ResourceLocation location = JustReplay.id("ui/shape_" + kind + "_" + c + "_" + r + "_" + param);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        SHAPES.put(key, location);
        return location;
    }
}
