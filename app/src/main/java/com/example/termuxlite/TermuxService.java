package com.example.termuxlite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service that holds terminal sessions in memory.
 *
 * Keeps processes and terminal sessions running continuously in the background
 * even when the activity is minimized, closed, or swiped away from recents.
 */
public class TermuxService extends Service implements TerminalSessionClient {

    private static final String TAG = "BoomAuxService";
    private static final String CHANNEL_ID = "boomaux_background_service";
    private static final int NOTIFICATION_ID = 1337;
    public static final String ACTION_STOP_SERVICE = "com.boomaux.boom.bdcreator.ACTION_STOP_SERVICE";

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

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_SERVICE.equals(intent.getAction())) {
            stopAllSessionsAndSelf();
            return START_NOT_STICKY;
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
        // Do NOT destroy sessions when user swipes away app from recents!
        // Keep running in background with ongoing notification just like official Termux.
        Log.i(TAG, "Task swiped away from recents. Keeping " + sessions.size() + " session(s) active in background.");
        if (sessions.isEmpty()) {
            stopSelf();
        } else {
            updateNotification();
        }
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        for (TerminalSession s : sessions) {
            s.finishIfRunning();
        }
        sessions.clear();
        super.onDestroy();
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

    // ---------------------------------------------------------------- notification & wakelock

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "BoomAux Terminal Sessions",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows notification while terminal sessions run in the background");
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void updateNotification() {
        int count = sessions.size();
        String contentText = count == 1 ? "1 session running" : (count + " sessions running");
        if (count == 0) {
            contentText = "No active sessions";
        }

        Intent notifyIntent = new Intent(this, MainActivity.class);
        notifyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(this, 0, notifyIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        Intent exitIntent = new Intent(this, TermuxService.class);
        exitIntent.setAction(ACTION_STOP_SERVICE);
        PendingIntent exitPendingIntent = PendingIntent.getService(this, 1, exitIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BoomAux")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", exitPendingIntent)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BoomAux:WakeLock");
                wakeLock.acquire();
            }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
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

    @Override
    public void logError(String tag, String message) {
        Log.e(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        Log.d(tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        Log.v(tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.e(tag, "Exception", e);
    }
}
