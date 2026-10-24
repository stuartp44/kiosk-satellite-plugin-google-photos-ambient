package com.stuartp44.photos;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.jxl.kiosk.plugins.PluginHost;
import org.json.JSONException;
import org.json.JSONObject;

final class AmbientSession {
    interface Clock { long now(); }
    interface Display {
        boolean visible();
        int width();
        int height();
        boolean hasPhoto();
        void sizing(boolean fill);
        void clear(String message);
        void photo(byte[] bytes) throws IOException;
        void close();
    }

    private final AmbientApi api;
    private final PluginHost host;
    private final Display display;
    private final Clock clock;
    private final Object lifecycle = new Object();
    private volatile boolean stopped;
    private volatile String canceledRun = "";
    private String clientId = "", secret = "", name = "Kiosk Satellite";
    private String refreshToken = "", deviceId = "", requestId = "", boundClientId = "";
    private String accessToken = "";
    private long tokenExpires, retryAt, nextDevicePoll, nextList, nextSlide;
    private int slideSeconds = 30, index;
    private int failures;
    private List<String> photos = Collections.emptyList();
    private volatile String run = "";
    private String deviceCode = "", instructions = "";
    private long authorizationExpires, nextAuthorizationPoll, nextProgress;
    private long authorizationInterval = 5000;
    private boolean sourcesSet;
    private boolean configured;
    private boolean wasVisible;
    private String operation = "initializing";
    private String grantedScopeStatus = "";

    AmbientSession(AmbientApi api, PluginHost host, Display display, Clock clock) {
        this.api = api;
        this.host = host;
        this.display = display;
        this.clock = clock;
    }

    void initialize(Map<String, Object> settings) {
        refreshToken = text(settings, "refreshToken", "");
        deviceId = text(settings, "deviceId", "");
        requestId = text(settings, "requestId", "");
        boundClientId = text(settings, "boundClientId", "");
        log("Session started; saved connection " + (refreshToken.isEmpty() ? "absent." : "present."));
        configure(settings);
    }

    void configure(Map<String, Object> settings) {
        try {
            String nextClient = text(settings, "clientId", "");
            String nextSecret = text(settings, "clientSecret", "");
            String nextName = text(settings, "displayName", "Kiosk Satellite").trim();
            Object seconds = settings.get("slideSeconds");
            double value = seconds instanceof Number ? ((Number) seconds).doubleValue() : 30;
            String fit = text(settings, "fit", "Fit");
            if (nextName.isEmpty() || nextName.length() > 100)
                throw new IllegalArgumentException("Device name must contain 1 to 100 characters.");
            if (!Double.isFinite(value) || value < 10 || value > 300 || value % 5 != 0)
                throw new IllegalArgumentException("Photo interval must be 10 to 300 seconds in steps of 5.");
            if (!fit.equals("Fit") && !fit.equals("Fill"))
                throw new IllegalArgumentException("Photo sizing must be Fit or Fill.");
            boolean credentialsChanged = !nextClient.equals(clientId) || !nextSecret.equals(secret);
            if (credentialsChanged && !run.isEmpty()) finish("error", "Sign-in canceled because credentials changed.");
            clientId = nextClient;
            secret = nextSecret;
            name = nextName;
            slideSeconds = (int) value;
            display.sizing("Fill".equals(fit));
            if (credentialsChanged) {
                grantedScopeStatus = "";
                log("OAuth credentials changed; clearing in-memory authorization and photos.");
                accessToken = "";
                photos = Collections.emptyList();
                sourcesSet = false;
                nextDevicePoll = 0;
                nextList = 0;
                retryAt = 0;
                failures = 0;
                display.clear("Checking Google Photos connection...");
            }
            configured = true;
            if (clientId.isEmpty() || secret.isEmpty()) status("Enter your Google OAuth client ID and secret.", false);
            else if (!boundClientId.isEmpty() && !boundClientId.equals(clientId)) {
                display.clear("Restore the original OAuth client to disconnect this device before changing clients.");
                status("Saved connection belongs to another OAuth client. Restore it and disconnect first.", true);
            } else if (refreshToken.isEmpty()) status("Press Sign in to connect Google Photos.", false);
        } catch (IllegalArgumentException error) {
            configured = false;
            display.clear(error.getMessage());
            status(error.getMessage(), true);
        }
    }

