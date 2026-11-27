package com.vyrriox.justreplay.mcp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * The pictures the observation tools send: the size a caller may ask for, the scaling of a captured frame to that
 * size and its encoding as JPEG (small, the default) or PNG (exact). Plain Java, no image library: the game hands
 * over the RGBA bytes of a frame, top row first, as the video export reads them.
 *
 * @author vyrriox
 */
public final class McpImage {
    /** Shortest side of a picture. */
    public static final int MIN_SIDE = 64;
    /** Longest side of a picture. */
    public static final int MAX_SIDE = 1280;
    /** Most pixels in one picture (1280x720), which keeps a PNG under 3 MB and a JPEG near 200 KB. */
    public static final int MAX_PIXELS = 1280 * 720;
    /** Width given when the caller asks for none. */
    public static final int DEFAULT_WIDTH = 640;
    public static final int DEFAULT_QUALITY = 80;
    public static final String JPEG = "jpeg";
    public static final String PNG = "png";

    private McpImage() {
    }

    /**
     * Size of a picture.
     *
     * @param clamped the size asked for was out of bounds and was brought back inside them
     */
    public record Size(int width, int height, boolean clamped) {
    }

    /**
     * The size of the picture for what the caller asked, either side possibly absent (null): the missing one follows
     * {@code aspect} (width / height of what is shown), 640 wide when both are missing. Each side is kept from
     * {@link #MIN_SIDE} to {@link #MAX_SIDE} and the whole within {@link #MAX_PIXELS}, the shape kept.
     */
    public static Size fit(Integer width, Integer height, double aspect) {
        double a = Double.isFinite(aspect) && aspect > 0.0 ? Math.max(0.1, Math.min(10.0, aspect)) : 16.0 / 9.0;
        double w;
        double h;
        if (width == null && height == null) {
            w = DEFAULT_WIDTH;
            h = DEFAULT_WIDTH / a;
        } else if (width == null) {
            h = height;
            w = height * a;
        } else if (height == null) {
            w = width;
            h = width / a;
        } else {
            w = width;
            h = height;
        }
        double asked = w * h;
        // Too long a side or too many pixels: shrink both sides alike, the shape is kept.
        double shrink = Math.min(1.0, Math.min(MAX_SIDE / w, MAX_SIDE / h));
        if (asked * shrink * shrink > MAX_PIXELS) {
            shrink = Math.sqrt(MAX_PIXELS / asked);
        }
        w *= shrink;
        h *= shrink;
        int iw = clampSide((int) Math.floor(w + 1.0E-6));
        int ih = clampSide((int) Math.floor(h + 1.0E-6));
        while ((long) iw * ih > MAX_PIXELS) {
            // A side raised to the minimum can push the other one over the pixel budget.
            if (iw >= ih) {
                iw--;
            } else {
                ih--;
            }
        }
        boolean clamped = width != null && width != iw || height != null && height != ih;
        return new Size(iw, ih, clamped);
    }

    private static int clampSide(int side) {
        return Math.max(MIN_SIDE, Math.min(MAX_SIDE, side));
    }

    // ------------------------------------------------------------------ scaling

