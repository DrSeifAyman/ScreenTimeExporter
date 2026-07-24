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
    private static final long MIN_DURATION_MS = 1000L; // Ignore sessions less than 1 second

    private UsageReportGenerator() {}

    static final class SessionRecord {
        String packageName;
        String appName;
        String category;
        long sessionStartMs;
        long sessionEndMs;
        long durationMs;

        SessionRecord(String pkg, long start, long end, long duration) {
            this.packageName = pkg;
            this.sessionStartMs = start;
            this.sessionEndMs = end;
            this.durationMs = duration;
        }
    }

    static String todayKey() {
        return dateKey(System.currentTimeMillis());
    }

    static String yesterdayKey() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -1);
        return dateKey(c.getTimeInMillis());
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

    // This method aggregates SessionRecords into AppUsageRecords for the UI list
    static List<AppUsageRecord> getTodayTopApps(Context context) {
        try {
            String key = todayKey();
            Calendar startCalendar = parseDateKey(key);
            List<SessionRecord> sessions = queryDaySessions(context, startCalendar.getTimeInMillis(), System.currentTimeMillis());
            return aggregateSessions(sessions);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // This method aggregates SessionRecords into AppUsageRecords for the UI list
    static List<AppUsageRecord> getYesterdayTopApps(Context context) {
        try {
            String key = yesterdayKey();
            Calendar startCalendar = parseDateKey(key);
            Calendar endCalendar = (Calendar) startCalendar.clone();
            endCalendar.add(Calendar.DAY_OF_YEAR, 1);
            List<SessionRecord> sessions = queryDaySessions(context, startCalendar.getTimeInMillis(), endCalendar.getTimeInMillis());
            return aggregateSessions(sessions);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    static List<AppUsageRecord> aggregateSessions(List<SessionRecord> sessions) {
        Map<String, AppUsageRecord> map = new HashMap<>();
        for (SessionRecord s : sessions) {
            AppUsageRecord r = map.get(s.packageName);
            if (r == null) {
                r = new AppUsageRecord(s.packageName);
                r.appName = s.appName;
                r.category = s.category;
                map.put(s.packageName, r);
            }
            r.durationMs += s.durationMs;
            r.launches++; // Each session counts as a launch
        }
        List<AppUsageRecord> list = new ArrayList<>(map.values());
        Collections.sort(list, new Comparator<AppUsageRecord>() {
            @Override
            public int compare(AppUsageRecord a, AppUsageRecord b) {
                int byDuration = Long.compare(b.durationMs, a.durationMs);
                if (byDuration != 0) return byDuration;
                return a.appName.compareToIgnoreCase(b.appName);
            }
        });
        return list;
    }

    static ReportResult generateFullDay(Context context, String dayKey) throws Exception {
        return generateMasterReport(context);
    }

    static ReportResult generateTodayPreview(Context context) throws Exception {
        return generateMasterReport(context);
    }

    static int generateAllHistory(Context context) throws Exception {
        ReportResult result = generateMasterReport(context);
        return result.sessionCount;
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
                List<SessionRecord> daySessions = queryDaySessions(context, start, end);
                for (SessionRecord s : daySessions) {
                    masterList.add(new MasterRecord(dayKey, s));
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
        for (MasterRecord r : masterList) totalMs += r.session.durationMs;

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

    static List<SessionRecord> queryDaySessions(Context context, long start, long end) throws Exception {
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

        java.util.List<SessionRecord> sessions = new java.util.ArrayList<>();

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
                        addSession(sessions, currentApp, startTime, ts, start, end);
                        currentApp = pkg;
                        startTime = ts;
                    }
                } else {
                    currentApp = pkg;
                    startTime = ts;
                }
            } else if (type == android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND) {
                if (currentApp != null && currentApp.equals(pkg)) {
                    addSession(sessions, currentApp, startTime, ts, start, end);
                    currentApp = null;
                }
            } else if (type == 16 || type == 26 || type == 18) { // SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN, or KEYGUARD_SHOWN
                if (currentApp != null) {
                    addSession(sessions, currentApp, startTime, ts, start, end);
                    currentApp = null;
                }
            }
        }

        if (currentApp != null) {
            addSession(sessions, currentApp, startTime, Math.min(System.currentTimeMillis(), queryEnd), start, end);
        }

        java.util.Set<String> excluded = excludedPackages(context);
        android.content.pm.PackageManager pm = context.getPackageManager();
        java.util.List<SessionRecord> filteredList = new java.util.ArrayList<>();
        
        for (SessionRecord session : sessions) {
            if (session.durationMs < MIN_DURATION_MS) continue;
            if (excluded.contains(session.packageName)) continue;
            populateAppInfo(pm, session);
            filteredList.add(session);
        }

        java.util.Collections.sort(filteredList, new java.util.Comparator<SessionRecord>() {
            @Override
            public int compare(SessionRecord a, SessionRecord b) {
                return Long.compare(a.sessionStartMs, b.sessionStartMs);
            }
        });

        return filteredList;
    }

    private static void addSession(
            java.util.List<SessionRecord> sessions,
            String pkg,
            long activeStart,
            long activeEnd,
            long rangeStart,
            long rangeEnd
    ) {
        long clampedStart = Math.max(activeStart, rangeStart);
        long clampedEnd = Math.min(activeEnd, rangeEnd);
        if (clampedEnd > clampedStart) {
            sessions.add(new SessionRecord(pkg, clampedStart, clampedEnd, clampedEnd - clampedStart));
        }
    }

    private static final class MasterRecord {
        final String dateKey;
        final SessionRecord session;

        MasterRecord(String dateKey, SessionRecord session) {
            this.dateKey = dateKey;
            this.session = session;
        }
    }

    private static String formatIsoTime(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        String dateStr = format.format(new Date(millis));
        
        int offsetMillis = TimeZone.getDefault().getOffset(millis);
        int offsetHours = Math.abs(offsetMillis / 3600000);
        int offsetMinutes = Math.abs((offsetMillis / 60000) % 60);
        String sign = offsetMillis >= 0 ? "+" : "-";
        return String.format(Locale.US, "%s%s%02d:%02d", dateStr, sign, offsetHours, offsetMinutes);
    }

    private static void writeMasterCsv(File file, List<MasterRecord> masterList) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("date,app_name,category,package_name,session_start,session_end,duration_seconds\n");

        for (MasterRecord item : masterList) {
            sb.append(csv(item.dateKey)).append(',')
                    .append(csv(item.session.appName)).append(',')
                    .append(csv(item.session.category)).append(',')
                    .append(csv(item.session.packageName)).append(',')
                    .append(csv(formatIsoTime(item.session.sessionStartMs))).append(',')
                    .append(csv(formatIsoTime(item.session.sessionEndMs))).append(',')
                    .append(item.session.durationMs / 1000L).append('\n');
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
        final int sessionCount;
        final long totalMs;
        final File csvFile;
        final File jsonFile;
        final File textFile;

        ReportResult(
                String dayKey,
                int sessionCount,
                long totalMs,
                File csvFile,
                File jsonFile,
                File textFile
        ) {
            this.dayKey = dayKey;
            this.sessionCount = sessionCount;
            this.totalMs = totalMs;
            this.csvFile = csvFile;
            this.jsonFile = jsonFile;
            this.textFile = textFile;
        }
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

    private static void populateAppInfo(android.content.pm.PackageManager pm, SessionRecord record) {
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