    void connect(String requestedRun) {
        if (stopped) return;
        if (!run.isEmpty()) finish("error", "Replaced by another sign-in.");
        run = requestedRun;
        canceledRun = "";
        retryAt = 0;
        failures = 0;
        try {
            if (!configured || clientId.isEmpty() || secret.isEmpty())
                throw new IOException("missing_credentials");
            if (!refreshToken.isEmpty() || !deviceId.isEmpty())
                throw new IOException("already_connected");
            requestId = UUID.randomUUID().toString();
            boundClientId = clientId;
            persist();
            if (!progress("Requesting a Google sign-in code...")) return;
            status("Requesting a Google sign-in code...", false);
            operation = "requesting sign-in code";
            log("Requesting Google sign-in code.");
            JSONObject response = api.authorize(clientId, requestId, name);
            if (!wanted()) return;
            deviceCode = response.getString("device_code");
            String url = response.getString("verification_url");
            if (!url.startsWith("https://")) throw new IOException("invalid_verification_url");
            String signInDetails = "Open " + url + "\nCode: " + response.getString("user_code")
                    + "\nThen select photos in Google Photos.";
            instructions = "Waiting for Google authorization. Use the URL and code in the status message above.";
            long now = clock.now();
            authorizationExpires = now + seconds(response, "expires_in", 0) * 1000;
            authorizationInterval = Math.max(5000, seconds(response, "interval", 5) * 1000);
            nextAuthorizationPoll = now + authorizationInterval;
            nextProgress = now + 20000;
            log("Sign-in code received; waiting for browser authorization.");
            status(signInDetails, false);
            progress(instructions);
        } catch (IOException | JSONException error) {
            fail(error);
        }
    }

    void cancel(String run) {
        if (run.equals(this.run)) canceledRun = run;
    }

    void disconnect(String requestedRun) {
        if (stopped) return;
        if (!run.isEmpty()) finish("error", "Sign-in canceled by disconnect.");
        run = requestedRun;
        canceledRun = "";
        try {
            operation = "disconnecting";
            log("Disconnect requested.");
            if (!progress("Removing this Google Photos device...")) return;
            if (!refreshToken.isEmpty()) {
                requireClient();
                token();
                if (!wanted()) return;
                if (!deviceId.isEmpty() || !requestId.isEmpty()) {
                    operation = "deleting Ambient device";
                    log("Deleting Ambient device.");
                    api.delete(accessToken, deviceId, requestId);
                }
                if (!wanted()) return;
            }
            refreshToken = deviceId = requestId = boundClientId = accessToken = "";
            grantedScopeStatus = "";
            photos = Collections.emptyList();
            sourcesSet = false;
            nextList = nextSlide = nextDevicePoll = 0;
            persist();
            display.clear("Connect Google Photos in Plugin Manager.");
            status("Google Photos disconnected.", false);
            log("Disconnect completed; saved connection erased.");
            finish("success", "Device removed and saved connection erased.");
        } catch (IOException | JSONException error) {
            display.clear("Disconnect failed. Retry in Plugin Manager.");
            fail(error);
        }
    }

    void forget(String requestedRun) {
        if (stopped) return;
        if (!run.isEmpty()) finish("error", "Sign-in canceled.");
        run = requestedRun;
        canceledRun = "";
        try {
            operation = "forgetting local connection";
            log("Forgetting local connection without deleting the Google device.");
            refreshToken = deviceId = requestId = boundClientId = accessToken = "";
            grantedScopeStatus = "";
            photos = Collections.emptyList();
            sourcesSet = false;
            nextList = nextSlide = nextDevicePoll = retryAt = 0;
            persist();
            display.clear("Connect Google Photos in Plugin Manager.");
            status("Local connection erased. Remove the old device in Google Photos if it still exists.", false);
            finish("success", "Local connection erased. This does not remove the device from Google Photos.");
        } catch (IOException error) { fail(error); }
    }