    /**
     * Scales an RGBA picture (top row first) to {@code dstWidth x dstHeight} RGB, averaging the source pixels each
     * output pixel covers. When the shapes differ the source is cropped around its centre, as a video export does,
     * so nothing is stretched.
     */
    public static byte[] scale(byte[] rgba, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
            throw new IllegalArgumentException("Empty picture");
        }
        if (rgba.length < (long) srcWidth * srcHeight * 4) {
            throw new IllegalArgumentException("Picture of " + rgba.length + " bytes for " + srcWidth + "x" + srcHeight);
        }
        double cropX = 0.0;
        double cropY = 0.0;
        double cropW = srcWidth;
        double cropH = srcHeight;
        double srcAspect = (double) srcWidth / srcHeight;
        double dstAspect = (double) dstWidth / dstHeight;
        if (srcAspect > dstAspect + 1.0E-9) {
            cropW = srcHeight * dstAspect;
            cropX = (srcWidth - cropW) / 2.0;
        } else if (srcAspect < dstAspect - 1.0E-9) {
            cropH = srcWidth / dstAspect;
            cropY = (srcHeight - cropH) / 2.0;
        }
        Span[] columns = spans(cropX, cropW, dstWidth, srcWidth);
        Span[] rows = spans(cropY, cropH, dstHeight, srcHeight);
        byte[] out = new byte[dstWidth * dstHeight * 3];
        float[] line = new float[dstWidth * 3];
        float[] acc = new float[dstWidth * 3];
        int lineRow = -1;
        for (int y = 0; y < dstHeight; y++) {
            java.util.Arrays.fill(acc, 0.0F);
            Span rowSpan = rows[y];
            for (int i = 0; i < rowSpan.index.length; i++) {
                int r = rowSpan.index[i];
                if (r != lineRow) {
                    horizontal(rgba, srcWidth, r, columns, line);
                    lineRow = r;
                }
                float wy = rowSpan.weight[i];
                for (int k = 0; k < acc.length; k++) {
                    acc[k] += line[k] * wy;
                }
            }
            int o = y * dstWidth * 3;
            for (int k = 0; k < acc.length; k++) {
                out[o + k] = (byte) Math.max(0, Math.min(255, Math.round(acc[k])));
            }
        }
        return out;
    }

    /** Source pixels one output pixel covers along one axis, with the share of each (summing to 1). */
    private record Span(int[] index, float[] weight) {
    }

    private static Span[] spans(double start, double length, int count, int limit) {
        Span[] spans = new Span[count];
        double step = length / count;
        for (int i = 0; i < count; i++) {
            double a = start + i * step;
            double b = a + step;
            if (step < 1.0) {
                // Enlarging: the output pixel is narrower than a source pixel, take the one under its centre.
                int c = Math.max(0, Math.min(limit - 1, (int) Math.floor((a + b) / 2.0)));
                spans[i] = new Span(new int[]{c}, new float[]{1.0F});
                continue;
            }
            int first = (int) Math.floor(a);
            int last = (int) Math.ceil(b) - 1;
            int n = Math.max(1, last - first + 1);
            int[] index = new int[n];
            float[] weight = new float[n];
            double total = 0.0;
            for (int k = 0; k < n; k++) {
                int p = first + k;
                double cover = Math.min(b, p + 1.0) - Math.max(a, p);
                index[k] = Math.max(0, Math.min(limit - 1, p));
                weight[k] = (float) Math.max(0.0, cover);
                total += weight[k];
            }
            for (int k = 0; k < n; k++) {
                weight[k] = total > 0.0 ? (float) (weight[k] / total) : 1.0F / n;
            }
            spans[i] = new Span(index, weight);
        }
        return spans;
    }

    private static void horizontal(byte[] rgba, int srcWidth, int row, Span[] columns, float[] out) {
        int base = row * srcWidth * 4;
        for (int x = 0; x < columns.length; x++) {
            Span s = columns[x];
            float r = 0.0F;
            float g = 0.0F;
            float b = 0.0F;
            for (int i = 0; i < s.index.length; i++) {
                int p = base + s.index[i] * 4;
                float w = s.weight[i];
                r += (rgba[p] & 0xFF) * w;
                g += (rgba[p + 1] & 0xFF) * w;
                b += (rgba[p + 2] & 0xFF) * w;
            }
            out[x * 3] = r;
            out[x * 3 + 1] = g;
            out[x * 3 + 2] = b;
        }
    }

    // ------------------------------------------------------------------ PNG

    /** An 8 bit RGB PNG of {@code rgb} (3 bytes per pixel, top row first). */
    public static byte[] png(byte[] rgb, int width, int height) {
        check(rgb, width, height);
        int stride = width * 3;
        byte[] filtered = new byte[(stride + 1) * height];
        byte[] previous = new byte[stride];
        byte[] row = new byte[stride];
        byte[][] candidates = new byte[5][stride];
        for (int y = 0; y < height; y++) {
            System.arraycopy(rgb, y * stride, row, 0, stride);
            // Each row takes the filter whose output is smallest in absolute value, the usual heuristic.
            int best = 0;
            long bestScore = Long.MAX_VALUE;
            for (int f = 0; f < 5; f++) {
                long score = filterRow(f, row, previous, candidates[f]);
                if (score < bestScore) {
                    bestScore = score;
                    best = f;
                }
            }
            int o = y * (stride + 1);
            filtered[o] = (byte) best;
            System.arraycopy(candidates[best], 0, filtered, o + 1, stride);
            byte[] swap = previous;
            previous = row;
            row = swap;
        }
        Deflater deflater = new Deflater(6);
        deflater.setInput(filtered);
        deflater.finish();
        ByteArrayOutputStream zipped = new ByteArrayOutputStream(filtered.length / 4 + 64);
        byte[] buffer = new byte[65536];
        while (!deflater.finished()) {
            int n = deflater.deflate(buffer);
            zipped.write(buffer, 0, n);
        }
        deflater.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream(zipped.size() + 64);
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        byte[] header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = 8; // bits per channel
        header[9] = 2; // RGB
        chunk(out, "IHDR", header);
        chunk(out, "IDAT", zipped.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static long filterRow(int filter, byte[] row, byte[] up, byte[] out) {
        long score = 0;
        for (int i = 0; i < row.length; i++) {
            int x = row[i] & 0xFF;
            int a = i >= 3 ? row[i - 3] & 0xFF : 0;
            int b = up[i] & 0xFF;
            int c = i >= 3 ? up[i - 3] & 0xFF : 0;
            int predicted = switch (filter) {
                case 1 -> a;
                case 2 -> b;
                case 3 -> (a + b) >>> 1;
                case 4 -> paeth(a, b, c);
                default -> 0;
            };
            byte v = (byte) (x - predicted);
            out[i] = v;
            score += Math.abs((int) v);
        }
        return score;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] head = new byte[8];
        putInt(head, 0, data.length);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, head, 4, 4);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        byte[] tail = new byte[4];
        putInt(tail, 0, (int) crc.getValue());
        out.writeBytes(head);
        out.writeBytes(data);
        out.writeBytes(tail);
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    private static void check(byte[] rgb, int width, int height) {
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) {
            throw new IllegalArgumentException("Picture size " + width + "x" + height);
        }
        if (rgb.length < width * height * 3) {
            throw new IllegalArgumentException("Picture of " + rgb.length + " bytes for " + width + "x" + height);
        }
    }

    // ------------------------------------------------------------------ JPEG

    /** Natural (row by row) index of each position of the zigzag order. */
    static final int[] ZIGZAG = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};
    /** Quantization tables of the JPEG standard (ITU T.81 Annex K.1), natural order, for quality 50. */
    private static final int[] LUMA_QUANT = {
            16, 11, 10, 16, 24, 40, 51, 61,
            12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77,
            24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101,
            72, 92, 95, 98, 112, 100, 103, 99};
    private static final int[] CHROMA_QUANT = {
            17, 18, 24, 47, 99, 99, 99, 99,
            18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99,
            47, 66, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99};
    /** Huffman tables of the JPEG standard (Annex K.3): code counts per length 1 to 16, then the values. */
    static final int[] DC_LUMA_BITS = {0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
    static final int[] DC_LUMA_VALUES = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    static final int[] DC_CHROMA_BITS = {0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
    static final int[] DC_CHROMA_VALUES = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    static final int[] AC_LUMA_BITS = {0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d};
    static final int[] AC_LUMA_VALUES = {
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
            0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
            0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa};
    static final int[] AC_CHROMA_BITS = {0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
    static final int[] AC_CHROMA_VALUES = {
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
            0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
            0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
            0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
            0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
            0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
            0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa};
    /** cos((2x + 1) u pi / 16), times 1/sqrt(2) for u = 0: the 8 point DCT. */
    private static final double[][] DCT = new double[8][8];

    static {
        for (int u = 0; u < 8; u++) {
            double c = u == 0 ? Math.sqrt(0.5) : 1.0;
            for (int x = 0; x < 8; x++) {
                DCT[u][x] = c * Math.cos((2 * x + 1) * u * Math.PI / 16.0);
            }
        }
    }

    /**
     * A baseline JFIF JPEG of {@code rgb} (3 bytes per pixel, top row first): YCbCr with the colour at half size
     * (4:2:0), the quantization tables of the standard scaled to {@code quality} (1 to 100, as the IJG library does)
     * and its Huffman tables.
     */
    public static byte[] jpeg(byte[] rgb, int width, int height, int quality) {
        check(rgb, width, height);
        int q = Math.max(1, Math.min(100, quality));
        int[] lumaQuant = scaledQuant(LUMA_QUANT, q);
        int[] chromaQuant = scaledQuant(CHROMA_QUANT, q);
        Huffman dcLuma = new Huffman(DC_LUMA_BITS, DC_LUMA_VALUES);
        Huffman acLuma = new Huffman(AC_LUMA_BITS, AC_LUMA_VALUES);
        Huffman dcChroma = new Huffman(DC_CHROMA_BITS, DC_CHROMA_VALUES);
        Huffman acChroma = new Huffman(AC_CHROMA_BITS, AC_CHROMA_VALUES);

        ByteArrayOutputStream out = new ByteArrayOutputStream(width * height / 4 + 1024);
        marker(out, 0xD8);
        segment(out, 0xE0, new byte[]{'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0});
        byte[] dqt = new byte[2 * 65];
        dqt[0] = 0;
        dqt[65] = 1;
        for (int k = 0; k < 64; k++) {
            dqt[1 + k] = (byte) lumaQuant[ZIGZAG[k]];
            dqt[66 + k] = (byte) chromaQuant[ZIGZAG[k]];
        }
        segment(out, 0xDB, dqt);
        segment(out, 0xC0, new byte[]{8, (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width, 3,
                1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1});
        ByteArrayOutputStream dht = new ByteArrayOutputStream();
        table(dht, 0x00, DC_LUMA_BITS, DC_LUMA_VALUES);
        table(dht, 0x10, AC_LUMA_BITS, AC_LUMA_VALUES);
        table(dht, 0x01, DC_CHROMA_BITS, DC_CHROMA_VALUES);
        table(dht, 0x11, AC_CHROMA_BITS, AC_CHROMA_VALUES);
        segment(out, 0xC4, dht.toByteArray());
        segment(out, 0xDA, new byte[]{3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0});

        Bits bits = new Bits(out);
        int[] previousDc = new int[3];
        double[] y = new double[64];
        double[] cb = new double[64];
        double[] cr = new double[64];
        double[] coefficients = new double[64];
        int[] quantized = new int[64];
        double[][] luma = new double[4][64];
        for (int my = 0; my < height; my += 16) {
            for (int mx = 0; mx < width; mx += 16) {
                // One unit of 16x16 pixels: four luma blocks, one block of each colour averaged over 2x2 pixels.
                java.util.Arrays.fill(cb, 0.0);
                java.util.Arrays.fill(cr, 0.0);
                for (int py = 0; py < 16; py++) {
                    int sy = Math.min(height - 1, my + py);
                    for (int px = 0; px < 16; px++) {
                        int sx = Math.min(width - 1, mx + px);
                        int p = (sy * width + sx) * 3;
                        double r = rgb[p] & 0xFF;
                        double g = rgb[p + 1] & 0xFF;
                        double b = rgb[p + 2] & 0xFF;
                        int block = (py >> 3) * 2 + (px >> 3);
                        luma[block][(py & 7) * 8 + (px & 7)] = 0.299 * r + 0.587 * g + 0.114 * b - 128.0;
                        int c = (py >> 1) * 8 + (px >> 1);
                        cb[c] += 0.25 * (-0.168736 * r - 0.331264 * g + 0.5 * b);
                        cr[c] += 0.25 * (0.5 * r - 0.418688 * g - 0.081312 * b);
                    }
                }
                for (int block = 0; block < 4; block++) {
                    System.arraycopy(luma[block], 0, y, 0, 64);
                    previousDc[0] = encodeBlock(bits, y, coefficients, quantized, lumaQuant, previousDc[0], dcLuma, acLuma);
                }
                previousDc[1] = encodeBlock(bits, cb, coefficients, quantized, chromaQuant, previousDc[1], dcChroma, acChroma);
                previousDc[2] = encodeBlock(bits, cr, coefficients, quantized, chromaQuant, previousDc[2], dcChroma, acChroma);
            }
        }
        bits.flush();
        marker(out, 0xD9);
        return out.toByteArray();
    }

    static int[] scaledQuant(int[] base, int quality) {
        int scale = quality < 50 ? 5000 / quality : 200 - quality * 2;
        int[] out = new int[64];
        for (int i = 0; i < 64; i++) {
            out[i] = Math.max(1, Math.min(255, (base[i] * scale + 50) / 100));
        }
        return out;
    }

    /** Transforms, quantizes and writes one block; returns its DC value for the next block of the component. */
    private static int encodeBlock(Bits bits, double[] samples, double[] coefficients, int[] quantized, int[] quant, int previousDc,
                                   Huffman dc, Huffman ac) {
        // Two passes of the 8 point DCT: rows, then columns.
        double[] tmp = new double[64];
        for (int row = 0; row < 8; row++) {
            for (int u = 0; u < 8; u++) {
                double s = 0.0;
                for (int x = 0; x < 8; x++) {
                    s += DCT[u][x] * samples[row * 8 + x];
                }
                tmp[row * 8 + u] = s / 2.0;
            }
        }
        for (int u = 0; u < 8; u++) {
            for (int v = 0; v < 8; v++) {
                double s = 0.0;
                for (int yy = 0; yy < 8; yy++) {
                    s += DCT[v][yy] * tmp[yy * 8 + u];
                }
                coefficients[v * 8 + u] = s / 2.0;
            }
        }
        for (int i = 0; i < 64; i++) {
            quantized[i] = (int) Math.round(coefficients[i] / quant[i]);
        }
        int dcValue = quantized[0];
        int diff = dcValue - previousDc;
        int size = magnitude(diff);
        bits.write(dc.code[size], dc.length[size]);
        if (size > 0) {
            bits.write(diff < 0 ? diff - 1 : diff, size);
        }
        int run = 0;
        for (int k = 1; k < 64; k++) {
            int value = quantized[ZIGZAG[k]];
            if (value == 0) {
                run++;
                continue;
            }
            while (run > 15) {
                bits.write(ac.code[0xF0], ac.length[0xF0]);
                run -= 16;
            }
            int s = magnitude(value);
            int symbol = (run << 4) | s;
            bits.write(ac.code[symbol], ac.length[symbol]);
            bits.write(value < 0 ? value - 1 : value, s);
            run = 0;
        }
        if (run > 0) {
            bits.write(ac.code[0x00], ac.length[0x00]);
        }
        return dcValue;
    }

    /** Bits needed for the magnitude of {@code value}: the JPEG "size" category. */
    static int magnitude(int value) {
        int v = Math.abs(value);
        int n = 0;
        while (v > 0) {
            n++;
            v >>= 1;
        }
        return n;
    }

    /** The codes of a Huffman table given as code counts per length and values, as the standard builds them. */
    static final class Huffman {
        final int[] code = new int[256];
        final int[] length = new int[256];

        Huffman(int[] bits, int[] values) {
            int next = 0;
            int k = 0;
            for (int len = 1; len <= 16; len++) {
                for (int i = 0; i < bits[len - 1]; i++) {
                    int value = values[k++];
                    code[value] = next++;
                    length[value] = len;
                }
                next <<= 1;
            }
        }
    }

    /** Writes bits most significant first, with a 0 byte after every 0xFF as the entropy coded data needs. */
    private static final class Bits {
        private final ByteArrayOutputStream out;
        private int buffer;
        private int count;

        Bits(ByteArrayOutputStream out) {
            this.out = out;
        }

        void write(int value, int n) {
            for (int i = n - 1; i >= 0; i--) {
                buffer = (buffer << 1) | ((value >>> i) & 1);
                if (++count == 8) {
                    emit();
                }
            }
        }

        void flush() {
            while (count != 0) {
                // Padding with ones, as the standard asks.
                buffer = (buffer << 1) | 1;
                if (++count == 8) {
                    emit();
                }
            }
        }

        private void emit() {
            out.write(buffer & 0xFF);
            if ((buffer & 0xFF) == 0xFF) {
                out.write(0);
            }
            buffer = 0;
            count = 0;
        }
    }

    private static void marker(ByteArrayOutputStream out, int code) {
        out.write(0xFF);
        out.write(code);
    }

    private static void segment(ByteArrayOutputStream out, int code, byte[] data) {
        marker(out, code);
        int length = data.length + 2;
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(data);
    }

    private static void table(ByteArrayOutputStream out, int classAndId, int[] bits, int[] values) {
        out.write(classAndId);
        for (int b : bits) {
            out.write(b);
        }
        for (int v : values) {
            out.write(v);
        }
    }

    // ------------------------------------------------------------------ reading back

    /**
     * Width and height written in the header of a PNG or baseline JPEG, or null when {@code data} is neither. Reads
     * the header only: the self test decodes the whole picture with the game's own decoder.
     */
    public static int[] headerSize(byte[] data) {
        if (data == null || data.length < 24) {
            return null;
        }
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return new int[]{readInt(data, 16), readInt(data, 20)};
        }
        if ((data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return null;
        }
        int at = 2;
        while (at + 9 < data.length) {
            if ((data[at] & 0xFF) != 0xFF) {
                return null;
            }
            int code = data[at + 1] & 0xFF;
            int length = ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
            if (code >= 0xC0 && code <= 0xC3) {
                int height = ((data[at + 5] & 0xFF) << 8) | (data[at + 6] & 0xFF);
                int width = ((data[at + 7] & 0xFF) << 8) | (data[at + 8] & 0xFF);
                return new int[]{width, height};
            }
            at += 2 + length;
        }
        return null;
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    /** MIME type of a format. */
    public static String mimeType(String format) {
        return PNG.equals(format) ? "image/png" : "image/jpeg";
    }

    /** Encodes {@code rgb} in {@code format} ({@link #JPEG} or {@link #PNG}). */
    public static byte[] encode(String format, byte[] rgb, int width, int height, int quality) {
        return PNG.equals(format) ? png(rgb, width, height) : jpeg(rgb, width, height, quality);
    }
}
