package com.seif.screentimeexporter;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;
import android.content.pm.ServiceInfo;
import android.os.Build;

/**
 * Background IntentService that handles all heavy work off the main thread.
 * This prevents ANR crashes that occur when BroadcastReceivers try to do
 * long-running operations on the main thread.
 */
public final class ReportService extends IntentService {
    private static final String TAG = "ReportService";
    public static final String ACTION_LOCAL = "com.seif.screentimeexporter.SERVICE_LOCAL";
    public static final String ACTION_DRIVE = "com.seif.screentimeexporter.SERVICE_DRIVE";
    public static final String ACTION_RETRY = "com.seif.screentimeexporter.SERVICE_RETRY";
    public static final String ACTION_INTERVAL = "com.seif.screentimeexporter.SERVICE_INTERVAL";

    public ReportService() {
        super("ReportService");
        setIntentRedelivery(true); // Redeliver intent if process dies
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;

        // Acquire a wakelock to prevent CPU from sleeping during our work
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "ScreenTimeExporter:ReportService");
        wl.acquire(5 * 60 * 1000L); // 5 minute max

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, NotificationHelper.getForegroundNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, NotificationHelper.getForegroundNotification(this));
            }

            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case ACTION_LOCAL:
                    handleLocalGeneration();
                    break;
                case ACTION_DRIVE:
                    handleDriveUpload();
                    break;
                case ACTION_RETRY:
                    handleRetryDrive();
                    break;
                case ACTION_INTERVAL:
                    handleIntervalSync();
                    break;
            }
        } finally {
            if (wl.isHeld()) wl.release();
            stopForeground(true);
        }
    }

    private void handleLocalGeneration() {
        try {
            UsageReportGenerator.generateMasterReport(this);
            ReportScheduler.markLocalSuccess(this);
            ReportScheduler.clearLastLocalError(this);
            NotificationHelper.sendNotification(this,
                    "Local Export Complete",
                    "Screen time report saved locally.");
        } catch (Exception e) {
            Log.e(TAG, "Failed local generation", e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            ReportScheduler.setLastLocalError(this, errorMsg);
            NotificationHelper.sendNotification(this,
                    "\u274C Local Export Failed",
                    "Error: " + errorMsg);
        } finally {
            ReportScheduler.scheduleNext(this);
        }
    }

    private void handleDriveUpload() {
        try {
            UsageReportGenerator.ReportResult result = UsageReportGenerator.generateMasterReport(this);
            ReportScheduler.markLocalSuccess(this);
            ReportScheduler.clearLastLocalError(this);

            // Synchronous upload with callback
            final Object lock = new Object();
            final boolean[] uploadResult = {false};

            GoogleDriveUploader.uploadFileAsync(this, result.csvFile, (success, msg) -> {
                synchronized (lock) {
                    uploadResult[0] = success;
                    if (success) {
                        ReportScheduler.markDriveSuccess(ReportService.this);
                        ReportScheduler.clearLastDriveError(ReportService.this);
                        NotificationHelper.sendNotification(ReportService.this,
                                "Drive Upload Complete",
                                "Report uploaded to Google Drive.");
                    } else {
                        String errorMsg = msg != null ? msg : "Unknown upload error";
                        ReportScheduler.setLastDriveError(ReportService.this, errorMsg);
                        ReportScheduler.incrementRetryCount(ReportService.this);
                        int attempt = ReportScheduler.getDriveRetryCount(ReportService.this);
                        NotificationHelper.sendNotification(ReportService.this,
                                "\u274C Drive Upload Failed",
                                "Attempt #" + attempt + ": " + errorMsg + ". Retrying in 30 min...");
                        ReportScheduler.scheduleRetry(ReportService.this);
                    }
                    lock.notify();
                }
            });

            // Wait for upload to complete (max 4 minutes)
            synchronized (lock) {
                lock.wait(4 * 60 * 1000L);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed drive upload", e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            ReportScheduler.setLastDriveError(this, errorMsg);
            ReportScheduler.incrementRetryCount(this);
            int attempt = ReportScheduler.getDriveRetryCount(this);
            NotificationHelper.sendNotification(this,
                    "\u274C Drive Upload Failed",
                    "Attempt #" + attempt + ": " + errorMsg + ". Retrying in 30 min...");
            ReportScheduler.scheduleRetry(this);
        } finally {
            ReportScheduler.scheduleNext(this);
        }
    }

    private void handleRetryDrive() {
        if (ReportScheduler.isDriveDoneToday(this)) {
            Log.i(TAG, "Drive already uploaded today, skipping retry.");
            return;
        }

        try {
            UsageReportGenerator.ReportResult result = UsageReportGenerator.generateMasterReport(this);

            final Object lock = new Object();
            GoogleDriveUploader.uploadFileAsync(this, result.csvFile, (success, msg) -> {
                synchronized (lock) {
                    if (success) {
                        ReportScheduler.markDriveSuccess(ReportService.this);
                        ReportScheduler.clearLastDriveError(ReportService.this);
                        NotificationHelper.sendNotification(ReportService.this,
                                "\u2705 Drive Upload Recovered",
                                "Report uploaded successfully after retry.");
                    } else {
                        String errorMsg = msg != null ? msg : "Unknown upload error";
                        ReportScheduler.setLastDriveError(ReportService.this, errorMsg);
                        ReportScheduler.incrementRetryCount(ReportService.this);
                        int attempt = ReportScheduler.getDriveRetryCount(ReportService.this);
                        NotificationHelper.sendNotification(ReportService.this,
                                "\u274C Drive Retry Failed",
                                "Attempt #" + attempt + ": " + errorMsg + ". Retrying...");
                        ReportScheduler.scheduleRetry(ReportService.this);
                    }
                    lock.notify();
                }
            });

            synchronized (lock) {
                lock.wait(4 * 60 * 1000L);
            }
        } catch (Exception e) {
            Log.e(TAG, "Retry failed", e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            ReportScheduler.setLastDriveError(this, errorMsg);
            ReportScheduler.incrementRetryCount(this);
            int attempt = ReportScheduler.getDriveRetryCount(this);
            NotificationHelper.sendNotification(this,
                    "\u274C Drive Retry Failed",
                    "Attempt #" + attempt + ": " + errorMsg);
            ReportScheduler.scheduleRetry(this);
        }
    }

    private void handleIntervalSync() {
        try {
            UsageReportGenerator.ReportResult result = UsageReportGenerator.generateMasterReport(this);
            ReportScheduler.markLocalSuccess(this);
            ReportScheduler.clearLastLocalError(this);
            Log.i(TAG, "Interval sync: local report updated.");

            // Also try Drive if signed in
            com.google.android.gms.auth.api.signin.GoogleSignInAccount account =
                    com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(this);
            if (account != null) {
                final Object lock = new Object();
                GoogleDriveUploader.uploadFileAsync(this, result.csvFile, (success, msg) -> {
                    synchronized (lock) {
                        if (success) {
                            ReportScheduler.markDriveSuccess(ReportService.this);
                            ReportScheduler.clearLastDriveError(ReportService.this);
                            Log.i(TAG, "Interval sync: Drive upload success.");
                        } else {
                            String errorMsg = msg != null ? msg : "Unknown upload error";
                            ReportScheduler.setLastDriveError(ReportService.this, errorMsg);
                            NotificationHelper.sendNotification(ReportService.this,
                                    "\u274C Interval Drive Upload Failed",
                                    errorMsg);
                        }
                        lock.notify();
                    }
                });
                synchronized (lock) {
                    lock.wait(4 * 60 * 1000L);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Interval sync failed", e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            ReportScheduler.setLastLocalError(this, errorMsg);
            NotificationHelper.sendNotification(this,
                    "\u274C Interval Sync Failed",
                    errorMsg);
        } finally {
            ReportScheduler.scheduleNextInterval(this);
        }
    }
}
