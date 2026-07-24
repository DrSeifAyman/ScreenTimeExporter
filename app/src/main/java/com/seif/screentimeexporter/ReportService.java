package com.seif.screentimeexporter;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

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
        }
    }

    private void handleLocalGeneration() {
        try {
            UsageReportGenerator.generateMasterReport(this);
            ReportScheduler.markLocalSuccess(this);
            NotificationHelper.sendNotification(this,
                    "Local Export Complete",
                    "Screen time report saved locally.");
        } catch (Exception e) {
            Log.e(TAG, "Failed local generation", e);
            NotificationHelper.sendNotification(this,
                    "Local Export Failed",
                    "Error: " + e.getMessage());
        } finally {
            ReportScheduler.scheduleNext(this);
        }
    }

    private void handleDriveUpload() {
        try {
            UsageReportGenerator.ReportResult result = UsageReportGenerator.generateMasterReport(this);
            ReportScheduler.markLocalSuccess(this);

            // Synchronous upload with callback
            final Object lock = new Object();
            final boolean[] uploadResult = {false};

            GoogleDriveUploader.uploadFileAsync(this, result.csvFile, (success, msg) -> {
                synchronized (lock) {
                    uploadResult[0] = success;
                    if (success) {
                        ReportScheduler.markDriveSuccess(ReportService.this);
                        NotificationHelper.sendNotification(ReportService.this,
                                "Drive Upload Complete",
                                "Report uploaded to Google Drive.");
                    } else {
                        ReportScheduler.incrementRetryCount(ReportService.this);
                        int attempt = ReportScheduler.getDriveRetryCount(ReportService.this);
                        if (attempt % 3 == 1 || attempt == 1) {
                            NotificationHelper.sendNotification(ReportService.this,
                                    "Drive Upload Failed",
                                    "Attempt #" + attempt + " failed. Retrying in 30 min...");
                        }
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
            ReportScheduler.incrementRetryCount(this);
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
                        NotificationHelper.sendNotification(ReportService.this,
                                "Drive Upload Recovered",
                                "Report uploaded successfully after retry.");
                    } else {
                        ReportScheduler.incrementRetryCount(ReportService.this);
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
            ReportScheduler.incrementRetryCount(this);
            ReportScheduler.scheduleRetry(this);
        }
    }

    private void handleIntervalSync() {
        try {
            UsageReportGenerator.ReportResult result = UsageReportGenerator.generateMasterReport(this);
            ReportScheduler.markLocalSuccess(this);
            Log.i(TAG, "Interval sync: local report updated.");

            // Also try Drive if signed in and not done today
            if (!ReportScheduler.isDriveDoneToday(this)) {
                com.google.android.gms.auth.api.signin.GoogleSignInAccount account =
                        com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(this);
                if (account != null) {
                    final Object lock = new Object();
                    GoogleDriveUploader.uploadFileAsync(this, result.csvFile, (success, msg) -> {
                        synchronized (lock) {
                            if (success) {
                                ReportScheduler.markDriveSuccess(ReportService.this);
                                Log.i(TAG, "Interval sync: Drive upload success.");
                            }
                            lock.notify();
                        }
                    });
                    synchronized (lock) {
                        lock.wait(4 * 60 * 1000L);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Interval sync failed", e);
        } finally {
            ReportScheduler.scheduleNextInterval(this);
        }
    }
}
