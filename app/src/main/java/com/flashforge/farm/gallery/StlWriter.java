package com.flashforge.farm.gallery;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class StlWriter {
    private StlWriter() {
    }

    public static void writeBinary(File out, GalleryMesh mesh) throws IOException {
        DataOutputStream dos = null;
        try {
            dos = new DataOutputStream(new FileOutputStream(out));
            byte[] header = new byte[80];
            byte[] tag = "FlashForgeFarm gallery".getBytes("US-ASCII");
            System.arraycopy(tag, 0, header, 0, Math.min(tag.length, 80));
            dos.write(header);
            writeLeInt(dos, mesh.triCount);
            float[] v = mesh.xyz;
            for (int t = 0; t < mesh.triCount; t++) {
                int b = t * 9;
                float ax = v[b], ay = v[b + 1], az = v[b + 2];
                float bx = v[b + 3], by = v[b + 4], bz = v[b + 5];
                float cx = v[b + 6], cy = v[b + 7], cz = v[b + 8];
                float ux = bx - ax, uy = by - ay, uz = bz - az;
                float wx = cx - ax, wy = cy - ay, wz = cz - az;
                float nx = uy * wz - uz * wy;
                float ny = uz * wx - ux * wz;
                float nz = ux * wy - uy * wx;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1e-12f) {
                    nx /= len;
                    ny /= len;
                    nz /= len;
                } else {
                    nx = 0;
                    ny = 0;
                    nz = 1;
                }
                writeLeFloat(dos, nx);
                writeLeFloat(dos, ny);
                writeLeFloat(dos, nz);
                writeLeFloat(dos, ax);
                writeLeFloat(dos, ay);
                writeLeFloat(dos, az);
                writeLeFloat(dos, bx);
                writeLeFloat(dos, by);
                writeLeFloat(dos, bz);
                writeLeFloat(dos, cx);
                writeLeFloat(dos, cy);
                writeLeFloat(dos, cz);
                dos.writeShort(0);
            }
        } finally {
            if (dos != null) {
                try {
                    dos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void writeLeInt(DataOutputStream dos, int v) throws IOException {
        byte[] b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
        dos.write(b);
    }

    private static void writeLeFloat(DataOutputStream dos, float v) throws IOException {
        byte[] b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array();
        dos.write(b);
    }
}
