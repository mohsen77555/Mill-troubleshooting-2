package com.alhadi.meshcheck;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High-accuracy post-capture crop for a physical 20 x 20 mm lens/opening.
 * The four handles are placed on the INNER corners of the known 20 mm opening.
 * The selected quadrilateral is perspective-rectified before thread counting.
 */
public class LensCropActivity extends ComponentActivity {
    public static final String EXTRA_INPUT_PATH = "meshcheck.crop.input_path";
    public static final String EXTRA_ZOOM_RATIO = "meshcheck.crop.zoom_ratio";

    private static final int SCAN_LINES = 25;
    private static final float PHYSICAL_SIDE_CM = 2.0f;
    private static final float ANALYSIS_INSET = 0.02f;
    private static final float ANALYSIS_LENGTH_CM = PHYSICAL_SIDE_CM * (1f - 2f * ANALYSIS_INSET);

    private CropView cropView;
    private TextView statusLabel;
    private Button analyzeButton;
    private Bitmap sourceBitmap;
    private String inputPath;
    private float zoomRatio = 1f;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        inputPath = getIntent().getStringExtra(EXTRA_INPUT_PATH);
        zoomRatio = getIntent().getFloatExtra(EXTRA_ZOOM_RATIO, 1f);
        if (inputPath == null || !(new File(inputPath).isFile())) {
            Toast.makeText(this, "الصورة غير موجودة.", Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        sourceBitmap = loadOrientedBitmap(inputPath);
        if (sourceBitmap == null) {
            Toast.makeText(this, "تعذر فتح الصورة.", Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        buildUi();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        cropView = new CropView(this, sourceBitmap);
        root.addView(cropView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView guide = new TextView(this);
        guide.setText("CROP 20×20 mm: ضع النقاط الأربع على الزوايا الداخلية لفتحة العدسة فقط");
        guide.setTextColor(Color.WHITE);
        guide.setTextSize(13f);
        guide.setGravity(Gravity.CENTER);
        guide.setPadding(dp(8), dp(8), dp(8), dp(8));
        guide.setBackgroundColor(0xAA000000);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        gp.setMargins(dp(6), dp(8), dp(6), 0);
        root.addView(guide, gp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(8), dp(6), dp(8), dp(10));
        bottom.setBackgroundColor(0xD10A1115);

        statusLabel = new TextView(this);
        statusLabel.setText("حرّك الزوايا بدقة. سيتم تصحيح الميل Perspective تلقائيًا بعد القص.");
        statusLabel.setTextColor(Color.WHITE);
        statusLabel.setTextSize(12f);
        statusLabel.setGravity(Gravity.CENTER);
        statusLabel.setPadding(dp(4), 0, dp(4), dp(4));
        bottom.addView(statusLabel, matchWrap());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);

        Button reset = new Button(this);
        reset.setText("RESET CROP");
        reset.setTextSize(10f);
        reset.setOnClickListener(v -> {
            cropView.resetCrop();
            statusLabel.setText("حرّك الزوايا إلى الحافة الداخلية لفتحة 20×20 mm.");
        });

        analyzeButton = new Button(this);
        analyzeButton.setText("قص + تحليل");
        analyzeButton.setTextSize(11f);
        analyzeButton.setOnClickListener(v -> analyzeSelectedCrop());

        Button cancel = new Button(this);
        cancel.setText("إلغاء");
        cancel.setTextSize(10f);
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        buttons.addView(reset, weighted());
        buttons.addView(analyzeButton, weighted());
        buttons.addView(cancel, weighted());
        bottom.addView(buttons, matchWrap());

        root.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
    }

    private void analyzeSelectedCrop() {
        final float[] corners = cropView.bitmapCorners();
        if (!validQuad(corners)) {
            Toast.makeText(this, "منطقة القص غير صحيحة. ضع النقاط حول الفتحة بدون تقاطع.", Toast.LENGTH_LONG).show();
            return;
        }
        analyzeButton.setEnabled(false);
        statusLabel.setText("جارٍ تصحيح المنظور وتحليل الصورة عالية الدقة...");

        executor.execute(() -> {
            try {
                int side = chooseOutputSide(corners);
                Bitmap rectified = rectify(sourceBitmap, corners, side);
                if (rectified == null) throw new IllegalStateException("Perspective correction failed");

                AnalysisResult result = analyzeRectified(rectified);
                if (!result.ok) {
                    rectified.recycle();
                    runOnUiThread(() -> {
                        analyzeButton.setEnabled(true);
                        statusLabel.setText(result.reason);
                    });
                    return;
                }

                File cropFile = new File(getCacheDir(), "meshcheck-lens20-crop-" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream stream = new FileOutputStream(cropFile)) {
                    rectified.compress(Bitmap.CompressFormat.JPEG, 96, stream);
                }
                rectified.recycle();

                Intent output = new Intent();
                output.putExtra(NativeCameraActivity.EXTRA_CAPTURE_PATH, cropFile.getAbsolutePath());
                output.putExtra(NativeCameraActivity.EXTRA_ZOOM_RATIO, zoomRatio);
                output.putExtra(NativeCameraActivity.EXTRA_FIXED_DISTANCE_CM, 0f);
                output.putExtra(NativeCameraActivity.EXTRA_FIXED_CALIBRATED, true);
                output.putExtra(NativeCameraActivity.EXTRA_RULER_BASE_PX_1X, 0f);
                output.putExtra(NativeCameraActivity.EXTRA_MARKER_MODE, false);
                output.putExtra(NativeCameraActivity.EXTRA_MANUAL_ROI_MODE, true);
                output.putExtra(NativeCameraActivity.EXTRA_THREAD_COUNT_X_CM, result.xCount);
                output.putExtra(NativeCameraActivity.EXTRA_THREAD_COUNT_Y_CM, result.yCount);
                output.putExtra(NativeCameraActivity.EXTRA_FULL_LINE_X, Math.round(result.xCount * 2f));
                output.putExtra(NativeCameraActivity.EXTRA_FULL_LINE_Y, Math.round(result.yCount * 2f));
                float primary = result.xCount > 0f && result.yCount > 0f
                        ? (result.xCount + result.yCount) / 2f
                        : Math.max(result.xCount, result.yCount);
                output.putExtra(NativeCameraActivity.EXTRA_THREAD_COUNT_CM, primary);
                output.putExtra(NativeCameraActivity.EXTRA_FULL_LINE_COUNT, Math.round(primary * 2f));
                output.putExtra(NativeCameraActivity.EXTRA_THREAD_COUNT_CONFIDENCE, result.confidence);
                output.putExtra(NativeCameraActivity.EXTRA_THREAD_COUNT_STABLE, true);

                runOnUiThread(() -> {
                    setResult(RESULT_OK, output);
                    finish();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    analyzeButton.setEnabled(true);
                    statusLabel.setText("تعذر تحليل القص: " + exception.getMessage());
                });
            }
        });
    }

    private AnalysisResult analyzeRectified(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w < 500 || h < 500) return AnalysisResult.fail("منطقة القص صغيرة جدًا — قرّب العدسة أو استخدم Zoom أكبر.");

        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        float sharpness = gradientSharpness(pixels, w, h);
        if (sharpness < 1.5f) return AnalysisResult.fail("الصورة غير حادة بما يكفي. أعد التصوير بعد التركيز على الخيوط.");

        ThreadProfileCounter.Result[] xScans = buildScans(pixels, w, h, true);
        ThreadProfileCounter.Result[] yScans = buildScans(pixels, w, h, false);
        ThreadCountConsensus.FrameResult x = ThreadCountConsensus.fuse(xScans, ANALYSIS_LENGTH_CM);
        ThreadCountConsensus.FrameResult y = ThreadCountConsensus.fuse(yScans, ANALYSIS_LENGTH_CM);

        if (!x.ok && !y.ok) {
            return AnalysisResult.fail("لم يتمكن التطبيق من تثبيت تكرار الخيوط داخل القص. اضبط الزوايا على الحافة الداخلية فقط.");
        }

        float xCount = x.ok ? x.threadsPerCm : 0f;
        float yCount = y.ok ? y.threadsPerCm : 0f;
        float baseConfidence = x.ok && y.ok ? Math.min(x.confidence, y.confidence)
                : Math.max(x.confidence, y.confidence);
        float sharpnessScore = clamp01((sharpness - 1.5f) / 10f);
        float confidence = clamp01(baseConfidence * 0.88f + sharpnessScore * 0.12f);
        return new AnalysisResult(true, "", xCount, yCount, confidence);
    }

    private ThreadProfileCounter.Result[] buildScans(int[] pixels, int w, int h, boolean horizontal) {
        ThreadProfileCounter.Result[] results = new ThreadProfileCounter.Result[SCAN_LINES];
        int start = Math.round((horizontal ? w : h) * ANALYSIS_INSET);
        int end = Math.round((horizontal ? w : h) * (1f - ANALYSIS_INSET));
        int length = Math.max(1, end - start);

        for (int s = 0; s < SCAN_LINES; s++) {
            float fixed = 0.07f + 0.86f * s / Math.max(1f, SCAN_LINES - 1f);
            int fixedPx = Math.round((horizontal ? h : w) * fixed);
            float[] profile = new float[length];
            for (int i = 0; i < length; i++) {
                int moving = start + i;
                float sum = 0f;
                int count = 0;
                for (int thick = -2; thick <= 2; thick++) {
                    int x = horizontal ? moving : clamp(fixedPx + thick, 0, w - 1);
                    int y = horizontal ? clamp(fixedPx + thick, 0, h - 1) : moving;
                    int color = pixels[y * w + x];
                    sum += luma(color);
                    count++;
                }
                profile[i] = sum / count;
            }
            results[s] = ThreadProfileCounter.analyze(profile, ANALYSIS_LENGTH_CM);
        }
        return results;
    }

    private static float gradientSharpness(int[] pixels, int w, int h) {
        double sum = 0.0;
        int count = 0;
        int step = Math.max(2, Math.min(w, h) / 400);
        for (int y = step; y < h - step; y += step) {
            for (int x = step; x < w - step; x += step) {
                float c = luma(pixels[y * w + x]);
                float gx = Math.abs(luma(pixels[y * w + (x + step)]) - c);
                float gy = Math.abs(luma(pixels[(y + step) * w + x]) - c);
                sum += gx + gy;
                count++;
            }
        }
        return count > 0 ? (float) (sum / count) : 0f;
    }

    private int chooseOutputSide(float[] q) {
        float top = distance(q[0], q[1], q[2], q[3]);
        float right = distance(q[2], q[3], q[4], q[5]);
        float bottom = distance(q[4], q[5], q[6], q[7]);
        float left = distance(q[6], q[7], q[0], q[1]);
        int average = Math.round((top + right + bottom + left) / 4f);
        return clamp(average, 900, 2000);
    }

    private static Bitmap rectify(Bitmap source, float[] q, int side) {
        Bitmap output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);
        float[] destination = new float[]{0f, 0f, side - 1f, 0f, side - 1f, side - 1f, 0f, side - 1f};
        Matrix transform = new Matrix();
        if (!transform.setPolyToPoly(q, 0, destination, 0, 4)) {
            output.recycle();
            return null;
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        canvas.drawBitmap(source, transform, paint);
        return output;
    }

    private static Bitmap loadOrientedBitmap(String path) {
        try {
            Bitmap raw = BitmapFactory.decodeFile(path);
            if (raw == null) return null;
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90f); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180f); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270f); break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.postScale(-1f, 1f); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.postScale(1f, -1f); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: matrix.postRotate(90f); matrix.postScale(-1f, 1f); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: matrix.postRotate(270f); matrix.postScale(-1f, 1f); break;
                default: break;
            }
            if (matrix.isIdentity()) return raw;
            Bitmap rotated = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, true);
            if (rotated != raw) raw.recycle();
            return rotated;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (sourceBitmap != null && !sourceBitmap.isRecycled()) sourceBitmap.recycle();
        super.onDestroy();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static float luma(int color) {
        return 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color);
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static boolean validQuad(float[] q) {
        if (q == null || q.length != 8) return false;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            if (distance(q[i * 2], q[i * 2 + 1], q[j * 2], q[j * 2 + 1]) < 80f) return false;
        }
        float sign = 0f;
        for (int i = 0; i < 4; i++) {
            int a = i, b = (i + 1) % 4, c = (i + 2) % 4;
            float abx = q[b * 2] - q[a * 2];
            float aby = q[b * 2 + 1] - q[a * 2 + 1];
            float bcx = q[c * 2] - q[b * 2];
            float bcy = q[c * 2 + 1] - q[b * 2 + 1];
            float cross = abx * bcy - aby * bcx;
            if (Math.abs(cross) < 30f) return false;
            if (sign == 0f) sign = Math.signum(cross);
            else if (Math.signum(cross) != sign) return false;
        }
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static final class AnalysisResult {
        final boolean ok;
        final String reason;
        final float xCount;
        final float yCount;
        final float confidence;

        AnalysisResult(boolean ok, String reason, float xCount, float yCount, float confidence) {
            this.ok = ok;
            this.reason = reason;
            this.xCount = xCount;
            this.yCount = yCount;
            this.confidence = confidence;
        }

        static AnalysisResult fail(String reason) {
            return new AnalysisResult(false, reason, 0f, 0f, 0f);
        }
    }

    private static final class CropView extends View {
        private final Bitmap bitmap;
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final float handleRadius;
        private final float[] corners = new float[8]; // bitmap coordinates TL,TR,BR,BL
        private float scale = 1f;
        private float offsetX, offsetY;
        private int activeCorner = -1;
        private boolean movingWhole;
        private float lastX, lastY;
        private boolean initialized;

        CropView(LensCropActivity context, Bitmap bitmap) {
            super(context);
            this.bitmap = bitmap;
            density = getResources().getDisplayMetrics().density;
            handleRadius = 16f * density;
            borderPaint.setColor(0xFFFFD54F);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(3f * density);
            handlePaint.setColor(0xFFFFD54F);
            handlePaint.setStyle(Paint.Style.FILL);
            dimPaint.setColor(0x75000000);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(12f * density);
            textPaint.setFakeBoldText(true);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            computeFit(w, h);
            if (!initialized) {
                initialized = true;
                resetCrop();
            }
        }

        private void computeFit(int w, int h) {
            scale = Math.min(w / (float) bitmap.getWidth(), h / (float) bitmap.getHeight());
            offsetX = (w - bitmap.getWidth() * scale) / 2f;
            offsetY = (h - bitmap.getHeight() * scale) / 2f;
        }

        void resetCrop() {
            float bw = bitmap.getWidth();
            float bh = bitmap.getHeight();
            corners[0] = bw * 0.16f; corners[1] = bh * 0.16f;
            corners[2] = bw * 0.84f; corners[3] = bh * 0.16f;
            corners[4] = bw * 0.84f; corners[5] = bh * 0.84f;
            corners[6] = bw * 0.16f; corners[7] = bh * 0.84f;
            invalidate();
        }

        float[] bitmapCorners() {
            synchronized (corners) { return corners.clone(); }
        }

        private float bxToView(float x) { return offsetX + x * scale; }
        private float byToView(float y) { return offsetY + y * scale; }
        private float viewToBx(float x) { return (x - offsetX) / scale; }
        private float viewToBy(float y) { return (y - offsetY) / scale; }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postTranslate(offsetX, offsetY);
            canvas.drawBitmap(bitmap, m, imagePaint);

            float[] q = bitmapCorners();
            Path path = new Path();
            path.moveTo(bxToView(q[0]), byToView(q[1]));
            path.lineTo(bxToView(q[2]), byToView(q[3]));
            path.lineTo(bxToView(q[4]), byToView(q[5]));
            path.lineTo(bxToView(q[6]), byToView(q[7]));
            path.close();

            Path dim = new Path();
            dim.setFillType(Path.FillType.EVEN_ODD);
            dim.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            dim.addPath(path);
            canvas.drawPath(dim, dimPaint);
            canvas.drawPath(path, borderPaint);

            String[] names = new String[]{"1", "2", "3", "4"};
            for (int i = 0; i < 4; i++) {
                float x = bxToView(q[i * 2]);
                float y = byToView(q[i * 2 + 1]);
                canvas.drawCircle(x, y, handleRadius, handlePaint);
                float tw = textPaint.measureText(names[i]);
                canvas.drawText(names[i], x - tw / 2f, y + 5f * density, textPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX(), y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    activeCorner = nearestCorner(x, y);
                    if (activeCorner >= 0) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    if (insideQuad(x, y)) {
                        movingWhole = true;
                        lastX = x; lastY = y;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (activeCorner >= 0) {
                        synchronized (corners) {
                            corners[activeCorner * 2] = clamp(viewToBx(x), 0f, bitmap.getWidth() - 1f);
                            corners[activeCorner * 2 + 1] = clamp(viewToBy(y), 0f, bitmap.getHeight() - 1f);
                        }
                        invalidate();
                        return true;
                    }
                    if (movingWhole) {
                        float dx = (x - lastX) / scale;
                        float dy = (y - lastY) / scale;
                        translate(dx, dy);
                        lastX = x; lastY = y;
                        invalidate();
                        return true;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    activeCorner = -1;
                    movingWhole = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return true;
            }
        }

        private int nearestCorner(float x, float y) {
            float[] q = bitmapCorners();
            int best = -1;
            float bestDistance = handleRadius * 2.4f;
            for (int i = 0; i < 4; i++) {
                float dx = x - bxToView(q[i * 2]);
                float dy = y - byToView(q[i * 2 + 1]);
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < bestDistance) { bestDistance = d; best = i; }
            }
            return best;
        }

        private boolean insideQuad(float x, float y) {
            float[] q = bitmapCorners();
            boolean inside = false;
            for (int i = 0, j = 3; i < 4; j = i++) {
                float xi = bxToView(q[i * 2]), yi = byToView(q[i * 2 + 1]);
                float xj = bxToView(q[j * 2]), yj = byToView(q[j * 2 + 1]);
                boolean intersect = ((yi > y) != (yj > y))
                        && (x < (xj - xi) * (y - yi) / (yj - yi + 0.0001f) + xi);
                if (intersect) inside = !inside;
            }
            return inside;
        }

        private void translate(float dx, float dy) {
            synchronized (corners) {
                float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
                float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (int i = 0; i < 4; i++) {
                    minX = Math.min(minX, corners[i * 2]);
                    maxX = Math.max(maxX, corners[i * 2]);
                    minY = Math.min(minY, corners[i * 2 + 1]);
                    maxY = Math.max(maxY, corners[i * 2 + 1]);
                }
                if (minX + dx < 0f) dx = -minX;
                if (maxX + dx > bitmap.getWidth() - 1f) dx = bitmap.getWidth() - 1f - maxX;
                if (minY + dy < 0f) dy = -minY;
                if (maxY + dy > bitmap.getHeight() - 1f) dy = bitmap.getHeight() - 1f - maxY;
                for (int i = 0; i < 4; i++) {
                    corners[i * 2] += dx;
                    corners[i * 2 + 1] += dy;
                }
            }
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
