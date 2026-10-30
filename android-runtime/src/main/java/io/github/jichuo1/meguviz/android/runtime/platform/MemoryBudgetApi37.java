package io.github.jichuo1.meguviz.android.runtime.platform;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.MemoryBudgetManager;
import android.content.Context;
import android.os.Looper;
import java.util.function.LongConsumer;

/** API 37.2 isolation; the public factory checks the complete minor version before loading this class. */
@TargetApi(37)
@SuppressLint("NewApi")
final class MemoryBudgetApi37 extends MemoryBudgetObserver {
    private final MemoryBudgetManager manager;
    private final MemoryBudgetManager.OnOverBudgetListener listener;
    private boolean closed;

    private MemoryBudgetApi37(Context context, Looper looper, LongConsumer callback) {
        super(looper);
        manager = context.getSystemService(MemoryBudgetManager.class);
        if (manager == null) throw new IllegalStateException("MemoryBudgetManager unavailable");
        listener = value -> { if (!closed) callback.accept(value); };
        manager.registerProcessOverBudgetListener(looper, listener);
    }

    static MemoryBudgetApi37 observe(Context context, Looper looper, LongConsumer callback) {
        return new MemoryBudgetApi37(context, looper, callback);
    }

    @Override public Snapshot snapshot() {
        checkOwner();
        if (closed) throw new IllegalStateException("Observer is closed");
        return new Snapshot(manager.getProcessBudgetBytes(), manager.getProcessCurrentUsageBytes(),
            manager.getPackageBudgetBytes(), manager.getPackageCurrentUsageBytes());
    }

    @Override public void close() {
        checkOwner();
        if (closed) return;
        closed = true;
        manager.unregisterProcessOverBudgetListener(listener);
    }
}
