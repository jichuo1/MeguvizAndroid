package io.github.jichuo1.meguviz.android.runtime.platform;

import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver;
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {27, 37})
public class BudgetFrameDriverPlatformTest {
    @Test public void disabledAndEnabledDriversCanCancelAndReleaseOwnership() {
        OwnerDispatcher owner = new OwnerDispatcher() {
            @Override public boolean isOwnerThread() { return true; }
            @Override public boolean post(Runnable callback) { return false; }
            @Override public void remove(Runnable callback) { }
        };
        for (boolean enabled : new boolean[] {false, true}) {
            BudgetFrameDriver driver = new BudgetFrameDriver(owner, enabled);
            FrameDriver.Callback first = time -> fail("removed callback invoked");
            FrameDriver.Callback second = time -> { };
            assertTrue(driver.request(first)); assertTrue(driver.request(first));
            assertThrows(IllegalStateException.class, () -> driver.request(second));
            driver.remove(first); assertTrue(driver.request(second)); driver.remove(second);
        }
    }
}
