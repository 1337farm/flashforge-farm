package com.flashforge.farm.slic3r;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
     * Parse extrusion toolpaths from a sliced .gcode file.
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

        List<Float> verts = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        float layerStartZ = 0;
        boolean layerHasExtrusion = false;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 65536)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith(";")) {
                    String upper = line.toUpperCase(Locale.US);
                    if (upper.startsWith(";LAYER_CHANGE")) {
                        // New layer boundary: flush the previous layer if it has content.
                        if (!verts.isEmpty() && layerHasExtrusion) {
                            flushLayer(layerVertices, layerIndices, layerZ, verts, idx, layerStartZ,
                                    maxVerticesPerLayer);
                        }
                        verts = new ArrayList<>();
                        idx = new ArrayList<>();
                        layerHasExtrusion = false;
                        layerStartZ = z;
                        extrudingSegment = false;
                    } else if (upper.startsWith("M82")) {
                        // Absolute mode commands can also appear as comments in some exports; handled below too.
                    }
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length == 0) continue;
                String cmd = parts[0].toUpperCase(Locale.US);
                if (cmd.equals("M82")) {
                    absoluteE = true;
                    continue;
                }
                if (cmd.equals("M83")) {
                    absoluteE = false;
                    continue;
                }
                if (!cmd.equals("G0") && !cmd.equals("G1") && !cmd.equals("G00") && !cmd.equals("G01")) {
                    continue;
                }
                Float nx = null, ny = null, nz = null, ne = null;
                for (int i = 1; i < parts.length; i++) {
                    String p = parts[i];
                    if (p.length() < 2) continue;
                    char axis = Character.toUpperCase(p.charAt(0));
                    try {
                        float v = Float.parseFloat(p.substring(1));
                        switch (axis) {
                            case 'X': nx = v; break;
                            case 'Y': ny = v; break;
                            case 'Z': nz = v; break;
                            case 'E': ne = v; break;
                            default: break;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                float px = x, py = y, pz = z, pe = e;
                if (nx != null) x = nx;
                if (ny != null) y = ny;
                if (nz != null) z = nz;
                if (ne != null) e = ne;
                if (cmd.equals("G1") || cmd.equals("G01")) {
                    boolean extruding = absoluteE ? (e > pe + 1e-9f) : (ne != null && ne > 1e-9f);
                    if (extruding) {
                        if (!layerHasExtrusion) {
                            layerStartZ = pz;
                            layerHasExtrusion = true;
                        }
                        if (!hasPos) {
                            // First extrusion point: seed the vertex list with the start point.
                            verts.add(px); verts.add(py); verts.add(pz);
                            idx.add(verts.size() / 3 - 1);
                        } else if (!extrudingSegment) {
                            // Resume after a travel: duplicate the seam vertex so the strip breaks cleanly.
                            verts.add(px); verts.add(py); verts.add(pz);
                            idx.add(verts.size() / 3 - 1);
                        }
                        verts.add(x); verts.add(y); verts.add(z);
                        idx.add(verts.size() / 3 - 1);
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
                        if (nx != null || ny != null || nz != null) hasPos = true;
                    }
                } else {
                    extrudingSegment = false;
                    if (nx != null || ny != null || nz != null) hasPos = true;
                }
            }
        }
        if (!verts.isEmpty() && layerHasExtrusion) {
            flushLayer(layerVertices, layerIndices, layerZ, verts, idx, layerStartZ, maxVerticesPerLayer);
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

    private static void flushLayer(List<float[]> layerVertices, List<int[]> layerIndices, List<Float> layerZ,
                                   List<Float> verts, List<Integer> idx, float z, int maxVerticesPerLayer) {
        int vertexCount = verts.size() / 3;
        int[] outIdx;
        float[] outVerts;
        if (maxVerticesPerLayer > 0 && vertexCount > maxVerticesPerLayer) {
            // Decimate by stride, preserving index runs via remap.
            int stride = (vertexCount + maxVerticesPerLayer - 1) / maxVerticesPerLayer;
            int[] remap = new int[vertexCount];
            int kept = 0;
            for (int i = 0; i < vertexCount; i++) {
                if (i % stride == 0 || i == vertexCount - 1) {
                    remap[i] = kept++;
                } else {
                    remap[i] = -1;
                }
            }
            outVerts = new float[kept * 3];
            int o = 0;
            for (int i = 0; i < vertexCount; i++) {
                if (remap[i] >= 0) {
                    outVerts[o++] = verts.get(i * 3);
                    outVerts[o++] = verts.get(i * 3 + 1);
                    outVerts[o++] = verts.get(i * 3 + 2);
                }
            }
            List<Integer> keptIdx = new ArrayList<>(idx.size());
            for (int id : idx) {
                int r = remap[id];
                if (r >= 0) keptIdx.add(r);
            }
            outIdx = new int[keptIdx.size()];
            for (int i = 0; i < keptIdx.size(); i++) outIdx[i] = keptIdx.get(i);
        } else {
            outVerts = new float[verts.size()];
            for (int i = 0; i < verts.size(); i++) outVerts[i] = verts.get(i);
            outIdx = new int[idx.size()];
            for (int i = 0; i < idx.size(); i++) outIdx[i] = idx.get(i);
        }
        layerVertices.add(outVerts);
        layerIndices.add(outIdx);
        layerZ.add(z);
    }
}
