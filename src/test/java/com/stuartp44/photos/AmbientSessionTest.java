package com.stuartp44.photos;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.PluginHost;
import org.junit.jupiter.api.Test;

class AmbientSessionTest {
    @Test void reportsGrantedScopeForSignInAndRefreshWithoutExposingTokenResponse() {
        Object[] scopes = {AmbientApi.SCOPE, "profile " + AmbientApi.SCOPE + " private-scope",
                " \t" + AmbientApi.SCOPE + "\n ", "profile",
                AmbientApi.SCOPE + ".extra", "", "   ", null, Boolean.TRUE};
        for (boolean signIn : new boolean[] {false, true}) {
            for (int i = 0; i < scopes.length; i++) {
                Fixture f = new Fixture(!signIn);
                org.json.JSONObject tokens = new org.json.JSONObject(TOKENS)
                        .put("access_token", "private-access-token")
                        .put("refresh_token", "private-refresh-token");
                if (scopes[i] != null) tokens.put("scope", scopes[i]);
                f.transport.reply(200, signIn ? CODE : tokens.toString());
                if (signIn) {
                    f.session.connect("pair");
                    f.now += 5000;
                    f.transport.reply(200, tokens.toString());
                    f.session.tick();
                }
                String expected = i < 3 ? "Ambient permission: granted in token response."
                        : i < 5 ? "Ambient permission: missing from reported token scopes."
                        : "Ambient permission: not reported in token response.";
                f.transport.reply(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\"}}");
                f.session.tick();
                assertTrue(f.host.logs.contains(expected));
                assertTrue(f.host.status.endsWith(expected));
                String exposed = f.host.status + String.join("\n", f.host.logs)
                        + String.join("\n", f.host.progress);
                for (String privateValue : new String[] {"private-access-token", "private-refresh-token",
                        "private-scope", AmbientApi.SCOPE}) {
                    assertFalse(exposed.contains(privateValue));
                }
            }
        }
    }

    @Test void laterTokenResponseDoesNotReusePreviouslyReportedScope() {
        Fixture f = new Fixture(true);
        f.transport.reply(200, new org.json.JSONObject(TOKENS).put("scope", AmbientApi.SCOPE).toString());
        f.transport.reply(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\"}}");
        f.session.tick();
        assertTrue(f.host.status.endsWith("Ambient permission: granted in token response."));
        f.now += 60000;
        f.transport.reply(200, TOKENS);
        f.transport.reply(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\"}}");
        f.session.tick();
        assertTrue(f.host.status.endsWith("Ambient permission: not reported in token response."));
    }

    @Test void legacyDiagnosticSettingCannotExposeUnrecognizedIdentifiers() {
        for (boolean enabled : new boolean[] {false, true}) {
            Fixture f = new Fixture(true);
            Map<String, Object> config = settings();
            config.put("errorDiagnostics", enabled);
            f.session.configure(config);
            f.transport.reply(200, TOKENS);
            f.transport.reply(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\","
                    + "\"message\":\"private message\",\"details\":["
                    + "{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\","
                    + "\"reason\":\"SYNTHETIC_UNKNOWN_REASON\",\"domain\":\"synthetic.example\","
                    + "\"metadata\":{\"secret\":\"private metadata\"}}]}}");
            f.session.tick();
            assertFalse(f.host.status.contains("SYNTHETIC_UNKNOWN_REASON"));
            assertFalse(f.host.status.contains("synthetic.example"));
            assertFalse(f.host.status.contains("private"));
            assertTrue(f.host.logs.stream().noneMatch(s -> s.contains("SYNTHETIC_UNKNOWN_REASON")
                    || s.contains("synthetic.example") || s.contains("private")));
            assertTrue(f.host.progress.stream().noneMatch(s -> s.contains("SYNTHETIC_UNKNOWN_REASON")));
            config.put("errorDiagnostics", false);
            f.session.configure(config);
            assertFalse(f.host.status.contains("SYNTHETIC_UNKNOWN_REASON"));
        }
    }

