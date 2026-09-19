package com.flashforge.farm.gallery;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.zip.ZipInputStream;

public final class MeshLoader {
    private MeshLoader() {
    }

    public static final int MAX_TRIS = 2000000;

    public static GalleryMesh load(File f) throws IOException {
        String name = f.getName().toLowerCase();
        if (name.endsWith(".stl")) return loadStl(f);
        if (name.endsWith(".obj")) return loadObj(f);
        if (name.endsWith(".3mf")) return load3mf(f);
        throw new IOException("unsupported model format");
    }

    private static byte[] readAll(File f) throws IOException {
        long len = f.length();
        if (len > 600L * 1024 * 1024) throw new IOException("file too large");
        byte[] data = new byte[(int) len];
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            int o = 0;
            while (o < data.length) {
                int n = in.read(data, o, data.length - o);
                if (n < 0) break;
                o += n;
            }
            if (o != data.length) throw new IOException("short read");
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
        return data;
    }

    private static GalleryMesh loadStl(File f) throws IOException {
        byte[] data = readAll(f);
        boolean binary = false;
        if (data.length >= 84) {
            int count = ByteBuffer.wrap(data, 80, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (count > 0 && (long) 84 + (long) count * 50L == (long) data.length) binary = true;
        }
        if (!binary && data.length >= 5) {
            String head = new String(data, 0, Math.min(64, data.length), "US-ASCII").trim().toLowerCase();
            binary = !head.startsWith("solid");
        }
        return binary ? parseBinaryStl(data) : parseAsciiStl(data);
    }

    private static GalleryMesh parseBinaryStl(byte[] data) throws IOException {
        int count = ByteBuffer.wrap(data, 80, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (count <= 0 || count > MAX_TRIS) throw new IOException("bad triangle count");
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[count * 9];
        int o = 0;
        for (int t = 0; t < count; t++) {
            int b = 84 + t * 50;
            bb.position(b + 12);
            for (int k = 0; k < 9; k++) out[o++] = bb.getFloat();
        }
        return new GalleryMesh(out);
    }

    private static GalleryMesh parseAsciiStl(byte[] data) throws IOException {
        String text = new String(data, "UTF-8");
        ArrayList<Float> list = new ArrayList<Float>(4096);
        int pos = 0;
        while (true) {
            int v = text.indexOf("vertex", pos);
            if (v < 0) break;
            int e = text.indexOf('\n', v);
            if (e < 0) e = text.length();
            String[] parts = text.substring(v + 6, e).trim().split("\\s+");
            if (parts.length < 3) throw new IOException("bad ascii stl");
            try {
                list.add(Float.parseFloat(parts[0]));
                list.add(Float.parseFloat(parts[1]));
                list.add(Float.parseFloat(parts[2]));
            } catch (NumberFormatException ex) {
                throw new IOException("bad ascii stl");
            }
            if (list.size() / 9 > MAX_TRIS) throw new IOException("too many triangles");
            pos = e;
        }
        if (list.size() % 9 != 0 || list.isEmpty()) throw new IOException("bad ascii stl");
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return new GalleryMesh(out);
    }

    private static GalleryMesh loadObj(File f) throws IOException {
        byte[] data = readAll(f);
        String text = new String(data, "UTF-8");
        ArrayList<Float> verts = new ArrayList<Float>(4096);
        ArrayList<Integer> idx = new ArrayList<Integer>(4096);
        String[] lines = text.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("v ")) {
                String[] p = line.substring(2).trim().split("\\s+");
                if (p.length < 3) continue;
                try {
                    verts.add(Float.parseFloat(p[0]));
                    verts.add(Float.parseFloat(p[1]));
                    verts.add(Float.parseFloat(p[2]));
                } catch (NumberFormatException ignored) {
                }
            } else if (line.startsWith("f ")) {
                String[] p = line.substring(2).trim().split("\\s+");
                ArrayList<Integer> face = new ArrayList<Integer>(p.length);
                for (String s : p) {
                    int slash = s.indexOf('/');
                    String vs = slash < 0 ? s : s.substring(0, slash);
                    try {
                        int vi = Integer.parseInt(vs);
                        int vc = verts.size() / 3;
                        if (vi < 0) vi = vc + vi;
                        else vi = vi - 1;
                        if (vi >= 0 && vi < vc) face.add(vi);
                    } catch (NumberFormatException ignored) {
                    }
                }
                for (int k = 1; k + 1 < face.size(); k++) {
                    idx.add(face.get(0));
                    idx.add(face.get(k));
                    idx.add(face.get(k + 1));
                    if (idx.size() / 3 > MAX_TRIS) throw new IOException("too many triangles");
                }
            }
        }
        if (idx.isEmpty()) throw new IOException("no faces");
        float[] out = new float[idx.size() * 3];
        int o = 0;
        for (int i = 0; i < idx.size(); i++) {
            int vi = idx.get(i) * 3;
            out[o++] = verts.get(vi);
            out[o++] = verts.get(vi + 1);
            out[o++] = verts.get(vi + 2);
        }
        return new GalleryMesh(out);
    }

    private static GalleryMesh load3mf(File f) throws IOException {
        byte[] data = readAll(f);
        ZipInputStream zip = null;
        try {
            zip = new ZipInputStream(new ByteArrayInputStream(data));
            while (true) {
                java.util.zip.ZipEntry e = zip.getNextEntry();
                if (e == null) break;
                String n = e.getName().toLowerCase();
                if (n.endsWith(".model")) {
                    GalleryMesh m = parse3mfModel(zip);
                    try {
                        zip.close();
                    } catch (IOException ignored) {
                    }
                    return m;
                }
                zip.closeEntry();
            }
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
        throw new IOException("no 3d model in 3mf");
    }

    private static GalleryMesh parse3mfModel(InputStream in) throws IOException {
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            ArrayList<Float> tris = new ArrayList<Float>(8192);
            ArrayList<Float> curVerts = null;
            boolean inMesh = false;
            int type;
            while ((type = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    if (tag.endsWith("mesh")) {
                        inMesh = true;
                        curVerts = new ArrayList<Float>(4096);
                    } else if (inMesh && tag.endsWith("vertex")) {
                        curVerts.add(parseF(p.getAttributeValue(null, "x")));
                        curVerts.add(parseF(p.getAttributeValue(null, "y")));
                        curVerts.add(parseF(p.getAttributeValue(null, "z")));
                    } else if (inMesh && tag.endsWith("triangle")) {
                        int v1 = Integer.parseInt(p.getAttributeValue(null, "v1"));
                        int v2 = Integer.parseInt(p.getAttributeValue(null, "v2"));
                        int v3 = Integer.parseInt(p.getAttributeValue(null, "v3"));
                        for (int vi : new int[]{v1, v2, v3}) {
                            tris.add(curVerts.get(vi * 3));
                            tris.add(curVerts.get(vi * 3 + 1));
                            tris.add(curVerts.get(vi * 3 + 2));
                        }
                        if (tris.size() / 9 > MAX_TRIS) throw new IOException("too many triangles");
                    }
                } else if (type == XmlPullParser.END_TAG && p.getName().endsWith("mesh")) {
                    if (!tris.isEmpty()) break;
                    inMesh = false;
                }
            }
            if (tris.isEmpty()) throw new IOException("empty 3mf mesh");
            float[] out = new float[tris.size()];
            for (int i = 0; i < out.length; i++) out[i] = tris.get(i);
            return new GalleryMesh(out);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("bad 3mf xml");
        }
    }

    private static float parseF(String s) {
        if (s == null) return 0;
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
