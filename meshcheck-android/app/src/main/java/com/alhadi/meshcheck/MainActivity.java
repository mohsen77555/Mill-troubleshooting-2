package com.alhadi.meshcheck;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A deliberately small native shell. The inspection and measurement workflow lives
 * in offline assets so the app does not send inspection images to a server.
 */
public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 1101;
    private static final int CSV_EXPORT_REQUEST = 1102;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private String pendingCsv;

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

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new ExportBridge(), "MeshExport");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
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
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    @Deprecated
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

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

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
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
