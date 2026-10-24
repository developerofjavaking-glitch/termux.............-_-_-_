package com.example.termuxlite;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A Termux-style terminal app with automatic bootstrap installer.
 *
 * Provides a pseudo-terminal environment supporting pkg, apt, python, pip,
 * bash, and multi-session tabs.
 */
public class MainActivity extends AppCompatActivity implements TerminalSessionClient, TerminalViewClient {

    private static final String TAG = "BoomAxe";
    private static final int MIN_FONT_DP = 8;
    private static final int MAX_FONT_DP = 40;

    private TerminalView terminalView;
    private TextView sessionTitle;
    private Button keyCtrl, keyAlt;

    private View bootstrapStatusLayout;
    private TextView bootstrapStatusText;
    private ProgressBar bootstrapProgressBar;
    private Button btnRetryBootstrap;

    private final List<TerminalSession> sessions = new ArrayList<>();
    private int current = -1;

    private boolean ctrlActive = false;
    private boolean altActive = false;
    private float fontDp = 14f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        terminalView = findViewById(R.id.terminalView);
        sessionTitle = findViewById(R.id.sessionTitle);
        keyCtrl = findViewById(R.id.keyCtrl);
        keyAlt = findViewById(R.id.keyAlt);

        bootstrapStatusLayout = findViewById(R.id.bootstrapStatusLayout);
        bootstrapStatusText = findViewById(R.id.bootstrapStatusText);
        bootstrapProgressBar = findViewById(R.id.bootstrapProgressBar);
        btnRetryBootstrap = findViewById(R.id.btnRetryBootstrap);

        btnRetryBootstrap.setOnClickListener(v -> checkAndInstallBootstrap());

        terminalView.setTerminalViewClient(this);
        applyFontSize();

        setupButtons();
        setupFallbackCommands();

        newSession();
        terminalView.requestFocus();

