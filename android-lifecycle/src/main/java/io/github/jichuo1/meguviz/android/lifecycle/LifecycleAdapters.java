package io.github.jichuo1.meguviz.android.lifecycle;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import io.github.jichuo1.meguviz.android.runtime.core.Registration;
import io.github.jichuo1.meguviz.android.window.ViewScope;
import io.github.jichuo1.meguviz.android.window.WindowSession;
import java.util.Objects;

/** Explicit owner adapters; no application/provider initialization or Context type guessing. */
public final class LifecycleAdapters {
    private LifecycleAdapters() { }
    public static Registration bindWindow(LifecycleOwner owner, WindowSession session) {
        session.dispatcher().checkOwner(); Objects.requireNonNull(owner);
        Lifecycle lifecycle = owner.getLifecycle();
        if (lifecycle.getCurrentState() == Lifecycle.State.DESTROYED) {
            session.close(); Registration empty = new Registration(session.dispatcher(), () -> { }); empty.close(); return empty;
        }
        LifecycleEventObserver observer = (source, event) -> {
            if (session.lifecycle().admissionClosed()) return;
            if (event == Lifecycle.Event.ON_DESTROY) session.close();
            else session.setStarted(source.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED));
        };
        Registration registration = new Registration(session.dispatcher(), () -> {
            lifecycle.removeObserver(observer);
            if (!session.lifecycle().admissionClosed()) session.setStarted(false);
        });
        session.own(registration); lifecycle.addObserver(observer);
        if (!session.lifecycle().admissionClosed()) session.setStarted(lifecycle.getCurrentState().isAtLeast(Lifecycle.State.STARTED));
        return registration;
    }
    public static Registration bindViewOwner(LifecycleOwner viewOwner, ViewScope scope) {
        scope.dispatcher().checkOwner(); Objects.requireNonNull(viewOwner);
        Lifecycle lifecycle = viewOwner.getLifecycle();
        if (lifecycle.getCurrentState() == Lifecycle.State.DESTROYED) {
            scope.close(); Registration empty = new Registration(scope.dispatcher(), () -> { }); empty.close(); return empty;
        }
        LifecycleEventObserver observer = (source, event) -> {
            if (scope.lifecycle().admissionClosed()) return;
            if (event == Lifecycle.Event.ON_DESTROY) scope.close();
            else scope.setOwnerStarted(source.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED));
        };
        Registration registration = new Registration(scope.dispatcher(), () -> {
            lifecycle.removeObserver(observer);
            if (!scope.lifecycle().admissionClosed()) scope.setOwnerStarted(false);
        });
        scope.own(registration); lifecycle.addObserver(observer);
        if (!scope.lifecycle().admissionClosed()) scope.setOwnerStarted(lifecycle.getCurrentState().isAtLeast(Lifecycle.State.STARTED));
        return registration;
    }
    /** Call after onCreateView; the Fragment's own lifetime is deliberately not used. */
    public static Registration bindFragmentView(Fragment fragment, ViewScope scope) {
        return bindViewOwner(fragment.getViewLifecycleOwner(), scope);
    }
}
