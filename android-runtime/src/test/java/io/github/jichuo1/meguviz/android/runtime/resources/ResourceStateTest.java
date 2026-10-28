package io.github.jichuo1.meguviz.android.runtime.resources;

import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResourceStateTest {
    private final OwnerDispatcher owner = new OwnerDispatcher() {
        public boolean isOwnerThread() { return true; }
        public boolean post(Runnable r) { throw new AssertionError("Unexpected post"); }
        public void remove(Runnable r) { }
    };
    private ResourceSnapshot snapshot(int display) {
        return new ResourceSnapshot(320, 1f, "en-US", 0, 16, 360, 640, display, "fixed");
    }
    private ResourceState scope(String namespace) { return new ResourceState(owner, namespace, snapshot(0), 2); }
    private static final class Value implements ResourceState.DerivedValue {
        final int value; Value(int value) { this.value = value; }
    }
    private Value resolve(ResourceState s, String name, int mask, int value) {
        return s.resolve(s.namespace(), "colors", 1, name, mask, Value.class, () -> new Value(value));
    }
    @Test public void themeInvalidationIsIndependentAndInvalidatesSameIdentity() {
        ResourceState s = scope("host"), other = scope("host");
        ResourceState.Token resources = s.token(ResourceState.RESOURCES), theme = s.token(ResourceState.THEME);
        ResourceState.Token otherTheme = other.token(ResourceState.THEME);
        Value size = resolve(s, "size", ResourceState.RESOURCES, 2);
        resolve(s, "color", ResourceState.THEME, 3);
        s.invalidateTheme();
        assertTrue(s.isCurrent(resources)); assertFalse(s.isCurrent(theme)); assertTrue(other.isCurrent(otherTheme));
        assertSame(size, resolve(s, "size", ResourceState.RESOURCES, 99));
        assertEquals(4, resolve(s, "color", ResourceState.THEME, 4).value);
    }
    @Test public void namespaceAndDisplayCannotAlias() {
        ResourceState host = scope("host"), module = scope("module");
        assertEquals(1, resolve(host, "123", ResourceState.ALL, 1).value);
        assertEquals(2, resolve(module, "123", ResourceState.ALL, 2).value);
        assertThrows(IllegalArgumentException.class, () -> host.resolve("module", "x", 1, "123", 3, Value.class, () -> new Value(9)));
        ResourceState.Token token = host.token(ResourceState.ALL);
        host.update(snapshot(2)); assertFalse(host.isCurrent(token)); assertEquals(0, host.cachedCount());
        assertFalse(host.isCurrent(module.token(ResourceState.ALL)));
    }
    @Test public void lruBoundAndResolverVersionAreReal() {
        ResourceState s = scope("host");
        Value a = resolve(s, "a", 3, 1);
        resolve(s, "b", 3, 2); assertSame(a, resolve(s, "a", 3, 9));
        resolve(s, "c", 3, 3); assertEquals(2, s.cachedCount());
        assertEquals(4, resolve(s, "b", 3, 4).value);
        assertEquals(8, s.resolve("host", "colors", 2, "b", 3, Value.class, () -> new Value(8)).value);
        assertEquals(2, s.cachedCount());
    }
    @Test public void reentrantInvalidationCannotCacheOldResult() {
        ResourceState s = scope("host");
        assertThrows(IllegalStateException.class, () -> s.resolve("host", "x", 1, "x", 3, Value.class,
                () -> { s.invalidateTheme(); return new Value(1); }));
        assertEquals(0, s.cachedCount());
    }
    @Test public void closeClearsCacheAndRejectsOldTokens() {
        ResourceState s = scope("host"); ResourceState.Token token = s.token(3);
        resolve(s, "a", 3, 1); s.close(); s.close();
        assertEquals(0, s.cachedCount()); assertFalse(s.isCurrent(token));
        assertThrows(IllegalStateException.class, () -> resolve(s, "a", 3, 2));
    }
}
