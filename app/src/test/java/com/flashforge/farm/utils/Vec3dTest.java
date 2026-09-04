package com.flashforge.farm.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class Vec3dTest {

    private static final double DELTA = 1e-10;

    @Test
    public void testNormalize_unitLength() {
        Vec3d v = new Vec3d(3, 4, 0).normalize();
        assertEquals(1.0, v.magnitude(), DELTA);
    }

    @Test
    public void testNormalize_zeroStaysZero() {
        Vec3d v = new Vec3d(0, 0, 0).normalize();
        assertEquals(0.0, v.magnitude(), DELTA);
    }

    @Test
    public void testAdd() {
        Vec3d v = new Vec3d(1, 2, 3).add(new Vec3d(4, 5, 6));
        assertEquals(5.0, v.x, DELTA);
        assertEquals(7.0, v.y, DELTA);
        assertEquals(9.0, v.z, DELTA);
    }

    @Test
    public void testCrossProduct_orthogonal() {
        Vec3d x = new Vec3d(1, 0, 0);
        Vec3d y = new Vec3d(0, 1, 0);
        Vec3d z = x.crossProduct(y);
        assertEquals(0.0, z.x, DELTA);
        assertEquals(0.0, z.y, DELTA);
        assertEquals(1.0, z.z, DELTA);
    }

    @Test
    public void testMagnitude_distance() {
        assertEquals(5.0, new Vec3d(3, 4, 0).magnitude(), DELTA);
        assertEquals(5.0, new Vec3d(0, 0, 0).distance(new Vec3d(3, 4, 0)), DELTA);
    }

    @Test
    public void testNegate_multiply() {
        Vec3d v = new Vec3d(1, -2, 3).negate();
        assertEquals(-1.0, v.x, DELTA);
        assertEquals(2.0, v.y, DELTA);
        assertEquals(-3.0, v.z, DELTA);
        v.multiply(2);
        assertEquals(-2.0, v.x, DELTA);
    }
}