    static final String TOKENS = "{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600}";
    static final String DEVICE = "{\"id\":\"device\",\"mediaSourcesSet\":true,\"pollingConfig\":{\"pollInterval\":\"10s\"}}";
    static final String PHOTOS = "{\"mediaItems\":[{\"mediaFile\":{\"mimeType\":\"image/jpeg\","
            + "\"baseUrl\":\"https://lh3.googleusercontent.com/photo\"}}]}";
    static final String CODE = "{\"device_code\":\"code\",\"user_code\":\"ABCD\",\"verification_url\":"
            + "\"https://google.com/device\",\"expires_in\":300,\"interval\":5}";

    static final class Call {
        final String method, url, bearer, body;
        final int limit;
        Call(String method, String url, String bearer, byte[] body, int limit) {
            this.method = method; this.url = url; this.bearer = bearer; this.limit = limit;
            this.body = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }
    }

    static class Transport implements AmbientApi.Transport {
        final Deque<AmbientApi.Response> responses = new ArrayDeque<>();
        final List<Call> calls = new ArrayList<>();
        boolean closed;
        void reply(int status, String body) { responses.add(new AmbientApi.Response(status, body.getBytes(StandardCharsets.UTF_8))); }
        @Override public AmbientApi.Response request(String method, String url, String bearer,
                String type, byte[] body, int limit) throws IOException {
            if (closed) throw new IOException("closed");
            calls.add(new Call(method, url, bearer, body, limit));
            assertFalse(responses.isEmpty(), "Unexpected request: " + method + " " + url);
            return responses.remove();
        }
        @Override public void close() { closed = true; }
    }

    static final class Host implements PluginHost {
        final Map<String, Object> saved = new HashMap<>();
        final List<String> progress = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        String status = "";
        boolean error, wanted = true;
        int saves;
        @Override public void updateSettings(Map<String, Object> values) { saved.putAll(values); saves++; }
        @Override public boolean setupResult(String run, String state, String message) {
            progress.add(run + ":" + state + ":" + message);
            return wanted;
        }
        @Override public void status(String text, boolean error) { this.status = text; this.error = error; }
        @Override public void log(String message) { logs.add(message); }
    }

    static final class Display implements AmbientSession.Display {
        boolean visible = true, closed, hasPhoto;
        int rendered;
        String message = "";
        @Override public boolean visible() { return visible; }
        @Override public int width() { return 1920; }
        @Override public int height() { return 1080; }
        @Override public boolean hasPhoto() { return hasPhoto; }
        @Override public void sizing(boolean fill) {}
        @Override public void clear(String message) { this.message = message; hasPhoto = false; }
        @Override public void photo(byte[] bytes) { assertFalse(closed); rendered++; hasPhoto = true; }
        @Override public void close() { closed = true; }
    }

    static final class Fixture {
        final Transport transport;
        final Host host = new Host();
        final Display display = new Display();
        final AmbientSession session;
        long now = 100000;
        Fixture(boolean connected) { this(connected, new Transport()); }
        Fixture(boolean connected, Transport transport) {
            this.transport = transport;
            session = new AmbientSession(new AmbientApi(transport), host, display, () -> now);
            Map<String, Object> settings = settings();
            if (connected) {
                settings.put("refreshToken", "refresh");
                settings.put("deviceId", "device");
                settings.put("boundClientId", "client");
                settings.put("requestId", UUID.randomUUID().toString());
            }
            session.initialize(settings);
        }
        void ready() {
            transport.reply(200, TOKENS);
            transport.reply(200, DEVICE);
            transport.reply(200, PHOTOS);
            transport.reply(200, "fake-image");
            session.tick();
        }
    }

    static Map<String, Object> settings() {
        Map<String, Object> values = new HashMap<>();
        values.put("clientId", "client"); values.put("clientSecret", "secret");
        return values;
    }

    @Test void restoredConnectionRefreshesListsAndDownloadsAtScreenSize() {
        Fixture f = new Fixture(true);
        f.ready();
        assertEquals(1, f.display.rendered);
        assertEquals(4, f.transport.calls.size());
        assertTrue(f.transport.calls.get(0).body.contains("grant_type=refresh_token"));
        assertEquals(AmbientApi.ROOT + "mediaItems?deviceId=device&pageSize=100", f.transport.calls.get(2).url);
        assertEquals("access", f.transport.calls.get(2).bearer);
        assertEquals("https://lh3.googleusercontent.com/photo=w1920-h1080", f.transport.calls.get(3).url);
        assertEquals("", f.transport.calls.get(3).bearer, "Never send OAuth tokens to photo URLs");
        assertEquals(12 * 1024 * 1024, f.transport.calls.get(3).limit);
    }

