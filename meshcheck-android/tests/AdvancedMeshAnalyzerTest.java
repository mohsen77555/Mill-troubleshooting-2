import com.alhadi.meshcheck.AdvancedMeshAnalyzer;

public final class AdvancedMeshAnalyzerTest {
    private static float[] synthetic(int length, double pitchPx, double yarnWidthPx, double phase, boolean brightThread) {
        float[] p = new float[length];
        for (int i = 0; i < length; i++) {
            double cycle = ((i - phase) % pitchPx + pitchPx) % pitchPx;
            double d = Math.min(cycle, pitchPx - cycle);
            boolean thread = d <= yarnWidthPx / 2.0;
            double base = thread == brightThread ? 218.0 : 32.0;
            double ripple = 2.7 * Math.sin(i * 0.173) + 1.3 * Math.cos(i * 0.071);
            p[i] = (float) (base + ripple);
        }
        return p;
    }

    private static void assertNear(String name, double actual, double expected, double tolerance) {
        if (Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(name + " expected " + expected + " ±" + tolerance + " but got " + actual);
        }
    }

    public static void main(String[] args) {
        // 2000 px over 20 mm. Pitch 200 px => 5.0 n/cm; yarn 60 px => 600 µm; opening 1400 µm.
        float[] p = synthetic(2001, 200.0, 60.0, 23.4, true);
        AdvancedMeshAnalyzer.ProfileResult r = AdvancedMeshAnalyzer.analyzeProfile(p, 2.0f);
        if (!r.ok) throw new AssertionError("profile failed: " + r.reason);
        assertNear("threads/cm", r.threadsPerCm, 5.0, 0.12);
        assertNear("pitch", r.pitchMicrons, 2000.0, 55.0);
        assertNear("yarn", r.yarnMicrons, 600.0, 70.0);
        assertNear("opening", r.openingMicrons, 1400.0, 90.0);
        if (!(r.confidence > 0.45f)) throw new AssertionError("confidence too low: " + r.confidence);

        AdvancedMeshAnalyzer.ProfileResult[] scans = new AdvancedMeshAnalyzer.ProfileResult[15];
        for (int i = 0; i < scans.length; i++) {
            scans[i] = AdvancedMeshAnalyzer.analyzeProfile(
                    synthetic(2001, 200.0 + (i % 3 - 1) * 0.8, 60.0 + (i % 2) * 1.0, 17.0 + i * 0.7, true),
                    2.0f);
        }
        AdvancedMeshAnalyzer.DirectionResult d = AdvancedMeshAnalyzer.aggregate(scans);
        if (!d.ok) throw new AssertionError("aggregate failed: " + d.reason);
        assertNear("aggregate threads/cm", d.threadsPerCm, 5.0, 0.10);
        assertNear("aggregate opening", d.openingMicrons, 1400.0, 80.0);

        // Decimal target 5.9 n/cm over 20 mm.
        double pitch59 = 2000.0 / 11.8; // pixels per pitch for 2000 px / 20 mm
        AdvancedMeshAnalyzer.ProfileResult r59 = AdvancedMeshAnalyzer.analyzeProfile(
                synthetic(2001, pitch59, pitch59 * 0.28, 31.2, false), 2.0f);
        if (!r59.ok) throw new AssertionError("5.9 profile failed: " + r59.reason);
        assertNear("5.9 threads/cm", r59.threadsPerCm, 5.9, 0.16);

        System.out.println("AdvancedMeshAnalyzerTest passed");
    }
}
