package com.example.termuxlite;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.Patterns;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Main Activity for BoomAux (Termux Lite).
 * Supports multi-session tabs, responsive UI, WakeLock toggle, URL tapping, and customizable fonts.
 */
public class MainActivity extends AppCompatActivity implements TerminalSessionClient, TerminalViewClient {

    private static final String TAG = "BoomAux";
    private static final int MIN_FONT_DP = 8;
    private static final int MAX_FONT_DP = 40;

    private TerminalView terminalView;
    private TextView sessionTitle;
    private Button btnWakeLock;
    private Button btnFolderShortcuts;
    private Button btnSettings;
    private Button keyCtrl, keyAlt;

    private View bootstrapStatusLayout;
    private TextView bootstrapStatusText;
    private ProgressBar bootstrapProgressBar;
    private Button btnRetryBootstrap;

    private TermuxService termuxService;
    private boolean isBound = false;

    private boolean ctrlActive = false;
    private boolean altActive = false;
    private float fontDp = 14f;

    private boolean pendingNewSession = false;

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> settingsLauncher;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TermuxService.LocalBinder binder = (TermuxService.LocalBinder) service;
            termuxService = binder.getService();
            isBound = true;
            termuxService.setActiveClient(MainActivity.this);
            termuxService.setSessionListener(new TermuxService.ServiceSessionListener() {
                @Override
                public void onSessionListChanged() {
                    runOnUiThread(() -> {
                        if (termuxService != null && termuxService.isReplacingSessions()) {
                            return; // sessions are being swapped, do not close the activity
                        }
                        if (termuxService == null || termuxService.getSessions().isEmpty()) {
                            finish();
                        } else {
                            attachCurrentSession();
                        }
                    });
                }

                @Override
                public void onSessionTitleChanged(TerminalSession session) {
                    runOnUiThread(() -> updateTitle());
                }

                @Override
                public void onSessionScreenUpdated(TerminalSession session) {
                    if (termuxService != null && session == termuxService.getCurrentSession()) {
                        runOnUiThread(() -> terminalView.onScreenUpdated());
                    }
                }
            });

            updateWakeLockUi();
            applySavedPreferences();

            boolean hadSessions = !termuxService.getSessions().isEmpty();
            if (!hadSessions || pendingNewSession) {
                newSession();
            } else {
                attachCurrentSession();
            }
            pendingNewSession = false;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            termuxService = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashHandler.init(this);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("boomaux_prefs", MODE_PRIVATE);
        pendingNewSession = getIntent() != null
                && TermuxService.ACTION_NEW_SESSION.equals(getIntent().getAction());

        terminalView = findViewById(R.id.terminalView);
        sessionTitle = findViewById(R.id.sessionTitle);
        btnWakeLock = findViewById(R.id.btnWakeLock);
        btnFolderShortcuts = findViewById(R.id.btnFolderShortcuts);
        btnSettings = findViewById(R.id.btnSettings);
        keyCtrl = findViewById(R.id.keyCtrl);
        keyAlt = findViewById(R.id.keyAlt);

        bootstrapStatusLayout = findViewById(R.id.bootstrapStatusLayout);
        bootstrapStatusText = findViewById(R.id.bootstrapStatusText);
        bootstrapProgressBar = findViewById(R.id.bootstrapProgressBar);
        btnRetryBootstrap = findViewById(R.id.btnRetryBootstrap);

        btnRetryBootstrap.setOnClickListener(v -> checkAndInstallBootstrap());

        terminalView.setTerminalViewClient(this);

        settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    applySavedPreferences();
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        boolean resetEnv = result.getData().getBooleanExtra("reset_environment", false);
                        if (resetEnv) {
                            boolean wipeHome = result.getData().getBooleanExtra("wipe_home", false);
                            performResetEnvironment(wipeHome);
                        }
                    }
                }
        );

        setupButtons();
        applySavedPreferences();

        terminalView.requestFocus();
        checkAndInstallBootstrap();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        Intent serviceIntent = new Intent(this, TermuxService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && TermuxService.ACTION_NEW_SESSION.equals(intent.getAction())) {
            if (termuxService != null) {
                newSession();
            } else {
                pendingNewSession = true;
            }
        }
    }

    private void applySavedPreferences() {
        fontDp = prefs.getInt("font_size", 14);
        applyFontSize();

        // Apply Font family
        String fontChoice = prefs.getString("font_family", "default");
        Typeface tf = Typeface.MONOSPACE;
        if ("jetbrains".equals(fontChoice)) {
            try {
                tf = Typeface.createFromAsset(getAssets(), "fonts/jetbrains_mono.ttf");
            } catch (Exception ignored) {}
        } else if ("fira".equals(fontChoice)) {
            try {
                tf = Typeface.createFromAsset(getAssets(), "fonts/fira_code.ttf");
            } catch (Exception ignored) {}
        }
        terminalView.setTypeface(tf);

        // Apply Color Scheme
        if (termuxService != null) {
            ColorThemeHelper.loadAndApplySavedTheme(this, termuxService.getSessions());
        }

        updateWakeLockUi();
    }

    private void updateWakeLockUi() {
        if (termuxService == null || btnWakeLock == null) return;
        boolean wakeHeld = termuxService.isWakeLockHeld();
        btnWakeLock.setTextColor(wakeHeld ? 0xFF00E676 : 0xFF777777);
        btnWakeLock.setContentDescription(getString(wakeHeld ? R.string.wakelock_active : R.string.wakelock_inactive));
    }

    private void toggleWakeLock() {
        if (termuxService == null) return;
        boolean newState = !termuxService.isWakeLockHeld();
        termuxService.setWakeLock(newState);
        updateWakeLockUi();
        Toast.makeText(this, newState ? R.string.wakelock_toggle_on : R.string.wakelock_toggle_off, Toast.LENGTH_SHORT).show();
    }

    private void checkAndInstallBootstrap() {
        if (BootstrapInstaller.isInstalled(this)) {
            bootstrapStatusLayout.setVisibility(View.GONE);
            BootstrapInstaller.ensureDirectories(this);
            return;
        }

        bootstrapStatusLayout.setVisibility(View.VISIBLE);
        bootstrapProgressBar.setIndeterminate(true);
        bootstrapStatusText.setText(R.string.bootstrap_installing);
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
                    Toast.makeText(MainActivity.this, R.string.bootstrap_ready, Toast.LENGTH_SHORT).show();
                    replaceAllSessions();
                    if (!isFinishing()) promptFirstRunDialog();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    bootstrapProgressBar.setIndeterminate(false);
                    bootstrapStatusText.setText(getString(R.string.bootstrap_error, error));
                    btnRetryBootstrap.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void promptFirstRunDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.first_run_dialog_title)
                .setMessage(R.string.first_run_dialog_msg)
                .setPositiveButton(R.string.action_yes, (d, w) -> {
                    TerminalSession current = terminalView.getCurrentSession();
                    if (current != null) {
                        Toast.makeText(this, R.string.first_run_installing, Toast.LENGTH_SHORT).show();
                        current.write("pkg update -y && pkg install -y python git\n");
                    }
                })
                .setNegativeButton(R.string.action_no, null)
                .show();
    }

    /**
     * Replaces every running session with one fresh session. The service keeps running and the
     * activity stays open while this happens (see TermuxService.setReplacingSessions).
     */
    private void replaceAllSessions() {
        if (termuxService == null) return;
        termuxService.setReplacingSessions(true);
        try {
            for (TerminalSession s : termuxService.getSessions()) {
                termuxService.removeSession(s);
            }
            newSession();
        } finally {
            termuxService.setReplacingSessions(false);
        }
    }

    private void performResetEnvironment(boolean wipeHome) {
        if (termuxService != null) {
            // Stop all shells (they run from the environment that is about to be replaced) but keep
            // the service and this activity alive until the reset has finished.
            termuxService.setReplacingSessions(true);
            for (TerminalSession s : termuxService.getSessions()) {
                termuxService.removeSession(s);
            }
        }
        bootstrapStatusLayout.setVisibility(View.VISIBLE);
        bootstrapProgressBar.setIndeterminate(true);
        btnRetryBootstrap.setVisibility(View.GONE);

        BootstrapInstaller.resetEnvironment(this, wipeHome, new BootstrapInstaller.Callback() {
            @Override
            public void onProgress(long current, long total, String message) {
                runOnUiThread(() -> {
                    bootstrapProgressBar.setIndeterminate(false);
                    if (total > 0) {
                        bootstrapProgressBar.setProgress((int) ((current * 100) / total));
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
                    Toast.makeText(MainActivity.this, R.string.bootstrap_ready, Toast.LENGTH_SHORT).show();
                    if (termuxService != null) termuxService.setReplacingSessions(false);
                    newSession();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    bootstrapProgressBar.setIndeterminate(false);
                    bootstrapStatusText.setText(getString(R.string.bootstrap_error, error));
                    btnRetryBootstrap.setVisibility(View.VISIBLE);
                    if (termuxService != null) {
                        termuxService.setReplacingSessions(false);
                        // Give the user a shell again (the old environment is kept on failure).
                        if (termuxService.getSessions().isEmpty()) newSession();
                    }
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (termuxService != null) {
            termuxService.setActiveClient(this);
            attachCurrentSession();
            updateWakeLockUi();
        }
    }

    @Override
    protected void onStop() {
        if (termuxService != null) {
            termuxService.setActiveClient(null);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (isBound) {
            if (termuxService != null) {
                termuxService.setActiveClient(null);
                termuxService.setSessionListener(null);
            }
            unbindService(serviceConnection);
            isBound = false;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- environment & sessions

    public static String[] buildStaticEnvironment(Context context) {
        File filesDir = context.getFilesDir();
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
        env.add("PATH=" + binDir + ":" + appletsDir + ":/system/bin");
        env.add("ANDROID_ROOT=/system");
        env.add("ANDROID_DATA=/data");
        env.add("EXTERNAL_STORAGE=/sdcard");
        env.add("TERMUX_APP_PACKAGE_MANAGER=apt");
        env.add("SSL_CERT_FILE=" + prefix + "/etc/tls/cert.pem");
        env.add("SSL_CERT_DIR=" + prefix + "/etc/ssl/certs");
        env.add("CURL_CA_BUNDLE=" + prefix + "/etc/tls/cert.pem");
        env.add("GIT_SSL_CAINFO=" + prefix + "/etc/tls/cert.pem");
        env.add("REQUESTS_CA_BUNDLE=" + prefix + "/etc/tls/cert.pem");
        env.add("CURL_HOME=" + home.getAbsolutePath());

        File bash = new File(binDir, "bash");
        if (bash.exists()) {
            env.add("SHELL=" + bash.getAbsolutePath());
        } else {
            env.add("SHELL=/system/bin/sh");
        }

        // Standard Termux-exec preload if present
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
        if (termuxService == null) return;
        File usr = new File(getFilesDir(), "usr");
        File bash = new File(usr, "bin/bash");
        File home = new File(getFilesDir(), "home");
        if (!home.exists()) home.mkdirs();

        String shell = bash.exists() ? bash.getAbsolutePath() : "/system/bin/sh";
        String cwd = bash.exists() ? home.getAbsolutePath() : getFilesDir().getAbsolutePath();
        String[] args = bash.exists() ? new String[]{"bash", "-l"} : new String[]{"sh"};

        termuxService.createSession(shell, cwd, args, buildStaticEnvironment(this),
                "Session " + (termuxService.getSessions().size() + 1));
        attachCurrentSession();
    }

    private void attachCurrentSession() {
        if (termuxService == null) return;
        TerminalSession session = termuxService.getCurrentSession();
        if (session != null) {
            terminalView.attachSession(session);
            terminalView.onScreenUpdated();
            updateTitle();
        }
    }

    private void switchTo(int index) {
        if (termuxService == null || termuxService.getSessions().isEmpty()) return;
        termuxService.setCurrentSessionIndex(index);
        attachCurrentSession();
    }

    private void closeCurrent() {
        if (termuxService == null || termuxService.getSessions().isEmpty()) return;
        TerminalSession current = termuxService.getCurrentSession();
        if (current != null) {
            termuxService.removeSession(current);
        }
        if (termuxService.getSessions().isEmpty()) {
            finish();
        } else {
            attachCurrentSession();
        }
    }

    private void updateTitle() {
        if (termuxService == null || termuxService.getSessions().isEmpty()) return;
        int current = termuxService.getCurrentSessionIndex();
        int total = termuxService.getSessions().size();
        TerminalSession s = termuxService.getCurrentSession();
        if (s == null) return;
        String t = s.getTitle();
        String label = (t == null || t.isEmpty()) ? s.mSessionName : t;
        sessionTitle.setText(getString(R.string.session_count_format, (current + 1), total, label));
    }

    private void promptRenameSession() {
        if (termuxService == null) return;
        TerminalSession current = termuxService.getCurrentSession();
        if (current == null) return;

        final EditText input = new EditText(this);
        input.setHint(R.string.session_rename_hint);
        input.setText(current.mSessionName);
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle(R.string.session_rename_title)
                .setView(input)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        current.mSessionName = name;
                        updateTitle();
                        if (termuxService != null) termuxService.updateNotification();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showFolderShortcuts() {
        // labels[i] is what the user sees, targets[i] is the directory that is cd'ed into.
        final List<String> labels = new ArrayList<>();
        final List<String> targets = new ArrayList<>();
        labels.add("~ (Home)");                                 targets.add("~");
        labels.add("~/storage/shared (Internal Storage)");      targets.add("~/storage/shared");
        labels.add("~/storage/downloads (Downloads)");          targets.add("~/storage/downloads");
        labels.add("~/storage/dcim (DCIM)");                    targets.add("~/storage/dcim");

        Set<String> custom = prefs.getStringSet("custom_folders", null);
        if (custom != null) {
            for (String path : new java.util.TreeSet<>(custom)) {
                labels.add(path);
                targets.add(path);
            }
        }

        final int addIndex = labels.size();
        final String[] items = new String[addIndex + 1];
        for (int i = 0; i < addIndex; i++) items[i] = labels.get(i);
        items[addIndex] = "+ " + getString(R.string.shortcut_add);

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_folder_shortcuts)
                .setItems(items, (dialog, which) -> {
                    if (which == addIndex) {
                        promptAddFolderShortcut();
                    } else {
                        TerminalSession s = terminalView.getCurrentSession();
                        if (s != null) {
                            s.write("cd " + quotePathForShell(targets.get(which)) + "\n");
                        }
                    }
                })
                .show();
    }

    /** Quotes a path for the shell while keeping a leading "~" expandable. Handles spaces and quotes. */
    private static String quotePathForShell(String path) {
        if ("~".equals(path)) return "~";
        String prefix = "";
        String rest = path;
        if (path.startsWith("~/")) {
            prefix = "~/";
            rest = path.substring(2);
        }
        return prefix + "'" + rest.replace("'", "'\\''") + "'";
    }

    private void promptAddFolderShortcut() {
        final EditText input = new EditText(this);
        input.setHint(R.string.shortcut_hint);

        new AlertDialog.Builder(this)
                .setTitle(R.string.shortcut_add)
                .setView(input)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String path = input.getText().toString().trim();
                    if (!path.isEmpty()) {
                        Set<String> custom = new HashSet<>(prefs.getStringSet("custom_folders", new HashSet<>()));
                        custom.add(path);
                        prefs.edit().putStringSet("custom_folders", custom).apply();
                        Toast.makeText(this, "Shortcut saved", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- buttons & keys

    private void setupButtons() {
        sessionTitle.setOnClickListener(v -> promptRenameSession());
        btnWakeLock.setOnClickListener(v -> toggleWakeLock());
        btnFolderShortcuts.setOnClickListener(v -> showFolderShortcuts());
        btnSettings.setOnClickListener(v -> settingsLauncher.launch(new Intent(this, SettingsActivity.class)));

        findViewById(R.id.btnNew).setOnClickListener(v -> newSession());
        findViewById(R.id.btnClose).setOnClickListener(v -> closeCurrent());
        findViewById(R.id.btnPrev).setOnClickListener(v -> {
            if (termuxService != null) switchTo(termuxService.getCurrentSessionIndex() - 1);
        });
        findViewById(R.id.btnNext).setOnClickListener(v -> {
            if (termuxService != null) switchTo(termuxService.getCurrentSessionIndex() + 1);
        });

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
        View v = findViewById(id);
        if (v == null) return;
        v.setOnClickListener(view -> {
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
        View v = findViewById(id);
        if (v == null) return;
        v.setOnClickListener(view -> {
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
        keyCtrl.setBackgroundColor(ctrlActive ? 0xFF00E676 : 0x00000000);
        keyCtrl.setTextColor(ctrlActive ? 0xFF000000 : 0xFFE0E0E0);
        keyAlt.setBackgroundColor(altActive ? 0xFF00E676 : 0x00000000);
        keyAlt.setTextColor(altActive ? 0xFF000000 : 0xFFE0E0E0);
    }

    private void applyFontSize() {
        float px = fontDp * getResources().getDisplayMetrics().density;
        terminalView.setTextSize(Math.round(px));
    }

    // ---------------------------------------------------------------- Volume Key Modifiers

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean volumeKeysEnabled = prefs.getBoolean("volume_keys_modifiers", false);
        if (volumeKeysEnabled) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                ctrlActive = !ctrlActive;
                refreshModifierButtons();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                altActive = !altActive;
                refreshModifierButtons();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean volumeKeysEnabled = prefs.getBoolean("volume_keys_modifiers", false);
        if (volumeKeysEnabled && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ---------------------------------------------------------------- Back button behavior

    @Override
    public void onBackPressed() {
        String backAction = prefs.getString("back_action", "minimize");
        if ("escape".equals(backAction)) {
            TerminalSession s = terminalView.getCurrentSession();
            if (s != null) {
                terminalView.handleKeyCode(KeyEvent.KEYCODE_ESCAPE, 0);
            }
        } else if ("close".equals(backAction)) {
            closeCurrent();
        } else {
            moveTaskToBack(true);
        }
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
        // TermuxService already removed the session and notifies us through its session listener.
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
            Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || session == null) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
        CharSequence text = item.coerceToText(this);
        if (text != null && text.length() > 0) {
            session.getEmulator().paste(text.toString());
        }
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        boolean vibrate = prefs.getBoolean("vibrate_on_bell", true);
        if (vibrate) {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(50);
        }
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession session) {
        if (session == terminalView.getCurrentSession()) terminalView.invalidate();
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {}

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession session, int pid) {}

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
            prefs.edit().putInt("font_size", Math.round(fontDp)).apply();
            return 1.0f;
        }
        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        TerminalSession session = terminalView.getCurrentSession();
        if (session != null && session.getEmulator() != null) {
            int[] colRow = terminalView.getColumnAndRow(e, true);
            String word = session.getEmulator().getScreen().getWordAtLocation(colRow[0], colRow[1]);
            String url = extractUrl(word);
            if (url != null) {
                openUrlInBrowser(url);
                return;
            }
        }

        terminalView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        TerminalSession session = terminalView.getCurrentSession();
        String candidateUrl = null;
        if (session != null && session.getEmulator() != null) {
            int[] colRow = terminalView.getColumnAndRow(event, true);
            String word = session.getEmulator().getScreen().getWordAtLocation(colRow[0], colRow[1]);
            candidateUrl = extractUrl(word);
        }

        final String targetUrl = candidateUrl;
        final int urlOffset = targetUrl != null ? 1 : 0;
        List<String> options = new ArrayList<>();
        if (targetUrl != null) {
            options.add(getString(R.string.menu_open_url, targetUrl));
        }
        options.add(getString(R.string.menu_copy_all));
        options.add(getString(R.string.menu_paste));
        options.add(getString(R.string.menu_rename_session));
        options.add(getString(R.string.menu_reset_terminal));
        options.add(getString(R.string.menu_kill_process));

        final String[] items = options.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle(R.string.terminal_menu_title)
                .setItems(items, (dialog, which) -> {
                    if (targetUrl != null && which == 0) {
                        openUrlInBrowser(targetUrl);
                        return;
                    }
                    switch (which - urlOffset) {
                        case 0: // copy all
                            if (session != null && session.getEmulator() != null) {
                                String text = session.getEmulator().getScreen().getTranscriptText();
                                onCopyTextToClipboard(session, text);
                            }
                            break;
                        case 1: // paste
                            onPasteTextFromClipboard(session);
                            break;
                        case 2: // rename session
                            promptRenameSession();
                            break;
                        case 3: // reset terminal
                            if (session != null) {
                                session.reset();
                                terminalView.invalidate();
                            }
                            break;
                        case 4: // kill process (Ctrl+C)
                            if (session != null) {
                                session.write(new byte[]{3}, 0, 1);
                            }
                            break;
                        default:
                            break;
                    }
                })
                .show();

        return true;
    }

    /**
     * Only treats text that really looks like a link as a URL (http://, https:// or www.).
     * Patterns.WEB_URL alone matches file names such as main.py, readme.md or run.sh because
     * py/md/sh are country TLDs, which made tapping a file name open the browser.
     */
    private static String extractUrl(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.isEmpty()) return null;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int start = lower.indexOf("http://");
        if (start < 0) start = lower.indexOf("https://");
        if (start < 0) start = lower.indexOf("www.");
        if (start < 0) return null;

        Matcher matcher = Patterns.WEB_URL.matcher(text.substring(start));
        if (matcher.find() && matcher.start() == 0) {
            String match = matcher.group();
            String matchLower = match.toLowerCase(java.util.Locale.ROOT);
            if (!matchLower.startsWith("http://") && !matchLower.startsWith("https://")) {
                match = "https://" + match;
            }
            return match;
        }
        return null;
    }

    private void openUrlInBrowser(String url) {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(browserIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open URL: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return true; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean copyMode) {}
    @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) { return false; }

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

    // ---------------------------------------------------------------- logging

    @Override public void logError(String tag, String message) { Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(tag, message, e); }
    @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "error", e); }
    }
