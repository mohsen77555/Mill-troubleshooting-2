package com.alhadi.meshcheck;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Robust fusion of 9 scan lines inside the calibrated 1 cm camera window. */
public final class ThreadCountConsensus {
    private ThreadCountConsensus() {}

    public static final class FrameResult {
        public final boolean ok;
        public final String reason;
        public final int currentFullLineCount;
        /** Precise density from center-to-center spacing inside the calibrated 1 cm window. */
        public final float threadsPerCm;
        public final float confidence;
        public final int validScans;
        public final int totalScans;
        public final float spread;
        public final float[] centersNormalized;

        private FrameResult(boolean ok, String reason, int currentFullLineCount,
                            float threadsPerCm, float confidence, int validScans,
                            int totalScans, float spread, float[] centersNormalized) {
            this.ok = ok;
            this.reason = reason;
            this.currentFullLineCount = currentFullLineCount;
            this.threadsPerCm = threadsPerCm;
            this.confidence = confidence;
            this.validScans = validScans;
            this.totalScans = totalScans;
            this.spread = spread;
            this.centersNormalized = centersNormalized == null ? new float[0] : centersNormalized;
        }

        static FrameResult fail(String reason, int totalScans) {
            return new FrameResult(false, reason, 0, 0f, 0f, 0, totalScans, 0f, new float[0]);
        }
    }

    public static FrameResult fuse(ThreadProfileCounter.Result[] results) {
        if (results == null || results.length == 0) return FrameResult.fail("لا توجد خطوط مسح.", 0);

        List<ThreadProfileCounter.Result> valid = new ArrayList<>();
        for (ThreadProfileCounter.Result r : results) {
            if (r != null && r.ok && r.fullLineCount >= 3 && r.spacingThreadsPerCm > 0f && r.confidence >= 0.15f) {
                valid.add(r);
            }
        }
        int minimum = Math.max(3, (results.length + 1) / 2);
        if (valid.size() < minimum) {
            return FrameResult.fail("لم تتفق خطوط المسح داخل 1 cm — حسّن التركيز والإضاءة.", results.length);
        }

        int[] counts = new int[valid.size()];
        for (int i = 0; i < valid.size(); i++) counts[i] = valid.get(i).fullLineCount;
        Arrays.sort(counts);
        float medianFullCount = median(counts);
        float fullCountTolerance = Math.max(1.0f, medianFullCount * 0.08f);

        List<ThreadProfileCounter.Result> countInliers = new ArrayList<>();
        for (ThreadProfileCounter.Result r : valid) {
            if (Math.abs(r.fullLineCount - medianFullCount) <= fullCountTolerance) countInliers.add(r);
        }
        if (countInliers.size() < minimum) {
            return FrameResult.fail("العد مختلف بين أجزاء نافذة 1 cm — اجعل القماش مسطحًا وثابتًا.", results.length);
        }

        float[] densities = new float[countInliers.size()];
        for (int i = 0; i < countInliers.size(); i++) densities[i] = countInliers.get(i).spacingThreadsPerCm;
        Arrays.sort(densities);
        float medianDensity = median(densities);
        float densityTolerance = Math.max(0.20f, medianDensity * 0.065f);

        float weightedDensity = 0f;
        float densityWeight = 0f;
        float weightedFullCount = 0f;
        float fullCountWeight = 0f;
        float confidenceSum = 0f;
        int densityInliers = 0;
        float minDensity = Float.MAX_VALUE;
        float maxDensity = -Float.MAX_VALUE;
        ThreadProfileCounter.Result bestVisual = null;
        float bestVisualScore = -1f;

        for (ThreadProfileCounter.Result r : countInliers) {
            if (Math.abs(r.spacingThreadsPerCm - medianDensity) > densityTolerance) continue;

            float edgeDifference = Math.abs(r.spacingThreadsPerCm - r.fullLineCount);
            float edgeAgreement = Math.max(0.55f, 1f - Math.min(1f, edgeDifference / 2.0f));
            float weight = Math.max(0.12f, r.confidence) * edgeAgreement;

            weightedDensity += r.spacingThreadsPerCm * weight;
            densityWeight += weight;
            weightedFullCount += r.fullLineCount * weight;
            fullCountWeight += weight;
            confidenceSum += r.confidence;
            densityInliers++;
            minDensity = Math.min(minDensity, r.spacingThreadsPerCm);
            maxDensity = Math.max(maxDensity, r.spacingThreadsPerCm);

            float visualScore = r.confidence * edgeAgreement;
            if (visualScore > bestVisualScore) {
                bestVisualScore = visualScore;
                bestVisual = r;
            }
        }

        if (densityInliers < minimum || densityWeight <= 0f) {
            return FrameResult.fail("المسافة بين الخيوط غير مستقرة — ثبّت الهاتف أكثر.", results.length);
        }

        float preciseDensity = weightedDensity / densityWeight;
        float visualCount = weightedFullCount / Math.max(0.001f, fullCountWeight);
        float densitySpread = maxDensity - minDensity;
        float scanAgreement = densityInliers / (float) results.length;
        float consistency = 1f - Math.min(1f, densitySpread / Math.max(1f, preciseDensity) * 3.0f);
        float meanConfidence = confidenceSum / densityInliers;
        float confidence = clamp01(0.55f * meanConfidence + 0.25f * scanAgreement + 0.20f * consistency);

        return new FrameResult(true, "", Math.round(visualCount), preciseDensity, confidence,
                densityInliers, results.length, densitySpread,
                bestVisual == null ? new float[0] : bestVisual.centersNormalized.clone());
    }

    public static final class Snapshot {
        public final boolean accepted;
        public final boolean stable;
        public final int currentFullLineCount;
        public final float threadsPerCm;
        public final float confidence;
        public final int samples;
        public final float spread;
        public final String reason;

