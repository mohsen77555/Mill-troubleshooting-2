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

/** v0.14: automatic 20x20 mm physical marker mode. */
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

    private static final int CAMERA_PERMISSION_REQUEST = 2201;
    private static final int SCAN_LINES = 9;
    private static final float PHYSICAL_SIDE_CM = 2.0f;

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Camera camera;
    private Button captureButton;
    private Button flashButton;
    private TextView statusLabel;
    private TextView resultLabel;
    private TextView zoomLabel;
    private MarkerOverlay overlay;
    private ScaleGestureDetector scaleGestureDetector;
    private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();
    private final ThreadCountConsensus.Stabilizer stabilizerX = new ThreadCountConsensus.Stabilizer(14, 7);
    private final ThreadCountConsensus.Stabilizer stabilizerY = new ThreadCountConsensus.Stabilizer(14, 7);

    private boolean torchOn;
    private boolean zoomGestureUsed;
    private volatile float currentZoomRatio = 1f;
    private volatile int markerStableFrames;
    private float[] previousMarkerNormalized = new float[0];

    private final Object measurementLock = new Object();
    private float lastX, lastY, lastConfidence;
    private int lastFullX, lastFullY;
    private boolean lastStable;
    private boolean markerDetected;

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

        overlay = new MarkerOverlay(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView guide = new TextView(this);
        guide.setText("ضع إطار 20×20 mm فوق المنخل • حاذِ أضلاعه مع الخيوط • لا معايرة ولا مسافة");
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
        statusLabel.setText("ابحث عن العلامات الأربع...");
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
            DetectorFrame detectorFrame = makeDetectorFrame(image);
            Marker20mmDetector.Result marker = Marker20mmDetector.detect(
                    detectorFrame.gray, detectorFrame.width, detectorFrame.height);
            if (!marker.ok) {
                markerDetected = false;
                markerStableFrames = 0;
                previousMarkerNormalized = new float[0];
                stabilizerX.reset(); stabilizerY.reset();
                runOnUiThread(() -> {
                    statusLabel.setText("20×20 marker: " + marker.reason);
                    resultLabel.setText("X: --  •  Y: --");
                    captureButton.setEnabled(false);
                    overlay.clearMarker(marker.reason);
                });
                return;
            }

            Marker20mmDetector.Point[] sourceCorners = scaleMarker(marker.corners, detectorFrame.step);
            boolean markerStable = updateMarkerStability(sourceCorners, image.getWidth(), image.getHeight());
            float[] viewCorners = sourceCornersToView(sourceCorners, image);
            runOnUiThread(() -> overlay.setMarker(viewCorners, marker.confidence, markerStable));

            if (!markerStable) {
                stabilizerX.reset(); stabilizerY.reset();
                runOnUiThread(() -> {
                    statusLabel.setText("HOLD — ثبّت الهاتف حتى يثبت مربع 20×20");
                    resultLabel.setText("X: --  •  Y: --");
                    captureButton.setEnabled(false);
                });
                return;
            }

            DirectionMeasurement x = measureDirection(image, sourceCorners, true);
            DirectionMeasurement y = measureDirection(image, sourceCorners, false);
            if (!x.ok && !y.ok) {
                runOnUiThread(() -> {
                    statusLabel.setText("Marker ثابت، لكن الخيوط غير واضحة — اضغط على القماش للتركيز");
                    captureButton.setEnabled(false);
                });
                return;
            }

            ThreadCountConsensus.Snapshot sx = x.ok ? stabilizerX.push(x.frame) : stabilizerX.current();
            ThreadCountConsensus.Snapshot sy = y.ok ? stabilizerY.push(y.frame) : stabilizerY.current();
            acceptMeasurements(x, y, sx, sy, marker.confidence);
        } catch (Exception e) {
            runOnUiThread(() -> statusLabel.setText("تعذر تحليل marker: " + e.getMessage()));
        } finally {
            image.close();
        }
    }

    private void acceptMeasurements(DirectionMeasurement x, DirectionMeasurement y,
                                    ThreadCountConsensus.Snapshot sx,
                                    ThreadCountConsensus.Snapshot sy,
                                    float markerConfidence) {
        float vx = sx.threadsPerCm > 0f ? sx.threadsPerCm : (x.ok ? x.frame.threadsPerCm : 0f);
        float vy = sy.threadsPerCm > 0f ? sy.threadsPerCm : (y.ok ? y.frame.threadsPerCm : 0f);
        boolean stableX = !x.ok || sx.stable;
        boolean stableY = !y.ok || sy.stable;
        boolean stable = (vx > 0f || vy > 0f) && stableX && stableY;
        float confidence = Math.min(markerConfidence,
                Math.max(sx.confidence, sy.confidence));
        int fullX = x.ok ? x.frame.currentFullLineCount : 0;
        int fullY = y.ok ? y.frame.currentFullLineCount : 0;

        synchronized (measurementLock) {
            lastX = vx; lastY = vy; lastFullX = fullX; lastFullY = fullY;
            lastStable = stable; lastConfidence = confidence; markerDetected = true;
        }

        runOnUiThread(() -> {
            statusLabel.setText(stable ? "✓ MARKER 20×20 DETECTED • القياس ثابت"
                    : "MARKER 20×20 DETECTED • جارٍ تثبيت القراءة...");
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

    private DetectorFrame makeDetectorFrame(ImageProxy image) {
        int step = Math.max(1, (int) Math.ceil(image.getWidth() / 520.0));
        int w = image.getWidth() / step;
        int h = image.getHeight() / step;
        byte[] gray = new byte[w * h];
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
        for (int y = 0; y < h; y++) {
            int sy = y * step;
            for (int x = 0; x < w; x++) {
                int sx = x * step;
                int index = sy * rowStride + sx * pixelStride;
                gray[y * w + x] = index < buffer.limit() ? buffer.get(index) : 0;
            }
        }
        return new DetectorFrame(gray, w, h, step);
    }

    private Marker20mmDetector.Point[] scaleMarker(Marker20mmDetector.Point[] p, int step) {
        Marker20mmDetector.Point[] out = new Marker20mmDetector.Point[4];
        for (int i = 0; i < 4; i++) out[i] = new Marker20mmDetector.Point(p[i].x * step, p[i].y * step);
        return out;
    }

    private boolean updateMarkerStability(Marker20mmDetector.Point[] q, int width, int height) {
        float[] now = new float[8];
        for (int i = 0; i < 4; i++) {
            now[i * 2] = q[i].x / width;
            now[i * 2 + 1] = q[i].y / height;
        }
        if (previousMarkerNormalized.length == 8) {
            float sum = 0f;
            for (int i = 0; i < 8; i++) sum += Math.abs(now[i] - previousMarkerNormalized[i]);
            float motion = sum / 8f;
            markerStableFrames = motion < 0.008f ? markerStableFrames + 1 : 0;
        } else markerStableFrames = 0;
        previousMarkerNormalized = now;
        return markerStableFrames >= 2;
    }

    private float[] sourceCornersToView(Marker20mmDetector.Point[] q, ImageProxy image) {
        int sw = image.getWidth(), sh = image.getHeight();
        int rotation = ((image.getImageInfo().getRotationDegrees() % 360) + 360) % 360;
        int rw = (rotation == 90 || rotation == 270) ? sh : sw;
        int rh = (rotation == 90 || rotation == 270) ? sw : sh;
        float scale = Math.min(previewView.getWidth() / (float) rw, previewView.getHeight() / (float) rh);
        float ox = (previewView.getWidth() - rw * scale) / 2f;
        float oy = (previewView.getHeight() - rh * scale) / 2f;
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            float rx, ry;
            float sx = q[i].x, sy = q[i].y;
            switch (rotation) {
                case 90: rx = sh - 1 - sy; ry = sx; break;
                case 180: rx = sw - 1 - sx; ry = sh - 1 - sy; break;
                case 270: rx = sy; ry = sw - 1 - sx; break;
                default: rx = sx; ry = sy; break;
            }
            out[i * 2] = ox + rx * scale;
            out[i * 2 + 1] = oy + ry * scale;
        }
        return out;
    }

    private void resetMeasurement() {
        stabilizerX.reset(); stabilizerY.reset();
        markerStableFrames = 0; previousMarkerNormalized = new float[0];
        synchronized (measurementLock) {
            lastX = lastY = lastConfidence = 0f; lastFullX = lastFullY = 0;
            lastStable = false; markerDetected = false;
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
        if (imageCapture == null) return;
        synchronized (measurementLock) {
            if (!markerDetected || !lastStable) {
                Toast.makeText(this, "انتظر حتى تثبت قراءة Marker 20×20.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        captureButton.setEnabled(false);
        File output = new File(getCacheDir(), "meshcheck-marker20-" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(output).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Intent result = new Intent();
                result.putExtra(EXTRA_CAPTURE_PATH, output.getAbsolutePath());
                result.putExtra(EXTRA_ZOOM_RATIO, currentZoomRatio);
                result.putExtra(EXTRA_FIXED_DISTANCE_CM, 0f);
                result.putExtra(EXTRA_FIXED_CALIBRATED, true);
                result.putExtra(EXTRA_RULER_BASE_PX_1X, 0f);
                result.putExtra(EXTRA_MARKER_MODE, true);
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
    private static float distance(Marker20mmDetector.Point a, Marker20mmDetector.Point b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static final class DetectorFrame {
        final byte[] gray; final int width, height, step;
        DetectorFrame(byte[] gray, int width, int height, int step) {
            this.gray = gray; this.width = width; this.height = height; this.step = step;
        }
    }

    private static final class DirectionMeasurement {
        final boolean ok; final ThreadCountConsensus.FrameResult frame;
        DirectionMeasurement(boolean ok, ThreadCountConsensus.FrameResult frame) { this.ok = ok; this.frame = frame; }
        static DirectionMeasurement fail() { return new DirectionMeasurement(false, null); }
    }

    /** Homography from unit square to detected 20x20 marker quadrilateral. */
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

    private static final class MarkerOverlay extends View {
        private final Paint markerPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stablePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint focusPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float[] corners=new float[0];
        private float xDensity,yDensity;
        private boolean stable;
        private float markerConfidence;
        private String message="";
        private float focusX=-1,focusY=-1; private long focusTime;

        MarkerOverlay(NativeCameraActivity c) {
            super(c); setWillNotDraw(false);
            float d=getResources().getDisplayMetrics().density;
            markerPaint.setColor(0xFFFFD54F); markerPaint.setStyle(Paint.Style.STROKE); markerPaint.setStrokeWidth(3f*d);
            stablePaint.setColor(0xFF67E8D1); stablePaint.setStyle(Paint.Style.STROKE); stablePaint.setStrokeWidth(4f*d);
            textPaint.setColor(Color.WHITE); textPaint.setTextSize(13f*d); textPaint.setFakeBoldText(true);
            focusPaint.setColor(0xFF67E8D1); focusPaint.setStyle(Paint.Style.STROKE); focusPaint.setStrokeWidth(2f*d);
        }
        void setMarker(float[] c,float confidence,boolean isStable){corners=c==null?new float[0]:c.clone();markerConfidence=confidence;stable=isStable;message="";invalidate();}
        void clearMarker(String msg){corners=new float[0];stable=false;message=msg;invalidate();}
        void setResults(float x,float y,boolean s){xDensity=x;yDensity=y;stable=s;invalidate();}
        void showFocus(float x,float y){focusX=x;focusY=y;focusTime=System.currentTimeMillis();invalidate();}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            if(corners.length==8){
                Path p=new Path();p.moveTo(corners[0],corners[1]);p.lineTo(corners[2],corners[3]);p.lineTo(corners[4],corners[5]);p.lineTo(corners[6],corners[7]);p.close();
                canvas.drawPath(p,stable?stablePaint:markerPaint);
                for(int i=0;i<4;i++)canvas.drawCircle(corners[i*2],corners[i*2+1],8f,getResources().getDisplayMetrics().density>2?stable?stablePaint:markerPaint:markerPaint);
                String t=String.format(Locale.US,"20×20 mm • %.0f%%",markerConfidence*100f);
                canvas.drawText(t,corners[0],Math.max(30f,corners[1]-14f),textPaint);
                if(xDensity>0f||yDensity>0f){
                    String r=String.format(Locale.US,"X %.1f • Y %.1f n/cm",xDensity,yDensity);
                    canvas.drawText(r,corners[6],Math.min(getHeight()-30f,corners[7]+28f),textPaint);
                }
            }
            if(!message.isEmpty())canvas.drawText(message,24f,getHeight()/2f,textPaint);
            if(focusX>=0&&System.currentTimeMillis()-focusTime<1200){canvas.drawCircle(focusX,focusY,28f,focusPaint);postInvalidateOnAnimation();}
        }
    }
}
