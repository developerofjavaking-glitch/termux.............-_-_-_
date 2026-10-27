package com.example.termuxlite;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * SettingsActivity managing preferences for fonts, color schemes, WakeLock, boot auto-start,
 * volume modifiers, package mirrors, and storage/backup utilities.
 */
public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    private SeekBar seekBarFontSize;
    private TextView tvFontSizeLabel;
    private Spinner spinnerFontFamily;
    private Spinner spinnerColorScheme;
    private SwitchMaterial switchWakeLockDefault;
    private SwitchMaterial switchBootAutoStart;
    private EditText etBootCommand;
    private MaterialButton btnBatteryOptimization;
    private SwitchMaterial switchVolumeKeys;
    private SwitchMaterial switchVibrateOnBell;
    private Spinner spinnerBackAction;
    private Spinner spinnerMirror;
    private MaterialButton btnResetEnv;
    private MaterialButton btnSetupStorage;
    private MaterialButton btnImportFile;
    private MaterialButton btnBackup;
    private MaterialButton btnRestore;
    private MaterialButton btnShareCrashLog;

    private ActivityResultLauncher<String[]> requestStoragePermissionLauncher;
    private ActivityResultLauncher<String[]> openDocumentImportLauncher;
    private ActivityResultLauncher<String> createBackupLauncher;
    private ActivityResultLauncher<String[]> openRestoreLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("boomaux_prefs", MODE_PRIVATE);

        initViews();
        setupActivityResultLaunchers();
        loadPreferences();
        setupListeners();
    }

    private void initViews() {
        seekBarFontSize = findViewById(R.id.seekBarFontSize);
        tvFontSizeLabel = findViewById(R.id.tvFontSizeLabel);
        spinnerFontFamily = findViewById(R.id.spinnerFontFamily);
        spinnerColorScheme = findViewById(R.id.spinnerColorScheme);
        switchWakeLockDefault = findViewById(R.id.switchWakeLockDefault);
        switchBootAutoStart = findViewById(R.id.switchBootAutoStart);
        etBootCommand = findViewById(R.id.etBootCommand);
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);
        switchVolumeKeys = findViewById(R.id.switchVolumeKeys);
        switchVibrateOnBell = findViewById(R.id.switchVibrateOnBell);
        spinnerBackAction = findViewById(R.id.spinnerBackAction);
        spinnerMirror = findViewById(R.id.spinnerMirror);
        btnResetEnv = findViewById(R.id.btnResetEnv);
        btnSetupStorage = findViewById(R.id.btnSetupStorage);
        btnImportFile = findViewById(R.id.btnImportFile);
        btnBackup = findViewById(R.id.btnBackup);
        btnRestore = findViewById(R.id.btnRestore);
        btnShareCrashLog = findViewById(R.id.btnShareCrashLog);
    }

    private void setupActivityResultLaunchers() {
        requestStoragePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean granted = true;
                    for (Boolean b : result.values()) {
                        if (!b) { granted = false; break; }
                    }
                    if (granted) {
                        doSetupStorage();
                    } else {
                        Toast.makeText(this, R.string.storage_perm_required, Toast.LENGTH_SHORT).show();
                    }
                }
        );

        openDocumentImportLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        try {
                            String fileName = "imported_file";
                            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                            if (cursor != null && cursor.moveToFirst()) {
                                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                                if (nameIndex >= 0) fileName = cursor.getString(nameIndex);
                                cursor.close();
                            }
                            StorageHelper.copyUriToHome(this, uri, fileName);
                            Toast.makeText(this, getString(R.string.import_success) + " (" + fileName + ")", Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                }
        );

        createBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"),
                uri -> {
                    if (uri != null) {
                        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                            if (out != null) {
                                StorageHelper.backupHomeToZip(this, out);
                                Toast.makeText(this, R.string.backup_success, Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            Toast.makeText(this, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                }
        );

        openRestoreLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        try (InputStream in = getContentResolver().openInputStream(uri)) {
                            if (in != null) {
                                StorageHelper.restoreHomeFromZip(this, in);
                                Toast.makeText(this, R.string.restore_success, Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            Toast.makeText(this, "Restore failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                }
        );
    }

    private void loadPreferences() {
        // Font Size (min 8, max 40 -> seekbar progress 0..32, fontDp = progress + 8)
        int fontSize = prefs.getInt("font_size", 14);
        seekBarFontSize.setProgress(Math.max(0, Math.min(32, fontSize - 8)));
        tvFontSizeLabel.setText(getString(R.string.pref_font_size, fontSize));

        // Font Family Spinner
        String[] fontNames = {
                getString(R.string.font_default),
                getString(R.string.font_jetbrains),
                getString(R.string.font_fira)
        };
        ArrayAdapter<String> fontAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, fontNames);
        spinnerFontFamily.setAdapter(fontAdapter);
        String savedFont = prefs.getString("font_family", "default");
        if ("jetbrains".equals(savedFont)) spinnerFontFamily.setSelection(1);
        else if ("fira".equals(savedFont)) spinnerFontFamily.setSelection(2);
        else spinnerFontFamily.setSelection(0);

        // Color Scheme Spinner
        String[] schemeNames = {
                getString(R.string.theme_default),
                getString(R.string.theme_dracula),
                getString(R.string.theme_solarized),
                getString(R.string.theme_monokai),
                getString(R.string.theme_light)
        };
        ArrayAdapter<String> schemeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, schemeNames);
        spinnerColorScheme.setAdapter(schemeAdapter);
        String savedTheme = prefs.getString("color_scheme", ColorThemeHelper.THEME_DEFAULT);
        switch (savedTheme) {
            case ColorThemeHelper.THEME_DRACULA: spinnerColorScheme.setSelection(1); break;
            case ColorThemeHelper.THEME_SOLARIZED: spinnerColorScheme.setSelection(2); break;
            case ColorThemeHelper.THEME_MONOKAI: spinnerColorScheme.setSelection(3); break;
            case ColorThemeHelper.THEME_LIGHT: spinnerColorScheme.setSelection(4); break;
            default: spinnerColorScheme.setSelection(0); break;
        }

        // Background & Boot
        switchWakeLockDefault.setChecked(prefs.getBoolean("wakelock_default", true));
        switchBootAutoStart.setChecked(prefs.getBoolean("boot_auto_start", false));
        etBootCommand.setText(prefs.getString("boot_command", ""));

        // Input
        switchVolumeKeys.setChecked(prefs.getBoolean("volume_keys_modifiers", false));
        switchVibrateOnBell.setChecked(prefs.getBoolean("vibrate_on_bell", true));

        // Back action
        String[] backActions = {
                getString(R.string.back_action_minimize),
                getString(R.string.back_action_escape),
                getString(R.string.back_action_close_session)
        };
        ArrayAdapter<String> backAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, backActions);
        spinnerBackAction.setAdapter(backAdapter);
        String savedBackAction = prefs.getString("back_action", "minimize");
        if ("escape".equals(savedBackAction)) spinnerBackAction.setSelection(1);
        else if ("close".equals(savedBackAction)) spinnerBackAction.setSelection(2);
        else spinnerBackAction.setSelection(0);

        // Package Mirror
        String[] mirrorNames = {
                getString(R.string.mirror_cf),
                getString(R.string.mirror_termux),
                getString(R.string.mirror_tsinghua),
                getString(R.string.mirror_bfsu)
        };
        ArrayAdapter<String> mirrorAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, mirrorNames);
        spinnerMirror.setAdapter(mirrorAdapter);
        String savedMirror = prefs.getString("package_mirror", BootstrapInstaller.MIRROR_CLOUDFLARE);
        if (BootstrapInstaller.MIRROR_OFFICIAL.equals(savedMirror)) spinnerMirror.setSelection(1);
        else if (BootstrapInstaller.MIRROR_TSINGHUA.equals(savedMirror)) spinnerMirror.setSelection(2);
        else if (BootstrapInstaller.MIRROR_BFSU.equals(savedMirror)) spinnerMirror.setSelection(3);
        else spinnerMirror.setSelection(0);
    }

    private void setupListeners() {
        seekBarFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int size = progress + 8;
                tvFontSizeLabel.setText(getString(R.string.pref_font_size, size));
                prefs.edit().putInt("font_size", size).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        spinnerFontFamily.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String val = position == 1 ? "jetbrains" : (position == 2 ? "fira" : "default");
                prefs.edit().putString("font_family", val).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerColorScheme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String key;
                switch (position) {
                    case 1: key = ColorThemeHelper.THEME_DRACULA; break;
                    case 2: key = ColorThemeHelper.THEME_SOLARIZED; break;
                    case 3: key = ColorThemeHelper.THEME_MONOKAI; break;
                    case 4: key = ColorThemeHelper.THEME_LIGHT; break;
                    default: key = ColorThemeHelper.THEME_DEFAULT; break;
                }
                prefs.edit().putString("color_scheme", key).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        switchWakeLockDefault.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("wakelock_default", isChecked).apply());

        switchBootAutoStart.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("boot_auto_start", isChecked).apply());

        switchVolumeKeys.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("volume_keys_modifiers", isChecked).apply());

        switchVibrateOnBell.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("vibrate_on_bell", isChecked).apply());

        spinnerBackAction.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String action = position == 1 ? "escape" : (position == 2 ? "close" : "minimize");
                prefs.edit().putString("back_action", action).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerMirror.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String mirrorUrl;
                switch (position) {
                    case 1: mirrorUrl = BootstrapInstaller.MIRROR_OFFICIAL; break;
                    case 2: mirrorUrl = BootstrapInstaller.MIRROR_TSINGHUA; break;
                    case 3: mirrorUrl = BootstrapInstaller.MIRROR_BFSU; break;
                    default: mirrorUrl = BootstrapInstaller.MIRROR_CLOUDFLARE; break;
                }
                prefs.edit().putString("package_mirror", mirrorUrl).apply();
                BootstrapInstaller.updateMirror(SettingsActivity.this, mirrorUrl);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnBatteryOptimization.setOnClickListener(v -> requestBatteryOptimizationExemption());

        btnResetEnv.setOnClickListener(v -> showResetEnvDialog());

        btnSetupStorage.setOnClickListener(v -> checkStorageAndSetup());

        btnImportFile.setOnClickListener(v -> openDocumentImportLauncher.launch(new String[]{"*/*"}));

        btnBackup.setOnClickListener(v -> createBackupLauncher.launch("boomaux_backup.zip"));

        btnRestore.setOnClickListener(v -> openRestoreLauncher.launch(new String[]{"application/zip", "application/octet-stream"}));

        btnShareCrashLog.setOnClickListener(v -> CrashHandler.shareCrashLog(this));
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs.edit().putString("boot_command", etBootCommand.getText().toString().trim()).apply();
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                        startActivity(intent);
                    } catch (Exception ignored) {}
                }
            } else {
                Toast.makeText(this, R.string.battery_already_optimized, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void checkStorageAndSetup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestStoragePermissionLauncher.launch(new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                });
                return;
            }
        }
        doSetupStorage();
    }

    private void doSetupStorage() {
        boolean ok = StorageHelper.setupStorageSymlinks(this);
        if (ok) {
            Toast.makeText(this, R.string.storage_setup_success, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Failed creating storage symlinks", Toast.LENGTH_SHORT).show();
        }
    }

    private void showResetEnvDialog() {
        View dialogView = getLayoutInflater().inflate(android.R.layout.simple_list_item_multiple_choice, null);
        final CheckBox checkBox = new CheckBox(this);
        checkBox.setText(R.string.reset_env_wipe_home_checkbox);
        checkBox.setPadding(30, 20, 20, 20);

        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_env_confirm_title)
                .setMessage(R.string.reset_env_confirm_msg)
                .setView(checkBox)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> {
                    boolean wipeHome = checkBox.isChecked();
                    Intent result = new Intent();
                    result.putExtra("reset_environment", true);
                    result.putExtra("wipe_home", wipeHome);
                    setResult(RESULT_OK, result);
                    finish();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
