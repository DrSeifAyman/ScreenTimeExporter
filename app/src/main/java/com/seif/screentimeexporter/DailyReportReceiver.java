package com.seif.screentimeexporter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.core.content.ContextCompat;

/**
 * Receives alarm broadcasts and delegates heavy work to ReportService.
 * This prevents ANR crashes since BroadcastReceivers have a 10-second limit.
 */
public final class DailyReportReceiver extends BroadcastReceiver {
    private static final String TAG = "DailyReportReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Received alarm: " + action);

        String serviceAction;

        if (ReportScheduler.ACTION_GENERATE_LOCAL.equals(action)) {
            serviceAction = ReportService.ACTION_LOCAL;
        } else if (ReportScheduler.ACTION_UPLOAD_DRIVE.equals(action)) {
            serviceAction = ReportService.ACTION_DRIVE;
        } else if (ReportScheduler.ACTION_RETRY_DRIVE.equals(action)) {
            serviceAction = ReportService.ACTION_RETRY;
        } else if (ReportScheduler.ACTION_INTERVAL.equals(action)) {
            serviceAction = ReportService.ACTION_INTERVAL;
        } else {
            // Default: trigger local generation
            serviceAction = ReportService.ACTION_LOCAL;
        }

        Intent serviceIntent = new Intent(context, ReportService.class);
        serviceIntent.setAction(serviceAction);
        ContextCompat.startForegroundService(context, serviceIntent);
    }
}