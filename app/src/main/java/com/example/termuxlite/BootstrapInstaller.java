package com.example.termuxlite;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.system.Os;
import android.util.Log;
import android.util.Pair;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Installs and configures the official Termux Linux bootstrap environment for BoomAux.
 * Supports download progress reporting (% and MB), mirror selection, and clean resets.
 */
public class BootstrapInstaller {

    private static final String TAG = "BoomAuxBootstrap";

    public static final String MIRROR_CLOUDFLARE = "https://packages-cf.termux.dev/apt/termux-main";
    public static final String MIRROR_OFFICIAL = "https://packages.termux.dev/apt/termux-main";
    public static final String MIRROR_TSINGHUA = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main";
    public static final String MIRROR_BFSU = "https://mirrors.bfsu.edu.cn/termux/apt/termux-main";

    public interface Callback {
        void onProgress(long current, long total, String message);
        void onStatus(String status);
        void onSuccess();
        void onError(String error);
    }

    public static boolean isInstalled(Context context) {
        File usr = new File(context.getFilesDir(), "usr");
        return new File(usr, "bin/pkg").exists()
                && new File(usr, "bin/bash").exists()
                && new File(usr, "bin/apt").exists();
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

    public static void resetEnvironment(Context context, boolean wipeHome, Callback callback) {
        new Thread(() -> {
            try {
                callback.onStatus("Resetting Linux environment...");
                File filesDir = context.getFilesDir();
                deleteRecursive(new File(filesDir, "usr"));
                deleteRecursive(new File(filesDir, "usr-staging"));
                if (wipeHome) {
                    deleteRecursive(new File(filesDir, "home"));
                    new File(filesDir, "home").mkdirs();
                }
                install(context, callback);
            } catch (Exception e) {
                Log.e(TAG, "Environment reset failed", e);
                callback.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }).start();
    }

    public static void install(Context context, Callback callback) throws Exception {
        String arch = getArch();
        Log.i(TAG, "Starting BoomAux bootstrap installation for architecture: " + arch);

        File filesDir = context.getFilesDir();
        File stagingDir = new File(filesDir, "usr-staging");
        File prefixDir = new File(filesDir, "usr");
        File homeDir = new File(filesDir, "home");
        if (!homeDir.exists()) homeDir.mkdirs();

        File tempZip = new File(context.getCacheDir(), "bootstrap-" + arch + ".zip");
        if (tempZip.exists()) tempZip.delete();

        boolean downloaded = false;
        Exception lastException = null;

        // 1. Check local candidates
        File[] localCandidates = new File[]{
                new File(context.getExternalFilesDir(null), "bootstrap-" + arch + ".zip"),
                new File("/sdcard/Download/bootstrap-" + arch + ".zip"),
                new File("/sdcard/bootstrap-" + arch + ".zip")
        };
        for (File candidate : localCandidates) {
            if (candidate.exists() && candidate.length() > 5 * 1024 * 1024) {
                try {
                    callback.onStatus("Found local bootstrap: " + candidate.getName());
                    copyFile(candidate, tempZip);
                    downloaded = true;
                    Log.i(TAG, "Using local bootstrap archive: " + candidate.getAbsolutePath());
                    break;
                } catch (Exception ignored) {}
            }
        }

        // 2. Download from high-speed CDN mirrors
        if (!downloaded) {
            String[] downloadUrls = new String[]{
                    "https://gh-proxy.com/https://github.com/termux/termux-packages/releases/download/bootstrap-2026.10.25-r1%2Bapt.android-7/bootstrap-" + arch + ".zip",
                    "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.10.25-r1%2Bapt.android-7/bootstrap-" + arch + ".zip",
                    "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-" + arch + ".zip",
                    "https://ghproxy.net/https://github.com/termux/termux-packages/releases/download/bootstrap-2026.10.25-r1%2Bapt.android-7/bootstrap-" + arch + ".zip"
            };

            for (String urlStr : downloadUrls) {
                try {
                    callback.onStatus("Connecting to BoomAux package mirrors...");
                    downloadWithProgress(urlStr, tempZip, callback);
                    downloaded = true;
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "Download attempt failed from: " + urlStr, e);
                    lastException = e;
                    if (tempZip.exists()) tempZip.delete();
                }
            }
        }

        if (!downloaded || !tempZip.exists() || tempZip.length() == 0) {
            throw new IOException("Failed downloading bootstrap: " +
                    (lastException != null ? lastException.getMessage() : "Network error"));
        }

        // 3. Extract to staging directory
        callback.onStatus("Extracting Linux base system...");
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

        // 4. Create symlinks
        callback.onStatus("Configuring symbolic links...");
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
                Log.w(TAG, "Symlink creation: " + linkFile + " -> " + target, e);
            }
        }

