package io.github.jichuo1.meguviz.android.runtime.core;

/** Immutable lease token; validate only immediately before an owner-thread commit. */
public final class WorkToken {
    final LifecycleScope scope;
    final BindingLease lease;
    final int dependencies;
    final EpochDomain.Stamp attach;
    final EpochDomain.Stamp binding;
    final EpochDomain[] extraDomains;
    final EpochDomain.Stamp[] extraStamps;

    WorkToken(LifecycleScope scope, BindingLease lease, int dependencies,
              EpochDomain.Stamp attach, EpochDomain.Stamp binding,
              EpochDomain[] extraDomains, EpochDomain.Stamp[] extraStamps) {
        this.scope = scope;
        this.lease = lease;
        this.dependencies = dependencies;
        this.attach = attach;
        this.binding = binding;
        this.extraDomains = extraDomains;
        this.extraStamps = extraStamps;
    }

    public boolean isCurrent() { return scope.isCurrent(this); }
    public boolean canCommit() { return scope.isActive() && isCurrent(); }
}
