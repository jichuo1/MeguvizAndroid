package io.github.jichuo1.meguviz.android.runtime.platform;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import java.util.Objects;
import java.util.function.LongConsumer;

/** Observation-only API 37.2 memory-budget bridge. It never chooses or changes a process/package budget. */
public abstract class MemoryBudgetObserver implements AutoCloseable {
    private final Looper looper;

    MemoryBudgetObserver(Looper looper) { this.looper = looper; }

    /**
     * Registers a process listener on [looper], or returns null before API 37.2. The callback receives the
     * raw platform signal; use [snapshot] for explicitly named budget/usage values. No thread is created.
     * Callback work must be short, idempotent and limited to resources owned by the caller.
     */
    public static MemoryBudgetObserver observeProcess(Context context, Looper looper, LongConsumer callback) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(looper, "looper");
        Objects.requireNonNull(callback, "callback");
        if (!isSupported()) return null;
        return MemoryBudgetApi37.observe(context, looper, callback);
    }

    private static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 36 && versionSupported(Build.VERSION.SDK_INT, Build.VERSION.SDK_INT_FULL);
    }

    static boolean versionSupported(int sdk, int sdkFull) { return sdk >= 37 && sdkFull >= 3_700_002; }

    /** Reads process and package values independently; neither includes native graphics/DMA-BUF ownership. */
    public abstract Snapshot snapshot();

    /** Unregisters on the supplied Looper. Repeated calls are harmless. */
    @Override public abstract void close();

    final void checkOwner() {
        if (Looper.myLooper() != looper) throw new IllegalStateException("Use the observer Looper");
    }

    public static final class Snapshot {
        public final long processBudgetBytes, processUsageBytes, packageBudgetBytes, packageUsageBytes;

        Snapshot(long processBudgetBytes, long processUsageBytes, long packageBudgetBytes, long packageUsageBytes) {
            this.processBudgetBytes = processBudgetBytes;
            this.processUsageBytes = processUsageBytes;
            this.packageBudgetBytes = packageBudgetBytes;
            this.packageUsageBytes = packageUsageBytes;
        }
    }
}
