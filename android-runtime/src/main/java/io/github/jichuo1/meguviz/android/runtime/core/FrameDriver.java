package io.github.jichuo1.meguviz.android.runtime.core;

/** Owner-thread, asynchronous one-shot frame source. */
public interface FrameDriver {
    @FunctionalInterface interface Callback {
        void onFrame(long frameTimeNanos);
        /** Numeric platform values copied during the source callback; legacy consumers may ignore them. */
        default void onFrame(long frameTimeNanos, long deadlineNanos, long expectedPresentationNanos) {
            onFrame(frameTimeNanos);
        }
    }
    boolean request(Callback callback);
    void remove(Callback callback);
}
