package com.seif.screentimeexporter;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.GoogleAuthUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class GoogleDriveUploader {
    private static final String PREFS = "screen_time_exporter";
    private static final String LAST_UPLOAD_KEY = "last_upload_status";
    private static final String DRIVE_FOLDER_KEY = "drive_folder_name";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private GoogleDriveUploader() {}

    static String getLastUploadStatus(Context context) {
        return prefs(context).getString(LAST_UPLOAD_KEY, "No upload yet");
    }

    static void setDriveFolderName(Context context, String folderName) {
        String safeName = (folderName == null || folderName.trim().isEmpty()) ? "ScreenTimeBackups" : folderName.trim();
        prefs(context).edit().putString(DRIVE_FOLDER_KEY, safeName).apply();
    }

    static String getDriveFolderName(Context context) {
        return prefs(context).getString(DRIVE_FOLDER_KEY, "ScreenTimeBackups");
    }

    static void listDriveFoldersAsync(Context context, FolderCallback callback) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        if (account == null) {
            if (callback != null) callback.onResult(null, "Sign in to Google first");
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                String scope = "oauth2:https://www.googleapis.com/auth/drive";
                String token = GoogleAuthUtil.getToken(context, account.getAccount(), scope);

                List<String> folders = new ArrayList<>();
                String query = "mimeType='application/vnd.google-apps.folder' and trashed=false";
                String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
                URL url = new URL("https://www.googleapis.com/drive/v3/files?q=" + encodedQuery + "&spaces=drive&fields=files(name)");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                if (conn.getResponseCode() == 200) {
                    String json = readResponse(conn);
                    JSONObject root = new JSONObject(json);
                    JSONArray files = root.optJSONArray("files");
                    if (files != null) {
                        for (int i = 0; i < files.length(); i++) {
                            JSONObject file = files.getJSONObject(i);
                            String name = file.optString("name", "");
                            if (!name.isEmpty()) folders.add(name);
                        }
                    }
                    if (callback != null) callback.onResult(folders, null);
                } else {
                    if (callback != null) callback.onResult(null, "Drive connection error: " + conn.getResponseCode());
                }
            } catch (Exception e) {
                if (callback != null) callback.onResult(null, "Error: " + e.getMessage());
            }
        });
    }

    static void uploadFileAsync(Context context, File file, UploadCallback callback) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        if (account == null) {
            String status = "Sign in to Google first";
            prefs(context).edit().putString(LAST_UPLOAD_KEY, status).apply();
            if (callback != null) callback.onResult(false, status);
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                String scope = "oauth2:https://www.googleapis.com/auth/drive";
                String token = GoogleAuthUtil.getToken(context, account.getAccount(), scope);

                String folderName = getDriveFolderName(context);
                boolean success = uploadFileSync(file, folderName, token);

                String status = success
                        ? "Uploaded " + file.getName() + " successfully"
                        : "Failed to upload " + file.getName();
                prefs(context).edit().putString(LAST_UPLOAD_KEY, status).apply();
                if (callback != null) callback.onResult(success, status);
            } catch (Exception e) {
                String status = "Upload error: " + e.getMessage();
                prefs(context).edit().putString(LAST_UPLOAD_KEY, status).apply();
                if (callback != null) callback.onResult(false, status);
            }
        });
    }

    private static boolean uploadFileSync(File file, String folderName, String token) throws Exception {
        if (!file.exists()) return false;

        String folderId = getOrCreateFolderId(folderName, token);
        if (folderId == null) {
            throw new Exception("Could not find or create folder");
        }

        String fileId = searchFileId(file.getName(), folderId, token);

        String apiUrl = fileId != null
            ? "https://www.googleapis.com/upload/drive/v3/files/" + fileId + "?uploadType=media"
            : "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart";

        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);

        if (fileId != null) {
            conn.setRequestMethod("PATCH");
            conn.setRequestProperty("Content-Type", "text/csv");

            try (OutputStream os = conn.getOutputStream();
                 FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        } else {
            conn.setRequestMethod("POST");
            String boundary = "-------314159265358979323846";
            conn.setRequestProperty("Content-Type", "multipart/related; boundary=\"" + boundary + "\"");

            String metadata = "{\"name\": \"" + file.getName() + "\", \"parents\": [\"" + folderId + "\"]}";

            StringBuilder body = new StringBuilder();
            body.append("--").append(boundary).append("\r\n");
            body.append("Content-Type: application/json; charset=UTF-8\r\n\r\n");
            body.append(metadata).append("\r\n");
            body.append("--").append(boundary).append("\r\n");
            body.append("Content-Type: text/csv\r\n\r\n");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));

                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = fis.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                    }
                }

                os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
        }

        int responseCode = conn.getResponseCode();
        return responseCode >= 200 && responseCode < 300;
    }

    private static String getOrCreateFolderId(String folderName, String token) throws Exception {
        String query = "mimeType='application/vnd.google-apps.folder' and name='" + folderName + "' and trashed=false";
        String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        URL url = new URL("https://www.googleapis.com/drive/v3/files?q=" + encodedQuery + "&spaces=drive&fields=files(id)");

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        if (conn.getResponseCode() == 200) {
            String json = readResponse(conn);
            JSONObject root = new JSONObject(json);
            JSONArray files = root.optJSONArray("files");
            if (files != null && files.length() > 0) {
                return files.getJSONObject(0).optString("id", null);
            }
        }

        // Create folder
        URL createUrl = new URL("https://www.googleapis.com/drive/v3/files");
        HttpURLConnection createConn = (HttpURLConnection) createUrl.openConnection();
        createConn.setRequestMethod("POST");
        createConn.setRequestProperty("Authorization", "Bearer " + token);
        createConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        createConn.setConnectTimeout(15000);
        createConn.setReadTimeout(15000);
        createConn.setDoOutput(true);

        String payload = "{\"name\": \"" + folderName + "\", \"mimeType\": \"application/vnd.google-apps.folder\"}";
        try (OutputStream os = createConn.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        if (createConn.getResponseCode() == 200) {
            String json = readResponse(createConn);
            JSONObject root = new JSONObject(json);
            return root.optString("id", null);
        }

        return null;
    }

    private static String searchFileId(String fileName, String folderId, String token) throws Exception {
        String query = "name='" + fileName + "' and '" + folderId + "' in parents and trashed=false";
        String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        URL url = new URL("https://www.googleapis.com/drive/v3/files?q=" + encodedQuery + "&spaces=drive&fields=files(id)");

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        if (conn.getResponseCode() == 200) {
            String json = readResponse(conn);
            JSONObject root = new JSONObject(json);
            JSONArray files = root.optJSONArray("files");
            if (files != null && files.length() > 0) {
                return files.getJSONObject(0).optString("id", null);
            }
        }
        return null;
    }

    private static String readResponse(HttpURLConnection conn) throws Exception {
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    interface UploadCallback {
        void onResult(boolean success, String message);
    }

    interface FolderCallback {
        void onResult(List<String> folders, String error);
    }
}