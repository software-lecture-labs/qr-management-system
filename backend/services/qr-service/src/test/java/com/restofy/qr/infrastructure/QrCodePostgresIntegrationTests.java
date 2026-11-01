package com.restofy.qr.infrastructure;

import com.restofy.qr.api.controller.QrCodeController;
import com.restofy.qr.api.dto.request.CreateTableRequest;
import com.restofy.qr.api.dto.request.ChangeTableActiveRequest;
import com.restofy.qr.api.dto.response.CreatedQrCodeResponse;
import com.restofy.qr.api.exception.ApiExceptionHandler;
import com.restofy.qr.application.service.QrCodeCreationService;
import com.restofy.qr.application.service.QrCodeLifecycleService;
import com.restofy.qr.application.service.TableManagementService;
import com.restofy.qr.domain.entity.QrCode;
import com.restofy.qr.domain.exception.QrCodeConflictException;
import com.restofy.qr.domain.exception.TableNotFoundException;
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
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class QrCodePostgresIntegrationTests {
    @Autowired QrCodeCreationService service;
    @Autowired QrCodeLifecycleService lifecycle;
    @Autowired TableManagementService tables;
    @Autowired QrCodeRepository codes;
    @Autowired DiningTableRepository tableRepository;
    @Autowired QrPayloadCodec codec;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RequestMappingHandlerAdapter adapter;
    // Test-only tenant fixture. The production security chain is tested separately.
    @MockitoBean TableManagementAccess access;
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

    private String path(UUID tableId) {
        return "/api/v1/qr/tables/" + tableId + "/qr-codes";
    }

    @Test
    void appliesForwardMigrationWithCompositeFkAndPartialUniqueIndex() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("121");
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank", String.class))
                .containsExactly("21", "22", "26", "27", "28", "29", "30", "121");
        assertThat(jdbc.queryForList("select indexname from pg_indexes where tablename='qr_codes'", String.class))
                .contains("qr_codes_pkey", "uq_qr_codes_token_digest", "uq_qr_codes_active_table");
    }

    @Test
    void createsDigestAndAuthenticatedCiphertextWithoutStoringRawCredential() {
        UUID table = createTable("1");
        var response = service.create(table);
        var saved = codes.findById(response.id()).orElseThrow();
        assertThat(response.restaurantId()).isEqualTo(restaurant.get());
        assertThat(response.tableId()).isEqualTo(table);
        assertThat(response.status().name()).isEqualTo("ACTIVE");
        assertThat(response.version()).isZero();
        assertThat(response.issuedAt()).isNotNull();
        assertThat(saved.getTokenDigest()).isEqualTo(codec.digest(response.qrToken())).isNotEqualTo(response.qrToken());
        assertThat(saved.getEncryptedPayload()).doesNotContain(response.qrToken());
        assertThat(codec.decrypt(saved.getEncryptedPayload(), restaurant.get(), table, response.id())).isEqualTo(response.qrToken());
        var other = service.create(createTable("2"));
        assertThat(other.id()).isNotEqualTo(response.id());
        assertThat(other.qrToken()).isNotEqualTo(response.qrToken());
    }

    @Test
    void duplicateCreateDoesNotRotateOrOverwriteExistingCode() {
        UUID table = createTable("1");
        var first = service.create(table);
        assertThatThrownBy(() -> service.create(table)).isInstanceOf(QrCodeConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from qr_codes where restaurant_id=? and table_id=?", Integer.class,
                restaurant.get(), table)).isEqualTo(1);
        assertThat(codes.findById(first.id()).orElseThrow().getTokenDigest()).isEqualTo(codec.digest(first.qrToken()));
    }

    @Test
    void inactiveAndMissingTablesCannotReceiveQrCode() {
        UUID table = createTable("1");
        tables.changeActive(table, new ChangeTableActiveRequest(false, 0L));
        assertThatThrownBy(() -> service.create(table)).isInstanceOf(QrCodeConflictException.class);
        UUID absent = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(absent)).isInstanceOf(TableNotFoundException.class);
        assertThat(jdbc.queryForObject("select count(*) from qr_codes where restaurant_id=?", Integer.class, restaurant.get())).isZero();
    }

    @Test
    void tenantIsRequiredAndForeignTableIsIndistinguishableFromMissingTable() throws Exception {
        UUID table = createTable("1");
        restaurant.set(UUID.randomUUID());
        assertThatThrownBy(() -> service.create(table)).isInstanceOf(TableNotFoundException.class);
        api.perform(post(path(table))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Table not found."));
        when(access.requireRestaurantId()).thenThrow(new AuthenticationCredentialsNotFoundException("test-only"));
        assertThatThrownBy(() -> service.create(table)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        api.perform(post(path(table))).andExpect(status().isUnauthorized());
    }

    @Test
    void databaseRejectsCrossTenantFkAndMissingParent() {
        UUID table = createTable("1");
        UUID other = UUID.randomUUID();
        var foreign = codeFor(other, table, codec.generateToken());
        assertThatThrownBy(() -> codes.saveAndFlush(foreign)).isInstanceOf(DataIntegrityViolationException.class);
        var missing = codeFor(restaurant.get(), UUID.randomUUID(), codec.generateToken());
        assertThatThrownBy(() -> codes.saveAndFlush(missing)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseEnforcesActiveTableAndDigestUniquenessEvenWhenServiceIsBypassed() {
        UUID table = createTable("1");
        var first = service.create(table);
        var duplicateTable = codeFor(restaurant.get(), table, codec.generateToken());
        assertThatThrownBy(() -> codes.saveAndFlush(duplicateTable)).isInstanceOf(DataIntegrityViolationException.class);
        UUID otherTable = createTable("2");
        var duplicateDigest = codeFor(restaurant.get(), otherTable, first.qrToken());
        assertThatThrownBy(() -> codes.saveAndFlush(duplicateDigest)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from qr_codes where restaurant_id=?", Integer.class, restaurant.get())).isEqualTo(1);
    }

    private QrCode codeFor(UUID tenant, UUID table, String token) {
        UUID id = UUID.randomUUID();
        return new QrCode(id, tenant, table, codec.digest(token), codec.encrypt(token, tenant, table, id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"token_digest='raw-token'", "token_digest=NULL", "encrypted_payload='plaintext'",
            "encrypted_payload=NULL", "status='UNKNOWN'", "version=-1", "issued_at=NULL"})
    void postgresRejectsMalformedStorageAndUnknownState(String assignment) {
        var code = service.create(createTable("1"));
        UUID id = code.id();
        String statement = "update qr_codes set " + assignment + " where id=?";
        assertThatThrownBy(() -> jdbc.update(statement, id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentCreatesHaveOneWinnerAndOneControlledConflict() throws Exception {
        UUID table = createTable("1");
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Object> create = () -> {
                start.await(10, TimeUnit.SECONDS);
                try { return service.create(table); }
                catch (QrCodeConflictException exception) { return exception; }
            };
            var first = executor.submit(create);
            var second = executor.submit(create);
            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results.stream().filter(CreatedQrCodeResponse.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(QrCodeConflictException.class::isInstance)).hasSize(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from qr_codes where restaurant_id=? and table_id=?", Integer.class,
                restaurant.get(), table)).isEqualTo(1);
    }

    @Test
    void concurrentDisableCommitsBeforeCreationCanReadActiveState() throws Exception {
        UUID table = createTable("1");
        var started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Object> creation = new TransactionTemplate(transactions).execute(transaction -> {
                var locked = tableRepository.findForQrManagement(restaurant.get(), table).orElseThrow();
                locked.changeActive(false);
                tableRepository.flush();
                var future = executor.submit(() -> {
                    started.countDown();
                    try { return service.create(table); }
                    catch (QrCodeConflictException exception) { return exception; }
                });
                try { assertThat(started.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return future;
            });
            assertThat(creation.get(20, TimeUnit.SECONDS)).isInstanceOf(QrCodeConflictException.class);
        }
        assertThat(jdbc.queryForObject("select count(*) from qr_codes where restaurant_id=?", Integer.class, restaurant.get())).isZero();
    }

    @Test
    void httpCreationReturns201NoStoreAndNoDigestOrCiphertext() throws Exception {
        UUID table = createTable("1");
        api.perform(post(path(table)).header("X-Restaurant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.tableId").value(table.toString()))
                .andExpect(jsonPath("$.restaurantId").value(restaurant.get().toString()))
                .andExpect(jsonPath("$.qrToken").value(matchesPattern("[A-Za-z0-9_-]{43}")))
                .andExpect(jsonPath("$.tokenDigest").doesNotExist()).andExpect(jsonPath("$.encryptedPayload").doesNotExist());
        api.perform(post(path(table))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("QR operation conflicts with the current table or QR state."))
                .andExpect(jsonPath("$.qrToken").doesNotExist()).andExpect(jsonPath("$.trace").doesNotExist());
        api.perform(post("/api/v1/qr/tables/not-a-uuid/qr-codes")).andExpect(status().isBadRequest());
        api.perform(post(path(UUID.randomUUID()))).andExpect(status().isNotFound());
    }
}
