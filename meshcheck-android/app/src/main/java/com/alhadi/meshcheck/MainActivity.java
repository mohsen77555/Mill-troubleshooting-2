package com.alhadi.meshcheck;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Native shell for MeshCheck. */
public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 1101;
    private static final int CSV_EXPORT_REQUEST = 1102;
    private static final int CAMERA_PERMISSION_REQUEST = 1103;
    private static final int NATIVE_CAMERA_REQUEST = 1104;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private String pendingCsv;
    private PermissionRequest pendingCameraPermissionRequest;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });
        webView.addJavascriptInterface(new ExportBridge(), "MeshExport");
        webView.addJavascriptInterface(new NativeCameraBridge(), "MeshNativeCamera");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(Intent.createChooser(params.createIntent(), "اختر صورة المنخل"), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
            }
            @Override public void onPermissionRequest(PermissionRequest request) { runOnUiThread(() -> handleWebPermissionRequest(request)); }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingCameraPermissionRequest == request) pendingCameraPermissionRequest = null;
            }
        });
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private void launchNativeCamera() {
        try { startActivityForResult(new Intent(this, NativeCameraActivity.class), NATIVE_CAMERA_REQUEST); }
        catch (Exception e) { Toast.makeText(this, "تعذر فتح الكاميرا الأصلية: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void handleWebPermissionRequest(PermissionRequest request) {
        boolean wantsVideo = false;
        for (String resource : request.getResources()) if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) wantsVideo = true;
        if (!wantsVideo) { request.deny(); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}); return;
        }
        if (pendingCameraPermissionRequest != null) pendingCameraPermissionRequest.deny();
        pendingCameraPermissionRequest = request;
        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST || pendingCameraPermissionRequest == null) return;
        PermissionRequest request = pendingCameraPermissionRequest; pendingCameraPermissionRequest = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
        else request.deny();
    }

    @Override @Deprecated protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == NATIVE_CAMERA_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                String capturePath = data.getStringExtra(NativeCameraActivity.EXTRA_CAPTURE_PATH);
                if (capturePath != null) deliverNativeCapture(capturePath, data);
            } else if (webView != null) {
                webView.evaluateJavascript("window.MeshCheckNativeCameraCanceled && window.MeshCheckNativeCameraCanceled();", null);
            }
            return;
        }
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback == null) return;
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null; return;
        }
        if (requestCode == CSV_EXPORT_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingCsv != null) {
                try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
                    if (stream == null) throw new IOException("Output stream unavailable");
                    stream.write(pendingCsv.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {}
            }
            pendingCsv = null;
        }
    }

    private void deliverNativeCapture(String capturePath, Intent captureData) {
        File file = new File(capturePath);
        if (!file.isFile()) return;
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            String dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);

            float threadCountCm = captureData.getFloatExtra(NativeCameraActivity.EXTRA_THREAD_COUNT_CM, 0f);
            float xCount = captureData.getFloatExtra(NativeCameraActivity.EXTRA_THREAD_COUNT_X_CM, 0f);
            float yCount = captureData.getFloatExtra(NativeCameraActivity.EXTRA_THREAD_COUNT_Y_CM, 0f);
            int fullLineCount = captureData.getIntExtra(NativeCameraActivity.EXTRA_FULL_LINE_COUNT, 0);
            int fullX = captureData.getIntExtra(NativeCameraActivity.EXTRA_FULL_LINE_X, 0);
            int fullY = captureData.getIntExtra(NativeCameraActivity.EXTRA_FULL_LINE_Y, 0);
            float confidence = captureData.getFloatExtra(NativeCameraActivity.EXTRA_THREAD_COUNT_CONFIDENCE, 0f);
            boolean stable = captureData.getBooleanExtra(NativeCameraActivity.EXTRA_THREAD_COUNT_STABLE, false);
            boolean markerMode = captureData.getBooleanExtra(NativeCameraActivity.EXTRA_MARKER_MODE, false);
            boolean manualRoiMode = captureData.getBooleanExtra(NativeCameraActivity.EXTRA_MANUAL_ROI_MODE, false);
            float zoomRatio = captureData.getFloatExtra(NativeCameraActivity.EXTRA_ZOOM_RATIO, 1f);

            float pitchX = captureData.getFloatExtra(NativeCameraActivity.EXTRA_PITCH_X_UM, 0f);
            float pitchY = captureData.getFloatExtra(NativeCameraActivity.EXTRA_PITCH_Y_UM, 0f);
            float yarnX = captureData.getFloatExtra(NativeCameraActivity.EXTRA_YARN_X_UM, 0f);
            float yarnY = captureData.getFloatExtra(NativeCameraActivity.EXTRA_YARN_Y_UM, 0f);
            float openingX = captureData.getFloatExtra(NativeCameraActivity.EXTRA_OPENING_X_UM, 0f);
            float openingY = captureData.getFloatExtra(NativeCameraActivity.EXTRA_OPENING_Y_UM, 0f);
            float uncertaintyX = captureData.getFloatExtra(NativeCameraActivity.EXTRA_UNCERTAINTY_X_UM, 0f);
            float uncertaintyY = captureData.getFloatExtra(NativeCameraActivity.EXTRA_UNCERTAINTY_Y_UM, 0f);
            float quality = captureData.getFloatExtra(NativeCameraActivity.EXTRA_QUALITY_SCORE, 0f);
            float sharpness = captureData.getFloatExtra(NativeCameraActivity.EXTRA_SHARPNESS_SCORE, 0f);
            float burstSharpness = captureData.getFloatExtra(NativeCameraActivity.EXTRA_BURST_SHARPNESS, 0f);

            JSONArray counts = new JSONArray();
            if (xCount > 0f) counts.put((double) xCount);
            if (yCount > 0f) counts.put((double) yCount);
            JSONArray pitches = positiveArray(pitchX, pitchY);
            JSONArray yarns = positiveArray(yarnX, yarnY);
            JSONArray openings = positiveArray(openingX, openingY);
            JSONArray uncertainties = positiveArray(uncertaintyX, uncertaintyY);

            float averagePitch = averagePositive(pitchX, pitchY);
            float averageYarn = averagePositive(yarnX, yarnY);
            float averageOpening = averagePositive(openingX, openingY);
            float averageUncertainty = averagePositive(uncertaintyX, uncertaintyY);

            JSONObject measurement = new JSONObject();
            measurement.put("valid", stable && threadCountCm > 0f);
            String source = manualRoiMode && averageOpening > 0f
                    ? "lens_crop_20x20_high_accuracy"
                    : (manualRoiMode ? "manual_20x20_mm_roi" : (markerMode ? "marker_20x20_mm" : "native_camera"));
            measurement.put("source", source);
            measurement.put("threadsPerCm", threadCountCm);
            measurement.put("threadsPerInch", threadCountCm * 2.54f);
            measurement.put("threadCountsPerCm", counts);
            measurement.put("threadsXPerCm", xCount);
            measurement.put("threadsYPerCm", yCount);
            measurement.put("fullLinesInWindow", fullLineCount);
            measurement.put("fullLinesXIn20mm", fullX);
            measurement.put("fullLinesYIn20mm", fullY);
            measurement.put("physicalWindowMm", (manualRoiMode || markerMode) ? 20 : 10);
            measurement.put("confidence", confidence);
            measurement.put("stable", stable);
            measurement.put("zoomRatio", zoomRatio);

            measurement.put("pitchMicrons", averagePitch);
            measurement.put("pitchMicronsXY", pitches);
            measurement.put("pitchXMicrons", pitchX);
            measurement.put("pitchYMicrons", pitchY);
            measurement.put("yarnMicrons", averageYarn);
            measurement.put("yarnMicronsXY", yarns);
            measurement.put("yarnXMicrons", yarnX);
            measurement.put("yarnYMicrons", yarnY);
            measurement.put("openingMicrons", averageOpening);
            measurement.put("openingMicronsXY", openings);
            measurement.put("openingXMicrons", openingX);
            measurement.put("openingYMicrons", openingY);
            measurement.put("uncertaintyMicrons", averageUncertainty);
            measurement.put("uncertaintyMicronsXY", uncertainties);
            measurement.put("quality", quality);
            measurement.put("sharpness", sharpness);
            measurement.put("burstSharpness", burstSharpness);

            String script = "window.MeshCheckNativeMeasurement=" + measurement.toString() + ";"
                    + "window.MeshCheckNativeMeasurementPending=true;"
                    + "window.MeshCheckNativeCameraResult && window.MeshCheckNativeCameraResult("
                    + JSONObject.quote(dataUrl) + "," + JSONObject.quote(file.getName()) + ");";
            webView.evaluateJavascript(script, null);
        } catch (Exception e) {
            Toast.makeText(this, "تعذر قراءة نتيجة الكاميرا: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally { file.delete(); }
    }

    private static JSONArray positiveArray(float a, float b) {
        JSONArray array = new JSONArray();
        try {
            if (a > 0f) array.put((double) a);
            if (b > 0f) array.put((double) b);
        } catch (Exception ignored) {}
        return array;
    }

    private static float averagePositive(float a, float b) {
        if (a > 0f && b > 0f) return (a + b) * 0.5f;
        return Math.max(a, b);
    }

    @Override public void onBackPressed() { if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() {
        if (pendingCameraPermissionRequest != null) pendingCameraPermissionRequest.deny();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private final class NativeCameraBridge {
        @JavascriptInterface public void open() { runOnUiThread(MainActivity.this::launchNativeCamera); }
    }
    private final class ExportBridge {
        @JavascriptInterface public void saveCsv(String fileName, String csvContent) {
            runOnUiThread(() -> {
                pendingCsv = csvContent;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/csv");
                intent.putExtra(Intent.EXTRA_TITLE, fileName);
                try { startActivityForResult(intent, CSV_EXPORT_REQUEST); }
                catch (ActivityNotFoundException e) { pendingCsv = null; }
            });
        }
    }
}
