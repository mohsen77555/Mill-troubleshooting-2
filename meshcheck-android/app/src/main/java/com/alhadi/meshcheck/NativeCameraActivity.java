package com.alhadi.meshcheck;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
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
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * v0.18 high-accuracy camera workflow.
 * Gyroscope hold -> AF/AE metering hold -> 5-frame burst -> sharpest frame -> 20x20 crop -> metrology.
 */
public class NativeCameraActivity extends ComponentActivity implements SensorEventListener {
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

    public static final String EXTRA_PITCH_X_UM = "meshcheck.pitch_x_um";
    public static final String EXTRA_PITCH_Y_UM = "meshcheck.pitch_y_um";
    public static final String EXTRA_YARN_X_UM = "meshcheck.yarn_x_um";
    public static final String EXTRA_YARN_Y_UM = "meshcheck.yarn_y_um";
    public static final String EXTRA_OPENING_X_UM = "meshcheck.opening_x_um";
    public static final String EXTRA_OPENING_Y_UM = "meshcheck.opening_y_um";
    public static final String EXTRA_UNCERTAINTY_X_UM = "meshcheck.uncertainty_x_um";
    public static final String EXTRA_UNCERTAINTY_Y_UM = "meshcheck.uncertainty_y_um";
    public static final String EXTRA_QUALITY_SCORE = "meshcheck.quality_score";
    public static final String EXTRA_SHARPNESS_SCORE = "meshcheck.sharpness_score";
    public static final String EXTRA_BURST_SHARPNESS = "meshcheck.burst_sharpness";

    private static final int CAMERA_PERMISSION_REQUEST = 2201;
    private static final int CROP_REQUEST = 2202;
    private static final int GALLERY_REQUEST = 2203;
    private static final int BURST_COUNT = 5;
    private static final float MOTION_THRESHOLD_RAD_S = 0.12f;
    private static final long STABLE_REQUIRED_MS = 500L;

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Camera camera;
    private Button captureButton;
    private Button flashButton;
    private Button galleryButton;
    private TextView statusLabel;
    private TextView zoomLabel;
    private LensGuideOverlay overlay;
    private ScaleGestureDetector scaleGestureDetector;

    private boolean torchOn;
    private boolean zoomGestureUsed;
    private boolean cameraReady;
    private boolean burstRunning;
    private volatile float currentZoomRatio = 1f;
    private String pendingOriginalPath;
    private float lastBurstSharpness;

