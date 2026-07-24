package com.seif.screentimeexporter;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ReportScheduler {
    private static final String TAG = "ReportScheduler";
    static final String ACTION_GENERATE_LOCAL = "com.seif.screentimeexporter.GENERATE_LOCAL";
    static final String ACTION_UPLOAD_DRIVE = "com.seif.screentimeexporter.UPLOAD_DRIVE";
    static final String ACTION_RETRY_DRIVE = "com.seif.screentimeexporter.RETRY_DRIVE";
    static final String ACTION_INTERVAL = "com.seif.screentimeexporter.INTERVAL_SYNC";
    private static final String PREFS = "screen_time_exporter";

    // Schedule time keys
    private static final String LOCAL_TIME_HOUR = "local_time_hour";
    private static final String LOCAL_TIME_MINUTE = "local_time_minute";
    private static final String DRIVE_TIME_HOUR = "drive_time_hour";
    private static final String DRIVE_TIME_MINUTE = "drive_time_minute";

    // Interval keys
    private static final String INTERVAL_ENABLED = "interval_enabled";
    private static final String INTERVAL_HOURS = "interval_hours";

    // Success tracking keys
    private static final String LAST_LOCAL_SUCCESS_DATE = "last_local_success_date";
    private static final String LAST_DRIVE_SUCCESS_DATE = "last_drive_success_date";
    private static final String DRIVE_RETRY_COUNT = "drive_retry_count";

    private static final int REQUEST_CODE_LOCAL = 1001;
    private static final int REQUEST_CODE_DRIVE = 1002;
    private static final int REQUEST_CODE_RETRY = 1003;
    private static final int REQUEST_CODE_INTERVAL = 1004;
    private static final long RETRY_INTERVAL_MS = 30L * 60L * 1000L; // 30 minutes

    private ReportScheduler() {}

    // ── Getters/Setters for schedule times ──

    static int getLocalHour(Context context) {
        return prefs(context).getInt(LOCAL_TIME_HOUR, 0);
    }

    static int getLocalMinute(Context context) {
        return prefs(context).getInt(LOCAL_TIME_MINUTE, 5);
    }

    static int getDriveHour(Context context) {
        return prefs(context).getInt(DRIVE_TIME_HOUR, 0);
    }

    static int getDriveMinute(Context context) {
        return prefs(context).getInt(DRIVE_TIME_MINUTE, 30);
    }

    static void setLocalTime(Context context, int hour, int minute) {
        prefs(context).edit().putInt(LOCAL_TIME_HOUR, hour).putInt(LOCAL_TIME_MINUTE, minute).apply();
        scheduleNext(context);
    }

    static void setDriveTime(Context context, int hour, int minute) {
        prefs(context).edit().putInt(DRIVE_TIME_HOUR, hour).putInt(DRIVE_TIME_MINUTE, minute).apply();
        scheduleNext(context);
    }

    // ── Interval settings ──

    static boolean isIntervalEnabled(Context context) {
        return prefs(context).getBoolean(INTERVAL_ENABLED, false);
    }

    static int getIntervalHours(Context context) {
        return prefs(context).getInt(INTERVAL_HOURS, 1);
    }

    static void setIntervalEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(INTERVAL_ENABLED, enabled).apply();
        if (enabled) {
            scheduleNextInterval(context);
        } else {
            cancelInterval(context);
        }
    }

    static void setIntervalHours(Context context, int hours) {
        prefs(context).edit().putInt(INTERVAL_HOURS, Math.max(1, hours)).apply();
        if (isIntervalEnabled(context)) {
            scheduleNextInterval(context);
        }
    }

    // ── Success tracking ──

    private static String todayDateString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static void markLocalSuccess(Context context) {
        prefs(context).edit().putString(LAST_LOCAL_SUCCESS_DATE, todayDateString()).apply();
    }

    static void markDriveSuccess(Context context) {
        prefs(context).edit()
                .putString(LAST_DRIVE_SUCCESS_DATE, todayDateString())
                .putInt(DRIVE_RETRY_COUNT, 0)
                .apply();
    }

    static boolean isLocalDoneToday(Context context) {
        String last = prefs(context).getString(LAST_LOCAL_SUCCESS_DATE, "");
        return todayDateString().equals(last);
    }

    static boolean isDriveDoneToday(Context context) {
        String last = prefs(context).getString(LAST_DRIVE_SUCCESS_DATE, "");
        return todayDateString().equals(last);
    }

    static int getDriveRetryCount(Context context) {
        return prefs(context).getInt(DRIVE_RETRY_COUNT, 0);
    }

    static void incrementRetryCount(Context context) {
        int current = getDriveRetryCount(context);
        prefs(context).edit().putInt(DRIVE_RETRY_COUNT, current + 1).apply();
    }

    // ── Scheduling ──

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void scheduleNext(Context context) {
        if (!PermissionUtils.hasUsageAccess(context) || !PermissionUtils.hasStorageAccess(context)) {
            return;
        }

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                // Fallback to inexact alarm
                long nextLocalTime = calculateNextTime(getLocalHour(context), getLocalMinute(context));
                long nextDriveTime = calculateNextTime(getDriveHour(context), getDriveMinute(context));
                PendingIntent piLocal = createPendingIntent(context, ACTION_GENERATE_LOCAL, REQUEST_CODE_LOCAL);
                PendingIntent piDrive = createPendingIntent(context, ACTION_UPLOAD_DRIVE, REQUEST_CODE_DRIVE);
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextLocalTime, piLocal);
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextDriveTime, piDrive);
                Log.w(TAG, "Using inexact alarms (exact alarm permission missing)");
                return;
            }
        }

        // Schedule Local Alarm
        long nextLocalTime = calculateNextTime(getLocalHour(context), getLocalMinute(context));
        PendingIntent piLocal = createPendingIntent(context, ACTION_GENERATE_LOCAL, REQUEST_CODE_LOCAL);

        // Schedule Drive Alarm
        long nextDriveTime = calculateNextTime(getDriveHour(context), getDriveMinute(context));
        PendingIntent piDrive = createPendingIntent(context, ACTION_UPLOAD_DRIVE, REQUEST_CODE_DRIVE);

        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextLocalTime, piLocal);
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextDriveTime, piDrive);
            Log.i(TAG, "Scheduled local=" + new Date(nextLocalTime) + " drive=" + new Date(nextDriveTime));
        } catch (SecurityException e) {
            Log.e(TAG, "Exact alarm permission revoked, using inexact", e);
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextLocalTime, piLocal);
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextDriveTime, piDrive);
        }

        // Also schedule interval if enabled
        if (isIntervalEnabled(context)) {
            scheduleNextInterval(context);
        }
    }

    static void scheduleNextInterval(Context context) {
        if (!isIntervalEnabled(context)) return;

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        int hours = getIntervalHours(context);
        long intervalMs = hours * 60L * 60L * 1000L;
        long nextTime = System.currentTimeMillis() + intervalMs;

        PendingIntent piInterval = createPendingIntent(context, ACTION_INTERVAL, REQUEST_CODE_INTERVAL);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime, piInterval);
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime, piInterval);
            }
            Log.i(TAG, "Interval sync scheduled in " + hours + "h at " + new Date(nextTime));
        } catch (SecurityException e) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime, piInterval);
        }
    }

    private static void cancelInterval(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        PendingIntent piInterval = createPendingIntent(context, ACTION_INTERVAL, REQUEST_CODE_INTERVAL);
        alarmManager.cancel(piInterval);
        Log.i(TAG, "Interval sync cancelled.");
    }

    static void scheduleRetry(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        long retryTime = System.currentTimeMillis() + RETRY_INTERVAL_MS;
        PendingIntent piRetry = createPendingIntent(context, ACTION_RETRY_DRIVE, REQUEST_CODE_RETRY);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, retryTime, piRetry);
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, retryTime, piRetry);
            }
            int attempt = getDriveRetryCount(context);
            Log.i(TAG, "Retry #" + (attempt + 1) + " scheduled in 30 minutes");
        } catch (SecurityException e) {
            Log.e(TAG, "Could not schedule retry", e);
        }
    }

    static long getNextAlarmMs(Context context, boolean isDrive) {
        if (!PermissionUtils.hasUsageAccess(context) || !PermissionUtils.hasStorageAccess(context)) {
            return 0L;
        }

        if (isDrive) {
            return calculateNextTime(getDriveHour(context), getDriveMinute(context));
        } else {
            return calculateNextTime(getLocalHour(context), getLocalMinute(context));
        }
    }

    private static long calculateNextTime(int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        long now = calendar.getTimeInMillis();

        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        if (calendar.getTimeInMillis() <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return calendar.getTimeInMillis();
    }

    private static PendingIntent createPendingIntent(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, DailyReportReceiver.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    // ── Catch-up (called on boot and app open) ──

    static UsageReportGenerator.ReportResult generateYesterdayNow(Context context) throws Exception {
        return UsageReportGenerator.generateMasterReport(context);
    }

    static void runCatchUpAsync(Context context, Runnable onComplete) {
        if (!PermissionUtils.hasUsageAccess(context) || !PermissionUtils.hasStorageAccess(context)) {
            if (onComplete != null) onComplete.run();
            return;
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                UsageReportGenerator.generateMasterReport(context);
                markLocalSuccess(context);
            } catch (Exception e) {
                Log.e(TAG, "Failed to run catch-up", e);
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
    }

    /**
     * Called on boot to check if Drive upload was missed.
     */
    static void catchUpDriveIfNeeded(Context context) {
        if (isDriveDoneToday(context)) return;

        com.google.android.gms.auth.api.signin.GoogleSignInAccount account =
                com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context);
        if (account == null) return;

        prefs(context).edit().putInt(DRIVE_RETRY_COUNT, 0).apply();

        // Start the service for drive upload
        Intent serviceIntent = new Intent(context, ReportService.class);
        serviceIntent.setAction(ReportService.ACTION_DRIVE);
        context.startService(serviceIntent);
    }
}