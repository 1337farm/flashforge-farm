package com.flashforge.farm.render;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FrustumCullerTest {

    /** Identity VP: NDC cube [-1,1]^3. */
    private static double[][] identityPlanes() {
        double[] vp = new double[16];
        vp[0] = vp[5] = vp[10] = vp[15] = 1;
        return FrustumCuller.planes(vp);
    }

    @Test
    public void testInsideSphereVisible() {
        assertTrue(FrustumCuller.sphereVisible(identityPlanes(), 0, 0, 0, 0.5));
    }

    @Test
    public void testOutsideSphereCulled() {
        assertFalse(FrustumCuller.sphereVisible(identityPlanes(), 5, 0, 0, 0.5));
        assertFalse(FrustumCuller.sphereVisible(identityPlanes(), 0, -5, 0, 0.5));
        assertFalse(FrustumCuller.sphereVisible(identityPlanes(), 0, 0, 5, 0.5));
    }

    @Test
    public void testIntersectingSphereVisible() {
        // Straddles the right plane: must render (fail-open on partial).
        assertTrue(FrustumCuller.sphereVisible(identityPlanes(), 0.9, 0, 0, 0.5));
    }

    @Test
    public void testNegativeRadiusFailsOpen() {
        assertTrue(FrustumCuller.sphereVisible(identityPlanes(), 50, 50, 50, -1));
    }

    @Test
    public void testSphereHelper() {
        double[] s = FrustumCuller.sphere(-1, -1, -1, 1, 1, 1);
        assertTrue(Math.abs(s[0]) < 1e-9 && Math.abs(s[1]) < 1e-9 && Math.abs(s[2]) < 1e-9);
        assertTrue(Math.abs(s[3] - Math.sqrt(3)) < 1e-9);
    }
}
