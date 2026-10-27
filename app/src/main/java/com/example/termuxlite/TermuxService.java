package com.example.termuxlite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service managing active terminal sessions, background execution, and WakeLock.
 */
public class TermuxService extends Service implements TerminalSessionClient {

    private static final String TAG = "BoomAuxService";
    private static final String CHANNEL_ID = "boomaux_background_service";
    private static final int NOTIFICATION_ID = 1337;

    public static final String ACTION_STOP_SERVICE = "com.termux.ACTION_STOP_SERVICE";
    public static final String ACTION_TOGGLE_WAKELOCK = "com.termux.ACTION_TOGGLE_WAKELOCK";
    public static final String ACTION_NEW_SESSION = "com.termux.ACTION_NEW_SESSION";
    public static final String ACTION_EXECUTE_BOOT_SCRIPT = "com.termux.ACTION_EXECUTE_BOOT_SCRIPT";

    public interface ServiceSessionListener {
        void onSessionListChanged();
        void onSessionTitleChanged(TerminalSession session);
        void onSessionScreenUpdated(TerminalSession session);
    }

    public class LocalBinder extends Binder {
        public TermuxService getService() {
            return TermuxService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final List<TerminalSession> sessions = new ArrayList<>();
    private int currentSessionIndex = 0;

    private ServiceSessionListener sessionListener;
    private TerminalSessionClient activeClient;
    private PowerManager.WakeLock wakeLock;
    private boolean isWakeLockEnabled = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        SharedPreferences prefs = getSharedPreferences("boomaux_prefs", MODE_PRIVATE);
        boolean defaultWakeLock = prefs.getBoolean("wakelock_default", true);
        if (defaultWakeLock) {
            setWakeLock(true);
        } else {
            updateNotification();
        }

        ColorThemeHelper.loadAndApplySavedTheme(this, sessions);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_STOP_SERVICE.equals(action)) {
                stopAllSessionsAndSelf();
                return START_NOT_STICKY;
            } else if (ACTION_TOGGLE_WAKELOCK.equals(action)) {
                setWakeLock(!isWakeLockHeld());
                return START_STICKY;
            } else if (ACTION_NEW_SESSION.equals(action)) {
                Intent mainIntent = new Intent(this, MainActivity.class);
                mainIntent.setAction(ACTION_NEW_SESSION);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(mainIntent);
                return START_STICKY;
            } else if (ACTION_EXECUTE_BOOT_SCRIPT.equals(action)) {
                executeBootScript();
                return START_STICKY;
            }
        }

