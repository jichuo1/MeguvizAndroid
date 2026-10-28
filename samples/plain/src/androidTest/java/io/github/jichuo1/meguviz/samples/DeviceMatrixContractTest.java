package io.github.jichuo1.meguviz.samples;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.view.WindowCallbackWrapper;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.jichuo1.meguviz.android.runtime.core.BindingLease;
import io.github.jichuo1.meguviz.android.runtime.core.LifecycleScope;
import io.github.jichuo1.meguviz.android.runtime.core.Registration;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceScope;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceSnapshot;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceState;
import io.github.jichuo1.meguviz.android.window.*;
import io.github.jichuo1.meguviz.samples.fixtures.PlatformFixtureActivity;
import io.github.jichuo1.meguviz.samples.fixtures.PlatformFixtureActivity.RecyclingRows;
import io.github.jichuo1.meguviz.samples.fixtures.PlatformFixtureActivity.Row;
import io.github.jichuo1.meguviz.samples.fixtures.PlatformFixtureActivity.ScopedFragment;
import io.github.jichuo1.meguviz.samples.fixtures.PlatformFixtureActivity.TrackingTextView;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import static org.junit.Assert.*;

/** Native instrumentation scenes. Diagnostic status 2 is never a fabricated test-success status. */
@RunWith(AndroidJUnit4.class)
public class DeviceMatrixContractTest {
    private ActivityScenario<PlatformFixtureActivity> scenario;
    @Before public void openFixture() {
        scenario = ActivityScenario.launch(PlatformFixtureActivity.class);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        main(activity -> { assertTrue(activity.root.isAttachedToWindow()); assertTrue(activity.session.lifecycle().isActive()); });
    }
    @After public void closeFixture() {
        if (scenario != null) {
            try { main(activity -> assertTrue("Scope failures: " + activity.errors, activity.errors.isEmpty())); }
            finally { scenario.close(); }
        }
    }
    private void main(Consumer<PlatformFixtureActivity> action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        scenario.onActivity(activity -> { try { action.accept(activity); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() instanceof Error) throw (Error) failure.get();
        if (failure.get() instanceof RuntimeException) throw (RuntimeException) failure.get();
        if (failure.get() != null) throw new AssertionError("Main-thread fixture failure", failure.get());
    }
    private void awaitMain(String label, Predicate<PlatformFixtureActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            AtomicBoolean result = new AtomicBoolean(); main(activity -> result.set(condition.test(activity)));
            if (result.get()) return;
            SystemClock.sleep(16);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Timed out waiting for real platform event: " + label);
    }
    private static void await(String label, CountDownLatch event) throws InterruptedException {
        assertTrue("Timed out waiting for real platform event: " + label, event.await(5, TimeUnit.SECONDS));
    }
    private void emit(String id, JSONObject observation) {
        main(activity -> {
            try {
                observation.put("runtime_api", Build.VERSION.SDK_INT);
                observation.put("host_target_sdk", activity.getApplicationInfo().targetSdkVersion);
            } catch (org.json.JSONException error) { throw new AssertionError(error); }
        });
        Bundle status = new Bundle(); status.putString("mvx_scenario", id); status.putString("mvx_observation", observation.toString());
        InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
    }

    @Test public void activityRecreationClosesOldScopes() throws Exception {
        AtomicReference<PlatformFixtureActivity> previous = new AtomicReference<>();
        AtomicReference<ViewScope> oldScope = new AtomicReference<>(); AtomicReference<BindingLease> old = new AtomicReference<>();
        main(activity -> {
            previous.set(activity); ViewScope scope = activity.session.scope(activity.input); oldScope.set(scope); old.set(scope.binding(ResourceState.ALL));
            assertTrue(scope.lifecycle().isActive());
        });
        scenario.recreate(); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        AtomicInteger commits = new AtomicInteger();
        main(activity -> {
            assertNotSame(previous.get(), activity); assertTrue(previous.get().isDestroyed());
            assertTrue(previous.get().session.lifecycle().isClosed()); assertTrue(oldScope.get().lifecycle().isClosed());
            assertEquals(LifecycleScope.CommitResult.STALE, oldScope.get().commit(old.get(), commits::incrementAndGet));
            ViewScope fresh = activity.session.scope(activity.input); BindingLease token = fresh.binding(ResourceState.ALL);
            assertEquals(LifecycleScope.CommitResult.COMPLETED, fresh.commit(token, commits::incrementAndGet));
            token.close(); fresh.close(); assertEquals(1, commits.get());
        });
        emit("activity-recreate", new JSONObject().put("old_scope_closed", true).put("stale_commits", 0).put("new_commits", 1));
    }

    @Test public void fragmentViewDestructionReleasesBinding() throws Exception {
        AtomicReference<ScopedFragment> fragment = new AtomicReference<>(); AtomicReference<ViewScope> oldScope = new AtomicReference<>();
        AtomicReference<BindingLease> old = new AtomicReference<>();
        main(activity -> {
            ScopedFragment value = new ScopedFragment(); fragment.set(value);
            activity.getSupportFragmentManager().beginTransaction().replace(PlatformFixtureActivity.FRAGMENT_CONTAINER_ID, value).commitNow();
            oldScope.set(value.scope); old.set(value.scope.binding(0)); assertTrue(value.scope.lifecycle().isActive());
            activity.getSupportFragmentManager().beginTransaction().detach(value).commitNow();
            assertNull(value.getView()); assertTrue(oldScope.get().lifecycle().isClosed()); assertFalse(activity.session.lifecycle().isClosed());
            assertEquals(0, oldScope.get().lifecycle().registrationCount());
            assertEquals(LifecycleScope.CommitResult.STALE, oldScope.get().commit(old.get(), () -> fail("Destroyed Fragment view committed")));
            activity.getSupportFragmentManager().beginTransaction().attach(value).commitNow();
            assertNotSame(oldScope.get(), value.scope); assertTrue(value.scope.lifecycle().isActive());
            BindingLease fresh = value.scope.binding(0);
            assertEquals(LifecycleScope.CommitResult.COMPLETED, value.scope.commit(fresh, () -> ((TextView)value.requireView()).setText("new view")));
            fresh.close();
        });
        emit("fragment-destroy-view", new JSONObject().put("old_view_registration_count", 0).put("new_view_active", true));
    }

    @Test public void dialogDismissClosesOnlyDialogSession() throws Exception {
        AtomicReference<Dialog> dialog = new AtomicReference<>(); AtomicReference<WindowSession> child = new AtomicReference<>();
        AtomicReference<ViewScope> childView = new AtomicReference<>(); AtomicReference<BindingLease> old = new AtomicReference<>();
        CountDownLatch dismissed = new CountDownLatch(1);
        try {
            main(activity -> {
                Dialog value = new Dialog(activity); TextView text = new TextView(value.getContext()); text.setText("Dialog owner"); value.setContentView(text); dialog.set(value);
                WindowSession window = WindowSession.open(activity.dispatcher, activity.scheduler, value.getWindow(), value.getContext(), "dialog", WindowSession.Permission.OWN_WINDOW, activity.errors::add);
                child.set(window); childView.set(window.scope(text)); window.setStarted(true);
                value.setOnDismissListener(ignored -> { window.close(); dismissed.countDown(); }); value.show();
            });
            awaitMain("dialog attachment", activity -> childView.get().lifecycle().isActive());
            main(activity -> { old.set(childView.get().binding(0)); dialog.get().dismiss(); });
            await("dialog dismiss callback", dismissed);
            main(activity -> {
                assertTrue(child.get().lifecycle().isClosed()); assertTrue(childView.get().lifecycle().isClosed());
                assertEquals(LifecycleScope.CommitResult.STALE, childView.get().commit(old.get(), () -> fail("Dismissed dialog committed")));
                assertTrue(activity.session.lifecycle().isActive()); assertEquals(0, child.get().lifecycle().registrationCount());
            });
        } finally { main(activity -> { if (dialog.get() != null) dialog.get().dismiss(); if (child.get() != null) child.get().close(); }); }
        emit("dialog-dismiss", new JSONObject().put("dialog_closed", true).put("activity_still_active", true));
    }

    @Test public void listRebindRejectsRecycledResults() throws Exception {
        AtomicReference<RecyclingRows> adapter = new AtomicReference<>(); List<Row> previous = new ArrayList<>(); List<BindingLease> leases = new ArrayList<>();
        main(activity -> {
            RecyclingRows rows = new RecyclingRows(activity); adapter.set(rows);
            activity.list.setRecyclerListener(rows::recycle); activity.list.setAdapter(rows);
        });
        awaitMain("native list layout", activity -> activity.list.getChildCount() > 0);
        main(activity -> {
            for (int i = 0; i < activity.list.getChildCount(); i++) {
                Row row = (Row) activity.list.getChildAt(i).getTag(); previous.add(row); leases.add(row.scope.binding(ResourceState.ALL));
            }
            adapter.get().generation++; adapter.get().notifyDataSetChanged(); activity.list.setSelection(24);
        });
        awaitMain("real ListView reuse", activity -> adapter.get().rebinds > 0 && activity.list.getFirstVisiblePosition() >= 24);
        AtomicInteger staleCommits = new AtomicInteger(); AtomicInteger currentCommits = new AtomicInteger();
        main(activity -> {
            for (int i = 0; i < previous.size(); i++) {
                assertEquals(LifecycleScope.CommitResult.STALE, previous.get(i).scope.commit(leases.get(i), staleCommits::incrementAndGet)); leases.get(i).close();
            }
            Row current = (Row) activity.list.getChildAt(0).getTag(); BindingLease fresh = current.scope.binding(ResourceState.ALL);
            assertEquals(LifecycleScope.CommitResult.COMPLETED, current.scope.commit(fresh, () -> { currentCommits.incrementAndGet(); current.view.setText("current binding"); })); fresh.close();
            assertEquals("current binding", current.view.getText().toString()); assertEquals(0, staleCommits.get()); assertEquals(1, currentCommits.get());
        });
        emit("list-rebind", new JSONObject().put("actual_rebinds", adapter.get().rebinds).put("actual_recycles", adapter.get().recycles)
                .put("old_visible_leases", leases.size()).put("stale_commits", 0));
    }

    @Test public void twoNativeWindowsKeepIndependentOwnership() throws Exception {
        AtomicReference<Dialog> dialog = new AtomicReference<>(); AtomicReference<WindowSession> child = new AtomicReference<>();
        AtomicReference<WindowSession.WindowFrameChannel> first = new AtomicReference<>(), second = new AtomicReference<>();
        AtomicReference<ResourceState.Token> parentResource = new AtomicReference<>(), childResource = new AtomicReference<>();
        CountDownLatch both = new CountDownLatch(2), survivor = new CountDownLatch(1);
        List<Long> sequences = new ArrayList<>(); AtomicInteger firstCalls = new AtomicInteger(), secondCalls = new AtomicInteger();
        try {
            main(activity -> {
                Dialog value = new Dialog(activity); value.setContentView(new TextView(value.getContext())); dialog.set(value);
                WindowSession window = WindowSession.open(activity.dispatcher, activity.scheduler, value.getWindow(), value.getContext(), "second-window", WindowSession.Permission.OWN_WINDOW, activity.errors::add);
                child.set(window); window.setStarted(true); value.setOnDismissListener(ignored -> window.close()); value.show();
            });
            awaitMain("second native window attach", activity -> child.get().lifecycle().isActive());
            main(activity -> {
                assertNotSame(activity.getWindow(), dialog.get().getWindow());
                parentResource.set(activity.session.resources().state().token(ResourceState.ALL)); childResource.set(child.get().resources().state().token(ResourceState.ALL));
                assertFalse(activity.session.resources().state().isCurrent(childResource.get()));
                first.set(activity.session.openFrameChannel(new Object(), (tick, quota) -> { sequences.add(tick.sequence()); if (firstCalls.incrementAndGet() == 1) both.countDown(); else survivor.countDown(); return 1; }, 1));
                second.set(child.get().openFrameChannel(new Object(), (tick, quota) -> { sequences.add(tick.sequence()); secondCalls.incrementAndGet(); both.countDown(); return 1; }, 1));
                first.get().requestFrame(); second.get().requestFrame();
            });
            await("shared native frame for two windows", both);
            main(activity -> {
                assertEquals(2, sequences.size()); assertEquals(sequences.get(0), sequences.get(1));
                child.get().close(); dialog.get().dismiss();
                assertTrue(activity.session.resources().state().isCurrent(parentResource.get()));
                first.get().requestFrame();
            });
            await("surviving window frame", survivor);
            main(activity -> { assertEquals(2, firstCalls.get()); assertEquals(1, secondCalls.get()); assertTrue(activity.session.lifecycle().isActive()); });
        } finally { main(activity -> { if (first.get() != null) first.get().close(); if (child.get() != null) child.get().close(); if (dialog.get() != null) dialog.get().dismiss(); }); }
        emit("multi-window", new JSONObject().put("kind", "activity-plus-dialog-native-windows").put("same_first_frame_sequence", true).put("split_screen_tested", false));
    }

    @Test public void realImeVisibilityFocusAndInput() throws Exception {
        AtomicReference<InsetsSlot> slot = new AtomicReference<>(); AtomicReference<ViewScope> observationScope = new AtomicReference<>();
        AtomicReference<InsetsSnapshot> observed = new AtomicReference<>(); CountDownLatch imeVisible = new CountDownLatch(1);
        try {
            awaitMain("owned activity window focus", activity -> activity.getWindow().getDecorView().hasWindowFocus());
            main(activity -> {
                activity.session.requirePermission(WindowSession.Permission.OWN_WINDOW);
                WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
                ViewScope scope = activity.session.scope(activity.root); observationScope.set(scope);
                InsetsSlot listener = InsetsSlot.claimExclusive(scope); slot.set(listener);
                listener.observe(snapshot -> {
                    observed.set(snapshot);
                    for (InsetsSnapshot.TypeInsets type : snapshot.types())
                        if (type.type == WindowInsetsCompat.Type.ime() && type.visible && type.edges.bottom > 0) imeVisible.countDown();
                });
                activity.input.setText(""); assertTrue(activity.input.requestFocus());
                InputMethodManager manager = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                manager.showSoftInput(activity.input, InputMethodManager.SHOW_IMPLICIT);
                WindowCompat.getInsetsController(activity.getWindow(), activity.input).show(WindowInsetsCompat.Type.ime());
            });
            boolean receivedVisibleIme = imeVisible.await(5, TimeUnit.SECONDS);
            AtomicBoolean nativeVisible = new AtomicBoolean(); AtomicInteger nativeBottom = new AtomicInteger();
            main(activity -> {
                WindowInsetsCompat nativeInsets = ViewCompat.getRootWindowInsets(activity.root);
                if (nativeInsets != null) {
                    nativeVisible.set(nativeInsets.isVisible(WindowInsetsCompat.Type.ime()));
                    nativeBottom.set(nativeInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
                }
                if (nativeVisible.get() && nativeBottom.get() > 0)
                    assertTrue("Native root reports visible IME but the owned Insets observer missed it", receivedVisibleIme);
            });
            if (!receivedVisibleIme) emit("ime-focus-input", new JSONObject().put("observation_target", "owned-content-root")
                    .put("native_ime_visible", nativeVisible.get()).put("native_ime_bottom_px", nativeBottom.get()).put("acceptance", "BLOCKED"));
            Assume.assumeTrue("BLOCKED: no visible IME Insets reached the owned root; inspect IMM/window evidence before classifying the precondition", receivedVisibleIme);
            main(activity -> { assertTrue(activity.input.hasFocus()); assertTrue(activity.input.hasWindowFocus()); });
            InstrumentationRegistry.getInstrumentation().sendStringSync("mvxinput");
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            awaitMain("input delivered through native focus dispatch", activity -> "mvxinput".contentEquals(activity.input.getText()));
            main(activity -> {
                InputMethodManager manager = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                assertTrue(manager.isActive(activity.input)); assertEquals("mvxinput", activity.input.getText().toString());
            });
            emit("ime-focus-input", new JSONObject().put("ime_visible", true).put("ime_bottom_px", observed.get().combined(WindowInsetsCompat.Type.ime()).bottom)
                    .put("observation_target", "owned-content-root").put("decor_fits_requested", false)
                    .put("text_input", "instrumentation key events to actual focused EditText").put("system_settings_changed", false));
        } finally {
            main(activity -> {
                WindowCompat.getInsetsController(activity.getWindow(), activity.input).hide(WindowInsetsCompat.Type.ime());
                activity.input.clearFocus(); activity.root.requestFocus();
                if (slot.get() != null) slot.get().close(); if (observationScope.get() != null) observationScope.get().close();
            });
        }
    }

    @Test public void localRtlFontDensityPreservesWindowDomain() throws Exception {
        AtomicReference<ResourceSnapshot> localSnapshot = new AtomicReference<>(); AtomicReference<Float> pixels = new AtomicReference<>();
        main(activity -> {
            ResourceSnapshot windowBefore = activity.session.resources().state().snapshot();
            ResourceState.Token parentToken = activity.session.resources().state().token(ResourceState.ALL);
            Configuration override = new Configuration(activity.getResources().getConfiguration());
            override.setLocale(Locale.forLanguageTag("ar")); override.setLayoutDirection(Locale.forLanguageTag("ar"));
            override.fontScale = 1.7f; override.densityDpi = windowBefore.densityDpi + 80;
            Context local = activity.createConfigurationContext(override);
            TextView text = new TextView(local); text.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f);
            activity.fragmentContainer.addView(text, new FrameLayout.LayoutParams(-1, -1));
            ViewScope scope = activity.session.scope(text, "rtl-local"); ResourceScope resources = scope.resources();
            ResourceSnapshot captured = resources.state().snapshot(); localSnapshot.set(captured);
            assertEquals(View.LAYOUT_DIRECTION_RTL, text.getLayoutDirection()); assertEquals(View.LAYOUT_DIRECTION_RTL, captured.layoutDirection);
            assertEquals(local.getResources().getConfiguration().fontScale, captured.fontScale, 0f);
            assertNotEquals(windowBefore.densityDpi, captured.densityDpi);
            float expected = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 40f, local.getResources().getDisplayMetrics());
            assertEquals(expected, resources.spToPixels(40f), .01f); assertEquals(expected, text.getTextSize(), .01f); pixels.set(expected);
            assertEquals(windowBefore, activity.session.resources().state().snapshot()); assertTrue(activity.session.resources().state().isCurrent(parentToken));
            scope.close(); activity.fragmentContainer.removeView(text);
        });
        emit("rtl-font-density", new JSONObject().put("layout_direction", localSnapshot.get().layoutDirection).put("font_scale", localSnapshot.get().fontScale)
                .put("density_dpi", localSnapshot.get().densityDpi).put("sp40_pixels", pixels.get()).put("system_settings_changed", false));
    }

    @Test public void nativeInsetsReachSiblingAndReportCutout() throws Exception {
        AtomicReference<View> first = new AtomicReference<>(), sibling = new AtomicReference<>(); AtomicReference<ViewScope> scope = new AtomicReference<>();
        AtomicReference<InsetsSlot> slot = new AtomicReference<>(); AtomicReference<InsetsSnapshot> captured = new AtomicReference<>();
        AtomicReference<WindowInsetsCompat> siblingInsets = new AtomicReference<>(); CountDownLatch both = new CountDownLatch(2);
        AtomicBoolean firstSeen = new AtomicBoolean(), siblingSeen = new AtomicBoolean(); AtomicBoolean hardwareCutout = new AtomicBoolean();
        try {
            main(activity -> {
                WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
                if (Build.VERSION.SDK_INT >= 28) {
                    WindowManager.LayoutParams params = activity.getWindow().getAttributes();
                    params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES; activity.getWindow().setAttributes(params);
                }
                FrameLayout row = new FrameLayout(activity); activity.fragmentContainer.addView(row, new FrameLayout.LayoutParams(-1, -1));
                View left = new View(activity), right = new View(activity); first.set(left); sibling.set(right);
                row.addView(left, new FrameLayout.LayoutParams(40, 40)); row.addView(right, new FrameLayout.LayoutParams(40, 40));
                left.setPaddingRelative(1, 2, 3, 4); ViewScope local = activity.session.scope(left); scope.set(local);
                InsetsSlot listener = InsetsSlot.claimExclusive(local); slot.set(listener);
                listener.applyPaddingToOwnedContainer(WindowInsetsCompat.Type.systemBars());
                listener.observe(snapshot -> { captured.set(snapshot); if (firstSeen.compareAndSet(false, true)) both.countDown(); });
                ViewCompat.setOnApplyWindowInsetsListener(right, (view, insets) -> { siblingInsets.set(insets); if (siblingSeen.compareAndSet(false, true)) both.countDown(); return insets; });
                ViewCompat.requestApplyInsets(activity.root);
            });
            await("actual native insets on both sibling views", both);
            main(activity -> {
                InsetsSnapshot snapshot = captured.get(); WindowInsetsCompat right = siblingInsets.get(); assertNotNull(snapshot); assertNotNull(right);
                assertFalse("Owned observer must not consume a later sibling", right.isConsumed());
                Insets nativeSafe = right.getInsets(WindowInsetsCompat.Type.systemBars()); InsetsValue actual = snapshot.combined(WindowInsetsCompat.Type.systemBars());
                assertEquals(new InsetsValue(nativeSafe.left, nativeSafe.top, nativeSafe.right, nativeSafe.bottom), actual);
                assertEquals(2 + actual.top, first.get().getPaddingTop()); assertEquals(4 + actual.bottom, first.get().getPaddingBottom());
                WindowInsetsCompat rootInsets = ViewCompat.getRootWindowInsets(activity.root); assertNotNull(rootInsets);
                Insets cutout = rootInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
                assertEquals(new InsetsValue(cutout.left, cutout.top, cutout.right, cutout.bottom), snapshot.combined(WindowInsetsCompat.Type.displayCutout()));
                if (Build.VERSION.SDK_INT >= 28) hardwareCutout.set(activity.root.getRootWindowInsets().getDisplayCutout() != null);
                assertEquals(activity.getApplicationInfo().targetSdkVersion, snapshot.hostTargetSdk);
            });
            emit("insets-sibling-cutout", new JSONObject().put("native_siblings_observed", 2).put("insets_consumed", false)
                    .put("hardware_cutout_present", hardwareCutout.get()).put("cutout_bottom_px", captured.get().combined(WindowInsetsCompat.Type.displayCutout()).bottom));
        } finally { main(activity -> { if (sibling.get() != null) ViewCompat.setOnApplyWindowInsetsListener(sibling.get(), null); if (slot.get() != null) slot.get().close(); if (scope.get() != null) scope.get().close(); }); }
    }

    @Test public void ownedListenersReleaseAndRespectSuccessors() throws Exception {
        AtomicReference<TrackingTextView> target = new AtomicReference<>(); AtomicReference<ViewScope> scope = new AtomicReference<>();
        AtomicReference<Registration> registration = new AtomicReference<>(); AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<CountDownLatch> draw = new AtomicReference<>(new CountDownLatch(1));
        main(activity -> {
            TrackingTextView view = new TrackingTextView(activity); target.set(view); activity.fragmentContainer.addView(view, new FrameLayout.LayoutParams(80, 80));
            ViewScope owner = activity.session.scope(view); scope.set(owner);
            registration.set(ObserverRegistration.onPreDraw(owner, () -> { callbacks.incrementAndGet(); draw.get().countDown(); })); view.invalidate();
        });
        await("owned native pre-draw", draw.get());
        AtomicInteger detachedCount = new AtomicInteger(); CountDownLatch parentDraw = new CountDownLatch(1);
        AtomicReference<ViewTreeObserver.OnPreDrawListener> parentListener = new AtomicReference<>(); AtomicReference<ViewTreeObserver> parentTree = new AtomicReference<>();
        main(activity -> {
            activity.fragmentContainer.removeView(target.get()); detachedCount.set(callbacks.get());
            ViewTreeObserver tree = activity.root.getViewTreeObserver(); parentTree.set(tree);
            ViewTreeObserver.OnPreDrawListener listener = () -> { parentDraw.countDown(); return true; }; parentListener.set(listener); tree.addOnPreDrawListener(listener); activity.root.invalidate();
        });
        try { await("parent draw after detach", parentDraw); }
        finally { main(activity -> { if (parentTree.get().isAlive()) parentTree.get().removeOnPreDrawListener(parentListener.get()); }); }
        main(activity -> {
            assertEquals(detachedCount.get(), callbacks.get()); draw.set(new CountDownLatch(1));
            activity.fragmentContainer.addView(target.get(), new FrameLayout.LayoutParams(80, 80)); target.get().invalidate();
        });
        await("owned pre-draw after reattach", draw.get());
        main(activity -> {
            registration.get().close(); scope.get().close(); assertEquals(0, target.get().listenerCount()); assertEquals(0, scope.get().lifecycle().registrationCount());
            Window window = activity.getWindow(); Window.Callback original = window.getCallback();
            WindowCallbackLease lease = WindowCallbackLease.install(activity.session, WindowCallbackWrapper::new);
            Window.Callback later = new WindowCallbackWrapper(window.getCallback()); window.setCallback(later); lease.close(); assertSame(later, window.getCallback()); window.setCallback(original);
            ViewScope next = activity.session.scope(target.get()); InsetsSlot insets = InsetsSlot.claimExclusive(next); insets.handOffToExternalOwner();
            AtomicInteger external = new AtomicInteger(); ViewCompat.setOnApplyWindowInsetsListener(target.get(), (view, values) -> { external.incrementAndGet(); return values; });
            next.close(); WindowInsetsCompat seed = ViewCompat.getRootWindowInsets(target.get()); assertNotNull(seed);
            ViewCompat.dispatchApplyWindowInsets(target.get(), seed); assertEquals(1, external.get()); ViewCompat.setOnApplyWindowInsetsListener(target.get(), null);
        });
        emit("listener-ownership", new JSONObject().put("remaining_attach_listeners", 0).put("post_detach_ghost_callbacks", 0).put("successor_callback_preserved", true));
    }
}
