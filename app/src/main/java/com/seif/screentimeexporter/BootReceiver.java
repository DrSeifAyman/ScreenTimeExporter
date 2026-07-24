package com.seif.screentimeexporter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Runs on device boot, app update, timezone change.
 * Reschedules all alarms and triggers catch-up via service.
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Received: " + action);

        // Reschedule all alarms immediately
        ReportScheduler.scheduleNext(context);

        // Trigger local catch-up via service
        Intent serviceIntent = new Intent(context, ReportService.class);
        serviceIntent.setAction(ReportService.ACTION_LOCAL);
        context.startService(serviceIntent);

        // Also check if Drive upload was missed
        ReportScheduler.catchUpDriveIfNeeded(context);
    }
}