    void tick() {
        if (stopped || !configured) return;
        try {
            long now = clock.now();
            if (!run.isEmpty()) {
                if (!wanted()) {
                    log("Setup canceled; stopping setup polling.");
                    status("Google Photos setup canceled.", false);
                    run = deviceCode = instructions = "";
                    return;
                }
                if (now >= nextProgress) {
                    if (!progress(instructions)) return;
                    nextProgress = now + 20000;
                }
                if (!deviceCode.isEmpty()) {
                    if (now >= authorizationExpires) {
                        log("Google sign-in code expired.");
                        status("Google sign-in expired. Press Sign in to try again.", false);
                        finish("error", "Google sign-in expired. Press Sign in to try again.");
                        return;
                    }
                    if (now >= nextAuthorizationPoll) pollAuthorization(now);
                    return;
                }
            }
            if (refreshToken.isEmpty() || now < retryAt) return;
            requireClient();
            boolean visible = display.visible();
            if (visible != wasVisible)
                log(visible ? "Screensaver visible; resuming photo updates."
                        : "Screensaver hidden; pausing photo updates.");
            if (!visible && wasVisible) {
                display.clear("Loading Google Photos...");
                nextSlide = 0;
            }
            wasVisible = visible;
            if (!visible && run.isEmpty()) return;
            token();
            if (stopped) return;
            if (deviceId.isEmpty()) {
                operation = "creating Ambient device";
                log("Creating Ambient device.");
                JSONObject device = api.create(accessToken, requestId, name);
                if (stopped) return;
                deviceId = device.getString("id");
                log("Ambient device created.");
                persist();
                nextDevicePoll = 0;
                nextList = 0;
            }
            if (now >= nextDevicePoll) {
                operation = "reading Ambient device";
                boolean firstPoll = nextDevicePoll == 0;
                JSONObject device = api.device(accessToken, deviceId);
                if (stopped) return;
                boolean previousSources = sourcesSet;
                sourcesSet = device.optBoolean("mediaSourcesSet", false);
                if (firstPoll || sourcesSet != previousSources)
                    log(sourcesSet ? "Photo sources selected." : "Waiting for photo source selection.");
                if (sourcesSet && !previousSources) nextList = 0;
                JSONObject polling = device.optJSONObject("pollingConfig");
                long interval = polling == null ? 10000 : pollInterval(polling.optString("pollInterval", "10s"));
                nextDevicePoll = now + Math.max(1000, interval);
                if (!sourcesSet) {
                    photos = Collections.emptyList();
                    display.clear("Select photos in Google Photos to start the screensaver.");
                    String settingsUri = device.optString("settingsUri");
                    String message = "Select photo sources in Google Photos"
                            + (settingsUri.isEmpty() ? "." : ": " + settingsUri);
                    status(message, false);
                    if (!run.isEmpty()) {
                        instructions = message;
                        progress(message);
                    }
                    return;
                }
                if (!run.isEmpty()) finish("success", "Google Photos connected. Select Google Photos in Screensaver mode.");
            }
            if (!sourcesSet || !visible) return;
            if (now >= nextList) {
                operation = "listing photo batch";
                log("Refreshing curated photo batch.");
                List<String> updated = api.photos(accessToken, deviceId);
                if (stopped) return;
                photos = updated;
                log("Photo batch received: " + photos.size() + " displayable images; next refresh in 20 minutes.");
                index = 0;
                nextList = now + 20 * 60 * 1000;
                if (photos.isEmpty()) {
                    display.clear("No displayable photos. Update your sources in Google Photos.");
                    status("Google Photos returned no displayable photos.", false);
                    return;
                }
                nextSlide = 0;
            }
            List<String> batch = photos;
            if (!batch.isEmpty() && (now >= nextSlide || !display.hasPhoto())) {
                operation = "downloading photo";
                byte[] bytes = api.photo(batch.get(index), display.width(), display.height());
                if (stopped) return;
                operation = "decoding photo";
                display.photo(bytes);
                index = (index + 1) % batch.size();
                nextSlide = clock.now() + slideSeconds * 1000L;
                failures = 0;
                status("Google Photos slideshow connected.", false);
            }
        } catch (IOException | JSONException error) {
            fail(error);
        }
    }

    private void pollAuthorization(long now) throws IOException, JSONException {
        nextAuthorizationPoll = now + authorizationInterval;
        try {
            operation = "polling sign-in authorization";
            JSONObject result = api.poll(clientId, secret, deviceCode);
            if (!wanted()) return;
            refreshToken = result.getString("refresh_token");
            accessToken = result.getString("access_token");
            tokenExpires = now + seconds(result, "expires_in", 0) * 1000;
            reportGrantedScope(result);
            persist();
            deviceCode = "";
            log("Google authorization completed; tokens saved.");
            instructions = "Signed in. Waiting for photo source selection...";
            status(instructions, false);
            progress(instructions);
        } catch (AmbientApi.ApiException error) {
            if ("authorization_pending".equals(error.code)) return;
            if ("slow_down".equals(error.code)) {
                authorizationInterval += 5000;
                nextAuthorizationPoll = now + authorizationInterval;
                log("Google requested slower authorization polling; interval " + authorizationInterval / 1000 + " seconds.");
                return;
            }
            throw error;
        }
    }

    private void token() throws IOException, JSONException {
        if (!accessToken.isEmpty() && clock.now() < tokenExpires - 60000) return;
        operation = "refreshing OAuth token";
        log("Refreshing Google authorization.");
        JSONObject result = api.refresh(clientId, secret, refreshToken);
        if (stopped) return;
        accessToken = result.getString("access_token");
        tokenExpires = clock.now() + seconds(result, "expires_in", 0) * 1000;
        reportGrantedScope(result);
        String rotated = result.optString("refresh_token", refreshToken);
        if (!rotated.equals(refreshToken)) { refreshToken = rotated; persist(); }
        log("Google authorization refreshed.");
    }

