package com.flashforge.farm.slic3r;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * App-side G-code toolpath parser for the per-layer slice preview.
 *
 * The libvgcode viewer stack is still a native stub (issue #212), so the
 * layers tab cannot render through {@link GCodeViewer}. This parser reads
 * the sliced .gcode file directly (absolute E coordinates, relative E
 * extrusion, ;LAYER_CHANGE / ;Z: markers) and groups extrusion segments by
 * Z layer. The renderer turns the selected layer range into line-strip
 * {@link GLModel}s drawn with the bundled flat shader.
 */
public final class GCodeToolpaths {
    private GCodeToolpaths() {
    }

    public static final class Layer {
        /** Packed xyz triplets of consecutive extrusion vertices. */
        public final float[] vertices;
        /** Index runs into {@link #vertices}; extrusion + travel separated as segments. */
        public final int[] indices;
        /** Layer Z in mm (first extrusion Z of the layer). */
        public final float z;

        Layer(float[] vertices, int[] indices, float z) {
            this.vertices = vertices;
            this.indices = indices;
            this.z = z;
        }
    }

    public static final class Parsed {
        public final List<Layer> layers;
        public final float[] boundsMin;
        public final float[] boundsMax;

        Parsed(List<Layer> layers, float[] boundsMin, float[] boundsMax) {
            this.layers = layers;
            this.boundsMin = boundsMin;
            this.boundsMax = boundsMax;
        }

        public int getLayersCount() {
            return layers.size();
        }
    }

    /**
     * Single-entry parsed-file cache: the slice screen and the renderer both
     * need the same parse, and re-slicing replaces the file (length/mtime
     * key prevents stale hits). Soft reference so a huge dragon parse can
     * still be reclaimed under memory pressure.
     */
    private static String cachedKey;
    private static long cachedLen = -1;
    private static long cachedModified = -1;
    private static int cachedCap = Integer.MIN_VALUE;
    private static java.lang.ref.SoftReference<Parsed> cachedParsed;

    public static synchronized Parsed parseCached(File f, int maxVerticesPerLayer) throws IOException {
        String key;
        try {
            key = f.getCanonicalPath();
        } catch (IOException e) {
            key = f.getAbsolutePath();
        }
        long len = f.length();
        long mod = f.lastModified();
        Parsed p = cachedParsed != null ? cachedParsed.get() : null;
        if (p != null && key.equals(cachedKey) && len == cachedLen && mod == cachedModified
                && maxVerticesPerLayer == cachedCap) {
            return p;
        }
        p = parse(f, maxVerticesPerLayer);
        cachedKey = key;
        cachedLen = len;
        cachedModified = mod;
        cachedCap = maxVerticesPerLayer;
        cachedParsed = new java.lang.ref.SoftReference<>(p);
        return p;
    }

    public static synchronized void invalidateCache() {
        cachedParsed = null;
        cachedKey = null;
    }

    /**
     * Parse extrusion toolpaths from a sliced .gcode file.
     *
     * <p>Hot-loop discipline (large dragon prints are millions of lines —
     * the old boxed/regex implementation froze the UI for ~a minute):
     * no per-line regex splits, no {@code toUpperCase} copies, no boxed
     * {@code Float}/{@code Integer} accumulators, manual float parsing
     * straight from the line buffer into growable primitive arrays.
     *
     * @param f sliced gcode file (must exist)
     * @param maxVerticesPerLayer cap on vertices kept per layer (decimates long layers
     *                            by stride to bound GL memory; <= 0 means no cap)
     */
    public static Parsed parse(File f, int maxVerticesPerLayer) throws IOException {
        List<float[]> layerVertices = new ArrayList<>();
        List<int[]> layerIndices = new ArrayList<>();
        List<Float> layerZ = new ArrayList<>();

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        float x = 0, y = 0, z = 0, e = 0;
        boolean absoluteE = true;
        boolean hasPos = false;
        boolean extrudingSegment = false;

        FloatList verts = new FloatList();
        IntList idx = new IntList();
        float layerStartZ = 0;
        boolean layerHasExtrusion = false;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 65536)) {
            String line;
            while ((line = br.readLine()) != null) {
                int len = line.length();
                int p = 0;
                while (p < len && line.charAt(p) <= ' ') p++;
                if (p >= len) continue;
                char c0 = line.charAt(p);
                if (c0 == ';') {
                    if (regionMatchesIgnoreCase(line, p + 1, "LAYER_CHANGE")) {
                        // New layer boundary: flush the previous layer if it has content.
                        if (!verts.isEmpty() && layerHasExtrusion) {
                            flushLayer(layerVertices, layerIndices, layerZ, verts, idx,
                                    layerStartZ, maxVerticesPerLayer);
                        }
                        verts.clear();
                        idx.clear();
                        layerHasExtrusion = false;
                        layerStartZ = z;
                        extrudingSegment = false;
                    }
                    continue;
                }
                // Command token: up to 3 chars (G0/G1/G00/G01/M82/M83), case-insensitive.
                int e0 = p;
                while (e0 < len && line.charAt(e0) > ' ') e0++;
                int cmdLen = e0 - p;
                if (cmdLen < 2 || cmdLen > 3) continue;
                char a = Character.toUpperCase(line.charAt(p));
                char b = cmdLen > 1 ? Character.toUpperCase(line.charAt(p + 1)) : 0;
                char c = cmdLen > 2 ? Character.toUpperCase(line.charAt(p + 2)) : 0;
                boolean isMove = (a == 'G' && b == '0' && (cmdLen == 2 || c == '0'))
                        || (a == 'G' && b == '1' && (cmdLen == 2 || c == '1'));
                if (!isMove) {
                    if (a == 'M' && b == '8' && cmdLen == 3 && (c == '2' || c == '3')) {
                        absoluteE = (c == '2');
                    }
                    continue;
                }
                boolean isExtrudeMove = (a == 'G' && b == '1');
                float nx = Float.NaN, ny = Float.NaN, nz = Float.NaN, ne = Float.NaN;
                int q = e0;
                while (q < len) {
                    while (q < len && line.charAt(q) <= ' ') q++;
                    if (q >= len) break;
                    char axis = Character.toUpperCase(line.charAt(q));
                    int v0 = q + 1;
                    int v1 = v0;
                    while (v1 < len && line.charAt(v1) > ' ') v1++;
                    // Comment tail starts a new statement; stop scanning params.
                    if (axis == ';') break;
                    if (v1 > v0) {
                        float v = parseFloat(line, v0, v1);
                        if (!Float.isNaN(v)) {
                            switch (axis) {
                                case 'X': nx = v; break;
                                case 'Y': ny = v; break;
                                case 'Z': nz = v; break;
                                case 'E': ne = v; break;
                                default: break;
                            }
                        }
                    }
                    q = v1;
                }
                float px = x, py = y, pz = z, pe = e;
                if (!Float.isNaN(nx)) x = nx;
                if (!Float.isNaN(ny)) y = ny;
                if (!Float.isNaN(nz)) z = nz;
                if (!Float.isNaN(ne)) e = ne;
                if (isExtrudeMove) {
                    boolean extruding = absoluteE ? (e > pe + 1e-9f) : (!Float.isNaN(ne) && ne > 1e-9f);
                    if (extruding) {
                        if (!layerHasExtrusion) {
                            layerStartZ = pz;
                            layerHasExtrusion = true;
                        }
                        if (!hasPos || !extrudingSegment) {
                            // Seed the vertex list with the start point (first
                            // point ever, or resume after a travel so the strip
                            // breaks cleanly with a duplicated seam vertex).
                            verts.add(px);
                            verts.add(py);
                            verts.add(pz);
                            idx.add(verts.n / 3 - 1);
                        }
                        verts.add(x);
                        verts.add(y);
                        verts.add(z);
                        idx.add(verts.n / 3 - 1);
                        extrudingSegment = true;
                        hasPos = true;
                        minX = Math.min(minX, Math.min(px, x));
                        minY = Math.min(minY, Math.min(py, y));
                        minZ = Math.min(minZ, Math.min(pz, z));
                        maxX = Math.max(maxX, Math.max(px, x));
                        maxY = Math.max(maxY, Math.max(py, y));
                        maxZ = Math.max(maxZ, Math.max(pz, z));
                    } else {
                        extrudingSegment = false;
                        if (!Float.isNaN(nx) || !Float.isNaN(ny) || !Float.isNaN(nz)) hasPos = true;
                    }
                } else {
                    extrudingSegment = false;
                    if (!Float.isNaN(nx) || !Float.isNaN(ny) || !Float.isNaN(nz)) hasPos = true;
                }
            }
        }
        if (!verts.isEmpty() && layerHasExtrusion) {
            flushLayer(layerVertices, layerIndices, layerZ, verts, idx,
                    layerStartZ, maxVerticesPerLayer);
        }
        List<Layer> layers = new ArrayList<>(layerVertices.size());
        for (int i = 0; i < layerVertices.size(); i++) {
            layers.add(new Layer(layerVertices.get(i), layerIndices.get(i), layerZ.get(i)));
        }
        if (layers.isEmpty()) {
            minX = minY = minZ = 0;
            maxX = maxY = maxZ = 0;
        }
        return new Parsed(layers,
                new float[]{minX, minY, minZ},
                new float[]{maxX, maxY, maxZ});
    }

    private static boolean regionMatchesIgnoreCase(String line, int start, String word) {
        if (start + word.length() > line.length()) return false;
        for (int i = 0; i < word.length(); i++) {
            if (Character.toUpperCase(line.charAt(start + i)) != word.charAt(i)) return false;
        }
        return true;
    }

    /** Manual float parse over {@code s[start, end)}; NaN when not a number. Zero garbage. */
    static float parseFloat(CharSequence s, int start, int end) {
        int p = start;
        while (p < end && s.charAt(p) <= ' ') p++;
        boolean neg = false;
        if (p < end && (s.charAt(p) == '-' || s.charAt(p) == '+')) {
            neg = s.charAt(p) == '-';
            p++;
        }
        long intPart = 0;
        boolean hasDigits = false;
        while (p < end) {
            char c = s.charAt(p);
            if (c < '0' || c > '9') break;
            hasDigits = true;
            intPart = intPart * 10 + (c - '0');
            if (intPart > 999999999999L) return Float.NaN;
            p++;
        }
        double v = intPart;
        if (p < end && s.charAt(p) == '.') {
            p++;
            double frac = 0, base = 1;
            while (p < end) {
                char c = s.charAt(p);
                if (c < '0' || c > '9') break;
                hasDigits = true;
                frac = frac * 10 + (c - '0');
                base *= 10;
                p++;
            }
            v += frac / base;
        }
        if (p < end && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
            p++;
            boolean expNeg = false;
            if (p < end && (s.charAt(p) == '-' || s.charAt(p) == '+')) {
                expNeg = s.charAt(p) == '-';
                p++;
            }
            int exp = 0;
            boolean hasExp = false;
            while (p < end) {
                char c = s.charAt(p);
                if (c < '0' || c > '9') break;
                hasExp = true;
                exp = exp * 10 + (c - '0');
                p++;
            }
            if (!hasExp) return Float.NaN;
            v *= Math.pow(10, expNeg ? -exp : exp);
        }
        while (p < end && s.charAt(p) <= ' ') p++;
        if (!hasDigits || p != end) return Float.NaN;
        return (float) (neg ? -v : v);
    }

    /** Growable primitive float buffer (replaces boxed ArrayList<Float> in the hot loop). */
    static final class FloatList {
        float[] a = new float[256];
        int n = 0;

        void add(float v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }

        boolean isEmpty() {
            return n == 0;
        }

        void clear() {
            n = 0;
        }
    }

    /** Growable primitive int buffer (replaces boxed ArrayList<Integer> in the hot loop). */
    static final class IntList {
        int[] a = new int[256];
        int n = 0;

        void add(int v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }

        boolean isEmpty() {
            return n == 0;
        }

        void clear() {
            n = 0;
        }
    }

    private static void flushLayer(List<float[]> layerVertices, List<int[]> layerIndices, List<Float> layerZ,
                                   FloatList verts, IntList idx, float z, int maxVerticesPerLayer) {
        // Index pairs (2k, 2k+1) are always genuine extrusion segments:
        // travels add no vertices, so a pair never spans a travel gap.
        // Decimation must therefore keep/drop whole pairs; dropping single
        // vertices would misalign pairing and draw zigzag artifacts.
        int pairCount = idx.n / 2;
        int stride = 1;
        if (maxVerticesPerLayer > 0 && pairCount * 2 > maxVerticesPerLayer) {
            int targetPairs = Math.max(1, maxVerticesPerLayer / 2);
            stride = (pairCount + targetPairs - 1) / targetPairs;
            if (stride < 1) stride = 1;
        }
        int keptPairs = 0;
        for (int k = 0; k < pairCount; k += stride) keptPairs++;
        // Always keep the final pair so strips terminate at the real endpoint.
        int lastPair = pairCount - 1;
        boolean keepLast = pairCount > 0 && (stride <= 1 || (lastPair % stride != 0));
        if (keepLast) keptPairs++;
        // Output packs kept pairs densely, so indices are the identity.
        float[] outVerts = new float[keptPairs * 2 * 3];
        int[] outIdx = new int[keptPairs * 2];
        int o = 0;
        for (int k = 0; k < pairCount; k += stride) {
            int a = idx.a[k * 2], b = idx.a[k * 2 + 1];
            outVerts[o++] = verts.a[a * 3];
            outVerts[o++] = verts.a[a * 3 + 1];
            outVerts[o++] = verts.a[a * 3 + 2];
            outVerts[o++] = verts.a[b * 3];
            outVerts[o++] = verts.a[b * 3 + 1];
            outVerts[o++] = verts.a[b * 3 + 2];
        }
        if (keepLast) {
            int a = idx.a[lastPair * 2], b = idx.a[lastPair * 2 + 1];
            outVerts[o++] = verts.a[a * 3];
            outVerts[o++] = verts.a[a * 3 + 1];
            outVerts[o++] = verts.a[a * 3 + 2];
            outVerts[o++] = verts.a[b * 3];
            outVerts[o++] = verts.a[b * 3 + 1];
            outVerts[o++] = verts.a[b * 3 + 2];
        }
        for (int i = 0; i < outIdx.length; i++) outIdx[i] = i;
        layerVertices.add(outVerts);
        layerIndices.add(outIdx);
        layerZ.add(z);
    }
}
