package com.flashforge.farm.render;

import androidx.core.math.MathUtils;

import com.flashforge.farm.utils.DoubleMatrix;
import com.flashforge.farm.utils.Vec3d;

public class Camera {
    private double[] viewMatrix = new double[16];
    private boolean viewMatrixDirty = true;

    public Vec3d position = new Vec3d(0, 0, 0);
    public Vec3d origin = new Vec3d(0, 0, 0);
    public Vec3d up = new Vec3d(0, 0, 1);

    private float zoom = 1f;

    // Pre-allocated scratch buffers — avoids Vec3d allocation on every gesture event
    private final Vec3d scratchDir = new Vec3d();
    private final Vec3d scratchUpMod = new Vec3d();
    private final Vec3d scratchRight = new Vec3d();
    private final Vec3d scratchScreenY = new Vec3d();

    public void invalidate() {
        viewMatrixDirty = true;
    }

    public Vec3d getDirToBed() {
        // origin*(1,1,0) + (-position) = (origin.x-position.x, origin.y-position.y, -position.z)
        scratchDir.x = origin.x - position.x;
        scratchDir.y = origin.y - position.y;
        scratchDir.z = -position.z;
        return scratchDir.normalize();
    }

    public Vec3d getDirForward() {
        scratchDir.x = origin.x - position.x;
        scratchDir.y = origin.y - position.y;
        scratchDir.z = origin.z - position.z;
        return scratchDir.normalize();
    }

    public double[] getViewModelMatrix() {
        if (viewMatrixDirty) {
            DoubleMatrix.setLookAtM(viewMatrix, 0,
                    position.x, position.y, position.z,
                    origin.x, origin.y, origin.z,
                    up.x, up.y, up.z);
            viewMatrixDirty = false;
        }
        return viewMatrix;
    }

    public float getZoom() {
        return zoom;
    }

    private final static float MIN_ZOOM = 0.6f;
    private final static float MAX_ZOOM = 10f;

    public void zoom(float zoom) {
        // Zoom OUT below the default view so the whole bed / large models fit on
        // screen, but stay above 0.6x: at wider fields the perspective becomes
        // fish-eyed and the raised bed grid skews when panned to one side.
        this.zoom = MathUtils.clamp(this.zoom + zoom / 25f, MIN_ZOOM, MAX_ZOOM);
    }

    public void setZoom(float zoom) {
        this.zoom = MathUtils.clamp(zoom, MIN_ZOOM, MAX_ZOOM);
    }

    /**
     * Multiplicative pinch zoom: factor > 1 zooms in, < 1 zooms out.
     * Shares the [0.6, 10] clamp with {@link #zoom(float)} so perspective
     * and orthographic projections always offer the same zoom range.
     */
    public void zoomBy(float factor) {
        if (!(factor > 0f) || Float.isNaN(factor) || Float.isInfinite(factor)) return;
        this.zoom = MathUtils.clamp(this.zoom * factor, MIN_ZOOM, MAX_ZOOM);
    }

    /** Translate camera and focus point together by a world-space delta. */
    public void moveByWorld(double dx, double dy, double dz) {
        position.x += dx; position.y += dy; position.z += dz;
        origin.x += dx; origin.y += dy; origin.z += dz;
        viewMatrixDirty = true;
    }

    public Vec3d calcScreenMovement(float x, float y) {
        x /= zoom;
        y /= zoom;
        computeScreenBasis();
        return new Vec3d(
                scratchRight.x * x + scratchScreenY.x * y,
                scratchRight.y * x + scratchScreenY.y * y,
                scratchRight.z * x + scratchScreenY.z * y
        );
    }

    // Computes scratchRight and scratchScreenY from current camera direction — no allocation.
    private void computeScreenBasis() {
        scratchDir.x = origin.x - position.x;
        scratchDir.y = origin.y - position.y;
        scratchDir.z = origin.z - position.z;
        scratchDir.normalize();

        double yaw = Math.atan2(scratchDir.x, scratchDir.y);
        double pitch = Math.asin(-scratchDir.z);
        double sinPitch = Math.sin(pitch);

        scratchUpMod.x = sinPitch * Math.sin(yaw);
        scratchUpMod.y = sinPitch * Math.cos(yaw);
        scratchUpMod.z = Math.cos(pitch);

        // right = dir × upMod
        scratchRight.x = scratchDir.y * scratchUpMod.z - scratchDir.z * scratchUpMod.y;
        scratchRight.y = scratchDir.z * scratchUpMod.x - scratchDir.x * scratchUpMod.z;
        scratchRight.z = scratchDir.x * scratchUpMod.y - scratchDir.y * scratchUpMod.x;

        // screenY = dir × right
        scratchScreenY.x = scratchDir.y * scratchRight.z - scratchDir.z * scratchRight.y;
        scratchScreenY.y = scratchDir.z * scratchRight.x - scratchDir.x * scratchRight.z;
        scratchScreenY.z = scratchDir.x * scratchRight.y - scratchDir.y * scratchRight.x;
    }

    /**
     * Screen-basis pan for pre-scaled world-unit deltas (no zoom scaling;
     * callers scale by world-per-pixel themselves).
     */
    public void moveWorld(float x, float y) {
        computeScreenBasis();

        double mx = scratchRight.x * x + scratchScreenY.x * y;
        double my = scratchRight.y * x + scratchScreenY.y * y;
        double mz = scratchRight.z * x + scratchScreenY.z * y;

        position.x += mx; position.y += my; position.z += mz;
        origin.x += mx; origin.y += my; origin.z += mz;
        viewMatrixDirty = true;
    }

    public void move(float x, float y) {
        x /= zoom;
        y /= zoom;
        computeScreenBasis();

        double mx = scratchRight.x * x + scratchScreenY.x * y;
        double my = scratchRight.y * x + scratchScreenY.y * y;
        double mz = scratchRight.z * x + scratchScreenY.z * y;

        position.x += mx; position.y += my; position.z += mz;
        origin.x += mx; origin.y += my; origin.z += mz;
        viewMatrixDirty = true;
    }

    public void rotateAround(double rx, double ry) {
        double vx = position.x - origin.x;
        double vy = position.y - origin.y;
        double vz = position.z - origin.z;
        double radius = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (radius <= 1e-12 || !Double.isFinite(radius)) return;

        double yaw = Math.atan2(-vx, -vy);
        double pitch = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, vz / radius))));
        double targetPitch = Math.max(-89.0, Math.min(89.0, pitch - ry));
        double targetYaw = yaw + Math.toRadians(rx);
        double horizontal = radius * Math.cos(Math.toRadians(targetPitch));

        position.x = origin.x - horizontal * Math.sin(targetYaw);
        position.y = origin.y - horizontal * Math.cos(targetYaw);
        position.z = origin.z + radius * Math.sin(Math.toRadians(targetPitch));
        viewMatrixDirty = true;
    }
}
