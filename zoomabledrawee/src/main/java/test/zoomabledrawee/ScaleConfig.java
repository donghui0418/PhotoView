package test.zoomabledrawee;

import android.util.SparseArray;

public final class ScaleConfig {
    public static final float SCALE_MIN = 1.0f;

    private static SparseArray<Float> sScaleLevels = new SparseArray<>();

    static {
        sScaleLevels.put(0, SCALE_MIN);
    }

    public static SparseArray<Float> getScaleLevels() {
        return sScaleLevels;
    }

    public static float getMinScale() {
        return sScaleLevels.get(0);
    }

    public static float getMaxScale() {
        return sScaleLevels.get(sScaleLevels.size() - 1);
    }

    public static float getScaleAtLevel(int level) {
        return sScaleLevels.get(level);
    }

    public static int getLevelByScale(float scale) {
        for (int i = 0; i < sScaleLevels.size(); ++i) {
            if (scale >= sScaleLevels.get(i)) continue;
            return i - 1;
        }
        return sScaleLevels.size() - 1;
    }

    public static void setScaleLevels(float... scales) {
        for (int i = 0; i < scales.length; ++i) {
            for (int j = 0; j < i; ++j) {
                if (scales[i] < scales[j])
                    throw new IllegalArgumentException(String.format("Scale level %d value must " +
                            ">= scale level %d value", i, j));
            }
            sScaleLevels.put(i + 1, scales[i]);
        }
    }
}
