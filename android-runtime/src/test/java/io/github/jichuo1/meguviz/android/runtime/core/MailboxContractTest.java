package io.github.jichuo1.meguviz.android.runtime.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static io.github.jichuo1.meguviz.android.runtime.core.BoundedMailbox.*;

public class MailboxContractTest {
    private final ControlledRuntime runtime = new ControlledRuntime();
    private final LifecycleScope scope = runtime.activeScope();
    private final FrameScheduler scheduler = runtime.scheduler(64);
    private final WorkToken token = scope.lease(LifecycleScope.ALL).token();
    private final AtomicInteger releases = new AtomicInteger();
    private final List<Integer> commits = new ArrayList<>();

    private BoundedMailbox mailbox(int states, int commands, int quota) {
        return new BoundedMailbox(scope, scheduler, runtime.errors::add, states, commands, quota);
    }

    @Test public void statesCoalesceAndCommandsKeepFifo() {
        BoundedMailbox box = mailbox(2, 3, 16); Slot slot = box.newSlot();
        WorkHandle old = box.offerState(slot, token, () -> commits.add(1), releases::incrementAndGet).handle();
        box.offerCommand(token, () -> commits.add(10), releases::incrementAndGet);
        Submission newest = box.offerState(slot, token, () -> commits.add(2), releases::incrementAndGet);
        box.offerCommand(token, () -> commits.add(20), releases::incrementAndGet);
        assertEquals(Admission.REPLACED, newest.admission()); assertEquals(Outcome.SUPERSEDED, old.outcome());
        runtime.drain();
        assertFalse(commits.contains(1)); assertTrue(commits.contains(2));
        assertTrue(commits.indexOf(10) < commits.indexOf(20)); assertEquals(4, releases.get());
        assertEquals(0, box.pendingStateCount()); assertFalse(runtime.hasFrame());
    }

    @Test public void capacityRejectsNewKeysButAllowsReplacement() {
        BoundedMailbox box = mailbox(1, 1, 1); Slot slot = box.newSlot();
        box.offerState(slot, token, () -> {}, releases::incrementAndGet);
        assertEquals(Admission.REJECTED_CAPACITY, box.offerState(box.newSlot(), token, () -> {}, releases::incrementAndGet).admission());
        assertEquals(Admission.REPLACED, box.offerState(slot, token, () -> {}, releases::incrementAndGet).admission());
        box.offerCommand(token, () -> {}, releases::incrementAndGet);
        assertEquals(Admission.REJECTED_CAPACITY, box.offerCommand(token, () -> {}, releases::incrementAndGet).admission());
        assertEquals(1, box.pendingStateCount()); assertEquals(1, box.pendingCommandCount());
        box.close(); assertEquals(3, releases.get());
    }

    @Test public void queuedCancellationPhysicallyReleasesAndReturnsCapacity() {
        BoundedMailbox box = mailbox(1, 1, 1);
        WorkHandle first = box.offerCommand(token, () -> fail("cancelled"), releases::incrementAndGet).handle();
        assertEquals(Cancellation.CANCELLED, first.cancel());
        assertEquals(Cancellation.ALREADY_TERMINAL, first.cancel());
        assertEquals(1, releases.get()); assertEquals(0, box.pendingCommandCount());
        assertFalse(runtime.hasFrame());
        assertTrue(box.offerCommand(token, () -> commits.add(2), releases::incrementAndGet).accepted());
        runtime.drain(); assertEquals(Arrays.asList(2), commits); assertEquals(2, releases.get());
    }

    @Test public void runningCancellationDoesNotPretendToPreempt() {
        BoundedMailbox box = mailbox(1, 1, 1); AtomicReference<WorkHandle> handle = new AtomicReference<>();
        handle.set(box.offerCommand(token, () -> {
            ControlledRuntime.background(() -> assertEquals(Cancellation.ALREADY_RUNNING, handle.get().cancel()));
            commits.add(1);
        }, releases::incrementAndGet).handle());
        runtime.drain(); assertEquals(Outcome.COMPLETED, handle.get().outcome()); assertEquals(Arrays.asList(1), commits);
    }