        // 5. Finalize directory placement
        callback.onStatus("Optimizing package manager & paths...");
        if (prefixDir.exists()) {
            deleteRecursive(prefixDir);
        }
        if (!stagingDir.renameTo(prefixDir)) {
            throw new IOException("Failed to rename " + stagingDir + " to " + prefixDir);
        }

        setupSystemDirsAndConfigs(context, prefixDir, homeDir);

        if (tempZip.exists()) tempZip.delete();

        Log.i(TAG, "BoomAux bootstrap successfully installed!");
        callback.onSuccess();
    }

    public static void setupSystemDirsAndConfigs(Context context, File prefixDir, File homeDir) {
        String prefix = prefixDir.getAbsolutePath();

        File tmpDir = new File(prefixDir, "tmp");
        if (!tmpDir.exists()) tmpDir.mkdirs();
        File varTmpDir = new File(prefixDir, "var/tmp");
        if (!varTmpDir.exists()) varTmpDir.mkdirs();

        // Essential apt & dpkg directories
        new File(prefixDir, "var/lib/dpkg/updates").mkdirs();
        new File(prefixDir, "var/lib/dpkg/info").mkdirs();
        new File(prefixDir, "var/lib/dpkg/alternatives").mkdirs();
        new File(prefixDir, "var/lib/apt/lists/partial").mkdirs();
        new File(prefixDir, "var/cache/apt/archives/partial").mkdirs();
        new File(prefixDir, "var/log/apt").mkdirs();
        new File(prefixDir, "etc/apt/apt.conf.d").mkdirs();
        new File(prefixDir, "etc/apt/preferences.d").mkdirs();
        new File(prefixDir, "etc/apt/sources.list.d").mkdirs();
        new File(prefixDir, "etc/termux").mkdirs();
        new File(homeDir, ".termux").mkdirs();
        new File(homeDir, ".config/pip").mkdirs();

        File dpkgStatus = new File(prefixDir, "var/lib/dpkg/status");
        if (!dpkgStatus.exists()) {
            try { writeStringToFile(dpkgStatus, ""); } catch (Exception ignored) {}
        }
        File dpkgAvailable = new File(prefixDir, "var/lib/dpkg/available");
        if (!dpkgAvailable.exists()) {
            try { writeStringToFile(dpkgAvailable, ""); } catch (Exception ignored) {}
        }

        // Configure APT with exact absolute paths
        String aptConfContent = "Dir \"" + prefix + "\";\n" +
                "Dir::State \"" + prefix + "/var/lib/apt\";\n" +
                "Dir::State::status \"" + prefix + "/var/lib/dpkg/status\";\n" +
                "Dir::Cache \"" + prefix + "/var/cache/apt\";\n" +
                "Dir::Etc \"" + prefix + "/etc/apt\";\n" +
                "Dir::Bin::methods \"" + prefix + "/lib/apt/methods\";\n" +
                "Dir::Bin::solvers \"" + prefix + "/lib/apt/solvers\";\n" +
                "Dir::Bin::dpkg \"" + prefix + "/bin/dpkg\";\n" +
                "DPkg::Options {\n" +
                "   \"--force-configure-any\";\n" +
                "   \"--force-depends\";\n" +
                "};\n" +
                "Acquire::Languages \"none\";\n" +
                "Acquire::http::Timeout \"30\";\n" +
                "Acquire::https::Timeout \"30\";\n" +
                "Acquire::https::Verify-Peer \"false\";\n" +
                "Acquire::https::Verify-Host \"false\";\n" +
                "Acquire::http::Verify-Peer \"false\";\n" +
                "Acquire::http::Verify-Host \"false\";\n" +
                "Acquire::https::CaInfo \"" + prefix + "/etc/tls/cert.pem\";\n";
        try {
            writeStringToFile(new File(prefixDir, "etc/apt/apt.conf"), aptConfContent);
        } catch (Exception ignored) {}

        // Configure curlrc
        String curlrc = "insecure\n" +
                "capath = " + prefix + "/etc/ssl/certs\n" +
                "cacert = " + prefix + "/etc/tls/cert.pem\n";
        try {
            writeStringToFile(new File(prefixDir, "etc/curlrc"), curlrc);
            writeStringToFile(new File(homeDir, ".curlrc"), curlrc);
        } catch (Exception ignored) {}

        // Read selected mirror preference or default to Cloudflare CDN
        SharedPreferences prefs = context.getSharedPreferences("boomaux_prefs", Context.MODE_PRIVATE);
        String mirrorUrl = prefs.getString("package_mirror", MIRROR_CLOUDFLARE);
        applyMirrorConfig(prefixDir, homeDir, mirrorUrl);

        // Ensure binaries have executable permissions
        File binDir = new File(prefixDir, "bin");
        File[] bins = binDir.listFiles();
        if (bins != null) {
            for (File b : bins) {
                try {
                    Os.chmod(b.getAbsolutePath(), 0755);
                } catch (Exception ignored) {}
            }
        }
    }

    public static void updateMirror(Context context, String mirrorUrl) {
        File filesDir = context.getFilesDir();
        File prefixDir = new File(filesDir, "usr");
        File homeDir = new File(filesDir, "home");
        if (prefixDir.exists()) {
            applyMirrorConfig(prefixDir, homeDir, mirrorUrl);
        }
    }

    private static void applyMirrorConfig(File prefixDir, File homeDir, String mirrorUrl) {
        String sourcesList = "deb " + mirrorUrl + " stable main\n";
        try {
            writeStringToFile(new File(prefixDir, "etc/apt/sources.list"), sourcesList);
            writeStringToFile(new File(prefixDir, "etc/termux/chosen_mirror"), mirrorUrl + "\n");
            writeStringToFile(new File(homeDir, ".termux/chosen_mirror"), mirrorUrl + "\n");
        } catch (Exception ignored) {}
    }

    private static SSLSocketFactory sPermissiveSSLSocketFactory = null;

    private static synchronized SSLSocketFactory getPermissiveSSLSocketFactory() {
        if (sPermissiveSSLSocketFactory != null) return sPermissiveSSLSocketFactory;
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            sPermissiveSSLSocketFactory = sc.getSocketFactory();
        } catch (Exception ignored) {}
        return sPermissiveSSLSocketFactory;
    }

    private static void downloadWithProgress(String urlString, File destFile, Callback callback) throws IOException {
        String currentUrl = urlString;
        HttpURLConnection conn = null;

        for (int i = 0; i < 10; i++) {
            URL url = new URL(currentUrl);
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
                SSLSocketFactory sf = getPermissiveSSLSocketFactory();
                if (sf != null) {
                    httpsConn.setSSLSocketFactory(sf);
                }
                httpsConn.setHostnameVerifier((hostname, session) -> true);
            }
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "*/*");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(45000);
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
            throw new IOException("HTTP " + code + " returned from server");
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
                if (now - lastReportTime > 200) {
                    lastReportTime = now;
                    double currentMB = totalRead / (1024.0 * 1024.0);
                    int percent = contentLength > 0 ? (int) ((totalRead * 100) / contentLength) : -1;
                    String msg;
                    if (contentLength > 0) {
                        double totalMB = contentLength / (1024.0 * 1024.0);
                        msg = String.format(java.util.Locale.US, "Downloading BoomAux: %.1f MB / %.1f MB (%d%%)", currentMB, totalMB, percent);
                    } else {
                        msg = String.format(java.util.Locale.US, "Downloading BoomAux: %.1f MB...", currentMB);
                    }
                    callback.onProgress(totalRead, contentLength, msg);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    public static void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory == null) return;
        boolean isSymlink = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            isSymlink = java.nio.file.Files.isSymbolicLink(fileOrDirectory.toPath());
        }
        if (!isSymlink && !fileOrDirectory.exists()) return;
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

    private static void copyFile(File src, File dest) throws IOException {
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
        }
    }

    private static void writeStringToFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
