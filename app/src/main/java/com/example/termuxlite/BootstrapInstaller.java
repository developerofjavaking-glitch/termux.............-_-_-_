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
                        if (name.startsWith("bin/") || name.startsWith("etc/")) {
                            patchScriptPaths(outFile, filesDir.getAbsolutePath());
                        }
                    }
                }
                zis.closeEntry();
            }
        }

        callback.onStatus("Configuring symlinks and permissions...");
        for (Pair<String, String> link : symlinks) {
            String target = link.first;
            if (target.contains("/data/data/com.termux/files") || target.contains("/data/user/0/com.termux/files")) {
                target = target.replace("/data/data/com.termux/files", filesDir.getAbsolutePath())
                               .replace("/data/user/0/com.termux/files", filesDir.getAbsolutePath());
            } else if (target.contains("/data/data/com.termux") || target.contains("/data/user/0/com.termux")) {
                target = target.replace("/data/data/com.termux", filesDir.getParent())
                               .replace("/data/user/0/com.termux", filesDir.getParent());
            }
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

        // Setup APT directories and configuration
        File aptConfD = new File(prefixDir, "etc/apt/apt.conf.d");
        aptConfD.mkdirs();
        File sourcesListD = new File(prefixDir, "etc/apt/sources.list.d");
        sourcesListD.mkdirs();
        File varLibDpkg = new File(prefixDir, "var/lib/dpkg");
        varLibDpkg.mkdirs();
        new File(prefixDir, "var/lib/dpkg/updates").mkdirs();
        new File(prefixDir, "var/lib/dpkg/info").mkdirs();
        File dpkgStatus = new File(varLibDpkg, "status");
        if (!dpkgStatus.exists()) {
            writeStringToFile(dpkgStatus, "");
        }
        File aptListsPartial = new File(prefixDir, "var/lib/apt/lists/partial");
        aptListsPartial.mkdirs();
        File aptCacheArchives = new File(prefixDir, "var/cache/apt/archives/partial");
        aptCacheArchives.mkdirs();

        File sourcesList = new File(prefixDir, "etc/apt/sources.list");
        String sourcesContent = "deb https://packages-cf.termux.dev/apt/termux-main stable main\n";
        writeStringToFile(sourcesList, sourcesContent);

        File aptConf = new File(prefixDir, "etc/apt/apt.conf");
        String aptConfContent =
                "Dir \"" + prefixDir.getAbsolutePath() + "\";\n" +
                "Dir::State \"var/lib/apt\";\n" +
                "Dir::State::status \"var/lib/dpkg/status\";\n" +
                "Dir::Cache \"var/cache/apt\";\n" +
                "Dir::Etc \"etc/apt\";\n" +
                "Dir::Etc::parts \"apt.conf.d\";\n" +
                "Dir::Etc::sourcelist \"sources.list\";\n" +
                "Dir::Etc::sourceparts \"sources.list.d\";\n" +
                "Dir::Bin::methods \"" + prefixDir.getAbsolutePath() + "/lib/apt/methods\";\n" +
                "Dir::Bin::dpkg \"" + prefixDir.getAbsolutePath() + "/bin/dpkg\";\n" +
                "Dir::Bin::apt-key \"" + prefixDir.getAbsolutePath() + "/bin/apt-key\";\n" +
                "DPkg::Options {\"--admindir=" + prefixDir.getAbsolutePath() + "/var/lib/dpkg\"};\n" +
                "Acquire::Languages \"none\";\n";
        writeStringToFile(aptConf, aptConfContent);

        // Setup Pip SSL cert configuration
        File pipConf = new File(prefixDir, "etc/pip.conf");
        String pipConfContent = "[global]\ncert = " + prefixDir.getAbsolutePath() + "/etc/tls/cert.pem\n";
        writeStringToFile(pipConf, pipConfContent);
        File userPipDir = new File(homeDir, ".config/pip");
        userPipDir.mkdirs();
        writeStringToFile(new File(userPipDir, "pip.conf"), pipConfContent);

        // Setup shell environment in profile and .bashrc
        File profile = new File(prefixDir, "etc/profile");
        String profileContent =
                "export PREFIX=\"" + prefixDir.getAbsolutePath() + "\"\n" +
                "export HOME=\"" + homeDir.getAbsolutePath() + "\"\n" +
                "export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n" +
                "export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n" +
                "export APT_CONFIG=\"$PREFIX/etc/apt/apt.conf\"\n" +
                "export TERMUX_APP_PACKAGE_MANAGER=apt\n" +
                "export PIP_CERT=\"$PREFIX/etc/tls/cert.pem\"\n" +
                "export REQUESTS_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n";
        writeStringToFile(profile, profileContent);
        writeStringToFile(new File(homeDir, ".bashrc"), profileContent);

        // Copy native redirect shim if available
        try {
            File nativeLib = new File(context.getApplicationInfo().nativeLibraryDir, "libboomaxe-redirect.so");
            File targetLib = new File(prefixDir, "lib/libboomaxe-redirect.so");
            if (nativeLib.exists()) {
                copyFile(nativeLib, targetLib);
                Os.chmod(targetLib.getAbsolutePath(), 0755);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed copying native redirect library", e);
        }

        // Deep patch all scripts in bin/ and etc/
        patchAllScripts(new File(prefixDir, "bin"), filesDir.getAbsolutePath());
        patchAllScripts(new File(prefixDir, "etc"), filesDir.getAbsolutePath());

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

    private static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
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

    private static void patchAllScripts(File dir, String targetPrefix) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                patchAllScripts(f, targetPrefix);
            } else if (f.isFile()) {
                patchScriptPaths(f, targetPrefix);
            }
        }
    }

    private static void patchScriptPaths(File file, String targetPrefix) {
        if (!file.isFile() || file.length() > 500000) return;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            // Only patch text files (first 4 bytes not ELF \x7fELF)
            if (bytes.length >= 4 && bytes[0] == 0x7f && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F') {
                return;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            boolean changed = false;
            if (content.contains("/data/data/com.termux/files") || content.contains("/data/user/0/com.termux/files")) {
                content = content.replace("/data/data/com.termux/files", targetPrefix)
                                 .replace("/data/user/0/com.termux/files", targetPrefix);
                changed = true;
            }
            if (content.contains("/data/data/com.termux") || content.contains("/data/user/0/com.termux")) {
                File targetFile = new File(targetPrefix);
                String parentPath = targetFile.getParent();
                if (parentPath != null) {
                    content = content.replace("/data/data/com.termux", parentPath)
                                     .replace("/data/user/0/com.termux", parentPath);
                    changed = true;
                }
            }
            if (changed) {
                java.nio.file.Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }
}
