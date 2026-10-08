package com.flashforge.farm.slic3r;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

public class GCodeToolpathsTest {
    private static File writeGcode(String body) throws Exception {
        File f = File.createTempFile("toolpaths", ".gcode");
        f.deleteOnExit();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(body);
        }
        return f;
    }

    @Test
    public void parsesLayersWithAbsoluteE() throws Exception {
        File f = writeGcode(
                "M82\n" +
                "G1 Z0.2 F300\n" +
                "G1 X0 Y0 Z0.2 E0\n" +
                "G1 X10 Y0 Z0.2 E1\n" +
                "G1 X10 Y10 Z0.2\n" +
                ";LAYER_CHANGE\n" +
                "G1 Z0.4 F300\n" +
                "G1 X0 Y0 Z0.4 E1\n" +
                "G1 X5 Y0 Z0.4 E2\n");
        GCodeToolpaths.Parsed parsed = GCodeToolpaths.parse(f, 0);
        assertEquals(2, parsed.getLayersCount());
        assertTrue(parsed.layers.get(0).vertices.length >= 6);
        assertEquals(parsed.layers.get(0).vertices.length / 3, parsed.layers.get(0).indices.length);
        assertEquals(0.2f, parsed.layers.get(0).z, 1e-3f);
        assertEquals(0.4f, parsed.layers.get(1).z, 1e-3f);
    }

    @Test
    public void parsesLayersWithAbsoluteE_skipZMovesWithoutE() throws Exception {
        File f = writeGcode(
                "G1 Z0.2\n" +
                "G1 X0 Y0 Z0.2\n" +
                "G1 X10 Y0 Z0.2\n" +
                "G1 X10 Y10 Z0.2\n" +
                ";LAYER_CHANGE\n" +
                "G1 Z0.4\n" +
                "G1 X0 Y0 Z0.4 E1\n" +
                "G1 X5 Y0 Z0.4 E2\n");
        GCodeToolpaths.Parsed parsed = GCodeToolpaths.parse(f, 0);
        assertEquals(1, parsed.getLayersCount());
        assertEquals(3, parsed.layers.get(0).indices.length);
    }

    @Test
    public void parsesLayersWithAbsoluteE_skipNoEAfterTravel() throws Exception {
        File f = writeGcode(
                "G1 X0 Y0 Z0.2\n" +
                "G1 X10 Y0 Z0.2 E1\n" +
                "G1 X10 Y10 Z0.2\n" +
                ";LAYER_CHANGE\n" +
                "G1 Z0.4\n" +
                "G1 X0 Y0 Z0.4\n" +
                "G1 X5 Y0 Z0.4 E2\n");
        GCodeToolpaths.Parsed parsed = GCodeToolpaths.parse(f, 0);
        assertEquals(2, parsed.getLayersCount());
        assertEquals(2, parsed.layers.get(0).indices.length);
    }

    @Test
    public void emptyFileYieldsNoLayers() throws Exception {
        File f = writeGcode("G28\nM104 S200\n");
        GCodeToolpaths.Parsed parsed = GCodeToolpaths.parse(f, 0);
        assertEquals(0, parsed.getLayersCount());
    }

    @Test
    public void decimationCapsVertices() throws Exception {
        StringBuilder sb = new StringBuilder("M82\nG1 X0 Y0 Z0.2 E0\n");
        for (int i = 1; i <= 200; i++) {
            sb.append("G1 X").append(i).append(" Y0 Z0.2 E").append(i).append('\n');
        }
        GCodeToolpaths.Parsed parsed = GCodeToolpaths.parse(writeGcode(sb.toString()), 32);
        assertEquals(1, parsed.getLayersCount());
        assertTrue(parsed.layers.get(0).vertices.length / 3 <= 33);
    }

    @Test
    public void parseFloatMatches() {
        assertEquals(10.5f, GCodeToolpaths.parseFloat("X10.5", 1, 5), 1e-6f);
        assertEquals(-0.2f, GCodeToolpaths.parseFloat("-0.2", 0, 4), 1e-6f);
        assertEquals(200f, GCodeToolpaths.parseFloat("200", 0, 3), 1e-6f);
        assertEquals(1e3f, GCodeToolpaths.parseFloat("1e3", 0, 3), 1e-3f);
        assertTrue(Float.isNaN(GCodeToolpaths.parseFloat("abc", 0, 3)));
        assertTrue(Float.isNaN(GCodeToolpaths.parseFloat("", 0, 0)));
    }

    @Test(timeout = 90000)
    public void largeFileParsesFast() throws Exception {
        // ~400k extrusion moves: the old boxed/regex parser took ~a minute
        // (UI freeze); the garbage-free loop must clear it far quicker.
        StringBuilder sb = new StringBuilder("M82\nG1 X0 Y0 Z0.2 E0\n");
        for (int layer = 0; layer < 40; layer++) {
            sb.append(";LAYER_CHANGE\n");
            for (int i = 1; i <= 10000; i++) {
                sb.append("G1 X").append(i % 200).append(" Y").append(i % 200)
                        .append(" Z0.2 E").append(layer * 10000 + i).append('\n');
            }
        }
        File f = writeGcode(sb.toString());
        long t0 = System.nanoTime();
        GCodeToolpaths.Parsed parsed = GCodeToolpaths.parse(f, 0);
        long ms = (System.nanoTime() - t0) / 1000000;
        assertEquals(40, parsed.getLayersCount());
        System.out.println("largeFileParsesFast ms=" + ms);
    }

    @Test
    public void decimationKeepsPairAlignment() throws Exception {
        // Index pairs (2k, 2k+1) are genuine extrusion segments; decimation
        // must keep/drop whole pairs or the renderer draws zigzag artifacts.
        StringBuilder sb = new StringBuilder("M82\nG1 X0 Y0 Z0.2 E0\n");
        for (int i = 1; i <= 100; i++) {
            sb.append("G1 X").append(i).append(" Y0 Z0.2 E").append(i).append('\n');
        }
        GCodeToolpaths.Parsed parsed = GCodeToolpaths.parse(writeGcode(sb.toString()), 40);
        assertEquals(1, parsed.getLayersCount());
        int[] idx = parsed.layers.get(0).indices;
        float[] v = parsed.layers.get(0).vertices;
        assertTrue(v.length / 3 <= 42);
        // Every kept pair must be a forward step along +X (no zigzag). A
        // trailing unpaired index (mid-strip layer end) is legitimate.
        int pairsEnd = idx.length - (idx.length % 2);
        for (int k = 0; k + 1 < pairsEnd; k += 2) {
            float x0 = v[idx[k] * 3];
            float x1 = v[idx[k + 1] * 3];
            assertTrue("pair " + k + " goes backwards: " + x0 + " -> " + x1, x1 >= x0);
        }
    }
}
