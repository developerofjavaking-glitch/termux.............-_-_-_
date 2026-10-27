package com.example.termuxlite;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Handles termux-setup-storage symlinks, SAF backup/restore, and file import/export.
 */
public class StorageHelper {

    private static final String TAG = "BoomAuxStorage";

    public static boolean setupStorageSymlinks(Context context) {
        File home = new File(context.getFilesDir(), "home");
        if (!home.exists()) home.mkdirs();

        File storageDir = new File(home, "storage");
        if (!storageDir.exists()) storageDir.mkdirs();

        File sdcard = Environment.getExternalStorageDirectory();
        if (sdcard == null || !sdcard.exists()) {
            sdcard = new File("/sdcard");
        }

        boolean success = true;
        success &= createSymlinkSafe(sdcard.getAbsolutePath(), new File(storageDir, "shared"));
        success &= createSymlinkSafe(new File(sdcard, "Download").getAbsolutePath(), new File(storageDir, "downloads"));
        success &= createSymlinkSafe(new File(sdcard, "DCIM").getAbsolutePath(), new File(storageDir, "dcim"));
        success &= createSymlinkSafe(new File(sdcard, "Pictures").getAbsolutePath(), new File(storageDir, "pictures"));
        success &= createSymlinkSafe(new File(sdcard, "Music").getAbsolutePath(), new File(storageDir, "music"));
        success &= createSymlinkSafe(new File(sdcard, "Movies").getAbsolutePath(), new File(storageDir, "movies"));

        // Check for secondary external SD card
        File[] externalFiles = context.getExternalFilesDirs(null);
        if (externalFiles != null && externalFiles.length > 1 && externalFiles[1] != null) {
            File extCard = externalFiles[1];
            while (extCard != null && extCard.getParentFile() != null &&
                    !"Android".equals(extCard.getName()) && !"Android".equals(extCard.getParentFile().getName())) {
                extCard = extCard.getParentFile();
            }
            if (extCard != null && extCard.getParentFile() != null) {
                createSymlinkSafe(extCard.getParentFile().getAbsolutePath(), new File(storageDir, "external-1"));
            }
        }

        Log.i(TAG, "Storage symlinks setup result: " + success);
        return success;
    }

    private static boolean createSymlinkSafe(String target, File link) {
        try {
            if (link.exists() || isSymlink(link)) {
                link.delete();
            }
            Os.symlink(target, link.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed creating symlink " + link.getAbsolutePath() + " -> " + target, e);
            return false;
        }
    }

    private static boolean isSymlink(File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return java.nio.file.Files.isSymbolicLink(file.toPath());
        }
        return false;
    }

    public static void copyUriToHome(Context context, Uri sourceUri, String destFileName) throws IOException {
        File home = new File(context.getFilesDir(), "home");
        if (!home.exists()) home.mkdirs();

        File destFile = new File(home, destFileName);
        try (InputStream in = context.getContentResolver().openInputStream(sourceUri);
             OutputStream out = new FileOutputStream(destFile)) {
            if (in == null) throw new IOException("Cannot open input stream for URI: " + sourceUri);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        try {
            Os.chmod(destFile.getAbsolutePath(), 0644);
        } catch (Exception ignored) {}
    }

    public static void backupHomeToZip(Context context, OutputStream outputStream) throws IOException {
        File home = new File(context.getFilesDir(), "home");
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(outputStream))) {
            zipDirectory(home, home.getAbsolutePath(), zos);
        }
    }

    private static void zipDirectory(File root, String basePath, ZipOutputStream zos) throws IOException {
        File[] files = root.listFiles();
        if (files == null) return;

        byte[] buffer = new byte[8192];
        for (File file : files) {
            if (isSymlink(file)) continue; // skip storage symlinks in backup

            String relPath = file.getAbsolutePath().substring(basePath.length());
            if (relPath.startsWith("/")) relPath = relPath.substring(1);

            if (file.isDirectory()) {
                ZipEntry entry = new ZipEntry(relPath + "/");
                zos.putNextEntry(entry);
                zos.closeEntry();
                zipDirectory(file, basePath, zos);
            } else {
                ZipEntry entry = new ZipEntry(relPath);
                zos.putNextEntry(entry);
                try (FileInputStream fis = new FileInputStream(file)) {
                    int read;
                    while ((read = fis.read(buffer)) != -1) {
                        zos.write(buffer, 0, read);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    public static void restoreHomeFromZip(Context context, InputStream inputStream) throws IOException {
        File home = new File(context.getFilesDir(), "home");
        if (!home.exists()) home.mkdirs();

        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(inputStream))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                File outFile = new File(home, name);
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
                }
                zis.closeEntry();
            }
        }
    }
}
