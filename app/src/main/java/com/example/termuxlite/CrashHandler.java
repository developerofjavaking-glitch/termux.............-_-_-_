package com.example.termuxlite;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Catches unhandled exceptions and writes detailed crash reports to files/crash.log.
 * Enables easy debugging and sharing of crash information.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "BoomAuxCrashHandler";
    private static final String CRASH_FILE = "crash.log";

    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    private CrashHandler(Context context) {
        this.context = context.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void init(Context context) {
        if (!(Thread.getDefaultUncaughtExceptionHandler() instanceof CrashHandler)) {
            Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context));
        }
    }

    public static File getCrashFile(Context context) {
        return new File(context.getFilesDir(), CRASH_FILE);
    }

    public static boolean hasCrashLog(Context context) {
        File file = getCrashFile(context);
        return file.exists() && file.length() > 0;
    }

    public static String readCrashLog(Context context) {
        File file = getCrashFile(context);
        if (!file.exists()) return "";
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 65536)];
            int read = fis.read(data);
            return new String(data, 0, Math.max(0, read), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Error reading crash log", e);
            return "";
        }
    }

    public static void shareCrashLog(Context context) {
        String log = readCrashLog(context);
        if (log.isEmpty()) {
            Toast.makeText(context, R.string.no_crash_log, Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "BoomAux Crash Report");
        intent.putExtra(Intent.EXTRA_TEXT, log);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.pref_share_crash_log)));
    }

    @Override
    public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
        try {
            writeCrashLog(t, e);
        } catch (Exception ex) {
            Log.e(TAG, "Failed writing crash log", ex);
        }

        if (defaultHandler != null) {
            defaultHandler.uncaughtException(t, e);
        } else {
            System.exit(1);
        }
    }

    private void writeCrashLog(Thread thread, Throwable throwable) {
        File crashFile = getCrashFile(context);
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        String stackTrace = sw.toString();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        String timestamp = sdf.format(new Date());

        StringBuilder report = new StringBuilder();
        report.append("=========================================\n");
        report.append("BoomAux Crash Report: ").append(timestamp).append("\n");
        report.append("=========================================\n");
        report.append("Package: ").append(context.getPackageName()).append("\n");
        report.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        report.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("Arch: ").append(BootstrapInstaller.getArch()).append("\n");
        report.append("Thread: ").append(thread.getName()).append("\n\n");
        report.append("Exception: ").append(throwable.getClass().getName()).append("\n");
        report.append("Message: ").append(throwable.getMessage()).append("\n\n");
        report.append("Stacktrace:\n").append(stackTrace).append("\n");
        report.append("=========================================\n\n");

        try (FileOutputStream fos = new FileOutputStream(crashFile, true)) {
            fos.write(report.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            Log.e(TAG, "Could not append crash log", ex);
        }
    }
}
