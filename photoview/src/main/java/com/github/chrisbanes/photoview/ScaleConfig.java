package com.github.chrisbanes.photoview;

import android.util.SparseArray;

import androidx.annotation.FloatRange;

public final class ScaleConfig {

    public static final float SCALE_VALUE_MIN = 0.1f;
    public static final float SCALE_VALUE_MAX = 10f;

    private static SparseArray<Float> sScaleLevels = new SparseArray<>();

    static {
        sScaleLevels.put(0, 1f);
        sScaleLevels.put(1, 4f);
    }

    public static SparseArray<Float> getScaleLevels() {
        return sScaleLevels;
    }

    public static void setScaleLevels(float... scaleLevels) {
        if (scaleLevels.length < 2) {
            throw new IllegalArgumentException("At least two levels are required");
        }
        for (int i = 0; i < scaleLevels.length; ++i) {
            for (int j = 0; j < i; ++j) {
                if (scaleLevels[i]<= scaleLevels[j])
                    throw new IllegalArgumentException(String.format("Scale level %d value must " +
                            "bigger than scale level %d value", i, j));
            }
            setScaleLevel(i, scaleLevels[i]);
        }
    }

    private static void setScaleLevel(int key, @FloatRange(from = SCALE_VALUE_MIN, to = SCALE_VALUE_MAX) float scale) {
        sScaleLevels.put(key, scale);
    }
}
