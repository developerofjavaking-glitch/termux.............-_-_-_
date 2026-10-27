package com.example.termuxlite;

import android.content.Context;
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
 * Installs the official Termux bootstrap Linux environment for BoomAux.
 *
 * Configures apt, dpkg, pkg, pip, and SSL certificates so any package or
 * Python library can be installed directly without path or certificate issues.
 */
public class BootstrapInstaller {

    private static final String TAG = "BoomAuxBootstrap";

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

        // Check for locally placed bootstrap file first (offline / manual fallback)
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

        // Multiple fast global CDN mirrors with SSL resilience
        if (!downloaded) {
            String[] downloadUrls = new String[]{
                    // Cloudflare high-speed CDN mirror (accessible worldwide without throttling)
                    "https://gh-proxy.com/https://github.com/termux/termux-packages/releases/download/bootstrap-2026.10.25-r1%2Bapt.android-7/bootstrap-" + arch + ".zip",
                    // Official direct GitHub release asset
                    "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.10.25-r1%2Bapt.android-7/bootstrap-" + arch + ".zip",
                    // Latest release endpoint
                    "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-" + arch + ".zip",
                    // Alternative CDN mirror
                    "https://ghproxy.net/https://github.com/termux/termux-packages/releases/download/bootstrap-2026.10.25-r1%2Bapt.android-7/bootstrap-" + arch + ".zip"
            };

            for (String urlStr : downloadUrls) {
                try {
                    callback.onStatus("Connecting to BoomAux package servers...");
                    downloadWithProgress(urlStr, tempZip, callback);
                    downloaded = true;
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "Failed downloading from: " + urlStr, e);
                    lastException = e;
                    if (tempZip.exists()) tempZip.delete();
                }
            }
        }

        if (!downloaded || !tempZip.exists() || tempZip.length() == 0) {
            throw new IOException("Failed to download bootstrap archive: " +
                    (lastException != null ? lastException.getMessage() : "network connection failed"));
        }

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

        callback.onStatus("Creating symlinks...");
        for (Pair<String, String> link : symlinks) {
            String target = link.first;
            String relPath = link.second;
            if (relPath.startsWith("./")) relPath = relPath.substring(2);

            // Rewrite target if it points to hardcoded package paths
            if (target.contains("/data/data/com.termux/files/usr")) {
                target = target.replace("/data/data/com.termux/files/usr", prefixDir.getAbsolutePath());
            } else if (target.contains("/data/user/0/com.termux/files/usr")) {
                target = target.replace("/data/user/0/com.termux/files/usr", prefixDir.getAbsolutePath());
            } else if (target.contains("/data/data/com.bomax.talha.bdcteator/files/usr")) {
                target = target.replace("/data/data/com.bomax.talha.bdcteator/files/usr", prefixDir.getAbsolutePath());
            } else if (target.contains("/data/user/0/com.bomax.talha.bdcteator/files/usr")) {
                target = target.replace("/data/user/0/com.bomax.talha.bdcteator/files/usr", prefixDir.getAbsolutePath());
            } else if (target.contains("/data/data/com.termux/files")) {
                target = target.replace("/data/data/com.termux/files", filesDir.getAbsolutePath());
            } else if (target.contains("/data/user/0/com.termux/files")) {
                target = target.replace("/data/user/0/com.termux/files", filesDir.getAbsolutePath());
            }

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

        callback.onStatus("Optimizing package manager & paths...");
        patchScriptsInDir(stagingDir, prefixDir.getAbsolutePath());

        if (prefixDir.exists()) {
            deleteRecursive(prefixDir);
        }
        if (!stagingDir.renameTo(prefixDir)) {
            throw new IOException("Failed to move " + stagingDir + " to " + prefixDir);
        }

        setupSystemDirsAndConfigs(context, prefixDir, homeDir);

        if (tempZip.exists()) tempZip.delete();

        Log.i(TAG, "BoomAux bootstrap installed successfully!");
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
        new File(prefixDir, "etc/pip.conf").delete();
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

        // Configure APT with exact absolute paths so it always finds its directories
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

        // Configure curlrc to allow seamless connectivity even with older Android system certificates
        String curlrc = "insecure\n" +
                "capath = " + prefix + "/etc/ssl/certs\n" +
                "cacert = " + prefix + "/etc/tls/cert.pem\n";
        try {
            writeStringToFile(new File(prefixDir, "etc/curlrc"), curlrc);
            writeStringToFile(new File(homeDir, ".curlrc"), curlrc);
        } catch (Exception ignored) {}

        // Configure stable official primary Cloudflare CDN mirror
        String sourcesList = "deb https://packages-cf.termux.dev/apt/termux-main stable main\n";
        try {
            writeStringToFile(new File(prefixDir, "etc/apt/sources.list"), sourcesList);
        } catch (Exception ignored) {}

        // Set chosen mirror in both system and home config so pkg doesn't trigger mirror testing
        String chosenMirror = "https://packages-cf.termux.dev/apt/termux-main\n";
        try {
            writeStringToFile(new File(prefixDir, "etc/termux/chosen_mirror"), chosenMirror);
            writeStringToFile(new File(homeDir, ".termux/chosen_mirror"), chosenMirror);
        } catch (Exception ignored) {}

        // Configure pip to use PyPI with trusted host fallbacks to fix certificate issues on all Android versions
        String pipConf = "[global]\n" +
                "cert = " + prefix + "/etc/tls/cert.pem\n" +
                "trusted-host = pypi.org pypi.python.org files.pythonhosted.org\n" +
                "disable-pip-version-check = true\n" +
                "timeout = 60\n";
        try {
            writeStringToFile(new File(prefixDir, "etc/pip.conf"), pipConf);
            writeStringToFile(new File(homeDir, ".config/pip/pip.conf"), pipConf);
        } catch (Exception ignored) {}

        // Ensure libboomaux-redirect.so is installed in $PREFIX/lib
        try {
            copyNativeRedirectLibrary(context, prefixDir);
        } catch (Exception e) {
            Log.w(TAG, "Error copying redirect so: " + e.getMessage());
        }

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

    private static void copyNativeRedirectLibrary(Context context, File prefixDir) {
        File libDir = new File(prefixDir, "lib");
        if (!libDir.exists()) libDir.mkdirs();

        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        String[] candidateNames = new String[]{
                "libboomaux-redirect.so",
                "libboomaxe-redirect.so"
        };

        for (String name : candidateNames) {
            File src = new File(nativeLibDir, name);
            if (src.exists() && src.length() > 0) {
                File dest = new File(libDir, "libboomaux-redirect.so");
                try {
                    copyFile(src, dest);
                    Os.chmod(dest.getAbsolutePath(), 0755);
                    Log.i(TAG, "Copied native redirect library to " + dest.getAbsolutePath());
                    break;
                } catch (Exception ignored) {}
            }
        }
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

    private static void patchScriptsInDir(File dir, String targetPrefix) {
        if (!dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                patchScriptsInDir(f, targetPrefix);
            } else if (f.isFile() && f.length() < 256 * 1024) {
                patchScriptFile(f, targetPrefix);
            }
        }
    }

    private static void patchScriptFile(File file, String targetPrefix) {
        try {
            byte[] bytes;
            try (FileInputStream fis = new FileInputStream(file)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                while ((r = fis.read(buf)) != -1) {
                    baos.write(buf, 0, r);
                }
                bytes = baos.toByteArray();
            }

            if (bytes.length > 2 && bytes[0] == '#' && bytes[1] == '!') {
                String content = new String(bytes, StandardCharsets.UTF_8);
                boolean changed = false;

                if (content.contains("/data/data/com.termux/files/usr")) {
                    content = content.replace("/data/data/com.termux/files/usr", targetPrefix);
                    changed = true;
                }
                if (content.contains("/data/user/0/com.termux/files/usr")) {
                    content = content.replace("/data/user/0/com.termux/files/usr", targetPrefix);
                    changed = true;
                }
                if (content.contains("/data/data/com.bomax.talha.bdcteator/files/usr")) {
                    content = content.replace("/data/data/com.bomax.talha.bdcteator/files/usr", targetPrefix);
                    changed = true;
                }
                if (content.contains("/data/user/0/com.bomax.talha.bdcteator/files/usr")) {
                    content = content.replace("/data/user/0/com.bomax.talha.bdcteator/files/usr", targetPrefix);
                    changed = true;
                }
                if (content.contains("/data/data/com.bomax.talha.bdcreator/files/usr")) {
                    content = content.replace("/data/data/com.bomax.talha.bdcreator/files/usr", targetPrefix);
                    changed = true;
                }
                if (content.contains("/data/user/0/com.bomax.talha.bdcreator/files/usr")) {
                    content = content.replace("/data/user/0/com.bomax.talha.bdcreator/files/usr", targetPrefix);
                    changed = true;
                }

                if (changed) {
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        fos.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                    try { Os.chmod(file.getAbsolutePath(), 0755); } catch (Exception ignored) {}
                }
            }
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
                if (now - lastReportTime > 250) {
                    lastReportTime = now;
                    int percent = contentLength > 0 ? (int) ((totalRead * 100) / contentLength) : -1;
                    String msg = percent >= 0 ?
                            "Downloading BoomAux environment (" + percent + "%)..." :
                            "Downloading BoomAux environment (" + (totalRead / (1024 * 1024)) + " MB)...";
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

    private static void writeStringToFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
