package com.flashforge.farm.render;

import com.flashforge.farm.utils.Vec3d;

import org.junit.Test;
import static org.junit.Assert.*;

public class CameraTest {

    private static final double DELTA = 1e-9;

    private static Camera defaultCamera() {
        Camera c = new Camera();
        c.position = new Vec3d(0, -200, 200);
        c.origin = new Vec3d(0, 0, 0);
        c.up = new Vec3d(0, 0, 1);
        return c;
    }

    @Test
    public void testInitialZoom_isOne() {
        assertEquals(1f, new Camera().getZoom(), 0f);
    }

    @Test
    public void testViewMatrix_shape() {
        double[] m = defaultCamera().getViewModelMatrix();
        assertEquals(16, m.length);
        for (double v : m) {
            assertTrue(Double.isFinite(v));
        }
    }

    @Test
    public void testDollyBy_movesAlongViewAxis() {
        Camera c = defaultCamera();
        double before = c.currentDistance();
        c.setDefaultDistance(before);
        c.dollyBy(2.0);
        // Factor 2 halves the camera-to-target distance; the target never moves.
        assertEquals(before / 2.0, c.currentDistance(), 1e-6);
        assertEquals(0.0, c.origin.x, DELTA);
        assertEquals(0.0, c.origin.y, DELTA);
        assertEquals(0.0, c.origin.z, DELTA);
        // Direction from target to camera is unchanged (pure dolly, no orbit).
        assertEquals(0.0, c.position.x, 1e-6);
        assertEquals(c.position.y / c.position.z, -200.0 / 200.0, 1e-9);
        c.dollyBy(0.5);
        assertEquals(before, c.currentDistance(), 1e-6);
    }

    @Test
    public void testDollyBy_clampedToFramingRange() {
        Camera c = defaultCamera();
        double d = c.currentDistance();
        c.setDefaultDistance(d);
        c.dollyBy(100.0);
        assertEquals(d / 10.0, c.currentDistance(), 1e-6);
        c.dollyBy(0.0001);
        assertEquals(d / 0.6f, c.currentDistance(), 1e-6);
        // Non-positive / NaN factors are ignored, never corrupt the camera.
        c.dollyBy(0.0);
        assertEquals(d / 0.6f, c.currentDistance(), 1e-6);
        c.dollyBy(Double.NaN);
        assertEquals(d / 0.6f, c.currentDistance(), 1e-6);
    }

    @Test
    public void testZoomRatio_tracksDolly() {
        Camera c = defaultCamera();
        // No reference distance yet: neutral ratio.
        assertEquals(1.0, c.zoomRatio(), 0.0);
        double d = c.currentDistance();
        c.setDefaultDistance(d);
        assertEquals(1.0, c.zoomRatio(), 1e-9);
        c.dollyBy(4.0);
        assertEquals(4.0, c.zoomRatio(), 1e-6);
    }

    @Test
    public void testZoomOrthoBy_multiplicativeAndClamped() {
        Camera c = defaultCamera();
        c.zoomOrthoBy(2f);
        assertEquals(2f, c.getZoom(), 0f);
        c.zoomOrthoBy(0.5f);
        assertEquals(1f, c.getZoom(), 0f);
        c.zoomOrthoBy(100f);
        assertEquals(10f, c.getZoom(), 0f);
        c.zoomOrthoBy(0.0001f);
        assertEquals(0.6f, c.getZoom(), 0f);
        // Non-positive / NaN factors are ignored, never corrupt zoom.
        c.zoomOrthoBy(0f);
        assertEquals(0.6f, c.getZoom(), 0f);
        c.zoomOrthoBy(Float.NaN);
        assertEquals(0.6f, c.getZoom(), 0f);
    }

    @Test
    public void testMoveByWorld_translatesPositionAndOriginEqually() {
        Camera c = defaultCamera();
        c.moveByWorld(3.0, -4.0, 0.0);
        assertEquals(3.0, c.position.x, DELTA);
        assertEquals(-204.0, c.position.y, DELTA);
        assertEquals(200.0, c.position.z, DELTA);
        assertEquals(3.0, c.origin.x, DELTA);
        assertEquals(-4.0, c.origin.y, DELTA);
        assertEquals(0.0, c.origin.z, DELTA);
    }

