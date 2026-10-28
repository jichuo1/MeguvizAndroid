package io.github.jichuo1.meguviz.android.runtime.core;

/** Copied frame values; no platform frame object survives a callback. */
public final class FrameTick {
    private final long sequence;
    private final long frameTimeNanos;
    private final Long deadlineNanos;
    private final Long expectedPresentationNanos;
    FrameTick(long sequence, long frameTimeNanos) { this(sequence, frameTimeNanos, null, null); }
    FrameTick(long sequence, long frameTimeNanos, Long deadlineNanos, Long expectedPresentationNanos) {
        this.sequence = sequence; this.frameTimeNanos = frameTimeNanos;
        this.deadlineNanos = deadlineNanos; this.expectedPresentationNanos = expectedPresentationNanos;
    }
    public long sequence() { return sequence; }
    public long frameTimeNanos() { return frameTimeNanos; }
    public Long deadlineNanos() { return deadlineNanos; }
    public Long expectedPresentationNanos() { return expectedPresentationNanos; }
    public String budgetSource() { return deadlineNanos == null ? "unknown" : "platform-vsync"; }
    /** Safe advisory budget with an explicit positive reservation for platform work, or null if unknown. */
    public Long workBudgetNanos(long nowNanos, long reserveNanos) {
        if (reserveNanos <= 0) throw new IllegalArgumentException("Positive platform reservation required");
        if (deadlineNanos == null) return null;
        if (deadlineNanos <= nowNanos) return 0L;
        long remaining;
        try { remaining = Math.subtractExact(deadlineNanos, nowNanos); }
        catch (ArithmeticException overflow) { remaining = Long.MAX_VALUE; }
        return remaining <= reserveNanos ? 0L : remaining - reserveNanos;
    }
}
