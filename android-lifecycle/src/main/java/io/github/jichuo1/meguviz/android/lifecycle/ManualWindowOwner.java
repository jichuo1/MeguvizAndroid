package io.github.jichuo1.meguviz.android.lifecycle;

import io.github.jichuo1.meguviz.android.window.WindowSession;
import java.util.Objects;

/** For dialogs or embedded hosts without an AndroidX owner; callers forward their real events. */
public final class ManualWindowOwner implements AutoCloseable {
    private final WindowSession session;
    public ManualWindowOwner(WindowSession session) { this.session = Objects.requireNonNull(session); }
    public void onStart() { session.setStarted(true); }
    public void onStop() { session.setStarted(false); }
    public void onVisibilityChanged(boolean visible) { session.setVisible(visible); }
    public void onDismiss() { close(); }
    @Override public void close() { session.close(); }
}
