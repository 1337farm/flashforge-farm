package com.flashforge.farm.render;

/**
 * Pure-JVM frustum culling for plate objects.
 *
 * Extracts the six frustum planes from a view-projection matrix and tests
 * bounding spheres, so fully off-screen objects skip their (potentially
 * million-triangle) draw entirely. No Android dependencies: plain JUnit
 * covers plane extraction and the sphere test. Fail-open by contract:
 * callers render whenever this is inconclusive.
 */
public final class FrustumCuller {
    private FrustumCuller() {
    }

    /** Six normalized planes (a,b,c,d) from rows of the column-major VP matrix. */
    public static double[][] planes(double[] vp) {
        double[][] r = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int c = 0; c < 4; c++) r[i][c] = vp[c * 4 + i];
        double[][] out = new double[6][];
        out[0] = norm(new double[]{r[3][0] + r[0][0], r[3][1] + r[0][1], r[3][2] + r[0][2], r[3][3] + r[0][3]});
        out[1] = norm(new double[]{r[3][0] - r[0][0], r[3][1] - r[0][1], r[3][2] - r[0][2], r[3][3] - r[0][3]});
        out[2] = norm(new double[]{r[3][0] + r[1][0], r[3][1] + r[1][1], r[3][2] + r[1][2], r[3][3] + r[1][3]});
        out[3] = norm(new double[]{r[3][0] - r[1][0], r[3][1] - r[1][1], r[3][2] - r[1][2], r[3][3] - r[1][3]});
        out[4] = norm(new double[]{r[3][0] + r[2][0], r[3][1] + r[2][1], r[3][2] + r[2][2], r[3][3] + r[2][3]});
        out[5] = norm(new double[]{r[3][0] - r[2][0], r[3][1] - r[2][1], r[3][2] - r[2][2], r[3][3] - r[2][3]});
        return out;
    }

    private static double[] norm(double[] p) {
        double l = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        if (l > 1e-12) {
            p[0] /= l;
            p[1] /= l;
            p[2] /= l;
            p[3] /= l;
        }
        return p;
    }

    /** True when the sphere is at least partly inside all six planes. */
    public static boolean sphereVisible(double[][] planes, double cx, double cy, double cz, double r) {
        if (r < 0) return true;
        for (double[] p : planes) {
            if (p[0] * cx + p[1] * cy + p[2] * cz + p[3] < -r) return false;
        }
        return true;
    }

    /** Convenience: bounding sphere from min/max corners. Returns {cx,cy,cz,r}. */
    public static double[] sphere(double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, cz = (minZ + maxZ) / 2;
        double dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        return new double[]{cx, cy, cz, Math.sqrt(dx * dx + dy * dy + dz * dz) / 2};
    }
}
