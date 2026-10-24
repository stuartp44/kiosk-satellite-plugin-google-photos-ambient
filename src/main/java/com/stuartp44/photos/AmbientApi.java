package com.stuartp44.photos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class AmbientApi {
    static final String ROOT = "https://photosambient.googleapis.com/v1/";
    static final String OAUTH = "https://oauth2.googleapis.com/";
    static final String SCOPE = "https://www.googleapis.com/auth/photosambient.mediaitems";

    interface Transport {
        Response request(String method, String url, String bearer, String contentType, byte[] body, int limit)
                throws IOException;
        void close();
    }

    static final class Response {
        final int status;
        final byte[] body;
        Response(int status, byte[] body) { this.status = status; this.body = body; }
    }

    static final class ApiException extends IOException {
        final int status;
        final String code;
        ApiException(int status, String code) {
            this(status, code, "");
        }
        ApiException(int status, String code, String reason) {
            super("Google request failed (HTTP " + status + ", " + code
                    + (reason.isEmpty() ? "" : ", reason: " + reason) + ").");
            this.status = status;
            this.code = code;
        }
    }

    static final class HttpTransport implements Transport {
        private HttpsURLConnection current;
        private boolean closed;

        @Override public Response request(String method, String url, String bearer, String contentType,
                                          byte[] body, int limit) throws IOException {
            HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
            synchronized (this) {
                if (closed) throw new IOException("Connection closed");
                current = connection;
            }
            try {
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod(method);
                connection.setUseCaches(false);
                if (!bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", contentType);
                    connection.setFixedLengthStreamingMode(body.length);
                    try (OutputStream stream = connection.getOutputStream()) { stream.write(body); }
                }
                int status = connection.getResponseCode();
                InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (input == null) return new Response(status, new byte[0]);
                try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Request interrupted");
                        if (output.size() + count > limit) throw new IOException("Google response exceeds size limit");
                        output.write(buffer, 0, count);
                    }
                    return new Response(status, output.toByteArray());
                }
            } finally {
                synchronized (this) { if (current == connection) current = null; }
                connection.disconnect();
            }
        }

        @Override public synchronized void close() {
            closed = true;
            if (current != null) current.disconnect();
            current = null;
        }
    }

    private final Transport transport;
    AmbientApi(Transport transport) { this.transport = transport; }
    void close() { transport.close(); }

    JSONObject authorize(String clientId, String requestId, String displayName) throws IOException, JSONException {
        JSONObject state = new JSONObject().put("requestId", requestId).put("displayName", displayName);
        return form("device/code", fields("client_id", clientId, "scope", SCOPE, "state", state.toString()));
    }

    JSONObject poll(String clientId, String secret, String deviceCode) throws IOException, JSONException {
        return form("token", fields("client_id", clientId, "client_secret", secret, "device_code", deviceCode,
                "grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
    }

    JSONObject refresh(String clientId, String secret, String refreshToken) throws IOException, JSONException {
        return form("token", fields("client_id", clientId, "client_secret", secret,
                "refresh_token", refreshToken, "grant_type", "refresh_token"));
    }

    JSONObject create(String accessToken, String requestId, String name) throws IOException, JSONException {
        return json("POST", ROOT + "devices?requestId=" + encode(requestId), accessToken,
                new JSONObject().put("displayName", name).toString(), "application/json");
    }

    JSONObject device(String accessToken, String deviceId) throws IOException, JSONException {
        return json("GET", ROOT + "devices/" + encode(deviceId), accessToken, null, "");
    }

    void delete(String accessToken, String deviceId, String requestId) throws IOException, JSONException {
        String path = "devices/" + encode(deviceId.isEmpty() ? requestId : deviceId);
        try { json("DELETE", ROOT + path, accessToken, null, ""); }
        catch (ApiException error) { if (error.status != 404) throw error; }
    }

    List<String> photos(String accessToken, String deviceId) throws IOException, JSONException {
        JSONObject result = json("GET", ROOT + "mediaItems?deviceId=" + encode(deviceId) + "&pageSize=100",
                accessToken, null, "");
        if (!result.has("mediaItems")) return Collections.emptyList();
        JSONArray items = result.getJSONArray("mediaItems");
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < Math.min(100, items.length()); i++) {
            JSONObject media = items.getJSONObject(i).getJSONObject("mediaFile");
            if (media.getString("mimeType").startsWith("image/")) {
                String url = media.getString("baseUrl");
                validatePhotoUrl(url);
                urls.add(url);
            }
        }
        return urls;
    }

    byte[] photo(String baseUrl, int width, int height) throws IOException {
        validatePhotoUrl(baseUrl);
        String url = baseUrl + "=w" + Math.max(1, Math.min(2048, width))
                + "-h" + Math.max(1, Math.min(2048, height));
        Response result = transport.request("GET", url, "", "", null, 12 * 1024 * 1024);
        if (result.status != 200) throw new ApiException(result.status, "photo_download_failed");
        return result.body;
    }

    static void validatePhotoUrl(String value) throws IOException {
        URL url = new URL(value);
        String host = url.getHost();
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)
                || !(host.equals("googleusercontent.com") || host.endsWith(".googleusercontent.com"))
                || url.getRef() != null || url.getQuery() != null) {
            throw new IOException("Google returned an unsupported photo URL");
        }
    }

    private JSONObject form(String endpoint, Map<String, String> values) throws IOException, JSONException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (body.length() != 0) body.append('&');
            body.append(encode(value.getKey())).append('=').append(encode(value.getValue()));
        }
        return json("POST", OAUTH + endpoint, "", body.toString(), "application/x-www-form-urlencoded");
    }

    private JSONObject json(String method, String url, String bearer, String body, String contentType)
            throws IOException, JSONException {
        Response result = transport.request(method, url, bearer, contentType,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), 1024 * 1024);
        if (result.status < 200 || result.status >= 300) {
            JSONObject failure;
            try {
                failure = result.body.length == 0 ? new JSONObject()
                        : new JSONObject(new String(result.body, StandardCharsets.UTF_8));
            } catch (JSONException invalidErrorBody) {
                throw new ApiException(result.status, "invalid_error_response");
            }
            Object error = failure.opt("error");
            String code = error instanceof JSONObject ? ((JSONObject) error).optString("status", "unknown")
                    : error instanceof String ? (String) error : "unknown";
            if (!code.matches("[a-zA-Z_]{1,80}")) code = "unknown";
            String reason = error instanceof JSONObject ? safeErrorReason((JSONObject) error) : "";
            if (result.status == 403 && reason.isEmpty()) reason = "not supplied";
            throw new ApiException(result.status, code, reason);
        }
        return result.body.length == 0 ? new JSONObject()
                : new JSONObject(new String(result.body, StandardCharsets.UTF_8));
    }

    private static String safeErrorReason(JSONObject error) {
        JSONArray details = error.optJSONArray("details");
        if (details == null) return "";
        boolean withheld = false;
        for (int i = 0; i < details.length(); i++) {
            JSONObject detail = details.optJSONObject(i);
            if (detail == null
                    || !"type.googleapis.com/google.rpc.ErrorInfo".equals(detail.optString("@type"))) continue;
            if (!detail.optString("reason").isEmpty()) withheld = true;
            if (!("googleapis.com".equals(detail.optString("domain"))
                    || "photosambient.googleapis.com".equals(detail.optString("domain")))) continue;
            // Only known diagnostic constants may reach settings and logs, never arbitrary response text.
            switch (detail.optString("reason")) {
                case "ACCESS_TOKEN_SCOPE_INSUFFICIENT": return "ACCESS_TOKEN_SCOPE_INSUFFICIENT";
                case "ACCESS_TOKEN_EXPIRED": return "ACCESS_TOKEN_EXPIRED";
                case "ACCESS_TOKEN_TYPE_UNSUPPORTED": return "ACCESS_TOKEN_TYPE_UNSUPPORTED";
                case "SERVICE_DISABLED": return "SERVICE_DISABLED";
                case "BILLING_DISABLED": return "BILLING_DISABLED";
                case "CONSUMER_INVALID": return "CONSUMER_INVALID";
                case "USER_PROJECT_DENIED": return "USER_PROJECT_DENIED";
                case "IAM_PERMISSION_DENIED": return "IAM_PERMISSION_DENIED";
                case "SECURITY_POLICY_VIOLATED": return "SECURITY_POLICY_VIOLATED";
                case "ORG_RESTRICTION_VIOLATION": return "ORG_RESTRICTION_VIOLATION";
                default: break;
            }
        }
        return withheld ? "withheld (unrecognized reason or domain)" : "";
    }

    static Map<String, String> fields(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        return values;
    }

    private static String encode(String text) throws IOException {
        return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
    }
}
