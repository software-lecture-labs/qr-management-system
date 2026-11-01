package com.restofy.qr.infrastructure;

import com.restofy.qr.api.dto.request.GuestActivityRequest;
import com.restofy.qr.application.service.SessionIdleExpiryService;
import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdleSessionPostgresIntegrationTests extends TransitionIntegrationSupport {
    @Test void independentGuestDeadlinesAndParentExpiryUseFencedOrderAuthority() {
        var f = create(); Instant start = time.get();
        time.set(start.plusSeconds(300)); var second = opening.open(f.code().qrToken());
        time.set(start.plusSeconds(900).minusNanos(1));
        assertThat(expiry.expire(tenant, main(f)).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.NOT_DUE);
        time.set(start.plusSeconds(900)); var first = expiry.expire(tenant, main(f));
        assertThat(first.mainExpired()).isFalse(); assertThat(first.guestsExpired()).isEqualTo(1);
        assertThat(guests.findById(f.owner().guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.EXPIRED);
        assertThat(statistics.get(f.code().tableId()).activeGuestCount()).isEqualTo(1);
        time.set(start.plusSeconds(1200)); var last = expiry.expire(tenant, main(f));
        assertThat(last.mainExpired()).isTrue(); assertThat(last.guestsExpired()).isEqualTo(1);
        assertThat(sessions.findById(main(f)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.EXPIRED);
        assertThat(guests.findById(second.guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.EXPIRED);
        assertThat(tables.get(f.code().tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.AVAILABLE);
        assertThat(expiry.expire(tenant, main(f)).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.MISSING_OR_CLOSED);
    }

    @Test void explicitActivityRenewsOnlyItsOwnerAndMainWhileTokenValidationDoesNotWrite() {
        var f = create(); var spectator = opening.open(f.code().qrToken()); Instant start = time.get();
        long mainVersion = sessions.findById(main(f)).orElseThrow().getVersion();
        time.set(start.plusSeconds(840));
        var resolved = validation.validate(f.owner().guestToken());
        assertThat(resolved.restaurantId()).isEqualTo(tenant);
        assertThat(resolved.tableSessionId()).isEqualTo(main(f));
        assertThat(resolved.guestSessionId()).isEqualTo(f.owner().guestSession().id());
        assertThat(sessions.findById(main(f)).orElseThrow().getVersion()).isEqualTo(mainVersion);
        var result = activity.recordActivity(new GuestActivityRequest(f.owner().guestToken(), tenant, main(f), f.owner().guestSession().id()));
        assertThat(result.guestExpiresAt()).isEqualTo(start.plusSeconds(1740));
        assertThat(guests.findById(spectator.guestSession().id()).orElseThrow().getExpiresAt()).isEqualTo(start.plusSeconds(900));
        time.set(start.plusSeconds(900));
        assertThat(expiry.expire(tenant, main(f)).guestsExpired()).isEqualTo(1);
        assertThat(validation.validate(f.owner().guestToken()).guestSessionId()).isEqualTo(f.owner().guestSession().id());
    }

    @Test void acceptedOrderProtectsMainAndOwnerButNotAnotherPhone() {
        var f = create(); var other = opening.open(f.code().qrToken()); Instant start = time.get();
        state(f, OrderSessionState.Knowledge.OPEN_WORK, Set.of(f.owner().guestSession().id()));
        time.set(start.plusSeconds(1200));
        assertThat(validation.validate(f.owner().guestToken()).guestSessionId()).isEqualTo(f.owner().guestSession().id());
        var otherToken = other.guestToken();
        assertThatThrownBy(() -> validation.validate(otherToken)).isInstanceOf(InvalidGuestSessionException.class);
        var result = expiry.expire(tenant, main(f));
        assertThat(result.mainExpired()).isFalse(); assertThat(result.guestsExpired()).isEqualTo(1);
        assertThat(sessions.findById(main(f)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
        assertThat(statistics.get(f.code().tableId()).activeGuestCount()).isEqualTo(1);
        assertThat(expiry.expire(tenant, main(f)).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.ORDERS_PRESENT);
    }

    @Test void unknownOrderStateNeverPhysicallyExpiresARowOrTreatsAbsenceAsNoOrders() {
        var f = create(); state(f, OrderSessionState.Knowledge.UNKNOWN, Set.of());
        time.set(time.get().plusSeconds(900));
        assertThat(expiry.expire(tenant, main(f)).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.UNKNOWN_ORDERS);
        assertThat(sessions.findById(main(f)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
        assertThat(guests.findById(f.owner().guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.ACTIVE);
        verify(source, never()).prepare(any());
    }

    @ParameterizedTest @ValueSource(strings = {"restaurant", "main", "guest", "token"})
    void tokenContextCannotBeReboundByCallerIds(String target) {
        var f = create();
        UUID restaurant = target.equals("restaurant") ? UUID.randomUUID() : tenant;
        UUID session = target.equals("main") ? UUID.randomUUID() : main(f);
        UUID guest = target.equals("guest") ? UUID.randomUUID() : f.owner().guestSession().id();
        String token = target.equals("token") ? "A".repeat(43) : f.owner().guestToken();
        assertThatThrownBy(() -> validation.validate(token, restaurant, session, guest)).isInstanceOf(InvalidGuestSessionException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"foreign", "expired", "future", "null"})
    void staleOrForeignOrderReadStateDoesNotProtectAnExpiredGuest(String kind) {
        var f = create(); var guestToken = f.owner().guestToken(); Instant start = time.get(); time.set(start.plusSeconds(901));
        var state = kind.equals("null") ? null : new OrderSessionState(kind.equals("foreign") ? UUID.randomUUID() : tenant,
                main(f), OrderSessionState.Knowledge.OPEN_WORK, 1, kind.equals("future") ? time.get().plusSeconds(1) : start,
                kind.equals("expired") ? time.get() : time.get().plusSeconds(30), Set.of(f.owner().guestSession().id()), Map.of(), null);
        doReturn(state).when(source).inspect(any(), any());
        assertThatThrownBy(() -> validation.validate(guestToken)).isInstanceOf(InvalidGuestSessionException.class);
    }

    @Test void safeCancellationGrantsFixedFifteenMinutesWithoutAProjectionOrReadActivityWrite() {
        var f = create(); Instant start = time.get(); Instant cancelled = start.plusSeconds(1000); time.set(cancelled);
        var state = new OrderSessionState(tenant, main(f), OrderSessionState.Knowledge.NO_ORDERS, 2, cancelled,
                cancelled.plusSeconds(300), Set.of(), Map.of(f.owner().guestSession().id(), cancelled), cancelled);
        doReturn(state).when(source).inspect(any(), any());
        long version = sessions.findById(main(f)).orElseThrow().getVersion();
        assertThat(validation.validate(f.owner().guestToken()).guestSessionId()).isEqualTo(f.owner().guestSession().id());
        assertThat(sessions.findById(main(f)).orElseThrow().getVersion()).isEqualTo(version);
        var expiryResult = expiry.expire(tenant, main(f));
        assertThat(sessions.findById(main(f)).orElseThrow().getIdleExpiresAt()).isEqualTo(cancelled.plusSeconds(900));
        assertThat(guests.findById(f.owner().guestSession().id()).orElseThrow().getExpiresAt()).isEqualTo(cancelled.plusSeconds(900));
        assertThat(expiryResult.outcome()).isEqualTo(SessionIdleExpiryService.Outcome.NOT_DUE);
    }

    @Test void lateActivityCannotReviveAPhysicallyExpiredIdentity() {
        var f = create(); var guestToken = f.owner().guestToken(); var sessionId = main(f); Instant start = time.get(); time.set(start.plusSeconds(900));
        assertThat(expiry.expire(tenant, sessionId).mainExpired()).isTrue();
        state(f, OrderSessionState.Knowledge.OPEN_WORK, Set.of(f.owner().guestSession().id()));
        var request = new GuestActivityRequest(guestToken, tenant, sessionId, f.owner().guestSession().id());
        assertThatThrownBy(() -> activity.recordActivity(request))
                .isInstanceOf(InvalidGuestSessionException.class);
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.EXPIRED);
    }

    @Test void sourceCallsCannotRunWhileACallerHoldsAWriteTransaction() {
        var f = create(); var guestToken = f.owner().guestToken(); var sessionId = main(f);
        var transaction = new TransactionTemplate(manager);
        assertThatThrownBy(() -> transaction.execute(status -> validation.validate(guestToken)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transaction.execute(status -> expiry.expire(tenant, sessionId)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test void qrHasNoLegacyAdmissionOrFinancialProjectionTables() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='public' and table_name in ('order_admissions','order_business_states','order_business_events')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version in ('29','30') and success", Integer.class)).isEqualTo(2);
    }
}
