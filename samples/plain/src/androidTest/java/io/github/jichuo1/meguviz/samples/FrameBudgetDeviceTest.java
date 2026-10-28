package io.github.jichuo1.meguviz.samples;

import android.os.Build;
import android.view.Choreographer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.jichuo1.meguviz.android.runtime.core.FrameScheduler;
import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver;
import io.github.jichuo1.meguviz.android.runtime.core.FrameTick;
import io.github.jichuo1.meguviz.android.runtime.platform.BudgetFrameDriver;
import io.github.jichuo1.meguviz.android.runtime.platform.ChoreographerDriver;
import io.github.jichuo1.meguviz.android.runtime.platform.MainThreadDispatcher;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real frame callback tests; separate from the 13-method window matrix. */
@RunWith(AndroidJUnit4.class)
public class FrameBudgetDeviceTest {
    @Test public void enabledDriverCopiesFrameValuesAndCancelsBeforeDelivery() throws Exception {
        verifyFrame(true);
    }

    @Test public void disabledDriverRetainsUnknownBudget() throws Exception {
        verifyFrame(false);
    }

    @Test public void extractedCancelledCallbackCannotDeliverReplacementInSameFrame() throws Exception {
        verifyExtractedCancellation(false);
        if (Build.VERSION.SDK_INT >= 33) verifyExtractedCancellation(true);
    }

    private void verifyExtractedCancellation(boolean vsync) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        var intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(intent);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        var activity = instrumentation.startActivitySync(intent);
        CountDownLatch delivered = new CountDownLatch(1), sentinelDelivered = new CountDownLatch(1);
        AtomicLong beforeTime = new AtomicLong(-1), sentinelTime = new AtomicLong(-1), replacementTime = new AtomicLong(-1);
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<FrameDriver> driver = new AtomicReference<>();
        FrameDriver.Callback old = time -> oldCalls.incrementAndGet();
        FrameDriver.Callback replacement = time -> { replacementTime.set(time); delivered.countDown(); };
        try {
            instrumentation.runOnMainSync(() -> {
                var owner = new MainThreadDispatcher();
                FrameDriver current = vsync ? new BudgetFrameDriver(owner, true) : new ChoreographerDriver(owner);
                driver.set(current);
                Choreographer choreographer = Choreographer.getInstance();
                // Both sentinels surround the driver's callback in the same platform phase.
                choreographer.postFrameCallback(time -> {
                    beforeTime.set(time);
                    try { current.remove(old); assertTrue(current.request(replacement)); }
                    catch (Throwable error) { failure.set(error); delivered.countDown(); }
                });
                assertTrue(current.request(old));
                choreographer.postFrameCallback(time -> { sentinelTime.set(time); sentinelDelivered.countDown(); });
            });
            assertTrue("No same-phase sentinel frame", sentinelDelivered.await(5, TimeUnit.SECONDS));
            assertTrue("No replacement frame", delivered.await(5, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals("Sentinels must bracket the extracted callback batch", beforeTime.get(), sentinelTime.get());
            assertEquals("Cancelled callback must never run", 0, oldCalls.get());
            assertTrue("Replacement must wait for a later platform frame", replacementTime.get() > beforeTime.get());
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (driver.get() != null) { driver.get().remove(old); driver.get().remove(replacement); }
                activity.finish();
            });
        }
    }

    private void verifyFrame(boolean enabled) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        var intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(intent);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        var activity = instrumentation.startActivitySync(intent);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicInteger cancelledCalls = new AtomicInteger();
        AtomicReference<FrameTick> tick = new AtomicReference<>();
        AtomicReference<FrameScheduler> scheduler = new AtomicReference<>();
        try {
            instrumentation.runOnMainSync(() -> {
                var owner = new MainThreadDispatcher();
                var current = new FrameScheduler(owner, new BudgetFrameDriver(owner, enabled), failure -> {
                    throw new AssertionError(failure);
                }, 2);
                scheduler.set(current);
                var cancelled = current.openChannel(new Object(), (frame, quota) -> {
                    cancelledCalls.incrementAndGet(); return 1;
                }, 1);
                cancelled.requestFrame(); cancelled.cancelPending();
                current.openChannel(new Object(), (frame, quota) -> {
                    tick.set(frame); delivered.countDown(); return 1;
                }, 1).requestFrame();
            });
            assertTrue("No real frame delivered", delivered.await(5, TimeUnit.SECONDS));
            FrameTick copied = tick.get(); assertNotNull(copied); assertEquals(0, cancelledCalls.get());
            assertEquals(1L, copied.sequence());
            if (enabled && Build.VERSION.SDK_INT >= 33) {
                assertEquals("platform-vsync", copied.budgetSource());
                assertNotNull(copied.deadlineNanos()); assertNotNull(copied.expectedPresentationNanos());
                assertTrue(copied.workBudgetNanos(System.nanoTime(), 1L) >= 0);
            } else {
                assertEquals("unknown", copied.budgetSource());
                assertNull(copied.deadlineNanos()); assertNull(copied.expectedPresentationNanos());
                assertNull(copied.workBudgetNanos(System.nanoTime(), 1L));
            }
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (scheduler.get() != null) scheduler.get().close();
                activity.finish();
            });
        }
    }
}
