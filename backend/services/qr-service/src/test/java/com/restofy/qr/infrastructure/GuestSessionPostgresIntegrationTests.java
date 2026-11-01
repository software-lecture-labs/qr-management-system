package com.restofy.qr.infrastructure;

import com.restofy.qr.api.controller.TableSessionController;
import com.restofy.qr.api.dto.request.*;
import com.restofy.qr.api.dto.response.*;
import com.restofy.qr.api.exception.ApiExceptionHandler;
import com.restofy.qr.application.service.*;
import com.restofy.qr.domain.entity.GuestSession;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.exception.InvalidGuestSessionException;
import com.restofy.qr.domain.exception.TableSessionConflictException;
import com.restofy.qr.infrastructure.persistence.repository.*;
import com.restofy.qr.infrastructure.security.GuestTokenCodec;
import com.restofy.qr.infrastructure.security.TableManagementAccess;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
class GuestSessionPostgresIntegrationTests {
    @Autowired GuestSessionOpeningService opening;
    @Autowired GuestSessionValidationService validation;
    @Autowired GuestTokenCodec tokens;
    @Autowired QrCodeCreationService creation;
    @Autowired TableManagementService tables;
    @Autowired GuestSessionRepository guests;
    @Autowired TableSessionRepository sessions;
    @Autowired DiningTableRepository tableRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RequestMappingHandlerAdapter adapter;
    @MockitoBean TableManagementAccess access;
    @MockitoBean Clock clock;
    private final AtomicReference<UUID> restaurant = new AtomicReference<>();
    private MockMvc api;

    @BeforeEach
    void setup() {
        restaurant.set(UUID.randomUUID());
        when(access.requireRestaurantId()).thenAnswer(invocation -> restaurant.get());
        when(clock.instant()).thenAnswer(invocation -> Instant.now());
        api = MockMvcBuilders.standaloneSetup(new TableSessionController(opening))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(adapter.getMessageConverters().toArray(HttpMessageConverter[]::new)).build();
    }

    private CreatedQrCodeResponse create(String number) {
        var code = creation.create(tables.create(new CreateTableRequest(number, null, null)).id());
        when(clock.instant()).thenReturn(code.issuedAt().plusSeconds(1).truncatedTo(ChronoUnit.MICROS));
        return code;
    }

    private int count(UUID tableId) {
        return jdbc.queryForObject("select count(*) from guest_sessions g join table_sessions s on s.id=g.table_session_id where s.table_id=?", Integer.class, tableId);
    }

    private GuestSessionValidationService.ValidatedGuestContext validate(OpenedGuestSessionResponse opened) {
        return validation.validate(opened.guestToken(), opened.guestSession().restaurantId(), opened.tableSession().id(), opened.guestSession().id());
    }

