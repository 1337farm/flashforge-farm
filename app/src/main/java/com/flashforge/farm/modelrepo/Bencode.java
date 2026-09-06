package com.flashforge.farm.modelrepo;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Bencode {
    private Bencode() {
    }

    public static byte[] encode(Object o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(o, out);
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object o, ByteArrayOutputStream out) {
        try {
            if (o instanceof byte[]) {
                byte[] b = (byte[]) o;
                out.write(Integer.toString(b.length).getBytes(StandardCharsets.US_ASCII));
                out.write(':');
                out.write(b, 0, b.length);
            } else if (o instanceof String) {
                write(((String) o).getBytes(StandardCharsets.UTF_8), out);
            } else if (o instanceof Number) {
                out.write('i');
                out.write(o.toString().getBytes(StandardCharsets.US_ASCII));
                out.write('e');
            } else if (o instanceof List) {
                out.write('l');
                for (Object e : (List<Object>) o) {
                    write(e, out);
                }
                out.write('e');
            } else if (o instanceof Map) {
                out.write('d');
                List<byte[]> keys = new ArrayList<>();
                Map<byte[], Object> byKey = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    byte[] k = toBytes(e.getKey());
                    keys.add(k);
                    byKey.put(k, e.getValue());
                }
                Collections.sort(keys, new Comparator<byte[]>() {
                    @Override
                    public int compare(byte[] a, byte[] b) {
                        int n = Math.min(a.length, b.length);
                        for (int i = 0; i < n; i++) {
                            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
                            if (d != 0) {
                                return d;
                            }
                        }
                        return a.length - b.length;
                    }
                });
                for (byte[] k : keys) {
                    write(k, out);
                    write(byKey.get(k), out);
                }
                out.write('e');
            } else {
                throw new IllegalArgumentException("unencodable: " + o);
            }
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] toBytes(Object o) {
        if (o instanceof byte[]) {
            return (byte[]) o;
        }
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }
}
