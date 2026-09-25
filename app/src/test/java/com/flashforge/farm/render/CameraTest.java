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
    public void testMove_translatesPositionAndOriginEqually() {
        Camera c = defaultCamera();
        double px = c.position.x, py = c.position.y, pz = c.position.z;
        double ox = c.origin.x, oy = c.origin.y, oz = c.origin.z;
        c.move(10f, -5f);
        assertEquals(c.position.x - px, c.origin.x - ox, DELTA);
        assertEquals(c.position.y - py, c.origin.y - oy, DELTA);
        assertEquals(c.position.z - pz, c.origin.z - oz, DELTA);
        assertTrue(c.position.x != px || c.position.y != py);
    }

    @Test
    public void testCalcScreenMovement_finite() {
        Vec3d v = defaultCamera().calcScreenMovement(10f, -5f);
        assertTrue(Double.isFinite(v.x));
        assertTrue(Double.isFinite(v.y));
        assertTrue(Double.isFinite(v.z));
        assertTrue(v.x != 0 || v.y != 0 || v.z != 0);
    }

    @Test
    public void testZoomBy_multiplicativeAndClamped() {
        Camera c = defaultCamera();
        c.zoomBy(2f);
        assertEquals(2f, c.getZoom(), 0f);
        c.zoomBy(0.5f);
        assertEquals(1f, c.getZoom(), 0f);
        c.zoomBy(100f);
        assertEquals(10f, c.getZoom(), 0f);
        c.zoomBy(0.0001f);
        assertEquals(0.6f, c.getZoom(), 0f);
        // Non-positive / NaN factors are ignored, never corrupt zoom.
        c.setZoom(1f);
        c.zoomBy(0f);
        assertEquals(1f, c.getZoom(), 0f);
        c.zoomBy(Float.NaN);
        assertEquals(1f, c.getZoom(), 0f);
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
    public void testMoveWorld_matchesMoveAtZoomOne() {
        Camera a = defaultCamera();
        Camera b = defaultCamera();
        a.move(10f, -5f);
        b.moveWorld(10f, -5f);
        assertEquals(a.position.x, b.position.x, DELTA);
        assertEquals(a.position.y, b.position.y, DELTA);
        assertEquals(a.position.z, b.position.z, DELTA);
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
