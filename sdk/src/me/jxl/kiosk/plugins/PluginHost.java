// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins;

import java.util.Map;

/** Compile-only subset of Community SDK 1. Never included in plugin.jar. */
public interface PluginHost {
    default void publishScreensaverView(String key, String title, OverlayFactory factory) {
        throw new UnsupportedOperationException("Native screensavers are unavailable");
    }
    default void removeScreensaver(String key) { throw new UnsupportedOperationException(); }
    default void status(String message, boolean error) { throw new UnsupportedOperationException(); }
    void log(String message);
    default void updateSettings(Map<String, Object> values) { throw new UnsupportedOperationException(); }
    default boolean setupResult(String run, String state, String message) { throw new UnsupportedOperationException(); }
}