    @Test
    public void testMoveWorld_translatesPositionAndOriginEqually() {
        Camera c = defaultCamera();
        double px = c.position.x, py = c.position.y, pz = c.position.z;
        double ox = c.origin.x, oy = c.origin.y, oz = c.origin.z;
        c.moveWorld(10f, -5f);
        assertEquals(c.position.x - px, c.origin.x - ox, DELTA);
        assertEquals(c.position.y - py, c.origin.y - oy, DELTA);
        assertEquals(c.position.z - pz, c.origin.z - oz, DELTA);
        assertTrue(c.position.x != px || c.position.y != py);
    }

    @Test
    public void testRotateAround_clampsPitchBelow89() {
        // Straight-down/up makes the view direction parallel to the up
        // vector and setLookAtM degenerate (frame flip). Huge drags must
        // stop at ±89° with finite direction.
        Camera c = defaultCamera();
        c.rotateAround(0, 10000);
        Vec3d down = c.getDirForward();
        assertTrue(Double.isFinite(down.x + down.y + down.z));
        assertTrue(Math.abs(down.z) <= Math.sin(Math.toRadians(89)) + 1e-9);
        Camera c2 = defaultCamera();
        c2.rotateAround(0, -10000);
        Vec3d up = c2.getDirForward();
        assertTrue(Double.isFinite(up.x + up.y + up.z));
        assertTrue(Math.abs(up.z) <= Math.sin(Math.toRadians(89)) + 1e-9);
    }

    @Test
    public void testRotateAround_keepsDistance() {
        Camera c = defaultCamera();
        double dx = c.position.x - c.origin.x;
        double dy = c.position.y - c.origin.y;
        double dz = c.position.z - c.origin.z;
        double before = Math.sqrt(dx * dx + dy * dy + dz * dz);
        c.rotateAround(10, 5);
        dx = c.position.x - c.origin.x;
        dy = c.position.y - c.origin.y;
        dz = c.position.z - c.origin.z;
        double after = Math.sqrt(dx * dx + dy * dy + dz * dz);
        assertEquals(before, after, 1e-6);
    }

    @Test
    public void testRotateAround_frontPitchChangesCameraOnly() {
        Camera c = new Camera();
        c.position = new Vec3d(0, -100, 0);
        c.origin = new Vec3d(0, 0, 0);
        c.rotateAround(0, -20);
        double radius = Math.sqrt(c.position.x * c.position.x
                + c.position.y * c.position.y + c.position.z * c.position.z);
        double pitch = Math.toDegrees(Math.asin(c.position.z / radius));
        assertEquals(20.0, pitch, 1e-6);
        assertEquals(0.0, c.position.x, 1e-6);
        assertEquals(0.0, c.origin.x, 1e-6);
        assertEquals(0.0, c.origin.y, 1e-6);
        assertEquals(0.0, c.origin.z, 1e-6);
    }

    @Test
    public void testRotateAround_yawTakesDegreesNotRadians() {
        Camera c = new Camera();
        c.position = new Vec3d(0, -100, 0);
        c.origin = new Vec3d(0, 0, 0);
        c.rotateAround(0, -20);
        double pitchBefore = Math.toDegrees(Math.asin(c.position.z
                / Math.sqrt(c.position.x * c.position.x + c.position.y * c.position.y + c.position.z * c.position.z)));

        c.rotateAround(1, 0);
        double yaw = Math.toDegrees(Math.atan2(-c.position.x, -c.position.y));
        double pitchAfter = Math.toDegrees(Math.asin(c.position.z
                / Math.sqrt(c.position.x * c.position.x + c.position.y * c.position.y + c.position.z * c.position.z)));
        assertEquals(1.0, yaw, 1e-6);
        assertEquals(pitchBefore, pitchAfter, 1e-6);

        Camera c2 = new Camera();
        c2.position = new Vec3d(0, -100, 0);
        c2.origin = new Vec3d(0, 0, 0);
        c2.rotateAround(90, 0);
        assertEquals(90.0, Math.toDegrees(Math.atan2(-c2.position.x, -c2.position.y)), 1e-6);
        assertTrue(Math.abs(c2.position.y) < 1e-3);
    }
}
