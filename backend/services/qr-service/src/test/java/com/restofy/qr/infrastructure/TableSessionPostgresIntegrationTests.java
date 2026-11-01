package com.restofy.qr.infrastructure;

import com.restofy.qr.api.controller.TableSessionController;
import com.restofy.qr.api.dto.request.*;
import com.restofy.qr.api.dto.response.CreatedQrCodeResponse;
import com.restofy.qr.api.exception.ApiExceptionHandler;
import com.restofy.qr.application.service.*;
import com.restofy.qr.domain.entity.TableSession;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.exception.InvalidQrCodeException;
import com.restofy.qr.infrastructure.persistence.repository.*;
import com.restofy.qr.infrastructure.security.TableManagementAccess;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class TableSessionPostgresIntegrationTests {
    @Autowired TableSessionOpeningService opening;
    @Autowired GuestSessionOpeningService guestOpening;
    @Autowired QrCodeCreationService creation;
    @Autowired QrCodeLifecycleService lifecycle;
    @Autowired TableManagementService tables;
    @Autowired TableSessionRepository sessions;
    @Autowired DiningTableRepository tableRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RequestMappingHandlerAdapter adapter;
    // Only management fixture setup and time are mocked. Opening/validation/DB are real.
    @MockitoBean TableManagementAccess access;
    @MockitoBean Clock clock;
    private final AtomicReference<UUID> restaurant = new AtomicReference<>();
    private MockMvc api;

    @BeforeEach
    void setup() {
        restaurant.set(UUID.randomUUID());
        when(access.requireRestaurantId()).thenAnswer(invocation -> restaurant.get());
        when(clock.instant()).thenAnswer(invocation -> Instant.now());
        api = MockMvcBuilders.standaloneSetup(new TableSessionController(guestOpening))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(adapter.getMessageConverters().toArray(HttpMessageConverter[]::new)).build();
    }

    private CreatedQrCodeResponse create(String number) {
        var code = creation.create(tables.create(new CreateTableRequest(number, null, null)).id());
        when(clock.instant()).thenReturn(code.issuedAt().plusSeconds(1).truncatedTo(ChronoUnit.MICROS));
        return code;
    }

    private String body(String token) {
        return "{\"qrToken\":\"" + token + "\"}";
    }

    private int count(UUID tableId) {
        return jdbc.queryForObject("select count(*) from table_sessions where table_id=?", Integer.class, tableId);
    }

    @Test
    void firstOpenIsAtomicAndRepeatKeepsIdentityWhileRefreshingOnlyTheMainIdleWindow() throws Exception {
        var code = create("1");
        api.perform(post("/api/v1/qr/table-sessions/open").contentType(MediaType.APPLICATION_JSON).content(body(code.qrToken())))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tableSession.restaurantId").value(code.restaurantId().toString()))
                .andExpect(jsonPath("$.tableSession.tableId").value(code.tableId().toString()))
                .andExpect(jsonPath("$.tableSession.status").value("OPEN")).andExpect(jsonPath("$.tableSession.version").value(0))
                .andExpect(jsonPath("$.qrToken").doesNotExist()).andExpect(jsonPath("$.guestSessionId").doesNotExist())
                .andExpect(jsonPath("$.guestCount").doesNotExist()).andExpect(jsonPath("$.role").doesNotExist());
        var first = opening.open(code.qrToken());
        assertThat(first.created()).isFalse();
        assertThat(first.session().openedAt()).isEqualTo(first.session().lastActivityAt());
        assertThat(first.session().idleExpiresAt()).isEqualTo(first.session().openedAt().plusSeconds(900));
        assertThat(tables.get(code.tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.OCCUPIED);
        assertThat(tables.get(code.tableId()).version()).isEqualTo(1);
        when(clock.instant()).thenReturn(first.session().openedAt().plusSeconds(300));
        api.perform(post("/api/v1/qr/table-sessions/open").contentType(MediaType.APPLICATION_JSON).content(body(code.qrToken())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.tableSession.id").value(first.session().id().toString()));
        var refreshed = opening.open(code.qrToken()).session();
        assertThat(refreshed.id()).isEqualTo(first.session().id());
        assertThat(refreshed.openedAt()).isEqualTo(first.session().openedAt());
        assertThat(refreshed.lastActivityAt()).isEqualTo(first.session().openedAt().plusSeconds(300));
        assertThat(refreshed.idleExpiresAt()).isEqualTo(refreshed.lastActivityAt().plusSeconds(900));
        assertThat(refreshed.version()).isEqualTo(first.session().version() + 1);
        assertThat(count(code.tableId())).isEqualTo(1);
        assertThat(tables.get(code.tableId()).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from guest_sessions where table_session_id=?", Integer.class, first.session().id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from qr_scan_events where table_id=?", Integer.class, code.tableId())).isEqualTo(2);
    }

    @Test
    void multipleTablesAndRestaurantsNeverShareTheirMainSession() {
        var first = create("1");
        var secondTable = create("2");
        restaurant.set(UUID.randomUUID());
        var otherRestaurant = create("1");
        var a = opening.open(first.qrToken()).session();
        var b = opening.open(secondTable.qrToken()).session();
        var c = opening.open(otherRestaurant.qrToken()).session();
        assertThat(a.restaurantId()).isEqualTo(first.restaurantId()).isNotEqualTo(c.restaurantId());
        assertThat(a.tableId()).isEqualTo(first.tableId()).isNotEqualTo(b.tableId());
        assertThat(c.tableId()).isEqualTo(otherRestaurant.tableId());
        assertThat(Set.of(a.id(), b.id(), c.id())).hasSize(3);
    }

    @Test
    void forgedHeadersDoNotChangeOwnershipOrCreateStaffAuthority() throws Exception {
        var code = create("1");
        api.perform(post("/api/v1/qr/table-sessions/open").header("X-Restaurant-Id", UUID.randomUUID().toString())
                .header("X-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content(body(code.qrToken())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.tableSession.restaurantId").value(code.restaurantId().toString()))
                .andExpect(jsonPath("$.tableSession.tableId").value(code.tableId().toString())).andExpect(jsonPath("$.role").doesNotExist());
    }

    @Test
    void eightConcurrentFirstScansCreateExactlyOneOpenSessionAndOneOccupancyChange() throws Exception {
        var code = create("1");
        var start = new CyclicBarrier(8);
        var futures = new ArrayList<Future<TableSessionOpeningService.OpeningResult>>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int scan = 0; scan < 8; scan++) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return opening.open(code.qrToken());
                }));
            }
            var results = new ArrayList<TableSessionOpeningService.OpeningResult>();
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            assertThat(results.stream().filter(TableSessionOpeningService.OpeningResult::created)).hasSize(1);
            assertThat(results.stream().map(result -> result.session().id()).distinct()).hasSize(1);
        }
        assertThat(count(code.tableId())).isEqualTo(1);
        assertThat(tables.get(code.tableId()).version()).isEqualTo(1);
    }

    @Test
    void closingSessionBlocksOpeningWithoutReplacingOrRefreshingIt() throws Exception {
        var code = create("1");
        var opened = opening.open(code.qrToken()).session();
        jdbc.update("update table_sessions set status='CLOSING',closing_at=last_activity_at where id=?", opened.id());
        api.perform(post("/api/v1/qr/table-sessions/open").contentType(MediaType.APPLICATION_JSON).content(body(code.qrToken())))
                .andExpect(status().isConflict());
        assertThat(count(code.tableId())).isEqualTo(1);
        assertThat(sessions.findById(opened.id()).orElseThrow().getLastActivityAt()).isEqualTo(opened.lastActivityAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSED", "EXPIRED"})
    void historicalTerminalSessionsDoNotBlockANewSessionWhenTableIsAvailable(String state) {
        var code = create("1");
        var previous = opening.open(code.qrToken()).session();
        jdbc.update("update table_sessions set status=?,closed_at=last_activity_at where id=?", state, previous.id());
        jdbc.update("update dining_tables set operational_status='AVAILABLE' where id=?", code.tableId());
        var next = opening.open(code.qrToken());
        assertThat(next.created()).isTrue();
        assertThat(next.session().id()).isNotEqualTo(previous.id());
        assertThat(count(code.tableId())).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OCCUPIED", "CLEANING"})
    void unavailableTableWithoutALiveSessionIsNotSilentlyRepaired(String state) throws Exception {
        var code = create("1");
        jdbc.update("update dining_tables set operational_status=? where id=?", state, code.tableId());
        api.perform(post("/api/v1/qr/table-sessions/open").contentType(MediaType.APPLICATION_JSON).content(body(code.qrToken())))
                .andExpect(status().isConflict());
        assertThat(count(code.tableId())).isZero();
    }

    @Test
    void openSessionWithAnInconsistentAvailableTableIsRejected() throws Exception {
        var code = create("1");
        opening.open(code.qrToken());
        jdbc.update("update dining_tables set operational_status='AVAILABLE' where id=?", code.tableId());
        api.perform(post("/api/v1/qr/table-sessions/open").contentType(MediaType.APPLICATION_JSON).content(body(code.qrToken())))
                .andExpect(status().isConflict());
        assertThat(count(code.tableId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactiveQr", "inactiveTable", "revoked", "deleted", "expired", "unknown"})
    void invalidCredentialsCannotCreateSessionsOrOccupyTables(String state) throws Exception {
        var code = create("1");
        String token = code.qrToken();
        switch (state) {
            case "inactiveQr" -> lifecycle.changeActive(code.tableId(), code.id(), new ChangeQrCodeActiveRequest(false, 0L));
            case "inactiveTable" -> tables.changeActive(code.tableId(), new ChangeTableActiveRequest(false, 0L));
            case "revoked" -> creation.rotate(code.tableId(), code.id(), new QrCodeVersionRequest(0L));
            case "deleted" -> lifecycle.delete(code.tableId(), code.id(), new QrCodeVersionRequest(0L));
            case "expired" -> expire(code);
            case "unknown" -> token = "A".repeat(43);
            default -> throw new IllegalArgumentException("Unknown test state");
        }
        api.perform(post("/api/v1/qr/table-sessions/open").contentType(MediaType.APPLICATION_JSON).content(body(token)))
                .andExpect(status().isNotFound());
        assertThat(count(code.tableId())).isZero();
        assertThat(tables.get(code.tableId()).operationalStatus()).isNotEqualTo(TableOperationalStatus.OCCUPIED);
    }

    @Test
    void replacementQrReusesTheTablesMainSessionWithoutDecidingGuestPolicy() {
        var code = create("1");
        var first = opening.open(code.qrToken()).session();
        var replacement = creation.rotate(code.tableId(), code.id(), new QrCodeVersionRequest(0L));
        when(clock.instant()).thenReturn(replacement.issuedAt().plusSeconds(1).truncatedTo(ChronoUnit.MICROS));
        var reused = opening.open(replacement.qrToken());
        assertThat(reused.created()).isFalse();
        assertThat(reused.session().id()).isEqualTo(first.id());
        assertThat(reused.session().openedAt()).isEqualTo(first.openedAt());
        assertThat(reused.session().idleExpiresAt()).isEqualTo(reused.session().lastActivityAt().plusSeconds(900));
    }

    @Test
    void databaseEnforcesLiveUniquenessAndTenantForeignKeyIndependentlyOfServiceChecks() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("121");
        var code = create("1");
        var first = opening.open(code.qrToken()).session();
        var duplicate = new TableSession(code.restaurantId(), code.tableId(), first.openedAt());
        assertThatThrownBy(() -> sessions.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
        var foreign = new TableSession(UUID.randomUUID(), code.tableId(), first.openedAt());
        assertThatThrownBy(() -> sessions.saveAndFlush(foreign)).isInstanceOf(DataIntegrityViolationException.class);
        UUID duplicateId = UUID.randomUUID();
        UUID restaurantId = code.restaurantId();
        UUID tableId = code.tableId();
        Timestamp opened = Timestamp.from(first.openedAt());
        assertThatThrownBy(() -> jdbc.update("insert into table_sessions(id,restaurant_id,table_id,status,opened_at,last_activity_at,closing_at) values(?,?,?,'CLOSING',?,?,?)",
                duplicateId, restaurantId, tableId, opened, opened, opened)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status='INVALID'", "version=-1", "last_activity_at=opened_at - interval '1 second'",
            "idle_expires_at=last_activity_at", "status='CLOSING'", "closed_at=opened_at", "status='CLOSED'",
            "closing_at=opened_at - interval '1 second'", "closed_by_user_id='00000000-0000-0000-0000-000000000001'"})
    void postgresRejectsInvalidSessionLifecycleAndTimeState(String assignment) {
        var code = create("1");
        UUID id = opening.open(code.qrToken()).session().id();
        String statement = "update table_sessions set " + assignment + " where id=?";
        assertThatThrownBy(() -> jdbc.update(statement, id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void failedSessionInsertRollsBackTheOccupancyAndTableVersion() throws Exception {
        var code = create("1");
        // Test-only real DB constraint; no mocked repository failure and no dev schema change.
        String name = "ck_test_session_" + code.tableId().toString().replace("-", "");
        jdbc.execute("alter table table_sessions add constraint " + name + " check(table_id <> '" + code.tableId() + "'::uuid)");
        api.perform(post("/api/v1/qr/table-sessions/open").contentType(MediaType.APPLICATION_JSON).content(body(code.qrToken())))
                .andExpect(status().isConflict());
        assertThat(tables.get(code.tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.AVAILABLE);
        assertThat(tables.get(code.tableId()).version()).isZero();
        assertThat(count(code.tableId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rotate", "disableQr", "disableTable", "delete", "expire"})
    void openingWaitsForTheMutationCommitAndRevalidatesBeforeWriting(String operation) throws Exception {
        var code = create("1");
        String token = code.qrToken();
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Object> result = new TransactionTemplate(transactions).execute(transaction -> {
                tableRepository.findForQrManagement(code.restaurantId(), code.tableId()).orElseThrow();
                switch (operation) {
                    case "rotate" -> creation.rotate(code.tableId(), code.id(), new QrCodeVersionRequest(0L));
                    case "disableQr" -> lifecycle.changeActive(code.tableId(), code.id(), new ChangeQrCodeActiveRequest(false, 0L));
                    case "disableTable" -> tables.changeActive(code.tableId(), new ChangeTableActiveRequest(false, 0L));
                    case "delete" -> lifecycle.delete(code.tableId(), code.id(), new QrCodeVersionRequest(0L));
                    case "expire" -> expire(code);
                    default -> throw new IllegalArgumentException("Unknown test operation");
                }
                var future = executor.submit(() -> {
                    try { return opening.open(token); }
                    catch (InvalidQrCodeException exception) { return exception; }
                });
                awaitTableLock();
                return future;
            });
            assertThat(result).isNotNull();
            assertThat(result.get(15, TimeUnit.SECONDS)).isInstanceOf(InvalidQrCodeException.class);
        }
        assertThat(count(code.tableId())).isZero();
    }

    @Test
    void openingFirstPreventsAConcurrentStaleDisableFromLeavingAnInactiveOccupiedTable() throws Exception {
        var code = create("1");
        var orderState = opening.prepare(code.qrToken());
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Object> disable = new TransactionTemplate(transactions).execute(transaction -> {
                opening.openLocked(code.qrToken(), orderState);
                var future = executor.submit(() -> {
                    try { return tables.changeActive(code.tableId(), new ChangeTableActiveRequest(false, 0L)); }
                    catch (OptimisticLockingFailureException exception) { return exception; }
                });
                awaitTableLock();
                return future;
            });
            assertThat(disable).isNotNull();
            assertThat(disable.get(15, TimeUnit.SECONDS)).isInstanceOf(OptimisticLockingFailureException.class);
        }
        assertThat(tables.get(code.tableId()).isActive()).isTrue();
        assertThat(tables.get(code.tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.OCCUPIED);
        assertThat(count(code.tableId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"qrToken\":null}", "{\"qrToken\":\"short\"}",
            "{\"qrToken\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"restaurantId\":\"forged\"}",
            "{\"qrToken\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"sessionId\":\"forged\"}"})
    void malformedOrAuthorityBearingBodiesAreRejected(String json) throws Exception {
        api.perform(post("/api/v1/qr/table-sessions/open").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
    }

    private void expire(CreatedQrCodeResponse code) {
        Instant expiry = code.issuedAt().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("update qr_codes set expires_at=? where id=?", Timestamp.from(expiry), code.id());
        when(clock.instant()).thenReturn(expiry);
    }

    private void awaitTableLock() {
        await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%dining_tables%' and pid<>pg_backend_pid())", Boolean.class)));
    }
}
