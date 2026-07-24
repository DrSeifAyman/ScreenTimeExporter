package com.seif.screentimeexporter;

final class AppUsageRecord {
    final String packageName;
    String appName;
    String category = "Unknown";
    long durationMs;
    int launches;

    AppUsageRecord(String packageName) {
        this.packageName = packageName;
        this.appName = packageName;
    }
}
