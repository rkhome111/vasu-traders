package com.vasutraders.app;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

final class TraderApi {
    private final URI origin = URI.create(BuildConfig.API_ROOT);

    Object call(String method, String path, JSONObject body, String token) throws Exception {
        boolean local = BuildConfig.DEBUG && "http".equals(origin.getScheme()) && "10.0.2.2".equals(origin.getHost());
        if (!local && !"https".equals(origin.getScheme())) throw new IOException("The Traders API requires HTTPS.");
        var connection = (HttpURLConnection) URI.create(origin.toString().replaceAll("/+$", "") + "/api/traders" + path)
                .toURL().openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(18000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (var output = connection.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            if (status == 204) return null;
            boolean success = status >= 200 && status < 300;
            String text = read(success ? connection.getInputStream() : connection.getErrorStream());
            Object result;
            try { result = new JSONTokener(text).nextValue(); }
            catch (Exception malformed) { throw new ApiError(status, "The Traders API is unavailable. Try again shortly."); }
            if (!success) throw new ApiError(status, status >= 500 ? "The Traders service is unavailable. Try again shortly." : message(result));
            return result;
        } finally { connection.disconnect(); }
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (stream; var bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int size; (size = stream.read(buffer)) != -1;) bytes.write(buffer, 0, size);
            return bytes.toString("UTF-8");
        }
    }

    private static String message(Object response) {
        if (response instanceof JSONObject json) {
            if (json.opt("details") instanceof JSONObject fields) {
                StringBuilder result = new StringBuilder();
                for (Iterator<String> it = fields.keys(); it.hasNext();) {
                    if (result.length() != 0) result.append(' ');
                    result.append(fields.optString(it.next()));
                }
                return result.toString();
            }
            if (json.opt("details") instanceof String details) return details;
        }
        return "Unable to complete this request.";
    }

    static final class ApiError extends IOException {
        final int status;
        ApiError(int status, String message) { super(message); this.status = status; }
    }
}
