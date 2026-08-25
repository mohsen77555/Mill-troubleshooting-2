package com.alhadi.meshcheck;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure-Java 1D periodic line counter used by the centered 1 cm camera ruler. */
public final class ThreadProfileCounter {
    private ThreadProfileCounter() {}

    public static final class Result {
        public final boolean ok;
        public final String reason;
        public final int fullLineCount;
        public final float pitchPixels;
        public final float spacingThreadsPerCm;
        public final float confidence;
        public final float[] centersNormalized;

        private Result(boolean ok, String reason, int fullLineCount, float pitchPixels,
                       float spacingThreadsPerCm, float confidence, float[] centersNormalized) {
            this.ok = ok;
            this.reason = reason;
            this.fullLineCount = fullLineCount;
            this.pitchPixels = pitchPixels;
            this.spacingThreadsPerCm = spacingThreadsPerCm;
            this.confidence = confidence;
            this.centersNormalized = centersNormalized;
        }

        static Result fail(String reason) {
            return new Result(false, reason, 0, 0f, 0f, 0f, new float[0]);
        }
    }

    public static Result analyze(float[] profile) {
        if (profile == null || profile.length < 60) {
            return Result.fail("منطقة 1 cm صغيرة في الصورة — استخدم Zoom أكبر.");
        }
        final int n = profile.length;
        final float[] smoothed = smooth(profile, n > 220 ? 2 : 1);
        final double mean = mean(smoothed);
        final double std = standardDeviation(smoothed, mean);
        final double p05 = percentile(smoothed, 0.05);
        final double p95 = percentile(smoothed, 0.95);
        final double contrastRange = p95 - p05;
        if (std < 2.0 || contrastRange < 8.0) {
            return Result.fail("التباين ضعيف داخل مسطرة 1 cm — حسّن الإضاءة أو التركيز.");
        }

        final double[] centered = new double[n];
        for (int i = 0; i < n; i++) centered[i] = smoothed[i] - mean;

        final int minLag = Math.max(3, (int) Math.floor((n - 1) / 90.0));
        final int maxLag = Math.min(n / 3, (int) Math.ceil((n - 1) / 3.5));
        if (maxLag <= minLag + 2) return Result.fail("كبّر الصورة حتى تصبح الخيوط أوضح داخل 1 cm.");

        final double[] corr = new double[maxLag + 2];
        double maxCorr = -1.0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            corr[lag] = correlation(centered, lag);
            if (corr[lag] > maxCorr) maxCorr = corr[lag];
        }

        int bestLag = -1;
        double bestCorr = -1.0;
        final double threshold = Math.max(0.25, maxCorr * 0.72);
        for (int lag = minLag + 1; lag < maxLag; lag++) {
            if (corr[lag] >= corr[lag - 1] && corr[lag] >= corr[lag + 1] && corr[lag] >= threshold) {
                bestLag = lag;
                bestCorr = corr[lag];
                break;
            }
        }
        if (bestLag < 0) {
            for (int lag = minLag; lag <= maxLag; lag++) {
                if (corr[lag] > bestCorr) {
                    bestCorr = corr[lag];
                    bestLag = lag;
                }
            }
        }
        if (bestCorr < 0.18) return Result.fail("لا يوجد تكرار واضح للخيوط داخل 1 cm.");

        double pitch = bestLag;
        if (bestLag > minLag && bestLag < maxLag) {
            double cm = corr[bestLag - 1], c0 = corr[bestLag], cp = corr[bestLag + 1];
            double denominator = cm - 2.0 * c0 + cp;
            if (Math.abs(denominator) > 1e-8) {
                double delta = 0.5 * (cm - cp) / denominator;
                if (Math.abs(delta) <= 1.0) pitch += delta;
            }
        }
        if (pitch < 4.0) {
            return Result.fail("الخيوط أدق من دقة الصورة الحالية — استخدم Zoom أكبر.");
        }

