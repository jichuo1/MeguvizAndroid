package io.github.jichuo1.meguviz.android.window;

import java.util.Objects;

/** Records actual write ownership; an external change requires an explicit rebase. */
public final class InsetsBaseline {
    private InsetsValue baseline;
    private InsetsValue lastApplied;
    public InsetsBaseline(InsetsValue baseline) { this.baseline = Objects.requireNonNull(baseline); }
    public InsetsValue apply(InsetsValue current, InsetsValue insets) {
        if (!current.equals(lastApplied == null ? baseline : lastApplied))
            throw new IllegalStateException("Layout changed externally; rebaseBaseline before applying again");
        lastApplied = baseline.plus(insets); return lastApplied;
    }
    public void rebase(InsetsValue next) { baseline = Objects.requireNonNull(next); lastApplied = null; }
    public InsetsValue restoreIfOwned(InsetsValue current) { return current.equals(lastApplied) ? baseline : current; }
}