    @Test void signInUsesStateAndHonorsPendingAndSlowDown() {
        Fixture f = new Fixture(false);
        f.transport.reply(200, CODE);
        f.session.connect("pair");
        assertTrue(f.host.status.contains("https://google.com/device"));
        assertTrue(f.host.status.contains("Code: ABCD"));
        assertFalse(f.host.progress.get(f.host.progress.size() - 1).contains("ABCD"));
        String body = java.net.URLDecoder.decode(f.transport.calls.get(0).body, StandardCharsets.UTF_8);
        assertTrue(body.contains(AmbientApi.SCOPE));
        assertTrue(body.contains("\"requestId\":\"" + f.host.saved.get("requestId")));
        assertEquals(4, UUID.fromString((String) f.host.saved.get("requestId")).version());
        f.session.tick();
        assertEquals(1, f.transport.calls.size());
        f.now += 5000;
        f.transport.reply(400, "{\"error\":\"authorization_pending\"}");
        f.session.tick();
        f.now += 5000;
        f.transport.reply(400, "{\"error\":\"slow_down\"}");
        f.session.tick();
        f.now += 5000;
        f.session.tick();
        assertEquals(3, f.transport.calls.size());
        f.now += 5000;
        f.transport.reply(200, TOKENS);
        f.session.tick();
        assertEquals("refresh", f.host.saved.get("refreshToken"));
        f.now++;
        f.transport.reply(200, "{\"id\":\"new-device\"}");
        f.transport.reply(200, DEVICE);
        f.transport.reply(200, PHOTOS);
        f.transport.reply(200, "image");
        f.session.tick();
        assertTrue(f.transport.calls.get(4).url.contains("devices?requestId=" + f.host.saved.get("requestId")));
        assertEquals("new-device", f.host.saved.get("deviceId"));
        assertTrue(f.host.progress.stream().anyMatch(p -> p.startsWith("pair:success:")));
        assertEquals(1, f.display.rendered);
    }

    @Test void waitsForSourcesUsingServerIntervalAndKeepsRunAlive() {
        Fixture f = new Fixture(false);
        f.transport.reply(200, CODE); f.session.connect("pair");
        f.now += 5000; f.transport.reply(200, TOKENS); f.session.tick();
        f.transport.reply(200, "{\"id\":\"device\"}");
        f.transport.reply(200, "{\"settingsUri\":\"https://photos.google.com/settings\","
                + "\"pollingConfig\":{\"pollInterval\":\"120s\"}}");
        f.session.tick();
        int calls = f.transport.calls.size(), reports = f.host.progress.size();
        f.now += 20000; f.session.tick();
        assertEquals(calls, f.transport.calls.size());
        assertTrue(f.host.progress.size() > reports);
        assertTrue(f.host.progress.get(f.host.progress.size() - 1).contains("photos.google.com/settings"));
    }

    @Test void canceledAndExpiredSignInsDoNotPoll() {
        Fixture f = new Fixture(false);
        f.transport.reply(200, CODE); f.session.connect("pair");
        f.session.cancel("pair");
        f.now += 6000; f.session.tick();
        assertEquals(1, f.transport.calls.size());
        f.transport.reply(200, CODE); f.session.connect("pair2");
        f.now += 300000; f.session.tick();
        assertEquals(2, f.transport.calls.size());
        assertTrue(f.host.progress.stream().anyMatch(p -> p.contains("expired")));
    }

    @Test void hostCancellationBeforeAuthorizationPreventsNetworkWork() {
        Fixture f = new Fixture(false);
        f.host.wanted = false;
        f.session.connect("canceled");
        assertTrue(f.transport.calls.isEmpty());
    }

    @Test void hiddenScreensaverDoesNotContactGoogleAndResumesImmediately() {
        Fixture f = new Fixture(true);
        f.display.visible = false; f.session.tick();
        assertTrue(f.transport.calls.isEmpty());
        f.display.visible = true; f.ready();
        f.display.visible = false; f.session.tick();
        f.now += 5000;
        f.display.visible = true;
        f.transport.reply(200, "image");
        f.session.tick();
        assertEquals(2, f.display.rendered);
    }