        final int phaseRange = Math.max(1, (int) Math.round(pitch));
        int bestPhase = 0;
        int bestPolarity = -1;
        double bestPhaseScore = -Double.MAX_VALUE;
        for (int polarity : new int[]{-1, 1}) {
            for (int phase = 0; phase < phaseRange; phase++) {
                double sum = 0.0;
                int count = 0;
                for (double pos = phase; pos < n; pos += pitch) {
                    int index = clamp((int) Math.round(pos), 0, n - 1);
                    sum += polarity * (smoothed[index] - mean);
                    count++;
                }
                if (count >= 3) {
                    double score = (sum / count) / Math.max(1e-6, std);
                    if (score > bestPhaseScore) {
                        bestPhaseScore = score;
                        bestPhase = phase;
                        bestPolarity = polarity;
                    }
                }
            }
        }

        final int searchRadius = Math.max(1, (int) Math.round(pitch * 0.28));
        final List<Integer> centers = new ArrayList<>();
        for (double pos = bestPhase; pos < n; pos += pitch) {
            int expected = (int) Math.round(pos);
            if (expected >= n) break;
            int lo = Math.max(0, expected - searchRadius);
            int hi = Math.min(n - 1, expected + searchRadius);
            int extremum = lo;
            for (int index = lo + 1; index <= hi; index++) {
                if ((bestPolarity < 0 && smoothed[index] < smoothed[extremum]) ||
                        (bestPolarity > 0 && smoothed[index] > smoothed[extremum])) {
                    extremum = index;
                }
            }
            if (centers.isEmpty() || extremum - centers.get(centers.size() - 1) > pitch * 0.45) {
                centers.add(extremum);
            }
        }
        if (centers.size() < 3) return Result.fail("لم يتم العثور على عدد كافٍ من الخيوط داخل 1 cm.");

        final double[] spacings = new double[centers.size() - 1];
        for (int i = 1; i < centers.size(); i++) spacings[i - 1] = centers.get(i) - centers.get(i - 1);
        final double medianSpacing = median(spacings);
        if (!(medianSpacing > 0)) return Result.fail("تعذر تثبيت المسافة بين الخيوط.");
        final double spacingMad = medianAbsoluteDeviation(spacings, medianSpacing);
        final double regularity = 1.0 - Math.min(1.0, (spacingMad / medianSpacing) * 3.0);
        final double confidence = clamp01(0.55 * Math.max(0.0, bestCorr)
                + 0.25 * regularity
                + 0.20 * Math.min(1.0, contrastRange / 40.0));

        final float[] normalized = new float[centers.size()];
        for (int i = 0; i < centers.size(); i++) normalized[i] = centers.get(i) / (float) (n - 1);
        final float spacingCount = (float) ((n - 1) / medianSpacing);
        return new Result(true, "", centers.size(), (float) pitch, spacingCount,
                (float) confidence, normalized);
    }

    private static float[] smooth(float[] input, int radius) {
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            int lo = Math.max(0, i - radius), hi = Math.min(input.length - 1, i + radius);
            float sum = 0f;
            for (int j = lo; j <= hi; j++) sum += input[j];
            output[i] = sum / (hi - lo + 1);
        }
        return output;
    }

    private static double correlation(double[] centered, int lag) {
        double numerator = 0.0, leftEnergy = 0.0, rightEnergy = 0.0;
        for (int i = 0; i < centered.length - lag; i++) {
            double a = centered[i], b = centered[i + lag];
            numerator += a * b;
            leftEnergy += a * a;
            rightEnergy += b * b;
        }
        double denominator = Math.sqrt(leftEnergy * rightEnergy);
        return denominator > 1e-9 ? numerator / denominator : -1.0;
    }

    private static double mean(float[] values) {
        double sum = 0.0;
        for (float value : values) sum += value;
        return sum / values.length;
    }

    private static double standardDeviation(float[] values, double mean) {
        double sum = 0.0;
        for (float value : values) {
            double d = value - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / values.length);
    }

    private static double percentile(float[] values, double fraction) {
        float[] copy = values.clone();
        Arrays.sort(copy);
        double position = fraction * (copy.length - 1);
        int lo = (int) Math.floor(position), hi = (int) Math.ceil(position);
        if (lo == hi) return copy[lo];
        double t = position - lo;
        return copy[lo] * (1.0 - t) + copy[hi] * t;
    }

    private static double median(double[] values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 1 ? copy[middle] : (copy[middle - 1] + copy[middle]) / 2.0;
    }

    private static double medianAbsoluteDeviation(double[] values, double median) {
        double[] deviations = new double[values.length];
        for (int i = 0; i < values.length; i++) deviations[i] = Math.abs(values[i] - median);
        return median(deviations);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
