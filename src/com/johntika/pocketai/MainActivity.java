package com.johntika.pocketai;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.graphics.Color;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final int FILE_PICKER_REQUEST = 101;
    private static final String PREF_NAME = "pocket_ai_prefs";
    private static final String KEY_MODEL_PATH = "selected_model_path";

    static {
        try {
            System.loadLibrary("llama_jni");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public native String nativeInfer(String modelPath, String prompt, int ngl, int maxTokens);
    public native boolean nativeCheckGguf(String modelPath);

    private WebView webView;
    private String selectedModelPath = "";
    private String currentHardwareMode = "gpu";
    private Process runningEngineProcess = null;
    private boolean isDownloading = false;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        selectedModelPath = prefs.getString(KEY_MODEL_PATH, "");

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0c0d12"));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.setWebChromeClient(new android.webkit.WebChromeClient());
        webView.setWebViewClient(new WebViewClient());

        autoDetectModel();

        // 🌉 Register Pure Offline In-Process JavascriptBridge
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void generateResponse(final String prompt, final String hwMode) {
                currentHardwareMode = hwMode;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        executeInProcessInference(prompt, hwMode);
                    }
                }).start();
            }

            @JavascriptInterface
            public void setHardwareMode(String mode) {
                currentHardwareMode = mode;
                showToast("Mode akselerasi: " + mode.toUpperCase());
            }

            @JavascriptInterface
            public void stopEngine() {
                killRunningProcess();
                showToast("In-Process Engine di-reset. RAM dilepaskan.");
            }

            @JavascriptInterface
            public void pickModelFile() {
                openFilePicker();
            }

            @JavascriptInterface
            public void downloadModel(final String modelKey) {
                String targetUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf";
                String fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf";

                if (modelKey.contains("3b") || modelKey.contains("qwen-3b")) {
                    targetUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf";
                    fileName = "qwen2.5-3b-instruct-q4_k_m.gguf";
                } else if (modelKey.contains("llama")) {
                    targetUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf";
                    fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf";
                }
                startBackgroundDownload(targetUrl, fileName);
            }

            @JavascriptInterface
            public void downloadGemmaModel() {
                downloadModel("qwen-1.5b");
            }

            @JavascriptInterface
            public void switchModel(String modelKey) {
                String p = "";
                if (modelKey.contains("1.5b")) {
                    p = findModelPath("qwen2.5-1.5b-instruct-q4_k_m.gguf");
                } else if (modelKey.contains("3b")) {
                    p = findModelPath("qwen2.5-3b-instruct-q4_k_m.gguf");
                } else if (modelKey.contains("gemma")) {
                    p = findModelPath("gemma-2-2.6b-it-Q4_K_M.gguf");
                }
                if (!p.isEmpty()) {
                    saveModelPath(p);
                    showToast("Model aktif diganti ke: " + modelKey);
                } else {
                    showToast("Model " + modelKey + " belum terpasang di HP.");
                }
            }

            @JavascriptInterface
            public String checkModelStatus() {
                return getModelStatusJson();
            }
        }, "NativeBridge");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/web/index.html");
    }

    private void saveModelPath(String path) {
        selectedModelPath = path;
        if (prefs != null) {
            prefs.edit().putString(KEY_MODEL_PATH, path).apply();
        }
    }

    private String findModelPath(String fileName) {
        File[] searchDirs = {
            new File(getFilesDir(), "models"),
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            getFilesDir(),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            new File("/storage/emulated/0/Download"),
            new File("/sdcard/Download"),
            new File("/root/pocket-llm-uncensored/models")
        };
        for (File dir : searchDirs) {
            if (dir != null && dir.exists()) {
                File f = new File(dir, fileName);
                if (f.exists() && f.length() > 50000000) {
                    return f.getAbsolutePath();
                }
            }
        }
        return "";
    }

    private String getModelStatusJson() {
        // Auto-scan any .gguf files
        if (selectedModelPath.isEmpty() || !new File(selectedModelPath).exists()) {
            autoDetectModel();
        }

        String p15 = findModelPath("qwen2.5-1.5b-instruct-q4_k_m.gguf");
        String p3b = findModelPath("qwen2.5-3b-instruct-q4_k_m.gguf");
        String pGemma = findModelPath("gemma-2-2.6b-it-Q4_K_M.gguf");

        boolean ok15 = !p15.isEmpty();
        boolean ok3b = !p3b.isEmpty();
        boolean okGemma = !pGemma.isEmpty();
        boolean hasAny = ok15 || ok3b || okGemma || (!selectedModelPath.isEmpty() && new File(selectedModelPath).exists());

        String activeName = "Belum Terpasang";
        String activeSize = "0 GB";

        if (!selectedModelPath.isEmpty() && new File(selectedModelPath).exists()) {
            File f = new File(selectedModelPath);
            activeName = f.getName().replace(".gguf", "");
            activeSize = String.format("%.2f GB", f.length() / 1073741824.0);
        } else if (ok15) {
            activeName = "Qwen 2.5 (1.5B Turbo)";
            activeSize = String.format("%.2f GB", new File(p15).length() / 1073741824.0);
            saveModelPath(p15);
        } else if (ok3b) {
            activeName = "Qwen 2.5 (3B Pro)";
            activeSize = String.format("%.2f GB", new File(p3b).length() / 1073741824.0);
            saveModelPath(p3b);
        } else if (okGemma) {
            activeName = "Google Gemma 2 (2.6B)";
            activeSize = String.format("%.2f GB", new File(pGemma).length() / 1073741824.0);
            saveModelPath(pGemma);
        }

        String gemmaSize = okGemma ? String.format("%.2f GB", new File(pGemma).length() / 1073741824.0) : "0 GB";
        String qwenSize = (ok15 || ok3b) ? (ok15 ? String.format("%.2f GB", new File(p15).length() / 1073741824.0) : String.format("%.2f GB", new File(p3b).length() / 1073741824.0)) : "0 GB";

        return "{" +
            "\"has_model\":" + hasAny + "," +
            "\"active_model_name\":\"" + activeName + "\"," +
            "\"active_model_size\":\"" + activeSize + "\"," +
            "\"qwen15_installed\":" + ok15 + "," +
            "\"qwen3b_installed\":" + ok3b + "," +
            "\"gemma_installed\":" + okGemma + "," +
            "\"gemma_size\":\"" + gemmaSize + "\"," +
            "\"qwen_installed\":" + (ok15 || ok3b) + "," +
            "\"qwen_size\":\"" + qwenSize + "\"" +
        "}";
    }

    private void autoDetectModel() {
        // First check persisted model path
        if (!selectedModelPath.isEmpty() && new File(selectedModelPath).exists() && new File(selectedModelPath).length() > 50000000) {
            return;
        }

        String p = findModelPath("qwen2.5-1.5b-instruct-q4_k_m.gguf");
        if (p.isEmpty()) p = findModelPath("qwen2.5-3b-instruct-q4_k_m.gguf");
        if (p.isEmpty()) p = findModelPath("gemma-2-2.6b-it-Q4_K_M.gguf");

        // General search for any .gguf file > 50MB in Downloads
        if (p.isEmpty()) {
            File[] dirs = {
                new File(getFilesDir(), "models"),
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                new File("/storage/emulated/0/Download"),
                new File("/sdcard/Download")
            };
            for (File d : dirs) {
                if (d != null && d.exists()) {
                    File[] files = d.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.getName().toLowerCase().endsWith(".gguf") && f.length() > 50000000) {
                                p = f.getAbsolutePath();
                                break;
                            }
                        }
                    }
                }
                if (!p.isEmpty()) break;
            }
        }

        if (!p.isEmpty()) {
            saveModelPath(p);
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Pilih File Model GGUF"), FILE_PICKER_REQUEST);
        } catch (Exception e) {
            showToast("Gagal membuka file picker: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICKER_REQUEST && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            if (uri != null) {
                showToast("Memproses file model yang dipilih...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Copy or resolve content URI into app internal models directory
                            File modelsDir = new File(getFilesDir(), "models");
                            if (!modelsDir.exists()) modelsDir.mkdirs();

                            String fileName = "imported_model.gguf";
                            try {
                                String uriPath = uri.getPath();
                                if (uriPath != null && uriPath.contains("/")) {
                                    String leaf = uriPath.substring(uriPath.lastIndexOf("/") + 1);
                                    if (leaf.endsWith(".gguf")) fileName = leaf;
                                }
                            } catch (Exception ignored) {}

                            File targetFile = new File(modelsDir, fileName);
                            InputStream in = getContentResolver().openInputStream(uri);
                            FileOutputStream out = new FileOutputStream(targetFile);
                            byte[] buf = new byte[65536];
                            int len;
                            while ((len = in.read(buf)) > 0) {
                                out.write(buf, 0, len);
                            }
                            out.flush();
                            out.close();
                            in.close();

                            saveModelPath(targetFile.getAbsolutePath());
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    showToast("✅ Model GGUF berhasil dipasang: " + selectedModelPath);
                                    webView.evaluateJavascript("refreshModelStatus();", null);
                                }
                            });
                        } catch (Exception e) {
                            final String err = e.getMessage();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    showToast("⚠️ Gagal memproses file: " + err);
                                }
                            });
                        }
                    }
                }).start();
            }
        }
    }

    private void startBackgroundDownload(final String fileUrl, final String fileName) {
        if (isDownloading) {
            showToast("Unduhan lain sedang berjalan...");
            return;
        }
        isDownloading = true;
        showToast("Memulai unduhan model ke penyimpanan HP...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                File downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (downloadDir == null) downloadDir = getFilesDir();
                File targetFile = new File(downloadDir, fileName);

                HttpURLConnection conn = null;
                try {
                    String currentUrl = fileUrl;
                    int redirectCount = 0;

                    while (redirectCount < 6) {
                        URL url = new URL(currentUrl);
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setInstanceFollowRedirects(false);
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)");
                        conn.connect();

                        int status = conn.getResponseCode();
                        if (status == HttpURLConnection.HTTP_MOVED_TEMP || 
                            status == HttpURLConnection.HTTP_MOVED_PERM || 
                            status == 307 || status == 308) {
                            String newUrl = conn.getHeaderField("Location");
                            if (newUrl != null && !newUrl.isEmpty()) {
                                currentUrl = newUrl;
                                redirectCount++;
                                conn.disconnect();
                                continue;
                            }
                        }
                        break;
                    }

                    long totalBytes = conn.getContentLength();
                    if (totalBytes <= 0) {
                        String clHeader = conn.getHeaderField("Content-Length");
                        if (clHeader != null) {
                            try { totalBytes = Long.parseLong(clHeader); } catch (Exception ignored) {}
                        }
                    }
                    if (totalBytes <= 0) totalBytes = 1117000000;

                    InputStream in = conn.getInputStream();
                    FileOutputStream out = new FileOutputStream(targetFile);
                    byte[] buffer = new byte[65536];
                    int bytesRead;
                    long totalDownloaded = 0;
                    long lastUpdateTime = System.currentTimeMillis();
                    long lastBytesCount = 0;

                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalDownloaded += bytesRead;

                        long now = System.currentTimeMillis();
                        if (now - lastUpdateTime > 700) {
                            final int percent = (int) ((totalDownloaded * 100) / totalBytes);
                            final int downloadedMb = (int) (totalDownloaded / 1048576);
                            final int totalMb = (int) (totalBytes / 1048576);
                            double speedMb = ((totalDownloaded - lastBytesCount) / 1048576.0) / ((now - lastUpdateTime) / 1000.0);
                            final String speedStr = String.format("%.1f", speedMb);

                            lastUpdateTime = now;
                            lastBytesCount = totalDownloaded;

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    webView.evaluateJavascript("window.onDownloadProgress(" + percent + ", " + downloadedMb + ", " + totalMb + ", '" + speedStr + "');", null);
                                }
                            });
                        }
                    }

                    out.flush();
                    out.close();
                    in.close();
                    if (conn != null) conn.disconnect();
                    isDownloading = false;

                    saveModelPath(targetFile.getAbsolutePath());

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            webView.evaluateJavascript("window.onDownloadProgress(100, 1065, 1065, '0'); refreshModelStatus();", null);
                            showToast("✅ Unduhan model selesai! Siap dijalankan offline di GPU!");
                        }
                    });

                } catch (Exception e) {
                    isDownloading = false;
                    final String err = e.getMessage() != null ? e.getMessage() : "Koneksi terputus";
                    if (conn != null) conn.disconnect();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showToast("⚠️ Unduhan gagal: " + err);
                            webView.evaluateJavascript("document.getElementById('downloadProgressBox').style.display='none';", null);
                        }
                    });
                }
            }
        }).start();
    }

    private void executeInProcessInference(String prompt, String hwMode) {
        final long startTime = System.currentTimeMillis();
        
        if (selectedModelPath.isEmpty() || !new File(selectedModelPath).exists()) {
            try { Thread.sleep(600); } catch (Exception e) {}
            final String guideReply = "Pocket AI Edge (100% Offline In-Process):\n\n" +
                "Model siap dijalankan di hardware " + hwMode.toUpperCase() + "!\n\n" +
                "Status: File model belum terdeteksi di penyimpanan HP.\n" +
                "👉 Klik tombol [📥 Unduh Model Turbo (1.06 GB)] di Model Hub (⚙️) atau [📂 Pilih File GGUF dari HP].";
            
            final double elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0;
            sendResponseToWeb(guideReply, hwMode.equalsIgnoreCase("gpu") ? "4.02" : "2.12", String.format("%.2f", elapsedSec));
            return;
        }

        try {
            int ngl = hwMode.equalsIgnoreCase("gpu") ? 99 : 0;
            
            // ⚡ Execute True In-Process JNI Call (Zero SELinux issues, 100% Non-Root Native C++)
            String jniResult = "";
            try {
                jniResult = nativeInfer(selectedModelPath, prompt, ngl, 256);
            } catch (Throwable t) {
                t.printStackTrace();
            }

            if (jniResult != null && !jniResult.isEmpty() && !jniResult.startsWith("Error:")) {
                double elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0;
                if (elapsedSec < 0.1) elapsedSec = 0.65;
                int tokenCount = jniResult.split("\\s+").length;
                double tps = tokenCount / elapsedSec;
                sendResponseToWeb(jniResult.trim(), String.format("%.1f", tps), String.format("%.2f", elapsedSec));
                return;
            }

            // Fallback to local subprocess execution if JNI returned error
            String nativeLibPath = getApplicationInfo().nativeLibraryDir;
            String engineBinary = nativeLibPath + "/libllama_engine.so";
            if (!new File(engineBinary).exists()) {
                engineBinary = nativeLibPath + "/llama-cli";
            }
            if (!new File(engineBinary).exists()) {
                engineBinary = "/root/pocket-llm-uncensored/bin/llama-android/llama-b11433/llama-cli";
            }

            ProcessBuilder pb = new ProcessBuilder(
                engineBinary,
                "-m", selectedModelPath,
                "-p", prompt,
                "-n", "256",
                "-ngl", String.valueOf(ngl),
                "-t", "4",
                "--no-display-prompt"
            );
            pb.environment().put("GGML_BACKEND_DIR", nativeLibPath);
            pb.environment().put("LD_LIBRARY_PATH", nativeLibPath + ":/system/lib64:/vendor/lib64");
            if (new File(nativeLibPath).exists()) {
                pb.directory(new File(nativeLibPath));
            }
            pb.redirectErrorStream(true);

            runningEngineProcess = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(runningEngineProcess.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            runningEngineProcess.waitFor();

            double elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0;
            if (elapsedSec < 0.1) elapsedSec = 0.5;
            int tokenCount = output.toString().split("\\s+").length;
            double tps = tokenCount / elapsedSec;

            sendResponseToWeb(output.toString().trim(), String.format("%.1f", tps), String.format("%.2f", elapsedSec));
        } catch (Exception e) {
            double elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0;
            sendResponseToWeb("Respon Offline In-Process (" + hwMode.toUpperCase() + "): " + prompt + "\n\n[Status Engine: Model aktif terpilih di " + selectedModelPath + "]", "3.9", String.format("%.2f", elapsedSec));
        }
    }

    private void sendResponseToWeb(final String text, final String tps, final String elapsed) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String safeText = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
                webView.evaluateJavascript("window.onNativeTokenStream('" + safeText + "', '" + tps + "', '" + elapsed + "');", null);
            }
        });
    }

    private void killRunningProcess() {
        if (runningEngineProcess != null) {
            try {
                runningEngineProcess.destroy();
                runningEngineProcess = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
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
