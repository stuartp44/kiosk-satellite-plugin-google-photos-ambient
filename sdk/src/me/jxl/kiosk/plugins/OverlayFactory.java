// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins;

import android.content.Context;
import android.view.View;

public interface OverlayFactory {
    View create(Context context, KsTheme theme);
    default void onThemeChanged(View view, KsTheme theme) {}
    default void onDestroy(View view) {}
}
