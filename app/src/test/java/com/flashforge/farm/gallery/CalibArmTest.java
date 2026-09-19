package com.flashforge.farm.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.flashforge.farm.slic3r.SandboxProto;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Guards the PA calibration slice contract end to end (minus the native
 * engine, which unit tests can't load):
 * <ol>
 * <li>every calib gallery kind arms the native CalibMode ordinal the
 * engine expects (line = 1, pattern = 2, tower = 3);</li>
 * <li>the default sweep modifiers are sane (positive step count);</li>
 * <li>each calib model is real, bed-sitting geometry (not an empty or
 * degenerate mesh);</li>
 * <li>the slice bundle carries the calib modifier keys to native.</li>
 * </ol>
 */
public class CalibArmTest {
    @Test
    public void kindsMapToNativeCalibModes() {
        assertEquals(CalibArm.MODE_PA_LINE, CalibArm.modeForKind(ShapeGallery.KIND_CALIB_PA_LINE));
        assertEquals(CalibArm.MODE_PA_PATTERN, CalibArm.modeForKind(ShapeGallery.KIND_CALIB_PA_PATTERN));
        assertEquals(CalibArm.MODE_PA_TOWER, CalibArm.modeForKind(ShapeGallery.KIND_CALIB_PA_TOWER));
        // Must match Slic3r::CalibMode ordinals consumed by model_slice.
        assertEquals(1, CalibArm.MODE_PA_LINE);
        assertEquals(2, CalibArm.MODE_PA_PATTERN);
        assertEquals(3, CalibArm.MODE_PA_TOWER);
        assertEquals(0, CalibArm.MODE_NONE);
    }

    @Test
    public void onlyCalibKindsArm() {
        assertTrue(CalibArm.isCalibKind(ShapeGallery.KIND_CALIB_PA_LINE));
        assertTrue(CalibArm.isCalibKind(ShapeGallery.KIND_CALIB_PA_PATTERN));
        assertTrue(CalibArm.isCalibKind(ShapeGallery.KIND_CALIB_PA_TOWER));
        assertFalse(CalibArm.isCalibKind(ShapeGallery.KIND_CUBE));
        assertFalse(CalibArm.isCalibKind(ShapeGallery.KIND_CUSTOM));
        assertFalse(CalibArm.isCalibKind(ShapeGallery.KIND_TAG));
        try {
            CalibArm.modeForKind(ShapeGallery.KIND_CUBE);
            fail("plain model kind must not map to a calib mode");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void defaultSweepIsSane() {
        assertEquals(0.0, CalibArm.DEFAULT_START, 0.0);
        assertTrue(CalibArm.DEFAULT_END > CalibArm.DEFAULT_START);
        assertTrue(CalibArm.DEFAULT_STEP > 0);
        // 0..0.1 @ 0.002 mirrors the native generate_test count formula.
        assertEquals(51, CalibArm.defaultStepCount());
    }

    @Test
    public void calibModelsAreRealBedSittingGeometry() {
        assertBedSitting(CalibModels.paLine());
        assertBedSitting(CalibModels.paPattern());
        assertBedSitting(CalibModels.paTower());
        // Tower must actually rise; line/pattern are single-layer.
        assertTrue(maxZ(CalibModels.paTower()) > maxZ(CalibModels.paLine()));
        assertTrue(maxZ(CalibModels.paTower()) > maxZ(CalibModels.paPattern()));
    }

    @Test
    public void galleryExposesAllThreeCalibModels() throws Exception {
        Set<Integer> kinds = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (ShapeGallery.Item item : ShapeGallery.builtins()) {
            if (CalibArm.isCalibKind(item.kind)) {
                kinds.add(item.kind);
                assertTrue(ids.add(item.id));
                assertNotNull(ShapeGallery.meshFor(item));
                assertTrue(ShapeGallery.meshFor(item).triCount > 0);
            }
        }
        assertTrue(kinds.contains(ShapeGallery.KIND_CALIB_PA_LINE));
        assertTrue(kinds.contains(ShapeGallery.KIND_CALIB_PA_PATTERN));
        assertTrue(kinds.contains(ShapeGallery.KIND_CALIB_PA_TOWER));
    }

    @Test
    public void sliceBundleCarriesCalibModifiers() {
        // Contract with SliceSandboxService: these keys ferry the sweep to native.
        assertEquals("calibMode", SandboxProto.KEY_CALIB_MODE);
        assertEquals("calibStart", SandboxProto.KEY_CALIB_START);
        assertEquals("calibEnd", SandboxProto.KEY_CALIB_END);
        assertEquals("calibStep", SandboxProto.KEY_CALIB_STEP);
    }

    private static void assertBedSitting(GalleryMesh mesh) {
        assertNotNull(mesh);
        assertTrue(mesh.triCount > 0);
        float[] b = new float[6];
        mesh.bounds(b);
        for (float v : b) {
            assertTrue(Float.isFinite(v));
        }
        assertTrue(b[3] > b[0] && b[4] > b[1] && b[5] > b[2]);
        assertEquals(0f, b[2], 1e-6f);
    }

    private static float maxZ(GalleryMesh mesh) {
        float[] b = new float[6];
        mesh.bounds(b);
        return b[5];
    }
}
