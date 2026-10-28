package io.github.jichuo1.meguviz.samples;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.jichuo1.meguviz.android.runtime.core.BindingLease;
import io.github.jichuo1.meguviz.android.runtime.core.LifecycleScope;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceState;
import io.github.jichuo1.meguviz.android.window.WindowSession;
import io.github.jichuo1.meguviz.android.window.ViewScope;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Executed on devices by the matrix runner; host Robolectric results do not satisfy this suite. */
@RunWith(AndroidJUnit4.class)
public class PlatformContractTest {
    private Activity activity;
    private WindowSession session;
    private ViewScope view;
    @Before public void open() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch); launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = instrumentation.startActivitySync(launch);
        instrumentation.waitForIdleSync();
        main(() -> {
            session = WindowSession.open(activity.getWindow(), activity, "device-test", WindowSession.Permission.OBSERVE);
            session.setStarted(true); view = session.scope(activity.getWindow().getDecorView());
        });
    }
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    @After public void close() { main(() -> { if (session != null) session.close(); if (activity != null) activity.finish(); }); }
    @Test public void oldRebindResultRejected() {
        main(() -> {
            BindingLease old = view.binding(ResourceState.ALL); view.rebind();
            assertEquals(LifecycleScope.CommitResult.STALE, view.commit(old, () -> fail("stale commit")));
        });
    }
    @Test public void closeIsTerminal() {
        main(() -> {
            BindingLease old = view.binding(0); session.close(); session.close();
            assertEquals(LifecycleScope.CommitResult.STALE, view.commit(old, () -> fail("closed commit")));
            assertTrue(session.lifecycle().isClosed());
        });
    }
    @Test public void themeIdentityDoesNotHideMutation() {
        main(() -> {
            ResourceState state = session.resources().state(); ResourceState.Token token = state.token(ResourceState.THEME);
            Object theme = activity.getTheme(); activity.getTheme().applyStyle(android.R.style.Theme_Material, true);
            assertSame(theme, activity.getTheme()); session.resources().invalidateTheme(); assertFalse(state.isCurrent(token));
        });
    }
    @Test public void pauseDoesNotCloseAndResumeRestoresAdmission() {
        main(() -> {
            BindingLease lease = view.binding(0); session.setStarted(false);
            assertEquals(LifecycleScope.CommitResult.INACTIVE, view.commit(lease, () -> fail("paused commit")));
            session.setStarted(true);
            assertEquals(LifecycleScope.CommitResult.COMPLETED, view.commit(lease, () -> {}));
        });
    }
}