    @Test public void rebindBeforeLateResultRejectsCommitAndReleasesPayload() {
        BoundedMailbox box = mailbox(1, 2, 2);
        WorkHandle old = box.offerCommand(token, () -> fail("stale"), releases::incrementAndGet).handle();
        scope.rebind(); runtime.drain();
        assertEquals(Outcome.STALE, old.outcome()); assertEquals(1, releases.get());
    }

    @Test public void scopeCloseClearsQueueCallbacksAndRegistrations() {
        BoundedMailbox box = mailbox(1, 2, 2);
        WorkHandle old = box.offerCommand(token, () -> fail("closed"), releases::incrementAndGet).handle();
        assertEquals(1, scope.registrationCount()); scope.close();
        assertEquals(Outcome.CLOSED, old.outcome()); assertEquals(1, releases.get());
        assertEquals(0, scope.registrationCount()); assertEquals(0, scheduler.channelCount()); assertFalse(runtime.hasFrame());
        assertEquals(Admission.REJECTED_CLOSED, box.offerCommand(token, () -> {}, releases::incrementAndGet).admission());
        assertEquals(1, releases.get()); assertTrue(runtime.errors.isEmpty());
    }

    @Test public void callbackReentryWaitsForNextFrame() {
        BoundedMailbox box = mailbox(1, 4, 4);
        box.offerCommand(token, () -> {
            commits.add(1); box.offerCommand(token, () -> commits.add(2), releases::incrementAndGet);
        }, releases::incrementAndGet);
        runtime.frame(); assertEquals(Arrays.asList(1), commits); assertTrue(runtime.hasFrame());
        runtime.frame(); assertEquals(Arrays.asList(1, 2), commits); assertFalse(runtime.hasFrame());
    }

    @Test public void newerStateBetweenExtractAndBeginSupersedesExtractedPayload() {
        BoundedMailbox box = mailbox(1, 2, 2); Slot slot = box.newSlot();
        WorkHandle old = box.offerState(slot, token, () -> fail("superseded extracted"), releases::incrementAndGet).handle();
        box.afterExtract = () -> {
            box.afterExtract = null;
            ControlledRuntime.background(() -> assertEquals(Admission.REPLACED,
                    box.offerState(slot, token, () -> commits.add(2), releases::incrementAndGet).admission()));
        };
        runtime.frame();
        assertEquals(Outcome.SUPERSEDED, old.outcome()); assertTrue(commits.isEmpty());
        runtime.frame(); assertEquals(Arrays.asList(2), commits); assertEquals(2, releases.get());
    }

    @Test public void publicationAtFinishBoundaryCannotLoseWakeup() {
        BoundedMailbox box = mailbox(1, 2, 2);
        box.offerCommand(token, () -> commits.add(1), releases::incrementAndGet);
        box.beforeFinish = () -> {
            box.beforeFinish = null;
            ControlledRuntime.background(() -> box.offerCommand(token, () -> commits.add(2), releases::incrementAndGet));
        };
        runtime.frame(); assertTrue(runtime.hasFrame()); runtime.frame();
        assertEquals(Arrays.asList(1, 2), commits); assertFalse(runtime.hasFrame());
        ControlledRuntime.background(() -> box.offerCommand(token, () -> commits.add(3), releases::incrementAndGet));
        assertEquals(1, runtime.queuedSignals()); runtime.drain();
        assertEquals(Arrays.asList(1, 2, 3), commits); assertFalse(runtime.hasFrame());
    }

    @Test public void backgroundStormHasOneOutstandingWakeupAndFiniteStorage() {
        BoundedMailbox box = mailbox(2, 2, 2); Slot slot = box.newSlot();
        ControlledRuntime.background(() -> {
            for (int i = 0; i < 10_000; i++) {
                final int value = i;
                assertTrue(box.offerState(slot, token, () -> commits.add(value), releases::incrementAndGet).accepted());
                assertEquals(1, box.pendingStateCount());
            }
        });
        assertEquals(1, runtime.posts); assertEquals(1, runtime.queuedSignals());
        runtime.drain(); assertEquals(Arrays.asList(9999), commits); assertEquals(10_000, releases.get());
        assertFalse(runtime.hasFrame());
    }

