package com.vyrriox.justreplay.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sizes, scaling and the two encoders, read back by the JDK's own decoders.
 *
 * @author vyrriox
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpImageTest {
    @Test
    void sizeFollowsTheShapeAndStaysInBounds() {
        assertEquals(new McpImage.Size(640, 360, false), McpImage.fit(null, null, 16.0 / 9.0));
        assertEquals(new McpImage.Size(320, 180, false), McpImage.fit(320, null, 16.0 / 9.0));
        assertEquals(new McpImage.Size(400, 300, false), McpImage.fit(null, 300, 4.0 / 3.0));
        assertEquals(new McpImage.Size(500, 500, false), McpImage.fit(500, 500, 16.0 / 9.0));
        // Too wide: both sides shrink alike.
        assertEquals(new McpImage.Size(1280, 720, true), McpImage.fit(2000, null, 16.0 / 9.0));
        assertEquals(new McpImage.Size(1280, 720, true), McpImage.fit(3840, 2160, 1.0));
        // Too many pixels for the budget.
        McpImage.Size square = McpImage.fit(1280, 1280, 1.0);
        assertEquals(960, square.width());
        assertEquals(960, square.height());
        assertTrue(square.clamped());
        // Over the budget but not too long: both sides shrink alike, the shape is kept (1.28).
        assertEquals(new McpImage.Size(1086, 848, true), McpImage.fit(1280, 1000, 1.0));
        // Too small.
        assertEquals(new McpImage.Size(64, 64, true), McpImage.fit(10, 10, 1.0));
        // A broken shape falls back to 16:9.
        assertEquals(new McpImage.Size(640, 360, false), McpImage.fit(null, null, Double.NaN));
    }

    @Test
    void noSizeEverLeavesTheBounds() {
        Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            Integer w = random.nextInt(4) == 0 ? null : 1 + random.nextInt(9000);
            Integer h = random.nextInt(4) == 0 ? null : 1 + random.nextInt(9000);
            McpImage.Size s = McpImage.fit(w, h, 0.05 + random.nextDouble() * 20.0);
            assertTrue(s.width() >= McpImage.MIN_SIDE && s.width() <= McpImage.MAX_SIDE, s.toString());
            assertTrue(s.height() >= McpImage.MIN_SIDE && s.height() <= McpImage.MAX_SIDE, s.toString());
            assertTrue((long) s.width() * s.height() <= McpImage.MAX_PIXELS, s.toString());
        }
    }

    @Test
    void sameSizeCopiesThePixels() {
        byte[] rgba = noise(37, 23, 1);
        byte[] rgb = McpImage.scale(rgba, 37, 23, 37, 23);
        for (int i = 0; i < 37 * 23; i++) {
            assertEquals(rgba[i * 4], rgb[i * 3]);
            assertEquals(rgba[i * 4 + 1], rgb[i * 3 + 1]);
            assertEquals(rgba[i * 4 + 2], rgb[i * 3 + 2]);
        }
    }

    @Test
    void shrinkingAveragesAndAnotherShapeIsCroppedAroundTheCentre() {
        // A 2x2 checkerboard of black and white becomes one grey pixel.
        byte[] board = new byte[2 * 2 * 4];
        fill(board, 0, 255, 255, 255);
        fill(board, 3, 255, 255, 255);
        byte[] grey = McpImage.scale(board, 2, 2, 1, 1);
        assertTrue(Math.abs((grey[0] & 0xFF) - 128) <= 1, Integer.toString(grey[0] & 0xFF));
        // Red outer columns, green inner ones: a square picture keeps the centre only.
        byte[] wide = new byte[4 * 2 * 4];
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 4; x++) {
                boolean inner = x == 1 || x == 2;
                fill(wide, y * 4 + x, inner ? 0 : 255, inner ? 255 : 0, 0);
            }
        }
        byte[] square = McpImage.scale(wide, 4, 2, 2, 2);
        for (int i = 0; i < 4; i++) {
            assertEquals(0, square[i * 3] & 0xFF);
            assertEquals(255, square[i * 3 + 1] & 0xFF);
        }
        // Enlarging keeps whole pixels.
        byte[] big = McpImage.scale(board, 2, 2, 4, 4);
        assertEquals(255, big[0] & 0xFF);
        assertEquals(255, big[3] & 0xFF);
        assertEquals(0, big[2 * 3] & 0xFF);
        assertEquals(255, big[(4 * 4 - 1) * 3] & 0xFF);
    }

    @Test
    void pngIsExact() throws IOException {
        byte[] rgb = McpImage.scale(noise(123, 45, 2), 123, 45, 123, 45);
        byte[] png = McpImage.png(rgb, 123, 45);
        assertArrayEquals(new int[]{123, 45}, McpImage.headerSize(png));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(123, image.getWidth());
        assertEquals(45, image.getHeight());
        for (int y = 0; y < 45; y++) {
            for (int x = 0; x < 123; x++) {
                int p = (y * 123 + x) * 3;
                int expected = ((rgb[p] & 0xFF) << 16) | ((rgb[p + 1] & 0xFF) << 8) | (rgb[p + 2] & 0xFF);
                assertEquals(expected, image.getRGB(x, y) & 0xFFFFFF, "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void jpegDecodesAtItsSizeAndLooksLikeTheSource() throws IOException {
        // Odd sizes: the last units are padded by repeating the edge.
        int w = 333;
        int h = 197;
        byte[] rgb = gradient(w, h);
        byte[] jpeg = McpImage.jpeg(rgb, w, h, 90);
        assertArrayEquals(new int[]{w, h}, McpImage.headerSize(jpeg));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertNotNull(image);
        assertEquals(w, image.getWidth());
        assertEquals(h, image.getHeight());
        double psnr = psnr(rgb, image);
        assertTrue(psnr > 32.0, "PSNR " + psnr);
        // Lower quality, smaller file, still readable.
        byte[] small = McpImage.jpeg(rgb, w, h, 20);
        assertTrue(small.length < jpeg.length, small.length + " >= " + jpeg.length);
        assertTrue(psnr(rgb, ImageIO.read(new ByteArrayInputStream(small))) > 24.0);
    }

    @Test
    void solidColourAndNoiseSurviveJpeg() throws IOException {
        byte[] solid = new byte[64 * 64 * 3];
        for (int i = 0; i < 64 * 64; i++) {
            solid[i * 3] = (byte) 200;
            solid[i * 3 + 1] = (byte) 100;
            solid[i * 3 + 2] = (byte) 50;
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(McpImage.jpeg(solid, 64, 64, 80)));
        int rgb = image.getRGB(31, 31);
        assertTrue(Math.abs(((rgb >> 16) & 0xFF) - 200) <= 4, Integer.toHexString(rgb));
        assertTrue(Math.abs(((rgb >> 8) & 0xFF) - 100) <= 4, Integer.toHexString(rgb));
        assertTrue(Math.abs((rgb & 0xFF) - 50) <= 4, Integer.toHexString(rgb));
        // Noise has every coefficient in use: the whole Huffman tables get exercised.
        byte[] noisy = McpImage.scale(noise(200, 120, 3), 200, 120, 200, 120);
        for (int q : new int[]{1, 50, 100}) {
            BufferedImage n = ImageIO.read(new ByteArrayInputStream(McpImage.jpeg(noisy, 200, 120, q)));
            assertNotNull(n, "quality " + q);
            assertEquals(200, n.getWidth());
        }
    }

    @Test
    void theLargestPictureStaysLight() {
        byte[] rgb = gradient(McpImage.MAX_SIDE, 720);
        byte[] jpeg = McpImage.jpeg(rgb, McpImage.MAX_SIDE, 720, McpImage.DEFAULT_QUALITY);
        assertTrue(jpeg.length < 400_000, jpeg.length + " bytes");
        byte[] png = McpImage.png(rgb, McpImage.MAX_SIDE, 720);
        assertTrue(png.length < 3_000_000, png.length + " bytes");
    }

    @Test
    void tablesAreTheStandardOnes() {
        assertEquals(McpImage.DC_LUMA_VALUES.length, sum(McpImage.DC_LUMA_BITS));
        assertEquals(McpImage.DC_CHROMA_VALUES.length, sum(McpImage.DC_CHROMA_BITS));
        assertEquals(McpImage.AC_LUMA_VALUES.length, sum(McpImage.AC_LUMA_BITS));
        assertEquals(McpImage.AC_CHROMA_VALUES.length, sum(McpImage.AC_CHROMA_BITS));
        // Every AC symbol a block can need: end of block, sixteen zeros, then each run of 0 to 15 with each size 1 to 10.
        Set<Integer> needed = new HashSet<>();
        needed.add(0x00);
        needed.add(0xF0);
        for (int run = 0; run < 16; run++) {
            for (int size = 1; size <= 10; size++) {
                needed.add(run << 4 | size);
            }
        }
        assertEquals(needed, set(McpImage.AC_LUMA_VALUES));
        assertEquals(needed, set(McpImage.AC_CHROMA_VALUES));
        Set<Integer> zigzag = set(McpImage.ZIGZAG);
        assertEquals(64, zigzag.size());
        assertTrue(zigzag.contains(0) && zigzag.contains(63));
        assertEquals(0, McpImage.magnitude(0));
        assertEquals(11, McpImage.magnitude(-2047));
        assertArrayEquals(new int[]{1, 1}, new int[]{McpImage.scaledQuant(new int[64], 100)[0], McpImage.scaledQuant(new int[64], 1)[63]});
    }

    @Test
    void headersOfOtherDataAreRefused() {
        assertNull(McpImage.headerSize(null));
        assertNull(McpImage.headerSize(new byte[40]));
        assertFalse(McpImage.mimeType(McpImage.JPEG).equals(McpImage.mimeType(McpImage.PNG)));
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] noise(int w, int h, long seed) {
        byte[] rgba = new byte[w * h * 4];
        new Random(seed).nextBytes(rgba);
        return rgba;
    }

    private static byte[] gradient(int w, int h) {
        byte[] rgb = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = (y * w + x) * 3;
                rgb[p] = (byte) (x * 255 / Math.max(1, w - 1));
                rgb[p + 1] = (byte) (y * 255 / Math.max(1, h - 1));
                rgb[p + 2] = (byte) (128 + 100 * Math.sin(x / 17.0) * Math.cos(y / 11.0));
            }
        }
        return rgb;
    }

    private static void fill(byte[] rgba, int pixel, int r, int g, int b) {
        rgba[pixel * 4] = (byte) r;
        rgba[pixel * 4 + 1] = (byte) g;
        rgba[pixel * 4 + 2] = (byte) b;
        rgba[pixel * 4 + 3] = (byte) 255;
    }

    private static double psnr(byte[] rgb, BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        double error = 0.0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = (y * w + x) * 3;
                int got = image.getRGB(x, y);
                int[] channels = {(got >> 16) & 0xFF, (got >> 8) & 0xFF, got & 0xFF};
                for (int c = 0; c < 3; c++) {
                    double d = (rgb[p + c] & 0xFF) - channels[c];
                    error += d * d;
                }
            }
        }
        double mse = error / (w * h * 3.0);
        return mse == 0.0 ? 99.0 : 10.0 * Math.log10(255.0 * 255.0 / mse);
    }

    private static int sum(int[] values) {
        int s = 0;
        for (int v : values) {
            s += v;
        }
        return s;
    }

    private static Set<Integer> set(int[] values) {
        Set<Integer> s = new HashSet<>();
        for (int v : values) {
            s.add(v);
        }
        return s;
    }
}
