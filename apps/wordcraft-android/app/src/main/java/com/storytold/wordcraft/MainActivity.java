package com.storytold.wordcraft;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String TAG = "WordCraft";
    private static final String HOST_DOMAIN = "wordcraft.app";
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri activeDocumentUri = null;
    private String activeDocumentName = null;
    private boolean isChoosingImage = false;

    public class BlobDownloadBridge {
        @JavascriptInterface
        public void processBase64Data(String base64Data, String filename, String mimeType) {
            saveDocumentDirect(base64Data, filename, mimeType);
        }

        @JavascriptInterface
        public void printPdfDocument(String base64Data, String documentName) {
            printPdf(base64Data, documentName);
        }
    }

    public static class PdfPrintAdapter extends PrintDocumentAdapter {
        private final File pdfFile;
        private final String documentName;

        public PdfPrintAdapter(File pdfFile, String documentName) {
            this.pdfFile = pdfFile;
            this.documentName = documentName;
        }

        @Override
        public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                             CancellationSignal cancellationSignal, LayoutResultCallback callback, Bundle extras) {
            if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                callback.onLayoutCancelled();
                return;
            }
            PrintDocumentInfo info = new PrintDocumentInfo.Builder(documentName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build();
            callback.onLayoutFinished(info, true);
        }

        @Override
        public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                            CancellationSignal cancellationSignal, WriteResultCallback callback) {
            try (InputStream in = new FileInputStream(pdfFile);
                 OutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) > 0) {
                    if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                        callback.onWriteCancelled();
                        return;
                    }
                    out.write(buffer, 0, bytesRead);
                }
                callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (Exception e) {
                callback.onWriteFailed(e.getMessage());
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        // Edge-to-edge layout across camera cutout/notch on Android 9+ (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        applyImmersiveMode();

        webView = new WebView(this);
        setContentView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        configureWebView();

        webView.loadUrl("https://" + HOST_DOMAIN + "/index.html");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    private void applyImmersiveMode() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }

        webView.addJavascriptInterface(new BlobDownloadBridge(), "AndroidBlobBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (HOST_DOMAIN.equalsIgnoreCase(url.getHost())) {
                    return serveAsset(url.getPath());
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "Page loaded: " + url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d(TAG, "[Console] " + message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                isChoosingImage = isImageChooserRequest(params);

                Intent intent;
                try {
                    intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(isChoosingImage ? "image/*" : "*/*");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION 
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION 
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (ActivityNotFoundException e) {
                    try {
                        // Fallback to default WebChromeClient intent if ACTION_OPEN_DOCUMENT is unavailable
                        intent = (params != null) ? params.createIntent() : new Intent(Intent.ACTION_GET_CONTENT).setType(isChoosingImage ? "image/*" : "*/*");
                        startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                    } catch (ActivityNotFoundException ex) {
                        filePathCallback = null;
                        Toast.makeText(MainActivity.this, "Cannot open file picker", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            handleDownload(url, contentDisposition, mimetype);
        });
    }

    private WebResourceResponse serveAsset(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || rawPath.equals("/")) {
            rawPath = "/index.html";
        }

        String assetPath = "wordcraft" + rawPath;
        try {
            InputStream stream = getAssets().open(assetPath);
            String mimeType = getMimeType(rawPath);

            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cross-Origin-Resource-Policy", "cross-origin");

            return new WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, stream);
        } catch (Exception e) {
            Log.e(TAG, "Asset not found: " + assetPath, e);
            return null;
        }
    }

    private String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private void handleDownload(String url, String contentDisposition, String mimetype) {
        try {
            if (url != null && url.startsWith("blob:")) {
                String guessedName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                if (guessedName == null || guessedName.isEmpty() || guessedName.equals("downloadfile")) {
                    guessedName = (activeDocumentName != null) ? activeDocumentName : "wordcraft_document.docx";
                }
                final String finalFileName = guessedName;
                final String finalMime = (mimetype != null && !mimetype.isEmpty()) ? mimetype : "application/octet-stream";

                String js = "(function() {"
                        + "  try {"
                        + "    fetch('" + url + "')"
                        + "      .then(function(r) { return r.blob(); })"
                        + "      .then(function(b) {"
                        + "        var reader = new FileReader();"
                        + "        reader.onloadend = function() {"
                        + "          if (window.AndroidBlobBridge) {"
                        + "            window.AndroidBlobBridge.processBase64Data(reader.result, '" + finalFileName + "', '" + finalMime + "');"
                        + "          }"
                        + "        };"
                        + "        reader.readAsDataURL(b);"
                        + "      })"
                        + "      .catch(function(e) { console.error('Blob fetch error', e); });"
                        + "  } catch (err) { console.error('Blob download script error', err); }"
                        + "})();";

                runOnUiThread(() -> webView.evaluateJavascript(js, null));
                return;
            }

            if (url != null && url.startsWith("data:")) {
                String guessedName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                saveDocumentDirect(url, guessedName, mimetype);
                return;
            }

            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Download error", e);
            Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean writeBytesToUri(Uri uri, byte[] bytes) {
        if (uri == null || bytes == null) return false;

        // Attempt 1: ParcelFileDescriptor with "wt" (write and truncate)
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "wt")) {
            if (pfd != null) {
                try (FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor())) {
                    fos.write(bytes);
                    fos.flush();
                    return true;
                }
            }
        } catch (Exception e1) {
            Log.w(TAG, "openFileDescriptor wt failed: " + e1.getMessage());
        }

        // Attempt 2: openOutputStream with "wt"
        try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
            if (os != null) {
                os.write(bytes);
                os.flush();
                return true;
            }
        } catch (Exception e2) {
            Log.w(TAG, "openOutputStream wt failed: " + e2.getMessage());
        }

        // Attempt 3: openOutputStream with "w"
        try (OutputStream os = getContentResolver().openOutputStream(uri, "w")) {
            if (os != null) {
                os.write(bytes);
                os.flush();
                return true;
            }
        } catch (Exception e3) {
            Log.w(TAG, "openOutputStream w failed: " + e3.getMessage());
        }

        // Attempt 4: openOutputStream default
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os != null) {
                os.write(bytes);
                os.flush();
                return true;
            }
        } catch (Exception e4) {
            Log.w(TAG, "openOutputStream default failed: " + e4.getMessage());
        }

        return false;
    }

    private void saveDocumentDirect(String base64Data, String filename, String mimeType) {
        try {
            if (base64Data == null || base64Data.isEmpty()) {
                return;
            }
            int commaIndex = base64Data.indexOf(',');
            if (commaIndex >= 0) {
                base64Data = base64Data.substring(commaIndex + 1);
            }
            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);

            if (filename == null || filename.isEmpty() || filename.equals("downloadfile")) {
                filename = (activeDocumentName != null) ? activeDocumentName : "wordcraft_document.docx";
            }
            if (mimeType == null || mimeType.isEmpty() || mimeType.equals("application/octet-stream")) {
                if (filename.endsWith(".docx")) {
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                } else if (filename.endsWith(".pdf")) {
                    mimeType = "application/pdf";
                } else {
                    mimeType = "application/octet-stream";
                }
            }

            // 1. Direct in-place overwrite if an active document URI exists and types match
            if (activeDocumentUri != null && activeDocumentName != null) {
                if (isDocumentFileName(activeDocumentName) && isCompatibleDocumentExtension(activeDocumentName, filename)) {
                    if (writeBytesToUri(activeDocumentUri, bytes)) {
                        final String docName = activeDocumentName;
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Saved: " + docName, Toast.LENGTH_SHORT).show());
                        return;
                    }
                }
            }

            // 2. Otherwise create new document in Downloads via MediaStore
            boolean saved = false;
            try {
                ContentValues values = new ContentValues();
                values.put("_display_name", filename);
                values.put("mime_type", mimeType);
                values.put("relative_path", Environment.DIRECTORY_DOWNLOADS);

                Uri downloadsUri = Uri.parse("content://media/external/downloads");
                Uri uri = getContentResolver().insert(downloadsUri, values);
                if (uri != null) {
                    if (writeBytesToUri(uri, bytes)) {
                        if (isDocumentFileName(filename)) {
                            activeDocumentUri = uri;
                            activeDocumentName = filename;
                        }
                        saved = true;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "MediaStore insert failed, falling back to direct write", e);
            }

            // 3. Fallback to direct Downloads folder write if needed
            if (!saved) {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File file = new File(dir, filename);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(bytes);
                    fos.flush();
                    saved = true;
                    if (isDocumentFileName(filename)) {
                        activeDocumentName = filename;
                    }
                }
            }

            final String finalName = (activeDocumentName != null) ? activeDocumentName : filename;
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Saved: " + finalName, Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            Log.e(TAG, "Save document error", e);
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Save error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
        }
    }

    public void printPdf(String base64Data, String documentName) {
        try {
            if (base64Data == null || base64Data.isEmpty()) return;
            int commaIndex = base64Data.indexOf(',');
            if (commaIndex >= 0) {
                base64Data = base64Data.substring(commaIndex + 1);
            }
            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);

            File cacheDir = getCacheDir();
            File tempPdf = new File(cacheDir, "wordcraft_print_" + System.currentTimeMillis() + ".pdf");
            try (FileOutputStream fos = new FileOutputStream(tempPdf)) {
                fos.write(bytes);
            }

            final String printJobName = (documentName != null && !documentName.isEmpty()) ? documentName : "WordCraft Document";
            runOnUiThread(() -> {
                PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                if (printManager != null) {
                    printManager.print(printJobName, new PdfPrintAdapter(tempPdf, printJobName),
                            new PrintAttributes.Builder().build());
                } else {
                    Toast.makeText(this, "Print service unavailable on device", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Print error", e);
            runOnUiThread(() -> Toast.makeText(this, "Print error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private String queryDisplayName(Uri uri) {
        if (uri == null) return null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex);
                }
            }
        } catch (Exception ignored) {}
        return uri.getLastPathSegment();
    }

    private boolean isImageChooserRequest(WebChromeClient.FileChooserParams params) {
        if (params == null) return false;
        String[] acceptTypes = params.getAcceptTypes();
        if (acceptTypes == null || acceptTypes.length == 0) return false;
        for (String type : acceptTypes) {
            if (type == null) continue;
            String lower = type.toLowerCase().trim();
            if (lower.startsWith("image/") 
                    || lower.equals(".png") || lower.equals(".jpg") || lower.equals(".jpeg") 
                    || lower.equals(".gif") || lower.equals(".webp") || lower.equals(".bmp") 
                    || lower.equals(".svg")) {
                return true;
            }
        }
        return false;
    }

    private boolean isDocumentFileName(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase().trim();
        return lower.endsWith(".docx") || lower.endsWith(".docm") || lower.endsWith(".dotx")
                || lower.endsWith(".dotm") || lower.endsWith(".doc") || lower.endsWith(".dot")
                || lower.endsWith(".odt") || lower.endsWith(".rtf") || lower.endsWith(".txt")
                || lower.endsWith(".md");
    }

    private boolean isImageFileName(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase().trim();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
                || lower.endsWith(".svg");
    }

    private boolean isCompatibleDocumentExtension(String sourceName, String targetName) {
        if (sourceName == null || targetName == null) return false;
        int dot1 = sourceName.lastIndexOf('.');
        int dot2 = targetName.lastIndexOf('.');
        if (dot1 < 0 || dot2 < 0) return false;
        String ext1 = sourceName.substring(dot1).toLowerCase().trim();
        String ext2 = targetName.substring(dot2).toLowerCase().trim();
        return ext1.equals(ext2);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri == null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                    uri = data.getClipData().getItemAt(0).getUri();
                }
                if (uri != null) {
                    String displayName = queryDisplayName(uri);
                    boolean isImage = isChoosingImage || isImageFileName(displayName);

                    if (!isImage && isDocumentFileName(displayName)) {
                        activeDocumentUri = uri;
                        activeDocumentName = displayName;
                        try {
                            final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                            getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        } catch (Exception e) {
                            Log.w(TAG, "takePersistableUriPermission: " + e.getMessage());
                        }
                    } else {
                        try {
                            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception e) {
                            Log.w(TAG, "takePersistableUriPermission read: " + e.getMessage());
                        }
                    }
                    results = new Uri[]{uri};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            isChoosingImage = false;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
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
}
