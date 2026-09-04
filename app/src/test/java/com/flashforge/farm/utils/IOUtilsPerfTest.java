package com.flashforge.farm.utils;

import org.json.JSONArray;
import org.junit.Test;

import java.lang.reflect.Method;

public class IOUtilsPerfTest {

    @Test
    public void benchmarkConfigJsonToString() throws Exception {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < 1000; i++) {
            arr.put("value_" + i);
        }

        Method method = IOUtils.class.getDeclaredMethod("configJsonToString", Object.class);
        method.setAccessible(true);

        // Warm up
        for (int i = 0; i < 10000; i++) {
            method.invoke(null, arr);
        }

        long start = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            method.invoke(null, arr);
        }
        long end = System.nanoTime();

        System.out.println("Execution time: " + (end - start) / 1_000_000.0 + " ms");
    }
}
