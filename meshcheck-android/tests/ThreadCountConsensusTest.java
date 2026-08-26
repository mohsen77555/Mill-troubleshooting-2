import com.alhadi.meshcheck.ThreadCountConsensus;
import com.alhadi.meshcheck.ThreadProfileCounter;
import java.util.Random;

public class ThreadCountConsensusTest {
    private static float[] synthetic(int n, double threadsPerCm, double phaseShift, double noise) {
        float[] p = new float[n];
        Random random = new Random((long) (phaseShift * 1000 + 77));
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
        return ThreadProfileCounter.analyze(synthetic(760, count, phase, noise));
    }

    public static void main(String[] args) {
        ThreadProfileCounter.Result[] scans = new ThreadProfileCounter.Result[9];
        for (int i = 0; i < scans.length; i++) {
            double actual = i == 8 ? 12.0 : 7.5; // one intentional outlier
            scans[i] = read(actual, 0.18 + i * 0.035, 8.0);
        }
        ThreadCountConsensus.FrameResult frame = ThreadCountConsensus.fuse(scans);
        if (!frame.ok) throw new AssertionError(frame.reason);
        if (frame.validScans < 6) throw new AssertionError("too few agreeing scan lines");
        if (Math.abs(frame.threadsPerCm - 7.5) > 0.8) {
            throw new AssertionError("consensus expected near 7.5/cm, got " + frame.threadsPerCm);
        }

        ThreadCountConsensus.Stabilizer stabilizer = new ThreadCountConsensus.Stabilizer(12, 6);
        ThreadCountConsensus.Snapshot snapshot = null;
        for (int frameIndex = 0; frameIndex < 8; frameIndex++) {
            ThreadProfileCounter.Result[] repeated = new ThreadProfileCounter.Result[9];
            for (int i = 0; i < repeated.length; i++) {
                repeated[i] = read(7.5, 0.20 + i * 0.03 + frameIndex * 0.004, 8.5);
            }
            snapshot = stabilizer.push(ThreadCountConsensus.fuse(repeated));
        }
        if (snapshot == null || !snapshot.stable) throw new AssertionError("temporal count did not stabilize");
        if (Math.abs(snapshot.threadsPerCm - 7.5) > 0.8) {
            throw new AssertionError("stable count expected near 7.5/cm, got " + snapshot.threadsPerCm);
        }
        System.out.println("multi-scan 1 cm consensus tests passed");
    }
}
