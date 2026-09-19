package com.flashforge.farm.config;

import com.flashforge.farm.slic3r.Slic3rConfigWrapper;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Guards the Java config flow that feeds the native slicer: profile INI parse
 * -> key migration -> serialization of slic3r_current.ini. A bool/enum value
 * mismatch here surfaces on device as a slice failure; these tests pin the
 * value-semantics of the key migrations so a legacy bool can never leak an
 * invalid enum token into the engine.
 */
public class ConfigSerializeFlowTest {

    private Slic3rConfigWrapper parse(String ini) throws Exception {
        return new Slic3rConfigWrapper(new ByteArrayInputStream(ini.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void gapFillEnabledBoolMigratesToValidEnum() throws Exception {
        // Legacy OrcaSlicer/PrusaSlicer process profiles stored gap_fill_enabled
        // as a boolean. It migrates to gap_fill_target (enum). The serialized
        // value must be an enum name, never a bare "0"/"1"/"true"/"false".
        String ini = "[print:gapfill]\n" +
                "gap_fill_enabled = 1\n";
        Slic3rConfigWrapper w = parse(ini);
        String serialized = w.serialize();
        assertTrue("serialized must contain migrated key", serialized.contains("gap_fill_target"));
        assertFalse("legacy bool name must not be serialized", serialized.contains("gap_fill_enabled"));
        assertTrue("bool 1 must become enum 'everywhere'",
                serialized.contains("gap_fill_target = everywhere"));
    }

    @Test
    public void gapFillDisabledBoolMigratesToNowhere() throws Exception {
        String ini = "[print:gapfill]\ngap_fill_enabled = 0\n";
        Slic3rConfigWrapper w = parse(ini);
        String serialized = w.serialize();
        assertTrue(serialized.contains("gap_fill_target = nowhere"));
    }

    @Test
    public void gapFillAlreadyEnumValueIsUntouched() throws Exception {
        // A profile already using the engine enum value must round-trip verbatim.
        String ini = "[print:gapfill]\ngap_fill_target = topbottom\n";
        Slic3rConfigWrapper w = parse(ini);
        String serialized = w.serialize();
        assertTrue(serialized.contains("gap_fill_target = topbottom"));
    }

    @Test
    public void normalizeSerializedValueDirect() {
        assertEquals("everywhere", ConfigObject.normalizeSerializedValue("gap_fill_target", "1"));
        assertEquals("everywhere", ConfigObject.normalizeSerializedValue("gap_fill_target", "true"));
        assertEquals("nowhere", ConfigObject.normalizeSerializedValue("gap_fill_target", "0"));
        assertEquals("nowhere", ConfigObject.normalizeSerializedValue("gap_fill_target", "false"));
        assertEquals("topbottom", ConfigObject.normalizeSerializedValue("gap_fill_target", "topbottom"));
        assertNull(ConfigObject.normalizeSerializedValue("gap_fill_target", null));
    }

    @Test
    public void configObjectPutMigratesKeyAndKeepsValue() {
        ConfigObject cfg = new ConfigObject("test");
        cfg.put("gap_fill_enabled", "1");
        assertTrue("put() must migrate the key", cfg.values.containsKey("gap_fill_target"));
        assertFalse(cfg.values.containsKey("gap_fill_enabled"));
        String out = cfg.serialize();
        assertTrue(out.contains("gap_fill_target = everywhere"));
    }
}