    private void reportGrantedScope(JSONObject result) {
        Object scope = result.opt("scope");
        grantedScopeStatus = "Ambient permission: not reported in token response.";
        if (scope instanceof String && !((String) scope).trim().isEmpty()) {
            boolean granted = false;
            for (String value : ((String) scope).trim().split("\\s+")) {
                if (AmbientApi.SCOPE.equals(value)) {
                    granted = true;
                    break;
                }
            }
            grantedScopeStatus = granted ? "Ambient permission: granted in token response."
                    : "Ambient permission: missing from reported token scopes.";
        }
        log(grantedScopeStatus);
    }

    private void requireClient() throws IOException {
        if (clientId.isEmpty() || secret.isEmpty()) throw new IOException("missing_credentials");
        if (!clientId.equals(boundClientId)) throw new IOException("client_changed");
    }

    private void persist() throws IOException {
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("refreshToken", refreshToken);
        saved.put("deviceId", deviceId);
        saved.put("requestId", requestId);
        saved.put("boundClientId", boundClientId);
        for (Object value : saved.values()) {
            if (((String) value).length() > 512) throw new IOException("Saved Google connection exceeds host setting limit");
        }
        synchronized (lifecycle) { if (!stopped) host.updateSettings(saved); }
    }

    private boolean wanted() { return !stopped && !run.isEmpty() && !run.equals(canceledRun); }

    private boolean progress(String message) {
        synchronized (lifecycle) {
            if (!wanted()) return false;
            if (!host.setupResult(run, "running", message)) { canceledRun = run; return false; }
            return true;
        }
    }

    private void finish(String state, String message) {
        synchronized (lifecycle) {
            if (wanted()) host.setupResult(run, state, message);
            run = deviceCode = instructions = "";
        }
    }

    private void fail(Exception error) {
        if (stopped) return;
        String message;
        if (error instanceof AmbientApi.ApiException) {
            AmbientApi.ApiException apiError = (AmbientApi.ApiException) error;
            message = apiError.getMessage();
            if ("photo_download_failed".equals(apiError.code)) {
                photos = Collections.emptyList();
                nextList = 0;
                display.clear("Photo unavailable. Waiting to refresh Google Photos...");
            } else if (apiError.status == 401 || apiError.status == 403 || "invalid_grant".equals(apiError.code)) {
                accessToken = "";
                photos = Collections.emptyList();
                nextList = nextDevicePoll = 0;
                display.clear("Google authorization failed. Check credentials or disconnect and sign in again.");
            }
            if (apiError.status == 404 && !"photo_download_failed".equals(apiError.code)) {
                photos = Collections.emptyList();
                nextList = nextDevicePoll = 0;
                display.clear("Google Photos device not found. Disconnect and sign in again.");
            }
            if ("FAILED_PRECONDITION".equals(apiError.code)) {
                photos = Collections.emptyList();
                sourcesSet = false;
                nextList = nextDevicePoll = 0;
                display.clear("Select photo sources in Google Photos.");
            }
        } else if (error instanceof JSONException) {
            message = "Google returned an invalid response.";
        } else {
            message = "Could not complete Google Photos request. Check credentials, connection and API access.";
        }
        failures = Math.min(7, failures + 1);
        long delay = Math.min(60 * 60 * 1000L, 60000L << (failures - 1));
        if (error instanceof AmbientApi.ApiException && ((AmbientApi.ApiException) error).status == 429)
            delay = Math.max(15 * 60 * 1000L, delay);
        retryAt = clock.now() + delay;
        message = "While " + operation + ": " + message;
        if (!grantedScopeStatus.isEmpty()) message += "\n" + grantedScopeStatus;
        log("Operation failed: " + operation + "; retry backoff " + delay / 1000 + " seconds.");
        status(message, true);
        finish("error", message);
    }

    private void log(String message) {
        synchronized (lifecycle) {
            if (!stopped) host.log(message);
        }
    }

    private void status(String message, boolean error) {
        synchronized (lifecycle) {
            if (stopped) return;
            host.status(message, error);
            if (error) host.log(message);
        }
    }

    void incompatibleHost() {
        status("Update Kiosk Satellite: native screensavers and setup/secret settings support are required.", true);
        close();
    }

    void close() {
        synchronized (lifecycle) { stopped = true; }
        api.close();
        display.close();
    }

    static long pollInterval(String value) throws IOException {
        if (!value.matches("[0-9]+(?:\\.[0-9]{1,9})?s")) throw new IOException("Invalid polling interval");
        double milliseconds = Double.parseDouble(value.substring(0, value.length() - 1)) * 1000;
        if (!Double.isFinite(milliseconds) || milliseconds > 86400000) throw new IOException("Invalid polling interval");
        return (long) Math.ceil(milliseconds);
    }

    private static long seconds(JSONObject value, String key, long fallback) throws JSONException {
        long seconds = value.has(key) ? value.getLong(key) : fallback;
        if (seconds <= 0 || seconds > 86400) throw new JSONException("Invalid OAuth lifetime");
        return seconds;
    }

    private static String text(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : fallback;
    }
}