    @Test public void rejectedDispatcherTerminatesAcceptedWorkAndReleasesPayload() {
        BoundedMailbox box = mailbox(1, 1, 1); runtime.rejectPost = true;
        AtomicReference<Submission> offered = new AtomicReference<>();
        ControlledRuntime.background(() -> offered.set(box.offerCommand(token, () -> fail("no dispatcher"), releases::incrementAndGet)));
        assertTrue(offered.get().accepted()); assertEquals(Outcome.FAILED, offered.get().handle().outcome());
        assertEquals(1, releases.get()); assertTrue(box.isClosed());
        box.close(); assertEquals(0, scheduler.channelCount()); assertEquals(0, scope.registrationCount());
    }

    @Test public void callbackFailureClosesOnlyAffectedMailbox() {
        BoundedMailbox left = mailbox(1, 3, 3);
        LifecycleScope rightScope = runtime.activeScope();
        BoundedMailbox right = new BoundedMailbox(rightScope, scheduler, runtime.errors::add, 1, 2, 2);
        WorkHandle bad = left.offerCommand(token, () -> { throw new IllegalStateException("bad"); }, releases::incrementAndGet).handle();
        WorkHandle queued = left.offerCommand(token, () -> fail("after failure"), releases::incrementAndGet).handle();
        right.offerCommand(rightScope.lease(0).token(), () -> commits.add(2), releases::incrementAndGet);
        runtime.drain();
        assertEquals(Outcome.FAILED, bad.outcome()); assertEquals(Outcome.FAILED, queued.outcome());
        assertTrue(left.isClosed()); assertFalse(right.isClosed()); assertEquals(Arrays.asList(2), commits); assertEquals(3, releases.get());
    }

    @Test public void failingReleaseDoesNotStopOtherOwnedReleases() {
        BoundedMailbox box = mailbox(1, 3, 3);
        box.offerCommand(token, () -> {}, () -> { releases.incrementAndGet(); throw new IllegalArgumentException("release"); });
        box.offerCommand(token, () -> fail("closed by release"), releases::incrementAndGet);
        box.offerCommand(token, () -> fail("closed by release"), releases::incrementAndGet);
        runtime.drain(); assertEquals(3, releases.get()); assertTrue(box.isClosed());
        assertEquals("release", box.lastFailure().getMessage());
    }

    @Test public void sinkFailureIsRecordedWithoutRecursiveReporting() {
        AtomicInteger reports = new AtomicInteger();
        BoundedMailbox box = new BoundedMailbox(scope, scheduler, failure -> { reports.incrementAndGet(); throw new IllegalStateException("sink"); }, 1, 1, 1);
        box.offerCommand(token, () -> { throw new IllegalArgumentException("work"); }, releases::incrementAndGet);
        runtime.drain(); assertEquals(1, reports.get()); assertEquals("work", box.lastFailure().getMessage());
        assertEquals("sink", box.lastFailure().getSuppressed()[0].getMessage());
    }

    @Test public void pauseCancelsCommandsButRetainsLatestStateAndResumesWakeup() {
        BoundedMailbox box = mailbox(1, 2, 2); Slot slot = box.newSlot();
        box.offerState(slot, token, () -> commits.add(1), releases::incrementAndGet);
        WorkHandle command = box.offerCommand(token, () -> fail("paused command"), releases::incrementAndGet).handle();
        box.setPaused(true); assertEquals(Outcome.INACTIVE, command.outcome()); assertFalse(runtime.hasFrame());
        ControlledRuntime.background(() -> box.offerState(slot, token, () -> commits.add(2), releases::incrementAndGet));
        assertEquals(0, runtime.queuedSignals()); assertFalse(runtime.hasFrame());
        box.setPaused(false); runtime.drain(); assertEquals(Arrays.asList(2), commits); assertEquals(3, releases.get());
    }

    @Test public void pauseDuringBatchDoesNotDiscardExtractedState() {
        BoundedMailbox box = mailbox(1, 1, 2); Slot slot = box.newSlot();
        box.offerCommand(token, () -> box.setPaused(true), releases::incrementAndGet);
        WorkHandle state = box.offerState(slot, token, () -> commits.add(2), releases::incrementAndGet).handle();
        runtime.frame(); assertEquals(Outcome.PENDING, state.outcome()); assertFalse(runtime.hasFrame());
        box.setPaused(false); runtime.drain(); assertEquals(Outcome.COMPLETED, state.outcome()); assertEquals(Arrays.asList(2), commits);
    }

