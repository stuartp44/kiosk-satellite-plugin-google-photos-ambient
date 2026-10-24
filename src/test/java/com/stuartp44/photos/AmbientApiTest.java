package com.stuartp44.photos;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AmbientApiTest {
    @Test void unrecognizedDiagnosticIdentifiersNeverReachExceptionMessages() {
            for (String reason : new String[] {"SYNTHETIC_REASON", "https://private.example",
                    "private\\ntext", String.join("", java.util.Collections.nCopies(121, "A"))}) {
                AmbientSessionTest.Transport transport = new AmbientSessionTest.Transport();
                AmbientApi api = new AmbientApi(transport);
                transport.reply(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"details\":["
                        + "{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\","
                        + "\"reason\":\"" + reason + "\",\"domain\":\"synthetic.example\"}]}}");
                AmbientApi.ApiException error = assertThrows(AmbientApi.ApiException.class,
                        () -> api.device("access", "device"));
                assertFalse(error.getMessage().contains("synthetic.example"));
                assertFalse(error.getMessage().contains("SYNTHETIC_REASON"));
        }
    }

    @Test void restrictsPhotoDestinationsAndResolution() throws Exception {
        AmbientSessionTest.Transport transport = new AmbientSessionTest.Transport();
        AmbientApi api = new AmbientApi(transport);
        for (String url : new String[] {"http://lh3.googleusercontent.com/x", "https://evil.example/x",
                "https://googleusercontent.com.evil.example/x", "https://googleusercontent.com:444/x",
                "https://user@googleusercontent.com/x", "https://googleusercontent.com/x?q=1"}) {
            assertThrows(IOException.class, () -> api.photo(url, 1920, 1080));
        }
        assertTrue(transport.calls.isEmpty());
        transport.reply(200, "image");
        api.photo("https://lh3.googleusercontent.com/x", Integer.MAX_VALUE, 0);
        assertEquals("https://lh3.googleusercontent.com/x=w2048-h1", transport.calls.get(0).url);
    }

    @Test void filtersNonImagesAndBoundsCuratedBatchWithoutFollowingPagination() throws Exception {
        AmbientSessionTest.Transport transport = new AmbientSessionTest.Transport();
        AmbientApi api = new AmbientApi(transport);
        transport.reply(200, "{\"nextPageToken\":\"more\",\"mediaItems\":["
                + "{\"mediaFile\":{\"mimeType\":\"video/mp4\",\"baseUrl\":\"https://evil.example\"}},"
                + "{\"mediaFile\":{\"mimeType\":\"image/jpeg\",\"baseUrl\":\"https://lh3.googleusercontent.com/x\"}}]}");
        List<String> photos = api.photos("access", "device /?");
        assertEquals(1, photos.size());
        assertTrue(transport.calls.get(0).url.contains("deviceId=device%20%2F%3F"));
        assertEquals(1, transport.calls.size());
        transport.reply(200, "{}");
        assertTrue(api.photos("access", "device").isEmpty());
    }

    @Test void orphanDeletionUsesRequestIdInPathAndAlreadyMissingIsSuccess() throws Exception {
        AmbientSessionTest.Transport transport = new AmbientSessionTest.Transport();
        transport.reply(404, "{\"error\":{\"status\":\"NOT_FOUND\"}}");
        new AmbientApi(transport).delete("access", "", "request-id");
        assertEquals(AmbientApi.ROOT + "devices/request-id", transport.calls.get(0).url);
        assertEquals("", transport.calls.get(0).body);
    }

    @Test void errorMessagesNeverReflectGoogleErrorDescriptionsOrUrls() {
        AmbientSessionTest.Transport transport = new AmbientSessionTest.Transport();
        transport.reply(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"private credentials\"}}");
        AmbientApi.ApiException error = assertThrows(AmbientApi.ApiException.class,
                () -> new AmbientApi(transport).device("sensitive-access-token", "private-device"));
        assertEquals("Google request failed (HTTP 403, PERMISSION_DENIED, reason: not supplied).", error.getMessage());
    }

    @Test void nonJsonFailurePreservesHttpStatusForAuthorizationHandling() {
        AmbientSessionTest.Transport transport = new AmbientSessionTest.Transport();
        transport.reply(403, "<html>Forbidden</html>");
        AmbientApi.ApiException error = assertThrows(AmbientApi.ApiException.class,
                () -> new AmbientApi(transport).device("access", "device"));
        assertEquals(403, error.status);
        assertEquals("invalid_error_response", error.code);
    }

    @Test void reportsAllowlistedGoogleReasonsWithoutMessagesOrMetadata() {
        for (String reason : new String[] {"ACCESS_TOKEN_SCOPE_INSUFFICIENT", "ACCESS_TOKEN_EXPIRED",
                "ACCESS_TOKEN_TYPE_UNSUPPORTED", "SERVICE_DISABLED", "BILLING_DISABLED", "CONSUMER_INVALID",
                "USER_PROJECT_DENIED", "IAM_PERMISSION_DENIED", "SECURITY_POLICY_VIOLATED",
                "ORG_RESTRICTION_VIOLATION"}) {
            for (String domain : new String[] {"googleapis.com", "photosambient.googleapis.com"}) {
                AmbientSessionTest.Transport transport = new AmbientSessionTest.Transport();
                transport.reply(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"private message\","
                        + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\","
                        + "\"domain\":\"" + domain + "\",\"reason\":\"" + reason + "\","
                        + "\"metadata\":{\"token\":\"private token\",\"consumer\":\"private project\"}},"
                        + "{\"@type\":\"type.googleapis.com/google.rpc.LocalizedMessage\","
                        + "\"message\":\"private account https://private.example\"}]}}");
                AmbientApi.ApiException error = assertThrows(AmbientApi.ApiException.class,
                        () -> new AmbientApi(transport).create("private-access", "private-request", "private-name"));
                assertEquals(403, error.status);
                assertEquals("PERMISSION_DENIED", error.code);
                assertEquals("Google request failed (HTTP 403, PERMISSION_DENIED, reason: " + reason + ").",
                        error.getMessage());
            }
        }
    }

    @Test void ignoresUnknownReasonsUntrustedDetailsAndMalformedDetailShapes() {
        for (String details : new String[] {"null", "{}", "\"private text\"", "[null,42,\"private text\"]",
                "[{\"@type\":\"other.Type\",\"domain\":\"googleapis.com\",\"reason\":\"SERVICE_DISABLED\"}]"}) {
            AmbientSessionTest.Transport transport = new AmbientSessionTest.Transport();
            transport.reply(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"details\":" + details + "}}");
            AmbientApi.ApiException error = assertThrows(AmbientApi.ApiException.class,
                    () -> new AmbientApi(transport).device("access", "device"));
            assertEquals("Google request failed (HTTP 403, PERMISSION_DENIED, reason: not supplied).", error.getMessage());
        }
    }

    @Test void reportsWithheldReasonsWithoutReflectingTheirValues() {
        for (String details : new String[] {
                "[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"domain\":\"googleapis.com\","
                        + "\"reason\":\"PRIVATE_TOKEN\"}]",
                "[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"domain\":\"googleapis.com\","
                        + "\"reason\":\"SERVICE_DISABLED\\nprivate token\"}]",
                "[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"domain\":\"evil.example\","
                        + "\"reason\":\"SERVICE_DISABLED\"}]",
                "[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"SERVICE_DISABLED\"}]"}) {
            AmbientSessionTest.Transport transport = new AmbientSessionTest.Transport();
            transport.reply(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"details\":" + details + "}}");
            AmbientApi.ApiException error = assertThrows(AmbientApi.ApiException.class,
                    () -> new AmbientApi(transport).device("access", "device"));
            assertEquals("Google request failed (HTTP 403, PERMISSION_DENIED, "
                    + "reason: withheld (unrecognized reason or domain)).", error.getMessage());
        }
    }

    @Test void findsKnownReasonAfterUnrecognizedDetails() {
        AmbientSessionTest.Transport transport = new AmbientSessionTest.Transport();
        transport.reply(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"details\":[null,"
                + "{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"domain\":\"googleapis.com\","
                + "\"reason\":\"PRIVATE_TOKEN\"},"
                + "{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"domain\":\"googleapis.com\","
                + "\"reason\":\"SERVICE_DISABLED\"}]}}");
        AmbientApi.ApiException error = assertThrows(AmbientApi.ApiException.class,
                () -> new AmbientApi(transport).device("access", "device"));
        assertEquals("Google request failed (HTTP 403, PERMISSION_DENIED, reason: SERVICE_DISABLED).",
                error.getMessage());
    }

    @Test void oversizedServerBatchRetainsAtMostOneHundredUrls() throws Exception {
        AmbientSessionTest.Transport transport = new AmbientSessionTest.Transport();
        String item = "{\"mediaFile\":{\"mimeType\":\"image/jpeg\","
                + "\"baseUrl\":\"https://lh3.googleusercontent.com/photo\"}}";
        transport.reply(200, "{\"mediaItems\":[" + String.join(",", java.util.Collections.nCopies(1000, item)) + "]}");
        assertEquals(100, new AmbientApi(transport).photos("access", "device").size());
    }
}