    @Test
    void firstHttpScanAndEveryRepeatProduceDistinctGuestsInTheSameMainSession() throws Exception {
        var code = create("1");
        String body = "{\"qrToken\":\"" + code.qrToken() + "\"}";
        api.perform(post("/api/v1/qr/table-sessions/open").header("X-Restaurant-Id", UUID.randomUUID().toString())
                .header("X-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tableSession.status").value("OPEN"))
                .andExpect(jsonPath("$.tableSession.restaurantId").value(code.restaurantId().toString()))
                .andExpect(jsonPath("$.tableSession.tableId").value(code.tableId().toString()))
                .andExpect(jsonPath("$.guestSession.status").value("ACTIVE"))
                .andExpect(jsonPath("$.guestToken").isString()).andExpect(jsonPath("$.guestSession.tokenDigest").doesNotExist())
                .andExpect(jsonPath("$.encryptedPayload").doesNotExist()).andExpect(jsonPath("$.qrToken").doesNotExist())
                .andExpect(jsonPath("$.role").doesNotExist()).andExpect(jsonPath("$.guestCount").doesNotExist());
        assertThat(count(code.tableId())).isEqualTo(1);
        var first = opening.open(code.qrToken());
        when(clock.instant()).thenReturn(first.guestSession().joinedAt().plusSeconds(30));
        var repeat = opening.open(code.qrToken());
        assertThat(repeat.tableSession().id()).isEqualTo(first.tableSession().id());
        assertThat(repeat.tableSession().openedAt()).isEqualTo(first.tableSession().openedAt());
        assertThat(repeat.tableSession().lastActivityAt()).isEqualTo(first.guestSession().joinedAt().plusSeconds(30));
        assertThat(repeat.tableSession().idleExpiresAt()).isEqualTo(repeat.tableSession().lastActivityAt().plusSeconds(900));
        assertThat(repeat.guestSession().id()).isNotEqualTo(first.guestSession().id());
        assertThat(repeat.guestToken()).isNotEqualTo(first.guestToken()).isNotEqualTo(code.qrToken());
        assertThat(repeat.guestSession().tableSessionId()).isEqualTo(first.tableSession().id());
        assertThat(repeat.guestSession().joinedAt()).isAfter(first.guestSession().joinedAt());
        assertThat(repeat.guestSession().joinedAt()).isEqualTo(repeat.guestSession().lastActivityAt());
        assertThat(repeat.guestSession().expiresAt()).isEqualTo(repeat.guestSession().joinedAt().plusSeconds(900));
        assertThat(repeat.guestSession().version()).isZero();
        assertThat(count(code.tableId())).isEqualTo(3);
        assertThat(tables.get(code.tableId()).version()).isEqualTo(1);
        var stored = guests.findById(first.guestSession().id()).orElseThrow();
        assertThat(stored.getTokenDigest()).isEqualTo(tokens.digest(first.guestToken())).isNotEqualTo(first.guestToken());
        assertThat(first.toString()).doesNotContain(first.guestToken());
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='guest_sessions'", String.class))
                .containsExactlyInAnyOrder("id", "restaurant_id", "table_session_id", "token_digest", "status", "joined_at", "last_activity_at", "expires_at", "ended_at", "version");
        assertThat(jdbc.queryForObject("select count(*) from qr_scan_events where table_id=?", Integer.class, code.tableId())).isEqualTo(3);
    }

