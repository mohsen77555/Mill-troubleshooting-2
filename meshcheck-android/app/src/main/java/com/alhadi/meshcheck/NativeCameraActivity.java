package com.alhadi.meshcheck;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.DisplayMetrics;
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
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class NativeCameraActivity extends ComponentActivity {
    public static final String EXTRA_CAPTURE_PATH = "meshcheck.capture_path";
    private static final int CAMERA_PERMISSION_REQUEST = 2201;

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Camera camera;
    private Button captureButton;
    private Button flashButton;
    private TextView zoomLabel;
    private ScaleGestureDetector scaleGestureDetector;
    private boolean torchOn;
    private boolean zoomGestureUsed;

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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(new RulerOverlayView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView topGuide = new TextView(this);
        topGuide.setText("Pinch للتقريب • اضغط على الخيوط للتركيز • مسطرة 1 cm مرجع بصري");
        topGuide.setTextColor(Color.WHITE);
        topGuide.setTextSize(13f);
        topGuide.setGravity(Gravity.CENTER);
        topGuide.setPadding(dp(12), dp(8), dp(12), dp(8));
        topGuide.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams guideParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        guideParams.setMargins(dp(10), dp(12), dp(10), 0);
        root.addView(topGuide, guideParams);

        LinearLayout zoomPanel = new LinearLayout(this);
        zoomPanel.setOrientation(LinearLayout.HORIZONTAL);
        zoomPanel.setGravity(Gravity.CENTER);
        zoomPanel.setPadding(dp(8), dp(6), dp(8), dp(6));
        zoomPanel.setBackgroundColor(0xA6071318);

        Button zoom1 = makeZoomButton("1×", 1f);
        Button zoom2 = makeZoomButton("2×", 2f);
        Button zoom3 = makeZoomButton("3×", 3f);
        Button zoom5 = makeZoomButton("5×", 5f);
        zoomLabel = new TextView(this);
        zoomLabel.setText("Zoom 1.0×");
        zoomLabel.setTextColor(Color.WHITE);
        zoomLabel.setTextSize(14f);
        zoomLabel.setGravity(Gravity.CENTER);
        zoomLabel.setPadding(dp(8), 0, dp(8), 0);

        zoomPanel.addView(zoom1, weightedButtonParams());
        zoomPanel.addView(zoom2, weightedButtonParams());
        zoomPanel.addView(zoom3, weightedButtonParams());
        zoomPanel.addView(zoom5, weightedButtonParams());
        zoomPanel.addView(zoomLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));

        FrameLayout.LayoutParams zoomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        zoomParams.setMargins(dp(8), 0, dp(8), dp(72));
        root.addView(zoomPanel, zoomParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(8), dp(8), dp(8), dp(12));
        controls.setBackgroundColor(0xAA071318);

        Button cancelButton = new Button(this);
        cancelButton.setText("رجوع");
        cancelButton.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        flashButton = new Button(this);
        flashButton.setText("Flash");
        flashButton.setEnabled(false);
        flashButton.setOnClickListener(v -> toggleTorch());

        captureButton = new Button(this);
        captureButton.setText("التقاط وتحليل");
        captureButton.setEnabled(false);
        captureButton.setOnClickListener(v -> capturePhoto());

        controls.addView(cancelButton, weightedButtonParams());
        controls.addView(flashButton, weightedButtonParams());
        controls.addView(captureButton, weightedButtonParams());

        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(controls, controlParams);

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

        setContentView(root);
    }

    private Button makeZoomButton(String label, float ratio) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12f);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setOnClickListener(v -> setZoomRatio(ratio));
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetResolution(new Size(2560, 1440))
                        .build();
                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, selector, preview, imageCapture);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                captureButton.setEnabled(true);
                flashButton.setEnabled(camera.getCameraInfo().hasFlashUnit());

                camera.getCameraInfo().getZoomState().observe(this, state -> {
                    if (state == null) return;
                    zoomLabel.setText(String.format(Locale.US, "Zoom %.1f×\nmax %.1f×", state.getZoomRatio(), state.getMaxZoomRatio()));
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
            Toast.makeText(this, "Focus", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            // Autofocus is best-effort; image capture still works on cameras without AF regions.
        }
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        flashButton.setText(torchOn ? "Flash ON" : "Flash");
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class RulerOverlayView extends View {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float rulerPixels;
        private final float density;

        RulerOverlayView(NativeCameraActivity context) {
            super(context);
            setWillNotDraw(false);
            setClickable(false);
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            density = metrics.density;
            float xdpi = metrics.xdpi;
            if (!(xdpi >= 100f && xdpi <= 1000f)) {
                xdpi = metrics.densityDpi;
            }
            rulerPixels = xdpi / 2.54f;
            shadowPaint.setColor(0xCC000000);
            shadowPaint.setStrokeWidth(5f * density);
            linePaint.setColor(Color.WHITE);
            linePaint.setStrokeWidth(2f * density);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(13f * density);
            textPaint.setFakeBoldText(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = 20f * density;
            float top = 72f * density;
            float right = left + rulerPixels;

            canvas.drawLine(left, top, right, top, shadowPaint);
            canvas.drawLine(left, top, right, top, linePaint);
            for (int index = 0; index <= 10; index++) {
                float x = left + rulerPixels * index / 10f;
                float tick = (index == 0 || index == 10) ? 12f * density : (index == 5 ? 9f * density : 6f * density);
                canvas.drawLine(x, top - tick, x, top + tick, shadowPaint);
                canvas.drawLine(x, top - tick, x, top + tick, linePaint);
            }
            canvas.drawText("1 cm  •  10 mm", left, top + 30f * density, textPaint);

            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float arm = 18f * density;
            canvas.drawLine(cx - arm, cy, cx + arm, cy, shadowPaint);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, shadowPaint);
            canvas.drawLine(cx - arm, cy, cx + arm, cy, linePaint);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, linePaint);
        }
    }
}
