package com.seif.screentimeexporter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int STORAGE_REQUEST = 1001;
    private static final int RC_SIGN_IN = 9001;
    private static final int NOTIFICATION_REQUEST = 1003;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView statusView;
    private TextView syncStatusView;
    private TextView resultView;
    private TextView emailView;
    private EditText drivePathInput;
    private GoogleSignInClient mGoogleSignInClient;

    // ── Color palette ──
    private static final int BG_PRIMARY = Color.parseColor("#0D0D0D");
    private static final int BG_CARD = Color.parseColor("#1A1A2E");
    private static final int BG_INPUT = Color.parseColor("#16213E");
    private static final int BORDER_COLOR = Color.parseColor("#2A2A4A");
    private static final int TEXT_PRIMARY = Color.parseColor("#E8EAED");
    private static final int TEXT_SECONDARY = Color.parseColor("#8B8FA3");
    private static final int ACCENT_BLUE = Color.parseColor("#4C8BF5");
    private static final int ACCENT_GREEN = Color.parseColor("#2ECC71");
    private static final int ACCENT_RED = Color.parseColor("#E74C3C");
    private static final int ACCENT_AMBER = Color.parseColor("#F39C12");
    private static final int ACCENT_PURPLE = Color.parseColor("#9B59B6");
    private static final int RIPPLE_COLOR = Color.parseColor("#33FFFFFF");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Dark status bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(BG_PRIMARY);
            window.setNavigationBarColor(BG_PRIMARY);
        }

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope("https://www.googleapis.com/auth/drive"))
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        setContentView(buildUi());
        ReportScheduler.scheduleNext(this);
        ReportScheduler.runCatchUpAsync(this, null);
        requestNotifications();
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        if (PermissionUtils.hasUsageAccess(this) && PermissionUtils.hasStorageAccess(this)
                && !ReportScheduler.isLocalDoneToday(this)) {
            ReportScheduler.runCatchUpAsync(this, null);
        }
    }

    // ════════════════════════════════════════════════
    //  BUILD UI - SIMPLIFIED
    // ════════════════════════════════════════════════

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG_PRIMARY);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(32), dp(20), dp(32));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        scroll.addView(root);

        // ── Header ──
        TextView title = text("Screen Time Exporter", 26, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setTextColor(TEXT_PRIMARY);
        title.setLetterSpacing(0.02f);
        root.addView(title, matchWrap());

        TextView subtitle = text("Automated background tracker", 13, false);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setTextColor(TEXT_SECONDARY);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(subtitle, matchWrap());

        // ── Sync Status ──
        syncStatusView = text("Last Sync: Loading...", 13, true);
        syncStatusView.setGravity(Gravity.CENTER_HORIZONTAL);
        syncStatusView.setTextColor(TEXT_SECONDARY);
        syncStatusView.setPadding(0, 0, 0, dp(16));
        root.addView(syncStatusView, matchWrap());

        // ── Status Card ──
        LinearLayout statusCard = createCard();
        statusView = text("Loading...", 13, false);
        statusView.setTextColor(TEXT_PRIMARY);
        statusView.setLineSpacing(dp(3), 1f);
        statusCard.addView(statusView, matchWrap());
        root.addView(statusCard, matchWrapWithMargin(0));

        // ── Section: Setup ──
        root.addView(sectionTitle("Setup"), matchWrapWithMargin(24));

        Button usageBtn = accentButton("Grant Usage Access", ACCENT_BLUE);
        usageBtn.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            }
        });
        root.addView(usageBtn, matchWrapWithMargin(8));

        Button storageBtn = accentButton("Grant Storage Access", ACCENT_BLUE);
        storageBtn.setOnClickListener(v -> requestStorage());
        root.addView(storageBtn, matchWrapWithMargin(6));

        Button batteryBtn = accentButton("Disable Battery Optimization", ACCENT_RED);
        batteryBtn.setOnClickListener(v -> requestBatteryOptimization());
        root.addView(batteryBtn, matchWrapWithMargin(6));

        // ── Section: Google Drive ──
        root.addView(sectionTitle("Google Drive"), matchWrapWithMargin(24));

        emailView = text("Not connected", 13, false);
        emailView.setTextColor(TEXT_SECONDARY);
        emailView.setPadding(dp(4), 0, 0, 0);
        root.addView(emailView, matchWrapWithMargin(4));

        LinearLayout authRow = new LinearLayout(this);
        authRow.setOrientation(LinearLayout.HORIZONTAL);

        Button signInBtn = accentButton("Sign In", ACCENT_BLUE);
        signInBtn.setOnClickListener(v -> {
            Intent signInIntent = mGoogleSignInClient.getSignInIntent();
            startActivityForResult(signInIntent, RC_SIGN_IN);
        });
        LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        bp1.rightMargin = dp(4);
        authRow.addView(signInBtn, bp1);

        Button signOutBtn = accentButton("Sign Out", Color.parseColor("#555555"));
        signOutBtn.setOnClickListener(v -> mGoogleSignInClient.signOut().addOnCompleteListener(this, task -> updateStatus()));
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        bp2.leftMargin = dp(4);
        authRow.addView(signOutBtn, bp2);

        root.addView(authRow, matchWrapWithMargin(8));

        drivePathInput = createInput("Drive Folder Name");
        drivePathInput.setText(GoogleDriveUploader.getDriveFolderName(this));
        root.addView(drivePathInput, matchWrapWithMargin(6));

        LinearLayout driveRow = new LinearLayout(this);
        driveRow.setOrientation(LinearLayout.HORIZONTAL);

        Button browseBtn = accentButton("Browse", ACCENT_PURPLE);
        browseBtn.setOnClickListener(v -> browseDriveFolders());
        driveRow.addView(browseBtn, bp1);

        Button saveDriveBtn = accentButton("Save Folder", ACCENT_GREEN);
        saveDriveBtn.setOnClickListener(v -> {
            String path = drivePathInput.getText().toString().trim();
            GoogleDriveUploader.setDriveFolderName(this, path);
            Toast.makeText(this, "Folder saved", Toast.LENGTH_SHORT).show();
            updateStatus();
        });
        driveRow.addView(saveDriveBtn, bp2);

        root.addView(driveRow, matchWrapWithMargin(6));

        // ── Section: Schedule ──
        root.addView(sectionTitle("Schedule"), matchWrapWithMargin(24));

        Button localTimeBtn = accentButton(fmtLocalTime(), ACCENT_AMBER);
        localTimeBtn.setOnClickListener(v -> showTimePicker(true, localTimeBtn));
        root.addView(localTimeBtn, matchWrapWithMargin(8));

        Button driveTimeBtn = accentButton(fmtDriveTime(), ACCENT_AMBER);
        driveTimeBtn.setOnClickListener(v -> showTimePicker(false, driveTimeBtn));
        root.addView(driveTimeBtn, matchWrapWithMargin(6));

        // ── Interval Sync ──
        LinearLayout intervalCard = createCard();

        LinearLayout intervalToggleRow = new LinearLayout(this);
        intervalToggleRow.setOrientation(LinearLayout.HORIZONTAL);
        intervalToggleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView intervalLabel = text("Repeat Sync Every", 14, true);
        intervalLabel.setTextColor(TEXT_PRIMARY);
        intervalToggleRow.addView(intervalLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Switch intervalSwitch = new Switch(this);
        intervalSwitch.setChecked(ReportScheduler.isIntervalEnabled(this));
        intervalSwitch.setTextColor(TEXT_PRIMARY);
        intervalToggleRow.addView(intervalSwitch);

        intervalCard.addView(intervalToggleRow, matchWrap());

        // Interval hours selector
        int currentInterval = ReportScheduler.getIntervalHours(this);
        TextView intervalValue = text(currentInterval + "h", 18, true);
        intervalValue.setTextColor(ACCENT_AMBER);
        intervalValue.setGravity(Gravity.CENTER);
        intervalValue.setPadding(0, dp(8), 0, dp(4));

        LinearLayout intervalBtnRow = new LinearLayout(this);
        intervalBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        intervalBtnRow.setGravity(Gravity.CENTER);

        Button minusBtn = accentButton("-", Color.parseColor("#555555"));
        minusBtn.setOnClickListener(v -> {
            int h = ReportScheduler.getIntervalHours(this);
            if (h > 1) {
                ReportScheduler.setIntervalHours(this, h - 1);
                intervalValue.setText((h - 1) + "h");
            }
        });
        LinearLayout.LayoutParams smallBtnParams = new LinearLayout.LayoutParams(dp(56), dp(40));
        smallBtnParams.rightMargin = dp(12);
        intervalBtnRow.addView(minusBtn, smallBtnParams);

        intervalBtnRow.addView(intervalValue, new LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT));

        Button plusBtn = accentButton("+", Color.parseColor("#555555"));
        plusBtn.setOnClickListener(v -> {
            int h = ReportScheduler.getIntervalHours(this);
            if (h < 12) {
                ReportScheduler.setIntervalHours(this, h + 1);
                intervalValue.setText((h + 1) + "h");
            }
        });
        LinearLayout.LayoutParams smallBtnParams2 = new LinearLayout.LayoutParams(dp(56), dp(40));
        smallBtnParams2.leftMargin = dp(12);
        intervalBtnRow.addView(plusBtn, smallBtnParams2);

        intervalCard.addView(intervalBtnRow, matchWrapWithMargin(4));

        intervalSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ReportScheduler.setIntervalEnabled(this, isChecked);
            Toast.makeText(this, isChecked ? "Interval sync enabled" : "Interval sync disabled", Toast.LENGTH_SHORT).show();
            updateStatus();
        });

        root.addView(intervalCard, matchWrapWithMargin(10));

        // ── Section: Manual Actions (only essential ones) ──
        root.addView(sectionTitle("Manual Actions"), matchWrapWithMargin(24));

        Button testUploadBtn = accentButton("Test Drive Upload", ACCENT_BLUE);
        testUploadBtn.setOnClickListener(v -> testDriveUpload());
        root.addView(testUploadBtn, matchWrapWithMargin(8));

        Button historyBtn = accentButton("Rewrite Full History", Color.parseColor("#555555"));
        historyBtn.setOnClickListener(v -> runAllHistoryExport());
        root.addView(historyBtn, matchWrapWithMargin(6));

        // ── Result Card ──
        LinearLayout resultCard = createCard();
        resultView = text(
                "Export: " + UsageReportGenerator.outputDirectory(this).getAbsolutePath(),
                12, false
        );
        resultView.setTextIsSelectable(true);
        resultView.setTextColor(TEXT_SECONDARY);
        resultCard.addView(resultView, matchWrap());
        root.addView(resultCard, matchWrapWithMargin(20));

        return scroll;
    }

    // ════════════════════════════════════════════════
    //  UI COMPONENTS
    // ════════════════════════════════════════════════

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_CARD);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), BORDER_COLOR);
        card.setBackground(bg);
        card.setElevation(dp(4));
        return card;
    }

    private EditText createInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextSize(14);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setTextColor(TEXT_PRIMARY);
        input.setHintTextColor(Color.parseColor("#555566"));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_INPUT);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), BORDER_COLOR);
        input.setBackground(bg);
        return input;
    }

    private Button accentButton(String label, int color) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(13);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setPadding(dp(16), dp(13), dp(16), dp(13));
        btn.setElevation(dp(2));

        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(10));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            RippleDrawable ripple = new RippleDrawable(
                    ColorStateList.valueOf(RIPPLE_COLOR), shape, null);
            btn.setBackground(ripple);
        } else {
            btn.setBackground(shape);
        }

        btn.setStateListAnimator(null);
        return btn;
    }

    private TextView sectionTitle(String titleText) {
        TextView tv = new TextView(this);
        tv.setText(titleText.toUpperCase(Locale.US));
        tv.setTextSize(11);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setTextColor(ACCENT_BLUE);
        tv.setLetterSpacing(0.12f);
        tv.setPadding(dp(4), 0, 0, dp(2));
        return tv;
    }

    // ════════════════════════════════════════════════
    //  SCHEDULE
    // ════════════════════════════════════════════════

    private String fmtLocalTime() {
        return String.format(Locale.US, "Local Export  %02d:%02d",
                ReportScheduler.getLocalHour(this), ReportScheduler.getLocalMinute(this));
    }

    private String fmtDriveTime() {
        return String.format(Locale.US, "Drive Upload  %02d:%02d",
                ReportScheduler.getDriveHour(this), ReportScheduler.getDriveMinute(this));
    }

    private void showTimePicker(boolean isLocal, Button btn) {
        int h = isLocal ? ReportScheduler.getLocalHour(this) : ReportScheduler.getDriveHour(this);
        int m = isLocal ? ReportScheduler.getLocalMinute(this) : ReportScheduler.getDriveMinute(this);

        new TimePickerDialog(this, (view, hh, mm) -> {
            if (isLocal) {
                ReportScheduler.setLocalTime(this, hh, mm);
                btn.setText(fmtLocalTime());
            } else {
                ReportScheduler.setDriveTime(this, hh, mm);
                btn.setText(fmtDriveTime());
            }
            Toast.makeText(this, "Schedule updated", Toast.LENGTH_SHORT).show();
            updateStatus();
        }, h, m, true).show();
    }

    // ════════════════════════════════════════════════
    //  DRIVE BROWSER
    // ════════════════════════════════════════════════

    private void browseDriveFolders() {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Fetching folders...");
        progress.setCancelable(false);
        progress.show();

        GoogleDriveUploader.listDriveFoldersAsync(this, (folders, error) -> {
            runOnUiThread(() -> {
                progress.dismiss();
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                    return;
                }
                if (folders == null || folders.isEmpty()) {
                    Toast.makeText(this, "No folders found", Toast.LENGTH_SHORT).show();
                    return;
                }

                new AlertDialog.Builder(this)
                        .setTitle("Select Folder")
                        .setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, folders),
                                (dialog, which) -> {
                                    String selected = folders.get(which);
                                    drivePathInput.setText(selected);
                                    GoogleDriveUploader.setDriveFolderName(this, selected);
                                    Toast.makeText(this, "Selected: " + selected, Toast.LENGTH_SHORT).show();
                                    updateStatus();
                                })
                        .show();
            });
        });
    }

    // ════════════════════════════════════════════════
    //  PERMISSIONS
    // ════════════════════════════════════════════════

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "Already disabled", Toast.LENGTH_SHORT).show();
            } else {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    private void requestStorage() {
        if (PermissionUtils.hasStorageAccess(this)) {
            Toast.makeText(this, "Already granted", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
            return;
        }
        requestPermissions(
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                STORAGE_REQUEST
        );
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                task.getResult(ApiException.class);
                Toast.makeText(this, "Signed in", Toast.LENGTH_SHORT).show();
            } catch (ApiException e) {
                Toast.makeText(this, "Sign-in failed: " + e.getStatusCode(), Toast.LENGTH_SHORT).show();
            }
            updateStatus();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_REQUEST) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? "Storage granted" : "Storage denied", Toast.LENGTH_SHORT).show();
            updateStatus();
        } else if (requestCode == NOTIFICATION_REQUEST) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? "Notifications enabled" : "Notifications disabled", Toast.LENGTH_SHORT).show();
        }
    }

    // ════════════════════════════════════════════════
    //  STATUS
    // ════════════════════════════════════════════════

    private void updateStatus() {
        boolean usage = PermissionUtils.hasUsageAccess(this);
        boolean storage = PermissionUtils.hasStorageAccess(this);

        long nextLocal = ReportScheduler.getNextAlarmMs(this, false);
        long nextDrive = ReportScheduler.getNextAlarmMs(this, true);
        long nextInterval = ReportScheduler.getNextIntervalMs(this);

        String nextLocalStr = nextLocal > 0L ? DateFormat.format("MM-dd HH:mm", new Date(nextLocal)).toString() : "Not set";
        String nextDriveStr = nextDrive > 0L ? DateFormat.format("MM-dd HH:mm", new Date(nextDrive)).toString() : "Not set";

        // Calculate "Next Sync" - the soonest upcoming event
        long soonest = Long.MAX_VALUE;
        String soonestLabel = "";
        if (nextLocal > 0L && nextLocal < soonest) { soonest = nextLocal; soonestLabel = "Local"; }
        if (nextDrive > 0L && nextDrive < soonest) { soonest = nextDrive; soonestLabel = "Drive"; }
        if (nextInterval > System.currentTimeMillis() && nextInterval < soonest) { soonest = nextInterval; soonestLabel = "Interval"; }
        String nextSyncStr = soonest < Long.MAX_VALUE
                ? soonestLabel + " @ " + DateFormat.format("MM-dd HH:mm", new Date(soonest))
                : "Not scheduled";

        // Last successful timestamps
        long lastLocalMs = ReportScheduler.getLastLocalSuccessTime(this);
        long lastDriveMs = GoogleDriveUploader.getLastUploadSuccessTime(this);
        String lastLocalStr = lastLocalMs > 0L
                ? DateFormat.format("yyyy-MM-dd HH:mm", new Date(lastLocalMs)).toString()
                : "Never";
        String lastDriveStr = lastDriveMs > 0L
                ? DateFormat.format("yyyy-MM-dd HH:mm", new Date(lastDriveMs)).toString()
                : "Never";

        String driveStatus = GoogleDriveUploader.getLastUploadStatus(this);

        String lastLocalError = ReportScheduler.getLastLocalError(this);
        String lastDriveError = ReportScheduler.getLastDriveError(this);
        String localErrorStr = !lastLocalError.isEmpty() ? "\n\u274C Local Error: " + lastLocalError : "";
        String driveErrorStr = !lastDriveError.isEmpty() ? "\n\u274C Drive Error: " + lastDriveError : "";

        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);

        boolean ignoringBattery = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            ignoringBattery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        if (account != null) {
            emailView.setText(account.getEmail());
            emailView.setTextColor(ACCENT_BLUE);
        } else {
            emailView.setText("Not connected");
            emailView.setTextColor(ACCENT_RED);
        }

        // Temporary summary while loading
        String usageSummary = "Today: Calculating...";
        
        // Setup initial text
        String ok = " [OK]";
        String no = " [X]";
        String intervalStr = ReportScheduler.isIntervalEnabled(this)
                ? "Every " + ReportScheduler.getIntervalHours(this) + "h"
                : "Off";
        String nextIntervalStr = "";
        if (ReportScheduler.isIntervalEnabled(this) && nextInterval > System.currentTimeMillis()) {
            nextIntervalStr = "\nNext Interval: " + DateFormat.format("MM-dd HH:mm", new Date(nextInterval));
        }

        Runnable updateText = () -> {
            // This is updated again below once apps are loaded
            statusView.setText(
                "Today: Calculating...\n\n" +
                "\n" +
                "Usage Access:" + (usage ? ok : no) + "\n" +
                "Storage Access:" + (storage ? ok : no) + "\n" +
                "Battery Opt. Off:" + (ignoringBattery ? ok : no) + "\n" +
                "\n" +
                "\u23F0 Next Sync: " + nextSyncStr + "\n" +
                "Next Local: " + nextLocalStr + "\n" +
                "Next Drive: " + nextDriveStr + "\n" +
                "Interval Sync: " + intervalStr +
                nextIntervalStr + "\n" +
                "\n" +
                "\u2705 Last Local: " + lastLocalStr +
                localErrorStr + "\n" +
                "\u2705 Last Drive: " + lastDriveStr +
                driveErrorStr
            );
        };
        updateText.run();

        // Fetch usage asynchronously
        executor.execute(() -> {
            long todayTotalMs = 0L;
            List<AppUsageRecord> todayApps = UsageReportGenerator.getTodayTopApps(this);
            StringBuilder topAppsText = new StringBuilder();
            int rank = 1;
            for (AppUsageRecord app : todayApps) {
                todayTotalMs += app.durationMs;
                if (rank <= 5) {
                    topAppsText.append(rank).append(". ")
                            .append(app.appName).append("  ")
                            .append(UsageReportGenerator.formatDuration(app.durationMs))
                            .append("\n");
                    rank++;
                }
            }

            String finalUsageSummary = todayApps.isEmpty()
                    ? "Today: No usage data"
                    : "Today: " + UsageReportGenerator.formatDuration(todayTotalMs) + "\n\n" + topAppsText;

            runOnUiThread(() -> {
                // Sync status indicator
                if (driveStatus.contains("successfully") || driveStatus.contains("Uploaded")) {
                     syncStatusView.setTextColor(ACCENT_GREEN);
                     syncStatusView.setText("Last Sync: " + lastDriveStr + " \u2705");
                } else if (driveStatus.contains("No upload")) {
                     syncStatusView.setTextColor(TEXT_SECONDARY);
                     syncStatusView.setText("Last Sync: Never");
                } else {
                     syncStatusView.setTextColor(ACCENT_AMBER);
                     syncStatusView.setText("Last Sync: " + driveStatus + " \u23f3");
                }

                statusView.setText(
                    finalUsageSummary +
                    "\n" +
                    "Usage Access:" + (usage ? ok : no) + "\n" +
                    "Storage Access:" + (storage ? ok : no) + "\n" +
                    "Battery Opt. Off:" + (ignoringBattery ? ok : no) + "\n" +
                    "\n" +
                    "\u23F0 Next Sync: " + nextSyncStr + "\n" +
                    "Next Local: " + nextLocalStr + "\n" +
                    "Next Drive: " + nextDriveStr + "\n" +
                    "Interval Sync: " + intervalStr +
                    nextIntervalStr + "\n" +
                    "\n" +
                    "\u2705 Last Local: " + lastLocalStr +
                    localErrorStr + "\n" +
                    "\u2705 Last Drive: " + lastDriveStr +
                    driveErrorStr
                );
            });
        });
    }

    // ════════════════════════════════════════════════
    //  ACTIONS
    // ════════════════════════════════════════════════

    private void runAllHistoryExport() {
        resultView.setText("Processing full history...");
        executor.execute(() -> {
            try {
                UsageReportGenerator.ReportResult result =
                        UsageReportGenerator.generateMasterReport(getApplicationContext());
                runOnUiThread(() -> {
                    resultView.setText("Done. " + result.csvFile.getAbsolutePath());
                    updateStatus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError(e));
            }
        });
    }

    private void testDriveUpload() {
        resultView.setText("Uploading to Drive...");
        executor.execute(() -> {
            try {
                UsageReportGenerator.ReportResult result =
                        UsageReportGenerator.generateMasterReport(getApplicationContext());
                GoogleDriveUploader.uploadFileAsync(getApplicationContext(), result.csvFile, (success, msg) ->
                        runOnUiThread(() -> {
                            resultView.setText(msg);
                            updateStatus();
                        }));
            } catch (Exception e) {
                runOnUiThread(() -> showError(e));
            }
        });
    }

    private void showError(Exception e) {
        resultView.setText("Error: " + e.getMessage());
        updateStatus();
    }

    // ════════════════════════════════════════════════
    //  UTILS
    // ════════════════════════════════════════════════

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapWithMargin(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}