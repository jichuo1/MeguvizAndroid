package io.github.jichuo1.meguviz.android.runtime.core;

import java.util.Objects;

final class Failures {
    private volatile ErrorSink sink;
    private volatile Throwable last;

    Failures(ErrorSink sink) { this.sink = Objects.requireNonNull(sink); }

    void report(Throwable failure) {
        reportTo(sink, failure);
    }

    void reportTo(ErrorSink receiver, Throwable failure) {
        last = failure;
        try { if (receiver != null) receiver.onError(failure); }
        catch (Throwable secondary) {
            if (secondary != failure && failure.getSuppressed().length < 8) failure.addSuppressed(secondary);
            rethrowFatal(secondary);
        }
        rethrowFatal(failure);
    }

    Throwable last() { return last; }
    ErrorSink sink() { return sink; }
    void clearSink() { sink = null; }
    static Throwable combine(Throwable first, Throwable next) {
        if (first == null) return next;
        if (next == null || first == next) return first;
        if (isFatal(next) && !isFatal(first)) {
            if (next.getSuppressed().length < 8) next.addSuppressed(first);
            return next;
        }
        if (first.getSuppressed().length < 8) first.addSuppressed(next);
        return first;
    }
    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath || failure instanceof LinkageError;
    }
    static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
        if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
        if (failure instanceof LinkageError) throw (LinkageError) failure;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof VirtualMachineError) throw (VirtualMachineError) suppressed;
            if (suppressed instanceof ThreadDeath) throw (ThreadDeath) suppressed;
            if (suppressed instanceof LinkageError) throw (LinkageError) suppressed;
        }
    }
}
