package com.flashforge.farm.utils;

import java.util.Random;

public class RandomUtils {

    public final static Random RANDOM = new Random();

    public static float randomf(float min, float max) {
        return min + RANDOM.nextFloat() * (max - min);
    }

    public static long randoml(long min, long max) {
        return (long) (min + RANDOM.nextDouble() * (max - min));
    }
}
