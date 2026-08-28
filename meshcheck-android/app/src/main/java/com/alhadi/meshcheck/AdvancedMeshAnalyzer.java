package com.alhadi.meshcheck;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * High-accuracy 1D mesh metrology.
 * Combines autocorrelation from ThreadProfileCounter with FFT spectral refinement,
 * then estimates sub-pixel yarn edges and aperture width from the known physical span.
 */
public final class AdvancedMeshAnalyzer {
    private AdvancedMeshAnalyzer() {}

    public static final class ProfileResult {
        public final boolean ok;
        public final String reason;
        public final float threadsPerCm;
        public final float pitchMicrons;
        public final float yarnMicrons;
        public final float openingMicrons;
        public final float uncertaintyMicrons;
        public final float confidence;
        public final float autocorrPitchPixels;
        public final float fftPitchPixels;

        ProfileResult(boolean ok, String reason, float threadsPerCm, float pitchMicrons,
                      float yarnMicrons, float openingMicrons, float uncertaintyMicrons,
                      float confidence, float autocorrPitchPixels, float fftPitchPixels) {
            this.ok = ok;
            this.reason = reason;
            this.threadsPerCm = threadsPerCm;
            this.pitchMicrons = pitchMicrons;
            this.yarnMicrons = yarnMicrons;
            this.openingMicrons = openingMicrons;
            this.uncertaintyMicrons = uncertaintyMicrons;
            this.confidence = confidence;
            this.autocorrPitchPixels = autocorrPitchPixels;
            this.fftPitchPixels = fftPitchPixels;
        }

        static ProfileResult fail(String reason) {
            return new ProfileResult(false, reason, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        }
    }

    public static final class DirectionResult {
        public final boolean ok;
        public final String reason;
        public final float threadsPerCm;
        public final float pitchMicrons;
        public final float yarnMicrons;
        public final float openingMicrons;
        public final float uncertaintyMicrons;
        public final float confidence;
        public final int validScans;
        public final int totalScans;

        DirectionResult(boolean ok, String reason, float threadsPerCm, float pitchMicrons,
                        float yarnMicrons, float openingMicrons, float uncertaintyMicrons,
                        float confidence, int validScans, int totalScans) {
            this.ok = ok;
            this.reason = reason;
            this.threadsPerCm = threadsPerCm;
            this.pitchMicrons = pitchMicrons;
            this.yarnMicrons = yarnMicrons;
            this.openingMicrons = openingMicrons;
            this.uncertaintyMicrons = uncertaintyMicrons;
            this.confidence = confidence;
            this.validScans = validScans;
            this.totalScans = totalScans;
        }

        static DirectionResult fail(String reason, int total) {
            return new DirectionResult(false, reason, 0f, 0f, 0f, 0f, 0f, 0f, 0, total);
        }
    }

