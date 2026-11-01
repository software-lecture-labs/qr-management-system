package com.restofy.qr.infrastructure;

import com.restofy.qr.api.controller.QrCodeController;
import com.restofy.qr.api.dto.request.*;
import com.restofy.qr.api.dto.response.CreatedQrCodeResponse;
import com.restofy.qr.api.exception.ApiExceptionHandler;
import com.restofy.qr.application.service.QrCodeCreationService;
import com.restofy.qr.application.service.QrCodeLifecycleService;
import com.restofy.qr.application.service.TableManagementService;
import com.restofy.qr.domain.enums.QrCodeStatus;
import com.restofy.qr.domain.exception.*;
import com.restofy.qr.infrastructure.persistence.repository.DiningTableRepository;
import com.restofy.qr.infrastructure.persistence.repository.QrCodeRepository;
import com.restofy.qr.infrastructure.security.QrPayloadCodec;
import com.restofy.qr.infrastructure.security.TableManagementAccess;
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
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class QrRotationPostgresIntegrationTests {
    @Autowired QrCodeCreationService service;
    @Autowired QrCodeLifecycleService lifecycle;
    @Autowired TableManagementService tables;
    @Autowired QrCodeRepository codes;
    @Autowired DiningTableRepository tableRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RequestMappingHandlerAdapter adapter;
    // Identity exists only in this test harness. Real production APIs remain closed.
    @MockitoBean TableManagementAccess access;
    // Real crypto; only token generation is overridden in the rollback failure test.
    @MockitoSpyBean QrPayloadCodec codec;
    private final AtomicReference<UUID> restaurant = new AtomicReference<>();
    private MockMvc api;

    @BeforeEach
    void setup() {
        restaurant.set(UUID.randomUUID());
        when(access.requireRestaurantId()).thenAnswer(invocation -> restaurant.get());
        api = MockMvcBuilders.standaloneSetup(new QrCodeController(service, lifecycle))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(adapter.getMessageConverters().toArray(HttpMessageConverter[]::new)).build();
    }

    private UUID createTable(String number) {
        return tables.create(new CreateTableRequest(number, null, null)).id();
    }

    private CreatedQrCodeResponse rotate(CreatedQrCodeResponse previous) {
        return service.rotate(previous.tableId(), previous.id(), new QrCodeVersionRequest(previous.version()));
    }

    private String path(UUID table, UUID qr) {
        return "/api/v1/qr/tables/" + table + "/qr-codes/" + qr + "/rotate";
    }

    private int activeDigestCount(String token) {
        // Storage eligibility check only. Public scan/guest validation belongs to QR-03.1.
        return jdbc.queryForObject("select count(*) from qr_codes where token_digest=? and status='ACTIVE'",
                Integer.class, codec.digest(token));
    }

    @Test
    void migrationPreservesUniqueIndexAndAddsRevocationConsistency() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("121");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='22' and success", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname='uq_qr_codes_active_table'", String.class))
                .contains("UNIQUE", "restaurant_id, table_id", "ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conname='ck_qr_codes_revocation'", Integer.class)).isEqualTo(1);
    }

    @Test
    void rotationRevokesOldDigestAndIssuesDifferentRecoverableCredential() {
        var first = service.create(createTable("1"));
        assertThat(activeDigestCount(first.qrToken())).isEqualTo(1);
        var replacement = rotate(first);
        var revoked = codes.findById(first.id()).orElseThrow();
        var active = codes.findById(replacement.id()).orElseThrow();
        assertThat(revoked.getStatus()).isEqualTo(QrCodeStatus.REVOKED);
        assertThat(revoked.getVersion()).isEqualTo(first.version() + 1);
        assertThat(revoked.getRevokedAt()).isAfterOrEqualTo(revoked.getIssuedAt());
        assertThat(revoked.getTokenDigest()).isEqualTo(codec.digest(first.qrToken()));
        assertThat(active.getStatus()).isEqualTo(QrCodeStatus.ACTIVE);
        assertThat(active.getRevokedAt()).isNull();
        assertThat(replacement.id()).isNotEqualTo(first.id());
        assertThat(replacement.qrToken()).isNotEqualTo(first.qrToken());
        assertThat(replacement.tableId()).isEqualTo(first.tableId());
        assertThat(replacement.restaurantId()).isEqualTo(first.restaurantId());
        assertThat(replacement.version()).isZero();
        assertThat(codec.decrypt(active.getEncryptedPayload(), active.getRestaurantId(), active.getTableId(), active.getId()))
                .isEqualTo(replacement.qrToken());
        assertThat(activeDigestCount(first.qrToken())).isZero();
        assertThat(activeDigestCount(replacement.qrToken())).isEqualTo(1);
    }

    @Test
    void staleOrRepeatedRotationCannotRevokeTheReplacement() {
        var first = service.create(createTable("1"));
        UUID table = first.tableId();
        UUID qr = first.id();
        var stale = new QrCodeVersionRequest(99L);
        assertThatThrownBy(() -> service.rotate(table, qr, stale)).isInstanceOf(QrCodeConflictException.class);
        assertThat(codes.findById(qr).orElseThrow().getStatus()).isEqualTo(QrCodeStatus.ACTIVE);
        var replacement = rotate(first);
        assertThatThrownBy(() -> rotate(first)).isInstanceOf(QrCodeConflictException.class);
        var revokedVersion = new QrCodeVersionRequest(1L);
        assertThatThrownBy(() -> service.rotate(table, qr, revokedVersion)).isInstanceOf(QrCodeConflictException.class);
        assertThat(activeDigestCount(replacement.qrToken())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from qr_codes where restaurant_id=?", Integer.class, restaurant.get())).isEqualTo(2);
    }

    @Test
    void missingForeignAndWrongTableQrIdsCannotBeRotated() {
        var first = service.create(createTable("1"));
        UUID table = first.tableId();
        UUID qr = first.id();
        UUID otherTable = createTable("2");
        UUID absent = UUID.randomUUID();
        var request = new QrCodeVersionRequest(0L);
        assertThatThrownBy(() -> service.rotate(table, absent, request)).isInstanceOf(QrCodeNotFoundException.class);
        assertThatThrownBy(() -> service.rotate(otherTable, qr, request)).isInstanceOf(QrCodeNotFoundException.class);
        assertThatThrownBy(() -> service.rotate(absent, qr, request)).isInstanceOf(TableNotFoundException.class);
        restaurant.set(UUID.randomUUID());
        assertThatThrownBy(() -> service.rotate(table, qr, request)).isInstanceOf(TableNotFoundException.class);
        UUID ownTable = createTable("1");
        assertThatThrownBy(() -> service.rotate(ownTable, qr, request)).isInstanceOf(QrCodeNotFoundException.class);
        assertThat(codes.findById(qr).orElseThrow().getStatus()).isEqualTo(QrCodeStatus.ACTIVE);
    }

    @Test
    void inactiveTableRejectsRotationWithoutChangingTheOldCode() {
        var first = service.create(createTable("1"));
        tables.changeActive(first.tableId(), new ChangeTableActiveRequest(false, 0L));
        assertThatThrownBy(() -> rotate(first)).isInstanceOf(QrCodeConflictException.class);
        assertThat(codes.findById(first.id()).orElseThrow().getStatus()).isEqualTo(QrCodeStatus.ACTIVE);
    }

    @Test
    void failedReplacementInsertRollsBackTheFlushedRevocation() {
        var first = service.create(createTable("1"));
        var other = service.create(createTable("2"));
        doReturn(other.qrToken()).when(codec).generateToken();
        assertThatThrownBy(() -> rotate(first)).isInstanceOf(DataIntegrityViolationException.class);
        var retained = codes.findById(first.id()).orElseThrow();
        assertThat(retained.getStatus()).isEqualTo(QrCodeStatus.ACTIVE);
        assertThat(retained.getVersion()).isEqualTo(first.version());
        assertThat(retained.getRevokedAt()).isNull();
        assertThat(activeDigestCount(first.qrToken())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from qr_codes where restaurant_id=? and table_id=?", Integer.class,
                restaurant.get(), first.tableId())).isEqualTo(1);
        doCallRealMethod().when(codec).generateToken();
        assertThat(rotate(first).id()).isNotEqualTo(first.id());
    }

    @Test
    void repeatedRotationsKeepHistoryButDatabaseRejectsASecondActiveCredential() {
        var first = service.create(createTable("1"));
        var second = rotate(first);
        var third = rotate(second);
        assertThat(activeDigestCount(first.qrToken())).isZero();
        assertThat(activeDigestCount(second.qrToken())).isZero();
        assertThat(activeDigestCount(third.qrToken())).isEqualTo(1);
        UUID firstId = first.id();
        assertThatThrownBy(() -> jdbc.update("update qr_codes set status='ACTIVE', revoked_at=NULL where id=?", firstId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from qr_codes where restaurant_id=? and status='REVOKED'", Integer.class,
                restaurant.get())).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoked_at=issued_at", "status='REVOKED'", "status='REVOKED', revoked_at=issued_at - interval '1 second'"})
    void postgresRejectsInconsistentRevocationState(String assignment) {
        var first = service.create(createTable("1"));
        UUID id = first.id();
        String statement = "update qr_codes set " + assignment + " where id=?";
        assertThatThrownBy(() -> jdbc.update(statement, id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentRotationsOfSameQrHaveExactlyOneWinner() throws Exception {
        var first = service.create(createTable("1"));
        var results = race(() -> rotate(first), () -> rotate(first));
        assertThat(results.stream().filter(CreatedQrCodeResponse.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(QrCodeConflictException.class::isInstance)).hasSize(1);
        assertThat(codes.findById(first.id()).orElseThrow().getStatus()).isEqualTo(QrCodeStatus.REVOKED);
        assertThat(jdbc.queryForObject("select count(*) from qr_codes where restaurant_id=? and status='ACTIVE'", Integer.class,
                restaurant.get())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from qr_codes where restaurant_id=?", Integer.class, restaurant.get())).isEqualTo(2);
    }

    @Test
    void concurrentCreateCannotInterleaveBetweenRevokeAndReplacement() throws Exception {
        var first = service.create(createTable("1"));
        UUID table = first.tableId();
        var results = race(() -> rotate(first), () -> service.create(table));
        assertThat(results.stream().filter(CreatedQrCodeResponse.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(QrCodeConflictException.class::isInstance)).hasSize(1);
        assertThat(codes.findById(first.id()).orElseThrow().getStatus()).isEqualTo(QrCodeStatus.REVOKED);
        assertThat(jdbc.queryForObject("select count(*) from qr_codes where restaurant_id=?", Integer.class, restaurant.get())).isEqualTo(2);
    }

    @Test
    void realConcurrentJpaRevocationsRejectLostUpdate() throws Exception {
        var first = service.create(createTable("1"));
        UUID table = first.tableId();
        UUID id = first.id();
        var loaded = new CyclicBarrier(2);
        Callable<Object> revoke = () -> new TransactionTemplate(transactions).execute(transaction -> {
            var code = codes.findByRestaurantIdAndTableIdAndId(restaurant.get(), table, id).orElseThrow();
            await(loaded);
            code.revoke(0);
            codes.flush();
            return "revoked";
        });
        var results = race(revoke, revoke);
        assertThat(results.stream().filter(String.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(OptimisticLockingFailureException.class::isInstance)).hasSize(1);
        assertThat(codes.findById(id).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void concurrentDisableFirstPreventsRotationWithoutRevokingOldCode() throws Exception {
        var first = service.create(createTable("1"));
        var started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Object> rotation = new TransactionTemplate(transactions).execute(transaction -> {
                var table = tableRepository.findForQrManagement(restaurant.get(), first.tableId()).orElseThrow();
                table.changeActive(false);
                tableRepository.flush();
                var future = executor.submit(() -> {
                    started.countDown();
                    try { return rotate(first); }
                    catch (QrCodeConflictException exception) { return exception; }
                });
                try { assertThat(started.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return future;
            });
            assertThat(rotation.get(20, TimeUnit.SECONDS)).isInstanceOf(QrCodeConflictException.class);
        }
        assertThat(codes.findById(first.id()).orElseThrow().getStatus()).isEqualTo(QrCodeStatus.ACTIVE);
    }

    private static List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<Object>>();
            for (var command : List.of(first, second)) {
                results.add(executor.submit(() -> {
                    await(start);
                    try { return command.call(); }
                    catch (RuntimeException exception) { return exception; }
                }));
            }
            return List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void httpRotationHasSafeDtoNoStoreAndRejectsStaleAndRepeatedCommands() throws Exception {
        var first = service.create(createTable("1"));
        String path = path(first.tableId(), first.id());
        api.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"version\":99}"))
                .andExpect(status().isConflict());
        api.perform(post(path).header("X-Restaurant-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.restaurantId").value(restaurant.get().toString()))
                .andExpect(jsonPath("$.tableId").value(first.tableId().toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.qrToken").value(matchesPattern("[A-Za-z0-9_-]{43}")))
                .andExpect(jsonPath("$.tokenDigest").doesNotExist()).andExpect(jsonPath("$.encryptedPayload").doesNotExist());
        api.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.qrToken").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "null", "{\"version\":null}", "{\"version\":-1}", "{broken",
            "{\"version\":0,\"restaurantId\":\"forged\"}", "{\"version\":0,\"status\":\"ACTIVE\"}",
            "{\"version\":0,\"qrToken\":\"forged\"}", "{\"version\":0,\"role\":\"ADMIN\"}"})
    void httpRejectsInvalidBodiesAndMassAssignmentWithoutRevoking(String body) throws Exception {
        var first = service.create(createTable("1"));
        api.perform(post(path(first.tableId(), first.id())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.trace").doesNotExist());
        assertThat(codes.findById(first.id()).orElseThrow().getStatus()).isEqualTo(QrCodeStatus.ACTIVE);
    }

    @Test
    void httpHidesForeignIdsAndSanitizesErrors() throws Exception {
        var first = service.create(createTable("1"));
        UUID absent = UUID.randomUUID();
        api.perform(post(path(first.tableId(), absent)).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("QR code not found."));
        restaurant.set(UUID.randomUUID());
        api.perform(post(path(first.tableId(), first.id())).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Table not found."));
        when(access.requireRestaurantId()).thenThrow(new AuthenticationCredentialsNotFoundException("test-only-secret"));
        api.perform(post(path(first.tableId(), first.id())).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Verified management context is required."));
        api.perform(post("/api/v1/qr/tables/not-a-uuid/qr-codes/" + first.id() + "/rotate")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isBadRequest());
        api.perform(post("/api/v1/qr/tables/" + first.tableId() + "/qr-codes/not-a-uuid/rotate")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isBadRequest());
    }
}
