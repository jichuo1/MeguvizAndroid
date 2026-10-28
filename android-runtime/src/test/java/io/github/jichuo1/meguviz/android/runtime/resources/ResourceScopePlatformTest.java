package io.github.jichuo1.meguviz.android.runtime.resources;

import android.app.Activity;
import android.util.TypedValue;
import io.github.jichuo1.meguviz.android.runtime.platform.MainThreadDispatcher;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {27, 29, 30, 37})
public class ResourceScopePlatformTest {
    @Test public void sameThemeApplyStyleRequiresExplicitEpochInvalidation() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            ResourceScope scope = new ResourceScope(new MainThreadDispatcher(), activity, "host", 4);
            assertTrue(scope.owns(activity.getResources()));
            assertFalse(scope.owns(android.content.res.Resources.getSystem()));
            Object sameTheme = activity.getTheme();
            ResourceState.Token token = scope.state().token(ResourceState.THEME);
            activity.getTheme().applyStyle(android.R.style.Theme_Material, true);
            assertSame(sameTheme, activity.getTheme());
            scope.invalidateTheme();
            assertFalse(scope.state().isCurrent(token));
            assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18,
                    activity.getResources().getDisplayMetrics()), scope.spToPixels(18), 0f);
            scope.close(); assertFalse(scope.state().isCurrent(token));
            assertThrows(IllegalStateException.class, () -> scope.owns(activity.getResources()));
        }
    }
}
