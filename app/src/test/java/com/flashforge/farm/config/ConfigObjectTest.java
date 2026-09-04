package com.flashforge.farm.config;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigObjectTest {

    @Test
    public void testMigrateKey_mappedKey() {
        // "extrusion_width" is mapped to "line_width" in ConfigObject.KEY_MIGRATION
        assertEquals("line_width", ConfigObject.migrateKey("extrusion_width"));
    }

    @Test
    public void testMigrateKey_unmappedKey() {
        // "unknown_key" is not in ConfigObject.KEY_MIGRATION, should return itself
        assertEquals("unknown_key", ConfigObject.migrateKey("unknown_key"));
    }

    @Test
    public void testMigrateKey_nullKey() {
        // null is not in ConfigObject.KEY_MIGRATION, should return itself (null)
        assertNull(ConfigObject.migrateKey(null));
    }
}
