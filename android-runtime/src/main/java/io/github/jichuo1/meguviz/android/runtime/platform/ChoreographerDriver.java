package io.github.jichuo1.meguviz.android.runtime.platform;

import android.view.Choreographer;
import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver;
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;
import java.util.Objects;

/** One public FrameCallback per pending request, registered only while dirty work exists. */
public final class ChoreographerDriver implements FrameDriver {
    interface Queue {
        void post(Choreographer.FrameCallback callback);
        void remove(Choreographer.FrameCallback callback);
    }
    private final OwnerDispatcher owner;
    private final Queue queue;
    private Pending pending;
    public ChoreographerDriver(OwnerDispatcher owner) {
        this.owner = Objects.requireNonNull(owner); owner.checkOwner();
        Choreographer choreographer = Choreographer.getInstance();
        queue = new Queue() {
            @Override public void post(Choreographer.FrameCallback callback) { choreographer.postFrameCallback(callback); }
            @Override public void remove(Choreographer.FrameCallback callback) { choreographer.removeFrameCallback(callback); }
        };
    }
    ChoreographerDriver(OwnerDispatcher owner, Queue queue) {
        this.owner = Objects.requireNonNull(owner); owner.checkOwner();
        this.queue = Objects.requireNonNull(queue);
    }
    private final class Pending implements Choreographer.FrameCallback {
        private Callback callback;
        Pending(Callback callback) { this.callback = callback; }
        @Override public void doFrame(long frameTimeNanos) {
            if (pending != this) { callback = null; return; }
            pending = null;
            Callback target = callback; callback = null;
            if (target != null) target.onFrame(frameTimeNanos);
        }
    }
    @Override public boolean request(Callback callback) {
        owner.checkOwner(); Objects.requireNonNull(callback);
        if (pending != null) {
            if (pending.callback != callback) throw new IllegalStateException("Driver already owned by another scheduler");
            return true;
        }
        Pending request = new Pending(callback);
        pending = request;
        try { queue.post(request); }
        catch (RuntimeException | Error failure) {
            if (pending == request) pending = null;
            request.callback = null;
            throw failure;
        }
        return true;
    }
    @Override public void remove(Callback callback) {
        owner.checkOwner();
        Pending request = pending;
        if (request != null && request.callback == callback) {
            pending = null;
            request.callback = null;
            queue.remove(request);
        }
    }
}
