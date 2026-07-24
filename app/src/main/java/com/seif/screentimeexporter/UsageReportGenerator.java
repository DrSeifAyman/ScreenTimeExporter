package com.seif.screentimeexporter;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Environment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

final class UsageReportGenerator {
    static final String OUTPUT_FOLDER = "Seif Health/ScreenTime";
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long MIN_DURATION_MS = 1000L;

    private UsageReportGenerator() {}

    static String todayKey() {
        return dateKey(System.currentTimeMillis());
    }

    static String yesterdayKey() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -1);
        return dateKey(c.getTimeInMillis());
    }

    static String previousDayKey(String key) throws Exception {
        Calendar c = parseDateKey(key);
        c.add(Calendar.DAY_OF_YEAR, -1);
        return dateKey(c.getTimeInMillis());
    }

    static String nextDayKey(String key) throws Exception {
        Calendar c = parseDateKey(key);
        c.add(Calendar.DAY_OF_YEAR, 1);
        return dateKey(c.getTimeInMillis());
    }

    static int compareDateKeys(String a, String b) {
        return a.compareTo(b);
    }

    private static final String PREFS = "screen_time_exporter";
    private static final String CUSTOM_LOCAL_PATH_KEY = "custom_local_path";

    static void setCustomLocalPath(Context context, String path) {
        String safePath = (path == null || path.trim().isEmpty()) ? OUTPUT_FOLDER : path.trim();
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(CUSTOM_LOCAL_PATH_KEY, safePath).apply();
    }

    static String getCustomLocalPath(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CUSTOM_LOCAL_PATH_KEY, OUTPUT_FOLDER);
    }

    static File outputDirectory(Context context) {
        String path = getCustomLocalPath(context);
        File primary = new File(Environment.getExternalStorageDirectory(), path);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (PermissionUtils.hasStorageAccess(context)) {
                return primary;
            }
            File fallback = context.getExternalFilesDir(path);
            if (fallback != null) return fallback;
        }
        return primary;
    }

    static List<AppUsageRecord> getTodayTopApps(Context context) {
        try {
            String key = todayKey();
            Calendar startCalendar = parseDateKey(key);
            return queryDayRecords(context, startCalendar.getTimeInMillis(), System.currentTimeMillis());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    static List<AppUsageRecord> getYesterdayTopApps(Context context) {
        try {
            String key = yesterdayKey();
            Calendar startCalendar = parseDateKey(key);
            Calendar endCalendar = (Calendar) startCalendar.clone();
            endCalendar.add(Calendar.DAY_OF_YEAR, 1);
            return queryDayRecords(context, startCalendar.getTimeInMillis(), endCalendar.getTimeInMillis());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    static ReportResult generateFullDay(Context context, String dayKey) throws Exception {
        return generateMasterReport(context);
    }

    static ReportResult generateTodayPreview(Context context) throws Exception {
        return generateMasterReport(context);
    }

    static int generateAllHistory(Context context) throws Exception {
        ReportResult result = generateMasterReport(context);
        return result.appCount;
    }

    static ReportResult generateMasterReport(Context context) throws Exception {
        Calendar todayStart = Calendar.getInstance();
        todayStart.set(Calendar.HOUR_OF_DAY, 0);
        todayStart.set(Calendar.MINUTE, 0);
        todayStart.set(Calendar.SECOND, 0);
        todayStart.set(Calendar.MILLISECOND, 0);

        List<MasterRecord> masterList = new ArrayList<>();

        for (int i = 14; i >= 0; i--) {
            Calendar dayCal = (Calendar) todayStart.clone();
            dayCal.add(Calendar.DAY_OF_YEAR, -i);
            String dayKey = dateKey(dayCal.getTimeInMillis());

            long start = dayCal.getTimeInMillis();
            long end = (i == 0) ? System.currentTimeMillis() : start + DAY_MS;
            if (end <= start) continue;

            try {
                List<AppUsageRecord> dayRecords = queryDayRecords(context, start, end);
                for (AppUsageRecord r : dayRecords) {
                    masterList.add(new MasterRecord(dayKey, r));
                }
            } catch (Exception ignored) {}
        }

        File dir = outputDirectory(context);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create folder: " + dir.getAbsolutePath());
        }

        File masterCsv = new File(dir, "ScreenTime-Master.csv");

        writeMasterCsv(masterCsv, masterList);

        cleanupExtraFilesExceptMasterCsv(dir);

        long totalMs = 0L;
        for (MasterRecord r : masterList) totalMs += r.record.durationMs;

        return new ReportResult(todayKey(), masterList.size(), totalMs, masterCsv, masterCsv, masterCsv);
    }

    private static void cleanupExtraFilesExceptMasterCsv(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.getName().equalsIgnoreCase("ScreenTime-Master.csv")) {
                f.delete();
            }
        }
    }

    static List<AppUsageRecord> queryDayRecords(Context context, long start, long end) throws Exception {
        if (!PermissionUtils.hasUsageAccess(context)) {
            throw new IllegalStateException("Usage Access is not granted");
        }
        if (!PermissionUtils.hasStorageAccess(context)) {
            throw new IllegalStateException("Storage permission is not granted");
        }
        if (end <= start) {
            throw new IllegalArgumentException("Invalid report range");
        }

        UsageStatsManager manager = (UsageStatsManager)
                context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("UsageStatsManager is unavailable");
        }

                long queryStart = Math.max(0L, start - DAY_MS);
        long queryEnd = end + DAY_MS;
        android.app.usage.UsageEvents events = manager.queryEvents(queryStart, queryEnd);
        if (events == null) {
            return java.util.Collections.emptyList();
        }

        java.util.Map<String, AppUsageRecord> records = new java.util.HashMap<>();

        String currentApp = null;
        long startTime = 0L;

        android.app.usage.UsageEvents.Event event = new android.app.usage.UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            long ts = event.getTimeStamp();
            String pkg = event.getPackageName();
            if (pkg == null || pkg.isEmpty()) continue;

            if (type == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (currentApp != null) {
                    if (!currentApp.equals(pkg)) {
                        addDuration(records, currentApp, startTime, ts, start, end);
                        currentApp = pkg;
                        startTime = ts;
                        if (ts >= start && ts < end) getRecord(records, pkg).launches++;
                    }
                } else {
                    currentApp = pkg;
                    startTime = ts;
                    if (ts >= start && ts < end) getRecord(records, pkg).launches++;
                }
            } else if (type == android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND) {
                if (currentApp != null && currentApp.equals(pkg)) {
                    addDuration(records, currentApp, startTime, ts, start, end);
                    currentApp = null;
                }
            } else if (type == 16 || type == 26 || type == 18) { // SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN, or KEYGUARD_SHOWN
                if (currentApp != null) {
                    addDuration(records, currentApp, startTime, ts, start, end);
                    currentApp = null;
                }
            }
            // type == 20 (KEYGUARD_HIDDEN) is functionally identical to the next ACTIVITY_RESUMED
            // which will trigger the start condition anyway, so we don't need an explicit case.
        }

        if (currentApp != null) {
            addDuration(records, currentApp, startTime, Math.min(System.currentTimeMillis(), queryEnd), start, end);
        }

        java.util.Set<String> excluded = excludedPackages(context);
        android.content.pm.PackageManager pm = context.getPackageManager();
        java.util.List<AppUsageRecord> list = new java.util.ArrayList<>();
        for (AppUsageRecord record : records.values()) {
            if (record.durationMs < MIN_DURATION_MS) continue;
            if (excluded.contains(record.packageName)) continue;
            populateAppInfo(pm, record);
            list.add(record);
        }

        java.util.Collections.sort(list, new java.util.Comparator<AppUsageRecord>() {
            @Override
            public int compare(AppUsageRecord a, AppUsageRecord b) {
                int byDuration = Long.compare(b.durationMs, a.durationMs);
                if (byDuration != 0) return byDuration;
                return a.appName.compareToIgnoreCase(b.appName);
            }
        });

        return list;
    }

    private static final class MasterRecord {
        final String dateKey;
        final AppUsageRecord record;

        MasterRecord(String dateKey, AppUsageRecord record) {
            this.dateKey = dateKey;
            this.record = record;
        }
    }

    private static void writeMasterCsv(File file, List<MasterRecord> masterList) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("date,app_name,category,package_name,duration_seconds,duration_formatted,launch_count\n");

        for (MasterRecord item : masterList) {
            sb.append(csv(item.dateKey)).append(',')
                    .append(csv(item.record.appName)).append(',')
                    .append(csv(item.record.category)).append(',')
                    .append(csv(item.record.packageName)).append(',')
                    .append(item.record.durationMs / 1000L).append(',')
                    .append(csv(formatDurationHHMMSS(item.record.durationMs))).append(',')
                    .append(item.record.launches).append('\n');
        }

        atomicWrite(file, sb.toString());
    }

    private static void writeMasterJson(File file, List<MasterRecord> masterList) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"updated_at\": \"").append(json(dateKey(System.currentTimeMillis()))).append("\",\n");
        sb.append("  \"records_count\": ").append(masterList.size()).append(",\n");
        sb.append("  \"records\": [\n");
        for (int i = 0; i < masterList.size(); i++) {
            MasterRecord item = masterList.get(i);
            sb.append("    {\n");
            sb.append("      \"date\": \"").append(json(item.dateKey)).append("\",\n");
            sb.append("      \"app_name\": \"").append(json(item.record.appName)).append("\",\n");
            sb.append("      \"category\": \"").append(json(item.record.category)).append("\",\n");
            sb.append("      \"package_name\": \"").append(json(item.record.packageName)).append("\",\n");
            sb.append("      \"duration_seconds\": ").append(item.record.durationMs / 1000L).append(",\n");
            sb.append("      \"duration_formatted\": \"").append(json(formatDurationHHMMSS(item.record.durationMs))).append("\",\n");
            sb.append("      \"launch_count\": ").append(item.record.launches).append('\n');
            sb.append("    }");
            if (i < masterList.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n");
        sb.append("}\n");
        atomicWrite(file, sb.toString());
    }

    private static void writeMasterTxt(File file, List<MasterRecord> masterList) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("Screen Time Master Report — Updated ").append(dateKey(System.currentTimeMillis())).append("\n\n");
        for (MasterRecord item : masterList) {
            sb.append(item.dateKey)
                    .append(" | ")
                    .append(item.record.appName)
                    .append(" [").append(item.record.category).append("] | ")
                    .append(formatDurationHHMMSS(item.record.durationMs))
                    .append(" | launches: ")
                    .append(item.record.launches)
                    .append('\n');
        }
        atomicWrite(file, sb.toString());
    }

    private static void atomicWrite(File target, String content) throws Exception {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temp, false), StandardCharsets.UTF_8))) {
            writer.write(content);
            writer.flush();
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Could not update file: " + target.getName());
        }
        if (!temp.renameTo(target)) {
            throw new IllegalStateException("Could not save file: " + target.getName());
        }
    }

    static String formatDurationHHMMSS(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    static String formatDuration(long durationMs) {
        long totalMinutes = Math.max(0L, durationMs / 60000L);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours > 0L) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    private static String json(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String dateKey(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(millis));
    }

    private static Calendar parseDateKey(String key) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getDefault());
        Date date = format.parse(key);
        if (date == null) throw new IllegalArgumentException("Invalid date: " + key);
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    static final class ReportResult {
        final String dayKey;
        final int appCount;
        final long totalMs;
        final File csvFile;
        final File jsonFile;
        final File textFile;

        ReportResult(
                String dayKey,
                int appCount,
                long totalMs,
                File csvFile,
                File jsonFile,
                File textFile
        ) {
            this.dayKey = dayKey;
            this.appCount = appCount;
            this.totalMs = totalMs;
            this.csvFile = csvFile;
            this.jsonFile = jsonFile;
            this.textFile = textFile;
        }
    }
    private static void addDuration(
            java.util.Map<String, AppUsageRecord> records,
            String pkg,
            long activeStart,
            long activeEnd,
            long rangeStart,
            long rangeEnd
    ) {
        long clampedStart = Math.max(activeStart, rangeStart);
        long clampedEnd = Math.min(activeEnd, rangeEnd);
        if (clampedEnd > clampedStart) {
            getRecord(records, pkg).durationMs += clampedEnd - clampedStart;
        }
    }

    private static AppUsageRecord getRecord(java.util.Map<String, AppUsageRecord> records, String pkg) {
        AppUsageRecord record = records.get(pkg);
        if (record == null) {
            record = new AppUsageRecord(pkg);
            records.put(pkg, record);
        }
        return record;
    }

    private static boolean isForegroundEvent(int type) {
        return type == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND;
    }

    private static boolean isBackgroundEvent(int type) {
        return type == android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND;
    }

    private static void closeAllActive(
            java.util.Map<String, AppUsageRecord> records,
            java.util.Map<String, Long> activePackages,
            long closeAt,
            long rangeStart,
            long rangeEnd
    ) {
        for (java.util.Map.Entry<String, Long> entry : activePackages.entrySet()) {
            long activeStart = entry.getValue();
            if (activeStart >= 0L) {
                addDuration(records, entry.getKey(), activeStart, closeAt, rangeStart, rangeEnd);
            }
        }
        activePackages.clear();
    }

    private static java.util.Set<String> excludedPackages(android.content.Context context) {
        java.util.Set<String> excluded = new java.util.HashSet<>();
        excluded.add(context.getPackageName());
        excluded.add("com.android.systemui");

        android.content.Intent homeIntent = new android.content.Intent(android.content.Intent.ACTION_MAIN);
        homeIntent.addCategory(android.content.Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo home = context.getPackageManager().resolveActivity(homeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && home.activityInfo != null) {
            excluded.add(home.activityInfo.packageName);
        }
        return excluded;
    }

    private static void populateAppInfo(android.content.pm.PackageManager pm, AppUsageRecord record) {
        try {
            android.content.pm.ApplicationInfo info = pm.getApplicationInfo(record.packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            record.appName = label == null ? record.packageName : label.toString();

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                switch (info.category) {
                    case android.content.pm.ApplicationInfo.CATEGORY_GAME: record.category = "Game"; break;
                    case android.content.pm.ApplicationInfo.CATEGORY_AUDIO: record.category = "Audio"; break;
                    case android.content.pm.ApplicationInfo.CATEGORY_VIDEO: record.category = "Video"; break;
                    case android.content.pm.ApplicationInfo.CATEGORY_IMAGE: record.category = "Image"; break;
                    case android.content.pm.ApplicationInfo.CATEGORY_SOCIAL: record.category = "Social"; break;
                    case android.content.pm.ApplicationInfo.CATEGORY_NEWS: record.category = "News"; break;
                    case android.content.pm.ApplicationInfo.CATEGORY_MAPS: record.category = "Maps"; break;
                    case android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY: record.category = "Productivity"; break;
                    default: record.category = "Other"; break;
                }
            } else {
                record.category = "Other";
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            record.appName = record.packageName;
            record.category = "Unknown";
        }
    }
}

