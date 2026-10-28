package io.github.jichuo1.meguviz.android.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** A bounded executor whose caller explicitly chooses each completion position. */
public final class ControlledExecutor implements Executor {
    private final int capacity;
    private final List<Runnable> pending = new ArrayList<>();
    public ControlledExecutor(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Positive capacity required");
        this.capacity = capacity;
    }
    @Override public synchronized void execute(Runnable command) {
        Objects.requireNonNull(command);
        if (pending.size() >= capacity) throw new RejectedExecutionException("Controlled executor full");
        pending.add(command);
    }
    public synchronized int pendingCount() { return pending.size(); }
    public void runAt(int index) {
        Runnable command;
        synchronized (this) { command = pending.remove(index); }
        command.run();
    }
    public synchronized void cancelAll() { pending.clear(); }
}