    public static ProfileResult analyzeProfile(float[] raw, float physicalLengthCm) {
        if (raw == null || raw.length < 80 || !(physicalLengthCm > 0f)) {
            return ProfileResult.fail("Profile too small");
        }

        ThreadProfileCounter.Result baseline = ThreadProfileCounter.analyze(raw, physicalLengthCm);
        if (!baseline.ok) return ProfileResult.fail(baseline.reason);

        float autocorrPitch = baseline.pitchPixels;
        float fftPitch = spectralPitch(raw, autocorrPitch);
        float refinedPitch = autocorrPitch;
        float fftAgreement = 0f;
        if (fftPitch > 0f) {
            float relative = Math.abs(fftPitch - autocorrPitch) / Math.max(1f, autocorrPitch);
            if (relative <= 0.12f) {
                fftAgreement = 1f - relative / 0.12f;
                refinedPitch = autocorrPitch * 0.62f + fftPitch * 0.38f;
            }
        }

        float[] profile = smooth(raw, raw.length > 600 ? 2 : 1);
        float[] centers = new float[baseline.centersNormalized.length];
        for (int i = 0; i < centers.length; i++) centers[i] = baseline.centersNormalized[i] * (profile.length - 1f);
        if (centers.length < 3) return ProfileResult.fail("Not enough thread centers");

        float centerMedian = medianSamples(profile, centers);
        float[] midpoints = new float[centers.length - 1];
        for (int i = 0; i < midpoints.length; i++) midpoints[i] = (centers[i] + centers[i + 1]) * 0.5f;
        float gapMedian = medianSamples(profile, midpoints);
        boolean brightThread = centerMedian >= gapMedian;
        float threshold = (centerMedian + gapMedian) * 0.5f;

        List<Float> widths = new ArrayList<>();
        float maxHalfWidth = refinedPitch * 0.48f;
        for (float center : centers) {
            float left = findCrossing(profile, center, -1, threshold, brightThread, maxHalfWidth);
            float right = findCrossing(profile, center, +1, threshold, brightThread, maxHalfWidth);
            if (left >= 0f && right > left) {
                float width = right - left;
                if (width > refinedPitch * 0.05f && width < refinedPitch * 0.88f) widths.add(width);
            }
        }
        if (widths.size() < Math.max(2, centers.length / 3)) {
            return ProfileResult.fail("Could not stabilize yarn edges");
        }

        float medianWidth = median(toArray(widths));
        float[] spacings = new float[centers.length - 1];
        for (int i = 1; i < centers.length; i++) spacings[i - 1] = centers[i] - centers[i - 1];
        float spacingMedian = median(spacings);
        float spacingMad = mad(spacings, spacingMedian);
        float widthMad = mad(toArray(widths), medianWidth);

        float micronsPerPixel = physicalLengthCm * 10000f / Math.max(1f, raw.length - 1f);
        float pitchMicrons = refinedPitch * micronsPerPixel;
        float yarnMicrons = medianWidth * micronsPerPixel;
        float openingMicrons = Math.max(0f, pitchMicrons - yarnMicrons);
        float threadsPerCm = 10000f / Math.max(1f, pitchMicrons);

        float spectralDelta = fftPitch > 0f ? Math.abs(fftPitch - autocorrPitch) : autocorrPitch * 0.06f;
        float pitchSigmaPx = Math.max(0.08f, Math.max(spacingMad, spectralDelta * 0.35f));
        float widthSigmaPx = Math.max(0.08f, widthMad);
        float uncertainty = (float) Math.sqrt(pitchSigmaPx * pitchSigmaPx + widthSigmaPx * widthSigmaPx)
                * micronsPerPixel;

        float regularity = clamp01(1f - spacingMad / Math.max(0.5f, refinedPitch) * 4f);
        float edgeRegularity = clamp01(1f - widthMad / Math.max(0.5f, medianWidth) * 3f);
        float confidence = clamp01(baseline.confidence * 0.55f
                + regularity * 0.18f
                + edgeRegularity * 0.17f
                + (fftPitch > 0f ? (0.05f + 0.05f * fftAgreement) : 0.03f));

        return new ProfileResult(true, "", threadsPerCm, pitchMicrons, yarnMicrons,
                openingMicrons, uncertainty, confidence, autocorrPitch, fftPitch);
    }

    public static DirectionResult aggregate(ProfileResult[] scans) {
        if (scans == null || scans.length == 0) return DirectionResult.fail("No scans", 0);
        List<ProfileResult> valid = new ArrayList<>();
        for (ProfileResult scan : scans) if (scan != null && scan.ok && scan.threadsPerCm > 0f) valid.add(scan);
        int minimum = Math.max(5, (scans.length + 2) / 3);
        if (valid.size() < minimum) return DirectionResult.fail("Insufficient valid scans", scans.length);

        float[] densities = new float[valid.size()];
        for (int i = 0; i < valid.size(); i++) densities[i] = valid.get(i).threadsPerCm;
        float medianDensity = median(densities);
        float densityMad = mad(densities, medianDensity);
        float tolerance = Math.max(0.12f, Math.max(medianDensity * 0.045f, densityMad * 3.5f));

        List<ProfileResult> inliers = new ArrayList<>();
        for (ProfileResult scan : valid) {
            if (Math.abs(scan.threadsPerCm - medianDensity) <= tolerance) inliers.add(scan);
        }
        if (inliers.size() < minimum) return DirectionResult.fail("Scan disagreement too high", scans.length);

        float pitch = weightedMedian(inliers, 0);
        float yarn = weightedMedian(inliers, 1);
        float opening = weightedMedian(inliers, 2);
        float density = 10000f / Math.max(1f, pitch);
        float uncertainty = weightedMedian(inliers, 3);

        float confidence = 0f;
        for (ProfileResult scan : inliers) confidence += scan.confidence;
        confidence /= inliers.size();
        float agreement = inliers.size() / (float) scans.length;
        float spread = mad(densities, medianDensity) / Math.max(0.01f, medianDensity);
        confidence = clamp01(confidence * 0.78f + agreement * 0.16f + clamp01(1f - spread * 8f) * 0.06f);

        return new DirectionResult(true, "", density, pitch, yarn, opening,
                uncertainty, confidence, inliers.size(), scans.length);
    }

    private static float spectralPitch(float[] raw, float expectedPitch) {
        int n = 1;
        while (n < raw.length) n <<= 1;
        if (n < 128) return 0f;
        double[] re = new double[n];
        double[] im = new double[n];
        double mean = 0.0;
        for (float v : raw) mean += v;
        mean /= raw.length;
        for (int i = 0; i < raw.length; i++) {
            double window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / Math.max(1, raw.length - 1));
            re[i] = (raw[i] - mean) * window;
        }
        fft(re, im);

