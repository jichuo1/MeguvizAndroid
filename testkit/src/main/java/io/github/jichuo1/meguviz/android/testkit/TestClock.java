package io.github.jichuo1.meguviz.android.testkit;

/** Explicit monotonic clock; test progress never depends on wall-clock sleeping. */
public final class TestClock {
    private long nanos;
    public long nowNanos() { return nanos; }
    public long advanceNanos(long delta) {
        if (delta < 0 || nanos > Long.MAX_VALUE - delta) throw new IllegalArgumentException("Clock must remain monotonic");
        nanos += delta; return nanos;
    }
}
