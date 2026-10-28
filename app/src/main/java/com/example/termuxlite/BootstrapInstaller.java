package com.example.termuxlite;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs and configures the official Termux bootstrap for BoomAux.
 *
 * IMPORTANT: the official bootstrap and package repository are built for the package name
 * "com.termux" (prefix /data/data/com.termux/files/usr), so the app's applicationId MUST be
 * "com.termux". No path patching is done here on purpose.
 *
 * Security: the bootstrap is downloaded only from the official GitHub release over normal,
 * verified TLS and is checked against a pinned SHA-256 before anything is extracted.
 * To update the bootstrap, change BOOTSTRAP_TAG and the SHA-256 values together.
 */
public class BootstrapInstaller {

    private static final String TAG = "BoomAuxBootstrap";

    public static final String REQUIRED_PACKAGE_NAME = "com.termux";

    private static final String BOOTSTRAP_TAG = "bootstrap-2026.10.25-r1%2Bapt.android-7";
    private static final String BOOTSTRAP_BASE_URL =
            "https://github.com/termux/termux-packages/releases/download/" + BOOTSTRAP_TAG + "/bootstrap-";

    private static final long MIN_FREE_BYTES = 400L * 1024 * 1024;

    public static final String MIRROR_CLOUDFLARE = "https://packages-cf.termux.dev/apt/termux-main";
    public static final String MIRROR_OFFICIAL = "https://packages.termux.dev/apt/termux-main";
    public static final String MIRROR_TSINGHUA = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main";
    public static final String MIRROR_BFSU = "https://mirrors.bfsu.edu.cn/termux/apt/termux-main";

    private static final AtomicBoolean sInstalling = new AtomicBoolean(false);
    private static volatile Callback sCallback;

    public interface Callback {
        void onProgress(long current, long total, String message);
        void onStatus(String status);
        void onSuccess();
        void onError(String error);
    }

    // ---------------------------------------------------------------- state

    public static boolean isInstalled(Context context) {
        File usr = new File(context.getFilesDir(), "usr");
        return new File(usr, "bin/pkg").exists()
                && new File(usr, "bin/bash").exists()
                && new File(usr, "bin/apt").exists();
    }

    public static boolean isInstalling() {
        return sInstalling.get();
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

    private static String expectedSha256(String arch) {
        switch (arch) {
            case "aarch64": return "964e0473a9fa881600927c0151b99896089fb595b81afdcd74b1a6743b8b5889";
            case "arm":     return "137d3a4a887194811dcc36e1c73c6f476105279648565b87491d67d0ab6abe3d";
            case "x86_64":  return "cb0ffc9f35dbb13971aad9683c393e6652bc95a4ba0eac905ca35065687f7de4";
            case "i686":    return "b2e6aeafd937df1890dff86eff4938894d19db2cd3f444e070cd2ac8e93adcd2";
            default:        return null;
        }
    }

    // ---------------------------------------------------------------- entry points

    /**
     * Starts the installation in the background. Only one installation can run at a time; if one is
     * already running, the new callback simply replaces the old one so the UI keeps updating.
     */
    public static void installAsync(final Context context, final Callback callback) {
        sCallback = callback;
        if (!sInstalling.compareAndSet(false, true)) {
            Log.i(TAG, "Installation already running, attached new callback.");
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                install(app, proxyCallback());
            } catch (Exception e) {
                Log.e(TAG, "Bootstrap installation failed", e);
                proxyCallback().onError(e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                sInstalling.set(false);
                sCallback = null;
            }
        }, "bootstrap-install").start();
    }