        double expectedBin = n / Math.max(2.0, expectedPitch);
        int lo = Math.max(1, (int) Math.floor(expectedBin * 0.78));
        int hi = Math.min(n / 2 - 2, (int) Math.ceil(expectedBin * 1.22));
        if (hi <= lo) return 0f;
        int best = lo;
        double bestPower = -1.0;
        for (int k = lo; k <= hi; k++) {
            double power = re[k] * re[k] + im[k] * im[k];
            if (power > bestPower) { bestPower = power; best = k; }
        }
        if (!(bestPower > 0.0)) return 0f;

        double refinedBin = best;
        if (best > 1 && best < n / 2 - 1) {
            double ym = Math.log(1e-12 + re[best - 1] * re[best - 1] + im[best - 1] * im[best - 1]);
            double y0 = Math.log(1e-12 + re[best] * re[best] + im[best] * im[best]);
            double yp = Math.log(1e-12 + re[best + 1] * re[best + 1] + im[best + 1] * im[best + 1]);
            double denominator = ym - 2.0 * y0 + yp;
            if (Math.abs(denominator) > 1e-9) {
                double delta = 0.5 * (ym - yp) / denominator;
                if (Math.abs(delta) <= 1.0) refinedBin += delta;
            }
        }
        return refinedBin > 0.0 ? (float) (n / refinedBin) : 0f;
    }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wLenR = Math.cos(angle), wLenI = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double wr = 1.0, wi = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j, v = i + j + len / 2;
                    double vr = re[v] * wr - im[v] * wi;
                    double vi = re[v] * wi + im[v] * wr;
                    double ur = re[u], ui = im[u];
                    re[u] = ur + vr; im[u] = ui + vi;
                    re[v] = ur - vr; im[v] = ui - vi;
                    double nextWr = wr * wLenR - wi * wLenI;
                    wi = wr * wLenI + wi * wLenR;
                    wr = nextWr;
                }
            }
        }
    }

    private static float findCrossing(float[] p, float center, int direction, float threshold,
                                      boolean brightThread, float maxDistance) {
        int start = clamp(Math.round(center), 0, p.length - 1);
        float previousX = start;
        float previousY = p[start];
        int steps = Math.max(2, Math.round(maxDistance));
        for (int s = 1; s <= steps; s++) {
            int index = clamp(start + direction * s, 0, p.length - 1);
            float y = p[index];
            boolean prevInside = brightThread ? previousY >= threshold : previousY <= threshold;
            boolean nowInside = brightThread ? y >= threshold : y <= threshold;
            if (prevInside && !nowInside) {
                float denominator = y - previousY;
                float fraction = Math.abs(denominator) > 1e-6f ? (threshold - previousY) / denominator : 0.5f;
                return previousX + (index - previousX) * clamp01(fraction);
            }
            previousX = index;
            previousY = y;
            if (index == 0 || index == p.length - 1) break;
        }
        return -1f;
    }

    private static float medianSamples(float[] p, float[] positions) {
        float[] values = new float[positions.length];
        for (int i = 0; i < positions.length; i++) values[i] = sampleLinear(p, positions[i]);
        return median(values);
    }

    private static float sampleLinear(float[] p, float x) {
        x = Math.max(0f, Math.min(p.length - 1f, x));
        int lo = (int) Math.floor(x), hi = Math.min(p.length - 1, lo + 1);
        float t = x - lo;
        return p[lo] * (1f - t) + p[hi] * t;
    }

    private static float[] smooth(float[] in, int radius) {
        float[] out = new float[in.length];
        for (int i = 0; i < in.length; i++) {
            int lo = Math.max(0, i - radius), hi = Math.min(in.length - 1, i + radius);
            float sum = 0f;
            for (int j = lo; j <= hi; j++) sum += in[j];
            out[i] = sum / (hi - lo + 1);
        }
        return out;
    }

    private static float weightedMedian(List<ProfileResult> list, int field) {
        float[] values = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            ProfileResult r = list.get(i);
            values[i] = field == 0 ? r.pitchMicrons : field == 1 ? r.yarnMicrons
                    : field == 2 ? r.openingMicrons : r.uncertaintyMicrons;
        }
        return median(values);
    }

    private static float[] toArray(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }

    private static float median(float[] values) {
        float[] copy = values.clone();
        Arrays.sort(copy);
        int m = copy.length / 2;
        return copy.length % 2 == 1 ? copy[m] : (copy[m - 1] + copy[m]) * 0.5f;
    }

    private static float mad(float[] values, float med) {
        float[] dev = new float[values.length];
        for (int i = 0; i < values.length; i++) dev[i] = Math.abs(values[i] - med);
        return median(dev);
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static float clamp01(float value) { return Math.max(0f, Math.min(1f, value)); }
}
