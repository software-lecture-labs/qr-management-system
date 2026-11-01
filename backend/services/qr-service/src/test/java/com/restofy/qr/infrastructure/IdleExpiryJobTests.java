package com.restofy.qr.infrastructure;

import com.restofy.qr.application.service.SessionIdleExpiryJob;
import com.restofy.qr.application.service.SessionIdleExpiryService;
import com.restofy.qr.infrastructure.persistence.repository.TableSessionRepository;
import com.restofy.qr.application.port.OrderSessionAccess;
import com.restofy.qr.domain.OrderSessionState;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdleExpiryJobTests {
    private TableSessionRepository.IdleCandidate candidate() {
        var row = mock(TableSessionRepository.IdleCandidate.class);
        when(row.getId()).thenReturn(UUID.randomUUID());
        when(row.getRestaurantId()).thenReturn(UUID.randomUUID());
        return row;
    }

    @Test
    void productionOrderKnowledgeIsUnknownAndNeverAssumesNoOrders() {
        assertThat(new OrderSessionAccess().inspect(UUID.randomUUID(), UUID.randomUUID()).knowledge()).isEqualTo(OrderSessionState.Knowledge.UNKNOWN);
    }

    @Test
    void batchReportsChangedUnknownPresentNoLongerDueAndFailureWithoutStoppingOtherCandidates() {
        var repo = mock(TableSessionRepository.class);
        var service = mock(SessionIdleExpiryService.class);
        var rows = List.of(candidate(), candidate(), candidate(), candidate(), candidate());
        when(repo.findIdleCandidates(any(), any(), any(), nullable(UUID.class), any(Pageable.class))).thenReturn(rows);
        when(service.expire(any(), any())).thenReturn(
                new SessionIdleExpiryService.ExpiryResult(SessionIdleExpiryService.Outcome.CHANGED, true, 1),
                new SessionIdleExpiryService.ExpiryResult(SessionIdleExpiryService.Outcome.UNKNOWN_ORDERS, false, 0),
                new SessionIdleExpiryService.ExpiryResult(SessionIdleExpiryService.Outcome.ORDERS_PRESENT, false, 0),
                new SessionIdleExpiryService.ExpiryResult(SessionIdleExpiryService.Outcome.NOT_DUE, false, 0))
                .thenThrow(new IllegalStateException("test-private-detail"));
        var result = new SessionIdleExpiryJob(repo, service, Clock.systemUTC()).runOnce();
        assertThat(result.candidates()).isEqualTo(5);
        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.unknownOrders()).isEqualTo(1);
        assertThat(result.ordersPresent()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void aFullUnknownBatchContinuesAfterItsLastIdAndWrapsAfterAnEmptyPage() {
        var repo = mock(TableSessionRepository.class);
        var service = mock(SessionIdleExpiryService.class);
        var rows = new ArrayList<TableSessionRepository.IdleCandidate>();
        for (int index = 0; index < 100; index++) { rows.add(candidate()); }
        when(repo.findIdleCandidates(any(), any(), any(), nullable(UUID.class), any(Pageable.class))).thenReturn(rows, List.of(), List.of());
        when(service.expire(any(), any())).thenReturn(new SessionIdleExpiryService.ExpiryResult(SessionIdleExpiryService.Outcome.UNKNOWN_ORDERS, false, 0));
        var job = new SessionIdleExpiryJob(repo, service, Clock.systemUTC());
        assertThat(job.runOnce().candidates()).isEqualTo(100);
        assertThat(job.runOnce().candidates()).isZero();
        assertThat(job.runOnce().candidates()).isZero();
        UUID lastId = rows.getLast().getId();
        verify(repo).findIdleCandidates(any(), any(), any(), eq(lastId), argThat(page -> page.getPageSize() == 100));
        verify(repo, times(2)).findIdleCandidates(any(), any(), any(), isNull(), argThat(page -> page.getPageSize() == 100));
    }
}
