import com.alhadi.meshcheck.AutoRulerModel;

public class AutoRulerModelTest {
    private static void near(float expected, float actual, float tolerance, String name) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(name + " expected " + expected + " got " + actual);
        }
    }

    public static void main(String[] args) {
        float basePx = 240f;

        near(240f, AutoRulerModel.rulerPixels(basePx, 2f, 10f, 2f, 10f), 0.01f, "base");
        near(480f, AutoRulerModel.rulerPixels(basePx, 2f, 10f, 4f, 10f), 0.01f, "zoom doubles");
        near(120f, AutoRulerModel.rulerPixels(basePx, 2f, 10f, 2f, 20f), 0.01f, "distance doubles");
        near(240f, AutoRulerModel.rulerPixels(basePx, 2f, 10f, 4f, 20f), 0.01f, "zoom distance cancel");

        // Autofocus distance is relative to the physically measured base distance.
        near(5f, AutoRulerModel.distanceFromFocus(10f, 5f, 10f), 0.01f, "focus nearer");
        near(20f, AutoRulerModel.distanceFromFocus(10f, 5f, 2.5f), 0.01f, "focus farther");

        if (AutoRulerModel.distanceFromFocus(10f, 0f, 5f) != 0f) {
            throw new AssertionError("invalid focus should require manual fallback");
        }
        System.out.println("auto ruler zoom + distance tests passed");
    }
}
