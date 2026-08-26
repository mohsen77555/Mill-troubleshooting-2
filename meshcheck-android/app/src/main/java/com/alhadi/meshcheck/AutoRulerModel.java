package com.alhadi.meshcheck;

/** Pure Java model for automatic 1 cm ruler scaling from one base calibration. */
public final class AutoRulerModel {
    private AutoRulerModel() {}

    /**
     * Scale the saved 1 cm ruler from the base calibration to a new zoom and distance.
     * The same logical rear camera must be used.
     */
    public static float rulerPixels(float baseRulerPx, float baseZoom, float baseDistanceCm,
                                    float currentZoom, float currentDistanceCm) {
        if (!(baseRulerPx > 0f) || !(baseZoom > 0f) || !(baseDistanceCm > 0f)
                || !(currentZoom > 0f) || !(currentDistanceCm > 0f)) {
            return 0f;
        }
        double pixels = baseRulerPx
                * ((double) currentZoom / baseZoom)
                * ((double) baseDistanceCm / currentDistanceCm);
        return clampPixels((float) pixels);
    }

    /**
     * Relative distance estimate from autofocus focus-distance values in diopters.
     * d_current = d_base * D_base / D_current.
     */
    public static float distanceFromFocus(float baseDistanceCm, float baseFocusDiopters,
                                          float currentFocusDiopters) {
        if (!(baseDistanceCm > 0f) || !(baseFocusDiopters > 0.02f) || !(currentFocusDiopters > 0.02f)) {
            return 0f;
        }
        double distance = baseDistanceCm * ((double) baseFocusDiopters / currentFocusDiopters);
        if (distance < 2.0 || distance > 100.0) return 0f;
        return (float) distance;
    }

    public static boolean focusDistanceUsable(float baseFocusDiopters, float currentFocusDiopters) {
        return baseFocusDiopters > 0.02f && currentFocusDiopters > 0.02f;
    }

    public static float clampDistance(float cm) {
        return Math.max(2f, Math.min(100f, cm));
    }

    public static float clampPixels(float px) {
        return Math.max(20f, Math.min(3000f, px));
    }
}
