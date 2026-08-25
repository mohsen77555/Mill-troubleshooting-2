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
import java.util.ArrayDeque;
import java.util.Arrays;
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
    private static final int HISTORY_SIZE = 7;

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Camera camera;
    private Button captureButton;
    private Button flashButton;
    private Button fixedDistanceButton;
    private TextView zoomLabel;
    private TextView fixedDistanceLabel;
    private TextView threadCountLabel;
    private LinearLayout calibrationPanel;
    private RulerOverlayView rulerOverlayView;
    private ScaleGestureDetector scaleGestureDetector;
    private SharedPreferences preferences;
    private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();

    private boolean torchOn;
    private boolean zoomGestureUsed;
    private volatile boolean calibrationMode;
    private volatile boolean fixedCalibrated;
    private float fixedDistanceCm = 10f;
    private float pendingDistanceCm = 10f;
    private float currentZoomRatio = 1f;
    private float previousZoomRatio = 1f;

    private final Object measurementLock = new Object();
    private final ArrayDeque<Integer> fullLineHistory = new ArrayDeque<>();
    private final ArrayDeque<Float> confidenceHistory = new ArrayDeque<>();
    private volatile int analysisFailures;
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

        buildUi();
        float savedBase = preferences.getFloat(PREF_RULER_BASE_PX, -1f);
        if (savedBase > 0f) rulerOverlayView.setBaseRulerPixelsAt1x(savedBase);
        rulerOverlayView.setFixedDistance(fixedDistanceCm, fixedCalibrated);
        updateFixedDistanceUi();
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
        topGuide.setText("ضع خيوط المنخل عمودية على مسطرة 1 cm • التطبيق يعد الخطوط الكاملة بين طرفيها");
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

        LinearLayout fixedRow = new LinearLayout(this);
        fixedRow.setOrientation(LinearLayout.HORIZONTAL);
        fixedRow.setGravity(Gravity.CENTER_VERTICAL);

        fixedDistanceLabel = new TextView(this);
        fixedDistanceLabel.setTextColor(Color.WHITE);
        fixedDistanceLabel.setTextSize(12f);
        fixedDistanceLabel.setGravity(Gravity.CENTER_VERTICAL);
        fixedDistanceLabel.setPadding(dp(6), 0, dp(6), 0);

        fixedDistanceButton = new Button(this);
        fixedDistanceButton.setText("معايرة المسافة");
        fixedDistanceButton.setTextSize(11f);
        fixedDistanceButton.setOnClickListener(v -> showDistanceDialog());

        fixedRow.addView(fixedDistanceLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.7f));
        fixedRow.addView(fixedDistanceButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottomPanel.addView(fixedRow, new LinearLayout.LayoutParams(
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
        rulerMinus.setText("− 2%");
        rulerMinus.setTextSize(11f);
        rulerMinus.setOnClickListener(v -> adjustCalibrationRuler(0.98f));

        Button saveCalibration = new Button(this);
        saveCalibration.setText("حفظ تطابق 1 cm");
        saveCalibration.setTextSize(11f);
        saveCalibration.setOnClickListener(v -> saveFixedDistanceCalibration());

        Button rulerPlus = new Button(this);
        rulerPlus.setText("+ 2%");
        rulerPlus.setTextSize(11f);
        rulerPlus.setOnClickListener(v -> adjustCalibrationRuler(1.02f));

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
        zoomLabel.setText("Zoom 1.0×");
        zoomLabel.setTextColor(Color.WHITE);
        zoomLabel.setTextSize(12f);
        zoomLabel.setGravity(Gravity.CENTER);
        zoomRow.addView(zoomLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
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
                if (camera == null) return false;
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
        button.setTextSize(11f);
        button.setPadding(dp(3), 0, dp(3), 0);
        button.setOnClickListener(v -> setZoomRatio(ratio));
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
                        resetThreadHistory();
                    }
                });
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
                postThreadWaiting(calibrationMode
                        ? "أكمل مطابقة مسطرة 1 cm ثم احفظ المعايرة."
                        : "عاير مسطرة 1 cm أولاً لكي يبدأ عد الخيوط.");
                return;
            }
            RulerGeometry geometry = rulerOverlayView.snapshotGeometry();
            if (!geometry.valid || !geometry.fits) {
                postThreadFailure("مسطرة 1 cm لا تقع كاملة داخل مجال الكاميرا — خفّض Zoom.");
                return;
            }
            float[] profile = buildRulerProfile(image, geometry);
            if (profile == null) {
                postThreadFailure("تعذر ربط مسطرة 1 cm بصورة الكاميرا. ثبّت الهاتف وحاول مرة أخرى.");
                return;
            }
            ThreadProfileCounter.Result result = ThreadProfileCounter.analyze(profile);
            if (!result.ok) {
                postThreadFailure(result.reason);
                return;
            }
            acceptThreadMeasurement(result);
        } catch (Exception exception) {
            postThreadFailure("تعذر عد الخيوط: " + exception.getMessage());
        } finally {
            image.close();
        }
    }

    private float[] buildRulerProfile(ImageProxy image, RulerGeometry geometry) {
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
        float rotatedY = (geometry.y - offsetY) / scale;
        if (rotatedLeft < 0 || rotatedRight >= rotatedWidth || rotatedY < 0 || rotatedY >= rotatedHeight) return null;

        int samples = Math.max(1, Math.round(rotatedRight - rotatedLeft) + 1);
        if (samples < 60) return null;
        int bandRadius = Math.max(2, Math.min(6, samples / 120));
        float[] profile = new float[samples];

        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ByteBuffer buffer = yPlane.getBuffer();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();

        for (int i = 0; i < samples; i++) {
            float rx = rotatedLeft + (rotatedRight - rotatedLeft) * i / Math.max(1f, samples - 1f);
            float sum = 0f;
            int count = 0;
            for (int dy = -bandRadius; dy <= bandRadius; dy++) {
                float ry = rotatedY + dy;
                if (ry < 0 || ry >= rotatedHeight) continue;
                int[] source = rotatedToSource(rx, ry, sourceWidth, sourceHeight, rotation);
                int index = source[1] * rowStride + source[0] * pixelStride;
                if (index >= 0 && index < buffer.limit()) {
                    sum += buffer.get(index) & 0xFF;
                    count++;
                }
            }
            profile[i] = count > 0 ? sum / count : 0f;
        }
        return profile;
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

    private void acceptThreadMeasurement(ThreadProfileCounter.Result result) {
        final int currentFullLines = result.fullLineCount;
        final float stableCount;
        final float stableConfidence;
        final boolean stable;
        synchronized (measurementLock) {
            analysisFailures = 0;
            fullLineHistory.addLast(currentFullLines);
            confidenceHistory.addLast(result.confidence);
            while (fullLineHistory.size() > HISTORY_SIZE) fullLineHistory.removeFirst();
            while (confidenceHistory.size() > HISTORY_SIZE) confidenceHistory.removeFirst();

            stableCount = trimmedAverage(fullLineHistory);
            stableConfidence = average(confidenceHistory);
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (int value : fullLineHistory) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            stable = fullLineHistory.size() >= 4 && max - min <= 2
                    && (max - min) / Math.max(1f, stableCount) <= 0.16f;

            lastFullLineCount = currentFullLines;
            lastThreadCountPerCm = stableCount;
            lastThreadConfidence = Math.max(0f, Math.min(1f, stableConfidence * (stable ? 1f : 0.82f)));
            lastThreadStable = stable;
        }

        runOnUiThread(() -> {
            String state = stable ? "ثابت" : "جارٍ التثبيت";
            threadCountLabel.setText(String.format(Locale.US,
                    "داخل 1 cm: %d خطوط كاملة • المتوسط %.1f خيط/سم • %s",
                    currentFullLines, stableCount, state));
            rulerOverlayView.setThreadMeasurement(currentFullLines, stableCount,
                    lastThreadConfidence, stable, result.centersNormalized, "");
        });
    }

    private void postThreadFailure(String reason) {
        analysisFailures++;
        if (analysisFailures >= 4) resetThreadHistory();
        runOnUiThread(() -> {
            threadCountLabel.setText("عدد الخيوط: " + reason);
            rulerOverlayView.setThreadMeasurement(0, 0f, 0f, false, new float[0], reason);
        });
    }

    private void postThreadWaiting(String reason) {
        runOnUiThread(() -> {
            threadCountLabel.setText("عدد الخيوط: " + reason);
            rulerOverlayView.setThreadMeasurement(0, 0f, 0f, false, new float[0], reason);
        });
    }

    private void updateThreadUiWaiting() {
        if (!fixedCalibrated) {
            threadCountLabel.setText("عدد الخيوط: عاير مسطرة 1 cm ثم ضع الخيوط أمامها.");
        } else {
            threadCountLabel.setText("عدد الخيوط: جارٍ عد الخطوط الكاملة داخل 1 cm...");
        }
    }

    private void resetThreadHistory() {
        synchronized (measurementLock) {
            fullLineHistory.clear();
            confidenceHistory.clear();
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

    private static float trimmedAverage(ArrayDeque<Integer> values) {
        if (values.isEmpty()) return 0f;
        int[] copy = new int[values.size()];
        int index = 0;
        for (int value : values) copy[index++] = value;
        Arrays.sort(copy);
        int from = copy.length >= 5 ? 1 : 0;
        int to = copy.length >= 5 ? copy.length - 1 : copy.length;
        float sum = 0f;
        for (int i = from; i < to; i++) sum += copy[i];
        return sum / Math.max(1, to - from);
    }

    private static float average(ArrayDeque<Float> values) {
        if (values.isEmpty()) return 0f;
        float sum = 0f;
        for (float value : values) sum += value;
        return sum / values.size();
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

    private void showDistanceDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(Locale.US, "%.1f", fixedDistanceCm));
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("Fixed Distance")
                .setMessage("أدخل المسافة من عدسة الهاتف إلى مستوى المنخل بالسنتيمتر. بعدها ضع مرجعًا حقيقيًا 1 cm في نفس المستوى وطابق المسطرة الوسطية معه.")
                .setView(input)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("ابدأ المعايرة", (dialog, which) -> {
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
        resetThreadHistory();
        calibrationPanel.setVisibility(View.VISIBLE);
        rulerOverlayView.setCalibrationMode(true);
        rulerOverlayView.setFixedDistance(pendingDistanceCm, false);
        fixedDistanceLabel.setText(String.format(Locale.US,
                "معايرة %.1f cm: طابق الخط مع مرجع حقيقي 1 cm",
                pendingDistanceCm));
        fixedDistanceButton.setText("إلغاء المعايرة");
        fixedDistanceButton.setOnClickListener(v -> cancelCalibration());
    }

    private void cancelCalibration() {
        calibrationMode = false;
        calibrationPanel.setVisibility(View.GONE);
        rulerOverlayView.setCalibrationMode(false);
        rulerOverlayView.setFixedDistance(fixedDistanceCm, fixedCalibrated);
        fixedDistanceButton.setText("معايرة المسافة");
        fixedDistanceButton.setOnClickListener(v -> showDistanceDialog());
        updateFixedDistanceUi();
        resetThreadHistory();
    }

    private void adjustCalibrationRuler(float factor) {
        if (!calibrationMode) return;
        rulerOverlayView.adjustBaseRuler(factor);
        resetThreadHistory();
    }

    private void saveFixedDistanceCalibration() {
        if (!calibrationMode) return;
        fixedDistanceCm = pendingDistanceCm;
        fixedCalibrated = true;
        preferences.edit()
                .putFloat(PREF_FIXED_DISTANCE_CM, fixedDistanceCm)
                .putFloat(PREF_RULER_BASE_PX, rulerOverlayView.getBaseRulerPixelsAt1x())
                .putBoolean(PREF_FIXED_CALIBRATED, true)
                .apply();

        calibrationMode = false;
        calibrationPanel.setVisibility(View.GONE);
        rulerOverlayView.setCalibrationMode(false);
        rulerOverlayView.setFixedDistance(fixedDistanceCm, true);
        fixedDistanceButton.setText("إعادة المعايرة");
        fixedDistanceButton.setOnClickListener(v -> showDistanceDialog());
        updateFixedDistanceUi();
        resetThreadHistory();
        Toast.makeText(this, "تم حفظ معايرة 1 cm. سيبدأ الآن عد الخيوط داخلها.", Toast.LENGTH_SHORT).show();
    }

    private void updateFixedDistanceUi() {
        if (fixedCalibrated) {
            fixedDistanceLabel.setText(String.format(Locale.US,
                    "✓ Fixed %.1f cm — مسطرة 1 cm هي مقياس العد",
                    fixedDistanceCm));
            fixedDistanceButton.setText("إعادة المعايرة");
        } else {
            fixedDistanceLabel.setText("Fixed Distance غير معاير — استخدم مرجع 1 cm مرة واحدة");
            fixedDistanceButton.setText("معايرة المسافة");
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
        private final float density;

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
            baseRulerPixelsAt1x = xdpi / 2.54f;

            shadowPaint.setColor(0xDD000000);
            shadowPaint.setStrokeWidth(5f * density);
            linePaint.setColor(Color.WHITE);
            linePaint.setStrokeWidth(2f * density);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(12f * density);
            textPaint.setFakeBoldText(true);
            panelPaint.setColor(0x76000000);
            focusPaint.setColor(0xFF67E8D1);
            focusPaint.setStyle(Paint.Style.STROKE);
            focusPaint.setStrokeWidth(2f * density);
            markerPaint.setColor(0xFFFFD54F);
            markerPaint.setStrokeWidth(2.5f * density);
            stablePaint.setColor(0xFF67E8D1);
            stablePaint.setStrokeWidth(3f * density);
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
            RectF panel = new RectF(panelLeft, y - 28f * density, panelRight, y + 66f * density);
            canvas.drawRoundRect(panel, 12f * density, 12f * density, panelPaint);

            canvas.drawLine(geometry.left, y, geometry.right, y, shadowPaint);
            canvas.drawLine(geometry.left, y, geometry.right, y, linePaint);
            for (int index = 0; index <= 10; index++) {
                float x = geometry.left + rulerLength * index / 10f;
                float tick = (index == 0 || index == 10) ? 13f * density : (index == 5 ? 10f * density : 7f * density);
                canvas.drawLine(x, y - tick, x, y + tick, shadowPaint);
                canvas.drawLine(x, y - tick, x, y + tick, linePaint);
            }

            float[] centers = measuredCenters;
            for (float normalized : centers) {
                if (normalized < 0f || normalized > 1f) continue;
                float x = geometry.left + rulerLength * normalized;
                canvas.drawLine(x, y - 17f * density, x, y + 17f * density, markerPaint);
                canvas.drawCircle(x, y, 3.2f * density, markerPaint);
            }

            String label;
            if (calibrationMode) {
                label = String.format(Locale.US, "MATCH REAL 1 cm • %.1f cm • %.1f×", fixedDistanceCm, zoomRatio);
            } else if (fixedCalibrated) {
                label = String.format(Locale.US, "1 cm COUNT WINDOW • Fixed %.1f cm • %.1f×", fixedDistanceCm, zoomRatio);
            } else {
                label = String.format(Locale.US, "1 cm GUIDE • %.1f×", zoomRatio);
            }
            if (!geometry.fits) label = "1 cm أكبر من الشاشة — خفّض Zoom";
            float textWidth = textPaint.measureText(label);
            canvas.drawText(label, Math.max(8f * density, cx - textWidth / 2f), y + 30f * density, textPaint);

            String countLine;
            if (measuredFullLines > 0) {
                countLine = String.format(Locale.US, "%d خطوط كاملة داخل 1 cm • %.1f /cm • %s",
                        measuredFullLines, measuredThreadsPerCm, measuredStable ? "STABLE" : "MEASURING");
            } else if (!measurementStatus.isEmpty()) {
                countLine = measurementStatus;
            } else {
                countLine = "يتم عد مراكز الخيوط الواقعة بين طرفي 1 cm";
            }
            float countWidth = textPaint.measureText(countLine);
            canvas.drawText(countLine, Math.max(8f * density, cx - countWidth / 2f), y + 53f * density, textPaint);
            if (measuredStable && measuredConfidence > 0.45f) {
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