    private SensorManager sensorManager;
    private Sensor gyroscope;
    private volatile boolean motionDetected;
    private long stableSinceMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(18, 59, 80));
        buildUi();
        setupMotionSensor();
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

        overlay = new LensGuideOverlay(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView guide = new TextView(this);
        guide.setText("ضع عدسة/إطار 20×20 بالكامل داخل الصورة • Tap للتركيز • التطبيق يأخذ 5 صور ويختار الأوضح");
        guide.setTextColor(Color.WHITE);
        guide.setTextSize(13f);
        guide.setGravity(Gravity.CENTER);
        guide.setPadding(dp(8), dp(8), dp(8), dp(8));
        guide.setBackgroundColor(0xA0000000);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        gp.setMargins(dp(7), dp(9), dp(7), 0);
        root.addView(guide, gp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(8), dp(5), dp(8), dp(10));
        bottom.setBackgroundColor(0xD0071117);

        statusLabel = new TextView(this);
        statusLabel.setText("انتظر تشغيل الكاميرا...");
        statusLabel.setTextColor(Color.WHITE);
        statusLabel.setTextSize(12f);
        statusLabel.setGravity(Gravity.CENTER);
        statusLabel.setPadding(dp(4), 0, dp(4), dp(3));
        bottom.addView(statusLabel, matchWrap());

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
        zoomRow.addView(zoomLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f));
        bottom.addView(zoomRow, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        captureButton = new Button(this);
        captureButton.setText("Burst + قص");
        captureButton.setTextSize(10f);
        captureButton.setEnabled(false);
        captureButton.setOnClickListener(v -> captureForCrop());

        galleryButton = new Button(this);
        galleryButton.setText("صورة + قص");
        galleryButton.setTextSize(10f);
        galleryButton.setOnClickListener(v -> chooseImageForCrop());

        flashButton = new Button(this);
        flashButton.setText("FLASH");
        flashButton.setTextSize(10f);
        flashButton.setEnabled(false);
        flashButton.setOnClickListener(v -> toggleTorch());

        Button back = new Button(this);
        back.setText("رجوع");
        back.setTextSize(10f);
        back.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        actions.addView(captureButton, weighted());
        actions.addView(galleryButton, weighted());
        actions.addView(flashButton, weighted());
        actions.addView(back, weighted());
        bottom.addView(actions, matchWrap());

        root.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        setupTouchControls();
    }

    private void setupMotionSensor() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        stableSinceMs = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;
        float x = event.values[0], y = event.values[1], z = event.values[2];
        float speed = (float) Math.sqrt(x * x + y * y + z * z);
        boolean moving = speed > MOTION_THRESHOLD_RAD_S;
        long now = SystemClock.elapsedRealtime();
        if (moving) {
            motionDetected = true;
            stableSinceMs = 0L;
        } else {
            if (stableSinceMs == 0L) stableSinceMs = now;
            motionDetected = now - stableSinceMs < STABLE_REQUIRED_MS;
        }
        if (!burstRunning) runOnUiThread(this::updateCaptureState);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void updateCaptureState() {
        boolean stable = gyroscope == null || !motionDetected;
        captureButton.setEnabled(cameraReady && stable && !burstRunning);
        galleryButton.setEnabled(!burstRunning);
        if (burstRunning) return;
        if (!cameraReady) statusLabel.setText("انتظر تشغيل الكاميرا...");
        else if (!stable) statusLabel.setText("HOLD — ثبّت الهاتف للحظة قبل الالتقاط");
        else statusLabel.setText("✓ ثابت — Tap على الخيوط للتركيز ثم Burst + قص");
        overlay.setStable(stable);
    }

    private void setupTouchControls() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                zoomGestureUsed = true;
                return !burstRunning;
            }
            @Override public boolean onScale(@NonNull ScaleGestureDetector detector) {
                if (camera == null || burstRunning) return false;
                ZoomState state = camera.getCameraInfo().getZoomState().getValue();
                if (state == null) return false;
                setZoom(state.getZoomRatio() * detector.getScaleFactor());
                return true;
            }
        });
        previewView.setOnTouchListener((view, event) -> {
            if (burstRunning) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) zoomGestureUsed = false;
            scaleGestureDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && !zoomGestureUsed) {
                focusAt(event.getX(), event.getY());
                view.performClick();
            }
            return true;
        });
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(1), 0, dp(1), 0);
        return p;
    }

    private Button makeZoomButton(String text, float ratio) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(10f);
        b.setOnClickListener(v -> {
            if (!burstRunning) setZoom(ratio);
        });
        return b;
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
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                cameraReady = true;
                flashButton.setEnabled(camera.getCameraInfo().hasFlashUnit());
                camera.getCameraInfo().getZoomState().observe(this, state -> {
                    if (state == null) return;
                    currentZoomRatio = state.getZoomRatio();
                    zoomLabel.setText(String.format(Locale.US, "%.2f×", currentZoomRatio));
                });
                updateCaptureState();
            } catch (ExecutionException e) {
                Toast.makeText(this, "تعذر تشغيل الكاميرا: " + e.getMessage(), Toast.LENGTH_LONG).show();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Toast.makeText(this, "تعذر تشغيل الكاميرا: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void setZoom(float requested) {
        if (camera == null) return;
        ZoomState state = camera.getCameraInfo().getZoomState().getValue();
        if (state == null) return;
        float clamped = Math.max(state.getMinZoomRatio(), Math.min(state.getMaxZoomRatio(), requested));
        camera.getCameraControl().setZoomRatio(clamped);
    }

    private void focusAt(float x, float y) {
        if (camera == null) return;
        try {
            camera.getCameraControl().cancelFocusAndMetering();
            MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .disableAutoCancel()
                    .build();
            camera.getCameraControl().startFocusAndMetering(action);
            overlay.showFocus(x, y);
            statusLabel.setText("AF/AE HOLD — ثبّت الهاتف ثم التقط Burst");
        } catch (Exception ignored) {}
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit() || burstRunning) return;
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        flashButton.setText(torchOn ? "FLASH ON" : "FLASH");
    }

    private void captureForCrop() {
        if (imageCapture == null || !cameraReady || burstRunning) return;
        if (gyroscope != null && motionDetected) {
            Toast.makeText(this, "ثبّت الهاتف للحظة ثم التقط.", Toast.LENGTH_SHORT).show();
            return;
        }
        burstRunning = true;
        captureButton.setEnabled(false);
        galleryButton.setEnabled(false);
        statusLabel.setText("Burst 1/" + BURST_COUNT + " — لا تحرك الهاتف");
        List<File> frames = new ArrayList<>();
        captureBurstFrame(0, frames);
    }

    private void captureBurstFrame(int index, List<File> frames) {
        if (!burstRunning) {
            deleteFiles(frames, null);
            return;
        }
        if (gyroscope != null && motionDetected && index > 0) {
            deleteFiles(frames, null);
            burstRunning = false;
            statusLabel.setText("تم إلغاء Burst بسبب الاهتزاز — ثبّت الهاتف وأعد الالتقاط");
            updateCaptureState();
            return;
        }
        if (index >= BURST_COUNT) {
            finishBurst(frames);
            return;
        }

        statusLabel.setText("Burst " + (index + 1) + "/" + BURST_COUNT + " — HOLD");
        File output = new File(getCacheDir(), "meshcheck-burst-" + System.currentTimeMillis() + "-" + index + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(output).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                frames.add(output);
                captureBurstFrame(index + 1, frames);
            }

            @Override public void onError(@NonNull ImageCaptureException exception) {
                output.delete();
                deleteFiles(frames, null);
                burstRunning = false;
                updateCaptureState();
                Toast.makeText(NativeCameraActivity.this,
                        "تعذر Burst: " + exception.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void finishBurst(List<File> frames) {
        if (frames.isEmpty()) {
            burstRunning = false;
            updateCaptureState();
            return;
        }
        File best = null;
        float bestScore = -1f;
        for (File file : frames) {
            float score = fileSharpness(file);
            if (score > bestScore) {
                bestScore = score;
                best = file;
            }
        }
        if (best == null) best = frames.get(0);
        lastBurstSharpness = Math.max(0f, bestScore);
        deleteFiles(frames, best);
        pendingOriginalPath = best.getAbsolutePath();
        burstRunning = false;
        statusLabel.setText(String.format(Locale.US,
                "تم اختيار أوضح لقطة من %d • Sharpness %.1f — افتح القص", BURST_COUNT, lastBurstSharpness));
        launchCrop(pendingOriginalPath, currentZoomRatio);
    }

    private static void deleteFiles(List<File> files, File keep) {
        for (File file : files) if (file != null && (keep == null || !file.equals(keep))) file.delete();
    }

    private static float fileSharpness(File file) {
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 4;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap == null || bitmap.getWidth() < 30 || bitmap.getHeight() < 30) return 0f;
            int w = bitmap.getWidth(), h = bitmap.getHeight();
            int step = Math.max(2, Math.min(w, h) / 240);
            double sum = 0.0;
            int count = 0;
            for (int y = step; y < h - step; y += step) {
                for (int x = step; x < w - step; x += step) {
                    float c = luma(bitmap.getPixel(x, y));
                    float gx = Math.abs(luma(bitmap.getPixel(x + step, y)) - c);
                    float gy = Math.abs(luma(bitmap.getPixel(x, y + step)) - c);
                    sum += gx + gy;
                    count++;
                }
            }
            return count > 0 ? (float) (sum / count) : 0f;
        } catch (Exception ignored) {
            return 0f;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private static float luma(int color) {
        return 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color);
    }

    private void chooseImageForCrop() {
        if (burstRunning) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, GALLERY_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "تعذر فتح الصور.", Toast.LENGTH_LONG).show();
        }
    }

    private void copyGalleryImageAndCrop(Uri uri) {
        try {
            File output = new File(getCacheDir(), "meshcheck-gallery-original-" + System.currentTimeMillis() + ".jpg");
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream stream = new FileOutputStream(output)) {
                if (input == null) throw new IllegalStateException("Image stream unavailable");
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) stream.write(buffer, 0, read);
            }
            pendingOriginalPath = output.getAbsolutePath();
            lastBurstSharpness = fileSharpness(output);
            launchCrop(pendingOriginalPath, 1f);
        } catch (Exception e) {
            Toast.makeText(this, "تعذر تجهيز الصورة للقص: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void launchCrop(String path, float zoom) {
        Intent intent = new Intent(this, LensCropActivity.class);
        intent.putExtra(LensCropActivity.EXTRA_INPUT_PATH, path);
        intent.putExtra(LensCropActivity.EXTRA_ZOOM_RATIO, zoom);
        startActivityForResult(intent, CROP_REQUEST);
    }

    @Override @Deprecated
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == GALLERY_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                copyGalleryImageAndCrop(data.getData());
            }
            return;
        }
        if (requestCode == CROP_REQUEST) {
            if (pendingOriginalPath != null) {
                new File(pendingOriginalPath).delete();
                pendingOriginalPath = null;
            }
            if (resultCode == Activity.RESULT_OK && data != null) {
                data.putExtra(EXTRA_BURST_SHARPNESS, lastBurstSharpness);
                setResult(RESULT_OK, data);
                finish();
            } else {
                updateCaptureState();
                statusLabel.setText("تم إلغاء القص — يمكنك إعادة Burst أو اختيار صورة أخرى.");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else {
            Toast.makeText(this, "اسمح باستخدام الكاميرا ثم جرّب مرة أخرى. يمكنك أيضًا استخدام صورة + قص.", Toast.LENGTH_LONG).show();
            cameraReady = false;
            updateCaptureState();
        }
    }

    @Override
    protected void onDestroy() {
        burstRunning = false;
        if (pendingOriginalPath != null) new File(pendingOriginalPath).delete();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class LensGuideOverlay extends View {
        private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stablePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private boolean stable;
        private float focusX = -1f, focusY = -1f;
        private long focusTime;

        LensGuideOverlay(NativeCameraActivity context) {
            super(context);
            setWillNotDraw(false);
            density = getResources().getDisplayMetrics().density;
            guidePaint.setColor(0xFFFFD54F);
            guidePaint.setStyle(Paint.Style.STROKE);
            guidePaint.setStrokeWidth(3f * density);
            stablePaint.setColor(0xFF67E8D1);
            stablePaint.setStyle(Paint.Style.STROKE);
            stablePaint.setStrokeWidth(4f * density);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(12f * density);
            textPaint.setFakeBoldText(true);
            focusPaint.setColor(0xFF67E8D1);
            focusPaint.setStyle(Paint.Style.STROKE);
            focusPaint.setStrokeWidth(2f * density);
        }

        void setStable(boolean value) {
            stable = value;
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
            float side = Math.min(getWidth() * 0.78f, getHeight() * 0.48f);
            float cx = getWidth() / 2f;
            float cy = getHeight() * 0.46f;
            RectF box = new RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);
            Paint p = stable ? stablePaint : guidePaint;
            canvas.drawRoundRect(box, 10f * density, 10f * density, p);
            String label = stable ? "عدسة 20×20 داخل الإطار • STABLE" : "ضع العدسة كاملة هنا";
            canvas.drawText(label, Math.max(12f, box.left), Math.max(80f, box.top - 14f * density), textPaint);

            if (focusX >= 0f && System.currentTimeMillis() - focusTime < 1200L) {
                canvas.drawCircle(focusX, focusY, 24f * density, focusPaint);
                canvas.drawLine(focusX - 15f * density, focusY, focusX + 15f * density, focusY, focusPaint);
                canvas.drawLine(focusX, focusY - 15f * density, focusX, focusY + 15f * density, focusPaint);
                postInvalidateOnAnimation();
            }
        }
    }
}
