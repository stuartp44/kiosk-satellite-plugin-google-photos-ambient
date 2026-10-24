package com.stuartp44.photos;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import me.jxl.kiosk.plugins.PluginHost;
import org.junit.jupiter.api.Test;

class GooglePhotosPluginTest {
    static final class Host implements PluginHost {
        final CountDownLatch initialized = new CountDownLatch(1);
        volatile boolean revoked;
        int removals;

        private void requireActive() {
            if (revoked) throw new IllegalStateException("Plugin host has been revoked");
        }

        @Override public void status(String message, boolean error) {
            requireActive();
            initialized.countDown();
        }
        @Override public void log(String message) { requireActive(); }
        @Override public void updateSettings(Map<String, Object> values) { requireActive(); }
        @Override public boolean setupResult(String run, String state, String message) {
            requireActive();
            return true;
        }
        @Override public void removeScreensaver(String key) {
            removals++;
            requireActive();
        }
    }

    @Test void repeatedStopWithRevokedHostReleasesWorkerTransportAndDisplay() throws Exception {
        GooglePhotosPlugin plugin = new GooglePhotosPlugin();
        for (int i = 0; i < 100; i++) {
            Host host = new Host();
            AmbientSessionTest.Display display = new AmbientSessionTest.Display();
            AmbientSessionTest.Transport transport = new AmbientSessionTest.Transport();
            AtomicReference<Thread> worker = new AtomicReference<>();
            CountDownLatch initialized = new CountDownLatch(1);
            AmbientSession.Display trackingDisplay = new AmbientSession.Display() {
                @Override public boolean visible() { return false; }
                @Override public int width() { return 1; }
                @Override public int height() { return 1; }
                @Override public boolean hasPhoto() { return false; }
                @Override public void sizing(boolean fill) {
                    worker.set(Thread.currentThread());
                    initialized.countDown();
                }
                @Override public void clear(String message) { display.clear(message); }
                @Override public void photo(byte[] bytes) { fail("No photos expected"); }
                @Override public void close() { display.close(); }
            };
            plugin.startWorker(host, AmbientSessionTest.settings(), new AmbientApi(transport), trackingDisplay);
            try {
                assertTrue(initialized.await(3, TimeUnit.SECONDS));
                assertTrue(host.initialized.await(3, TimeUnit.SECONDS));
                host.revoked = true;
                plugin.stop();
                worker.get().join(1000);
                assertFalse(worker.get().isAlive(), "stop must await the actual executor thread");
                assertTrue(transport.closed);
                assertTrue(display.closed);
                assertTrue(transport.calls.isEmpty());
                assertEquals(0, host.removals, "Host owns removal after revocation");
                plugin.stop();
            } finally {
                plugin.stop();
            }
        }
    }

    @Test void stopClosesInFlightRequestAndWaitsForWorkerExit() throws Exception {
        Host host = new Host();
        CountDownLatch entered = new CountDownLatch(1), closed = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicBoolean exited = new AtomicBoolean();
        AmbientApi.Transport transport = new AmbientApi.Transport() {
            @Override public AmbientApi.Response request(String method, String url, String bearer,
                    String type, byte[] body, int limit) throws IOException {
                worker.set(Thread.currentThread());
                entered.countDown();
                try {
                    // Model I/O that needs close as well as interruption to be released.
                    while (closed.getCount() != 0) {
                        try {
                            if (!closed.await(3, TimeUnit.SECONDS)) throw new IOException("Close timed out");
                        } catch (InterruptedException interrupted) {
                            // Keep waiting until the transport is closed.
                        }
                    }
                    return new AmbientApi.Response(200,
                            AmbientSessionTest.CODE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } finally {
                    exited.set(true);
                }
            }
            @Override public void close() { closed.countDown(); }
        };
        AmbientSessionTest.Display display = new AmbientSessionTest.Display();
        GooglePhotosPlugin plugin = new GooglePhotosPlugin();
        plugin.startWorker(host, AmbientSessionTest.settings(), new AmbientApi(transport), display);
        try {
            assertTrue(host.initialized.await(3, TimeUnit.SECONDS));
            plugin.onEvent("settings.button", Map.of("run", "pair", "id", "connect"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            host.revoked = true;
            plugin.stop();
            assertTrue(exited.get());
            worker.get().join(1000);
            assertFalse(worker.get().isAlive());
            assertTrue(display.closed);
            assertEquals(0, host.removals);
            plugin.configure(AmbientSessionTest.settings());
            plugin.onEvent("settings.button", Map.of("run", "late", "id", "connect"));
        } finally {
            plugin.stop();
        }
    }
}