        updateNotification();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "Task removed from recents. Keeping " + sessions.size() + " session(s) running.");
        if (sessions.isEmpty()) {
            stopSelf();
        } else {
            updateNotification();
        }
    }

    @Override
    public void onDestroy() {
        setWakeLock(false);
        for (TerminalSession s : sessions) {
            s.finishIfRunning();
        }
        sessions.clear();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- WakeLock

    public synchronized void setWakeLock(boolean enable) {
        this.isWakeLockEnabled = enable;
        if (enable) {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BoomAux:BotRunner");
                    wakeLock.acquire();
                    Log.i(TAG, "WakeLock acquired successfully.");
                }
            }
        } else {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
                Log.i(TAG, "WakeLock released.");
            }
        }
        updateNotification();
    }

    public synchronized boolean isWakeLockHeld() {
        return wakeLock != null && wakeLock.isHeld();
    }

    // ---------------------------------------------------------------- session management

    public synchronized List<TerminalSession> getSessions() {
        return sessions;
    }

    public synchronized int getCurrentSessionIndex() {
        if (sessions.isEmpty()) return -1;
        if (currentSessionIndex < 0 || currentSessionIndex >= sessions.size()) {
            currentSessionIndex = Math.max(0, sessions.size() - 1);
        }
        return currentSessionIndex;
    }

    public synchronized void setCurrentSessionIndex(int index) {
        if (sessions.isEmpty()) {
            currentSessionIndex = -1;
            return;
        }
        currentSessionIndex = ((index % sessions.size()) + sessions.size()) % sessions.size();
    }

    public synchronized TerminalSession getCurrentSession() {
        int idx = getCurrentSessionIndex();
        if (idx >= 0 && idx < sessions.size()) {
            return sessions.get(idx);
        }
        return null;
    }

    public synchronized TerminalSession createSession(String shell, String cwd, String[] args, String[] env, String sessionName) {
        TerminalSession session = new TerminalSession(shell, cwd, args, env,
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS, this);
        session.mSessionName = sessionName != null ? sessionName : ("Session " + (sessions.size() + 1));
        sessions.add(session);
        currentSessionIndex = sessions.size() - 1;

        updateNotification();
        if (sessionListener != null) {
            sessionListener.onSessionListChanged();
        }
        return session;
    }

    public synchronized void removeSession(TerminalSession session) {
        if (session == null) return;
        session.finishIfRunning();
        int idx = sessions.indexOf(session);
        if (idx >= 0) {
            sessions.remove(idx);
            if (currentSessionIndex >= sessions.size()) {
                currentSessionIndex = sessions.size() - 1;
            }
        }

        updateNotification();
        if (sessionListener != null) {
            sessionListener.onSessionListChanged();
        }

        if (sessions.isEmpty()) {
            stopSelf();
        }
    }

    public synchronized void stopAllSessionsAndSelf() {
        for (TerminalSession s : sessions) {
            s.finishIfRunning();
        }
        sessions.clear();
        setWakeLock(false);
        if (sessionListener != null) {
            sessionListener.onSessionListChanged();
        }
        stopForeground(true);
        stopSelf();
    }

    public void setSessionListener(ServiceSessionListener listener) {
        this.sessionListener = listener;
    }

    public void setActiveClient(TerminalSessionClient client) {
        this.activeClient = client;
    }

    // ---------------------------------------------------------------- boot execution

    private void executeBootScript() {
        if (!BootstrapInstaller.isInstalled(this)) {
            Log.w(TAG, "Bootstrap not yet installed. Skipping boot script execution.");
            return;
        }

        SharedPreferences prefs = getSharedPreferences("boomaux_prefs", MODE_PRIVATE);
        String customCmd = prefs.getString("boot_command", "").trim();

        File usr = new File(getFilesDir(), "usr");
        File bash = new File(usr, "bin/bash");
        File home = new File(getFilesDir(), "home");
        if (!home.exists()) home.mkdirs();

        String shell = bash.exists() ? bash.getAbsolutePath() : "/system/bin/sh";
        String cwd = home.getAbsolutePath();

        String[] args;
        if (!customCmd.isEmpty()) {
            args = new String[]{"bash", "-l", "-c", customCmd};
        } else {
            File startBotScript = new File(home, "start_bot.sh");
            if (startBotScript.exists()) {
                args = new String[]{"bash", "-l", startBotScript.getAbsolutePath()};
            } else {
                args = new String[]{"bash", "-l"};
            }
        }

        // Spawn background session
        createSession(shell, cwd, args, MainActivity.buildStaticEnvironment(this), "Boot Bot");
        Log.i(TAG, "Executed boot script/bot session in background.");
    }

    // ---------------------------------------------------------------- notification

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notification_channel_desc));
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    public void updateNotification() {
        int count = sessions.size();
        String sessionStr = count == 1 ? getString(R.string.notification_single_session) :
                (count == 0 ? getString(R.string.notification_no_sessions) : getString(R.string.notification_multi_sessions, count));

        boolean wakeHeld = isWakeLockHeld();
        String contentText = sessionStr + " • " + (wakeHeld ? getString(R.string.wakelock_active) : getString(R.string.wakelock_inactive));

        Intent notifyIntent = new Intent(this, MainActivity.class);
        notifyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(this, 0, notifyIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        // Action: New Session
        Intent newSessionIntent = new Intent(this, TermuxService.class);
        newSessionIntent.setAction(ACTION_NEW_SESSION);
        PendingIntent newSessionPendingIntent = PendingIntent.getService(this, 1, newSessionIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        // Action: WakeLock Toggle
        Intent wakeLockIntent = new Intent(this, TermuxService.class);
        wakeLockIntent.setAction(ACTION_TOGGLE_WAKELOCK);
        PendingIntent wakeLockPendingIntent = PendingIntent.getService(this, 2, wakeLockIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        // Action: Exit
        Intent exitIntent = new Intent(this, TermuxService.class);
        exitIntent.setAction(ACTION_STOP_SERVICE);
        PendingIntent exitPendingIntent = PendingIntent.getService(this, 3, exitIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        String wakeActionText = wakeHeld ? "WakeLock: ON" : "WakeLock: OFF";

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_input_add, getString(R.string.notification_action_new), newSessionPendingIntent)
                .addAction(android.R.drawable.ic_lock_idle_charging, wakeActionText, wakeLockPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_action_exit), exitPendingIntent)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    // ---------------------------------------------------------------- TerminalSessionClient delegate

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        if (activeClient != null) {
            activeClient.onTextChanged(changedSession);
        }
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession changedSession) {
        updateNotification();
        if (sessionListener != null) {
            sessionListener.onSessionTitleChanged(changedSession);
        }
        if (activeClient != null) {
            activeClient.onTitleChanged(changedSession);
        }
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        removeSession(finishedSession);
        if (activeClient != null) {
            activeClient.onSessionFinished(finishedSession);
        }
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        if (activeClient != null) {
            activeClient.onCopyTextToClipboard(session, text);
        }
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        if (activeClient != null) {
            activeClient.onPasteTextFromClipboard(session);
        }
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        if (activeClient != null) {
            activeClient.onBell(session);
        }
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession session) {
        if (activeClient != null) {
            activeClient.onColorsChanged(session);
        }
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        if (activeClient != null) {
            activeClient.onTerminalCursorStateChange(state);
        }
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession session, int pid) {
        if (activeClient != null) {
            activeClient.setTerminalShellPid(session, pid);
        }
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return activeClient != null ? activeClient.getTerminalCursorStyle() : TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE;
    }

    @Override public void logError(String tag, String message) { Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(tag, message, e); }
    @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "Exception", e); }
}
