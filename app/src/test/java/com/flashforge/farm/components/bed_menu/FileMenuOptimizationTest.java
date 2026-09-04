package com.flashforge.farm.components.bed_menu;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileMenuOptimizationTest {

    private String generateMockData() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("Some text here with a variable $['bed_x'] and another one $['color_accent'] and maybe $['unknown'] and so on.\n");
        }
        return sb.toString();
    }

    @Test
    public void benchmark() throws Exception {
        String mockData = generateMockData();
        byte[] mockBytes = mockData.getBytes(StandardCharsets.UTF_8);

        // Warmup
        for (int i = 0; i < 100; i++) {
            runOriginal(mockBytes);
            runOptimized(mockBytes);
        }

        long startOriginal = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            runOriginal(mockBytes);
        }
        long timeOriginal = System.nanoTime() - startOriginal;

        long startOptimized = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            runOptimized(mockBytes);
        }
        long timeOptimized = System.nanoTime() - startOptimized;

        System.out.println("Original time: " + (timeOriginal / 1000000) + " ms");
        System.out.println("Optimized time: " + (timeOptimized / 1000000) + " ms");
        System.out.println("Improvement: " + String.format("%.2f", (timeOriginal - timeOptimized) * 100.0 / timeOriginal) + "%");
    }

    private String runOriginal(byte[] inputBytes) throws Exception {
        InputStream in = new ByteArrayInputStream(inputBytes);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[10240]; int c;
        while ((c = in.read(buffer)) != -1) {
            bos.write(buffer, 0, c);
        }
        bos.close();
        in.close();

        String str = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(str);
        Pattern placeholderPattern = Pattern.compile("\\$\\['(\\w+?)(\\[\\d+]|)']");
        Matcher m = placeholderPattern.matcher(str);
        int offset = 0;
        while (m.find()) {
            String newVal = "replacement";
            sb = sb.replace(m.start() + offset, m.end() + offset, newVal);
            offset += newVal.length() - (m.end() - m.start());
        }

        return sb.toString();
    }

    private String runOptimized(byte[] inputBytes) throws Exception {
        InputStream in = new ByteArrayInputStream(inputBytes);
        InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[10240];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            sb.append(buffer, 0, read);
        }
        reader.close();

        Pattern placeholderPattern = Pattern.compile("\\$\\['(\\w+?)(\\[\\d+]|)']");
        Matcher m = placeholderPattern.matcher(sb);
        StringBuffer resultBuilder = new StringBuffer();
        while (m.find()) {
            String newVal = "replacement";
            m.appendReplacement(resultBuilder, Matcher.quoteReplacement(newVal));
        }
        m.appendTail(resultBuilder);
        return resultBuilder.toString();
    }
}
