package com.restofy.qr.infrastructure;

import com.restofy.qr.api.dto.request.CreateTableRequest;
import com.restofy.qr.api.dto.response.*;
import com.restofy.qr.application.port.OrderSessionAccess;
import com.restofy.qr.application.service.*;
import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.domain.SessionClosure.Staff;
import com.restofy.qr.domain.SessionTransition.*;
import com.restofy.qr.infrastructure.persistence.repository.*;
import com.restofy.qr.infrastructure.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL/Flyway/QR logic, with explicit test-only Order authority. Not real Order/Payment/Auth E2E. */
@SpringBootTest(properties = {"qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "spring.datasource.hikari.maximum-pool-size=3"})
abstract class TransitionIntegrationSupport {
    @Autowired TableManagementService tables;
    @Autowired QrCodeCreationService qr;
    @Autowired GuestSessionOpeningService opening;
    @Autowired GuestSessionValidationService validation;
    @Autowired GuestSessionActivityService activity;
    @Autowired TableSessionStatisticsService statistics;
    @Autowired TableSessionClosureService lifecycle;
    @Autowired SessionTransitionTransactions transitions;
    @Autowired SessionIdleExpiryService expiry;
    @Autowired TableSessionRepository sessions;
    @Autowired GuestSessionRepository guests;
    @Autowired SessionTransitionOperationRepository operations;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean TableManagementAccess management;
    @MockitoBean SessionLifecycleAccess staffAccess;
    @MockitoBean OrderSessionAccess source;
    @MockitoBean Clock clock;
    @MockitoBean SessionIdleExpiryJob idleJob;
    @MockitoBean SessionClosureRetryJob closureJob;
    UUID tenant;
    UUID staffId;
    final AtomicReference<Instant> time = new AtomicReference<>();
    final Map<UUID, OrderSessionState.Knowledge> knowledge = new ConcurrentHashMap<>();
    final Map<UUID, Set<UUID>> protectedGuests = new ConcurrentHashMap<>();

    @BeforeEach void fixtureAuthority() {
        tenant = UUID.randomUUID(); staffId = UUID.randomUUID(); knowledge.clear(); protectedGuests.clear();
        time.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        when(clock.instant()).thenAnswer(invocation -> time.get());
        when(management.requireRestaurantId()).thenAnswer(invocation -> tenant);
        when(staffAccess.requireStaff()).thenAnswer(invocation -> new Staff(tenant, staffId));
        when(source.inspect(any(), any())).thenAnswer(invocation -> {
            outsideTransaction(); UUID restaurant = invocation.getArgument(0); UUID main = invocation.getArgument(1);
            if (!tenant.equals(restaurant)) { return OrderSessionState.unknown(restaurant, main); }
            var kind = knowledge.getOrDefault(main, OrderSessionState.Knowledge.NO_ORDERS);
            if (kind == OrderSessionState.Knowledge.UNKNOWN) { return OrderSessionState.unknown(restaurant, main); }
            return new OrderSessionState(restaurant, main, kind, 1, time.get(), time.get().plusSeconds(300),
                    protectedGuests.getOrDefault(main, Set.of()), Map.of(), null);
        });
        when(source.prepare(any())).thenAnswer(invocation -> permit(invocation.getArgument(0)));
        when(source.status(any())).thenAnswer(invocation -> permit(invocation.getArgument(0)));
        when(source.acknowledge(any(), any())).thenAnswer(invocation -> confirmed(invocation.getArgument(0), invocation.getArgument(1)));
        when(source.abort(any())).thenAnswer(invocation -> released(invocation.getArgument(0)));
    }

    Authority permit(Command command) {
        outsideTransaction(); var snapshot = command.snapshot(); var now = time.get();
        var kind = knowledge.getOrDefault(snapshot.sessionId(), OrderSessionState.Knowledge.NO_ORDERS);
        if (kind == OrderSessionState.Knowledge.UNKNOWN) {
            return new Authority(command, SourceStatus.UNKNOWN, Effect.NONE, 0, null, null, false, false, Set.of());
        }
        if (command.purpose() == Purpose.SETTLED) {
            return authority(command, kind == OrderSessionState.Knowledge.SETTLED ? SourceStatus.AUTHORISED : SourceStatus.BLOCKED,
                    kind == OrderSessionState.Knowledge.SETTLED ? Effect.SETTLED_CLOSE : Effect.NONE, Set.of());
        }
        if (kind == OrderSessionState.Knowledge.NO_ORDERS && snapshot.idleExpiresAt() != null && !snapshot.idleExpiresAt().isAfter(now)) {
            return authority(command, SourceStatus.AUTHORISED, Effect.IDLE_MAIN, Set.of());
        }
        Set<UUID> held = protectedGuests.getOrDefault(snapshot.sessionId(), Set.of());
        var due = new HashSet<UUID>();
        snapshot.guests().forEach((id, guest) -> {
            if (!held.contains(id) && guest.expiresAt() != null && !guest.expiresAt().isAfter(now)) { due.add(id); }
        });
        return authority(command, due.isEmpty() ? SourceStatus.BLOCKED : SourceStatus.AUTHORISED,
                due.isEmpty() ? Effect.NONE : Effect.IDLE_GUESTS, due);
    }

    Authority authority(Command command, SourceStatus status, Effect effect, Set<UUID> due) {
        return new Authority(command, status, effect, 1, time.get(), time.get().plusSeconds(30), true, true, due);
    }

    Confirmation confirmed(Command command, LocalResult result) {
        outsideTransaction();
        return confirmation(command, SourceState.COMPLETED, result.effect(), false, List.of());
    }

    Confirmation released(Command command) {
        outsideTransaction();
        return confirmation(command, SourceState.RELEASED, Effect.NONE, false, List.of());
    }

    Confirmation confirmation(Command command, SourceState state, Effect effect, boolean drift, List<String> blockers) {
        return new Confirmation(command.operationId(), command.snapshot().restaurantId(), command.snapshot().sessionId(),
                state == SourceState.NOT_STARTED ? null : command.purpose(), command.digest(), state, effect,
                1, time.get(), true, drift, blockers);
    }

    void outsideTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    record Fixture(CreatedQrCodeResponse code, OpenedGuestSessionResponse owner) { }

    Fixture create() {
        var code = qr.create(tables.create(new CreateTableRequest(UUID.randomUUID().toString().substring(0, 12), null, null)).id());
        time.set(code.issuedAt().plusSeconds(1).truncatedTo(ChronoUnit.MICROS));
        return new Fixture(code, opening.open(code.qrToken()));
    }

    UUID main(Fixture fixture) { return fixture.owner().tableSession().id(); }
    void state(Fixture fixture, OrderSessionState.Knowledge kind, Set<UUID> held) {
        knowledge.put(main(fixture), kind); protectedGuests.put(main(fixture), held);
    }
    OperationView settle(Fixture fixture) {
        state(fixture, OrderSessionState.Knowledge.SETTLED, Set.of(fixture.owner().guestSession().id()));
        return lifecycle.evaluate(tenant, main(fixture), Purpose.SETTLED);
    }
}