    @Test void slideshowHonorsIntervalAndCuratedBatchRefreshBudget() {
        Fixture f = new Fixture(true);
        f.ready();
        f.now += 29000;
        f.transport.reply(200, DEVICE); f.session.tick();
        assertEquals(1, f.display.rendered);
        f.now += 1000; f.transport.reply(200, "image"); f.session.tick();
        assertEquals(2, f.display.rendered);
        for (int i = 1; i < 20; i++) {
            f.now = 100000 + i * 60000L;
            f.transport.reply(200, DEVICE);
            f.transport.reply(200, "image");
            f.session.tick();
        }
        assertEquals(1, f.transport.calls.stream().filter(c -> c.url.contains("mediaItems?")).count());
        f.now = 100000 + 20 * 60000L;
        f.transport.reply(200, DEVICE); f.transport.reply(200, PHOTOS); f.transport.reply(200, "image");
        f.session.tick();
        assertEquals(2, f.transport.calls.stream().filter(c -> c.url.contains("mediaItems?")).count());
    }

    @Test void revokedSourcesImmediatelyClearPhotosAndRecoverWithFreshList() {
        Fixture f = new Fixture(true);
        f.ready(); f.now += 10000;
        f.transport.reply(200, "{\"mediaSourcesSet\":false}"); f.session.tick();
        assertTrue(f.display.message.contains("Select photos"));
        f.now += 10000;
        f.transport.reply(200, DEVICE); f.transport.reply(200, PHOTOS); f.transport.reply(200, "image");
        f.session.tick();
        assertEquals(2, f.display.rendered);
    }

    @Test void refreshFailureIsReportedWithoutLeakingCredentialsAndBacksOff() {
        Fixture f = new Fixture(true);
        f.transport.reply(400, "{\"error\":\"invalid_grant\",\"error_description\":\"secret refresh access\"}");
        f.session.tick();
        assertTrue(f.host.error);
        assertTrue(f.display.message.contains("authorization failed"));
        assertFalse(f.host.status.contains("secret"));
        assertEquals("While refreshing OAuth token: Google request failed (HTTP 400, invalid_grant).",
                f.host.status);
        f.now += 59000; f.session.tick();
        assertEquals(1, f.transport.calls.size());
    }

    @Test void missingDeviceDoesNotSilentlyCreateAnotherDevice() {
        Fixture f = new Fixture(true);
        f.transport.reply(200, TOKENS);
        f.transport.reply(404, "{\"error\":{\"status\":\"NOT_FOUND\"}}");
        f.session.tick();
        assertTrue(f.host.error);
        assertTrue(f.display.message.contains("not found"));
        assertFalse(f.transport.calls.stream().anyMatch(c -> c.method.equals("POST") && c.url.contains("/devices")));
    }

    @Test void disconnectDeletesBeforeClearingAndForgetWorksWithRevokedCredentials() {
        Fixture f = new Fixture(true);
        f.transport.reply(200, TOKENS); f.transport.reply(200, "{}");
        f.session.disconnect("disconnect");
        assertEquals("DELETE", f.transport.calls.get(1).method);
        assertEquals(AmbientApi.ROOT + "devices/device", f.transport.calls.get(1).url);
        assertEquals("", f.host.saved.get("refreshToken"));
        assertEquals("", f.host.saved.get("deviceId"));
        Fixture broken = new Fixture(true);
        broken.transport.reply(400, "{\"error\":\"invalid_grant\"}");
        broken.session.disconnect("disconnect");
        assertEquals(0, broken.host.saves);
        broken.session.forget("forget");
        assertEquals("", broken.host.saved.get("refreshToken"));
        assertEquals(1, broken.transport.calls.size());
    }

