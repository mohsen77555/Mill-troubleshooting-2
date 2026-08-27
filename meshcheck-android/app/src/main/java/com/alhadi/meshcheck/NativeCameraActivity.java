package com.alhadi.meshcheck;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * v0.16: simplest 20x20 mm mode.
 * No calibration, no marker detection, no draggable corners, no lock step.
 * The user visually fits the REAL 20x20 mm inner opening to the fixed center box.
 * The app only counts threads inside that fixed box.
 */
public class NativeCameraActivity extends ComponentActivity {
    public static final String EXTRA_CAPTURE_PATH = "meshcheck.capture_path";
    public static final String EXTRA_ZOOM_RATIO = "meshcheck.zoom_ratio";
    public static final String EXTRA_FIXED_DISTANCE_CM = "meshcheck.fixed_distance_cm";
    public static final String EXTRA_FIXED_CALIBRATED = "meshcheck.fixed_calibrated";
    public static final String EXTRA_RULER_BASE_PX_1X = "meshcheck.ruler_base_px_1x";
    public static final String EXTRA_THREAD_COUNT_CM = "meshcheck.thread_count_cm";
    public static final String EXTRA_FULL_LINE_COUNT = "meshcheck.full_line_count";
    public static final String EXTRA_THREAD_COUNT_CONFIDENCE = "meshcheck.thread_count_confidence";
    public static final String EXTRA_THREAD_COUNT_STABLE = "meshcheck.thread_count_stable";
    public static final String EXTRA_THREAD_COUNT_X_CM = "meshcheck.thread_count_x_cm";
    public static final String EXTRA_THREAD_COUNT_Y_CM = "meshcheck.thread_count_y_cm";
    public static final String EXTRA_FULL_LINE_X = "meshcheck.full_line_x";
    public static final String EXTRA_FULL_LINE_Y = "meshcheck.full_line_y";
    public static final String EXTRA_MARKER_MODE = "meshcheck.marker_20mm";
    public static final String EXTRA_MANUAL_ROI_MODE = "meshcheck.manual_20mm_roi";

    private static final int CAMERA_PERMISSION_REQUEST = 2201;
    private static final int SCAN_LINES = 9;
    private static final float PHYSICAL_SIDE_CM = 2.0f;
    private static final float EDGE_INSET = 0.0125f; // ignore only the extreme edge; physical length = 19.5 mm
    private static final float MEASURED_LENGTH_CM = PHYSICAL_SIDE_CM * (1f - 2f * EDGE_INSET);

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Camera camera;
    private Button captureButton;
    private Button flashButton;
    private TextView statusLabel;
    private TextView resultLabel;
    private TextView zoomLabel;
    private FixedSquareOverlay overlay;
    private ScaleGestureDetector scaleGestureDetector;
    private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();
    private final ThreadCountConsensus.Stabilizer stabilizerX = new ThreadCountConsensus.Stabilizer(14, 7);
    private final ThreadCountConsensus.Stabilizer stabilizerY = new ThreadCountConsensus.Stabilizer(14, 7);

    private boolean torchOn;
    private boolean zoomGestureUsed;
    private volatile float currentZoomRatio = 1f;

