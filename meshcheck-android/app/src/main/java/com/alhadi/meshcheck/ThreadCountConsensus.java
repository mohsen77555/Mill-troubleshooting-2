package com.alhadi.meshcheck;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Robust fusion for the centered 1 cm thread counter.
 *
 * IMPORTANT: the reported thread count is based on COMPLETE thread centers observed
 * inside the 1 cm window. Pitch/spacing is used only as a quality-consistency signal,
 * never as the primary count value.
 */
public final class ThreadCountConsensus {
    private ThreadCountConsensus() {}

    public static final class FrameResult {
        public final boolean ok;
        public final String reason;
        public final int currentFullLineCount;
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
        for (ThreadProfileCounter.Result result : results) {
            if (result != null && result.ok && result.fullLineCount >= 3 && result.confidence >= 0.15f) {
                valid.add(result);
            }
        }
        int minimum = Math.max(3, (results.length + 1) / 2);
        if (valid.size() < minimum) {
            return FrameResult.fail("لم تتفق خطوط المسح داخل 1 cm — حسّن التركيز والإضاءة.", results.length);
        }

        int[] counts = new int[valid.size()];
        for (int i = 0; i < valid.size(); i++) counts[i] = valid.get(i).fullLineCount;
        Arrays.sort(counts);
        float median = median(counts);
        float inlierTolerance = Math.max(1.0f, median * 0.07f);

        List<ThreadProfileCounter.Result> inliers = new ArrayList<>();
        for (ThreadProfileCounter.Result result : valid) {
            if (Math.abs(result.fullLineCount - median) <= inlierTolerance) inliers.add(result);
        }
        if (inliers.size() < minimum) {
            return FrameResult.fail("العد مختلف بين أجزاء نافذة 1 cm — اجعل القماش مسطحًا وثابتًا.", results.length);
        }

        float weightedCount = 0f;
        float weightSum = 0f;
        float confidenceSum = 0f;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        ThreadProfileCounter.Result bestVisual = null;
        float bestVisualScore = -1f;

        for (ThreadProfileCounter.Result result : inliers) {
            // Spacing is only a sanity check. It does NOT determine the returned count.
            float spacingAgreement = 1f;
            if (result.spacingThreadsPerCm > 0f) {
                float relative = Math.abs(result.spacingThreadsPerCm - result.fullLineCount)
                        / Math.max(1f, result.fullLineCount);
                spacingAgreement = Math.max(0.45f, 1f - Math.min(1f, relative * 1.8f));
            }
            float weight = Math.max(0.12f, result.confidence) * spacingAgreement;
            weightedCount += result.fullLineCount * weight;
            weightSum += weight;
            confidenceSum += result.confidence;
            min = Math.min(min, result.fullLineCount);
            max = Math.max(max, result.fullLineCount);

            float visualScore = result.confidence * spacingAgreement;
            if (visualScore > bestVisualScore) {
                bestVisualScore = visualScore;
                bestVisual = result;
            }
        }

        float count = weightedCount / Math.max(0.001f, weightSum);
        float spread = max - min;
        float scanAgreement = inliers.size() / (float) results.length;
        float consistency = 1f - Math.min(1f, spread / Math.max(1f, median) * 2.2f);
        float meanConfidence = confidenceSum / inliers.size();
        float confidence = clamp01(0.55f * meanConfidence + 0.25f * scanAgreement + 0.20f * consistency);

        return new FrameResult(
                true,
                "",
                Math.round(count),
                count,
                confidence,
                inliers.size(),
                results.length,
                spread,
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

    /** Temporal stabilizer. Uses only fused full-line counts from consecutive frames. */
    public static final class Stabilizer {
        private final int historySize;
        private final int minimumStableSamples;
        private final ArrayDeque<Float> countHistory = new ArrayDeque<>();
        private final ArrayDeque<Float> confidenceHistory = new ArrayDeque<>();

        public Stabilizer() {
            this(12, 6);
        }

        public Stabilizer(int historySize, int minimumStableSamples) {
            this.historySize = Math.max(6, historySize);
            this.minimumStableSamples = Math.max(4, minimumStableSamples);
        }

        public synchronized Snapshot push(FrameResult frame) {
            if (frame == null || !frame.ok || !(frame.threadsPerCm > 0f)) {
                return snapshot(false, frame == null ? "لا توجد قراءة." : frame.reason,
                        frame == null ? 0 : frame.currentFullLineCount);
            }

            if (countHistory.size() >= 4) {
                float[] existing = toArray(countHistory);
                Arrays.sort(existing);
                float median = median(existing);
                float tolerance = Math.max(2.0f, median * 0.18f);
                if (Math.abs(frame.threadsPerCm - median) > tolerance) {
                    return snapshot(false, "تم تجاهل إطار شاذ.", frame.currentFullLineCount);
                }
            }

            countHistory.addLast(frame.threadsPerCm);
            confidenceHistory.addLast(frame.confidence);
            while (countHistory.size() > historySize) countHistory.removeFirst();
            while (confidenceHistory.size() > historySize) confidenceHistory.removeFirst();
            return snapshot(true, "", frame.currentFullLineCount);
        }

        public synchronized void reset() {
            countHistory.clear();
            confidenceHistory.clear();
        }

        public synchronized Snapshot current() {
            return snapshot(false, "", 0);
        }

        private Snapshot snapshot(boolean accepted, String reason, int currentFullLineCount) {
            if (countHistory.isEmpty()) {
                return new Snapshot(accepted, false, currentFullLineCount, 0f, 0f, 0, 0f, reason);
            }

            float[] counts = toArray(countHistory);
            Arrays.sort(counts);
            int from = counts.length >= 7 ? 1 : 0;
            int to = counts.length >= 7 ? counts.length - 1 : counts.length;
            float sum = 0f;
            for (int i = from; i < to; i++) sum += counts[i];
            float mean = sum / Math.max(1, to - from);
            float low = counts[from];
            float high = counts[to - 1];
            float spread = high - low;

            float confidence = 0f;
            for (float value : confidenceHistory) confidence += value;
            confidence /= Math.max(1, confidenceHistory.size());

            float tolerance = Math.max(1.05f, Math.min(2.0f, mean * 0.06f));
            boolean stable = countHistory.size() >= minimumStableSamples
                    && spread <= tolerance
                    && confidence >= 0.42f;
            float stabilityFactor = stable ? 1f : 0.82f;
            confidence = clamp01(confidence * stabilityFactor);

            return new Snapshot(accepted, stable, currentFullLineCount, mean,
                    confidence, countHistory.size(), spread, reason);
        }
    }

    private static float[] toArray(ArrayDeque<Float> values) {
        float[] output = new float[values.size()];
        int i = 0;
        for (float value : values) output[i++] = value;
        return output;
    }

    private static float median(int[] sorted) {
        int middle = sorted.length / 2;
        if (sorted.length % 2 == 1) return sorted[middle];
        return (sorted[middle - 1] + sorted[middle]) / 2f;
    }

    private static float median(float[] sorted) {
        int middle = sorted.length / 2;
        if (sorted.length % 2 == 1) return sorted[middle];
        return (sorted[middle - 1] + sorted[middle]) / 2f;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
