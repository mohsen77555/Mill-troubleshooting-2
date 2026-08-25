import com.alhadi.meshcheck.ThreadProfileCounter;
import java.util.Random;

public class ThreadProfileCounterTest {
    private static float[] synthetic(int n, double threadsPerCm, boolean brightThreads, double noise) {
        float[] p = new float[n];
        Random random = new Random(7);
        double pitch = (n - 1) / threadsPerCm;
        for (int x = 0; x < n; x++) {
            double phase = ((x - 0.31 * pitch + pitch / 2.0) % pitch + pitch) % pitch - pitch / 2.0;
            double sigma = pitch * 0.30 / 2.355;
            double band = Math.exp(-0.5 * phase * phase / (sigma * sigma));
            double base = brightThreads ? 35 : 220;
            double amplitude = brightThreads ? 150 : -150;
            p[x] = (float) (base + amplitude * band + random.nextGaussian() * noise);
        }
        return p;
    }

    private static void check(double expected, boolean bright) {
        ThreadProfileCounter.Result r = ThreadProfileCounter.analyze(synthetic(720, expected, bright, 9));
        if (!r.ok) throw new AssertionError(r.reason);
        if (Math.abs(r.spacingThreadsPerCm - expected) > Math.max(0.8, expected * 0.04)) {
            throw new AssertionError("expected " + expected + " got " + r.spacingThreadsPerCm);
        }
        if (Math.abs(r.fullLineCount - expected) > 2.0) {
            throw new AssertionError("full count unreasonable: expected " + expected + " got " + r.fullLineCount);
        }
    }

    public static void main(String[] args) {
        for (double value : new double[]{4.3, 7.5, 15, 24, 41, 64}) {
            check(value, false);
            check(value, true);
        }
        System.out.println("centered 1 cm thread line counter tests passed");
    }
}
