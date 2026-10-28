package com.example.termuxlite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Starts TermuxService on boot if enabled in settings, optionally running ~/start_bot.sh
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BoomAuxBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences("boomaux_prefs", Context.MODE_PRIVATE);
        boolean autoStart = prefs.getBoolean("boot_auto_start", false);

        if (!autoStart) {
            Log.i(TAG, "Device booted but auto-start is disabled in settings.");
            return;
        }

        Log.i(TAG, "Device boot detected. Auto-starting BoomAux service...");
        Intent serviceIntent = new Intent(context, TermuxService.class);
        serviceIntent.setAction(TermuxService.ACTION_EXECUTE_BOOT_SCRIPT);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed starting TermuxService on boot", e);
        }
    }
}
