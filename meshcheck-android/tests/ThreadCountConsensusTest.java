import com.alhadi.meshcheck.ThreadCountConsensus;
import com.alhadi.meshcheck.ThreadProfileCounter;
import java.util.Random;

public class ThreadCountConsensusTest {
    private static float[] synthetic(int n, double threadsPerCm, double phaseShift, double noise) {
        float[] p = new float[n];
        Random random = new Random((long) (phaseShift * 10000 + threadsPerCm * 100 + 77));
        double pitch = (n - 1) / threadsPerCm;
        for (int x = 0; x < n; x++) {
            double phase = ((x - phaseShift * pitch + pitch / 2.0) % pitch + pitch) % pitch - pitch / 2.0;
            double sigma = pitch * 0.28 / 2.355;
            double band = Math.exp(-0.5 * phase * phase / (sigma * sigma));
            p[x] = (float) (215 - 155 * band + random.nextGaussian() * noise);
        }
        return p;
    }

    private static ThreadProfileCounter.Result read(double count, double phase, double noise) {
        return ThreadProfileCounter.analyze(synthetic(900, count, phase, noise));
    }

    private static ThreadCountConsensus.FrameResult frame(double density, double phaseBase, double noise) {
        ThreadProfileCounter.Result[] scans = new ThreadProfileCounter.Result[9];
        for (int i = 0; i < scans.length; i++) {
            scans[i] = read(density, phaseBase + i * 0.012, noise);
        }
        return ThreadCountConsensus.fuse(scans);
    }

    private static void assertNear(double expected, double actual, double tolerance, String name) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(name + " expected " + expected + " got " + actual);
        }
    }

    public static void main(String[] args) {
        // Multi-scan outlier rejection around 7.5/cm.
        ThreadProfileCounter.Result[] scans = new ThreadProfileCounter.Result[9];
        for (int i = 0; i < scans.length; i++) {
            double actual = i == 8 ? 12.0 : 7.5;
            scans[i] = read(actual, 0.18 + i * 0.018, 7.0);
        }
        ThreadCountConsensus.FrameResult fused = ThreadCountConsensus.fuse(scans);
        if (!fused.ok) throw new AssertionError(fused.reason);
        if (fused.validScans < 6) throw new AssertionError("too few agreeing scan lines");
        assertNear(7.5, fused.threadsPerCm, 0.35, "7.5/cm consensus");

        // Decimal densities requested by the user must not be forced to integers.
        ThreadCountConsensus.FrameResult f43 = frame(4.3, 0.13, 5.0);
        if (!f43.ok) throw new AssertionError("4.3 frame: " + f43.reason);
        assertNear(4.3, f43.threadsPerCm, 0.20, "4.3/cm precise density");

        ThreadCountConsensus.FrameResult f59 = frame(5.9, 0.17, 5.5);
        if (!f59.ok) throw new AssertionError("5.9 frame: " + f59.reason);
        assertNear(5.9, f59.threadsPerCm, 0.20, "5.9/cm precise density");

        // Stable decimal reading over multiple nearly identical frames.
        ThreadCountConsensus.Stabilizer stabilizer = new ThreadCountConsensus.Stabilizer(14, 7);
        ThreadCountConsensus.Snapshot snapshot = null;
        for (int i = 0; i < 10; i++) {
            snapshot = stabilizer.push(frame(5.9, 0.20 + i * 0.002, 5.0));
        }
        if (snapshot == null || !snapshot.stable) throw new AssertionError("decimal density did not stabilize");
        assertNear(5.9, snapshot.threadsPerCm, 0.18, "stable 5.9/cm");

        // Anti-vibration: a sudden large center shift must be rejected.
        ThreadCountConsensus.Snapshot beforeShake = stabilizer.push(frame(5.9, 0.22, 5.0));
        ThreadCountConsensus.Snapshot shake = stabilizer.push(frame(5.9, 0.55, 5.0));
        if (shake.accepted) throw new AssertionError("vibration frame should be rejected");
        if (shake.reason == null || !shake.reason.toLowerCase().contains("vibration")) {
            throw new AssertionError("vibration rejection reason missing: " + shake.reason);
        }
        assertNear(beforeShake.threadsPerCm, shake.threadsPerCm, 0.08, "held value during vibration");

        System.out.println("precise decimal + anti-vibration thread count tests passed");
    }
}
