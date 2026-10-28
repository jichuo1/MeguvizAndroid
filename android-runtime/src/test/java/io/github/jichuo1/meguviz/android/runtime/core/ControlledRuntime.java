package io.github.jichuo1.meguviz.android.runtime.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

final class ControlledRuntime implements OwnerDispatcher, FrameDriver {
    private final Thread owner = Thread.currentThread();
    private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
    private FrameDriver.Callback frame;
    boolean rejectPost;
    boolean rejectFrame;
    int posts;
    int frameRequests;
    int frameRemovals;
    long nanos;
    final List<Throwable> errors = new ArrayList<>();

    @Override public boolean isOwnerThread() { return Thread.currentThread() == owner; }
    @Override public synchronized boolean post(Runnable callback) {
        posts++;
        if (rejectPost) return false;
        callbacks.addLast(callback); return true;
    }
    @Override public synchronized void remove(Runnable callback) { callbacks.removeIf(value -> value == callback); }
    synchronized int queuedSignals() { return callbacks.size(); }
    void pumpSignals() {
        checkOwner();
        while (true) {
            Runnable callback;
            synchronized (this) { callback = callbacks.pollFirst(); }
            if (callback == null) return;
            callback.run();
        }
    }
    @Override public boolean request(FrameDriver.Callback callback) {
        checkOwner(); frameRequests++;
        if (rejectFrame) return false;
        if (frame != null) throw new AssertionError("Duplicate platform frame");
        frame = callback; return true;
    }
    @Override public void remove(FrameDriver.Callback callback) {
        checkOwner();
        if (frame == callback) { frame = null; frameRemovals++; }
    }
    boolean hasFrame() { checkOwner(); return frame != null; }
    void frame() {
        checkOwner();
        FrameDriver.Callback pending = frame;
        if (pending == null) throw new AssertionError("No frame requested");
        frame = null; nanos += 16_666_667L; pending.onFrame(nanos);
    }
    void drain() {
        for (int i = 0; i < 1000; i++) {
            pumpSignals();
            if (!hasFrame()) return;
            frame();
        }
        throw new AssertionError("Unbounded frame loop");
    }
    LifecycleScope activeScope() {
        LifecycleScope scope = new LifecycleScope(this, errors::add);
        scope.setAttached(true); scope.setStarted(true); return scope;
    }
    FrameScheduler scheduler(int quota) { return new FrameScheduler(this, this, errors::add, quota); }
    static void background(Runnable callback) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> { try { callback.run(); } catch (Throwable error) { failure.set(error); } }, "controlled-producer");
        thread.start();
        try { thread.join(3000); } catch (InterruptedException error) { throw new AssertionError(error); }
        if (thread.isAlive()) throw new AssertionError("Producer deadlocked");
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
