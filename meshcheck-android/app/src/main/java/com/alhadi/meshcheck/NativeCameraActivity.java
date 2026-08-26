package com.alhadi.meshcheck;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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

    private static final int CAMERA_PERMISSION_REQUEST = 2201;
    private static final String PREFS = "meshcheck.camera.calibration";
    private static final String PREF_FIXED_DISTANCE_CM = "fixed_distance_cm";
    private static final String PREF_RULER_BASE_PX = "ruler_base_px_1x";
    private static final String PREF_FIXED_CALIBRATED = "fixed_calibrated";
    private static final String PREF_CALIBRATION_LOCKED = "calibration_locked";
    private static final int SCAN_LINES = 9;

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Camera camera;
    private Button captureButton;
    private Button flashButton;
    private Button calibrationButton;
    private TextView zoomLabel;
    private TextView calibrationLabel;
    private TextView threadCountLabel;
    private LinearLayout calibrationPanel;
    private RulerOverlayView rulerOverlayView;
    private ScaleGestureDetector scaleGestureDetector;
    private SharedPreferences preferences;
    private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();
    private final ThreadCountConsensus.Stabilizer stabilizer = new ThreadCountConsensus.Stabilizer(12, 6);

    private boolean torchOn;
    private boolean zoomGestureUsed;
    private volatile boolean calibrationMode;
    private volatile boolean fixedCalibrated;
    private volatile boolean calibrationLocked;
    private float fixedDistanceCm = 10f;
    private float pendingDistanceCm = 10f;
    private float currentZoomRatio = 1f;
    private float previousZoomRatio = 1f;
    private volatile int analysisFailures;

    private final Object measurementLock = new Object();
    private float lastThreadCountPerCm;
    private int lastFullLineCount;
    private float lastThreadConfidence;
    private boolean lastThreadStable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(18, 59, 80));

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        fixedDistanceCm = preferences.getFloat(PREF_FIXED_DISTANCE_CM, 10f);
        pendingDistanceCm = fixedDistanceCm;
        fixedCalibrated = preferences.getBoolean(PREF_FIXED_CALIBRATED, false);
        calibrationLocked = preferences.getBoolean(PREF_CALIBRATION_LOCKED, fixedCalibrated);

        buildUi();
        float savedBase = preferences.getFloat(PREF_RULER_BASE_PX, -1f);
        if (savedBase > 0f) rulerOverlayView.setBaseRulerPixelsAt1x(savedBase);
        rulerOverlayView.setFixedDistance(fixedDistanceCm, fixedCalibrated);
        updateCalibrationUi();
        updateThreadUiWaiting();

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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        rulerOverlayView = new RulerOverlayView(this);
        root.addView(rulerOverlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView topGuide = new TextView(this);
        topGuide.setText("ضع خيوط المنخل عمودية على نافذة 1 cm • 9 مسارات عد تعمل تلقائيًا");
        topGuide.setTextColor(Color.WHITE);
        topGuide.setTextSize(13f);
        topGuide.setGravity(Gravity.CENTER);
        topGuide.setPadding(dp(10), dp(8), dp(10), dp(8));
        topGuide.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams guideParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        guideParams.setMargins(dp(8), dp(10), dp(8), 0);
        root.addView(topGuide, guideParams);

        LinearLayout bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setGravity(Gravity.CENTER);
        bottomPanel.setPadding(dp(8), dp(6), dp(8), dp(10));
        bottomPanel.setBackgroundColor(0xB3071318);

        LinearLayout calibrationRow = new LinearLayout(this);
        calibrationRow.setOrientation(LinearLayout.HORIZONTAL);
        calibrationRow.setGravity(Gravity.CENTER_VERTICAL);

        calibrationLabel = new TextView(this);
        calibrationLabel.setTextColor(Color.WHITE);
        calibrationLabel.setTextSize(12f);
        calibrationLabel.setGravity(Gravity.CENTER_VERTICAL);
        calibrationLabel.setPadding(dp(6), 0, dp(6), 0);

        calibrationButton = new Button(this);
        calibrationButton.setTextSize(11f);
        calibrationButton.setOnClickListener(v -> onCalibrationButton());

        calibrationRow.addView(calibrationLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f));
        calibrationRow.addView(calibrationButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottomPanel.addView(calibrationRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        threadCountLabel = new TextView(this);
        threadCountLabel.setTextColor(Color.WHITE);
        threadCountLabel.setTextSize(13f);
        threadCountLabel.setGravity(Gravity.CENTER);
        threadCountLabel.setPadding(dp(6), dp(4), dp(6), dp(4));
        bottomPanel.addView(threadCountLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        calibrationPanel = new LinearLayout(this);
        calibrationPanel.setOrientation(LinearLayout.HORIZONTAL);
        calibrationPanel.setGravity(Gravity.CENTER);
        calibrationPanel.setVisibility(View.GONE);
        calibrationPanel.setPadding(0, dp(4), 0, dp(4));

        Button rulerMinus = new Button(this);
        rulerMinus.setText("− 0.5%");
        rulerMinus.setTextSize(10f);
        rulerMinus.setOnClickListener(v -> adjustCalibrationRuler(0.995f));

        Button saveCalibration = new Button(this);
        saveCalibration.setText("حفظ وقفل 1 cm");
        saveCalibration.setTextSize(10f);
        saveCalibration.setOnClickListener(v -> saveOneTimeCalibration());

        Button rulerPlus = new Button(this);
        rulerPlus.setText("+ 0.5%");
        rulerPlus.setTextSize(10f);
        rulerPlus.setOnClickListener(v -> adjustCalibrationRuler(1.005f));

        calibrationPanel.addView(rulerMinus, weightedButtonParams());
        calibrationPanel.addView(saveCalibration, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f));
        calibrationPanel.addView(rulerPlus, weightedButtonParams());
        bottomPanel.addView(calibrationPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER);
        zoomRow.addView(makeZoomButton("1×", 1f), weightedButtonParams());
        zoomRow.addView(makeZoomButton("2×", 2f), weightedButtonParams());
        zoomRow.addView(makeZoomButton("3×", 3f), weightedButtonParams());
        zoomRow.addView(makeZoomButton("5×", 5f), weightedButtonParams());

        zoomLabel = new TextView(this);
        zoomLabel.setText("1.0×");
        zoomLabel.setTextColor(Color.WHITE);
        zoomLabel.setTextSize(11f);
        zoomLabel.setGravity(Gravity.CENTER);
        zoomRow.addView(zoomLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f));
        bottomPanel.addView(zoomRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(4), 0, 0);

        captureButton = new Button(this);
        captureButton.setText("التقاط وتحليل");
        captureButton.setEnabled(false);
        captureButton.setOnClickListener(v -> capturePhoto());

        flashButton = new Button(this);
        flashButton.setText("FLASH");
        flashButton.setEnabled(false);
        flashButton.setOnClickListener(v -> toggleTorch());

        Button cancelButton = new Button(this);
        cancelButton.setText("رجوع");
        cancelButton.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        controls.addView(captureButton, weightedButtonParams());
        controls.addView(flashButton, weightedButtonParams());
        controls.addView(cancelButton, weightedButtonParams());
        bottomPanel.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(bottomPanel, bottomParams);

        setupTouchControls();
        setContentView(root);
    }

    private void setupTouchControls() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                zoomGestureUsed = true;
                return true;
            }

            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                if (camera == null || calibrationMode) return false;
                ZoomState state = camera.getCameraInfo().getZoomState().getValue();
                if (state == null) return false;
                setZoomRatio(state.getZoomRatio() * detector.getScaleFactor());
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

    private Button makeZoomButton(String label, float ratio) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(10f);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setOnClickListener(v -> {
            if (!calibrationMode) setZoomRatio(ratio);
        });
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .build();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetResolution(new Size(2560, 1440))
                        .build();
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(analyzerExecutor, this::analyzeFrame);

                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture, imageAnalysis);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                captureButton.setEnabled(true);
                flashButton.setEnabled(camera.getCameraInfo().hasFlashUnit());

                camera.getCameraInfo().getZoomState().observe(this, state -> {
                    if (state == null) return;
                    currentZoomRatio = state.getZoomRatio();
                    zoomLabel.setText(String.format(Locale.US, "%.1f×\nmax %.1f", currentZoomRatio, state.getMaxZoomRatio()));
                    rulerOverlayView.setZoomRatio(currentZoomRatio);
                    if (Math.abs(currentZoomRatio - previousZoomRatio) > 0.08f) {
                        previousZoomRatio = currentZoomRatio;
                        resetMeasurement();
                    }
                });

                if (!fixedCalibrated) {
                    runOnUiThread(() -> showDistanceDialog(false));
                }
            } catch (ExecutionException exception) {
                Toast.makeText(this, "تعذر تشغيل الكاميرا: " + exception.getMessage(), Toast.LENGTH_LONG).show();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                Toast.makeText(this, "تمت مقاطعة تشغيل الكاميرا.", Toast.LENGTH_LONG).show();
            } catch (Exception exception) {
                Toast.makeText(this, "تعذر تشغيل الكاميرا الخلفية: " + exception.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        try {
            if (!fixedCalibrated || calibrationMode) {
                postWaiting(calibrationMode ? "المعايرة جارية..." : "أكمل معايرة 1 cm مرة واحدة.");
                return;
            }

            RulerGeometry geometry = rulerOverlayView.snapshotGeometry();
            if (!geometry.valid || !geometry.fits) {
                postFailure("نافذة 1 cm خارج مجال الصورة — خفّض Zoom.");
                return;
            }

            ThreadProfileCounter.Result[] scans = buildAndAnalyzeScanLines(image, geometry);
            if (scans == null) {
                postFailure("تعذر قراءة نافذة 1 cm من الصورة.");
                return;
            }

            ThreadCountConsensus.FrameResult frame = ThreadCountConsensus.fuse(scans);
            if (!frame.ok) {
                postFailure(frame.reason);
                return;
            }

            acceptFrame(frame);
        } catch (Exception exception) {
            postFailure("تعذر عد الخيوط: " + exception.getMessage());
        } finally {
            image.close();
        }
    }

    private ThreadProfileCounter.Result[] buildAndAnalyzeScanLines(ImageProxy image, RulerGeometry geometry) {
        if (geometry.viewWidth <= 0 || geometry.viewHeight <= 0) return null;
        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        int rotation = ((image.getImageInfo().getRotationDegrees() % 360) + 360) % 360;
        int rotatedWidth = (rotation == 90 || rotation == 270) ? sourceHeight : sourceWidth;
        int rotatedHeight = (rotation == 90 || rotation == 270) ? sourceWidth : sourceHeight;

        float scale = Math.min(geometry.viewWidth / (float) rotatedWidth,
                geometry.viewHeight / (float) rotatedHeight);
        if (!(scale > 0f)) return null;
        float displayedWidth = rotatedWidth * scale;
        float displayedHeight = rotatedHeight * scale;
        float offsetX = (geometry.viewWidth - displayedWidth) / 2f;
        float offsetY = (geometry.viewHeight - displayedHeight) / 2f;

        float rotatedLeft = (geometry.left - offsetX) / scale;
        float rotatedRight = (geometry.right - offsetX) / scale;
        float rotatedCenterY = (geometry.y - offsetY) / scale;
        if (rotatedLeft < 0 || rotatedRight >= rotatedWidth || rotatedCenterY < 0 || rotatedCenterY >= rotatedHeight) {
            return null;
        }

        int samples = Math.max(1, Math.round(rotatedRight - rotatedLeft) + 1);
        if (samples < 60) return null;

        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ByteBuffer buffer = yPlane.getBuffer();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();

        float scanSpacing = Math.max(2f, Math.min(6f, samples / 160f));
        ThreadProfileCounter.Result[] results = new ThreadProfileCounter.Result[SCAN_LINES];
        int middle = SCAN_LINES / 2;

        for (int scan = 0; scan < SCAN_LINES; scan++) {
            float ry = rotatedCenterY + (scan - middle) * scanSpacing;
            float[] profile = new float[samples];
            for (int i = 0; i < samples; i++) {
                float rx = rotatedLeft + (rotatedRight - rotatedLeft) * i / Math.max(1f, samples - 1f);
                float sum = 0f;
                int count = 0;
                for (int local = -1; local <= 1; local++) {
                    int[] source = rotatedToSource(rx, ry + local, sourceWidth, sourceHeight, rotation);
                    int index = source[1] * rowStride + source[0] * pixelStride;
                    if (index >= 0 && index < buffer.limit()) {
                        sum += buffer.get(index) & 0xFF;
                        count++;
                    }
                }
                profile[i] = count > 0 ? sum / count : 0f;
            }
            results[scan] = ThreadProfileCounter.analyze(profile);
        }
        return results;
    }

    private static int[] rotatedToSource(float rx, float ry, int width, int height, int rotation) {
        int sx;
        int sy;
        switch (rotation) {
            case 90:
                sx = Math.round(ry);
                sy = height - 1 - Math.round(rx);
                break;
            case 180:
                sx = width - 1 - Math.round(rx);
                sy = height - 1 - Math.round(ry);
                break;
            case 270:
                sx = width - 1 - Math.round(ry);
                sy = Math.round(rx);
                break;
            default:
                sx = Math.round(rx);
                sy = Math.round(ry);
                break;
        }
        sx = Math.max(0, Math.min(width - 1, sx));
        sy = Math.max(0, Math.min(height - 1, sy));
        return new int[]{sx, sy};
    }

    private void acceptFrame(ThreadCountConsensus.FrameResult frame) {
        analysisFailures = 0;
        ThreadCountConsensus.Snapshot snapshot = stabilizer.push(frame);

        synchronized (measurementLock) {
            lastFullLineCount = frame.currentFullLineCount;
            lastThreadCountPerCm = snapshot.threadsPerCm > 0f ? snapshot.threadsPerCm : frame.threadsPerCm;
            lastThreadConfidence = snapshot.confidence > 0f ? snapshot.confidence : frame.confidence;
            lastThreadStable = snapshot.stable;
        }

        runOnUiThread(() -> {
            String state = snapshot.stable ? "✓ ثابت" : "جارٍ التثبيت " + snapshot.samples + "/6";
            threadCountLabel.setText(String.format(Locale.US,
                    "1 cm: %d خطوط كاملة • %.1f خيط/سم • %s • %d/%d مسارات",
                    frame.currentFullLineCount,
                    lastThreadCountPerCm,
                    state,
                    frame.validScans,
                    frame.totalScans));
            rulerOverlayView.setThreadMeasurement(
                    frame.currentFullLineCount,
                    lastThreadCountPerCm,
                    lastThreadConfidence,
                    snapshot.stable,
                    frame.centersNormalized,
                    "");
        });
    }

    private void postFailure(String reason) {
        analysisFailures++;
        if (analysisFailures >= 5) resetMeasurement();
        runOnUiThread(() -> {
            threadCountLabel.setText("عدد الخيوط: " + reason);
            rulerOverlayView.setThreadMeasurement(0, 0f, 0f, false, new float[0], reason);
        });
    }

    private void postWaiting(String reason) {
        runOnUiThread(() -> {
            threadCountLabel.setText("عدد الخيوط: " + reason);
            rulerOverlayView.setThreadMeasurement(0, 0f, 0f, false, new float[0], reason);
        });
    }

    private void updateThreadUiWaiting() {
        if (!fixedCalibrated) {
            threadCountLabel.setText("عدد الخيوط: عاير 1 cm مرة واحدة فقط.");
        } else {
            threadCountLabel.setText("عدد الخيوط: جارٍ دمج 9 مسارات داخل 1 cm...");
        }
    }

    private void resetMeasurement() {
        stabilizer.reset();
        synchronized (measurementLock) {
            lastThreadCountPerCm = 0f;
            lastFullLineCount = 0;
            lastThreadConfidence = 0f;
            lastThreadStable = false;
        }
        runOnUiThread(() -> {
            rulerOverlayView.setThreadMeasurement(0, 0f, 0f, false, new float[0], "");
            updateThreadUiWaiting();
        });
    }

    private void onCalibrationButton() {
        if (!fixedCalibrated || !calibrationLocked) {
            showDistanceDialog(false);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Reset calibration")
                .setMessage("المعايرة محفوظة ومقفلة. أعد ضبطها فقط إذا تغيّر الهاتف أو طريقة تثبيت المسافة.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("Reset", (dialog, which) -> resetCalibration())
                .show();
    }

    private void resetCalibration() {
        fixedCalibrated = false;
        calibrationLocked = false;
        calibrationMode = false;
        preferences.edit()
                .remove(PREF_FIXED_DISTANCE_CM)
                .remove(PREF_RULER_BASE_PX)
                .putBoolean(PREF_FIXED_CALIBRATED, false)
                .putBoolean(PREF_CALIBRATION_LOCKED, false)
                .apply();
        rulerOverlayView.resetBaseRulerToDisplayDefault();
        rulerOverlayView.setFixedDistance(10f, false);
        fixedDistanceCm = 10f;
        pendingDistanceCm = 10f;
        calibrationPanel.setVisibility(View.GONE);
        resetMeasurement();
        updateCalibrationUi();
        showDistanceDialog(false);
    }

    private void showDistanceDialog(boolean forcedReset) {
        if (calibrationLocked && fixedCalibrated && !forcedReset) return;

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(Locale.US, "%.1f", fixedDistanceCm));
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("المعايرة مرة واحدة")
                .setMessage("ثبت الهاتف على المسافة التي ستستخدمها دائمًا. ضع مرجعًا حقيقيًا 1 cm في نفس مستوى المنخل. بعد الحفظ لن يطلب التطبيق المعايرة مرة أخرى.")
                .setView(input)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("ابدأ", (dialog, which) -> {
                    try {
                        float value = Float.parseFloat(input.getText().toString().trim());
                        if (!(value >= 2f && value <= 100f)) throw new NumberFormatException();
                        beginCalibration(value);
                    } catch (NumberFormatException exception) {
                        Toast.makeText(this, "أدخل مسافة صحيحة بين 2 و100 cm.", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void beginCalibration(float distanceCm) {
        pendingDistanceCm = distanceCm;
        calibrationMode = true;
        calibrationLocked = false;
        resetMeasurement();
        setZoomRatio(1f);
        calibrationPanel.setVisibility(View.VISIBLE);
        rulerOverlayView.setCalibrationMode(true);
        rulerOverlayView.setFixedDistance(pendingDistanceCm, false);
        calibrationLabel.setText(String.format(Locale.US,
                "طابق الخط بدقة مع مرجع حقيقي 1 cm • %.1f cm • Zoom 1×",
                pendingDistanceCm));
        calibrationButton.setText("إلغاء");
        calibrationButton.setOnClickListener(v -> cancelCalibration());
    }

    private void cancelCalibration() {
        calibrationMode = false;
        calibrationPanel.setVisibility(View.GONE);
        rulerOverlayView.setCalibrationMode(false);
        rulerOverlayView.setFixedDistance(fixedDistanceCm, fixedCalibrated);
        calibrationButton.setOnClickListener(v -> onCalibrationButton());
        updateCalibrationUi();
        resetMeasurement();
    }

    private void adjustCalibrationRuler(float factor) {
        if (!calibrationMode) return;
        rulerOverlayView.adjustBaseRuler(factor);
    }

    private void saveOneTimeCalibration() {
        if (!calibrationMode) return;
        fixedDistanceCm = pendingDistanceCm;
        fixedCalibrated = true;
        calibrationLocked = true;
        preferences.edit()
                .putFloat(PREF_FIXED_DISTANCE_CM, fixedDistanceCm)
                .putFloat(PREF_RULER_BASE_PX, rulerOverlayView.getBaseRulerPixelsAt1x())
                .putBoolean(PREF_FIXED_CALIBRATED, true)
                .putBoolean(PREF_CALIBRATION_LOCKED, true)
                .apply();

        calibrationMode = false;
        calibrationPanel.setVisibility(View.GONE);
        rulerOverlayView.setCalibrationMode(false);
        rulerOverlayView.setFixedDistance(fixedDistanceCm, true);
        calibrationButton.setOnClickListener(v -> onCalibrationButton());
        updateCalibrationUi();
        resetMeasurement();
        Toast.makeText(this, "تم حفظ وقفل معايرة 1 cm. لن تُطلب مرة أخرى.", Toast.LENGTH_LONG).show();
    }

    private void updateCalibrationUi() {
        if (fixedCalibrated && calibrationLocked) {
            calibrationLabel.setText(String.format(Locale.US,
                    "✓ Calibration LOCKED • 1 cm • Fixed %.1f cm",
                    fixedDistanceCm));
            calibrationButton.setText("Reset");
        } else {
            calibrationLabel.setText("المعايرة غير محفوظة — مطلوبة مرة واحدة فقط");
            calibrationButton.setText("معايرة");
        }
    }

    private void setZoomRatio(float requestedRatio) {
        if (camera == null) return;
        ZoomState state = camera.getCameraInfo().getZoomState().getValue();
        if (state == null) return;
        float ratio = Math.max(state.getMinZoomRatio(), Math.min(state.getMaxZoomRatio(), requestedRatio));
        camera.getCameraControl().setZoomRatio(ratio);
    }

    private void focusAt(float x, float y) {
        if (camera == null) return;
        try {
            MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(4, TimeUnit.SECONDS)
                    .build();
            camera.getCameraControl().startFocusAndMetering(action);
            rulerOverlayView.showFocusMarker(x, y);
        } catch (Exception ignored) {
        }
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        flashButton.setText(torchOn ? "FLASH ON" : "FLASH");
    }

    private void capturePhoto() {
        if (imageCapture == null) return;
        captureButton.setEnabled(false);
        File output = new File(getCacheDir(), "meshcheck-native-" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(output).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Intent result = new Intent();
                result.putExtra(EXTRA_CAPTURE_PATH, output.getAbsolutePath());
                result.putExtra(EXTRA_ZOOM_RATIO, currentZoomRatio);
                result.putExtra(EXTRA_FIXED_DISTANCE_CM, fixedDistanceCm);
                result.putExtra(EXTRA_FIXED_CALIBRATED, fixedCalibrated);
                result.putExtra(EXTRA_RULER_BASE_PX_1X, rulerOverlayView.getBaseRulerPixelsAt1x());
                synchronized (measurementLock) {
                    result.putExtra(EXTRA_THREAD_COUNT_CM, lastThreadCountPerCm);
                    result.putExtra(EXTRA_FULL_LINE_COUNT, lastFullLineCount);
                    result.putExtra(EXTRA_THREAD_COUNT_CONFIDENCE, lastThreadConfidence);
                    result.putExtra(EXTRA_THREAD_COUNT_STABLE, lastThreadStable);
                }
                setResult(RESULT_OK, result);
                finish();
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                captureButton.setEnabled(true);
                Toast.makeText(NativeCameraActivity.this, "تعذر التقاط الصورة: " + exception.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "اسمح باستخدام الكاميرا من إعدادات التطبيق ثم جرّب مرة أخرى.", Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        analyzerExecutor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class RulerGeometry {
        final float left;
        final float right;
        final float y;
        final int viewWidth;
        final int viewHeight;
        final boolean fits;
        final boolean valid;

        RulerGeometry(float left, float right, float y, int viewWidth, int viewHeight, boolean fits) {
            this.left = left;
            this.right = right;
            this.y = y;
            this.viewWidth = viewWidth;
            this.viewHeight = viewHeight;
            this.fits = fits;
            this.valid = viewWidth > 0 && viewHeight > 0 && right > left;
        }
    }

    private static final class RulerOverlayView extends View {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stablePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final float displayDefaultRulerPixelsAt1x;

        private volatile float baseRulerPixelsAt1x;
        private volatile float zoomRatio = 1f;
        private volatile float fixedDistanceCm = 10f;
        private volatile boolean fixedCalibrated;
        private volatile boolean calibrationMode;
        private volatile int measuredFullLines;
        private volatile float measuredThreadsPerCm;
        private volatile float measuredConfidence;
        private volatile boolean measuredStable;
        private volatile float[] measuredCenters = new float[0];
        private volatile String measurementStatus = "";
        private volatile int viewWidth;
        private volatile int viewHeight;
        private float focusX = -1f;
        private float focusY = -1f;
        private long focusTime;

        RulerOverlayView(NativeCameraActivity context) {
            super(context);
            setWillNotDraw(false);
            setClickable(false);
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            density = metrics.density;
            float xdpi = metrics.xdpi;
            if (!(xdpi >= 100f && xdpi <= 1000f)) xdpi = metrics.densityDpi;
            displayDefaultRulerPixelsAt1x = xdpi / 2.54f;
            baseRulerPixelsAt1x = displayDefaultRulerPixelsAt1x;

            shadowPaint.setColor(0xDD000000);
            shadowPaint.setStrokeWidth(5f * density);
            linePaint.setColor(Color.WHITE);
            linePaint.setStrokeWidth(2f * density);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(11.5f * density);
            textPaint.setFakeBoldText(true);
            panelPaint.setColor(0x76000000);
            focusPaint.setColor(0xFF67E8D1);
            focusPaint.setStyle(Paint.Style.STROKE);
            focusPaint.setStrokeWidth(2f * density);
            markerPaint.setColor(0xFFFFD54F);
            markerPaint.setStrokeWidth(2.5f * density);
            stablePaint.setColor(0xFF67E8D1);
            stablePaint.setStrokeWidth(3f * density);
            scanPaint.setColor(0x5573D7FF);
            scanPaint.setStrokeWidth(1f * density);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            viewWidth = w;
            viewHeight = h;
        }

        void setBaseRulerPixelsAt1x(float pixels) {
            if (pixels > 12f) {
                baseRulerPixelsAt1x = pixels;
                invalidate();
            }
        }

        void resetBaseRulerToDisplayDefault() {
            baseRulerPixelsAt1x = displayDefaultRulerPixelsAt1x;
            invalidate();
        }

        float getBaseRulerPixelsAt1x() {
            return baseRulerPixelsAt1x;
        }

        void adjustBaseRuler(float factor) {
            baseRulerPixelsAt1x = Math.max(20f, Math.min(3000f, baseRulerPixelsAt1x * factor));
            invalidate();
        }

        void setZoomRatio(float ratio) {
            zoomRatio = Math.max(1f, ratio);
            invalidate();
        }

        void setFixedDistance(float distanceCm, boolean calibrated) {
            fixedDistanceCm = distanceCm;
            fixedCalibrated = calibrated;
            invalidate();
        }

        void setCalibrationMode(boolean enabled) {
            calibrationMode = enabled;
            invalidate();
        }

        void setThreadMeasurement(int fullLines, float threadsPerCm, float confidence,
                                  boolean stable, float[] centersNormalized, String status) {
            measuredFullLines = fullLines;
            measuredThreadsPerCm = threadsPerCm;
            measuredConfidence = confidence;
            measuredStable = stable;
            measuredCenters = centersNormalized == null ? new float[0] : centersNormalized.clone();
            measurementStatus = status == null ? "" : status;
            invalidate();
        }

        RulerGeometry snapshotGeometry() {
            int w = viewWidth;
            int h = viewHeight;
            float rulerLength = baseRulerPixelsAt1x * zoomRatio;
            float cx = w / 2f;
            float cy = h / 2f;
            float left = cx - rulerLength / 2f;
            float right = cx + rulerLength / 2f;
            float y = cy - 38f * density;
            boolean fits = left >= 8f * density && right <= w - 8f * density;
            return new RulerGeometry(left, right, y, w, h, fits);
        }

        void showFocusMarker(float x, float y) {
            focusX = x;
            focusY = y;
            focusTime = System.currentTimeMillis();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            RulerGeometry geometry = snapshotGeometry();
            float cx = geometry.viewWidth / 2f;
            float cy = geometry.viewHeight / 2f;
            float rulerLength = geometry.right - geometry.left;
            float y = geometry.y;

            float panelLeft = Math.max(6f * density, geometry.left - 14f * density);
            float panelRight = Math.min(getWidth() - 6f * density, geometry.right + 14f * density);
            RectF panel = new RectF(panelLeft, y - 42f * density, panelRight, y + 73f * density);
            canvas.drawRoundRect(panel, 12f * density, 12f * density, panelPaint);

            for (int scan = 0; scan < SCAN_LINES; scan++) {
                float offset = (scan - SCAN_LINES / 2f) * 2.2f * density;
                canvas.drawLine(geometry.left, y + offset, geometry.right, y + offset, scanPaint);
            }

            canvas.drawLine(geometry.left, y, geometry.right, y, shadowPaint);
            canvas.drawLine(geometry.left, y, geometry.right, y, linePaint);
            for (int index = 0; index <= 10; index++) {
                float x = geometry.left + rulerLength * index / 10f;
                float tick = (index == 0 || index == 10) ? 13f * density : (index == 5 ? 10f * density : 7f * density);
                canvas.drawLine(x, y - tick, x, y + tick, shadowPaint);
                canvas.drawLine(x, y - tick, x, y + tick, linePaint);
            }

            for (float normalized : measuredCenters) {
                if (normalized < 0f || normalized > 1f) continue;
                float x = geometry.left + rulerLength * normalized;
                canvas.drawLine(x, y - 18f * density, x, y + 18f * density, markerPaint);
                canvas.drawCircle(x, y, 3.2f * density, markerPaint);
            }

            String label;
            if (calibrationMode) {
                label = String.format(Locale.US, "CALIBRATE REAL 1 cm • %.1f cm • LOCK AFTER SAVE", fixedDistanceCm);
            } else if (fixedCalibrated) {
                label = String.format(Locale.US, "1 cm COUNT WINDOW • %.1f× • 9 SCANS", zoomRatio);
            } else {
                label = "1 cm GUIDE • CALIBRATION REQUIRED ONCE";
            }
            if (!geometry.fits) label = "1 cm أكبر من الشاشة — خفّض Zoom";
            float labelWidth = textPaint.measureText(label);
            canvas.drawText(label, Math.max(8f * density, cx - labelWidth / 2f), y - 24f * density, textPaint);

            String countLine;
            if (measuredFullLines > 0) {
                countLine = String.format(Locale.US, "%d خطوط كاملة • %.1f /cm • %s",
                        measuredFullLines, measuredThreadsPerCm, measuredStable ? "STABLE" : "MEASURING");
            } else if (!measurementStatus.isEmpty()) {
                countLine = measurementStatus;
            } else {
                countLine = "يتم دمج 9 مسارات عد داخل 1 cm";
            }
            float countWidth = textPaint.measureText(countLine);
            canvas.drawText(countLine, Math.max(8f * density, cx - countWidth / 2f), y + 50f * density, textPaint);
            if (measuredStable && measuredConfidence > 0.42f) {
                canvas.drawLine(geometry.left, y + 60f * density, geometry.right, y + 60f * density, stablePaint);
            }

            float arm = 19f * density;
            canvas.drawLine(cx - arm, cy, cx + arm, cy, shadowPaint);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, shadowPaint);
            canvas.drawLine(cx - arm, cy, cx + arm, cy, linePaint);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, linePaint);

            if (focusX >= 0f && System.currentTimeMillis() - focusTime < 1200L) {
                float radius = 18f * density;
                canvas.drawCircle(focusX, focusY, radius, focusPaint);
                canvas.drawLine(focusX - radius, focusY, focusX + radius, focusY, focusPaint);
                canvas.drawLine(focusX, focusY - radius, focusX, focusY + radius, focusPaint);
                postInvalidateOnAnimation();
            }
        }
    }
}
