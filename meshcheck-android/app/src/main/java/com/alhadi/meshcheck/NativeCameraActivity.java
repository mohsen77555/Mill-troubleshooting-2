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

/**
 * Native CameraX measurement screen.
 *
 * v0.11 calibration rule:
 * 1) Choose zoom first.
 * 2) Start calibration at that exact zoom.
 * 3) Match the centered ruler to a real 1 cm reference in the mesh plane.
 * 4) Save. Both ruler pixel length and zoom ratio are persisted and locked.
 *
 * The app never assumes the ruler scales linearly with digital zoom after calibration.
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

    private static final int CAMERA_PERMISSION_REQUEST = 2201;
    private static final String PREFS = "meshcheck.camera.calibration";
    private static final String PREF_DISTANCE_CM = "fixed_distance_cm";
    private static final String PREF_RULER_PX = "ruler_pixels_at_calibration_zoom";
    private static final String PREF_CALIBRATED_ZOOM = "calibrated_zoom_ratio";
    private static final String PREF_CALIBRATED = "fixed_calibrated";
    private static final String PREF_LOCKED = "calibration_locked";
    private static final int SCAN_LINES = 9;
    private static final float ZOOM_LOCK_TOLERANCE = 0.035f;

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
    private RulerOverlayView overlay;
    private ScaleGestureDetector scaleGestureDetector;
    private SharedPreferences preferences;
    private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();
    private final ThreadCountConsensus.Stabilizer stabilizer = new ThreadCountConsensus.Stabilizer(12, 6);

    private boolean torchOn;
    private boolean zoomGestureUsed;
    private volatile boolean calibrationMode;
    private volatile boolean calibrated;
    private volatile boolean locked;
    private float fixedDistanceCm = 10f;
    private float pendingDistanceCm = 10f;
    private float calibratedZoomRatio = 1f;
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
        fixedDistanceCm = preferences.getFloat(PREF_DISTANCE_CM, 10f);
        pendingDistanceCm = fixedDistanceCm;
        calibratedZoomRatio = preferences.getFloat(PREF_CALIBRATED_ZOOM, 1f);
        calibrated = preferences.getBoolean(PREF_CALIBRATED, false);
        locked = preferences.getBoolean(PREF_LOCKED, calibrated);

        buildUi();

        float savedRulerPx = preferences.getFloat(PREF_RULER_PX, -1f);
        if (savedRulerPx > 20f) overlay.setRulerPixels(savedRulerPx);
        overlay.setCalibrationState(fixedDistanceCm, calibrated, calibratedZoomRatio);
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

        overlay = new RulerOverlayView(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView topGuide = new TextView(this);
        topGuide.setText("اختَر Zoom أولاً ثم عاير 1 cm • بعد الحفظ يُقفل Zoom والمعايرة");
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

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(8), dp(5), dp(8), dp(10));
        bottom.setBackgroundColor(0xB3071318);

        LinearLayout calibrationRow = new LinearLayout(this);
        calibrationRow.setOrientation(LinearLayout.HORIZONTAL);
        calibrationRow.setGravity(Gravity.CENTER_VERTICAL);

        calibrationLabel = new TextView(this);
        calibrationLabel.setTextColor(Color.WHITE);
        calibrationLabel.setTextSize(12f);
        calibrationLabel.setGravity(Gravity.CENTER_VERTICAL);
        calibrationLabel.setPadding(dp(5), 0, dp(5), 0);

        calibrationButton = new Button(this);
        calibrationButton.setTextSize(10f);
        calibrationButton.setOnClickListener(v -> onCalibrationButton());

        calibrationRow.addView(calibrationLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.9f));
        calibrationRow.addView(calibrationButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));
        bottom.addView(calibrationRow, matchWrap());

        threadCountLabel = new TextView(this);
        threadCountLabel.setTextColor(Color.WHITE);
        threadCountLabel.setTextSize(13f);
        threadCountLabel.setGravity(Gravity.CENTER);
        threadCountLabel.setPadding(dp(5), dp(3), dp(5), dp(3));
        bottom.addView(threadCountLabel, matchWrap());

        calibrationPanel = new LinearLayout(this);
        calibrationPanel.setOrientation(LinearLayout.VERTICAL);
        calibrationPanel.setGravity(Gravity.CENTER);
        calibrationPanel.setVisibility(View.GONE);
        calibrationPanel.setPadding(0, dp(2), 0, dp(3));

        LinearLayout fineRow = new LinearLayout(this);
        fineRow.setOrientation(LinearLayout.HORIZONTAL);
        fineRow.setGravity(Gravity.CENTER);
        fineRow.addView(makeAdjustButton("−1%", 0.99f), weighted());
        fineRow.addView(makeAdjustButton("−0.1%", 0.999f), weighted());
        fineRow.addView(makeAdjustButton("+0.1%", 1.001f), weighted());
        fineRow.addView(makeAdjustButton("+1%", 1.01f), weighted());
        calibrationPanel.addView(fineRow, matchWrap());

        Button save = new Button(this);
        save.setText("حفظ 1 cm + قفل Zoom");
        save.setTextSize(11f);
        save.setOnClickListener(v -> saveCalibration());
        calibrationPanel.addView(save, matchWrap());
        bottom.addView(calibrationPanel, matchWrap());

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
        zoomRow.addView(zoomLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f));
        bottom.addView(zoomRow, matchWrap());

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(3), 0, 0);

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
        back.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        controls.addView(captureButton, weighted());
        controls.addView(flashButton, weighted());
        controls.addView(back, weighted());
        bottom.addView(controls, matchWrap());

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(bottom, bottomParams);

        setContentView(root);
        setupTouchControls();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private Button makeAdjustButton(String text, float factor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(9f);
        button.setPadding(dp(1), 0, dp(1), 0);
        button.setOnClickListener(v -> {
            if (calibrationMode) overlay.adjustRuler(factor);
        });
        return button;
    }

    private Button makeZoomButton(String text, float zoom) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(10f);
        button.setPadding(dp(1), 0, dp(1), 0);
        button.setOnClickListener(v -> requestZoom(zoom));
        return button;
    }

    private void setupTouchControls() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                zoomGestureUsed = true;
                return !zoomIsLocked();
            }

            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                if (camera == null || zoomIsLocked()) return false;
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

    private boolean zoomIsLocked() {
        return calibrationMode || (calibrated && locked);
    }

    private void requestZoom(float ratio) {
        if (calibrated && locked) {
            Toast.makeText(this,
                    String.format(Locale.US, "Zoom مقفل عند %.2f× لأنه جزء من المعايرة.", calibratedZoomRatio),
                    Toast.LENGTH_SHORT).show();
            setZoomInternal(calibratedZoomRatio);
            return;
        }
        if (calibrationMode) {
            Toast.makeText(this, "أنهِ أو ألغِ المعايرة قبل تغيير Zoom.", Toast.LENGTH_SHORT).show();
            return;
        }
        setZoomInternal(ratio);
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
                        .setTargetResolution(new Size(2560, 1440))
                        .build();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analyzerExecutor, this::analyzeFrame);

                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture, analysis);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                captureButton.setEnabled(true);
                flashButton.setEnabled(camera.getCameraInfo().hasFlashUnit());

                camera.getCameraInfo().getZoomState().observe(this, state -> {
                    if (state == null) return;
                    currentZoomRatio = state.getZoomRatio();
                    zoomLabel.setText(String.format(Locale.US, "%.2f×\nmax %.1f", currentZoomRatio, state.getMaxZoomRatio()));

                    if (calibrated && locked && !zoomMatchesCalibration()) {
                        setZoomInternal(calibratedZoomRatio);
                        postWaiting("إعادة Zoom إلى قيمة المعايرة...");
                        return;
                    }

                    if (Math.abs(currentZoomRatio - previousZoomRatio) > 0.04f) {
                        previousZoomRatio = currentZoomRatio;
                        resetMeasurement();
                    }
                });

                if (calibrated && locked) {
                    setZoomInternal(calibratedZoomRatio);
                } else {
                    runOnUiThread(() -> Toast.makeText(this,
                            "اختَر Zoom المناسب أولاً، ثم اضغط معايرة.",
                            Toast.LENGTH_LONG).show());
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

    private boolean zoomMatchesCalibration() {
        if (!(calibratedZoomRatio > 0f) || !(currentZoomRatio > 0f)) return false;
        return Math.abs(currentZoomRatio - calibratedZoomRatio) / calibratedZoomRatio <= ZOOM_LOCK_TOLERANCE;
    }

    private void onCalibrationButton() {
        if (calibrated && locked) {
            new AlertDialog.Builder(this)
                    .setTitle("Reset calibration")
                    .setMessage(String.format(Locale.US,
                            "المعايرة محفوظة عند Zoom %.2f× ومسافة %.1f cm. أعدها فقط إذا أردت Zoom مختلفًا أو تغير تثبيت الهاتف.",
                            calibratedZoomRatio, fixedDistanceCm))
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("Reset", (dialog, which) -> resetCalibration())
                    .show();
            return;
        }
        showDistanceDialog();
    }

    private void showDistanceDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(Locale.US, "%.1f", fixedDistanceCm));
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle(String.format(Locale.US, "معايرة عند Zoom %.2f×", currentZoomRatio))
                .setMessage("ثبّت Zoom الحالي. ضع مرجعًا حقيقيًا 1 cm في نفس مستوى المنخل وأدخل مسافة العدسة عن المنخل. بعدها عدّل طول الخط حتى يطابق المرجع تمامًا.")
                .setView(input)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("ابدأ المعايرة", (dialog, which) -> {
                    try {
                        float distance = Float.parseFloat(input.getText().toString().trim());
                        if (!(distance >= 2f && distance <= 100f)) throw new NumberFormatException();
                        beginCalibration(distance);
                    } catch (NumberFormatException exception) {
                        Toast.makeText(this, "أدخل مسافة صحيحة بين 2 و100 cm.", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void beginCalibration(float distanceCm) {
        if (camera == null) return;
        pendingDistanceCm = distanceCm;
        calibrationMode = true;
        locked = false;
        resetMeasurement();

        // Start from a reasonable on-screen ruler only for visual convenience.
        // The user then matches it physically using ±1% and ±0.1% controls.
        if (!calibrated) overlay.resetRulerToDisplayCm();
        overlay.setCalibrationMode(true);
        overlay.setCalibrationState(pendingDistanceCm, false, currentZoomRatio);
        calibrationPanel.setVisibility(View.VISIBLE);
        calibrationLabel.setText(String.format(Locale.US,
                "CALIBRATE: %.2f× • %.1f cm • طابق الخط مع 1 cm الحقيقي",
                currentZoomRatio, pendingDistanceCm));
        calibrationButton.setText("إلغاء");
        calibrationButton.setOnClickListener(v -> cancelCalibration());
    }

    private void cancelCalibration() {
        calibrationMode = false;
        calibrationPanel.setVisibility(View.GONE);
        overlay.setCalibrationMode(false);
        overlay.setCalibrationState(fixedDistanceCm, calibrated, calibratedZoomRatio);
        calibrationButton.setOnClickListener(v -> onCalibrationButton());
        if (calibrated && locked) setZoomInternal(calibratedZoomRatio);
        updateCalibrationUi();
        resetMeasurement();
    }

    private void saveCalibration() {
        if (!calibrationMode) return;

        fixedDistanceCm = pendingDistanceCm;
        calibratedZoomRatio = currentZoomRatio;
        calibrated = true;
        locked = true;

        preferences.edit()
                .putFloat(PREF_DISTANCE_CM, fixedDistanceCm)
                .putFloat(PREF_RULER_PX, overlay.getRulerPixels())
                .putFloat(PREF_CALIBRATED_ZOOM, calibratedZoomRatio)
                .putBoolean(PREF_CALIBRATED, true)
                .putBoolean(PREF_LOCKED, true)
                .apply();

        calibrationMode = false;
        calibrationPanel.setVisibility(View.GONE);
        overlay.setCalibrationMode(false);
        overlay.setCalibrationState(fixedDistanceCm, true, calibratedZoomRatio);
        calibrationButton.setOnClickListener(v -> onCalibrationButton());
        updateCalibrationUi();
        resetMeasurement();

        Toast.makeText(this,
                String.format(Locale.US,
                        "تم الحفظ: 1 cm عند Zoom %.2f×. سيبقى Zoom مقفلاً لكل القياسات.",
                        calibratedZoomRatio),
                Toast.LENGTH_LONG).show();
    }

    private void resetCalibration() {
        calibrated = false;
        locked = false;
        calibrationMode = false;
        preferences.edit().clear().apply();
        fixedDistanceCm = 10f;
        pendingDistanceCm = 10f;
        calibratedZoomRatio = 1f;
        overlay.resetRulerToDisplayCm();
        overlay.setCalibrationMode(false);
        overlay.setCalibrationState(fixedDistanceCm, false, 1f);
        calibrationPanel.setVisibility(View.GONE);
        calibrationButton.setOnClickListener(v -> onCalibrationButton());
        resetMeasurement();
        updateCalibrationUi();
        Toast.makeText(this, "تم فك القفل. اختَر Zoom الجديد ثم اعمل المعايرة.", Toast.LENGTH_LONG).show();
    }

    private void updateCalibrationUi() {
        if (calibrated && locked) {
            calibrationLabel.setText(String.format(Locale.US,
                    "✓ LOCKED • 1 cm • Zoom %.2f× • %.1f cm",
                    calibratedZoomRatio, fixedDistanceCm));
            calibrationButton.setText("Reset");
        } else {
            calibrationLabel.setText("1) اختَر Zoom  2) عاير 1 cm  3) احفظ");
            calibrationButton.setText("معايرة");
        }
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        try {
            if (!calibrated || calibrationMode || !locked) {
                postWaiting(calibrationMode ? "المعايرة جارية..." : "اختَر Zoom ثم عاير 1 cm.");
                return;
            }
            if (!zoomMatchesCalibration()) {
                postWaiting(String.format(Locale.US, "القياس ينتظر Zoom %.2f×...", calibratedZoomRatio));
                return;
            }

            RulerGeometry geometry = overlay.snapshotGeometry();
            if (!geometry.valid || !geometry.fits) {
                postFailure("نافذة 1 cm خارج مجال الصورة.");
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
        if (rotatedLeft < 0 || rotatedRight >= rotatedWidth || rotatedCenterY < 0 || rotatedCenterY >= rotatedHeight) return null;

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
            String state = snapshot.stable ? "✓ ثابت" : "تثبيت " + snapshot.samples + "/6";
            threadCountLabel.setText(String.format(Locale.US,
                    "1 cm: %d خطوط كاملة • %.1f خيط/سم • %s • %d/%d",
                    frame.currentFullLineCount, lastThreadCountPerCm, state,
                    frame.validScans, frame.totalScans));
            overlay.setThreadMeasurement(frame.currentFullLineCount, lastThreadCountPerCm,
                    lastThreadConfidence, snapshot.stable, frame.centersNormalized, "");
        });
    }

    private void postFailure(String reason) {
        analysisFailures++;
        if (analysisFailures >= 5) resetMeasurement();
        runOnUiThread(() -> {
            threadCountLabel.setText("عدد الخيوط: " + reason);
            overlay.setThreadMeasurement(0, 0f, 0f, false, new float[0], reason);
        });
    }

    private void postWaiting(String reason) {
        runOnUiThread(() -> {
            threadCountLabel.setText("عدد الخيوط: " + reason);
            overlay.setThreadMeasurement(0, 0f, 0f, false, new float[0], reason);
        });
    }

    private void updateThreadUiWaiting() {
        if (!calibrated) threadCountLabel.setText("عدد الخيوط: اختَر Zoom ثم عاير 1 cm.");
        else threadCountLabel.setText("عدد الخيوط: جارٍ تحليل 9 مسارات داخل 1 cm...");
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
            overlay.setThreadMeasurement(0, 0f, 0f, false, new float[0], "");
            updateThreadUiWaiting();
        });
    }

    private void focusAt(float x, float y) {
        if (camera == null) return;
        try {
            MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(4, TimeUnit.SECONDS)
                    .build();
            camera.getCameraControl().startFocusAndMetering(action);
            overlay.showFocusMarker(x, y);
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
        if (!calibrated || !locked || !zoomMatchesCalibration()) {
            Toast.makeText(this, "أكمل المعايرة المقفلة قبل الالتقاط.", Toast.LENGTH_LONG).show();
            return;
        }

        captureButton.setEnabled(false);
        File output = new File(getCacheDir(), "meshcheck-native-" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(output).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Intent result = new Intent();
                result.putExtra(EXTRA_CAPTURE_PATH, output.getAbsolutePath());
                result.putExtra(EXTRA_ZOOM_RATIO, calibratedZoomRatio);
                result.putExtra(EXTRA_FIXED_DISTANCE_CM, fixedDistanceCm);
                result.putExtra(EXTRA_FIXED_CALIBRATED, true);
                result.putExtra(EXTRA_RULER_BASE_PX_1X, overlay.getRulerPixels());
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
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else {
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
        final float left, right, y;
        final int viewWidth, viewHeight;
        final boolean fits, valid;

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
        private final float displayOneCmPixels;

        private volatile float rulerPixels;
        private volatile float fixedDistanceCm = 10f;
        private volatile float calibratedZoom = 1f;
        private volatile boolean calibrated;
        private volatile boolean calibrationMode;
        private volatile int measuredFullLines;
        private volatile float measuredThreadsPerCm;
        private volatile float measuredConfidence;
        private volatile boolean measuredStable;
        private volatile float[] measuredCenters = new float[0];
        private volatile String measurementStatus = "";
        private volatile int viewWidth, viewHeight;
        private float focusX = -1f, focusY = -1f;
        private long focusTime;

        RulerOverlayView(NativeCameraActivity context) {
            super(context);
            setWillNotDraw(false);
            setClickable(false);
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            density = metrics.density;
            float xdpi = metrics.xdpi;
            if (!(xdpi >= 100f && xdpi <= 1000f)) xdpi = metrics.densityDpi;
            displayOneCmPixels = xdpi / 2.54f;
            rulerPixels = displayOneCmPixels;

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

        void setRulerPixels(float pixels) {
            if (pixels > 20f) {
                rulerPixels = Math.max(20f, Math.min(3000f, pixels));
                invalidate();
            }
        }

        float getRulerPixels() {
            return rulerPixels;
        }

        void resetRulerToDisplayCm() {
            rulerPixels = displayOneCmPixels;
            invalidate();
        }

        void adjustRuler(float factor) {
            rulerPixels = Math.max(20f, Math.min(3000f, rulerPixels * factor));
            invalidate();
        }

        void setCalibrationState(float distanceCm, boolean isCalibrated, float zoom) {
            fixedDistanceCm = distanceCm;
            calibrated = isCalibrated;
            calibratedZoom = zoom;
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
            float cx = w / 2f;
            float cy = h / 2f;
            float left = cx - rulerPixels / 2f;
            float right = cx + rulerPixels / 2f;
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
            RulerGeometry g = snapshotGeometry();
            float cx = g.viewWidth / 2f;
            float cy = g.viewHeight / 2f;
            float y = g.y;
            float length = g.right - g.left;

            float panelLeft = Math.max(6f * density, g.left - 14f * density);
            float panelRight = Math.min(getWidth() - 6f * density, g.right + 14f * density);
            RectF panel = new RectF(panelLeft, y - 43f * density, panelRight, y + 74f * density);
            canvas.drawRoundRect(panel, 12f * density, 12f * density, panelPaint);

            for (int scan = 0; scan < SCAN_LINES; scan++) {
                float offset = (scan - SCAN_LINES / 2f) * 2.2f * density;
                canvas.drawLine(g.left, y + offset, g.right, y + offset, scanPaint);
            }

            canvas.drawLine(g.left, y, g.right, y, shadowPaint);
            canvas.drawLine(g.left, y, g.right, y, linePaint);
            for (int i = 0; i <= 10; i++) {
                float x = g.left + length * i / 10f;
                float tick = (i == 0 || i == 10) ? 13f * density : (i == 5 ? 10f * density : 7f * density);
                canvas.drawLine(x, y - tick, x, y + tick, shadowPaint);
                canvas.drawLine(x, y - tick, x, y + tick, linePaint);
            }

            for (float normalized : measuredCenters) {
                if (normalized < 0f || normalized > 1f) continue;
                float x = g.left + length * normalized;
                canvas.drawLine(x, y - 18f * density, x, y + 18f * density, markerPaint);
                canvas.drawCircle(x, y, 3.2f * density, markerPaint);
            }

            String title;
            if (calibrationMode) {
                title = String.format(Locale.US, "MATCH REAL 1 cm • Zoom %.2f× • %.1f cm", calibratedZoom, fixedDistanceCm);
            } else if (calibrated) {
                title = String.format(Locale.US, "1 cm LOCKED • Zoom %.2f× • 9 SCANS", calibratedZoom);
            } else {
                title = "SELECT ZOOM → CALIBRATE REAL 1 cm";
            }
            if (!g.fits) title = "1 cm خارج الشاشة — Reset واستخدم Zoom أقل";
            float titleWidth = textPaint.measureText(title);
            canvas.drawText(title, Math.max(8f * density, cx - titleWidth / 2f), y - 25f * density, textPaint);

            String countText;
            if (measuredFullLines > 0) {
                countText = String.format(Locale.US, "%d خطوط كاملة • %.1f /cm • %s",
                        measuredFullLines, measuredThreadsPerCm, measuredStable ? "STABLE" : "MEASURING");
            } else if (!measurementStatus.isEmpty()) countText = measurementStatus;
            else countText = "9 مسارات عد داخل نفس 1 cm";

            float countWidth = textPaint.measureText(countText);
            canvas.drawText(countText, Math.max(8f * density, cx - countWidth / 2f), y + 51f * density, textPaint);
            if (measuredStable && measuredConfidence > 0.42f) {
                canvas.drawLine(g.left, y + 61f * density, g.right, y + 61f * density, stablePaint);
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
