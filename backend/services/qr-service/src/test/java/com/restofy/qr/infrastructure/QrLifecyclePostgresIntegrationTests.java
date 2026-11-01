package com.restofy.qr.infrastructure;

import com.restofy.qr.api.controller.QrCodeController;
import com.restofy.qr.api.dto.request.*;
import com.restofy.qr.api.dto.response.CreatedQrCodeResponse;
import com.restofy.qr.api.exception.ApiExceptionHandler;
import com.restofy.qr.application.service.*;
import com.restofy.qr.domain.enums.QrCodeStatus;
import com.restofy.qr.domain.exception.*;
import com.restofy.qr.infrastructure.persistence.repository.QrCodeRepository;
import com.restofy.qr.infrastructure.security.TableManagementAccess;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class QrLifecyclePostgresIntegrationTests {
    @Autowired QrCodeCreationService creation;
    @Autowired QrCodeLifecycleService lifecycle;
    @Autowired TableManagementService tables;
    @Autowired QrCodeRepository codes;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired RequestMappingHandlerAdapter adapter;
    // Test-only tenant context; production access and security filters remain closed.
    @MockitoBean TableManagementAccess access;
    private final AtomicReference<UUID> restaurant = new AtomicReference<>();
    private MockMvc api;

    @BeforeEach
    void setup() {
        restaurant.set(UUID.randomUUID());
        when(access.requireRestaurantId()).thenAnswer(invocation -> restaurant.get());
        api = MockMvcBuilders.standaloneSetup(new QrCodeController(creation, lifecycle))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(adapter.getMessageConverters().toArray(HttpMessageConverter[]::new)).build();
    }

    private CreatedQrCodeResponse create() {
        return creation.create(tables.create(new CreateTableRequest("1", null, null)).id());
    }

    private String path(CreatedQrCodeResponse code) {
        return "/api/v1/qr/tables/" + code.tableId() + "/qr-codes/" + code.id();
    }

    private long version(CreatedQrCodeResponse code) {
        return codes.findById(code.id()).orElseThrow().getVersion();
    }

    private void change(CreatedQrCodeResponse code, boolean enabled, long version) {
        lifecycle.changeActive(code.tableId(), code.id(), new ChangeQrCodeActiveRequest(enabled, version));
    }

    private void remove(CreatedQrCodeResponse code, long version) {
        lifecycle.delete(code.tableId(), code.id(), new QrCodeVersionRequest(version));
    }

    private int activeCount() {
        return jdbc.queryForObject("select count(*) from qr_codes where restaurant_id=? and status='ACTIVE'", Integer.class, restaurant.get());
    }

    @Test
    void forwardMigrationAllowsInactiveStateWithNoRevocationTimestamp() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("121");
        var code = create();
        change(code, false, 0);
        assertThat(codes.findById(code.id()).orElseThrow().getRevokedAt()).isNull();
        assertThat(activeCount()).isZero();
        UUID id = code.id();
        assertThatThrownBy(() -> jdbc.update("update qr_codes set revoked_at=issued_at where id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deactivateAndReactivatePreserveIdentityPayloadAndIdempotentVersions() {
        var code = create();
        String encrypted = codes.findById(code.id()).orElseThrow().getEncryptedPayload();
        change(code, true, 0);
        assertThat(version(code)).isZero();
        change(code, false, 0);
        assertThat(version(code)).isEqualTo(1);
        change(code, false, 1);
        assertThat(version(code)).isEqualTo(1);
        assertThat(activeCount()).isZero();
        change(code, true, 1);
        assertThat(activeCount()).isEqualTo(1);
        var retained = codes.findById(code.id()).orElseThrow();
        assertThat(retained.getVersion()).isEqualTo(2);
        assertThat(retained.getEncryptedPayload()).isEqualTo(encrypted);
        assertThatThrownBy(() -> change(code, false, 0)).isInstanceOf(QrCodeConflictException.class);
    }

    @Test
    void anotherActiveQrPreventsReactivationAndFailureRetainsInactiveState() {
        var first = create();
        change(first, false, 0);
        var second = creation.create(first.tableId());
        assertThatThrownBy(() -> change(first, true, 1)).isInstanceOf(QrCodeConflictException.class);
        assertThat(codes.findById(first.id()).orElseThrow().getStatus()).isEqualTo(QrCodeStatus.INACTIVE);
        assertThat(version(first)).isEqualTo(1);
        change(second, false, 0);
        change(first, true, 1);
        assertThat(activeCount()).isEqualTo(1);
    }

    @Test
    void revokedQrCannotBeEnabledOrConvertedIntoReversibleInactiveState() {
        var code = create();
        creation.rotate(code.tableId(), code.id(), new QrCodeVersionRequest(0L));
        assertThatThrownBy(() -> change(code, true, 1)).isInstanceOf(QrCodeConflictException.class);
        assertThatThrownBy(() -> change(code, false, 1)).isInstanceOf(QrCodeConflictException.class);
        assertThat(codes.findById(code.id()).orElseThrow().getStatus()).isEqualTo(QrCodeStatus.REVOKED);
    }

    @Test
    void inactiveTableAllowsDeactivationButBlocksActivation() {
        var code = create();
        tables.changeActive(code.tableId(), new ChangeTableActiveRequest(false, 0L));
        assertThatThrownBy(() -> change(code, true, 0)).isInstanceOf(QrCodeConflictException.class);
        change(code, false, 0);
        assertThatThrownBy(() -> change(code, true, 1)).isInstanceOf(QrCodeConflictException.class);
        assertThat(version(code)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(QrCodeStatus.class)
    void hardDeleteRemovesActualRowAndCredentialInEveryState(QrCodeStatus status) {
        var code = create();
        if (status == QrCodeStatus.INACTIVE) change(code, false, 0);
        if (status == QrCodeStatus.REVOKED) creation.rotate(code.tableId(), code.id(), new QrCodeVersionRequest(0L));
        remove(code, version(code));
        assertThat(codes.findById(code.id())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from qr_codes where id=?", Integer.class, code.id())).isZero();
        assertThat(tables.get(code.tableId()).id()).isEqualTo(code.tableId());
        assertThatThrownBy(() -> remove(code, 0)).isInstanceOf(QrCodeNotFoundException.class);
    }

    @Test
    void staleDeleteDoesNotRemoveQrAndDeletingActiveQrAllowsFreshCreation() {
        var code = create();
        assertThatThrownBy(() -> remove(code, 99)).isInstanceOf(QrCodeConflictException.class);
        assertThat(codes.findById(code.id())).isPresent();
        remove(code, 0);
        var replacement = creation.create(code.tableId());
        assertThat(replacement.id()).isNotEqualTo(code.id());
        assertThat(replacement.qrToken()).isNotEqualTo(code.qrToken());
    }

    @Test
    void fkHistoryReferenceRejectsHardDeleteWithoutCascadeOrPartialChanges() throws Exception {
        var code = create();
        // Only the disposable Testcontainers database. This is a FK safety fixture, not production history.
        jdbc.execute("create table if not exists test_qr_delete_references (id uuid primary key, qr_id uuid not null references qr_codes(id) on delete restrict)");
        UUID reference = UUID.randomUUID();
        jdbc.update("insert into test_qr_delete_references(id,qr_id) values (?,?)", reference, code.id());
        api.perform(delete(path(code)).queryParam("version", "0")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The operation conflicts with the current data. Reload and retry."));
        assertThat(codes.findById(code.id())).isPresent();
        assertThat(version(code)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from test_qr_delete_references where id=?", Integer.class, reference)).isEqualTo(1);
    }

    @Test
    void tenantAndTableScopeProtectBothCommandsAndMissingAuthIsRejected() {
        var code = create();
        UUID other = tables.create(new CreateTableRequest("2", null, null)).id();
        var active = new ChangeQrCodeActiveRequest(false, 0L);
        var version = new QrCodeVersionRequest(0L);
        UUID qr = code.id();
        UUID table = code.tableId();
        assertThatThrownBy(() -> lifecycle.changeActive(other, qr, active)).isInstanceOf(QrCodeNotFoundException.class);
        assertThatThrownBy(() -> lifecycle.delete(other, qr, version)).isInstanceOf(QrCodeNotFoundException.class);
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> lifecycle.changeActive(missing, qr, active)).isInstanceOf(TableNotFoundException.class);
        assertThatThrownBy(() -> lifecycle.delete(missing, qr, version)).isInstanceOf(TableNotFoundException.class);
        restaurant.set(UUID.randomUUID());
        assertThatThrownBy(() -> lifecycle.changeActive(table, qr, active)).isInstanceOf(TableNotFoundException.class);
        assertThatThrownBy(() -> lifecycle.delete(table, qr, version)).isInstanceOf(TableNotFoundException.class);
        when(access.requireRestaurantId()).thenThrow(new AuthenticationCredentialsNotFoundException("test-only"));
        assertThatThrownBy(() -> lifecycle.changeActive(table, qr, active)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> lifecycle.delete(table, qr, version)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThat(codes.findById(qr)).isPresent();
    }

    @Test
    void concurrentReactivationsOfDifferentCodesHaveOneWinner() throws Exception {
        var first = create();
        change(first, false, 0);
        var second = creation.create(first.tableId());
        change(second, false, 0);
        var results = race(() -> { change(first, true, 1); return "active"; },
                () -> { change(second, true, 1); return "active"; });
        assertThat(results.stream().filter(String.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(QrCodeConflictException.class::isInstance)).hasSize(1);
        assertThat(activeCount()).isEqualTo(1);
    }

    @Test
    void concurrentCreateAndReactivationPreserveSingleActiveInvariant() throws Exception {
        var first = create();
        change(first, false, 0);
        var results = race(() -> creation.create(first.tableId()), () -> { change(first, true, 1); return "active"; });
        assertThat(results.stream().filter(QrCodeConflictException.class::isInstance)).hasSize(1);
        assertThat(activeCount()).isEqualTo(1);
    }

    @Test
    void concurrentDeleteAndRotationCannotBothApplyToSameOldVersion() throws Exception {
        var code = create();
        var results = race(() -> { remove(code, 0); return "deleted"; },
                () -> creation.rotate(code.tableId(), code.id(), new QrCodeVersionRequest(0L)));
        assertThat(results.stream().filter(RuntimeException.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(value -> value instanceof QrCodeConflictException || value instanceof QrCodeNotFoundException)).hasSize(1);
        assertThat(activeCount()).isBetween(0, 1);
    }

    @Test
    void httpLifecycleReturnsSafeMetadataNoStoreAndPhysicalDelete204() throws Exception {
        var code = create();
        api.perform(patch(path(code) + "/active").header("X-Restaurant-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("INACTIVE")).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.restaurantId").value(restaurant.get().toString()))
                .andExpect(jsonPath("$.qrToken").doesNotExist()).andExpect(jsonPath("$.tokenDigest").doesNotExist())
                .andExpect(jsonPath("$.encryptedPayload").doesNotExist());
        api.perform(delete(path(code)).queryParam("version", "0")).andExpect(status().isConflict());
        api.perform(delete(path(code)).queryParam("version", "1")).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(codes.findById(code.id())).isEmpty();
        api.perform(delete(path(code)).queryParam("version", "1")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "null", "{broken", "{\"isActive\":null,\"version\":0}",
            "{\"isActive\":false}", "{\"isActive\":false,\"version\":-1}",
            "{\"isActive\":false,\"version\":0,\"restaurantId\":\"forged\"}",
            "{\"isActive\":false,\"version\":0,\"status\":\"ACTIVE\"}"})
    void httpRejectsInvalidActivationBodiesWithoutMutation(String body) throws Exception {
        var code = create();
        api.perform(patch(path(code) + "/active").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(version(code)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-1", "invalid", "9223372036854775808"})
    void httpRejectsInvalidDeleteVersionQuery(String version) throws Exception {
        var code = create();
        api.perform(delete(path(code)).queryParam("version", version)).andExpect(status().isBadRequest());
        assertThat(codes.findById(code.id())).isPresent();
    }

    @Test
    void httpRequiresDeleteVersionAndHidesForeignQrIds() throws Exception {
        var code = create();
        api.perform(delete(path(code))).andExpect(status().isBadRequest());
        restaurant.set(UUID.randomUUID());
        api.perform(delete(path(code)).queryParam("version", "0")).andExpect(status().isNotFound());
        api.perform(patch(path(code) + "/active").contentType(MediaType.APPLICATION_JSON)
                .content("{\"isActive\":false,\"version\":0}")).andExpect(status().isNotFound());
    }

    private static List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Object>>();
            for (var command : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try { return command.call(); }
                    catch (RuntimeException exception) { return exception; }
                }));
            }
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }
}
