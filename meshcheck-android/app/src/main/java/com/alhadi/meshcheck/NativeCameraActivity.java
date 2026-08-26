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
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
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
import androidx.camera.camera2.interop.Camera2Interop;
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
 * CameraX measurement screen.
 * v0.13: one base 1 cm calibration, then automatic ruler scaling from zoom + distance.
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
    private static final String PREF_BASE_DISTANCE_CM = "auto_base_distance_cm";
    private static final String PREF_BASE_RULER_PX = "auto_base_ruler_px";
    private static final String PREF_BASE_ZOOM = "auto_base_zoom";
    private static final String PREF_BASE_FOCUS_D = "auto_base_focus_diopters";
    private static final String PREF_MANUAL_DISTANCE_CM = "manual_distance_cm";
    private static final String PREF_CALIBRATED = "auto_calibrated";
    private static final int SCAN_LINES = 9;

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Camera camera;
    private Button captureButton;
    private Button flashButton;
    private Button calibrationButton;
    private Button distanceButton;
    private TextView zoomLabel;
    private TextView calibrationLabel;
    private TextView distanceLabel;
    private TextView threadCountLabel;
    private LinearLayout calibrationPanel;
    private RulerOverlayView overlay;
    private ScaleGestureDetector scaleGestureDetector;
    private SharedPreferences preferences;
    private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();
    private final ThreadCountConsensus.Stabilizer stabilizer = new ThreadCountConsensus.Stabilizer(14, 7);

    private boolean torchOn;
    private boolean zoomGestureUsed;
    private volatile boolean calibrationMode;
    private volatile boolean calibrated;
    private float baseDistanceCm = 10f;
    private float pendingBaseDistanceCm = 10f;
    private float baseRulerPx;
    private float baseZoom = 1f;
    private float baseFocusDiopters;
    private float manualDistanceCm = 10f;
    private volatile float currentDistanceCm = 10f;
    private volatile float currentZoomRatio = 1f;
    private volatile float previousZoomRatio = 1f;
    private volatile float latestFocusDiopters;
    private volatile float smoothedFocusDiopters;
    private volatile boolean autoDistanceActive;
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
        calibrated = preferences.getBoolean(PREF_CALIBRATED, false);
        baseDistanceCm = preferences.getFloat(PREF_BASE_DISTANCE_CM, 10f);
        pendingBaseDistanceCm = baseDistanceCm;
        baseRulerPx = preferences.getFloat(PREF_BASE_RULER_PX, 0f);
        baseZoom = preferences.getFloat(PREF_BASE_ZOOM, 1f);
        baseFocusDiopters = preferences.getFloat(PREF_BASE_FOCUS_D, 0f);
        manualDistanceCm = preferences.getFloat(PREF_MANUAL_DISTANCE_CM, baseDistanceCm);
        currentDistanceCm = manualDistanceCm;

        buildUi();
        if (baseRulerPx > 20f) overlay.setRulerPixels(baseRulerPx);
        overlay.setCalibrationState(baseDistanceCm, calibrated, baseZoom);
        updateCalibrationUi();
        updateDistanceUi();
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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new RulerOverlayView(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView topGuide = new TextView(this);
        topGuide.setText("معايرة واحدة فقط • بعدها 1 cm يتغير تلقائيًا مع Zoom والمسافة");
        topGuide.setTextColor(Color.WHITE);
        topGuide.setTextSize(13f);
        topGuide.setGravity(Gravity.CENTER);
        topGuide.setPadding(dp(10), dp(8), dp(10), dp(8));
        topGuide.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams guideParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
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
        calibrationLabel.setTextSize(11.5f);
        calibrationLabel.setPadding(dp(5), 0, dp(5), 0);
        calibrationButton = new Button(this);
        calibrationButton.setTextSize(10f);
        calibrationButton.setOnClickListener(v -> onCalibrationButton());
        calibrationRow.addView(calibrationLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.9f));
        calibrationRow.addView(calibrationButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));
        bottom.addView(calibrationRow, matchWrap());

        LinearLayout distanceRow = new LinearLayout(this);
        distanceRow.setOrientation(LinearLayout.HORIZONTAL);
        distanceRow.setGravity(Gravity.CENTER_VERTICAL);
        distanceLabel = new TextView(this);
        distanceLabel.setTextColor(Color.WHITE);
        distanceLabel.setTextSize(12f);
        distanceLabel.setPadding(dp(5), 0, dp(5), 0);
        distanceButton = new Button(this);
        distanceButton.setText("Distance");
        distanceButton.setTextSize(10f);
        distanceButton.setOnClickListener(v -> showCurrentDistanceDialog());
        distanceRow.addView(distanceLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f));
        distanceRow.addView(distanceButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f));
        bottom.addView(distanceRow, matchWrap());

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
        LinearLayout fineRow = new LinearLayout(this);
        fineRow.setOrientation(LinearLayout.HORIZONTAL);
        fineRow.addView(makeAdjustButton("−1%", 0.99f), weighted());
        fineRow.addView(makeAdjustButton("−0.1%", 0.999f), weighted());
        fineRow.addView(makeAdjustButton("+0.1%", 1.001f), weighted());
        fineRow.addView(makeAdjustButton("+1%", 1.01f), weighted());
        calibrationPanel.addView(fineRow, matchWrap());
        Button save = new Button(this);
        save.setText("حفظ معايرة الأساس");
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
        zoomRow.addView(zoomLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f));
        bottom.addView(zoomRow, matchWrap());

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
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

    private Button makeAdjustButton(String text, float factor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(9f);
        b.setOnClickListener(v -> { if (calibrationMode) overlay.adjustRuler(factor); });
        return b;
    }

    private Button makeZoomButton(String text, float ratio) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(10f);
        b.setOnClickListener(v -> requestZoom(ratio));
        return b;
    }

    private void setupTouchControls() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                zoomGestureUsed = true;
                return !calibrationMode;
            }
            @Override public boolean onScale(@NonNull ScaleGestureDetector detector) {
                if (camera == null || calibrationMode) return false;
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

    private void requestZoom(float ratio) {
        if (calibrationMode) {
            Toast.makeText(this, "أكمل المعايرة قبل تغيير Zoom.", Toast.LENGTH_SHORT).show();
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
                        .setTargetResolution(new Size(2560, 1440)).build();

                ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);
                new Camera2Interop.Extender<>(analysisBuilder).setSessionCaptureCallback(
                        new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                           @NonNull android.hardware.camera2.CaptureRequest request,
                                                           @NonNull TotalCaptureResult result) {
                                Float focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                                if (focus != null && focus > 0.02f) {
                                    latestFocusDiopters = focus;
                                    if (!(smoothedFocusDiopters > 0.02f)) smoothedFocusDiopters = focus;
                                    else smoothedFocusDiopters = smoothedFocusDiopters * 0.86f + focus * 0.14f;
                                    updateAutoDistanceAndRuler();
                                }
                            }
                        });
                ImageAnalysis analysis = analysisBuilder.build();
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
                    if (Math.abs(currentZoomRatio - previousZoomRatio) > 0.02f) {
                        previousZoomRatio = currentZoomRatio;
                        if (!calibrationMode) updateAutoDistanceAndRuler();
                        resetMeasurement();
                    }
                });
            } catch (ExecutionException e) {
                Toast.makeText(this, "تعذر تشغيل الكاميرا: " + e.getMessage(), Toast.LENGTH_LONG).show();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Toast.makeText(this, "تعذر تشغيل الكاميرا الخلفية: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void updateAutoDistanceAndRuler() {
        if (!calibrated || calibrationMode) return;
        float estimated = AutoRulerModel.distanceFromFocus(baseDistanceCm, baseFocusDiopters, smoothedFocusDiopters);
        if (estimated > 0f) {
            currentDistanceCm = estimated;
            autoDistanceActive = true;
        } else {
            currentDistanceCm = manualDistanceCm;
            autoDistanceActive = false;
        }
        float px = AutoRulerModel.rulerPixels(baseRulerPx, baseZoom, baseDistanceCm,
                currentZoomRatio, currentDistanceCm);
        if (px > 0f) overlay.setRulerPixels(px);
        overlay.setLiveAutoState(currentDistanceCm, currentZoomRatio, autoDistanceActive);
        runOnUiThread(this::updateDistanceUi);
    }

    private void showCurrentDistanceDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(Locale.US, "%.1f", manualDistanceCm));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("المسافة الحالية")
                .setMessage("هذه القيمة تُستخدم تلقائيًا إذا لم يوفر الهاتف Focus Distance موثوقًا. أدخل المسافة من العدسة إلى مستوى المنخل بالسنتيمتر.")
                .setView(input)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("اعتماد", (d, w) -> {
                    try {
                        manualDistanceCm = AutoRulerModel.clampDistance(Float.parseFloat(input.getText().toString().trim()));
                        preferences.edit().putFloat(PREF_MANUAL_DISTANCE_CM, manualDistanceCm).apply();
                        updateAutoDistanceAndRuler();
                        resetMeasurement();
                    } catch (Exception e) {
                        Toast.makeText(this, "أدخل مسافة صحيحة.", Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void onCalibrationButton() {
        if (calibrated) {
            new AlertDialog.Builder(this)
                    .setTitle("إعادة معايرة الأساس")
                    .setMessage(String.format(Locale.US,
                            "المعايرة الأساسية الحالية: %.2f× عند %.1f cm. لا تحتاج إعادة المعايرة عند تغيير Zoom أو المسافة.",
                            baseZoom, baseDistanceCm))
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("Reset", (d, w) -> resetCalibration()).show();
        } else showBaseCalibrationDialog();
    }

    private void showBaseCalibrationDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(Locale.US, "%.1f", manualDistanceCm));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(String.format(Locale.US, "Base calibration at %.2f×", currentZoomRatio))
                .setMessage("ضع مرجعًا حقيقيًا 1 cm في نفس مستوى المنخل، وقِس المسافة من العدسة إلى المنخل. هذه المعايرة ستُستخدم بعد ذلك لكل Zoom ومسافة.")
                .setView(input)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("ابدأ", (d, w) -> {
                    try {
                        float cm = Float.parseFloat(input.getText().toString().trim());
                        if (cm < 2f || cm > 100f) throw new NumberFormatException();
                        beginCalibration(cm);
                    } catch (Exception e) {
                        Toast.makeText(this, "أدخل مسافة صحيحة 2–100 cm.", Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void beginCalibration(float distanceCm) {
        pendingBaseDistanceCm = distanceCm;
        calibrationMode = true;
        resetMeasurement();
        overlay.resetRulerToDisplayCm();
        overlay.setCalibrationMode(true);
        overlay.setCalibrationState(distanceCm, false, currentZoomRatio);
        calibrationPanel.setVisibility(View.VISIBLE);
        calibrationLabel.setText(String.format(Locale.US,
                "BASE: %.2f× • %.1f cm • طابق الخط مع 1 cm الحقيقي",
                currentZoomRatio, distanceCm));
        calibrationButton.setText("إلغاء");
        calibrationButton.setOnClickListener(v -> cancelCalibration());
    }

    private void cancelCalibration() {
        calibrationMode = false;
        calibrationPanel.setVisibility(View.GONE);
        overlay.setCalibrationMode(false);
        calibrationButton.setOnClickListener(v -> onCalibrationButton());
        if (calibrated) updateAutoDistanceAndRuler();
        updateCalibrationUi();
        resetMeasurement();
    }

    private void saveCalibration() {
        if (!calibrationMode) return;
        baseDistanceCm = pendingBaseDistanceCm;
        baseRulerPx = overlay.getRulerPixels();
        baseZoom = currentZoomRatio;
        baseFocusDiopters = smoothedFocusDiopters > 0.02f ? smoothedFocusDiopters : latestFocusDiopters;
        manualDistanceCm = baseDistanceCm;
        calibrated = true;
        calibrationMode = false;
        preferences.edit()
                .putBoolean(PREF_CALIBRATED, true)
                .putFloat(PREF_BASE_DISTANCE_CM, baseDistanceCm)
                .putFloat(PREF_BASE_RULER_PX, baseRulerPx)
                .putFloat(PREF_BASE_ZOOM, baseZoom)
                .putFloat(PREF_BASE_FOCUS_D, baseFocusDiopters)
                .putFloat(PREF_MANUAL_DISTANCE_CM, manualDistanceCm)
                .apply();
        calibrationPanel.setVisibility(View.GONE);
        overlay.setCalibrationMode(false);
        overlay.setCalibrationState(baseDistanceCm, true, baseZoom);
        calibrationButton.setOnClickListener(v -> onCalibrationButton());
        updateCalibrationUi();
        updateAutoDistanceAndRuler();
        resetMeasurement();
        Toast.makeText(this, "تم حفظ Base Calibration. الآن 1 cm يتغير تلقائيًا مع Zoom والمسافة.", Toast.LENGTH_LONG).show();
    }

    private void resetCalibration() {
        calibrated = false;
        calibrationMode = false;
        preferences.edit().clear().apply();
        baseDistanceCm = 10f;
        baseRulerPx = 0f;
        baseZoom = 1f;
        baseFocusDiopters = 0f;
        manualDistanceCm = 10f;
        currentDistanceCm = 10f;
        autoDistanceActive = false;
        overlay.resetRulerToDisplayCm();
        overlay.setCalibrationMode(false);
        overlay.setCalibrationState(10f, false, 1f);
        calibrationPanel.setVisibility(View.GONE);
        calibrationButton.setOnClickListener(v -> onCalibrationButton());
        updateCalibrationUi();
        updateDistanceUi();
        resetMeasurement();
    }

    private void updateCalibrationUi() {
        if (calibrated) {
            calibrationLabel.setText(String.format(Locale.US,
                    "✓ BASE SAVED • %.2f× @ %.1f cm", baseZoom, baseDistanceCm));
            calibrationButton.setText("Reset");
        } else {
            calibrationLabel.setText("معايرة واحدة: اختر Zoom + ضع 1 cm حقيقي");
            calibrationButton.setText("معايرة");
        }
    }

    private void updateDistanceUi() {
        if (!calibrated) {
            distanceLabel.setText(String.format(Locale.US, "Distance %.1f cm (قبل المعايرة)", manualDistanceCm));
            return;
        }
        distanceLabel.setText(String.format(Locale.US, "Distance %.1f cm (%s)",
                currentDistanceCm, autoDistanceActive ? "AUTO" : "MANUAL"));
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        try {
            if (!calibrated || calibrationMode) {
                postWaiting(calibrationMode ? "المعايرة جارية..." : "اعمل Base Calibration مرة واحدة.");
                return;
            }
            RulerGeometry geometry = overlay.snapshotGeometry();
            if (!geometry.valid || !geometry.fits) {
                postFailure("1 cm خارج مجال الصورة — قلل Zoom أو زِد المسافة.");
                return;
            }
            ThreadProfileCounter.Result[] scans = buildAndAnalyzeScanLines(image, geometry);
            if (scans == null) { postFailure("تعذر قراءة نافذة 1 cm."); return; }
            ThreadCountConsensus.FrameResult frame = ThreadCountConsensus.fuse(scans);
            if (!frame.ok) { postFailure(frame.reason); return; }
            acceptFrame(frame);
        } catch (Exception e) {
            postFailure("تعذر عد الخيوط: " + e.getMessage());
        } finally { image.close(); }
    }

    private ThreadProfileCounter.Result[] buildAndAnalyzeScanLines(ImageProxy image, RulerGeometry g) {
        int sourceWidth = image.getWidth(), sourceHeight = image.getHeight();
        int rotation = ((image.getImageInfo().getRotationDegrees() % 360) + 360) % 360;
        int rotatedWidth = (rotation == 90 || rotation == 270) ? sourceHeight : sourceWidth;
        int rotatedHeight = (rotation == 90 || rotation == 270) ? sourceWidth : sourceHeight;
        float scale = Math.min(g.viewWidth / (float) rotatedWidth, g.viewHeight / (float) rotatedHeight);
        if (!(scale > 0f)) return null;
        float offsetX = (g.viewWidth - rotatedWidth * scale) / 2f;
        float offsetY = (g.viewHeight - rotatedHeight * scale) / 2f;
        float rotatedLeft = (g.left - offsetX) / scale;
        float rotatedRight = (g.right - offsetX) / scale;
        float rotatedCenterY = (g.y - offsetY) / scale;
        if (rotatedLeft < 0 || rotatedRight >= rotatedWidth || rotatedCenterY < 0 || rotatedCenterY >= rotatedHeight) return null;
        int samples = Math.max(1, Math.round(rotatedRight - rotatedLeft) + 1);
        if (samples < 60) return null;

        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ByteBuffer buffer = yPlane.getBuffer();
        int rowStride = yPlane.getRowStride(), pixelStride = yPlane.getPixelStride();
        float scanSpacing = Math.max(2f, Math.min(6f, samples / 160f));
        ThreadProfileCounter.Result[] results = new ThreadProfileCounter.Result[SCAN_LINES];
        int middle = SCAN_LINES / 2;
        for (int scan = 0; scan < SCAN_LINES; scan++) {
            float ry = rotatedCenterY + (scan - middle) * scanSpacing;
            float[] profile = new float[samples];
            for (int i = 0; i < samples; i++) {
                float rx = rotatedLeft + (rotatedRight - rotatedLeft) * i / Math.max(1f, samples - 1f);
                float sum = 0f; int count = 0;
                for (int local = -1; local <= 1; local++) {
                    int[] source = rotatedToSource(rx, ry + local, sourceWidth, sourceHeight, rotation);
                    int index = source[1] * rowStride + source[0] * pixelStride;
                    if (index >= 0 && index < buffer.limit()) { sum += buffer.get(index) & 0xFF; count++; }
                }
                profile[i] = count > 0 ? sum / count : 0f;
            }
            results[scan] = ThreadProfileCounter.analyze(profile);
        }
        return results;
    }

    private static int[] rotatedToSource(float rx, float ry, int width, int height, int rotation) {
        int sx, sy;
        switch (rotation) {
            case 90: sx = Math.round(ry); sy = height - 1 - Math.round(rx); break;
            case 180: sx = width - 1 - Math.round(rx); sy = height - 1 - Math.round(ry); break;
            case 270: sx = width - 1 - Math.round(ry); sy = Math.round(rx); break;
            default: sx = Math.round(rx); sy = Math.round(ry); break;
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
            String state = snapshot.stable ? "✓ ثابت" : "تثبيت " + snapshot.samples + "/7";
            threadCountLabel.setText(String.format(Locale.US,
                    "%d خطوط كاملة • %.1f n/cm • %s • %d/%d",
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
            threadCountLabel.setText(reason);
            overlay.setThreadMeasurement(0, 0f, 0f, false, new float[0], reason);
        });
    }

    private void postWaiting(String reason) {
        runOnUiThread(() -> {
            threadCountLabel.setText(reason);
            overlay.setThreadMeasurement(0, 0f, 0f, false, new float[0], reason);
        });
    }

    private void updateThreadUiWaiting() {
        threadCountLabel.setText(calibrated ? "جارٍ تحليل 9 مسارات داخل Auto 1 cm..." : "اعمل Base Calibration مرة واحدة.");
    }

    private void resetMeasurement() {
        stabilizer.reset();
        synchronized (measurementLock) {
            lastThreadCountPerCm = 0f; lastFullLineCount = 0; lastThreadConfidence = 0f; lastThreadStable = false;
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
                    .setAutoCancelDuration(4, TimeUnit.SECONDS).build();
            camera.getCameraControl().startFocusAndMetering(action);
            overlay.showFocusMarker(x, y);
        } catch (Exception ignored) {}
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        flashButton.setText(torchOn ? "FLASH ON" : "FLASH");
    }

    private void capturePhoto() {
        if (imageCapture == null || !calibrated) {
            Toast.makeText(this, "اعمل Base Calibration أولًا.", Toast.LENGTH_LONG).show();
            return;
        }
        captureButton.setEnabled(false);
        File output = new File(getCacheDir(), "meshcheck-native-" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(output).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Intent result = new Intent();
                result.putExtra(EXTRA_CAPTURE_PATH, output.getAbsolutePath());
                result.putExtra(EXTRA_ZOOM_RATIO, currentZoomRatio);
                result.putExtra(EXTRA_FIXED_DISTANCE_CM, currentDistanceCm);
                result.putExtra(EXTRA_FIXED_CALIBRATED, true);
                result.putExtra(EXTRA_RULER_BASE_PX_1X, overlay.getRulerPixels());
                synchronized (measurementLock) {
                    result.putExtra(EXTRA_THREAD_COUNT_CM, lastThreadCountPerCm);
                    result.putExtra(EXTRA_FULL_LINE_COUNT, lastFullLineCount);
                    result.putExtra(EXTRA_THREAD_COUNT_CONFIDENCE, lastThreadConfidence);
                    result.putExtra(EXTRA_THREAD_COUNT_STABLE, lastThreadStable);
                }
                setResult(RESULT_OK, result); finish();
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

    private static final class RulerGeometry {
        final float left, right, y; final int viewWidth, viewHeight; final boolean fits, valid;
        RulerGeometry(float left, float right, float y, int viewWidth, int viewHeight, boolean fits) {
            this.left = left; this.right = right; this.y = y; this.viewWidth = viewWidth; this.viewHeight = viewHeight;
            this.fits = fits; this.valid = viewWidth > 0 && viewHeight > 0 && right > left;
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
        private final float density, displayOneCmPixels;
        private volatile float rulerPixels, fixedDistanceCm = 10f, calibratedZoom = 1f;
        private volatile float liveDistanceCm = 10f, liveZoom = 1f;
        private volatile boolean calibrated, calibrationMode, autoDistance;
        private volatile int measuredFullLines, viewWidth, viewHeight;
        private volatile float measuredThreadsPerCm, measuredConfidence;
        private volatile boolean measuredStable;
        private volatile float[] measuredCenters = new float[0];
        private volatile String measurementStatus = "";
        private float focusX = -1f, focusY = -1f; private long focusTime;

        RulerOverlayView(NativeCameraActivity context) {
            super(context); setWillNotDraw(false);
            DisplayMetrics m = getResources().getDisplayMetrics(); density = m.density;
            float xdpi = (m.xdpi >= 100f && m.xdpi <= 1000f) ? m.xdpi : m.densityDpi;
            displayOneCmPixels = xdpi / 2.54f; rulerPixels = displayOneCmPixels;
            shadowPaint.setColor(0xDD000000); shadowPaint.setStrokeWidth(5f * density);
            linePaint.setColor(Color.WHITE); linePaint.setStrokeWidth(2f * density);
            textPaint.setColor(Color.WHITE); textPaint.setTextSize(11f * density); textPaint.setFakeBoldText(true);
            panelPaint.setColor(0x76000000);
            focusPaint.setColor(0xFF67E8D1); focusPaint.setStyle(Paint.Style.STROKE); focusPaint.setStrokeWidth(2f * density);
            markerPaint.setColor(0xFFFFD54F); markerPaint.setStrokeWidth(2.5f * density);
            stablePaint.setColor(0xFF67E8D1); stablePaint.setStrokeWidth(3f * density);
            scanPaint.setColor(0x5573D7FF); scanPaint.setStrokeWidth(1f * density);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { viewWidth = w; viewHeight = h; }
        void setRulerPixels(float px) { if (px > 20f) { rulerPixels = AutoRulerModel.clampPixels(px); invalidate(); } }
        float getRulerPixels() { return rulerPixels; }
        void resetRulerToDisplayCm() { rulerPixels = displayOneCmPixels; invalidate(); }
        void adjustRuler(float factor) { rulerPixels = AutoRulerModel.clampPixels(rulerPixels * factor); invalidate(); }
        void setCalibrationState(float distanceCm, boolean ok, float zoom) { fixedDistanceCm = distanceCm; calibrated = ok; calibratedZoom = zoom; invalidate(); }
        void setCalibrationMode(boolean enabled) { calibrationMode = enabled; invalidate(); }
        void setLiveAutoState(float distanceCm, float zoom, boolean auto) { liveDistanceCm = distanceCm; liveZoom = zoom; autoDistance = auto; invalidate(); }
        void setThreadMeasurement(int fullLines, float threadsPerCm, float confidence, boolean stable, float[] centers, String status) {
            measuredFullLines = fullLines; measuredThreadsPerCm = threadsPerCm; measuredConfidence = confidence;
            measuredStable = stable; measuredCenters = centers == null ? new float[0] : centers.clone(); measurementStatus = status == null ? "" : status; invalidate();
        }
        RulerGeometry snapshotGeometry() {
            float cx = viewWidth / 2f, cy = viewHeight / 2f;
            float left = cx - rulerPixels / 2f, right = cx + rulerPixels / 2f, y = cy - 38f * density;
            return new RulerGeometry(left, right, y, viewWidth, viewHeight,
                    left >= 8f * density && right <= viewWidth - 8f * density);
        }
        void showFocusMarker(float x, float y) { focusX = x; focusY = y; focusTime = System.currentTimeMillis(); invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            RulerGeometry g = snapshotGeometry(); float cx = g.viewWidth / 2f, cy = g.viewHeight / 2f, y = g.y, len = g.right - g.left;
            canvas.drawRoundRect(new RectF(Math.max(6f*density,g.left-14f*density), y-44f*density,
                    Math.min(getWidth()-6f*density,g.right+14f*density), y+75f*density), 12f*density,12f*density,panelPaint);
            for (int s=0;s<SCAN_LINES;s++) { float off=(s-SCAN_LINES/2f)*2.2f*density; canvas.drawLine(g.left,y+off,g.right,y+off,scanPaint); }
            canvas.drawLine(g.left,y,g.right,y,shadowPaint); canvas.drawLine(g.left,y,g.right,y,linePaint);
            for (int i=0;i<=10;i++) { float x=g.left+len*i/10f; float t=(i==0||i==10)?13f*density:(i==5?10f*density:7f*density);
                canvas.drawLine(x,y-t,x,y+t,shadowPaint); canvas.drawLine(x,y-t,x,y+t,linePaint); }
            for (float n:measuredCenters) if(n>=0f&&n<=1f){ float x=g.left+len*n; canvas.drawLine(x,y-18f*density,x,y+18f*density,markerPaint); canvas.drawCircle(x,y,3.2f*density,markerPaint); }
            String title = calibrationMode
                    ? String.format(Locale.US,"BASE 1 cm • %.2f× • %.1f cm",calibratedZoom,fixedDistanceCm)
                    : calibrated
                    ? String.format(Locale.US,"AUTO 1 cm • %.2f× • %.1f cm %s",liveZoom,liveDistanceCm,autoDistance?"AUTO":"MANUAL")
                    : "CALIBRATE REAL 1 cm ONCE";
            if(!g.fits) title="AUTO 1 cm خارج الشاشة — غيّر Zoom/Distance";
            float tw=textPaint.measureText(title); canvas.drawText(title,Math.max(8f*density,cx-tw/2f),y-25f*density,textPaint);
            String count=measuredFullLines>0?String.format(Locale.US,"%d خطوط • %.1f n/cm • %s",measuredFullLines,measuredThreadsPerCm,measuredStable?"STABLE":"MEASURING")
                    :(!measurementStatus.isEmpty()?measurementStatus:"9 SCANS داخل Auto 1 cm");
            float cw=textPaint.measureText(count); canvas.drawText(count,Math.max(8f*density,cx-cw/2f),y+51f*density,textPaint);
            if(measuredStable&&measuredConfidence>0.42f) canvas.drawLine(g.left,y+61f*density,g.right,y+61f*density,stablePaint);
            float arm=19f*density; canvas.drawLine(cx-arm,cy,cx+arm,cy,linePaint); canvas.drawLine(cx,cy-arm,cx,cy+arm,linePaint);
            if(focusX>=0f&&System.currentTimeMillis()-focusTime<1200L){ float r=18f*density; canvas.drawCircle(focusX,focusY,r,focusPaint); postInvalidateOnAnimation(); }
        }
    }
}
