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
}