    @Test public void newCommandWhilePausedTerminatesInactiveWithoutWaitingForResume() {
        BoundedMailbox box = mailbox(1, 1, 1); box.setPaused(true);
        AtomicReference<Submission> result = new AtomicReference<>();
        ControlledRuntime.background(() -> result.set(box.offerCommand(token, () -> fail("paused command"), releases::incrementAndGet)));
        assertTrue(result.get().accepted()); assertEquals(Outcome.INACTIVE, result.get().handle().outcome());
        assertEquals(1, releases.get()); assertEquals(0, box.pendingCommandCount());
        assertEquals(0, runtime.queuedSignals()); box.setPaused(false); assertFalse(runtime.hasFrame());
    }

    @Test public void cancelBetweenExtractionAndEntryPreventsCallback() {
        BoundedMailbox box = mailbox(1, 1, 1);
        WorkHandle handle = box.offerCommand(token, () -> fail("cancelled extracted"), releases::incrementAndGet).handle();
        box.afterExtract = () -> ControlledRuntime.background(() -> assertEquals(Cancellation.CANCELLED, handle.cancel()));
        runtime.frame(); assertEquals(Outcome.CANCELLED, handle.outcome()); assertEquals(1, releases.get()); assertFalse(runtime.hasFrame());
    }

    @Test public void explicitOrderedBatchPreservesOrderWithoutRollback() {
        BoundedMailbox box = mailbox(1, 1, 1);
        WorkHandle handle = box.offerBatch(token, Arrays.asList(() -> commits.add(1), () -> { throw new IllegalStateException("partial"); }, () -> commits.add(3)), releases::incrementAndGet).handle();
        runtime.drain(); assertEquals(Arrays.asList(1), commits); assertEquals(Outcome.FAILED, handle.outcome()); assertEquals(1, releases.get());
    }

    @Test public void platformRejectionTerminatesOtherQueuedOwners() {
        BoundedMailbox box = mailbox(1, 1, 1); runtime.rejectFrame = true;
        WorkHandle handle = box.offerCommand(token, () -> fail("rejected frame"), releases::incrementAndGet).handle();
        assertEquals(Outcome.FAILED, handle.outcome()); assertEquals(1, releases.get()); assertTrue(box.isClosed());
        assertEquals(0, scheduler.channelCount());
    }

    @Test public void foreignSlotAndForeignTokenAreRejectedWithoutOwnershipTransfer() {
        BoundedMailbox left = mailbox(1, 1, 1); BoundedMailbox right = mailbox(1, 1, 1);
        assertEquals(Admission.UNSUPPORTED, left.offerState(right.newSlot(), token, () -> {}, releases::incrementAndGet).admission());
        WorkToken foreign = runtime.activeScope().lease(0).token();
        assertEquals(Admission.UNSUPPORTED, left.offerCommand(foreign, () -> {}, releases::incrementAndGet).admission());
        assertEquals(0, releases.get());
    }

    @Test public void payloadFatalAfterOrdinaryReleaseErrorsStillReleasesRemainingPayloads() {
        BoundedMailbox box = mailbox(1, 16, 1);
        for (int i = 0; i < 12; i++) box.offerCommand(token, () -> {}, () -> { releases.incrementAndGet(); throw new IllegalStateException("ordinary"); });
        box.offerCommand(token, () -> {}, () -> { releases.incrementAndGet(); throw new LinkageError("injected fatal"); });
        box.offerCommand(token, () -> {}, releases::incrementAndGet);
        assertThrows(LinkageError.class, box::close);
        assertEquals(14, releases.get()); assertEquals(0, box.pendingCommandCount());
        assertEquals(0, scheduler.channelCount()); assertEquals(0, scope.registrationCount());
        assertTrue(box.lastFailure().getSuppressed().length <= 8);
    }

    @Test public void closeHookWaitsForActualOwnerCleanupAfterBackgroundRejection() {
        BoundedMailbox box = mailbox(1, 1, 1); AtomicInteger closed = new AtomicInteger();
        box.onClose(closed::incrementAndGet); runtime.rejectPost = true;
        ControlledRuntime.background(() -> box.offerCommand(token, () -> {}, releases::incrementAndGet));
        assertTrue(box.isClosed()); assertEquals(0, closed.get());
        box.close(); assertEquals(1, closed.get()); box.close(); assertEquals(1, closed.get());
    }
}
