package com.restofy.qr.infrastructure;

import com.restofy.qr.api.controller.QrValidationController;
import com.restofy.qr.api.dto.request.*;
import com.restofy.qr.api.dto.response.CreatedQrCodeResponse;
import com.restofy.qr.api.exception.ApiExceptionHandler;
import com.restofy.qr.application.service.*;
import com.restofy.qr.domain.exception.InvalidQrCodeException;
import com.restofy.qr.infrastructure.persistence.repository.*;
import com.restofy.qr.infrastructure.security.TableManagementAccess;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
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
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class QrValidationPostgresIntegrationTests {
    @Autowired QrCodeValidationService validation;
    @Autowired QrCodeCreationService creation;
    @Autowired QrCodeLifecycleService lifecycle;
    @Autowired QrCodeQueryService queries;
    @Autowired TableManagementService tables;
    @Autowired QrCodeRepository codes;
    @Autowired DiningTableRepository tableRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RequestMappingHandlerAdapter adapter;
    // Test-only context for creating management fixtures, not for QR validation.
    @MockitoBean TableManagementAccess access;
    @MockitoBean Clock clock;
    private final AtomicReference<UUID> restaurant = new AtomicReference<>();
    private MockMvc api;

    @BeforeEach
    void setup() {
        restaurant.set(UUID.randomUUID());
        when(access.requireRestaurantId()).thenAnswer(invocation -> restaurant.get());
        when(clock.instant()).thenAnswer(invocation -> Instant.now());
        api = MockMvcBuilders.standaloneSetup(new QrValidationController(validation))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(adapter.getMessageConverters().toArray(HttpMessageConverter[]::new)).build();
    }

    private CreatedQrCodeResponse create(String number) {
        return creation.create(tables.create(new CreateTableRequest(number, "Test table", null)).id());
    }

    private String body(String token) {
        return "{\"qrToken\":\"" + token + "\"}";
    }

    @Test
    void resolvesServerOwnedContextWithoutCreatingSessionsOrChangingQrOrTable() throws Exception {
        var code = create("1");
        var before = codes.findById(code.id()).orElseThrow();
        Instant checked = Instant.now().plusSeconds(1);
        when(clock.instant()).thenReturn(checked);
        for (int call = 0; call < 2; call++) {
            api.perform(post("/api/v1/qr/validate").contentType(MediaType.APPLICATION_JSON).content(body(code.qrToken())))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.qrId").value(code.id().toString()))
                    .andExpect(jsonPath("$.restaurantId").value(code.restaurantId().toString()))
                    .andExpect(jsonPath("$.tableId").value(code.tableId().toString()))
                    .andExpect(jsonPath("$.tableNumber").value("1"))
                    .andExpect(jsonPath("$.displayName").value("Test table"))
                    .andExpect(jsonPath("$.qrToken").doesNotExist())
                    .andExpect(jsonPath("$.tokenDigest").doesNotExist())
                    .andExpect(jsonPath("$.encryptedPayload").doesNotExist())
                    .andExpect(jsonPath("$.guestSessionId").doesNotExist())
                    .andExpect(jsonPath("$.role").doesNotExist());
        }
        var after = codes.findById(code.id()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getEncryptedPayload()).isEqualTo(before.getEncryptedPayload());
        assertThat(tables.get(code.tableId()).version()).isZero();
        assertThat(validation.validate(code.qrToken()).checkedAt()).isEqualTo(checked);
        assertThat(jdbc.queryForObject("select count(*) from table_sessions where table_id=?", Integer.class, code.tableId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from guest_sessions g join table_sessions s on s.id=g.table_session_id where s.table_id=?", Integer.class, code.tableId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from qr_scan_events where table_id=?", Integer.class, code.tableId())).isZero();
    }

    @Test
    void tokenDeterminesRestaurantAndTableWithoutUsingUnverifiedManagementContext() {
        var first = create("1");
        restaurant.set(UUID.randomUUID());
        var second = create("1");
        when(access.requireRestaurantId()).thenThrow(new AuthenticationCredentialsNotFoundException("Test staff context absent"));
        var resolvedFirst = validation.validate(first.qrToken());
        var resolvedSecond = validation.validate(second.qrToken());
        assertThat(resolvedFirst.restaurantId()).isEqualTo(first.restaurantId()).isNotEqualTo(second.restaurantId());
        assertThat(resolvedFirst.tableId()).isEqualTo(first.tableId());
        assertThat(resolvedSecond.restaurantId()).isEqualTo(second.restaurantId());
        assertThat(resolvedSecond.tableId()).isEqualTo(second.tableId());
    }

    @Test
    void forgedHeadersCannotRebindTheServerResolvedContext() throws Exception {
        var code = create("1");
        api.perform(post("/api/v1/qr/validate").header("X-Restaurant-Id", UUID.randomUUID().toString())
                .header("X-Table-Id", UUID.randomUUID().toString()).header("X-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content(body(code.qrToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantId").value(code.restaurantId().toString()))
                .andExpect(jsonPath("$.tableId").value(code.tableId().toString()))
                .andExpect(jsonPath("$.role").doesNotExist());
    }

    @Test
    void unknownAlteredAndDeletedCredentialsShareTheSameUnavailableResponse() throws Exception {
        var code = create("1");
        String altered = (code.qrToken().charAt(0) == 'A' ? "B" : "A") + code.qrToken().substring(1);
        lifecycle.delete(code.tableId(), code.id(), new QrCodeVersionRequest(0L));
        for (String token : new String[]{code.qrToken(), altered, "A".repeat(43)}) {
            api.perform(post("/api/v1/qr/validate").contentType(MediaType.APPLICATION_JSON).content(body(token)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("QR code is invalid or unavailable."));
        }
    }

    @Test
    void inactiveQrAndInactiveTableCannotResolveUntilExplicitlyReenabled() {
        var code = create("1");
        String token = code.qrToken();
        lifecycle.changeActive(code.tableId(), code.id(), new ChangeQrCodeActiveRequest(false, 0L));
        assertThatThrownBy(() -> validation.validate(token)).isInstanceOf(InvalidQrCodeException.class);
        lifecycle.changeActive(code.tableId(), code.id(), new ChangeQrCodeActiveRequest(true, 1L));
        tables.changeActive(code.tableId(), new ChangeTableActiveRequest(false, 0L));
        assertThatThrownBy(() -> validation.validate(token)).isInstanceOf(InvalidQrCodeException.class);
        tables.changeActive(code.tableId(), new ChangeTableActiveRequest(true, 1L));
        assertThat(validation.validate(token).qrId()).isEqualTo(code.id());
    }

    @Test
    void rotatedCredentialFailsRealValidationWhileTheReplacementResolves() {
        var old = create("1");
        String oldToken = old.qrToken();
        var next = creation.rotate(old.tableId(), old.id(), new QrCodeVersionRequest(0L));
        assertThatThrownBy(() -> validation.validate(oldToken)).isInstanceOf(InvalidQrCodeException.class);
        assertThat(validation.validate(next.qrToken()).qrId()).isEqualTo(next.id());
    }

    @Test
    void optionalExpirationIsExclusiveAndDefaultQrHasNoInventedTtl() {
        var code = create("1");
        String token = code.qrToken();
        assertThat(code.expiresAt()).isNull();
        assertThat(queries.detail(code.tableId(), code.id()).expiresAt()).isNull();
        Instant expiry = code.issuedAt().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("update qr_codes set expires_at=? where id=?", Timestamp.from(expiry), code.id());
        when(clock.instant()).thenReturn(expiry.minusNanos(1));
        assertThat(validation.validate(token).qrId()).isEqualTo(code.id());
        when(clock.instant()).thenReturn(expiry);
        assertThatThrownBy(() -> validation.validate(token)).isInstanceOf(InvalidQrCodeException.class);
        when(clock.instant()).thenReturn(expiry.plusNanos(1));
        assertThatThrownBy(() -> validation.validate(token)).isInstanceOf(InvalidQrCodeException.class);
        assertThat(queries.detail(code.tableId(), code.id()).expiresAt()).isEqualTo(expiry);
    }

    @Test
    void futureIssuedCredentialIsUnavailable() {
        var code = create("1");
        String token = code.qrToken();
        Instant checked = Instant.now();
        jdbc.update("update qr_codes set issued_at=? where id=?", Timestamp.from(checked.plusSeconds(60)), code.id());
        when(clock.instant()).thenReturn(checked);
        assertThatThrownBy(() -> validation.validate(token)).isInstanceOf(InvalidQrCodeException.class);
    }

    @Test
    void forwardMigrationRetainsUnboundedCodesAndChecksOptionalExpiry() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("121");
        var code = create("1");
        assertThat(codes.findById(code.id()).orElseThrow().getExpiresAt()).isNull();
        UUID id = code.id();
        for (String assignment : new String[]{"issued_at", "issued_at - interval '1 second'", "'infinity'::timestamptz", "'-infinity'::timestamptz"}) {
            String statement = "update qr_codes set expires_at=" + assignment + " where id=?";
            assertThatThrownBy(() -> jdbc.update(statement, id)).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"qrToken\":null}", "{\"qrToken\":\"\"}", "{\"qrToken\":\" \"}",
            "{\"qrToken\":\"short\"}", "{\"qrToken\":\"https://guest.example.test/qr#token=value\"}"})
    void missingMalformedAndFullUrlInputsAreRejected(String json) throws Exception {
        api.perform(post("/api/v1/qr/validate").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"restaurantId", "tableId", "role", "isActive", "expiresAt"})
    void clientCannotSupplyOwnershipOrAuthorityFields(String field) throws Exception {
        var code = create("1");
        String json = "{\"qrToken\":\"" + code.qrToken() + "\",\"" + field + "\":\"forged\"}";
        api.perform(post("/api/v1/qr/validate").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rotate", "disableQr", "disableTable", "delete", "expire"})
    void validationWaitsForLifecycleCommitAndReadsFreshState(String operation) throws Exception {
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
                    case "expire" -> {
                        Instant expiry = code.issuedAt().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
                        jdbc.update("update qr_codes set expires_at=? where id=?", Timestamp.from(expiry), code.id());
                        when(clock.instant()).thenReturn(expiry);
                    }
                    default -> throw new IllegalArgumentException("Unknown test operation");
                }
                var future = executor.submit(() -> {
                    try { return validation.validate(token); }
                    catch (InvalidQrCodeException exception) { return exception; }
                });
                await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%dining_tables%' and pid<>pg_backend_pid())", Boolean.class)));
                return future;
            });
            assertThat(result).isNotNull();
            assertThat(result.get(15, TimeUnit.SECONDS)).isInstanceOf(InvalidQrCodeException.class);
        }
    }
}
