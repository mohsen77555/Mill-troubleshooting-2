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

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Native shell for MeshCheck. The app stays offline. The primary camera flow is
 * now a native CameraX activity; WebView camera remains only as a browser fallback.
 */
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
    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });

        webView.addJavascriptInterface(new ExportBridge(), "MeshExport");
        webView.addJavascriptInterface(new NativeCameraBridge(), "MeshNativeCamera");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = callback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(Intent.createChooser(intent, "اختر صورة المنخل"), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException exception) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, "لا يوجد تطبيق لاختيار الصور.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermissionRequest(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingCameraPermissionRequest == request) {
                    pendingCameraPermissionRequest = null;
                }
            }
        });

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private void launchNativeCamera() {
        try {
            Intent intent = new Intent(this, NativeCameraActivity.class);
            startActivityForResult(intent, NATIVE_CAMERA_REQUEST);
        } catch (Exception exception) {
            Toast.makeText(this, "تعذر فتح الكاميرا الأصلية: " + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void handleWebPermissionRequest(PermissionRequest request) {
        boolean wantsVideo = false;
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                wantsVideo = true;
                break;
            }
        }

        if (!wantsVideo) {
            request.deny();
            return;
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            return;
        }

        if (pendingCameraPermissionRequest != null) {
            pendingCameraPermissionRequest.deny();
        }
        pendingCameraPermissionRequest = request;
        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST || pendingCameraPermissionRequest == null) {
            return;
        }

        PermissionRequest request = pendingCameraPermissionRequest;
        pendingCameraPermissionRequest = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
        } else {
            request.deny();
            Toast.makeText(this, "يجب السماح بالكاميرا لاستخدام الفحص المباشر.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @Deprecated
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == NATIVE_CAMERA_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                String capturePath = data.getStringExtra(NativeCameraActivity.EXTRA_CAPTURE_PATH);
                if (capturePath != null) {
                    deliverNativeCapture(capturePath);
                }
            } else if (webView != null) {
                webView.evaluateJavascript("window.MeshCheckNativeCameraCanceled && window.MeshCheckNativeCameraCanceled();", null);
            }
            return;
        }

        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback == null) {
                return;
            }
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
            return;
        }

        if (requestCode == CSV_EXPORT_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingCsv != null) {
                try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
                    if (stream == null) {
                        throw new IOException("Output stream unavailable");
                    }
                    stream.write(pendingCsv.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "تم حفظ ملف CSV.", Toast.LENGTH_SHORT).show();
                } catch (IOException exception) {
                    Toast.makeText(this, "تعذر حفظ ملف CSV.", Toast.LENGTH_LONG).show();
                }
            }
            pendingCsv = null;
        }
    }

    private void deliverNativeCapture(String capturePath) {
        File file = new File(capturePath);
        if (!file.isFile()) {
            Toast.makeText(this, "لم يتم العثور على صورة الكاميرا.", Toast.LENGTH_LONG).show();
            return;
        }

        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            String base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            String dataUrl = "data:image/jpeg;base64," + base64;
            String script = "window.MeshCheckNativeCameraResult && window.MeshCheckNativeCameraResult("
                    + JSONObject.quote(dataUrl) + "," + JSONObject.quote(file.getName()) + ");";
            webView.evaluateJavascript(script, null);
        } catch (IOException exception) {
            Toast.makeText(this, "تعذر قراءة صورة الكاميرا.", Toast.LENGTH_LONG).show();
        } finally {
            // The WebView has received its own copy; keep cache clean.
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (pendingCameraPermissionRequest != null) {
            pendingCameraPermissionRequest.deny();
            pendingCameraPermissionRequest = null;
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    private final class NativeCameraBridge {
        @JavascriptInterface
        public void open() {
            runOnUiThread(MainActivity.this::launchNativeCamera);
        }
    }

    private final class ExportBridge {
        @JavascriptInterface
        public void saveCsv(String fileName, String csvContent) {
            runOnUiThread(() -> {
                pendingCsv = csvContent;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/csv");
                intent.putExtra(Intent.EXTRA_TITLE, fileName);
                try {
                    startActivityForResult(intent, CSV_EXPORT_REQUEST);
                } catch (ActivityNotFoundException exception) {
                    pendingCsv = null;
                    Toast.makeText(MainActivity.this, "لا يوجد تطبيق لحفظ الملف.", Toast.LENGTH_LONG).show();
                }
            });
        }
    }
}
