package com.vyrriox.justreplay.gui.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.vyrriox.justreplay.JustReplay;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Procedural icon set. Every icon is a signed distance field on a 24 unit grid (outline style, rounded strokes);
 * an atlas is rasterised with analytic anti-aliasing once per real pixel size, so icons stay crisp at any GUI scale.
 *
 * @author vyrriox
 */
public final class Icons {
    static final int COLUMNS = 8;
    private static final Int2ObjectOpenHashMap<Atlas> ATLASES = new Int2ObjectOpenHashMap<>();
    private static final Sdf[] SHAPES = new Sdf[Icon.values().length];
    /** Half width of strokes in grid units, set for the atlas being rasterised. */
    private static float hw = 1.0F;

    record Atlas(ResourceLocation location, int width, int height) {
    }

    @FunctionalInterface
    private interface Sdf {
        float d(float x, float y);
    }

    private Icons() {
    }

    static Atlas atlas(int px) {
        Atlas atlas = ATLASES.get(px);
        if (atlas == null) {
            atlas = build(px);
            ATLASES.put(px, atlas);
        }
        return atlas;
    }

    private static Atlas build(int px) {
        Icon[] icons = Icon.values();
        int rows = (icons.length + COLUMNS - 1) / COLUMNS;
        int w = COLUMNS * px, h = rows * px;
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, true);
        // Strokes never thinner than about one real pixel.
        hw = Math.max(1.0F, 0.62F * 24.0F / px);
        // Shapes are drawn up to one unit from the edge with a half stroke of one unit: thicker strokes at small
        // sizes would bleed out of the cell, so the grid is widened around its centre to keep them inside.
        float zoom = (24.0F + 2.0F * Math.max(0.0F, hw - 1.0F)) / 24.0F;
        float unit = 24.0F * zoom / px;
        for (Icon icon : icons) {
            Sdf sdf = shape(icon);
            int ox = icon.ordinal() % COLUMNS * px, oy = icon.ordinal() / COLUMNS * px;
            for (int j = 0; j < px; j++) {
                for (int i = 0; i < px; i++) {
                    float gx = 12.0F + ((i + 0.5F) * unit - 12.0F * zoom);
                    float gy = 12.0F + ((j + 0.5F) * unit - 12.0F * zoom);
                    float d = sdf.d(gx, gy) / unit;
                    int a = Math.round(Anim.clamp01(0.5F - d) * 255.0F);
                    image.setPixelRGBA(ox + i, oy + j, a << 24 | 0xFFFFFF);
                }
            }
        }
        DynamicTexture texture = new DynamicTexture(image);
        texture.setFilter(true, false);
        ResourceLocation location = JustReplay.id("ui/icons_" + px);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        return new Atlas(location, w, h);
    }

    private static Sdf shape(Icon icon) {
        Sdf cached = SHAPES[icon.ordinal()];
        if (cached == null) {
            cached = define(icon);
            SHAPES[icon.ordinal()] = cached;
        }
        return cached;
    }

    // ---------------------------------------------------------------- icon definitions

    private static Sdf define(Icon icon) {
        return switch (icon) {
            case PLAY -> round(fill(8, 6, 18.5F, 12, 8, 18), 1.2F);
            case PAUSE -> union(box(6, 5, 4.5F, 14, 1.3F), box(13.5F, 5, 4.5F, 14, 1.3F));
            case SKIP_START -> union(round(fill(18, 6.5F, 10, 12, 18, 17.5F), 1.0F), box(5.5F, 5.5F, 2.8F, 13, 1.2F));
            case SKIP_END -> union(round(fill(6, 6.5F, 14, 12, 6, 17.5F), 1.0F), box(15.7F, 5.5F, 2.8F, 13, 1.2F));
            case REWIND -> union(round(fill(12, 7, 4.5F, 12, 12, 17), 0.8F), round(fill(20, 7, 12.5F, 12, 20, 17), 0.8F));
            case FORWARD -> union(round(fill(12, 7, 19.5F, 12, 12, 17), 0.8F), round(fill(4, 7, 11.5F, 12, 4, 17), 0.8F));
            case CAMERA -> union(strokeBox(2.5F, 6.5F, 13, 11, 2.5F), closed(15.5F, 10.5F, 21.5F, 7, 21.5F, 17, 15.5F, 13.5F));
            case CUT -> union(ring(6, 6, 3), ring(6, 18, 3), seg(20, 4, 8.2F, 15.8F), seg(14.5F, 14.5F, 20, 20), seg(8.2F, 8.2F, 12, 12));
            case KEY -> round(fill(12, 5, 19, 12, 12, 19, 5, 12), 1.5F);
            case TRASH -> union(seg(3.5F, 6.5F, 20.5F, 6.5F), open(9, 6.5F, 9, 3.8F, 15, 3.8F, 15, 6.5F),
                    open(5.5F, 6.5F, 6.6F, 20.2F, 17.4F, 20.2F, 18.5F, 6.5F), seg(10, 10.5F, 10, 16), seg(14, 10.5F, 14, 16));
            case EYE -> union(ellipse(12, 12, 10, 6.5F), ring(12, 12, 3));
            case EYE_OFF -> union(gap(union(ellipse(12, 12, 10, 6.5F), ring(12, 12, 3)), 4, 4, 20, 20), seg(4, 4, 20, 20));
            case PLUS -> union(seg(12, 5, 12, 19), seg(5, 12, 19, 12));
            case MINUS -> seg(5, 12, 19, 12);
            case GEAR -> gear();
            case FILM -> union(strokeBox(3.5F, 3.5F, 17, 17, 2.5F), seg(8, 3.5F, 8, 20.5F), seg(16, 3.5F, 16, 20.5F),
                    seg(3.5F, 12, 20.5F, 12), seg(3.5F, 7.75F, 8, 7.75F), seg(3.5F, 16.25F, 8, 16.25F),
                    seg(16, 7.75F, 20.5F, 7.75F), seg(16, 16.25F, 20.5F, 16.25F));
            case FOLDER -> closed(3, 19, 21, 19, 21, 8.5F, 12, 8.5F, 10, 5.5F, 3, 5.5F);
            case SPEAKER -> union(closed(3.5F, 9.5F, 7.5F, 9.5F, 12.5F, 5, 12.5F, 19, 7.5F, 14.5F, 3.5F, 14.5F),
                    arc(12.5F, 12, 4.5F, -50, 50), arc(12.5F, 12, 8.5F, -50, 50));
            case SPEAKER_OFF -> union(closed(3.5F, 9.5F, 7.5F, 9.5F, 12.5F, 5, 12.5F, 19, 7.5F, 14.5F, 3.5F, 14.5F),
                    seg(16, 9.5F, 21, 14.5F), seg(21, 9.5F, 16, 14.5F));
            case FLAG -> union(seg(5, 21, 5, 3.5F), closed(5, 4, 19, 4, 15.5F, 8.75F, 19, 13.5F, 5, 13.5F));
            case PERSON -> union(ring(12, 7.5F, 4), arc(12, 21.5F, 8, 180, 360));
            case HELP -> union(ring(12, 12, 10), arc(12, 9.3F, 3, 180, 400), open(14.3F, 11.2F, 12, 12.8F, 12, 14),
                    disc(12, 17.3F, 1.4F));
            case SAVE -> union(strokeBox(3.5F, 3.5F, 17, 17, 2.5F), open(7.5F, 3.5F, 7.5F, 8.5F, 15, 8.5F, 15, 3.5F),
                    strokeBox(7, 13, 10, 7.5F, 1));
            case CHECK -> open(5, 12.5F, 10, 17.5F, 19.5F, 7);
            case CLOSE -> union(seg(6, 6, 18, 18), seg(18, 6, 6, 18));
            case CHEVRON_LEFT -> open(15, 5, 8, 12, 15, 19);
            case CHEVRON_RIGHT -> open(9, 5, 16, 12, 9, 19);
            case CHEVRON_DOWN -> open(5, 9, 12, 16, 19, 9);
            case CHEVRON_UP -> open(5, 15, 12, 8, 19, 15);
            case SEARCH -> union(ring(10.5F, 10.5F, 6.5F), seg(15.5F, 15.5F, 20.5F, 20.5F));
            case EDIT -> union(closed(15.5F, 4, 20, 8.5F, 8.5F, 20, 4, 20, 4, 15.5F), seg(13, 6.5F, 17.5F, 11));
            case COPY -> union(strokeBox(8.5F, 8.5F, 12.5F, 12.5F, 2), open(5.5F, 15.5F, 3.5F, 15.5F, 3.5F, 3.5F, 15.5F, 3.5F, 15.5F, 5.5F));
            case TARGET -> union(ring(12, 12, 7.5F), disc(12, 12, 1.8F), seg(12, 1.5F, 12, 5.5F), seg(12, 18.5F, 12, 22.5F),
                    seg(1.5F, 12, 5.5F, 12), seg(18.5F, 12, 22.5F, 12));
            case PATH -> union(ring(5.5F, 18.5F, 2.5F), ring(18.5F, 5.5F, 2.5F), bezier(7.8F, 17.2F, 14, 15.5F, 10, 8.5F, 16.2F, 6.8F));
            case EXPORT -> union(seg(12, 15, 12, 3.5F), open(7.5F, 8, 12, 3.5F, 16.5F, 8), open(4, 14, 4, 20, 20, 20, 20, 14));
            case CLOCK -> union(ring(12, 12, 9.5F), open(12, 6.5F, 12, 12, 15.5F, 14));
            case SORT -> union(seg(7, 4, 7, 20), open(3.5F, 16.5F, 7, 20, 10.5F, 16.5F), seg(17, 20, 17, 4),
                    open(13.5F, 7.5F, 17, 4, 20.5F, 7.5F));
            case RECORD -> disc(12, 12, 7);
            case SIDEBAR -> union(strokeBox(3.5F, 4.5F, 17, 15, 2.5F), seg(9.5F, 4.5F, 9.5F, 19.5F));
            case CUBE -> union(closed(12, 2.5F, 20.5F, 7.25F, 20.5F, 16.75F, 12, 21.5F, 3.5F, 16.75F, 3.5F, 7.25F),
                    open(3.5F, 7.25F, 12, 12, 20.5F, 7.25F), seg(12, 12, 12, 21.5F));
            case PIN -> union(ring(12, 10, 2.8F), arc(12, 10, 7.5F, 140, 400), seg(6.25F, 14.8F, 12, 21.5F), seg(17.75F, 14.8F, 12, 21.5F));
            case FIT -> union(open(3.5F, 8.5F, 3.5F, 3.5F, 8.5F, 3.5F), open(15.5F, 3.5F, 20.5F, 3.5F, 20.5F, 8.5F),
                    open(20.5F, 15.5F, 20.5F, 20.5F, 15.5F, 20.5F), open(8.5F, 20.5F, 3.5F, 20.5F, 3.5F, 15.5F));
            case ROTATE -> union(arc(12, 12, 8, -40, 250), open(19.2F, 2.8F, 18.4F, 7.1F, 14, 6.6F));
            case ANGLE -> union(open(20.5F, 5, 3.5F, 19.5F, 20.5F, 19.5F), arc(3.5F, 19.5F, 9, -40, 0));
            case STOP -> box(6, 6, 12, 12, 2.5F);
            case BOLT -> closed(13, 2.5F, 4, 13.5F, 12, 13.5F, 11, 21.5F, 20, 10.5F, 12, 10.5F);
            case END -> union(seg(19.5F, 4, 19.5F, 20), seg(4, 12, 15.5F, 12), open(11.5F, 8, 15.5F, 12, 11.5F, 16));
            case PUZZLE -> union(strokeBox(4, 8, 12, 12, 2), ring(10, 5.5F, 2.3F), ring(18.5F, 14, 2.3F));
            case SCENE -> union(closed(12, 3, 21, 8, 12, 13, 3, 8), open(3, 12.5F, 12, 17.5F, 21, 12.5F), open(3, 16.5F, 12, 21.5F, 21, 16.5F));
            case DOT -> disc(12, 12, 4);
            case ARROW_UP -> union(seg(12, 19, 12, 5), open(6, 11, 12, 5, 18, 11));
            case ARROW_DOWN -> union(seg(12, 5, 12, 19), open(6, 13, 12, 19, 18, 13));
            case KEYBOARD -> union(strokeBox(2.5F, 6, 19, 12, 2), disc(6.5F, 10, 1.1F), disc(10, 10, 1.1F), disc(13.5F, 10, 1.1F),
                    disc(17, 10, 1.1F), seg(7.5F, 14.5F, 16.5F, 14.5F));
            case MOUSE -> union(strokeBox(7, 3.5F, 10, 17, 5), seg(12, 7, 12, 10));
        };
    }

    private static Sdf gear() {
        Sdf[] parts = new Sdf[10];
        parts[0] = ring(12, 12, 3);
        parts[1] = ring(12, 12, 6.5F);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45 + 22.5);
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            Sdf tooth = seg(12 + 7.2F * c, 12 + 7.2F * s, 12 + 9.6F * c, 12 + 9.6F * s);
            parts[2 + i] = (x, y) -> tooth.d(x, y) - hw * 0.35F;
        }
        return union(parts);
    }

    // ---------------------------------------------------------------- primitives (24 unit grid)

    private static Sdf union(Sdf... parts) {
        return (x, y) -> {
            float d = Float.MAX_VALUE;
            for (Sdf p : parts) {
                d = Math.min(d, p.d(x, y));
            }
            return d;
        };
    }

    /** Cuts a clear band around a segment out of a shape. */
    private static Sdf gap(Sdf shape, float ax, float ay, float bx, float by) {
        return (x, y) -> Math.max(shape.d(x, y), -(segDistance(x, y, ax, ay, bx, by) - hw * 2.4F));
    }

    private static Sdf round(Sdf shape, float radius) {
        return (x, y) -> shape.d(x, y) - radius;
    }

    private static float segDistance(float x, float y, float ax, float ay, float bx, float by) {
        float px = x - ax, py = y - ay, ex = bx - ax, ey = by - ay;
        float t = Anim.clamp01((px * ex + py * ey) / Math.max(1e-6F, ex * ex + ey * ey));
        float dx = px - ex * t, dy = py - ey * t;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Stroked segment. */
    private static Sdf seg(float ax, float ay, float bx, float by) {
        return (x, y) -> segDistance(x, y, ax, ay, bx, by) - hw;
    }

    /** Stroked open polyline. */
    private static Sdf open(float... p) {
        return (x, y) -> {
            float d = Float.MAX_VALUE;
            for (int i = 0; i + 3 < p.length; i += 2) {
                d = Math.min(d, segDistance(x, y, p[i], p[i + 1], p[i + 2], p[i + 3]));
            }
            return d - hw;
        };
    }

    /** Stroked closed polygon. */
    private static Sdf closed(float... p) {
        return (x, y) -> {
            float d = Float.MAX_VALUE;
            int n = p.length / 2;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                d = Math.min(d, segDistance(x, y, p[2 * i], p[2 * i + 1], p[2 * j], p[2 * j + 1]));
            }
            return d - hw;
        };
    }

    /** Filled polygon (exact signed distance). */
    private static Sdf fill(float... p) {
        return (x, y) -> {
            int n = p.length / 2;
            float dx0 = x - p[0], dy0 = y - p[1];
            float d = dx0 * dx0 + dy0 * dy0;
            float s = 1.0F;
            for (int i = 0, j = n - 1; i < n; j = i, i++) {
                float vix = p[2 * i], viy = p[2 * i + 1], vjx = p[2 * j], vjy = p[2 * j + 1];
                float ex = vjx - vix, ey = vjy - viy, wx = x - vix, wy = y - viy;
                float t = Anim.clamp01((wx * ex + wy * ey) / Math.max(1e-6F, ex * ex + ey * ey));
                float bx = wx - ex * t, by = wy - ey * t;
                d = Math.min(d, bx * bx + by * by);
                boolean c1 = y >= viy, c2 = y < vjy, c3 = ex * wy > ey * wx;
                if (c1 && c2 && c3 || !c1 && !c2 && !c3) {
                    s = -s;
                }
            }
            return s * (float) Math.sqrt(d);
        };
    }

    private static Sdf disc(float cx, float cy, float r) {
        return (x, y) -> (float) Math.hypot(x - cx, y - cy) - r;
    }

    private static Sdf ring(float cx, float cy, float r) {
        return (x, y) -> Math.abs((float) Math.hypot(x - cx, y - cy) - r) - hw;
    }

    /** Approximate stroked ellipse. */
    private static Sdf ellipse(float cx, float cy, float rx, float ry) {
        float k = Math.min(rx, ry);
        return (x, y) -> Math.abs(((float) Math.hypot((x - cx) / rx, (y - cy) / ry) - 1.0F) * k) - hw;
    }

    /** Stroked arc from {@code a0} to {@code a1} degrees, clockwise on screen (y down). */
    private static Sdf arc(float cx, float cy, float r, float a0, float a1) {
        float ax = cx + r * (float) Math.cos(Math.toRadians(a0)), ay = cy + r * (float) Math.sin(Math.toRadians(a0));
        float bx = cx + r * (float) Math.cos(Math.toRadians(a1)), by = cy + r * (float) Math.sin(Math.toRadians(a1));
        float sweep = a1 - a0;
        return (x, y) -> {
            float dx = x - cx, dy = y - cy;
            float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
            float rel = ((angle - a0) % 360 + 360) % 360;
            float d;
            if (rel <= sweep) {
                d = Math.abs((float) Math.hypot(dx, dy) - r);
            } else {
                d = Math.min((float) Math.hypot(x - ax, y - ay), (float) Math.hypot(x - bx, y - by));
            }
            return d - hw;
        };
    }

    private static float boxDistance(float x, float y, float bx, float by, float w, float h, float r) {
        float hx = w / 2, hy = h / 2;
        float qx = Math.abs(x - (bx + hx)) - (hx - r), qy = Math.abs(y - (by + hy)) - (hy - r);
        float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
        return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - r;
    }

    /** Filled rounded box. */
    private static Sdf box(float bx, float by, float w, float h, float r) {
        return (x, y) -> boxDistance(x, y, bx, by, w, h, r);
    }

    /** Stroked rounded box. */
    private static Sdf strokeBox(float bx, float by, float w, float h, float r) {
        return (x, y) -> Math.abs(boxDistance(x, y, bx, by, w, h, r)) - hw;
    }

    /** Stroked cubic Bezier, flattened into segments. */
    private static Sdf bezier(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
        int steps = 16;
        float[] pts = new float[(steps + 1) * 2];
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps, u = 1 - t;
            pts[2 * i] = u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3;
            pts[2 * i + 1] = u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3;
        }
        return open(pts);
    }
}