        checkAndInstallBootstrap();
    }

    private void setupFallbackCommands() {
        try {
            File binDir = new File(getFilesDir(), "bin");
            if (!binDir.exists()) binDir.mkdirs();

            File pkgFile = new File(binDir, "pkg");
            String pkgContent = "#!/system/bin/sh\n" +
                    "echo \"\\033[1;33m[*] BoomAxe bootstrap environment is installing in the background.\\033[0m\"\n" +
                    "echo \"[*] Once installation completes, full 'pkg' and 'apt' will be active.\"\n" +
                    "echo \"[*] Please wait a moment...\"\n";
            writeStringToFile(pkgFile, pkgContent);
            try {
                android.system.Os.chmod(pkgFile.getAbsolutePath(), 0755);
            } catch (Exception ignored) {}

            File pipFile = new File(binDir, "pip");
            String pipContent = "#!/system/bin/sh\n" +
                    "echo \"\\033[1;33m[*] Python and pip require the BoomAxe bootstrap environment.\\033[0m\"\n" +
                    "echo \"[*] Once bootstrap setup completes, run: pkg install python\\033[0m\"\n";
            writeStringToFile(pipFile, pipContent);
            try {
                android.system.Os.chmod(pipFile.getAbsolutePath(), 0755);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private static void writeStringToFile(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void checkAndInstallBootstrap() {
        if (BootstrapInstaller.isInstalled(this)) {
            bootstrapStatusLayout.setVisibility(View.GONE);
            return;
        }

        bootstrapStatusLayout.setVisibility(View.VISIBLE);
        bootstrapProgressBar.setIndeterminate(true);
        bootstrapStatusText.setText("Installing Termux bootstrap (required for pkg and pip)...");
        btnRetryBootstrap.setVisibility(View.GONE);

        BootstrapInstaller.installAsync(this, new BootstrapInstaller.Callback() {
            @Override
            public void onProgress(long current, long total, String message) {
                runOnUiThread(() -> {
                    bootstrapProgressBar.setIndeterminate(false);
                    if (total > 0) {
                        int pct = (int) ((current * 100) / total);
                        bootstrapProgressBar.setProgress(pct);
                    }
                    bootstrapStatusText.setText(message);
                });
            }

            @Override
            public void onStatus(String status) {
                runOnUiThread(() -> {
                    bootstrapProgressBar.setIndeterminate(true);
                    bootstrapStatusText.setText(status);
                });
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    bootstrapStatusLayout.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "BoomAxe bootstrap installed successfully!", Toast.LENGTH_LONG).show();
                    // Open a new session with bash
                    newSession();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    bootstrapProgressBar.setIndeterminate(false);
                    bootstrapStatusText.setText("Bootstrap error: " + error);
                    btnRetryBootstrap.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        for (TerminalSession s : sessions) s.finishIfRunning();
        sessions.clear();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- sessions

    private String[] buildEnvironment() {
        File filesDir = getFilesDir();
        File usr = new File(filesDir, "usr");
        File home = new File(filesDir, "home");
        if (!home.exists()) home.mkdirs();
        File tmp = new File(usr, "tmp");
        if (!tmp.exists()) tmp.mkdirs();

        String prefix = usr.getAbsolutePath();
        String binDir = prefix + "/bin";
        String appletsDir = binDir + "/applets";

        List<String> env = new ArrayList<>();
        env.add("TERM=xterm-256color");
        env.add("COLORTERM=truecolor");
        env.add("HOME=" + home.getAbsolutePath());
        env.add("TMPDIR=" + tmp.getAbsolutePath());
        env.add("PREFIX=" + prefix);
        env.add("LANG=en_US.UTF-8");
        env.add("PATH=" + binDir + ":" + appletsDir + ":" + filesDir.getAbsolutePath() + "/bin:/system/bin:/system/xbin:/vendor/bin");
        env.add("ANDROID_ROOT=/system");
        env.add("ANDROID_DATA=/data");
        env.add("EXTERNAL_STORAGE=/sdcard");
        env.add("LD_LIBRARY_PATH=" + prefix + "/lib");

        File bash = new File(binDir, "bash");
        if (bash.exists()) {
            env.add("SHELL=" + bash.getAbsolutePath());
        } else {
            env.add("SHELL=/system/bin/sh");
        }

        File termuxExec = new File(prefix, "lib/libtermux-exec-ld-preload.so");
        if (!termuxExec.exists()) {
            termuxExec = new File(prefix, "lib/libtermux-exec.so");
        }
        if (termuxExec.exists()) {
            env.add("LD_PRELOAD=" + termuxExec.getAbsolutePath());
        }

        return env.toArray(new String[0]);
    }

    private void newSession() {
        File usr = new File(getFilesDir(), "usr");
        File bash = new File(usr, "bin/bash");
        File home = new File(getFilesDir(), "home");
        if (!home.exists()) home.mkdirs();

        String shell = bash.exists() ? bash.getAbsolutePath() : "/system/bin/sh";
        String cwd = bash.exists() ? home.getAbsolutePath() : getFilesDir().getAbsolutePath();
        String[] args = bash.exists() ? new String[]{"bash", "-l"} : new String[]{"sh"};

        TerminalSession session = new TerminalSession(shell, cwd, args, buildEnvironment(),
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS, this);
        session.mSessionName = "Session " + (sessions.size() + 1);
        sessions.add(session);
        switchTo(sessions.size() - 1);
    }

    private void switchTo(int index) {
        if (sessions.isEmpty()) return;
        current = ((index % sessions.size()) + sessions.size()) % sessions.size();
        TerminalSession s = sessions.get(current);
        terminalView.attachSession(s);
        terminalView.onScreenUpdated();
        updateTitle();
    }

    private void closeCurrent() {
        if (sessions.isEmpty()) return;
        TerminalSession s = sessions.get(current);
        s.finishIfRunning();
        removeSession(s);
    }

    private void removeSession(TerminalSession s) {
        int idx = sessions.indexOf(s);
        if (idx < 0) return;
        sessions.remove(idx);
        if (sessions.isEmpty()) {
            finish();
            return;
        }
        switchTo(Math.min(idx, sessions.size() - 1));
    }

    private void updateTitle() {
        if (current < 0 || current >= sessions.size()) return;
        TerminalSession s = sessions.get(current);
        String t = s.getTitle();
        String label = (t == null || t.isEmpty()) ? s.mSessionName : t;
        sessionTitle.setText((current + 1) + "/" + sessions.size() + "  " + label);
    }

    // ---------------------------------------------------------------- buttons

    private void setupButtons() {
        findViewById(R.id.btnNew).setOnClickListener(v -> newSession());
        findViewById(R.id.btnClose).setOnClickListener(v -> closeCurrent());
        findViewById(R.id.btnPrev).setOnClickListener(v -> switchTo(current - 1));
        findViewById(R.id.btnNext).setOnClickListener(v -> switchTo(current + 1));

        keyCtrl.setOnClickListener(v -> { ctrlActive = !ctrlActive; refreshModifierButtons(); });
        keyAlt.setOnClickListener(v -> { altActive = !altActive; refreshModifierButtons(); });

        bindKey(R.id.keyEsc, KeyEvent.KEYCODE_ESCAPE);
        bindKey(R.id.keyTab, KeyEvent.KEYCODE_TAB);
        bindKey(R.id.keyLeft, KeyEvent.KEYCODE_DPAD_LEFT);
        bindKey(R.id.keyUp, KeyEvent.KEYCODE_DPAD_UP);
        bindKey(R.id.keyDown, KeyEvent.KEYCODE_DPAD_DOWN);
        bindKey(R.id.keyRight, KeyEvent.KEYCODE_DPAD_RIGHT);
        bindKey(R.id.keyHome, KeyEvent.KEYCODE_MOVE_HOME);
        bindKey(R.id.keyEnd, KeyEvent.KEYCODE_MOVE_END);
        bindKey(R.id.keyPgUp, KeyEvent.KEYCODE_PAGE_UP);
        bindKey(R.id.keyPgDn, KeyEvent.KEYCODE_PAGE_DOWN);

        bindText(R.id.keyDash, "-");
        bindText(R.id.keySlash, "/");
        bindText(R.id.keyPipe, "|");
        bindText(R.id.keyTilde, "~");
    }

    private void bindKey(int id, int keyCode) {
        findViewById(id).setOnClickListener(v -> {
            TerminalSession s = terminalView.getCurrentSession();
            if (s == null) return;
            int mod = 0;
            if (ctrlActive) mod |= com.termux.terminal.KeyHandler.KEYMOD_CTRL;
            if (altActive) mod |= com.termux.terminal.KeyHandler.KEYMOD_ALT;
            terminalView.handleKeyCode(keyCode, mod);
            clearModifiers();
        });
    }

    private void bindText(int id, String text) {
        findViewById(id).setOnClickListener(v -> {
            TerminalSession s = terminalView.getCurrentSession();
            if (s == null) return;
            terminalView.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD,
                    text.codePointAt(0), ctrlActive, altActive);
            clearModifiers();
        });
    }

    private void clearModifiers() {
        ctrlActive = false;
        altActive = false;
        refreshModifierButtons();
    }

    private void refreshModifierButtons() {
        keyCtrl.setBackgroundColor(ctrlActive ? 0xFF3F51B5 : 0x00000000);
        keyAlt.setBackgroundColor(altActive ? 0xFF3F51B5 : 0x00000000);
    }

    private void applyFontSize() {
        float px = fontDp * getResources().getDisplayMetrics().density;
        terminalView.setTextSize(Math.round(px));
    }

    // ---------------------------------------------------------------- TerminalSessionClient

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        if (changedSession == terminalView.getCurrentSession()) terminalView.onScreenUpdated();
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession changedSession) {
        if (changedSession == terminalView.getCurrentSession()) updateTitle();
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        // Shell exited (e.g. user typed "exit"): drop the session.
        removeSession(finishedSession);
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || session == null) return;
        ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
        CharSequence text = item.coerceToText(this);
        if (text != null && text.length() > 0) {
            session.getEmulator().paste(text.toString());
        }
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) v.vibrate(50);
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession session) {
        if (session == terminalView.getCurrentSession()) terminalView.invalidate();
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        // Cursor blink is not used in this minimal app.
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession session, int pid) {
        // Not needed.
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE;
    }

    // ---------------------------------------------------------------- TerminalViewClient

    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            float delta = scale > 1f ? 1f : -1f;
            fontDp = Math.max(MIN_FONT_DP, Math.min(MAX_FONT_DP, fontDp + delta));
            applyFontSize();
            return 1.0f;
        }
        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        terminalView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return true; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean copyMode) { }
    @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) { return false; }
    @Override public boolean onKeyUp(int keyCode, KeyEvent e) { return false; }
    @Override public boolean onLongPress(MotionEvent event) { return false; }

    // Sticky modifiers are read several times for a single key press, so they are cleared
    // shortly after the first read instead of immediately.
    @Override public boolean readControlKey() { return readModifier(ctrlActive); }
    @Override public boolean readAltKey() { return readModifier(altActive); }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }

    private boolean readModifier(boolean active) {
        if (active) handler.postDelayed(this::clearModifiers, 150);
        return active;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {
        updateTitle();
    }

    // ---------------------------------------------------------------- logging (both interfaces)

    @Override public void logError(String tag, String message) { Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(tag, message, e); }
    @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "error", e); }
}
