package com.stuartp44.photos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

public final class GooglePhotosPlugin implements KioskPlugin {
    private ScheduledExecutorService worker;
    private AmbientSession session;
    private PluginHost host;

    @Override public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        PhotoRenderer renderer = new PhotoRenderer();
        try {
            host.publishScreensaverView("photos", "Google Photos", renderer);
        } catch (UnsupportedOperationException | LinkageError error) {
            renderer.close();
            host.status("Update Kiosk Satellite: native plugin screensavers are required.", true);
            return;
        }
        startWorker(host, settings, new AmbientApi(new AmbientApi.HttpTransport()), renderer);
    }

    void startWorker(PluginHost host, Map<String, Object> settings, AmbientApi api, AmbientSession.Display display) {
        this.host = host;
        long origin = System.nanoTime();
        AmbientSession current = new AmbientSession(api, host, display,
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - origin));
        session = current;
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "google-photos-ambient");
            thread.setDaemon(true);
            return thread;
        });
        Map<String, Object> snapshot = new LinkedHashMap<>(settings);
        worker.execute(() -> compatible(current, () -> current.initialize(snapshot)));
        worker.scheduleWithFixedDelay(() -> compatible(current, current::tick), 1, 1, TimeUnit.SECONDS);
    }

    @Override public void configure(Map<String, Object> settings) {
        if (worker == null) return;
        Map<String, Object> snapshot = new LinkedHashMap<>(settings);
        AmbientSession current = session;
        worker.execute(() -> compatible(current, () -> current.configure(snapshot)));
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        if (session == null || worker == null) return;
        Object run = payload.get("run");
        if (!(run instanceof String)) return;
        String id = (String) run;
        AmbientSession current = session;
        if ("settings.cancel".equals(event)) current.cancel(id);
        else if ("settings.button".equals(event)) {
            if ("connect".equals(payload.get("id"))) worker.execute(() -> compatible(current, () -> current.connect(id)));
            else if ("disconnect".equals(payload.get("id"))) worker.execute(() -> compatible(current, () -> current.disconnect(id)));
            else if ("forget".equals(payload.get("id"))) worker.execute(() -> compatible(current, () -> current.forget(id)));
        }
    }

    private void compatible(AmbientSession current, Runnable action) {
        try { action.run(); }
        catch (UnsupportedOperationException | LinkageError incompatibleHost) {
            current.incompatibleHost();
            ScheduledExecutorService executor = worker;
            if (executor != null) executor.shutdownNow();
        }
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        host.status("This plugin has no commands. Use its Google account settings buttons.", true);
    }

    @Override public void stop() throws InterruptedException {
        AmbientSession current = session;
        ScheduledExecutorService executor = worker;
        worker = null;
        session = null;
        host = null;
        if (executor != null) executor.shutdownNow();
        try {
            if (current != null) current.close();
        } finally {
            // The host revokes its API and removes renderers when the session ends.
            if (executor != null && !executor.awaitTermination(2, TimeUnit.SECONDS))
                throw new IllegalStateException("Google Photos worker did not stop within 2 seconds.");
        }
    }
}
