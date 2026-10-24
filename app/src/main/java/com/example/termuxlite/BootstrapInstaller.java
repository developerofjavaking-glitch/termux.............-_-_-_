package com.example.termuxlite;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.util.Log;
import android.util.Pair;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BootstrapInstaller {

    private static final String TAG = "BootstrapInstaller";

    public interface Callback {
        void onProgress(long current, long total, String message);
        void onStatus(String status);
        void onSuccess();
        void onError(String error);
    }

    public static boolean isInstalled(Context context) {
        File filesDir = context.getFilesDir();
        File usr = new File(filesDir, "usr");
        File pkg = new File(usr, "bin/pkg");
        File bash = new File(usr, "bin/bash");
        File apt = new File(usr, "bin/apt");
        return pkg.exists() && bash.exists() && apt.exists();
    }

    public static String getArch() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return "aarch64";
            if ("x86_64".equals(abi)) return "x86_64";
            if ("armeabi-v7a".equals(abi) || "armeabi".equals(abi)) return "arm";
            if ("x86".equals(abi)) return "i686";
        }
        return "aarch64";
    }

    public static void installAsync(final Context context, final Callback callback) {
        new Thread(() -> {
            try {
                install(context, callback);
            } catch (Exception e) {
                Log.e(TAG, "Bootstrap installation failed", e);
                callback.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }).start();
    }

    public static void install(Context context, Callback callback) throws Exception {
        String arch = getArch();
        Log.i(TAG, "Starting bootstrap installation for architecture: " + arch);

        File filesDir = context.getFilesDir();
        File stagingDir = new File(filesDir, "usr-staging");
        File prefixDir = new File(filesDir, "usr");
        File homeDir = new File(filesDir, "home");
        if (!homeDir.exists()) homeDir.mkdirs();

        File tempZip = new File(context.getCacheDir(), "bootstrap-" + arch + ".zip");
        if (tempZip.exists()) tempZip.delete();

        String[] downloadUrls = new String[]{
                "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-" + arch + ".zip",
                "https://github.com/termux/termux-packages/releases/download/bootstrap-2024.10.23-r1%2Bapt-android-7/bootstrap-" + arch + ".zip"
        };

        boolean downloaded = false;
        Exception lastException = null;

        for (String urlStr : downloadUrls) {
            try {
                callback.onStatus("Connecting to download server...");
                downloadWithProgress(urlStr, tempZip, callback);
                downloaded = true;
                break;
            } catch (Exception e) {
                Log.w(TAG, "Failed downloading from: " + urlStr, e);
                lastException = e;
                if (tempZip.exists()) tempZip.delete();
            }
        }

        if (!downloaded || !tempZip.exists() || tempZip.length() == 0) {
            throw new IOException("Failed to download bootstrap archive: " +
                    (lastException != null ? lastException.getMessage() : "unknown error"));
        }

        callback.onStatus("Extracting packages...");
        deleteRecursive(stagingDir);
        if (!stagingDir.mkdirs()) {
            throw new IOException("Failed to create staging directory: " + stagingDir);
        }

        List<Pair<String, String>> symlinks = new ArrayList<>();
        byte[] buffer = new byte[16384];

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(tempZip)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if ("SYMLINKS.txt".equals(name)) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        String[] parts = line.split("←");
                        if (parts.length == 2) {
                            symlinks.add(new Pair<>(parts[0].trim(), parts[1].trim()));
                        }
                    }
                } else {
                    File outFile = new File(stagingDir, name);
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        File parent = outFile.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            int read;
                            while ((read = zis.read(buffer)) != -1) {
                                fos.write(buffer, 0, read);
                            }
                        }
                        if (name.startsWith("bin/") || name.startsWith("libexec/") ||
                                name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods/")) {
                            try {
                                Os.chmod(outFile.getAbsolutePath(), 0755);
                            } catch (Exception ignored) {}
                        }
                    }
                }
                zis.closeEntry();
            }
        }

        callback.onStatus("Configuring symlinks and permissions...");
        for (Pair<String, String> link : symlinks) {
            String target = link.first;
            String relPath = link.second;
            if (relPath.startsWith("./")) relPath = relPath.substring(2);
            File linkFile = new File(stagingDir, relPath);
            File parent = linkFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (linkFile.exists()) linkFile.delete();
            try {
                Os.symlink(target, linkFile.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "Symlink creation failed for: " + linkFile + " -> " + target, e);
            }
        }

        callback.onStatus("Finalizing setup...");
        if (prefixDir.exists()) {
            deleteRecursive(prefixDir);
        }
        if (!stagingDir.renameTo(prefixDir)) {
            if (!prefixDir.exists()) prefixDir.mkdirs();
            File[] items = stagingDir.listFiles();
            if (items != null) {
                for (File item : items) {
                    item.renameTo(new File(prefixDir, item.getName()));
                }
            }
            stagingDir.delete();
        }

        File tmpDir = new File(prefixDir, "tmp");
        if (!tmpDir.exists()) tmpDir.mkdirs();
        try {
            Os.chmod(tmpDir.getAbsolutePath(), 0777);
        } catch (Exception ignored) {}

        if (tempZip.exists()) tempZip.delete();

        Log.i(TAG, "Termux bootstrap packages installed successfully!");
        callback.onSuccess();
    }

    private static void downloadWithProgress(String urlString, File destFile, Callback callback) throws IOException {
        String currentUrl = urlString;
        HttpURLConnection conn = null;

        for (int i = 0; i < 5; i++) {
            URL url = new URL(currentUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Termux-Installer/1.0");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location != null) {
                    if (location.startsWith("/")) {
                        currentUrl = url.getProtocol() + "://" + url.getHost() + location;
                    } else {
                        currentUrl = location;
                    }
                    continue;
                }
            }

            if (code == HttpURLConnection.HTTP_OK) {
                break;
            }
            conn.disconnect();
            throw new IOException("HTTP " + code + " returned from " + currentUrl);
        }

        if (conn == null) throw new IOException("Unable to establish connection");

        long contentLength = conn.getContentLength();
        if (contentLength <= 0) {
            String lenStr = conn.getHeaderField("Content-Length");
            if (lenStr != null) {
                try {
                    contentLength = Long.parseLong(lenStr);
                } catch (NumberFormatException ignored) {}
            }
        }

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[16384];
            long totalRead = 0;
            int read;
            long lastReportTime = 0;

            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                totalRead += read;

                long now = System.currentTimeMillis();
                if (now - lastReportTime > 250) {
                    lastReportTime = now;
                    int percent = contentLength > 0 ? (int) ((totalRead * 100) / contentLength) : -1;
                    String msg = percent >= 0 ?
                            "Downloading bootstrap (" + percent + "%)..." :
                            "Downloading bootstrap (" + (totalRead / (1024 * 1024)) + " MB)...";
                    callback.onProgress(totalRead, contentLength, msg);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    public static void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory == null || !fileOrDirectory.exists()) return;
        boolean isSymlink = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            isSymlink = java.nio.file.Files.isSymbolicLink(fileOrDirectory.toPath());
        }
        if (!isSymlink && fileOrDirectory.isDirectory()) {
            File[] files = fileOrDirectory.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }
}
