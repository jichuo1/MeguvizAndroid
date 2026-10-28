package io.github.jichuo1.meguviz.android.runtime.core;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class LifecycleContractTest {
    @Test public void detachIsRecoverableAndInvalidatesAttachLease() {
        ControlledRuntime runtime = new ControlledRuntime();
        LifecycleScope scope = runtime.activeScope();
        WorkToken old = scope.lease(LifecycleScope.ALL).token();
        scope.setAttached(false);
        assertEquals(LifecycleScope.State.SUSPENDED, scope.state());
        assertFalse(old.isCurrent());
        scope.setAttached(true);
        assertTrue(scope.isActive()); assertFalse(old.isCurrent());
        scope.close();
        assertEquals(LifecycleScope.State.CLOSED, scope.state());
        assertThrows(IllegalStateException.class, () -> scope.setAttached(true));
    }

    @Test public void rebindInvalidatesOnlyRelevantDomain() {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        WorkToken binding = scope.lease(LifecycleScope.BINDING).token();
        WorkToken attach = scope.lease(LifecycleScope.ATTACH).token();
        scope.rebind();
        assertFalse(binding.isCurrent()); assertTrue(attach.isCurrent());
        AtomicInteger commits = new AtomicInteger();
        assertEquals(LifecycleScope.CommitResult.STALE, scope.commit(binding, commits::incrementAndGet));
        assertEquals(0, commits.get());
    }

    @Test public void externalResourceDomainsAreIndependent() {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        EpochDomain theme = new EpochDomain(runtime); EpochDomain resources = new EpochDomain(runtime);
        WorkToken themed = scope.lease(0, theme).token();
        WorkToken resourceOnly = scope.lease(0, resources).token();
        theme.invalidate();
        assertFalse(themed.isCurrent()); assertTrue(resourceOnly.isCurrent());
    }

    @Test public void closeAdmissionAndCompletionAreSeparate() {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        WorkToken token = scope.lease(LifecycleScope.ALL).token();
        AtomicReference<LifecycleScope.CloseRequest> result = new AtomicReference<>();
        ControlledRuntime.background(() -> result.set(scope.requestClose()));
        assertTrue(result.get().accepted()); assertFalse(result.get().completed());
        assertTrue(scope.admissionClosed()); assertFalse(scope.isClosed()); assertFalse(token.isCurrent());
        assertEquals(1, runtime.queuedSignals());
        ControlledRuntime.background(scope::requestClose);
        assertEquals(1, runtime.queuedSignals());
        runtime.pumpSignals();
        assertTrue(result.get().completed()); assertTrue(scope.isClosed());
    }

    @Test public void rejectedCloseRequiresOwnerCompletion() {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        runtime.rejectPost = true;
        AtomicReference<LifecycleScope.CloseRequest> result = new AtomicReference<>();
        ControlledRuntime.background(() -> result.set(scope.requestClose()));
        assertTrue(result.get().accepted()); assertFalse(result.get().completed()); assertNotNull(result.get().failure());
        scope.close(); assertTrue(result.get().completed());
    }

    @Test public void closeReleasesEveryRegistrationEvenWhenSinkThrows() {
        ControlledRuntime runtime = new ControlledRuntime();
        LifecycleScope scope = new LifecycleScope(runtime, error -> { throw new IllegalArgumentException("sink"); });
        AtomicInteger releases = new AtomicInteger();
        scope.own(new Registration(runtime, () -> { releases.incrementAndGet(); throw new IllegalStateException("cleanup"); }));
        scope.own(new Registration(runtime, releases::incrementAndGet));
        scope.close(); scope.close();
        assertEquals(2, releases.get()); assertEquals(0, scope.registrationCount());
        assertEquals("cleanup", scope.lastFailure().getMessage());
        assertEquals("sink", scope.lastFailure().getSuppressed()[0].getMessage());
    }

    @Test public void closingOneScopeDoesNotCloseAnother() {
        ControlledRuntime runtime = new ControlledRuntime();
        LifecycleScope left = runtime.activeScope(); LifecycleScope right = runtime.activeScope();
        WorkToken token = right.lease(LifecycleScope.ALL).token(); left.close();
        assertTrue(right.isActive()); assertTrue(token.isCurrent());
    }

    @Test public void alreadyEnteredCallbackCannotBePreemptedButNextCommitIsStale() {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        WorkToken token = scope.lease(LifecycleScope.ALL).token(); AtomicInteger count = new AtomicInteger();
        assertEquals(LifecycleScope.CommitResult.COMPLETED, scope.commit(token, () -> { scope.close(); count.incrementAndGet(); }));
        assertEquals(LifecycleScope.CommitResult.STALE, scope.commit(token, count::incrementAndGet));
        assertEquals(1, count.get());
    }

    @Test public void explicitLeaseCloseInvalidatesTokens() {
        ControlledRuntime runtime = new ControlledRuntime(); BindingLease lease = runtime.activeScope().lease(0);
        lease.close(); lease.close(); assertFalse(lease.token().isCurrent());
    }

    @Test public void epochOverflowChangesIdentityAndCannotReviveOldStamp() {
        ControlledRuntime runtime = new ControlledRuntime(); EpochDomain domain = new EpochDomain(runtime, Long.MAX_VALUE);
        EpochDomain.Stamp old = domain.stamp(); domain.invalidate();
        assertEquals(0, domain.stamp().epoch()); assertFalse(domain.matches(old));
        domain.close(); assertFalse(domain.matches(old));
    }

    @Test public void commitMustRevalidateOnOwnerThread() {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        WorkToken token = scope.lease(0).token();
        ControlledRuntime.background(() -> assertThrows(IllegalStateException.class, token::isCurrent));
        scope.setStarted(false);
        assertEquals(LifecycleScope.CommitResult.INACTIVE, scope.commit(token, () -> fail("inactive commit")));
    }

    @Test public void callbackFailureClosesScopeAndKeepsOtherCleanup() {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        AtomicInteger released = new AtomicInteger(); scope.own(new Registration(runtime, released::incrementAndGet));
        assertEquals(LifecycleScope.CommitResult.FAILED, scope.commit(scope.lease(0).token(), () -> { throw new IllegalStateException("callback"); }));
        assertTrue(scope.isClosed()); assertEquals(1, released.get()); assertEquals(1, runtime.errors.size());
    }

    @Test public void cleanupFatalAfterManyOrdinaryFailuresStillPropagatesAfterAllReleases() {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        AtomicInteger releases = new AtomicInteger();
        for (int i = 0; i < 12; i++) scope.own(new Registration(runtime, () -> { releases.incrementAndGet(); throw new IllegalStateException("ordinary"); }));
        scope.own(new Registration(runtime, () -> { releases.incrementAndGet(); throw new OutOfMemoryError("injected fatal"); }));
        scope.own(new Registration(runtime, releases::incrementAndGet));
        assertThrows(OutOfMemoryError.class, scope::close);
        assertEquals(14, releases.get()); assertEquals(0, scope.registrationCount());
        assertTrue(scope.requestClose().completed());
        assertTrue(scope.lastFailure().getSuppressed().length <= 8);
    }

    @Test public void closingRegistrationsUnlinksOwnerWithoutWaitingForScopeClose() {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        AtomicInteger count = new AtomicInteger();
        for (int i = 0; i < 1000; i++) {
            Registration registration = scope.own(new Registration(runtime, count::incrementAndGet));
            assertEquals(1, scope.registrationCount()); registration.close();
            assertEquals(0, scope.registrationCount());
        }
        assertEquals(1000, count.get());
    }

    @Test public void registrationCleanupFailureStillUnlinksAndCannotAcquireTwoOwners() throws Exception {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        Registration registration = scope.own(new Registration(runtime, () -> { throw new IllegalStateException("cleanup"); }));
        assertThrows(IllegalStateException.class, () -> scope.own(registration));
        assertThrows(IllegalStateException.class, registration::close); assertEquals(0, scope.registrationCount());
        java.lang.reflect.Field field = Registration.class.getDeclaredField("ownerRelease"); field.setAccessible(true);
        assertNull(field.get(registration));
    }

    @Test public void bindingCloseHookIsSingleOwnerAndClearedBeforeReentry() throws Exception {
        ControlledRuntime runtime = new ControlledRuntime(); BindingLease lease = runtime.activeScope().lease(0);
        AtomicInteger closed = new AtomicInteger(); lease.onClose(() -> { closed.incrementAndGet(); lease.close(); });
        assertThrows(IllegalStateException.class, () -> lease.onClose(() -> {}));
        lease.close(); lease.close(); assertEquals(1, closed.get());
        java.lang.reflect.Field field = BindingLease.class.getDeclaredField("closeListener"); field.setAccessible(true);
        assertNull(field.get(lease));
    }
}
