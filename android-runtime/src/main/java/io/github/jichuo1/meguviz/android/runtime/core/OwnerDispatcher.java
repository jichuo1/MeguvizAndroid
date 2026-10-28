package io.github.jichuo1.meguviz.android.runtime.core;

/** The owning thread's non-blocking, removable event entry point. */
public interface OwnerDispatcher {
    boolean isOwnerThread();
    boolean post(Runnable callback);
    void remove(Runnable callback);

    default void checkOwner() {
        if (!isOwnerThread()) throw new IllegalStateException("Operation requires the owner thread");
    }
}
