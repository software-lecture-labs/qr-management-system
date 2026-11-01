package com.restofy.qr.infrastructure;

import com.restofy.qr.application.service.*;
import com.restofy.qr.domain.SessionTransition.*;
import com.restofy.qr.domain.entity.SessionTransitionOperation;
import com.restofy.qr.infrastructure.persistence.repository.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionClosureRetryJobTests {
    private SessionTransitionOperation operation() {
        var now = Instant.EPOCH;
        var command = new Command(UUID.randomUUID(), Purpose.SETTLED,
                new Snapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 0, now, now, null, Map.of()), now);
        return new SessionTransitionOperation(command, "{}");
    }

    private TableSessionRepository.IdleCandidate candidate() {
        var row = mock(TableSessionRepository.IdleCandidate.class);
        when(row.getId()).thenReturn(UUID.randomUUID()); when(row.getRestaurantId()).thenReturn(UUID.randomUUID());
        return row;
    }

    @Test void aFailedPendingReceiptDoesNotStarveOtherPendingOrNewCandidates() {
        var operations = mock(SessionTransitionOperationRepository.class); var sessions = mock(TableSessionRepository.class);
        var lifecycle = mock(TableSessionClosureService.class);
        when(operations.findPending(any(), nullable(UUID.class), any(Pageable.class))).thenReturn(List.of(operation(), operation()));
        var candidates = List.of(candidate(), candidate());
        when(sessions.findLifecycleCandidates(any(), nullable(UUID.class), any(Pageable.class))).thenReturn(candidates);
        when(lifecycle.resume(any(), any())).thenThrow(new IllegalStateException("private-test-detail")).thenReturn(null);
        when(lifecycle.evaluate(any(), any(), eq(Purpose.SETTLED))).thenThrow(new IllegalStateException("private-test-detail")).thenReturn(null);
        var result = new SessionClosureRetryJob(operations, sessions, lifecycle).runOnce();
        assertThat(result.pending()).isEqualTo(2); assertThat(result.candidates()).isEqualTo(2); assertThat(result.failed()).isEqualTo(2);
        verify(lifecycle, times(2)).resume(any(), any()); verify(lifecycle, times(2)).evaluate(any(), any(), eq(Purpose.SETTLED));
    }

    @Test void keysetsMovePastFullBatchesAndWrapAfterAnEmptyPage() {
        var operations = mock(SessionTransitionOperationRepository.class); var sessions = mock(TableSessionRepository.class);
        var lifecycle = mock(TableSessionClosureService.class);
        var pending = new ArrayList<SessionTransitionOperation>(); var open = new ArrayList<TableSessionRepository.IdleCandidate>();
        for (int i = 0; i < 100; i++) { pending.add(operation()); open.add(candidate()); }
        when(operations.findPending(any(), nullable(UUID.class), any(Pageable.class))).thenReturn(pending, List.of(), List.of());
        when(sessions.findLifecycleCandidates(any(), nullable(UUID.class), any(Pageable.class))).thenReturn(open, List.of(), List.of());
        var job = new SessionClosureRetryJob(operations, sessions, lifecycle);
        assertThat(job.runOnce().pending()).isEqualTo(100);
        assertThat(job.runOnce().pending()).isZero(); assertThat(job.runOnce().pending()).isZero();
        UUID pendingCursor = pending.getLast().getId();
        UUID openCursor = open.getLast().getId();
        verify(operations).findPending(any(), eq(pendingCursor), argThat(page -> page.getPageSize() == 100));
        verify(sessions).findLifecycleCandidates(any(), eq(openCursor), argThat(page -> page.getPageSize() == 100));
        verify(operations, times(2)).findPending(any(), isNull(), any(Pageable.class));
        verify(sessions, times(2)).findLifecycleCandidates(any(), isNull(), any(Pageable.class));
    }
}