    @Test void deviceCreationPermissionReasonReachesStatusProgressAndLogsWithoutPrivateData() {
        Fixture f = new Fixture(false);
        f.transport.reply(200, CODE);
        f.session.connect("pair");
        f.now += 5000;
        f.transport.reply(200, TOKENS);
        f.session.tick();
        f.transport.reply(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"private account\","
                + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\","
                + "\"domain\":\"googleapis.com\",\"reason\":\"SERVICE_DISABLED\","
                + "\"metadata\":{\"consumer\":\"private project\",\"token\":\"private token\"}}]}}");
        f.session.tick();
        String expected = "While creating Ambient device: Google request failed "
                + "(HTTP 403, PERMISSION_DENIED, reason: SERVICE_DISABLED)."
                + "\nAmbient permission: not reported in token response.";
        assertTrue(f.host.error);
        assertEquals(expected, f.host.status);
        assertEquals(expected, f.host.logs.get(f.host.logs.size() - 1));
        assertEquals("pair:error:" + expected, f.host.progress.get(f.host.progress.size() - 1));
        assertTrue(f.host.logs.contains("Operation failed: creating Ambient device; retry backoff 60 seconds."));
        assertEquals("", f.host.saved.get("deviceId"));
        int calls = f.transport.calls.size();
        f.now += 59000;
        f.session.tick();
        assertEquals(calls, f.transport.calls.size(), "Permission errors retain the existing retry backoff");
    }

    @Test void logsSetupStagesWithoutCodesDeviceNamesOrSettingsUrls() {
        Fixture f = new Fixture(false);
        Map<String, Object> settings = settings();
        settings.put("clientId", "private-client-id");
        settings.put("clientSecret", "private-client-secret");
        settings.put("displayName", "private-device-name");
        f.session.configure(settings);
        f.transport.reply(200, CODE);
        f.session.connect("private-run-id");
        f.now += 5000;
        f.transport.reply(200, TOKENS);
        f.session.tick();
        f.transport.reply(200, "{\"id\":\"private-device-id\"}");
        f.transport.reply(200, "{\"settingsUri\":\"https://photos.google.com/private-settings\"}");
        f.session.tick();
        assertTrue(f.host.logs.contains("Requesting Google sign-in code."));
        assertTrue(f.host.logs.contains("Sign-in code received; waiting for browser authorization."));
        assertTrue(f.host.logs.contains("Google authorization completed; tokens saved."));
        assertTrue(f.host.logs.contains("Creating Ambient device."));
        assertTrue(f.host.logs.contains("Ambient device created."));
        assertTrue(f.host.logs.contains("Waiting for photo source selection."));
        String logs = String.join("\n", f.host.logs);
        for (String sensitive : new String[] {"private-", "ABCD", "https://", "\"access\"", "\"refresh\""}) {
            assertFalse(logs.contains(sensitive), "Logs must omit " + sensitive);
        }
        int count = f.host.logs.size();
        f.now += 10000;
        f.transport.reply(200, "{\"settingsUri\":\"https://photos.google.com/private-settings\"}");
        f.session.tick();
        assertEquals(count, f.host.logs.size(), "Unchanged source polling must not spam logs");
    }

    @Test void signInDetailsRemainInTopStatusAcrossProgressHeartbeatsAndClearOnCancellation() {
        Fixture f = new Fixture(false);
        f.transport.reply(200, CODE);
        f.session.connect("pair");
        String details = f.host.status;
        assertEquals("Open https://google.com/device\nCode: ABCD\nThen select photos in Google Photos.", details);
        for (int i = 0; i < 3; i++) {
            f.now += 20000;
            f.transport.reply(400, "{\"error\":\"authorization_pending\"}");
            f.session.tick();
            assertEquals(details, f.host.status);
            assertTrue(f.host.progress.get(f.host.progress.size() - 1).contains("status message above"));
        }
        assertTrue(f.host.progress.stream().noneMatch(p -> p.contains("ABCD") || p.contains("https://")));
        assertTrue(f.host.logs.stream().noneMatch(p -> p.contains("ABCD") || p.contains("https://")));
        f.session.cancel("pair");
        f.session.tick();
        assertEquals("Google Photos setup canceled.", f.host.status);
    }

    @Test void signInDetailsClearOnAuthorizationAndExpiry() {
        Fixture authorized = new Fixture(false);
        authorized.transport.reply(200, CODE);
        authorized.session.connect("pair");
        authorized.now += 5000;
        authorized.transport.reply(200, TOKENS);
        authorized.session.tick();
        assertEquals("Signed in. Waiting for photo source selection...", authorized.host.status);

        Fixture expired = new Fixture(false);
        expired.transport.reply(200, CODE);
        expired.session.connect("pair");
        expired.now += 300000;
        expired.session.tick();
        assertEquals("Google sign-in expired. Press Sign in to try again.", expired.host.status);
    }

