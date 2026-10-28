package io.github.jichuo1.meguviz.android.runtime.platform;

import android.os.Handler;
import android.os.Looper;
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;

/** A normal synchronous main Handler; it does not bypass traversal barriers. */
public final class MainThreadDispatcher implements OwnerDispatcher {
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Override public boolean isOwnerThread() { return Looper.myLooper() == handler.getLooper(); }
    @Override public boolean post(Runnable callback) { return handler.post(callback); }
    @Override public void remove(Runnable callback) { handler.removeCallbacks(callback); }
}