    /**
     * Re-installs the environment. The existing usr directory is only replaced after the new
     * bootstrap was downloaded, verified and extracted, so a failed download keeps the old one.
     */
    public static void resetEnvironment(Context context, boolean wipeHome, Callback callback) {
        sCallback = callback;
        if (!sInstalling.compareAndSet(false, true)) {
            Log.i(TAG, "Installation already running, attached new callback.");
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                proxyCallback().onStatus("Resetting Linux environment...");
                File filesDir = app.getFilesDir();
                if (wipeHome) {
                    deleteRecursive(new File(filesDir, "home"));
                    new File(filesDir, "home").mkdirs();
                }
                install(app, proxyCallback());
            } catch (Exception e) {
                Log.e(TAG, "Environment reset failed", e);
                proxyCallback().onError(e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                sInstalling.set(false);
                sCallback = null;
            }
        }, "bootstrap-reset").start();
    }

    private static Callback proxyCallback() {
        return new Callback() {
            @Override public void onProgress(long current, long total, String message) {
                Callback c = sCallback;
                if (c != null) c.onProgress(current, total, message);
            }
            @Override public void onStatus(String status) {
                Callback c = sCallback;
                if (c != null) c.onStatus(status);
            }
            @Override public void onSuccess() {
                Callback c = sCallback;
                if (c != null) c.onSuccess();
            }
            @Override public void onError(String error) {
                Callback c = sCallback;
                if (c != null) c.onError(error);
            }
        };
    }

    // ---------------------------------------------------------------- install

    private static void install(Context context, Callback callback) throws Exception {
        if (!REQUIRED_PACKAGE_NAME.equals(context.getPackageName())) {
            throw new IOException("applicationId must be \"" + REQUIRED_PACKAGE_NAME + "\" (current: "
                    + context.getPackageName() + "). The official Termux packages only work with that name.");
        }

        final String arch = getArch();
        final String expectedSha = expectedSha256(arch);
        if (expectedSha == null) {
            throw new IOException("Unsupported CPU architecture: " + arch);
        }
        Log.i(TAG, "Starting bootstrap installation for architecture: " + arch);

        File filesDir = context.getFilesDir();
        if (filesDir.getUsableSpace() < MIN_FREE_BYTES) {
            throw new IOException("Not enough free storage. At least 400 MB is required.");
        }

        File stagingDir = new File(filesDir, "usr-staging");
        File prefixDir = new File(filesDir, "usr");
        File homeDir = new File(filesDir, "home");
        if (!homeDir.exists()) homeDir.mkdirs();

        File tempZip = new File(context.getCacheDir(), "bootstrap-" + arch + ".zip");
        if (tempZip.exists()) tempZip.delete();

        try {
            boolean haveArchive = tryLocalArchive(context, arch, expectedSha, tempZip, callback);

            if (!haveArchive) {
                callback.onStatus("Connecting to the official Termux release...");
                downloadVerified(BOOTSTRAP_BASE_URL + arch + ".zip", tempZip, expectedSha, callback);
            }

            extractArchive(tempZip, stagingDir, callback);

            callback.onStatus("Finalizing setup...");
            if (prefixDir.exists()) {
                deleteRecursive(prefixDir);
            }
            if (!stagingDir.renameTo(prefixDir)) {
                throw new IOException("Failed to move " + stagingDir + " to " + prefixDir);
            }
        } finally {
            if (tempZip.exists()) tempZip.delete();
            if (stagingDir.exists()) deleteRecursive(stagingDir);
        }

        ensureDirectories(prefixDir, homeDir);
        SharedPreferences prefs = context.getSharedPreferences("boomaux_prefs", Context.MODE_PRIVATE);
        applyMirrorConfig(prefixDir, prefs.getString("package_mirror", MIRROR_CLOUDFLARE));

        Log.i(TAG, "Bootstrap successfully installed!");
        callback.onSuccess();
    }

    /** Uses a locally placed archive only if its SHA-256 matches the pinned value. */
    private static boolean tryLocalArchive(Context context, String arch, String expectedSha,
                                           File tempZip, Callback callback) {
        File ext = context.getExternalFilesDir(null);
        File[] candidates = new File[]{
                ext != null ? new File(ext, "bootstrap-" + arch + ".zip") : null,
                new File("/sdcard/Download/bootstrap-" + arch + ".zip"),
                new File("/sdcard/bootstrap-" + arch + ".zip")
        };
        for (File candidate : candidates) {
            if (candidate == null || !candidate.isFile() || candidate.length() < 5L * 1024 * 1024) continue;
            try {
                callback.onStatus("Checking local bootstrap: " + candidate.getName());
                String sha = sha256Of(candidate);
                if (!expectedSha.equalsIgnoreCase(sha)) {
                    Log.w(TAG, "Ignoring local archive with unexpected checksum: " + candidate);
                    continue;
                }
                copyFile(candidate, tempZip);
                Log.i(TAG, "Using verified local bootstrap archive: " + candidate);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Could not use local archive " + candidate, e);
            }
        }
        return false;
    }

    private static void extractArchive(File zipFile, File stagingDir, Callback callback) throws IOException {
        callback.onStatus("Extracting Linux base system...");
        deleteRecursive(stagingDir);
        if (!stagingDir.mkdirs()) {
            throw new IOException("Failed to create staging directory: " + stagingDir);
        }
        final String stagingCanonical = stagingDir.getCanonicalPath();

        List<Pair<String, String>> symlinks = new ArrayList<>();
        byte[] buffer = new byte[16384];

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
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
                    File outFile = safeChild(stagingCanonical, name);
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
                                Os.chmod(outFile.getAbsolutePath(), 0700);
                            } catch (Exception ignored) {}
                        }
                    }
                }
                zis.closeEntry();
            }
        }

        callback.onStatus("Configuring symbolic links...");
        for (Pair<String, String> link : symlinks) {
            String target = link.first;
            String relPath = link.second;
            if (relPath.startsWith("./")) relPath = relPath.substring(2);

            File linkFile = safeChild(stagingCanonical, relPath);
            File parent = linkFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (linkFile.exists() || isSymlink(linkFile)) linkFile.delete();
            try {
                Os.symlink(target, linkFile.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "Symlink creation failed: " + linkFile + " -> " + target, e);
            }
        }
    }

    /** Resolves {@code name} below {@code rootCanonical} and rejects any path that escapes it. */
    private static File safeChild(String rootCanonical, String name) throws IOException {
        File f = new File(rootCanonical, name);
        String canonical = f.getCanonicalPath();
        if (!canonical.equals(rootCanonical) && !canonical.startsWith(rootCanonical + File.separator)) {
            throw new IOException("Unsafe path in archive: " + name);
        }
        return f;
    }

    // ---------------------------------------------------------------- directories & mirror

    /** Cheap and idempotent: only makes sure the directories apt/dpkg need exist. */
    public static void ensureDirectories(File prefixDir, File homeDir) {
        new File(prefixDir, "tmp").mkdirs();
        new File(prefixDir, "var/tmp").mkdirs();
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

        try {
            File dpkgStatus = new File(prefixDir, "var/lib/dpkg/status");
            if (!dpkgStatus.exists()) writeStringToFile(dpkgStatus, "");
            File dpkgAvailable = new File(prefixDir, "var/lib/dpkg/available");
            if (!dpkgAvailable.exists()) writeStringToFile(dpkgAvailable, "");
        } catch (IOException e) {
            Log.w(TAG, "Could not create dpkg database files", e);
        }
    }

    public static void ensureDirectories(Context context) {
        File filesDir = context.getFilesDir();
        File prefixDir = new File(filesDir, "usr");
        if (prefixDir.isDirectory()) {
            ensureDirectories(prefixDir, new File(filesDir, "home"));
        }
    }

    /** Called from Settings when the user picks another mirror. Does nothing if it did not change. */
    public static void updateMirror(Context context, String mirrorUrl) {
        File prefixDir = new File(context.getFilesDir(), "usr");
        if (prefixDir.isDirectory()) {
            applyMirrorConfig(prefixDir, mirrorUrl);
        }
    }

    private static String mirrorFileFor(String mirrorUrl) {
        if (MIRROR_OFFICIAL.equals(mirrorUrl)) return "europe/packages.termux.dev";
        if (MIRROR_TSINGHUA.equals(mirrorUrl)) return "chinese_mainland/mirrors.tuna.tsinghua.edu.cn";
        if (MIRROR_BFSU.equals(mirrorUrl)) return "chinese_mainland/mirrors.bfsu.edu.cn";
        return "default"; // Cloudflare
    }

    /**
     * pkg reads {@code etc/termux/chosen_mirrors} (a symlink to one of the files in
     * {@code etc/termux/mirrors}) and rewrites sources.list from it. Pointing the symlink at the
     * chosen mirror makes pkg use it instead of testing every mirror in the world on each run.
     */
    private static void applyMirrorConfig(File prefixDir, String mirrorUrl) {
        try {
            String relative = mirrorFileFor(mirrorUrl);
            File mirrorFile = new File(prefixDir, "etc/termux/mirrors/" + relative);
            File chosen = new File(prefixDir, "etc/termux/chosen_mirrors");

            if (mirrorFile.isFile()) {
                if (chosen.exists() || isSymlink(chosen)) deleteRecursive(chosen);
                Os.symlink(mirrorFile.getAbsolutePath(), chosen.getAbsolutePath());
            }
            writeStringToFile(new File(prefixDir, "etc/apt/sources.list"),
                    "deb " + mirrorUrl + " stable main\n");
        } catch (Exception e) {
            Log.w(TAG, "Could not apply mirror configuration", e);
        }
    }

    // ---------------------------------------------------------------- download

    private static void downloadVerified(String urlString, File destFile, String expectedSha,
                                         Callback callback) throws Exception {
        String currentUrl = urlString;
        HttpURLConnection conn = null;

        for (int i = 0; i < 10; i++) {
            URL url = new URL(currentUrl);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IOException("Refusing non-HTTPS download URL");
            }
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "BoomAux/1.0");
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
                conn = null;
                if (location == null) throw new IOException("Redirect without Location header");
                currentUrl = location.startsWith("/")
                        ? url.getProtocol() + "://" + url.getHost() + location
                        : location;
                continue;
            }
            if (code == HttpURLConnection.HTTP_OK) {
                break;
            }
            conn.disconnect();
            throw new IOException("HTTP " + code + " from " + url.getHost());
        }
        if (conn == null) throw new IOException("Too many redirects");

        long contentLength = conn.getContentLengthLong();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[16384];
            long totalRead = 0;
            int read;
            long lastReportTime = 0;

            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                digest.update(buf, 0, read);
                totalRead += read;

                long now = System.currentTimeMillis();
                if (now - lastReportTime > 200) {
                    lastReportTime = now;
                    double currentMB = totalRead / (1024.0 * 1024.0);
                    String msg;
                    if (contentLength > 0) {
                        double totalMB = contentLength / (1024.0 * 1024.0);
                        int percent = (int) ((totalRead * 100) / contentLength);
                        msg = String.format(Locale.US, "Downloading BoomAux: %.1f MB / %.1f MB (%d%%)",
                                currentMB, totalMB, percent);
                    } else {
                        msg = String.format(Locale.US, "Downloading BoomAux: %.1f MB...", currentMB);
                    }
                    callback.onProgress(totalRead, contentLength, msg);
                }
            }
        } finally {
            conn.disconnect();
        }

        callback.onStatus("Verifying download...");
        String actual = toHex(digest.digest());
        if (!expectedSha.equalsIgnoreCase(actual)) {
            destFile.delete();
            throw new IOException("Checksum mismatch: the downloaded file is not the expected "
                    + "official bootstrap. Please try again on another network.");
        }
    }

    private static String sha256Of(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[16384];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- file helpers

    /** True if the path itself is a symbolic link (does not follow it). Works on all API levels. */
    public static boolean isSymlink(File file) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(file.getPath()).st_mode);
        } catch (Exception e) {
            return false;
        }
    }

    /** Deletes a file or directory tree. Symbolic links are removed, never followed. */
    public static void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory == null) return;
        boolean symlink = isSymlink(fileOrDirectory);
        if (!symlink && !fileOrDirectory.exists()) return;
        if (!symlink && fileOrDirectory.isDirectory()) {
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
            byte[] buf = new byte[16384];
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