    @Test void logsAreBoundedDuringIdleTicksSlidesAndPendingAuthorization() {
        Fixture slideshow = new Fixture(true);
        slideshow.ready();
        assertTrue(slideshow.host.logs.contains("Photo batch received: 1 displayable images; next refresh in 20 minutes."));
        int count = slideshow.host.logs.size();
        for (int i = 0; i < 10000; i++) slideshow.session.tick();
        assertEquals(count, slideshow.host.logs.size());
        slideshow.display.hasPhoto = false;
        slideshow.transport.reply(200, "image");
        slideshow.session.tick();
        assertEquals(count, slideshow.host.logs.size(), "Individual slides are not logged");
        slideshow.display.visible = false;
        slideshow.session.tick();
        assertEquals(count + 1, slideshow.host.logs.size());
        for (int i = 0; i < 10000; i++) slideshow.session.tick();
        assertEquals(count + 1, slideshow.host.logs.size());

        Fixture pending = new Fixture(false);
        pending.transport.reply(200, CODE);
        pending.session.connect("pair");
        count = pending.host.logs.size();
        for (int i = 0; i < 50; i++) {
            pending.now += 5000;
            pending.transport.reply(400, "{\"error\":\"authorization_pending\"}");
            pending.session.tick();
        }
        assertEquals(count, pending.host.logs.size(), "Expected authorization-pending responses are not logged");
        pending.session.close();
        pending.session.tick();
        assertEquals(count, pending.host.logs.size(), "No host logging after stop");
    }

    @Test void changedClientIsBlockedButSlideshowSettingsDoNotDiscardConnection() {
        Fixture f = new Fixture(true);
        Map<String, Object> config = settings();
        config.put("slideSeconds", 60); config.put("fit", "Fill");
        f.session.configure(config); f.ready();
        config.put("clientId", "other-client");
        f.session.configure(config); f.session.tick();
        assertEquals(4, f.transport.calls.size());
        assertTrue(f.host.error);
        assertTrue(f.display.message.contains("Restore"));
    }

    @Test void invalidSettingsDoNotStartNetworkWork() {
        Fixture f = new Fixture(true);
        Map<String, Object> invalid = settings();
        invalid.put("slideSeconds", 11);
        f.session.configure(invalid); f.session.tick();
        assertTrue(f.host.error); assertTrue(f.transport.calls.isEmpty());
    }

    @Test void repeatedStopAndLateResponseCannotPublishOrPersist() throws Exception {
        for (int i = 0; i < 100; i++) {
            Fixture f = new Fixture(true);
            f.session.close();
            f.session.tick();
            assertTrue(f.transport.closed);
            assertTrue(f.display.closed);
            assertTrue(f.transport.calls.isEmpty());
        }
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Transport blocking = new Transport() {
            @Override public AmbientApi.Response request(String method, String url, String bearer,
                    String type, byte[] body, int limit) throws IOException {
                entered.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                return new AmbientApi.Response(200, TOKENS.getBytes(StandardCharsets.UTF_8));
            }
        };
        Fixture f = new Fixture(true, blocking);
        Thread thread = new Thread(f.session::tick);
        thread.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        f.session.close();
        int saves = f.host.saves, reports = f.host.logs.size();
        release.countDown(); thread.join(3000);
        assertFalse(thread.isAlive());
        assertEquals(saves, f.host.saves);
        assertEquals(reports, f.host.logs.size());
        assertEquals(0, f.display.rendered);
    }

    @Test void pollingDurationSupportsFractionalSeconds() throws Exception {
        assertEquals(3500, AmbientSession.pollInterval("3.5s"));
        assertEquals(1, AmbientSession.pollInterval("0.000000001s"));
        assertThrows(IOException.class, () -> AmbientSession.pollInterval("-1s"));
        assertThrows(IOException.class, () -> AmbientSession.pollInterval("Infinitys"));
    }