    @Test
    void eightConcurrentScansCreateOneMainAndEightDistinctBoundGuestsAndTokens() throws Exception {
        var code = create("1");
        var start = new CyclicBarrier(8);
        var futures = new ArrayList<Future<OpenedGuestSessionResponse>>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return opening.open(code.qrToken()); }));
            }
            var results = new ArrayList<OpenedGuestSessionResponse>();
            for (var future : futures) { results.add(future.get(20, TimeUnit.SECONDS)); }
            assertThat(results.stream().map(result -> result.tableSession().id()).distinct()).hasSize(1);
            assertThat(results.stream().map(result -> result.guestSession().id()).distinct()).hasSize(8);
            assertThat(results.stream().map(OpenedGuestSessionResponse::guestToken).distinct()).hasSize(8);
            for (var result : results) {
                assertThat(validate(result).tableId()).isEqualTo(code.tableId());
                assertThat(result.guestSession().tableSessionId()).isEqualTo(result.tableSession().id());
            }
        }
        assertThat(count(code.tableId())).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from table_sessions where table_id=?", Integer.class, code.tableId())).isEqualTo(1);
        assertThat(tables.get(code.tableId()).version()).isEqualTo(1);
    }

    @Test
    void tablesAndRestaurantsNeverShareGuestOwnershipAndValidationDoesNotRefreshActivity() {
        var first = opening.open(create("1").qrToken());
        var second = opening.open(create("2").qrToken());
        restaurant.set(UUID.randomUUID());
        var third = opening.open(create("1").qrToken());
        for (var result : List.of(first, second, third)) {
            var before = guests.findById(result.guestSession().id()).orElseThrow();
            var context = validate(result);
            assertThat(context.restaurantId()).isEqualTo(result.guestSession().restaurantId());
            assertThat(context.tableSessionId()).isEqualTo(result.tableSession().id());
            assertThat(context.guestSessionId()).isEqualTo(result.guestSession().id());
            var after = guests.findById(result.guestSession().id()).orElseThrow();
            assertThat(after.getVersion()).isEqualTo(before.getVersion());
            assertThat(after.getLastActivityAt()).isEqualTo(before.getLastActivityAt());
            assertThat(after.getExpiresAt()).isEqualTo(before.getExpiresAt());
        }
        assertThat(first.tableSession().id()).isNotEqualTo(second.tableSession().id());
        assertThat(first.guestSession().restaurantId()).isNotEqualTo(third.guestSession().restaurantId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"restaurant", "main", "guest", "nullRestaurant", "nullMain", "nullGuest"})
    void tokenCannotValidateAnotherOperationContextOrGuestOnTheSameTable(String mismatch) {
        var code = create("1");
        var first = opening.open(code.qrToken());
        var second = opening.open(code.qrToken());
        UUID tenant = mismatch.equals("restaurant") ? UUID.randomUUID() : first.guestSession().restaurantId();
        UUID main = mismatch.equals("main") ? UUID.randomUUID() : first.tableSession().id();
        UUID guest = mismatch.equals("guest") ? second.guestSession().id() : first.guestSession().id();
        UUID expectedTenant = mismatch.equals("nullRestaurant") ? null : tenant;
        UUID expectedMain = mismatch.equals("nullMain") ? null : main;
        UUID expectedGuest = mismatch.equals("nullGuest") ? null : guest;
        String token = first.guestToken();
        assertThatThrownBy(() -> validation.validate(token, expectedTenant, expectedMain, expectedGuest))
                .isInstanceOf(InvalidGuestSessionException.class).hasMessage("Guest session is unavailable.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void missingMalformedOrUnknownGuestTokenIsRejected(String token) {
        var opened = opening.open(create("1").qrToken());
        UUID tenant = opened.guestSession().restaurantId();
        UUID main = opened.tableSession().id();
        UUID guest = opened.guestSession().id();
        assertThatThrownBy(() -> validation.validate(token, tenant, main, guest))
                .isInstanceOf(InvalidGuestSessionException.class).hasMessage("Guest session is unavailable.");
    }

    @Test
    void qrTokenAndGuestUuidAreNotGuestCredentials() {
        var code = create("1");
        var opened = opening.open(code.qrToken());
        UUID tenant = code.restaurantId();
        UUID main = opened.tableSession().id();
        UUID guest = opened.guestSession().id();
        for (String credential : List.of(code.qrToken(), opened.guestSession().id().toString())) {
            assertThatThrownBy(() -> validation.validate(credential, tenant, main, guest))
                    .isInstanceOf(InvalidGuestSessionException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENDED", "EXPIRED", "expiryBoundary", "futureJoin", "futureActivity"})
    void invalidGuestStateOrTimeIsRejectedWithoutAffectingOtherGuests(String state) {
        var code = create("1");
        var first = opening.open(code.qrToken());
        var other = opening.open(code.qrToken());
        UUID id = first.guestSession().id();
        switch (state) {
            case "ENDED", "EXPIRED" -> jdbc.update("update guest_sessions set status=?,ended_at=last_activity_at where id=?", state, id);
            case "expiryBoundary" -> {
                Instant expiry = first.guestSession().joinedAt().plusSeconds(60);
                jdbc.update("update guest_sessions set expires_at=? where id=?", Timestamp.from(expiry), id);
                when(clock.instant()).thenReturn(expiry.minusNanos(1));
                assertThat(validate(first).guestSessionId()).isEqualTo(id);
                when(clock.instant()).thenReturn(expiry);
            }
            case "futureJoin" -> jdbc.update("update guest_sessions set joined_at=joined_at+interval '1 minute',last_activity_at=last_activity_at+interval '1 minute' where id=?", id);
            case "futureActivity" -> jdbc.update("update guest_sessions set last_activity_at=last_activity_at+interval '1 minute' where id=?", id);
            default -> throw new IllegalArgumentException("Unknown state");
        }
        assertThatThrownBy(() -> validate(first)).isInstanceOf(InvalidGuestSessionException.class);
        assertThat(validate(other).guestSessionId()).isEqualTo(other.guestSession().id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSING", "CLOSED", "EXPIRED", "idleBoundary", "futureOpen", "futureActivity", "inactiveTable", "cleaning"})
    void guestCannotBeUsedWithAnUnavailableMainSessionOrTable(String state) {
        var code = create("1");
        var opened = opening.open(code.qrToken());
        UUID main = opened.tableSession().id();
        switch (state) {
            case "CLOSING" -> jdbc.update("update table_sessions set status='CLOSING',closing_at=last_activity_at where id=?", main);
            case "CLOSED", "EXPIRED" -> jdbc.update("update table_sessions set status=?,closed_at=last_activity_at where id=?", state, main);
            case "idleBoundary" -> {
                Instant expiry = opened.tableSession().lastActivityAt().plusSeconds(60);
                jdbc.update("update table_sessions set idle_expires_at=? where id=?", Timestamp.from(expiry), main);
                when(clock.instant()).thenReturn(expiry);
                String token = code.qrToken();
                assertThatThrownBy(() -> opening.open(token)).isInstanceOf(TableSessionConflictException.class);
            }
            case "futureOpen" -> jdbc.update("update table_sessions set opened_at=opened_at+interval '1 minute',last_activity_at=last_activity_at+interval '1 minute' where id=?", main);
            case "futureActivity" -> jdbc.update("update table_sessions set last_activity_at=last_activity_at+interval '1 minute' where id=?", main);
            case "inactiveTable" -> jdbc.update("update dining_tables set is_active=false,operational_status='DISABLED' where id=?", code.tableId());
            case "cleaning" -> jdbc.update("update dining_tables set operational_status='CLEANING' where id=?", code.tableId());
            default -> throw new IllegalArgumentException("Unknown state");
        }
        assertThatThrownBy(() -> validate(opened)).isInstanceOf(InvalidGuestSessionException.class);
        assertThat(count(code.tableId())).isEqualTo(1);
    }

    @Test
    void databaseEnforcesDigestUniquenessAndTheCompositeTenantParentKey() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("121");
        var opened = opening.open(create("1").qrToken());
        var guest = opened.guestSession();
        var duplicate = new GuestSession(guest.restaurantId(), guest.tableSessionId(), tokens.digest(opened.guestToken()), guest.joinedAt());
        assertThatThrownBy(() -> guests.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
        var foreign = new GuestSession(UUID.randomUUID(), guest.tableSessionId(), tokens.digest(tokens.generateToken()), guest.joinedAt());
        assertThatThrownBy(() -> guests.saveAndFlush(foreign)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status='INVALID'", "version=-1", "token_digest='raw-token'", "last_activity_at=joined_at-interval '1 second'",
            "expires_at=joined_at", "expires_at='infinity'", "joined_at='-infinity'", "status='ENDED'", "status='EXPIRED'",
            "ended_at=joined_at", "status='ENDED',ended_at=joined_at-interval '1 second'"})
    void postgresRejectsInvalidCredentialLifecycleOrTimeState(String assignment) {
        var opened = opening.open(create("1").qrToken());
        UUID id = opened.guestSession().id();
        assertThatThrownBy(() -> jdbc.update("update guest_sessions set " + assignment + " where id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedGuestInsertRollsBackTheWholeFirstScanAndPreservesAnExistingSession(boolean existing) throws Exception {
        var code = create("1");
        OpenedGuestSessionResponse before = existing ? opening.open(code.qrToken()) : null;
        UUID main = existing ? before.tableSession().id() : null;
        // A real INSERT constraint failure in disposable Testcontainers only.
        String name = "ck_test_guest_" + code.tableId().toString().replace("-", "");
        String predicate = existing ? "table_session_id <> '" + main + "'::uuid or id = '" + before.guestSession().id() + "'::uuid"
                : "restaurant_id <> '" + code.restaurantId() + "'::uuid";
        jdbc.execute("alter table guest_sessions add constraint " + name + " check (" + predicate + ")");
        api.perform(post("/api/v1/qr/table-sessions/open").contentType(MediaType.APPLICATION_JSON)
                .content("{\"qrToken\":\"" + code.qrToken() + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("The operation conflicts with the current data. Reload and retry."));
        assertThat(count(code.tableId())).isEqualTo(existing ? 1 : 0);
        assertThat(jdbc.queryForObject("select count(*) from table_sessions where table_id=?", Integer.class, code.tableId())).isEqualTo(existing ? 1 : 0);
        assertThat(tables.get(code.tableId()).version()).isEqualTo(existing ? 1 : 0);
        assertThat(tables.get(code.tableId()).operationalStatus()).isEqualTo(existing ? TableOperationalStatus.OCCUPIED : TableOperationalStatus.AVAILABLE);
        if (existing) {
            assertThat(sessions.findById(main).orElseThrow().getLastActivityAt()).isEqualTo(before.tableSession().lastActivityAt());
            assertThat(validate(before).guestSessionId()).isEqualTo(before.guestSession().id());
        }
    }

    @Test
    void backwardsClockCannotRefreshAnExistingMainOrAddAnotherGuest() {
        var code = create("1");
        Instant checked = code.issuedAt().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        when(clock.instant()).thenReturn(checked);
        opening.open(code.qrToken());
        when(clock.instant()).thenReturn(checked.minusSeconds(1));
        String token = code.qrToken();
        assertThatThrownBy(() -> opening.open(token)).isInstanceOf(TableSessionConflictException.class);
        assertThat(count(code.tableId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from table_sessions where table_id=?", Integer.class, code.tableId())).isEqualTo(1);
        assertThat(tables.get(code.tableId()).version()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"endGuest", "expireGuest", "closingMain", "closeMain", "expireMain", "inactiveTable"})
    void validationWaitsForTheParentLockAndReadsTheCommittedGuestAndMainState(String operation) throws Exception {
        var code = create("1");
        var opened = opening.open(code.qrToken());
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Object> result = new TransactionTemplate(transactions).execute(transaction -> {
                tableRepository.findForQrManagement(code.restaurantId(), code.tableId()).orElseThrow();
                switch (operation) {
                    case "endGuest" -> jdbc.update("update guest_sessions set status='ENDED',ended_at=last_activity_at where id=?", opened.guestSession().id());
                    case "expireGuest" -> {
                        Instant expiry = opened.guestSession().joinedAt().plusSeconds(60);
                        jdbc.update("update guest_sessions set expires_at=? where id=?", Timestamp.from(expiry), opened.guestSession().id());
                        when(clock.instant()).thenReturn(expiry);
                    }
                    case "closingMain" -> jdbc.update("update table_sessions set status='CLOSING',closing_at=last_activity_at where id=?", opened.tableSession().id());
                    case "closeMain", "expireMain" -> jdbc.update("update table_sessions set status=?,closed_at=last_activity_at where id=?",
                            operation.equals("closeMain") ? "CLOSED" : "EXPIRED", opened.tableSession().id());
                    case "inactiveTable" -> jdbc.update("update dining_tables set is_active=false,operational_status='DISABLED' where id=?", code.tableId());
                    default -> throw new IllegalArgumentException("Unknown operation");
                }
                var future = executor.submit(() -> {
                    try { return validate(opened); }
                    catch (InvalidGuestSessionException exception) { return exception; }
                });
                awaitTableLock();
                return future;
            });
            assertThat(result).isNotNull();
            assertThat(result.get(15, TimeUnit.SECONDS)).isInstanceOf(InvalidGuestSessionException.class);
        }
    }

    @Test
    void scanWaitingForClosingCommitDoesNotCreateAnotherGuest() throws Exception {
        var code = create("1");
        var opened = opening.open(code.qrToken());
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Object> result = new TransactionTemplate(transactions).execute(transaction -> {
                tableRepository.findForQrManagement(code.restaurantId(), code.tableId()).orElseThrow();
                jdbc.update("update table_sessions set status='CLOSING',closing_at=last_activity_at where id=?", opened.tableSession().id());
                var future = executor.submit(() -> {
                    try { return opening.open(code.qrToken()); }
                    catch (TableSessionConflictException exception) { return exception; }
                });
                awaitTableLock();
                return future;
            });
            assertThat(result).isNotNull();
            assertThat(result.get(15, TimeUnit.SECONDS)).isInstanceOf(TableSessionConflictException.class);
        }
        assertThat(count(code.tableId())).isEqualTo(1);
    }

    private void awaitTableLock() {
        await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%dining_tables%' and pid<>pg_backend_pid())", Boolean.class)));
    }
}
