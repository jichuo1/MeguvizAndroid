package io.github.jichuo1.meguviz.android.resourcesloader;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.jichuo1.meguviz.android.runtime.platform.MainThreadDispatcher;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceScope;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceState;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native ResourcesLoader, two real APK tables, and real per-context Resources objects. */
@RunWith(AndroidJUnit4.class)
public class RealLoaderDeviceTest {
    private static final String FIXTURE_PACKAGE = "io.github.jichuo1.meguviz.loaderfixture";
    private static File fixture(Context testContext, String name) throws IOException {
        File destination = new File(testContext.getCacheDir(), name);
        try (InputStream input = testContext.getAssets().open(name);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        return destination;
    }
    private static void put(ScopedResourceLoader loader, String slot, File apk) throws IOException {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)) {
            loader.putApk(slot, fd);
        }
    }
    @Test public void nativeProvidersOrExplicitUnsupportedFallback() throws Throwable {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        File first = fixture(instrumentation.getContext(), "fixture-one.apk");
        File second = fixture(instrumentation.getContext(), "fixture-two.apk");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                Context base = instrumentation.getTargetContext();
                Configuration oneConfig = new Configuration(base.getResources().getConfiguration());
                Configuration twoConfig = new Configuration(oneConfig);
                oneConfig.fontScale = 1.11f; twoConfig.fontScale = 1.23f;
                Context oneContext = base.createConfigurationContext(oneConfig);
                Context twoContext = base.createConfigurationContext(twoConfig);
                Resources one = oneContext.getResources(), two = twoContext.getResources();
                assertNotSame(one, two);
                ResourceScope oneScope = new ResourceScope(new MainThreadDispatcher(), oneContext, "one", 2);
                ResourceScope twoScope = new ResourceScope(new MainThreadDispatcher(), twoContext, "two", 2);
                ScopedResourceLoader firstLoader = null, secondLoader = null;
                try {
                    ResourceState.Token before = oneScope.state().token(ResourceState.ALL);
                    ScopedResourceLoader.OpenResult firstOpen = ScopedResourceLoader.open(oneScope, one);
                    if (Build.VERSION.SDK_INT < 30) {
                        assertEquals(ScopedResourceLoader.Status.UNSUPPORTED, firstOpen.status);
                        assertNull(firstOpen.loader); assertTrue(oneScope.state().isCurrent(before));
                    } else {
                        assertEquals(ScopedResourceLoader.Status.OPENED, firstOpen.status);
                        firstLoader = firstOpen.loader;
                        secondLoader = ScopedResourceLoader.open(twoScope, two).loader;
                        assertFalse(oneScope.state().isCurrent(before));
                        put(firstLoader, "base", first); put(secondLoader, "base", second);
                        int firstId = one.getIdentifier("probe", "string", FIXTURE_PACKAGE);
                        int secondId = two.getIdentifier("probe", "string", FIXTURE_PACKAGE);
                        assertEquals(0x80010000, firstId); assertEquals(firstId, secondId);
                        assertEquals("first-provider", one.getString(firstId));
                        assertEquals("second-provider", two.getString(secondId));
                        ResourceState.Token old = oneScope.state().token(ResourceState.ALL);
                        put(firstLoader, "overlay", second);
                        assertFalse(oneScope.state().isCurrent(old));
                        assertEquals("second-provider", one.getString(firstId));
                        assertTrue(firstLoader.remove("overlay"));
                        assertEquals("first-provider", one.getString(firstId));
                        old = oneScope.state().token(ResourceState.ALL);
                        put(firstLoader, "base", second);
                        assertFalse(oneScope.state().isCurrent(old));
                        assertEquals("second-provider", one.getString(firstId));
                        firstLoader.close(); firstLoader = null;
                        assertEquals(0, one.getIdentifier("probe", "string", FIXTURE_PACKAGE));
                        assertEquals("second-provider", two.getString(secondId));
                    }
                    Bundle observation = new Bundle();
                    observation.putString("mvx_loader_branch", Build.VERSION.SDK_INT < 30 ? "unsupported" : "native-resources-loader");
                    instrumentation.sendStatus(2, observation);
                } finally {
                    if (firstLoader != null) firstLoader.close();
                    if (secondLoader != null) secondLoader.close();
                    oneScope.close(); twoScope.close();
                }
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
    }
}
