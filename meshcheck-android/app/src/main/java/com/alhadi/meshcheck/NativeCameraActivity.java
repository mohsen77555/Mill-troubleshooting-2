package com.alhadi.meshcheck;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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

/** v0.15: user-defined manual 20x20 mm ROI. No marker detection or auto square tracking. */
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

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Camera camera;
    private Button captureButton;
    private Button flashButton;
    private Button lockButton;
    private TextView statusLabel;
    private TextView resultLabel;
    private TextView zoomLabel;
    private ManualRoiOverlay overlay;
    private ScaleGestureDetector scaleGestureDetector;
    private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();
    private final ThreadCountConsensus.Stabilizer stabilizerX = new ThreadCountConsensus.Stabilizer(14, 7);
    private final ThreadCountConsensus.Stabilizer stabilizerY = new ThreadCountConsensus.Stabilizer(14, 7);

    private boolean torchOn;
    private boolean zoomGestureUsed;
    private volatile float currentZoomRatio = 1f;
    private volatile boolean roiLocked;

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

        overlay = new ManualRoiOverlay(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView guide = new TextView(this);
        guide.setText("ضع مربع 20×20 mm الحقيقي • اسحب النقاط الأربع إلى زواياه الداخلية • ثم LOCK");
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
        statusLabel.setText("EDIT 20×20 — طابق النقاط مع المربع الحقيقي");
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

        LinearLayout roiRow = new LinearLayout(this);
        roiRow.setOrientation(LinearLayout.HORIZONTAL);
        roiRow.setGravity(Gravity.CENTER);

        lockButton = new Button(this);
        lockButton.setText("LOCK 20×20");
        lockButton.setTextSize(10f);
        lockButton.setOnClickListener(v -> toggleRoiLock());

        Button resetArea = new Button(this);
        resetArea.setText("RESET AREA");
        resetArea.setTextSize(10f);
        resetArea.setOnClickListener(v -> {
            roiLocked = false;
            overlay.setLocked(false);
            overlay.resetArea();
            resetMeasurement();
            lockButton.setText("LOCK 20×20");
            statusLabel.setText("EDIT 20×20 — طابق النقاط مع المربع الحقيقي");
        });
        roiRow.addView(lockButton, weighted());
        roiRow.addView(resetArea, weighted());
        bottom.addView(roiRow, matchWrap());

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

    private void toggleRoiLock() {
        if (roiLocked) {
            roiLocked = false;
            overlay.setLocked(false);
            resetMeasurement();
            lockButton.setText("LOCK 20×20");
            statusLabel.setText("EDIT 20×20 — عدّل النقاط ثم اضغط LOCK");
            return;
        }
        float[] corners = overlay.snapshotCorners();
        if (!validQuad(corners)) {
            Toast.makeText(this, "رتّب النقاط الأربع حول المربع بدون تقاطع.", Toast.LENGTH_LONG).show();
            return;
        }
        roiLocked = true;
        overlay.setLocked(true);
        resetMeasurement();
        lockButton.setText("EDIT AREA");
        statusLabel.setText("✓ 20×20 LOCKED — العد داخل المربع الذي حددته فقط");
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
                    if (Math.abs(newZoom - currentZoomRatio) > 0.02f) {
                        if (roiLocked) {
                            roiLocked = false;
                            overlay.setLocked(false);
                            lockButton.setText("LOCK 20×20");
                            statusLabel.setText("Zoom تغيّر — طابق المربع مرة أخرى ثم LOCK");
                        }
                        resetMeasurement();
                    }
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
            if (!roiLocked) return;

            float[] viewCorners = overlay.snapshotCorners();
            if (!validQuad(viewCorners)) {
                runOnUiThread(() -> statusLabel.setText("منطقة 20×20 غير صحيحة — اضغط EDIT"));
                return;
            }

            Marker20mmDetector.Point[] sourceCorners = viewCornersToSource(viewCorners, image);
            if (sourceCorners == null) {
                runOnUiThread(() -> statusLabel.setText("المربع خارج صورة الكاميرا — اضغط EDIT"));
                return;
            }

            DirectionMeasurement x = measureDirection(image, sourceCorners, true);
            DirectionMeasurement y = measureDirection(image, sourceCorners, false);
            if (!x.ok && !y.ok) {
                runOnUiThread(() -> {
                    statusLabel.setText("الخيوط غير واضحة داخل المربع — اضغط على القماش للتركيز");
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

    private void acceptMeasurements(DirectionMeasurement x, DirectionMeasurement y,
                                    ThreadCountConsensus.Snapshot sx,
                                    ThreadCountConsensus.Snapshot sy) {
        float vx = sx.threadsPerCm > 0f ? sx.threadsPerCm : (x.ok ? x.frame.threadsPerCm : 0f);
        float vy = sy.threadsPerCm > 0f ? sy.threadsPerCm : (y.ok ? y.frame.threadsPerCm : 0f);
        int fullX = x.ok ? x.frame.currentFullLineCount : 0;
        int fullY = y.ok ? y.frame.currentFullLineCount : 0;

        boolean vibration = (x.ok && !sx.accepted && sx.reason != null && sx.reason.toLowerCase(Locale.US).contains("vibration"))
                || (y.ok && !sy.accepted && sy.reason != null && sy.reason.toLowerCase(Locale.US).contains("vibration"));
        boolean stableX = !x.ok || sx.stable;
        boolean stableY = !y.ok || sy.stable;
        boolean stable = !vibration && (vx > 0f || vy > 0f) && stableX && stableY;
        float confidence;
        if (sx.confidence > 0f && sy.confidence > 0f) confidence = Math.min(sx.confidence, sy.confidence);
        else confidence = Math.max(sx.confidence, sy.confidence);

        synchronized (measurementLock) {
            lastX = vx; lastY = vy; lastFullX = fullX; lastFullY = fullY;
            lastStable = stable; lastConfidence = confidence;
        }

        runOnUiThread(() -> {
            if (vibration) statusLabel.setText("HOLD — اهتزاز، ثبّت الهاتف والمربع داخل الإطار المحدد");
            else statusLabel.setText(stable ? "✓ 20×20 MANUAL ROI • القياس ثابت"
                    : "20×20 MANUAL ROI • جارٍ تثبيت القراءة...");
            resultLabel.setText(String.format(Locale.US,
                    "X %.1f n/cm (%d/20mm)  •  Y %.1f n/cm (%d/20mm)",
                    vx, fullX, vy, fullY));
            overlay.setResults(vx, vy, stable);
            captureButton.setEnabled(stable);
        });
    }

    private DirectionMeasurement measureDirection(ImageProxy image,
                                                  Marker20mmDetector.Point[] q,
                                                  boolean horizontal) {
        Homography h = Homography.fromQuad(q[0], q[1], q[2], q[3]);
        if (h == null) return DirectionMeasurement.fail();
        int sideSamples = estimateSamples(q, horizontal);
        ThreadProfileCounter.Result[] scans = new ThreadProfileCounter.Result[SCAN_LINES];
        for (int s = 0; s < SCAN_LINES; s++) {
            float fixed = (s + 1f) / (SCAN_LINES + 1f);
            float[] profile = new float[sideSamples];
            for (int i = 0; i < sideSamples; i++) {
                float t = i / (float) Math.max(1, sideSamples - 1);
                float u = horizontal ? t : fixed;
                float v = horizontal ? fixed : t;
                Marker20mmDetector.Point p = h.map(u, v);
                profile[i] = sampleLuma(image, p.x, p.y);
            }
            scans[s] = ThreadProfileCounter.analyze(profile, PHYSICAL_SIDE_CM);
        }
        ThreadCountConsensus.FrameResult frame = ThreadCountConsensus.fuse(scans, PHYSICAL_SIDE_CM);
        return frame.ok ? new DirectionMeasurement(true, frame) : DirectionMeasurement.fail();
    }

    private int estimateSamples(Marker20mmDetector.Point[] q, boolean horizontal) {
        float a = horizontal ? distance(q[0], q[1]) : distance(q[0], q[3]);
        float b = horizontal ? distance(q[3], q[2]) : distance(q[1], q[2]);
        return Math.max(180, Math.min(1200, Math.round((a + b) / 2f)));
    }

    private float sampleLuma(ImageProxy image, float x, float y) {
        int ix = Math.max(0, Math.min(image.getWidth() - 1, Math.round(x)));
        int iy = Math.max(0, Math.min(image.getHeight() - 1, Math.round(y)));
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        int index = iy * plane.getRowStride() + ix * plane.getPixelStride();
        ByteBuffer buffer = plane.getBuffer();
        return index >= 0 && index < buffer.limit() ? (buffer.get(index) & 0xFF) : 0f;
    }

    /** Convert the four user-selected preview points to ImageProxy source coordinates. */
    private Marker20mmDetector.Point[] viewCornersToSource(float[] corners, ImageProxy image) {
        if (corners == null || corners.length != 8 || previewView.getWidth() <= 0 || previewView.getHeight() <= 0) return null;
        int sw = image.getWidth(), sh = image.getHeight();
        int rotation = ((image.getImageInfo().getRotationDegrees() % 360) + 360) % 360;
        int rw = (rotation == 90 || rotation == 270) ? sh : sw;
        int rh = (rotation == 90 || rotation == 270) ? sw : sh;
        float scale = Math.min(previewView.getWidth() / (float) rw, previewView.getHeight() / (float) rh);
        float ox = (previewView.getWidth() - rw * scale) / 2f;
        float oy = (previewView.getHeight() - rh * scale) / 2f;
        Marker20mmDetector.Point[] out = new Marker20mmDetector.Point[4];

        for (int i = 0; i < 4; i++) {
            float rx = (corners[i * 2] - ox) / scale;
            float ry = (corners[i * 2 + 1] - oy) / scale;
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
            out[i] = new Marker20mmDetector.Point(sx, sy);
        }
        return out;
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
        if (imageCapture == null || !roiLocked) return;
        synchronized (measurementLock) {
            if (!lastStable) {
                Toast.makeText(this, "انتظر حتى تثبت القراءة داخل مربع 20×20 الذي حددته.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        captureButton.setEnabled(false);
        File output = new File(getCacheDir(), "meshcheck-manual20-" + System.currentTimeMillis() + ".jpg");
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
                Toast.makeText(NativeCameraActivity.this, "تعذر التقاط الصورة: " + exception.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else { setResult(RESULT_CANCELED); finish(); }
    }

    @Override protected void onDestroy() {
        analyzerExecutor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static float distance(Marker20mmDetector.Point a, Marker20mmDetector.Point b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static boolean validQuad(float[] q) {
        if (q == null || q.length != 8) return false;
        float minEdge = 45f;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            float dx = q[i * 2] - q[j * 2];
            float dy = q[i * 2 + 1] - q[j * 2 + 1];
            if (Math.sqrt(dx * dx + dy * dy) < minEdge) return false;
        }
        float sign = 0f;
        for (int i = 0; i < 4; i++) {
            int a = i, b = (i + 1) % 4, c = (i + 2) % 4;
            float abx = q[b * 2] - q[a * 2];
            float aby = q[b * 2 + 1] - q[a * 2 + 1];
            float bcx = q[c * 2] - q[b * 2];
            float bcy = q[c * 2 + 1] - q[b * 2 + 1];
            float cross = abx * bcy - aby * bcx;
            if (Math.abs(cross) < 20f) return false;
            if (sign == 0f) sign = Math.signum(cross);
            else if (Math.signum(cross) != sign) return false;
        }
        return true;
    }

    private static final class DirectionMeasurement {
        final boolean ok;
        final ThreadCountConsensus.FrameResult frame;
        DirectionMeasurement(boolean ok, ThreadCountConsensus.FrameResult frame) { this.ok = ok; this.frame = frame; }
        static DirectionMeasurement fail() { return new DirectionMeasurement(false, null); }
    }

    /** Homography from unit square to the four user-selected corners. */
    private static final class Homography {
        final double a11,a12,a13,a21,a22,a23,a31,a32;
        Homography(double a11,double a12,double a13,double a21,double a22,double a23,double a31,double a32) {
            this.a11=a11;this.a12=a12;this.a13=a13;this.a21=a21;this.a22=a22;this.a23=a23;this.a31=a31;this.a32=a32;
        }
        static Homography fromQuad(Marker20mmDetector.Point tl, Marker20mmDetector.Point tr,
                                   Marker20mmDetector.Point br, Marker20mmDetector.Point bl) {
            double x0=tl.x,y0=tl.y,x1=tr.x,y1=tr.y,x2=br.x,y2=br.y,x3=bl.x,y3=bl.y;
            double dx1=x1-x2, dx2=x3-x2, dx3=x0-x1+x2-x3;
            double dy1=y1-y2, dy2=y3-y2, dy3=y0-y1+y2-y3;
            double g=0,h=0;
            if (Math.abs(dx3)>1e-9 || Math.abs(dy3)>1e-9) {
                double det=dx1*dy2-dx2*dy1;
                if (Math.abs(det)<1e-9) return null;
                g=(dx3*dy2-dx2*dy3)/det;
                h=(dx1*dy3-dx3*dy1)/det;
            }
            return new Homography(x1-x0+g*x1, x3-x0+h*x3, x0,
                    y1-y0+g*y1, y3-y0+h*y3, y0, g, h);
        }
        Marker20mmDetector.Point map(double u,double v) {
            double d=a31*u+a32*v+1.0;
            return new Marker20mmDetector.Point((float)((a11*u+a12*v+a13)/d),
                    (float)((a21*u+a22*v+a23)/d));
        }
    }

    private static final class ManualRoiOverlay extends View {
        private final Paint editPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lockedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final float handleRadius;
        private final float[] corners = new float[8]; // TL, TR, BR, BL
        private boolean initialized;
        private boolean locked;
        private int activeCorner = -1;
        private boolean movingWhole;
        private float lastTouchX, lastTouchY;
        private float xDensity, yDensity;
        private boolean measurementStable;
        private float focusX=-1f, focusY=-1f;
        private long focusTime;

        ManualRoiOverlay(NativeCameraActivity context) {
            super(context);
            setWillNotDraw(false);
            density = getResources().getDisplayMetrics().density;
            handleRadius = 15f * density;
            editPaint.setColor(0xFFFFD54F);
            editPaint.setStyle(Paint.Style.STROKE);
            editPaint.setStrokeWidth(3f * density);
            lockedPaint.setColor(0xFF67E8D1);
            lockedPaint.setStyle(Paint.Style.STROKE);
            lockedPaint.setStrokeWidth(4f * density);
            handlePaint.setColor(0xFFFFD54F);
            handlePaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(12f * density);
            textPaint.setFakeBoldText(true);
            focusPaint.setColor(0xFF67E8D1);
            focusPaint.setStyle(Paint.Style.STROKE);
            focusPaint.setStrokeWidth(2f * density);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            if (!initialized && w > 0 && h > 0) {
                initialized = true;
                resetArea();
            }
        }

        void resetArea() {
            if (getWidth() <= 0 || getHeight() <= 0) return;
            float side = Math.min(getWidth() * 0.52f, getHeight() * 0.30f);
            float cx = getWidth() / 2f;
            float cy = getHeight() * 0.49f;
            corners[0] = cx - side/2f; corners[1] = cy - side/2f;
            corners[2] = cx + side/2f; corners[3] = cy - side/2f;
            corners[4] = cx + side/2f; corners[5] = cy + side/2f;
            corners[6] = cx - side/2f; corners[7] = cy + side/2f;
            locked = false;
            xDensity = yDensity = 0f;
            measurementStable = false;
            invalidate();
        }

        void setLocked(boolean value) {
            locked = value;
            activeCorner = -1;
            movingWhole = false;
            invalidate();
        }

        float[] snapshotCorners() {
            synchronized (corners) { return corners.clone(); }
        }

        void setResults(float x, float y, boolean stable) {
            xDensity = x;
            yDensity = y;
            measurementStable = stable;
            invalidate();
        }

        void showFocus(float x, float y) {
            focusX=x; focusY=y; focusTime=System.currentTimeMillis(); invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (locked) return false;
            float x = event.getX(), y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    activeCorner = nearestCorner(x, y);
                    if (activeCorner >= 0) {
                        lastTouchX = x; lastTouchY = y;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    if (pointInQuad(x, y)) {
                        movingWhole = true;
                        lastTouchX = x; lastTouchY = y;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (activeCorner >= 0) {
                        synchronized (corners) {
                            corners[activeCorner*2] = clamp(x, 8f*density, getWidth()-8f*density);
                            corners[activeCorner*2+1] = clamp(y, 70f*density, getHeight()-150f*density);
                        }
                        invalidate();
                        return true;
                    }
                    if (movingWhole) {
                        float dx = x-lastTouchX, dy = y-lastTouchY;
                        translateWhole(dx, dy);
                        lastTouchX=x; lastTouchY=y;
                        invalidate();
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean consumed = activeCorner >= 0 || movingWhole;
                    activeCorner = -1;
                    movingWhole = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return consumed;
                default:
                    return false;
            }
        }

        private int nearestCorner(float x, float y) {
            int best=-1;
            float bestD=handleRadius*2.2f;
            synchronized (corners) {
                for (int i=0;i<4;i++) {
                    float dx=x-corners[i*2], dy=y-corners[i*2+1];
                    float d=(float)Math.sqrt(dx*dx+dy*dy);
                    if (d<bestD) { bestD=d; best=i; }
                }
            }
            return best;
        }

        private boolean pointInQuad(float x, float y) {
            float[] q=snapshotCorners();
            boolean inside=false;
            for(int i=0,j=3;i<4;j=i++) {
                float xi=q[i*2], yi=q[i*2+1], xj=q[j*2], yj=q[j*2+1];
                boolean intersect=((yi>y)!=(yj>y)) && (x < (xj-xi)*(y-yi)/(yj-yi+0.0001f)+xi);
                if(intersect) inside=!inside;
            }
            return inside;
        }

        private void translateWhole(float dx, float dy) {
            synchronized (corners) {
                float minX=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,minY=Float.MAX_VALUE,maxY=-Float.MAX_VALUE;
                for(int i=0;i<4;i++){minX=Math.min(minX,corners[i*2]);maxX=Math.max(maxX,corners[i*2]);minY=Math.min(minY,corners[i*2+1]);maxY=Math.max(maxY,corners[i*2+1]);}
                if(minX+dx<8f*density) dx=8f*density-minX;
                if(maxX+dx>getWidth()-8f*density) dx=getWidth()-8f*density-maxX;
                if(minY+dy<70f*density) dy=70f*density-minY;
                if(maxY+dy>getHeight()-150f*density) dy=getHeight()-150f*density-maxY;
                for(int i=0;i<4;i++){corners[i*2]+=dx;corners[i*2+1]+=dy;}
            }
        }

        private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float[] q=snapshotCorners();
            Paint border = locked ? lockedPaint : editPaint;
            Path p=new Path();
            p.moveTo(q[0],q[1]); p.lineTo(q[2],q[3]); p.lineTo(q[4],q[5]); p.lineTo(q[6],q[7]); p.close();
            canvas.drawPath(p,border);

            if (!locked) {
                String[] labels={"1","2","3","4"};
                for(int i=0;i<4;i++){
                    canvas.drawCircle(q[i*2],q[i*2+1],handleRadius,handlePaint);
                    float tw=textPaint.measureText(labels[i]);
                    canvas.drawText(labels[i],q[i*2]-tw/2f,q[i*2+1]+5f*density,textPaint);
                }
                canvas.drawText("اسحب الزوايا الداخلية للمربع 20×20", Math.max(12f,q[0]), Math.max(85f,q[1]-18f*density), textPaint);
            } else {
                String title="20×20 mm MANUAL ROI — لا يوجد اكتشاف تلقائي";
                canvas.drawText(title,Math.max(12f,q[0]),Math.max(85f,q[1]-15f*density),textPaint);
                if(xDensity>0f||yDensity>0f){
                    String r=String.format(Locale.US,"X %.1f • Y %.1f n/cm • %s",xDensity,yDensity,measurementStable?"STABLE":"MEASURING");
                    canvas.drawText(r,Math.max(12f,q[6]),Math.min(getHeight()-155f,q[7]+28f*density),textPaint);
                }
            }

            if(focusX>=0&&System.currentTimeMillis()-focusTime<1200){
                canvas.drawCircle(focusX,focusY,28f*density,focusPaint);
                postInvalidateOnAnimation();
            }
        }
    }
}
