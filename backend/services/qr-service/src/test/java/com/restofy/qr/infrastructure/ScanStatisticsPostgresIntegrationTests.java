package com.restofy.qr.infrastructure;

import com.restofy.qr.api.controller.TableSessionStatisticsController;
import com.restofy.qr.api.dto.request.*;
import com.restofy.qr.api.dto.response.*;
import com.restofy.qr.api.exception.ApiExceptionHandler;
import com.restofy.qr.application.service.*;
import com.restofy.qr.domain.entity.GuestSession;
import com.restofy.qr.domain.entity.QrScanEvent;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.exception.*;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class ScanStatisticsPostgresIntegrationTests {
    @Autowired TableSessionStatisticsService statistics;
    @Autowired GuestSessionOpeningService opening;
    @Autowired TableSessionOpeningService mainOpening;
    @Autowired QrCodeCreationService creation;
    @Autowired QrCodeLifecycleService lifecycle;
    @Autowired TableManagementService tables;
    @Autowired DiningTableRepository tableRepository;
    @Autowired GuestSessionRepository guests;
    @Autowired QrScanEventRepository scans;
    @Autowired GuestTokenCodec tokens;
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
        api = MockMvcBuilders.standaloneSetup(new TableSessionStatisticsController(statistics))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(adapter.getMessageConverters().toArray(HttpMessageConverter[]::new)).build();
    }

    private CreatedQrCodeResponse create(String number) {
        var code = creation.create(tables.create(new CreateTableRequest(number, null, null)).id());
        when(clock.instant()).thenReturn(code.issuedAt().plusSeconds(1).truncatedTo(ChronoUnit.MICROS));
        return code;
    }

    @Test
    void emptyTableAndRepeatedScansReturnRealSeparateCountsWithoutCredentialsOrWrites() throws Exception {
        var code = create("1");
        var empty = statistics.get(code.tableId());
        assertThat(empty.liveSessionId()).isNull();
        assertThat(empty.activeGuestCount()).isZero();
        assertThat(empty.sessionAcceptedScanCount()).isZero();
        assertThat(empty.tableAcceptedScanCount()).isZero();
        var first = opening.open(code.qrToken());
        opening.open(code.qrToken());
        var before = guests.findById(first.guestSession().id()).orElseThrow();
        api.perform(get("/api/v1/qr/tables/{id}/session-statistics", code.tableId())
                .header("X-Restaurant-Id", "forged").header("X-Role", "ADMIN"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.restaurantId").value(code.restaurantId().toString()))
                .andExpect(jsonPath("$.liveSessionId").value(first.tableSession().id().toString()))
                .andExpect(jsonPath("$.liveSessionStatus").value("OPEN"))
                .andExpect(jsonPath("$.activeGuestCount").value(2))
                .andExpect(jsonPath("$.sessionAcceptedScanCount").value(2))
                .andExpect(jsonPath("$.tableAcceptedScanCount").value(2))
                .andExpect(jsonPath("$.guestToken").doesNotExist()).andExpect(jsonPath("$.guestSessionIds").doesNotExist());
        var after = guests.findById(first.guestSession().id()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getLastActivityAt()).isEqualTo(before.getLastActivityAt());
        assertThat(tables.get(code.tableId()).version()).isEqualTo(1);
        assertThat(scans.countByRestaurantIdAndTableId(code.restaurantId(), code.tableId())).isEqualTo(2);
        var event = jdbc.queryForMap("select * from qr_scan_events where guest_session_id=?", first.guestSession().id());
        assertThat(event).containsEntry("qr_code_id", code.id()).containsEntry("table_session_id", first.tableSession().id())
                .containsEntry("table_id", code.tableId()).containsEntry("outcome", "ACCEPTED");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='qr_scan_events'", String.class))
                .containsExactlyInAnyOrder("id", "restaurant_id", "table_id", "qr_code_id", "table_session_id", "guest_session_id", "scanned_at", "outcome");
    }

    @Test
    void countsAndExistenceStayInsideTheVerifiedRestaurantAndTable() throws Exception {
        var first = create("1");
        var second = create("2");
        opening.open(first.qrToken());
        opening.open(first.qrToken());
        opening.open(second.qrToken());
        assertThat(statistics.get(first.tableId()).activeGuestCount()).isEqualTo(2);
        assertThat(statistics.get(second.tableId()).tableAcceptedScanCount()).isEqualTo(1);
        restaurant.set(UUID.randomUUID());
        var other = create("1");
        opening.open(other.qrToken());
        assertThat(statistics.get(other.tableId()).tableAcceptedScanCount()).isEqualTo(1);
        api.perform(get("/api/v1/qr/tables/{id}/session-statistics", first.tableId())).andExpect(status().isNotFound());
        UUID absent = UUID.randomUUID();
        assertThatThrownBy(() -> statistics.get(absent)).isInstanceOf(TableNotFoundException.class);
        api.perform(get("/api/v1/qr/tables/not-a-uuid/session-statistics")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENDED", "EXPIRED", "expiredTime", "futureJoin", "futureActivity"})
    void inactiveOrTimeInvalidGuestsDoNotReduceHistoricalScanCounts(String state) {
        var code = create("1");
        var first = opening.open(code.qrToken());
        opening.open(code.qrToken());
        UUID guest = first.guestSession().id();
        switch (state) {
            case "ENDED", "EXPIRED" -> jdbc.update("update guest_sessions set status=?,ended_at=last_activity_at where id=?", state, guest);
            case "expiredTime" -> {
                Instant expiry = first.guestSession().joinedAt().plusSeconds(60);
                jdbc.update("update guest_sessions set expires_at=? where id=?", Timestamp.from(expiry), guest);
                when(clock.instant()).thenReturn(expiry.minusNanos(1));
                assertThat(statistics.get(code.tableId()).activeGuestCount()).isEqualTo(2);
                when(clock.instant()).thenReturn(expiry);
            }
            case "futureJoin" -> jdbc.update("update guest_sessions set joined_at=joined_at+interval '1 minute',last_activity_at=last_activity_at+interval '1 minute' where id=?", guest);
            case "futureActivity" -> jdbc.update("update guest_sessions set last_activity_at=last_activity_at+interval '1 minute' where id=?", guest);
            default -> throw new IllegalArgumentException("Unknown state");
        }
        var result = statistics.get(code.tableId());
        assertThat(result.activeGuestCount()).isEqualTo(1);
        assertThat(result.sessionAcceptedScanCount()).isEqualTo(2);
        assertThat(result.tableAcceptedScanCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSING", "CLOSED", "EXPIRED", "idleBoundary", "futureMainActivity", "inactiveTable", "cleaning"})
    void unusableParentOrTableMakesActiveCountZeroWhilePreservingRecordedScans(String state) {
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
            }
            case "futureMainActivity" -> jdbc.update("update table_sessions set last_activity_at=last_activity_at+interval '1 minute' where id=?", main);
            case "inactiveTable" -> jdbc.update("update dining_tables set is_active=false,operational_status='DISABLED' where id=?", code.tableId());
            case "cleaning" -> jdbc.update("update dining_tables set operational_status='CLEANING' where id=?", code.tableId());
            default -> throw new IllegalArgumentException("Unknown state");
        }
        var result = statistics.get(code.tableId());
        assertThat(result.activeGuestCount()).isZero();
        assertThat(result.tableAcceptedScanCount()).isEqualTo(1);
        assertThat(result.sessionAcceptedScanCount()).isEqualTo(state.equals("CLOSED") || state.equals("EXPIRED") ? 0 : 1);
    }

    @Test
    void aNewMainHasItsOwnCountsAndOlderUnrecordedGuestsAreNotFabricatedAsScans() {
        var code = create("1");
        var previous = opening.open(code.qrToken());
        jdbc.update("update table_sessions set status='CLOSED',closed_at=last_activity_at where id=?", previous.tableSession().id());
        jdbc.update("update dining_tables set operational_status='AVAILABLE' where id=?", code.tableId());
        var next = opening.open(code.qrToken());
        var current = statistics.get(code.tableId());
        assertThat(current.liveSessionId()).isEqualTo(next.tableSession().id()).isNotEqualTo(previous.tableSession().id());
        assertThat(current.activeGuestCount()).isEqualTo(1);
        assertThat(current.sessionAcceptedScanCount()).isEqualTo(1);
        assertThat(current.tableAcceptedScanCount()).isEqualTo(2);
        var legacyCode = create("legacy");
        var legacyMain = mainOpening.open(legacyCode.qrToken()).session();
        guests.saveAndFlush(new GuestSession(legacyMain.restaurantId(), legacyMain.id(), tokens.digest(tokens.generateToken()), legacyMain.openedAt()));
        var legacy = statistics.get(legacyCode.tableId());
        assertThat(legacy.activeGuestCount()).isEqualTo(1);
        assertThat(legacy.tableAcceptedScanCount()).isZero();
    }

    @Test
    void rotationKeepsAcceptedHistoryAndReferencedQrDeletionIsRejected() {
        var code = create("1");
        opening.open(code.qrToken());
        var replacement = creation.rotate(code.tableId(), code.id(), new QrCodeVersionRequest(0L));
        when(clock.instant()).thenReturn(replacement.issuedAt().plusSeconds(1).truncatedTo(ChronoUnit.MICROS));
        opening.open(replacement.qrToken());
        assertThat(statistics.get(code.tableId()).tableAcceptedScanCount()).isEqualTo(2);
        UUID tableId = code.tableId();
        UUID qrId = code.id();
        var version = new QrCodeVersionRequest(1L);
        assertThatThrownBy(() -> lifecycle.delete(tableId, qrId, version)).isInstanceOf(DataIntegrityViolationException.class);
        var unused = create("unused");
        lifecycle.delete(unused.tableId(), unused.id(), new QrCodeVersionRequest(0L));
        assertThat(statistics.get(unused.tableId()).tableAcceptedScanCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactiveQr", "inactiveTable", "revoked", "deleted", "expired", "unknown"})
    void rejectedQrRequestsNeverCreateAcceptedEventsOrInflateCounts(String state) {
        var code = create("1");
        String token = code.qrToken();
        switch (state) {
            case "inactiveQr" -> lifecycle.changeActive(code.tableId(), code.id(), new ChangeQrCodeActiveRequest(false, 0L));
            case "inactiveTable" -> tables.changeActive(code.tableId(), new ChangeTableActiveRequest(false, 0L));
            case "revoked" -> creation.rotate(code.tableId(), code.id(), new QrCodeVersionRequest(0L));
            case "deleted" -> lifecycle.delete(code.tableId(), code.id(), new QrCodeVersionRequest(0L));
            case "expired" -> {
                Instant expiry = code.issuedAt().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
                jdbc.update("update qr_codes set expires_at=? where id=?", Timestamp.from(expiry), code.id());
                when(clock.instant()).thenReturn(expiry);
            }
            case "unknown" -> token = "A".repeat(43);
            default -> throw new IllegalArgumentException("Unknown state");
        }
        String credential = token;
        assertThatThrownBy(() -> opening.open(credential)).isInstanceOf(InvalidQrCodeException.class);
        assertThat(statistics.get(code.tableId()).tableAcceptedScanCount()).isZero();
        assertThat(statistics.get(code.tableId()).activeGuestCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scanInsertFailureRollsBackGuestAndFirstMainWithoutChangingExistingCounts(boolean existing) {
        var code = create("1");
        UUID allowed = existing ? jdbcEventId(opening.open(code.qrToken()).guestSession().id()) : UUID.randomUUID();
        String name = "ck_test_scan_" + code.tableId().toString().replace("-", "");
        jdbc.execute("alter table qr_scan_events add constraint " + name + " check(restaurant_id <> '" + code.restaurantId() + "'::uuid or id = '" + allowed + "'::uuid)");
        String token = code.qrToken();
        assertThatThrownBy(() -> opening.open(token)).isInstanceOf(DataIntegrityViolationException.class);
        var result = statistics.get(code.tableId());
        assertThat(result.activeGuestCount()).isEqualTo(existing ? 1 : 0);
        assertThat(result.tableAcceptedScanCount()).isEqualTo(existing ? 1 : 0);
        assertThat(jdbc.queryForObject("select count(*) from guest_sessions where restaurant_id=?", Long.class, code.restaurantId())).isEqualTo(existing ? 1 : 0);
        assertThat(tables.get(code.tableId()).version()).isEqualTo(existing ? 1 : 0);
        assertThat(tables.get(code.tableId()).operationalStatus()).isEqualTo(existing ? TableOperationalStatus.OCCUPIED : TableOperationalStatus.AVAILABLE);
    }

    private UUID jdbcEventId(UUID guestId) {
        return jdbc.queryForObject("select id from qr_scan_events where guest_session_id=?", UUID.class, guestId);
    }

    @Test
    void concurrentScansKeepMainGuestAndEventCountsAligned() throws Exception {
        var code = create("1");
        var start = new CyclicBarrier(8);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<OpenedGuestSessionResponse>>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return opening.open(code.qrToken()); }));
            }
            for (var future : futures) { assertThat(future.get(20, TimeUnit.SECONDS).tableSession().id()).isNotNull(); }
        }
        var result = statistics.get(code.tableId());
        assertThat(result.activeGuestCount()).isEqualTo(8);
        assertThat(result.sessionAcceptedScanCount()).isEqualTo(8);
        assertThat(result.tableAcceptedScanCount()).isEqualTo(8);
    }

    @Test
    void statisticsDoesNotWaitForAnUncommittedScanAndNeverObservesAPartialGuestEvent() throws Exception {
        var code = create("1");
        UUID tableId = code.tableId();
        var orderState = mainOpening.prepare(code.qrToken());
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                opening.openLocked(code.qrToken(), orderState);
                var future = executor.submit(() -> statistics.get(tableId));
                try {
                    // The writer still holds its row lock. Readers see the entire previous commit.
                    var counts = future.get(10, TimeUnit.SECONDS);
                    assertThat(counts.liveSessionId()).isNull();
                    assertThat(counts.activeGuestCount()).isZero();
                    assertThat(counts.sessionAcceptedScanCount()).isZero();
                    assertThat(counts.tableAcceptedScanCount()).isZero();
                } catch (Exception exception) {
                    throw new AssertionError("Statistics must complete while the scan transaction remains open.", exception);
                }
            });
            var counts = statistics.get(tableId);
            assertThat(counts.activeGuestCount()).isEqualTo(1);
            assertThat(counts.sessionAcceptedScanCount()).isEqualTo(1);
            assertThat(counts.tableAcceptedScanCount()).isEqualTo(1);
        }
    }

    @Test
    void statisticsKeepsOneSnapshotWhenAScanCommitsBetweenItsQueriesEvenInsideACallerTransaction() throws Exception {
        var code = create("1");
        var now = code.issuedAt().plusSeconds(1).truncatedTo(ChronoUnit.MICROS);
        var reader = new AtomicReference<Thread>();
        var firstQueryCompleted = new CountDownLatch(1);
        var writerCommitted = new CountDownLatch(1);
        when(clock.instant()).thenAnswer(invocation -> {
            if (Thread.currentThread() == reader.get()) {
                firstQueryCompleted.countDown();
                assertThat(writerCommitted.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return now;
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> new TransactionTemplate(transactions).execute(transaction -> {
                reader.set(Thread.currentThread());
                return statistics.get(code.tableId());
            }));
            try {
                assertThat(firstQueryCompleted.await(10, TimeUnit.SECONDS)).isTrue();
                opening.open(code.qrToken());
            } finally {
                writerCommitted.countDown();
            }
            var before = future.get(15, TimeUnit.SECONDS);
            assertThat(before.liveSessionId()).isNull();
            assertThat(before.activeGuestCount()).isZero();
            assertThat(before.sessionAcceptedScanCount()).isZero();
            assertThat(before.tableAcceptedScanCount()).isZero();
            var after = statistics.get(code.tableId());
            assertThat(after.liveSessionId()).isNotNull();
            assertThat(after.activeGuestCount()).isEqualTo(1);
            assertThat(after.sessionAcceptedScanCount()).isEqualTo(1);
            assertThat(after.tableAcceptedScanCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"qr", "main", "guest", "tenant"})
    void compositeForeignKeysRejectWrongTableTenantAndGuestChains(String mismatch) {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("121");
        var firstCode = create("1");
        var secondCode = create("2");
        var first = opening.open(firstCode.qrToken());
        var second = opening.open(secondCode.qrToken());
        var unused = guests.saveAndFlush(new GuestSession(firstCode.restaurantId(), first.tableSession().id(), tokens.digest(tokens.generateToken()), first.guestSession().joinedAt()));
        var unusedOther = guests.saveAndFlush(new GuestSession(secondCode.restaurantId(), second.tableSession().id(), tokens.digest(tokens.generateToken()), second.guestSession().joinedAt()));
        UUID tenant = mismatch.equals("tenant") ? UUID.randomUUID() : firstCode.restaurantId();
        UUID qr = mismatch.equals("qr") ? secondCode.id() : firstCode.id();
        UUID main = mismatch.equals("main") ? second.tableSession().id() : first.tableSession().id();
        UUID guest = mismatch.equals("guest") ? unusedOther.getId() : unused.getId();
        var event = new QrScanEvent(tenant, firstCode.tableId(), qr, main, guest, first.guestSession().joinedAt());
        assertThatThrownBy(() -> scans.saveAndFlush(event)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateGuestEventAndInvalidOutcomeOrInfiniteTimeAreRejected() {
        var code = create("1");
        var opened = opening.open(code.qrToken());
        var event = new QrScanEvent(code.restaurantId(), code.tableId(), code.id(), opened.tableSession().id(), opened.guestSession().id(), opened.guestSession().joinedAt());
        assertThatThrownBy(() -> scans.saveAndFlush(event)).isInstanceOf(DataIntegrityViolationException.class);
        UUID eventId = jdbcEventId(opened.guestSession().id());
        assertThatThrownBy(() -> jdbc.update("update qr_scan_events set outcome='FAILED' where id=?", eventId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update qr_scan_events set scanned_at='infinity' where id=?", eventId)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