    private final Object measurementLock = new Object();
    private float lastX, lastY, lastConfidence;
    private int lastFullX, lastFullY;
    private boolean lastStable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(18, 59, 80));
        buildUi();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new FixedSquareOverlay(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView guide = new TextView(this);
        guide.setText("طابق الفتحة الداخلية لمربعك الحقيقي 20×20 mm مع الإطار الأصفر • التطبيق يعد ما بداخله فقط");
        guide.setTextColor(Color.WHITE);
        guide.setTextSize(13f);
        guide.setGravity(Gravity.CENTER);
        guide.setPadding(dp(8), dp(8), dp(8), dp(8));
        guide.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        gp.setMargins(dp(8), dp(10), dp(8), 0);
        root.addView(guide, gp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(8), dp(5), dp(8), dp(10));
        bottom.setBackgroundColor(0xB3071318);

        statusLabel = new TextView(this);
        statusLabel.setText("ضع مربعك داخل الإطار ثم ثبّت الهاتف");
        statusLabel.setTextColor(Color.WHITE);
        statusLabel.setTextSize(13f);
        statusLabel.setGravity(Gravity.CENTER);
        bottom.addView(statusLabel, matchWrap());

        resultLabel = new TextView(this);
        resultLabel.setText("X: --  •  Y: --");
        resultLabel.setTextColor(Color.WHITE);
        resultLabel.setTextSize(14f);
        resultLabel.setGravity(Gravity.CENTER);
        resultLabel.setPadding(0, dp(3), 0, dp(3));
        bottom.addView(resultLabel, matchWrap());

        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER);
        zoomRow.addView(makeZoomButton("1×", 1f), weighted());
        zoomRow.addView(makeZoomButton("2×", 2f), weighted());
        zoomRow.addView(makeZoomButton("3×", 3f), weighted());
        zoomRow.addView(makeZoomButton("5×", 5f), weighted());
        zoomLabel = new TextView(this);
        zoomLabel.setText("1.0×");
        zoomLabel.setTextColor(Color.WHITE);
        zoomLabel.setTextSize(11f);
        zoomLabel.setGravity(Gravity.CENTER);
        zoomRow.addView(zoomLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));
        bottom.addView(zoomRow, matchWrap());

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        captureButton = new Button(this);
        captureButton.setText("التقاط وتحليل");
        captureButton.setEnabled(false);
        captureButton.setOnClickListener(v -> capturePhoto());
        flashButton = new Button(this);
        flashButton.setText("FLASH");
        flashButton.setEnabled(false);
        flashButton.setOnClickListener(v -> toggleTorch());
        Button back = new Button(this);
        back.setText("رجوع");
        back.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        controls.addView(captureButton, weighted());
        controls.addView(flashButton, weighted());
        controls.addView(back, weighted());
        bottom.addView(controls, matchWrap());

        root.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        setupTouchControls();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private Button makeZoomButton(String text, float ratio) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(10f);
        b.setOnClickListener(v -> setZoomInternal(ratio));
        return b;
    }

    private void setupTouchControls() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                zoomGestureUsed = true;
                return true;
            }
            @Override public boolean onScale(@NonNull ScaleGestureDetector detector) {
                if (camera == null) return false;
                ZoomState state = camera.getCameraInfo().getZoomState().getValue();
                if (state == null) return false;
                setZoomInternal(state.getZoomRatio() * detector.getScaleFactor());
                return true;
            }
        });
        previewView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) zoomGestureUsed = false;
            scaleGestureDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && !zoomGestureUsed) {
                focusAt(event.getX(), event.getY());
                view.performClick();
            }
            return true;
        });
    }

    private void setZoomInternal(float requested) {
        if (camera == null) return;
        ZoomState state = camera.getCameraInfo().getZoomState().getValue();
        if (state == null) return;
        float clamped = Math.max(state.getMinZoomRatio(), Math.min(state.getMaxZoomRatio(), requested));
        camera.getCameraControl().setZoomRatio(clamped);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().setTargetResolution(new Size(1280, 720)).build();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetResolution(new Size(2560, 1440)).build();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(analyzerExecutor, this::analyzeFrame);
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture, analysis);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                flashButton.setEnabled(camera.getCameraInfo().hasFlashUnit());
                camera.getCameraInfo().getZoomState().observe(this, state -> {
                    if (state == null) return;
                    float newZoom = state.getZoomRatio();
                    if (Math.abs(newZoom - currentZoomRatio) > 0.02f) resetMeasurement();
                    currentZoomRatio = newZoom;
                    zoomLabel.setText(String.format(Locale.US, "%.2f×", currentZoomRatio));
                });
            } catch (ExecutionException e) {
                Toast.makeText(this, "تعذر تشغيل الكاميرا: " + e.getMessage(), Toast.LENGTH_LONG).show();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Toast.makeText(this, "تعذر تشغيل الكاميرا: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        try {
            RectF roi = overlay.snapshotRoi();
            if (roi == null || roi.width() < 80f || roi.height() < 80f) return;

            DirectionMeasurement x = measureDirection(image, roi, true);
            DirectionMeasurement y = measureDirection(image, roi, false);
            if (!x.ok && !y.ok) {
                runOnUiThread(() -> {
                    statusLabel.setText("الخيوط غير واضحة — اضغط داخل المربع للتركيز");
                    captureButton.setEnabled(false);
                });
                return;
            }

            ThreadCountConsensus.Snapshot sx = x.ok ? stabilizerX.push(x.frame) : stabilizerX.current();
            ThreadCountConsensus.Snapshot sy = y.ok ? stabilizerY.push(y.frame) : stabilizerY.current();
            acceptMeasurements(x, y, sx, sy);
        } catch (Exception e) {
            runOnUiThread(() -> statusLabel.setText("تعذر العد داخل المربع: " + e.getMessage()));
        } finally {
            image.close();
        }
    }

    private DirectionMeasurement measureDirection(ImageProxy image, RectF roi, boolean horizontal) {
        int sideSamples = estimateSamples(image, roi, horizontal);
        if (sideSamples < 120) return DirectionMeasurement.fail();

        ThreadProfileCounter.Result[] scans = new ThreadProfileCounter.Result[SCAN_LINES];
        for (int s = 0; s < SCAN_LINES; s++) {
            float fixed = 0.10f + 0.80f * s / Math.max(1f, SCAN_LINES - 1f);
            float[] profile = new float[sideSamples];
            for (int i = 0; i < sideSamples; i++) {
                float t = EDGE_INSET + (1f - 2f * EDGE_INSET) * i / Math.max(1f, sideSamples - 1f);
                float vx = horizontal
                        ? roi.left + roi.width() * t
                        : roi.left + roi.width() * fixed;
                float vy = horizontal
                        ? roi.top + roi.height() * fixed
                        : roi.top + roi.height() * t;
                profile[i] = sampleAtViewPoint(image, vx, vy);
            }
            scans[s] = ThreadProfileCounter.analyze(profile, MEASURED_LENGTH_CM);
        }
        ThreadCountConsensus.FrameResult frame = ThreadCountConsensus.fuse(scans, MEASURED_LENGTH_CM);
        return frame.ok ? new DirectionMeasurement(true, frame) : DirectionMeasurement.fail();
    }

    private int estimateSamples(ImageProxy image, RectF roi, boolean horizontal) {
        int rotation = normalizedRotation(image);
        int rw = (rotation == 90 || rotation == 270) ? image.getHeight() : image.getWidth();
        int rh = (rotation == 90 || rotation == 270) ? image.getWidth() : image.getHeight();
        float scale = Math.min(previewView.getWidth() / (float) rw, previewView.getHeight() / (float) rh);
        float viewLength = horizontal ? roi.width() : roi.height();
        int sourcePixels = Math.round(viewLength / Math.max(0.001f, scale));
        return Math.max(160, Math.min(1200, sourcePixels));
    }

    private float sampleAtViewPoint(ImageProxy image, float viewX, float viewY) {
        SourcePoint p = viewToSource(image, viewX, viewY);
        if (p == null) return 0f;
        int ix = Math.max(0, Math.min(image.getWidth() - 1, Math.round(p.x)));
        int iy = Math.max(0, Math.min(image.getHeight() - 1, Math.round(p.y)));
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        float sum = 0f;
        int count = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int sy = Math.max(0, Math.min(image.getHeight() - 1, iy + dy));
            int index = sy * rowStride + ix * pixelStride;
            if (index >= 0 && index < buffer.limit()) {
                sum += buffer.get(index) & 0xFF;
                count++;
            }
        }
        return count > 0 ? sum / count : 0f;
    }

    private SourcePoint viewToSource(ImageProxy image, float viewX, float viewY) {
        if (previewView.getWidth() <= 0 || previewView.getHeight() <= 0) return null;
        int sw = image.getWidth();
        int sh = image.getHeight();
        int rotation = normalizedRotation(image);
        int rw = (rotation == 90 || rotation == 270) ? sh : sw;
        int rh = (rotation == 90 || rotation == 270) ? sw : sh;
        float scale = Math.min(previewView.getWidth() / (float) rw, previewView.getHeight() / (float) rh);
        float ox = (previewView.getWidth() - rw * scale) / 2f;
        float oy = (previewView.getHeight() - rh * scale) / 2f;
        float rx = (viewX - ox) / scale;
        float ry = (viewY - oy) / scale;
        if (rx < 0f || ry < 0f || rx > rw - 1f || ry > rh - 1f) return null;

        float sx, sy;
        switch (rotation) {
            case 90:
                sx = ry;
                sy = sh - 1f - rx;
                break;
            case 180:
                sx = sw - 1f - rx;
                sy = sh - 1f - ry;
                break;
            case 270:
                sx = sw - 1f - ry;
                sy = rx;
                break;
            default:
                sx = rx;
                sy = ry;
                break;
        }
        if (sx < 0f || sy < 0f || sx > sw - 1f || sy > sh - 1f) return null;
        return new SourcePoint(sx, sy);
    }

    private static int normalizedRotation(ImageProxy image) {
        return ((image.getImageInfo().getRotationDegrees() % 360) + 360) % 360;
    }

    private void acceptMeasurements(DirectionMeasurement x, DirectionMeasurement y,
                                    ThreadCountConsensus.Snapshot sx,
                                    ThreadCountConsensus.Snapshot sy) {
        float vx = sx.threadsPerCm > 0f ? sx.threadsPerCm : (x.ok ? x.frame.threadsPerCm : 0f);
        float vy = sy.threadsPerCm > 0f ? sy.threadsPerCm : (y.ok ? y.frame.threadsPerCm : 0f);

        boolean vibration = (x.ok && !sx.accepted && containsVibration(sx.reason))
                || (y.ok && !sy.accepted && containsVibration(sy.reason));
        boolean stableX = !x.ok || sx.stable;
        boolean stableY = !y.ok || sy.stable;
        boolean stable = !vibration && (vx > 0f || vy > 0f) && stableX && stableY;

        int fullX = vx > 0f ? Math.max(1, Math.round(vx * PHYSICAL_SIDE_CM)) : 0;
        int fullY = vy > 0f ? Math.max(1, Math.round(vy * PHYSICAL_SIDE_CM)) : 0;
        float confidence;
        if (sx.confidence > 0f && sy.confidence > 0f) confidence = Math.min(sx.confidence, sy.confidence);
        else confidence = Math.max(sx.confidence, sy.confidence);

        synchronized (measurementLock) {
            lastX = vx;
            lastY = vy;
            lastFullX = fullX;
            lastFullY = fullY;
            lastStable = stable;
            lastConfidence = confidence;
        }

        runOnUiThread(() -> {
            if (vibration) statusLabel.setText("HOLD — اهتزاز، القراءة محفوظة حتى يثبت الهاتف");
            else statusLabel.setText(stable ? "✓ ثابت — العد فقط داخل مربع 20×20"
                    : "جارٍ تثبيت العد داخل المربع...");
            resultLabel.setText(String.format(Locale.US,
                    "X %.1f n/cm (≈%d/20mm)  •  Y %.1f n/cm (≈%d/20mm)",
                    vx, fullX, vy, fullY));
            overlay.setResults(vx, vy, stable);
            captureButton.setEnabled(stable);
        });
    }

    private static boolean containsVibration(String reason) {
        return reason != null && reason.toLowerCase(Locale.US).contains("vibration");
    }

    private void resetMeasurement() {
        stabilizerX.reset();
        stabilizerY.reset();
        synchronized (measurementLock) {
            lastX = lastY = lastConfidence = 0f;
            lastFullX = lastFullY = 0;
            lastStable = false;
        }
        runOnUiThread(() -> {
            captureButton.setEnabled(false);
            resultLabel.setText("X: --  •  Y: --");
            statusLabel.setText("طابق مربعك 20×20 مع الإطار ثم ثبّت الهاتف");
            overlay.setResults(0f, 0f, false);
        });
    }

    private void focusAt(float x, float y) {
        if (camera == null) return;
        try {
            MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(4, TimeUnit.SECONDS).build();
            camera.getCameraControl().startFocusAndMetering(action);
            overlay.showFocus(x, y);
        } catch (Exception ignored) {}
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        flashButton.setText(torchOn ? "FLASH ON" : "FLASH");
    }

    private void capturePhoto() {
        if (imageCapture == null) return;
        synchronized (measurementLock) {
            if (!lastStable) {
                Toast.makeText(this, "انتظر حتى تثبت القراءة داخل مربع 20×20.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        captureButton.setEnabled(false);
        File output = new File(getCacheDir(), "meshcheck-fixed20-" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(output).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Intent result = new Intent();
                result.putExtra(EXTRA_CAPTURE_PATH, output.getAbsolutePath());
                result.putExtra(EXTRA_ZOOM_RATIO, currentZoomRatio);
                result.putExtra(EXTRA_FIXED_DISTANCE_CM, 0f);
                result.putExtra(EXTRA_FIXED_CALIBRATED, true);
                result.putExtra(EXTRA_RULER_BASE_PX_1X, 0f);
                result.putExtra(EXTRA_MARKER_MODE, false);
                result.putExtra(EXTRA_MANUAL_ROI_MODE, true);
                synchronized (measurementLock) {
                    float primary = lastX > 0f && lastY > 0f ? (lastX + lastY) / 2f : Math.max(lastX, lastY);
                    result.putExtra(EXTRA_THREAD_COUNT_CM, primary);
                    result.putExtra(EXTRA_FULL_LINE_COUNT, Math.max(lastFullX, lastFullY));
                    result.putExtra(EXTRA_THREAD_COUNT_X_CM, lastX);
                    result.putExtra(EXTRA_THREAD_COUNT_Y_CM, lastY);
                    result.putExtra(EXTRA_FULL_LINE_X, lastFullX);
                    result.putExtra(EXTRA_FULL_LINE_Y, lastFullY);
                    result.putExtra(EXTRA_THREAD_COUNT_CONFIDENCE, lastConfidence);
                    result.putExtra(EXTRA_THREAD_COUNT_STABLE, lastStable);
                }
                setResult(RESULT_OK, result);
                finish();
            }

            @Override public void onError(@NonNull ImageCaptureException exception) {
                captureButton.setEnabled(true);
                Toast.makeText(NativeCameraActivity.this,
                        "تعذر التقاط الصورة: " + exception.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode,
                                                     @NonNull String[] permissions,
                                                     @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else { setResult(RESULT_CANCELED); finish(); }
    }

    @Override protected void onDestroy() {
        analyzerExecutor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class SourcePoint {
        final float x, y;
        SourcePoint(float x, float y) { this.x = x; this.y = y; }
    }

    private static final class DirectionMeasurement {
        final boolean ok;
        final ThreadCountConsensus.FrameResult frame;
        DirectionMeasurement(boolean ok, ThreadCountConsensus.FrameResult frame) {
            this.ok = ok;
            this.frame = frame;
        }
        static DirectionMeasurement fail() { return new DirectionMeasurement(false, null); }
    }

    /** Fixed, non-interactive center square. User fits the real 20x20 opening to this box. */
    private static final class FixedSquareOverlay extends View {
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stablePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final RectF roi = new RectF();
        private float xDensity, yDensity;
        private boolean measurementStable;
        private float focusX = -1f, focusY = -1f;
        private long focusTime;

        FixedSquareOverlay(NativeCameraActivity context) {
            super(context);
            setWillNotDraw(false);
            density = getResources().getDisplayMetrics().density;
            borderPaint.setColor(0xFFFFD54F);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(3f * density);
            stablePaint.setColor(0xFF67E8D1);
            stablePaint.setStyle(Paint.Style.STROKE);
            stablePaint.setStrokeWidth(4f * density);
            scanPaint.setColor(0x4473D7FF);
            scanPaint.setStrokeWidth(1f * density);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(12f * density);
            textPaint.setFakeBoldText(true);
            focusPaint.setColor(0xFF67E8D1);
            focusPaint.setStyle(Paint.Style.STROKE);
            focusPaint.setStrokeWidth(2f * density);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float side = Math.min(w * 0.61f, h * 0.34f);
            float cx = w / 2f;
            float cy = h * 0.49f;
            roi.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);
        }

        RectF snapshotRoi() {
            synchronized (roi) { return new RectF(roi); }
        }

        void setResults(float x, float y, boolean stable) {
            xDensity = x;
            yDensity = y;
            measurementStable = stable;
            invalidate();
        }

        void showFocus(float x, float y) {
            focusX = x;
            focusY = y;
            focusTime = System.currentTimeMillis();
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            RectF r = snapshotRoi();
            Paint edge = measurementStable ? stablePaint : borderPaint;
            canvas.drawRect(r, edge);

            float corner = 20f * density;
            canvas.drawLine(r.left, r.top, r.left + corner, r.top, edge);
            canvas.drawLine(r.left, r.top, r.left, r.top + corner, edge);
            canvas.drawLine(r.right, r.top, r.right - corner, r.top, edge);
            canvas.drawLine(r.right, r.top, r.right, r.top + corner, edge);
            canvas.drawLine(r.left, r.bottom, r.left + corner, r.bottom, edge);
            canvas.drawLine(r.left, r.bottom, r.left, r.bottom - corner, edge);
            canvas.drawLine(r.right, r.bottom, r.right - corner, r.bottom, edge);
            canvas.drawLine(r.right, r.bottom, r.right, r.bottom - corner, edge);

            for (int s = 0; s < SCAN_LINES; s++) {
                float f = 0.10f + 0.80f * s / Math.max(1f, SCAN_LINES - 1f);
                float y = r.top + r.height() * f;
                float x = r.left + r.width() * f;
                canvas.drawLine(r.left, y, r.right, y, scanPaint);
                canvas.drawLine(x, r.top, x, r.bottom, scanPaint);
            }

            String title = "ضع الفتحة الداخلية 20×20 mm هنا — NO CALIBRATION";
            canvas.drawText(title, Math.max(10f, r.left), Math.max(80f, r.top - 14f * density), textPaint);
            if (xDensity > 0f || yDensity > 0f) {
                String result = String.format(Locale.US, "X %.1f • Y %.1f n/cm • %s",
                        xDensity, yDensity, measurementStable ? "STABLE" : "MEASURING");
                canvas.drawText(result, Math.max(10f, r.left),
                        Math.min(getHeight() - 150f, r.bottom + 25f * density), textPaint);
            }

            if (focusX >= 0f && System.currentTimeMillis() - focusTime < 1200L) {
                canvas.drawCircle(focusX, focusY, 25f * density, focusPaint);
                postInvalidateOnAnimation();
            }
        }
    }
}
