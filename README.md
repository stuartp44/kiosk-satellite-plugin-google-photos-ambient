# Google Photos Ambient for Kiosk Satellite

> [!CAUTION]
> **DISCONTINUED - REFERENCE ONLY.**
> **NOT WORKING with ordinary, unapproved Google Cloud projects.**
> Google has confirmed in its [official documentation](https://developers.google.com/photos/partner-program/overview#ambient-api)
> that Ambient API access is restricted to accepted Google Photos partners.
> In our testing, OAuth sign-in succeeded and the required scope was granted,
> but device creation failed with **HTTP 403 `PERMISSION_DENIED`**.
> **Development has ended; no further work or support is planned.**
> This repository is retained for reference unless Google changes its access restrictions.
> This plugin has not been
> validated with an approved project. Enabling the API or adding OAuth test users
> does not remove this restriction.

This discontinued project attempted to show photos selected in Google Photos
on the Kiosk Satellite screensaver using the
[Google Photos Ambient API](https://developers.google.com/photos/ambient).
It is retained as an independent community reference, not a working product
or a Google product.

## Status: discontinued; retained for reference only

Google explicitly requires acceptance into its
[Google Photos partner program](https://developers.google.com/photos/partner-program/overview#ambient-api)
before granting Ambient API access. Enabling the API, adding OAuth test users,
and obtaining a token with the Ambient scope do not replace partner onboarding.
OAuth verification is a separate requirement.

Testing reached token authorization with the Ambient scope reported as granted,
but device creation returned HTTP 403 `PERMISSION_DENIED` and no device ID was
saved. The generic error does not itself identify the missing approval, but
partner access is a documented prerequisite. This integration cannot proceed
with an unapproved project. Redirect URIs, additional Photos APIs, repeated
sign-ins, and regenerated credentials do not satisfy that prerequisite.

The maintainer will not pursue partner access or continue development.
Unless Google changes its Ambient API access restrictions, this repository
will remain reference-only. A change in Google's policy would permit
reassessment, not guarantee that development will resume.
No working live slideshow has been demonstrated in this setup. The code and
deterministic tests are retained for reference, not as a supported integration
or a claim that the service works with ordinary developer projects.
Picker-based imports or local/network photo sources would be separate features,
not an Ambient workaround; Picker does not provide automatic album updates or
Google Memories selection.

### Closing down a test setup

- Use **Forget locally** to erase plugin-managed connection state when API
  deletion is denied; this does not remove any Google-side device.
- Clear the entered OAuth client ID and secret, then disable the plugin to
  stop background requests and release its in-memory state.
- Revoke this app's grant in your Google Account's third-party connections,
  if you no longer use it. This can affect other devices using the same client.
- Stop any sample server, remove local credential overrides, and clear the
  sample origin's cookies and local storage (or close its private-browser
  session). The official sample stores tokens in browser data.
- Remove any obsolete Ambient device manually in Google Photos if present.
  Review backups or exported settings separately; local cleanup cannot erase
  previously copied credentials. Rotate a client secret if it was exposed.

## Historical implementation notes

The requirements, setup, behavior, and build instructions below describe the
experimental implementation. They are preserved for reference, not as a
recommendation to install it or a promise of continued compatibility or support.

## Requirements (reference only)

- Android 7.0 / API 24 or later.
- A **recent Kiosk Satellite release supporting native plugin screensavers,
  settings buttons, secret settings, and `updateSettings`**. SDK `apiVersion: 1`
  alone does not guarantee these extensions. Update the kiosk if setup controls
  are missing or the plugin reports native screensavers are unavailable.
- Internet access and a Google Account with Google Photos.
- A Google Cloud project with the Ambient API enabled and an OAuth client of
  type **TVs and Limited Input devices**. Service accounts, API keys, Android
  client IDs, and ordinary web client IDs are not substitutes.

Google must allow your project to use the API and requested OAuth scope.
Public distribution requires Google's review/verification; this repository
does not provide credentials, approval, or a way around Google's access rules.
See [Google's configuration guide](https://developers.google.com/photos/ambient/guides/configure-your-app).

## Setup (reference only)

1. Enable the Ambient API in your Google Cloud project, configure its OAuth
   consent screen, and add your account as a test user where applicable.
2. Create a **TVs and Limited Input devices** OAuth client and copy its client
   ID and secret.
3. Install the plugin ZIP through **Plugin Manager > Developer Tools >
   Install from ZIP**, trust it, and enable it. The build instructions below
   describe how to produce a developer ZIP; no future release is planned.
4. In the plugin's **Google account** settings, enter the client ID, secret,
   and a device name (1-100 characters).
5. Press **Sign in**. Keep the settings page open. The Google verification URL
   and code appear in the plugin's top status/info message, separate from the
   periodically refreshed button progress. Open that URL on a phone/computer,
   enter the code, authorize the app,
   and select the photo sources in Google Photos. No credentials need to be
   entered on the screensaver. If Google does not take you to source selection,
   the setup progress shows the device's `settingsUri` after device creation.
   The sign-in URL/code stay unchanged while authorization is pending and are
   cleared on completion, cancellation, or expiry. Link clickability and text
   selection during host page refreshes depend on the Kiosk Satellite UI.
6. Under **Screensaver > Screensaver mode**, choose
   **Google Photos (Google Photos Ambient)**, or choose it in a screensaver
   schedule. Start the screensaver or wait for the normal idle timeout.

The plugin registers a mode; it does **not** select it or start the screensaver
automatically. Kiosk Satellite still owns wake triggers, touch dismissal,
brightness, schedules, stock widgets, At a Glance, and Now Playing layouts.

### Slideshow settings

| Setting | Default | Behavior |
| --- | --- | --- |
| Seconds per photo | 30 | 10-300 seconds, in steps of 5 |
| Photo sizing | Fit | Fit preserves the whole photo with black borders; Fill crops to fill the viewport |
| Name in Google Photos | Kiosk Satellite | Applied at device creation; disconnect and pair again to change it |

Google chooses the curated photos from the sources you selected. The plugin
requests one batch of up to 100 photos every 20 minutes of active use and cycles
that batch in the returned order. It intentionally does not exhaust pagination
or enumerate your full library. At continuous steady-state use this is about
72 media-list calls per day, below the documented 240-per-device daily limit.
Restarts, source changes, and retries can add calls. Quota errors back off for
at least 15 minutes; other repeated failures back off from 1 minute to 1 hour.
An empty batch retries at the next scheduled batch refresh.

Images are fetched in the Java worker, not in an HTML WebView (the host blocks
network requests there). Requests use the viewport dimensions capped at
2048 pixels on each axis. Downloads are limited to 12 MiB and decoded images to
approximately 4 megapixels. Only the current decoded photo and a bounded list
of URLs are retained; photos are not saved to disk. The previous photo may
remain visible during a temporary network error, but known authorization or
source-removal errors clear it. Dismissal releases displayed images. API
traffic pauses when the renderer is hidden, except while setup is in progress.
Videos/motion-photo video playback, offline albums, and transitions are not
implemented.

## Disconnecting and recovery

- **Disconnect** removes this Ambient device using Google's API, then erases
  its saved local connection. If deletion fails, the connection is retained
  for retry. An already missing device counts as successfully removed.
- **Forget locally** is explicitly confirmed recovery for revoked credentials,
  a lost OAuth client, or an unreachable service. It erases local credentials
  **without** deleting anything from Google. Remove the old device manually
  in Google Photos to avoid accumulating orphaned devices.
- Removing/disabling the plugin stops its worker and renderer, but does not
  delete a Google device. Disconnect before uninstalling when possible.
  Shutdown interrupts queued/running work, closes the active HTTP transport,
  and waits up to two seconds for the worker to exit. The host automatically
  removes the screensaver registration; the plugin does not call a revoked
  host during shutdown. A worker that fails to exit is reported as a shutdown
  failure rather than silently allowing replacement.
- A lost device ID can be deleted using the saved UUID request ID. An ambiguous
  create failure (`ALREADY_EXISTS`) is reported, never silently worked around
  by creating duplicates: disconnect and pair again.
- Resources belong to the original OAuth client. Restore that client and
  disconnect before changing it; alternatively forget locally and manually
  clean up the old Google device. The plugin never deletes another client's
  resources.

The plugin does not revoke the entire Google OAuth grant on disconnect, as
that can affect other devices using the same client. Remove the application's
access in your Google Account if you want to revoke the whole grant.

### Credentials and privacy

Refresh tokens, device IDs, request IDs, and the client binding are saved as
host **secret settings**; access tokens remain in memory. The fields under
**Saved connection** are plugin-managed and should not be edited.
The host masks secret settings in its UI/API; this is not a claim of
hardware-backed encryption. Plugins run as trusted code inside Kiosk Satellite.
Protect access to the kiosk, its backups, and Remote Admin (especially on HTTP).
Do not publish credentials or share/export saved settings. Do not fleet-sync
account/device settings between kiosks: pair each screen separately.
Error logging omits Google response descriptions, account data, photo URLs,
and tokens. OAuth tokens are never sent to photo download hosts.

### Diagnosing Google permission errors

After sign-in or a token refresh, the plugin checks the token response's `scope`
field and logs only whether the Ambient permission is **granted**, **missing
from reported scopes**, or **not reported**. The same summary is appended to
subsequent failure status and setup results. It never prints the token or raw
scope list, makes no additional network request, and does not change API access
or automatically reauthorize. An absent, empty, or non-string scope field is
reported as unknown, not as missing permission. The summary describes the latest
successful token response only, not an independent verification of the token.
To check an existing connection, install the updated plugin and start its
screensaver: the saved refresh token obtains a new access token automatically.
Do not clear the saved connection just to run this check.

The temporary raw reason/domain diagnostic switch has been removed. Unknown
identifiers remain withheld in every output, even if an older installation
still has that setting saved.

The host's plugin logs record session startup, credential-change resets,
sign-in stages, token refresh, device creation/deletion, source-selection
changes, screensaver visibility changes, and photo-batch counts. Failures
include the operation being attempted and retry-backoff duration, followed by
the sanitized error. Use these entries to distinguish, for example,
`creating Ambient device` from `reading Ambient device`.
Logs exclude OAuth client values, device/request/run identifiers, device names,
sign-in codes, verification/settings URLs, and photo URLs. Normal authorization
pending responses, unchanged source polls, individual slides, and idle ticks
are not logged. Shutdown does not log through the host because its API may
already be revoked.

Failed requests show the HTTP status and Google error code in plugin status,
setup progress, and error logs. When Google includes a recognized
`google.rpc.ErrorInfo` reason from a Google API domain, the message also includes
an allowlisted diagnostic constant, for example:

```text
Google request failed (HTTP 403, PERMISSION_DENIED, reason: SERVICE_DISABLED).
```

- `SERVICE_DISABLED`: check that `photosambient.googleapis.com` is enabled in
  the project containing the OAuth client used by the plugin.
- `ACCESS_TOKEN_SCOPE_INSUFFICIENT`: check the Ambient permission
  `https://www.googleapis.com/auth/photosambient.mediaitems` and authorize again.
- `IAM_PERMISSION_DENIED`, `USER_PROJECT_DENIED`, or policy-related reasons:
  investigate the project's permissions or organization restrictions.

Unknown reasons, raw Google messages, and error metadata are deliberately omitted
to avoid exposing credentials, project identifiers, or account information.
For HTTP 403 errors, `reason: not supplied` means the response contained no
nonempty structured `google.rpc.ErrorInfo` reason; `reason: withheld
(unrecognized reason or domain)` means a reason was present but was not safe
to display under the allowlist. Errors in status and setup progress include
the operation, such as `While creating Ambient device`, so copying the status
alone retains that diagnostic context.
A bare `PERMISSION_DENIED` therefore does not establish a specific cause or
prove that public app verification is required.

## Build and test

Use JDK 17 or newer (JDK 25 is used in CI), an Android SDK, and Android build
tools containing D8. The checked-in Gradle wrapper uses Gradle 9.1.

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" # adapt for your installation
# Install platforms;android-36 and build-tools;36.0.0 using SDK Manager if needed.
./gradlew --no-daemon build
```

Output:

- `dist/google-photos-ambient-0.1.0.zip` -- installable developer package
- `dist/kiosk-satellite-plugin.json`
- `dist/google-photos-ambient-0.1.0.zip.sha256`

`./gradlew test` runs deterministic API/session tests without contacting Google.
`./gradlew packagePlugin` runs tests and creates the ZIP. Release versions can
be supplied with `-PpluginVersion=0.1.1` without changing the source manifest.
The ZIP contains a DEX `plugin.jar`, manifest, and license, with no SDK classes,
credentials, external runtime dependencies, or directory entries.

The compile-only types under [sdk/](sdk/) are the subset of
[Community SDK 1](https://github.com/jxlarrea/kiosk-satellite-plugins)
used by this plugin, plus an unused theme type marker. The host supplies their
real implementations at runtime. Do not package these stubs into the plugin.

The [build workflow](.github/workflows/build.yml) tests and packages commits/PRs.
Publishing a GitHub release such as `v0.1.0` builds the tagged source and uploads
the ZIP, manifest, and checksum using `github-actions[bot]`, as required by
Kiosk Satellite's verified repository installer. No release is published merely
by building locally.

## Validation status

Unit tests cover OAuth pending/slow-down/expiry/cancellation, UUID state and
device persistence, source polling, token refresh, sizing URL construction,
batch cadence, error redaction/backoff, reconnect/disconnect/forget, hidden
renderers, 100 session shutdowns, and a response arriving after shutdown.
The Android code is compiled and DEX-packaged locally.

**Live Google authorization, native view rendering, actual image decoding,
rotation/Now Playing resizing, real socket shutdown, and extended operation
on a tablet still require device testing.** Unit tests use fake transports and
displays and do not prove live service access or leak-free Android operation.

## References

- [Ambient API overview](https://developers.google.com/photos/ambient/guides/get-started)
- [Device lifecycle](https://developers.google.com/photos/ambient/guides/devices)
- [Media listing and limits](https://developers.google.com/photos/ambient/reference/rest/v1/mediaItems/list)
- [Native plugin screensavers](https://github.com/jxlarrea/kiosk-satellite-plugins/blob/main/docs/screensavers.md)