        Snapshot(boolean accepted, boolean stable, int currentFullLineCount,
                 float threadsPerCm, float confidence, int samples, float spread, String reason) {
            this.accepted = accepted;
            this.stable = stable;
            this.currentFullLineCount = currentFullLineCount;
            this.threadsPerCm = threadsPerCm;
            this.confidence = confidence;
            this.samples = samples;
            this.spread = spread;
            this.reason = reason;
        }
    }

    /** Temporal anti-vibration stabilizer for the precise decimal n/cm measurement. */
    public static final class Stabilizer {
        private final int historySize;
        private final int minimumStableSamples;
        private final ArrayDeque<Float> densityHistory = new ArrayDeque<>();
        private final ArrayDeque<Float> confidenceHistory = new ArrayDeque<>();
        private float[] previousCenters = new float[0];

        public Stabilizer() { this(14, 7); }

        public Stabilizer(int historySize, int minimumStableSamples) {
            this.historySize = Math.max(7, historySize);
            this.minimumStableSamples = Math.max(5, minimumStableSamples);
        }

        public synchronized Snapshot push(FrameResult frame) {
            if (frame == null || !frame.ok || !(frame.threadsPerCm > 0f)) {
                return snapshot(false, frame == null ? "لا توجد قراءة." : frame.reason,
                        frame == null ? 0 : frame.currentFullLineCount);
            }

            // Image-based anti-vibration guard. It compares detected thread centers
            // in the same calibrated 1 cm window, so it catches both phone shake and cloth movement.
            if (previousCenters.length >= 3 && frame.centersNormalized.length >= 3) {
                float motion = bestCenterMotion(previousCenters, frame.centersNormalized);
                previousCenters = frame.centersNormalized.clone();
                if (motion > 0.018f) { // >0.18 mm movement inside the physical 1 cm window
                    return snapshot(false, "HOLD — vibration detected", frame.currentFullLineCount);
                }
            } else {
                previousCenters = frame.centersNormalized.clone();
            }

            if (densityHistory.size() >= 4) {
                float[] existing = toArray(densityHistory);
                Arrays.sort(existing);
                float med = median(existing);
                float tolerance = Math.max(0.38f, med * 0.075f);
                if (Math.abs(frame.threadsPerCm - med) > tolerance) {
                    return snapshot(false, "تم تجاهل إطار متحرك/شاذ.", frame.currentFullLineCount);
                }
            }

            densityHistory.addLast(frame.threadsPerCm);
            confidenceHistory.addLast(frame.confidence);
            while (densityHistory.size() > historySize) densityHistory.removeFirst();
            while (confidenceHistory.size() > historySize) confidenceHistory.removeFirst();
            return snapshot(true, "", frame.currentFullLineCount);
        }

        public synchronized void reset() {
            densityHistory.clear();
            confidenceHistory.clear();
            previousCenters = new float[0];
        }

        public synchronized Snapshot current() { return snapshot(false, "", 0); }

        private Snapshot snapshot(boolean accepted, String reason, int currentFullLineCount) {
            if (densityHistory.isEmpty()) {
                return new Snapshot(accepted, false, currentFullLineCount, 0f, 0f, 0, 0f, reason);
            }

            float[] values = toArray(densityHistory);
            Arrays.sort(values);
            int trim = values.length >= 9 ? 2 : (values.length >= 7 ? 1 : 0);
            int from = trim;
            int to = values.length - trim;
            float sum = 0f;
            for (int i = from; i < to; i++) sum += values[i];
            float mean = sum / Math.max(1, to - from);
            float low = values[from];
            float high = values[to - 1];
            float spread = high - low;

            float confidence = 0f;
            for (float value : confidenceHistory) confidence += value;
            confidence /= Math.max(1, confidenceHistory.size());

            // Keeps a stable decimal such as 4.3 or 5.9 instead of flickering between values.
            float tolerance = Math.max(0.10f, Math.min(0.35f, mean * 0.022f));
            boolean stable = densityHistory.size() >= minimumStableSamples
                    && spread <= tolerance
                    && confidence >= 0.42f;
            confidence = clamp01(confidence * (stable ? 1f : 0.84f));

            return new Snapshot(accepted, stable, currentFullLineCount, mean,
                    confidence, densityHistory.size(), spread, reason);
        }
    }

    /**
     * Finds the smallest median center displacement allowing a ±2 index offset.
     * This avoids mistaking a thread entering/leaving one edge of the 1 cm window for vibration.
     */
    private static float bestCenterMotion(float[] previous, float[] current) {
        float best = Float.MAX_VALUE;
        for (int offset = -2; offset <= 2; offset++) {
            List<Float> differences = new ArrayList<>();
            for (int i = 0; i < previous.length; i++) {
                int j = i + offset;
                if (j < 0 || j >= current.length) continue;
                differences.add(Math.abs(previous[i] - current[j]));
            }
            if (differences.size() < 3) continue;
            float[] values = new float[differences.size()];
            for (int i = 0; i < values.length; i++) values[i] = differences.get(i);
            Arrays.sort(values);
            best = Math.min(best, median(values));
        }
        return best == Float.MAX_VALUE ? 0f : best;
    }

    private static float[] toArray(ArrayDeque<Float> values) {
        float[] output = new float[values.size()];
        int i = 0;
        for (float value : values) output[i++] = value;
        return output;
    }

    private static float median(int[] sorted) {
        int m = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[m] : (sorted[m - 1] + sorted[m]) / 2f;
    }

    private static float median(float[] sorted) {
        int m = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[m] : (sorted[m - 1] + sorted[m]) / 2f;
    }

    private static float clamp01(float value) { return Math.max(0f, Math.min(1f, value)); }
}
