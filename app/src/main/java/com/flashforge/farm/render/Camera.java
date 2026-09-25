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

    /**
     * Orthographic box-scale factor. Perspective NEVER reads this: apparent
     * size in perspective comes only from physical camera distance (dolly),
     * never from focal length.
     */
    public float getZoom() {
        return zoom;
    }

    private final static float MIN_ZOOM = 0.4f;
    private final static float MAX_ZOOM = 15f;

    /**
     * Scale the orthographic view box only. The pinch handler calls this in
     * ortho mode; perspective pinch uses {@link #dollyBy} instead.
     */
    public void zoomOrthoBy(float factor) {
        if (!(factor > 0f) || Float.isNaN(factor) || Float.isInfinite(factor)) return;
        this.zoom = MathUtils.clamp(this.zoom * factor, MIN_ZOOM, MAX_ZOOM);
    }

    /**
     * Ortho box zoom that reproduces the current perspective framing: matches
     * the ortho vertical half-extent to dist*tan(30deg) so toggling ortho
     * keeps the picture stable without moving the camera.
     */
    public static float orthoZoomForDistance(double dist, double baseHalfExtent, double aspect) {
        if (!(dist > 0) || !(baseHalfExtent > 0)) return 1f;
        if (!(aspect > 0)) aspect = 1.0;
        double ratioVertical = aspect < 1 ? 1.0 / aspect : 1.0;
        double zoom = baseHalfExtent * ratioVertical / (dist * Math.tan(Math.toRadians(30.0)));
        if (zoom < MIN_ZOOM) return MIN_ZOOM;
        if (zoom > MAX_ZOOM) return MAX_ZOOM;
        return (float) zoom;
    }

    /**
     * Startup camera distance that frames the whole bed volume plus a margin
     * at the constant 60deg field of view. Width-bound on narrow (portrait)
     * screens, height-bound otherwise; the 25deg downward tilt only
     * foreshortens the bed vertically on screen, so the height fit is
     * conservative.
     */
    public static double startupDistance(double maxDim, double aspect) {
        if (!(maxDim > 0)) return 1.0;
        if (!(aspect > 0)) aspect = 0.5;
        double needHalf = maxDim / 2.0 + 10.0;
        double tan30 = Math.tan(Math.toRadians(30.0));
        double forWidth = needHalf / (tan30 * Math.min(aspect, 1.0));
        double forHeight = needHalf / tan30;
        return Math.max(forWidth, forHeight);
    }

    /** Reference camera-to-target distance captured at default framing. */
    private double defaultDistance = 0;

    public void setDefaultDistance(double distance) {
        if (distance > 0 && Double.isFinite(distance)) this.defaultDistance = distance;
    }

    public double currentDistance() {
        double dx = position.x - origin.x;
        double dy = position.y - origin.y;
        double dz = position.z - origin.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Physical zoom: dolly the camera along the view axis. factor > 1 moves
     * closer (zooms in), factor < 1 moves away. Travel is clamped to [0.4, 15]
     * as distance ratios of the default framing distance, so the bed can be
     * inspected up close and the whole farm still fits when backed out.
     */
    public void dollyBy(double factor) {
        if (!(factor > 0) || Double.isNaN(factor) || Double.isInfinite(factor)) return;
        double dx = position.x - origin.x;
        double dy = position.y - origin.y;
        double dz = position.z - origin.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(dist > 1e-12) || !Double.isFinite(dist)) return;
        double minDist = defaultDistance > 0 ? defaultDistance / MAX_ZOOM : dist / MAX_ZOOM;
        double maxDist = defaultDistance > 0 ? defaultDistance / MIN_ZOOM : dist / MIN_ZOOM;
        double target = dist / factor;
        if (target < minDist) target = minDist;
        if (target > maxDist) target = maxDist;
        double s = target / dist;
        position.x = origin.x + dx * s;
        position.y = origin.y + dy * s;
        position.z = origin.z + dz * s;
        viewMatrixDirty = true;
    }

    /**
     * Apparent-size ratio vs default framing (&gt;1 = closer/larger). Replaces
     * the old focal zoom for constant-screen-size compensation (axes).
     */
    public double zoomRatio() {
        double dist = currentDistance();
        if (!(dist > 1e-12) || !(defaultDistance > 0)) return 1.0;
        return defaultDistance / dist;
    }

    /** Translate camera and focus point together by a world-space delta. */
    public void moveByWorld(double dx, double dy, double dz) {
        position.x += dx; position.y += dy; position.z += dz;
        origin.x += dx; origin.y += dy; origin.z += dz;
        viewMatrixDirty = true;
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
