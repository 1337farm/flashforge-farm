package com.flashforge.farm.slic3r;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class SandboxProtoTest {
    @Test
    public void statusCodesAreDistinct() {
        assertNotEquals(SandboxProto.STATUS_OK, SandboxProto.STATUS_ERROR_CONTENT);
        assertNotEquals(SandboxProto.STATUS_OK, SandboxProto.STATUS_ERROR_INFRA);
        assertNotEquals(SandboxProto.STATUS_ERROR_CONTENT, SandboxProto.STATUS_ERROR_INFRA);
        assertEquals(0, SandboxProto.STATUS_OK);
    }

    @Test
    public void fdPathShape() {
        assertEquals("/proc/self/fd/0", SandboxProto.fdPath(0));
        assertEquals("/proc/self/fd/42", SandboxProto.fdPath(42));
        assertTrue(SandboxProto.fdPath(1024).startsWith("/proc/self/fd/"));
    }

    @Test
    public void fdPathRejectsNegative() {
        try {
            SandboxProto.fdPath(-1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }
}
