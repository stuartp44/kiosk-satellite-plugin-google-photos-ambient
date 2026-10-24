package com.stuartp44.photos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;

final class PhotoRenderer implements OverlayFactory, AmbientSession.Display {
    private final Set<PhotoView> views = new HashSet<>();
    private Bitmap bitmap;
    private String message = "Connect Google Photos in Plugin Manager.";
    private boolean fill;
    private boolean stopped;

    @Override public synchronized View create(Context context, KsTheme theme) {
        PhotoView view = new PhotoView(context);
        if (!stopped) { views.add(view); view.render(); }
        return view;
    }

    @Override public synchronized void onDestroy(View view) {
        PhotoView photo = (PhotoView) view;
        views.remove(photo);
        photo.image.setImageDrawable(null);
        if (views.isEmpty()) bitmap = null;
    }

    @Override public synchronized boolean visible() {
        for (PhotoView view : views) if (view.showing && view.width > 0 && view.height > 0) return true;
        return false;
    }

    @Override public synchronized int width() {
        int size = 1;
        for (PhotoView view : views) size = Math.max(size, view.width);
        return size;
    }

    @Override public synchronized int height() {
        int size = 1;
        for (PhotoView view : views) size = Math.max(size, view.height);
        return size;
    }

    @Override public synchronized boolean hasPhoto() { return bitmap != null; }

    @Override public synchronized void sizing(boolean fill) { this.fill = fill; redraw(); }

    @Override public synchronized void clear(String message) {
        bitmap = null;
        this.message = message;
        redraw();
    }

    @Override public void photo(byte[] bytes) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Google returned an invalid image");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while ((long) (bounds.outWidth / options.inSampleSize) * (bounds.outHeight / options.inSampleSize) > 4194304L) {
            options.inSampleSize *= 2;
        }
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) throw new IOException("Could not decode Google photo");
        synchronized (this) {
            if (stopped || views.isEmpty()) return;
            bitmap = decoded;
            message = "";
            redraw();
        }
    }

    private void redraw() {
        for (PhotoView view : views) {
            view.post(() -> {
                synchronized (PhotoRenderer.this) {
                    if (!stopped && views.contains(view)) view.render();
                }
            });
        }
    }

    @Override public synchronized void close() {
        stopped = true;
        bitmap = null;
        for (PhotoView view : views) view.post(() -> view.image.setImageDrawable(null));
        views.clear();
    }

    private final class PhotoView extends FrameLayout {
        final ImageView image;
        final TextView text;
        volatile boolean showing;
        volatile int width, height;

        PhotoView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            image = new ImageView(context);
            addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            text = new TextView(context);
            text.setTextColor(Color.WHITE);
            text.setTextSize(20);
            text.setGravity(Gravity.CENTER);
            int padding = (int) (24 * context.getResources().getDisplayMetrics().density);
            text.setPadding(padding, padding, padding, padding);
            addView(text, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        void render() {
            image.setScaleType(fill ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            image.setImageBitmap(bitmap);
            text.setText(message);
            text.setVisibility(bitmap == null ? View.VISIBLE : View.GONE);
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            showing = visibility == View.VISIBLE && isShown();
        }

        @Override protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            showing = getWindowVisibility() == View.VISIBLE && isShown();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            this.width = width;
            this.height = height;
        }

        @Override protected void onDetachedFromWindow() {
            showing = false;
            super.onDetachedFromWindow();
        }
    }
}
