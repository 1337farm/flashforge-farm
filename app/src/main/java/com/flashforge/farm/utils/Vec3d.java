package com.flashforge.farm.utils;

public class Vec3d {
    public double x, y, z;

    public Vec3d() {}

    public Vec3d(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3d(Vec3d from) {
        this.x = from.x;
        this.y = from.y;
        this.z = from.z;
    }

    public Vec3d center(Vec3d with) {
        // Bolt ⚡: Optimization - compute midpoint directly to avoid 3 unnecessary object allocations (clones) per call.
        return new Vec3d((this.x + with.x) * 0.5, (this.y + with.y) * 0.5, (this.z + with.z) * 0.5);
    }

    public Vec3d set(Vec3d vec) {
        this.x = vec.x;
        this.y = vec.y;
        this.z = vec.z;
        return this;
    }

    public Vec3d set(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public Vec3d add(Vec3d vec) {
        this.x += vec.x;
        this.y += vec.y;
        this.z += vec.z;
        return this;
    }

    public Vec3d add(double x, double y, double z) {
        this.x += x;
        this.y += y;
        this.z += z;
        return this;
    }

    public Vec3d multiply(Vec3d vec) {
        this.x *= vec.x;
        this.y *= vec.y;
        this.z *= vec.z;
        return this;
    }

    public Vec3d multiply(double x, double y, double z) {
        this.x *= x;
        this.y *= y;
        this.z *= z;
        return this;
    }

    public Vec3d multiply(double m) {
        this.x *= m;
        this.y *= m;
        this.z *= m;
        return this;
    }

    public double magnitude() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double distance(Vec3d with) {
        double dx = x - with.x, dy = with.y - y, dz = with.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public Vec3d normalize() {
        double d = magnitude();
        if (d > 0) multiply(1 / d);
        return this;
    }

    public Vec3d negate() {
        return multiply(-1);
    }

    public Vec3d divide(Vec3d vec) {
        this.x /= vec.x;
        this.y /= vec.y;
        this.z /= vec.z;
        return this;
    }

    public Vec3d crossProduct(Vec3d with) {
        return new Vec3d(
            y * with.z - z * with.y,
            z * with.x - x * with.z,
            x * with.y - y * with.x
        );
    }

    public Vec3d clone() {
        return new Vec3d(this);
    }

    public float[] asArray() {
        return new float[]{(float) x, (float) y, (float) z, 1};
    }

    public double[] asDoubleArray() {
        return new double[]{x, y, z, 1};
    }
}