    @Test void recreatedNativeSurfaceGetsPhotoWithoutWaitingForSlideDeadline() {
        Fixture f = new Fixture(true); f.ready();
        f.display.hasPhoto = false;
        f.now++;
        f.transport.reply(200, "image"); f.session.tick();
        assertEquals(2, f.display.rendered);
    }

    @Test void quotaResponsesBackOffForAtLeastFifteenMinutes() {
        Fixture f = new Fixture(true);
        f.transport.reply(200, TOKENS);
        f.transport.reply(200, DEVICE);
        f.transport.reply(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");
        f.session.tick();
        f.now += 14 * 60000; f.session.tick();
        assertEquals(3, f.transport.calls.size());
        f.now += 60000;
        f.transport.reply(200, DEVICE); f.transport.reply(200, PHOTOS); f.transport.reply(200, "image");
        f.session.tick();
        assertEquals(1, f.display.rendered);
    }

    @Test void disconnectAndPairAgainDoesNotKeepPreviousBatchDeadline() {
        Fixture f = new Fixture(true); f.ready();
        f.transport.reply(200, "{}"); f.session.disconnect("disconnect");
        f.transport.reply(200, CODE); f.session.connect("pair");
        f.now += 5000; f.transport.reply(200, TOKENS); f.session.tick();
        f.transport.reply(200, "{\"id\":\"second-device\"}");
        f.transport.reply(200, DEVICE); f.transport.reply(200, PHOTOS); f.transport.reply(200, "image");
        f.session.tick();
        assertEquals(2, f.display.rendered);
        assertEquals("second-device", f.host.saved.get("deviceId"));
    }

    @Test void changedSourcesBetweenDeviceCheckAndListClearPreviousPhoto() {
        Fixture f = new Fixture(true); f.ready();
        f.now += 20 * 60000;
        f.transport.reply(200, DEVICE);
        f.transport.reply(400, "{\"error\":{\"status\":\"FAILED_PRECONDITION\"}}");
        f.session.tick();
        assertFalse(f.display.hasPhoto);
        assertTrue(f.display.message.contains("Select photo sources"));
    }

    @Test void expiredPhotoUrlRefreshesBatchInsteadOfReportingMissingDevice() {
        Fixture f = new Fixture(true); f.ready();
        f.now += 30000;
        f.transport.reply(200, DEVICE); f.transport.reply(404, "");
        f.session.tick();
        assertTrue(f.display.message.contains("Photo unavailable"));
        assertFalse(f.display.hasPhoto);
        f.now += 60000;
        f.transport.reply(200, DEVICE); f.transport.reply(200, PHOTOS); f.transport.reply(200, "image");
        f.session.tick();
        assertEquals(2, f.display.rendered);
    }

    @Test void refreshesExpiringAccessTokenAndPersistsRefreshTokenRotation() {
        Fixture f = new Fixture(true); f.ready();
        f.now += 3550000;
        f.transport.reply(200, "{\"access_token\":\"next-access\",\"refresh_token\":\"rotated\",\"expires_in\":3600}");
        f.transport.reply(200, DEVICE); f.transport.reply(200, PHOTOS); f.transport.reply(200, "image");
        f.session.tick();
        assertEquals("rotated", f.host.saved.get("refreshToken"));
        assertEquals("next-access", f.transport.calls.get(5).bearer);
    }

    @Test void ambiguousCreateFailureKeepsRequestIdForExplicitOrphanCleanup() {
        Fixture f = new Fixture(false);
        f.transport.reply(200, CODE); f.session.connect("pair");
        f.now += 5000; f.transport.reply(200, TOKENS); f.session.tick();
        String requestId = (String) f.host.saved.get("requestId");
        f.transport.reply(409, "{\"error\":{\"status\":\"ALREADY_EXISTS\"}}");
        f.session.tick();
        assertTrue(f.host.error);
        assertEquals(requestId, f.host.saved.get("requestId"));
        assertEquals("", f.host.saved.get("deviceId"));
        f.transport.reply(200, "{}"); f.session.disconnect("cleanup");
        Call cleanup = f.transport.calls.get(f.transport.calls.size() - 1);
        assertEquals("DELETE", cleanup.method);
        assertEquals(AmbientApi.ROOT + "devices/" + requestId, cleanup.url);
        assertEquals("", f.host.saved.get("refreshToken"));
    }
}
