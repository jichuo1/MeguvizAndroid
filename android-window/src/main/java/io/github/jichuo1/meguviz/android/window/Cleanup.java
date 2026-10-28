package io.github.jichuo1.meguviz.android.window;

import java.util.List;

final class Cleanup {
    private Cleanup() { }
    static void closeAll(List<? extends AutoCloseable> owned) {
        Throwable first = null;
        for (AutoCloseable item : owned) {
            try { item.close(); }
            catch (Throwable failure) {
                if (first == null) first = failure;
                else if (first != failure) {
                    if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath || failure instanceof LinkageError) {
                        failure.addSuppressed(first); first = failure;
                    } else first.addSuppressed(failure);
                }
            }
        }
        if (first instanceof Error) throw (Error) first;
        if (first instanceof RuntimeException) throw (RuntimeException) first;
        if (first != null) throw new IllegalStateException("Owned cleanup failed", first);
    }
}
