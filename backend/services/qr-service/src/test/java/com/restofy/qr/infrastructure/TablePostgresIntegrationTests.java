package com.restofy.qr.infrastructure;

import com.restofy.qr.api.controller.TableController;
import com.restofy.qr.api.dto.request.*;
import com.restofy.qr.api.dto.response.TableResponse;
import com.restofy.qr.api.exception.ApiExceptionHandler;
import com.restofy.qr.application.service.TableManagementService;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.exception.*;
import com.restofy.qr.infrastructure.persistence.repository.DiningTableRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class TablePostgresIntegrationTests {
    @Autowired TableManagementService service;
    @Autowired DiningTableRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RequestMappingHandlerAdapter adapter;
    // Test-only tenant fixture. Production access and HTTP security remain closed.
    @MockitoBean TableManagementAccess access;
    private final AtomicReference<UUID> restaurant = new AtomicReference<>();
    private MockMvc api;

    @BeforeEach
    void setup() {
        restaurant.set(UUID.randomUUID());
        when(access.requireRestaurantId()).thenAnswer(invocation -> restaurant.get());
        // Standalone HTTP harness deliberately tests binding/validation + real use cases/DB.
        // QrServiceApplicationTests separately exercise the real, closed SecurityFilterChain.
        api = MockMvcBuilders.standaloneSetup(new TableController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(adapter.getMessageConverters().toArray(HttpMessageConverter[]::new)).build();
    }

    private TableResponse create(String number) {
        return service.create(new CreateTableRequest(number, null, 4));
    }

    @Test
    void flywayAndJpaValidateActualPostgresSchema() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("121");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='21' and success", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("select indexname from pg_indexes where tablename='dining_tables'", String.class))
                .contains("dining_tables_pkey", "uq_dining_tables_restaurant_number", "ix_dining_tables_restaurant_state");
        var table = create("schema-check");
        assertThat(table.createdAt()).isNotNull();
        assertThat(table.updatedAt()).isEqualTo(table.createdAt());
    }

    @Test
    void createsUpdatesAndChecksVersion() {
        var table = create("  A-1  ");
        var changed = service.update(table.id(), new UpdateTableRequest("  A-2  ", "  Teras  ", 8, table.version()));
        assertThat(changed.tableNumber()).isEqualTo("A-2");
        assertThat(changed.displayName()).isEqualTo("Teras");
        assertThat(changed.capacity()).isEqualTo(8);
        assertThat(changed.version()).isEqualTo(table.version() + 1);
        assertThat(changed.updatedAt()).isAfterOrEqualTo(table.createdAt());
        UUID id = table.id();
        var staleRequest = new UpdateTableRequest("stale", null, null, table.version());
        assertThatThrownBy(() -> service.update(id, staleRequest))
                .isInstanceOf(TableConflictException.class);
        assertThat(service.get(table.id()).tableNumber()).isEqualTo("A-2");
    }

    @Test
    void changesActiveIdempotentlyAndRejectsStaleWrites() {
        var table = create("1");
        var disabled = service.changeActive(table.id(), new ChangeTableActiveRequest(false, table.version()));
        assertThat(disabled.isActive()).isFalse();
        assertThat(disabled.operationalStatus()).isEqualTo(TableOperationalStatus.DISABLED);
        assertThat(service.changeActive(table.id(), new ChangeTableActiveRequest(false, disabled.version())).version())
                .isEqualTo(disabled.version());
        UUID id = table.id();
        var staleRequest = new ChangeTableActiveRequest(true, table.version());
        assertThatThrownBy(() -> service.changeActive(id, staleRequest))
                .isInstanceOf(TableConflictException.class);
        assertThat(service.changeActive(table.id(), new ChangeTableActiveRequest(true, disabled.version())).operationalStatus())
                .isEqualTo(TableOperationalStatus.AVAILABLE);
    }

    @Test
    void isolatesEveryReadAndWriteBetweenRestaurants() {
        var table = create("1");
        restaurant.set(UUID.randomUUID());
        UUID id = table.id();
        var updateRequest = new UpdateTableRequest("2", null, null, 0L);
        var activeRequest = new ChangeTableActiveRequest(false, 0L);
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(TableNotFoundException.class);
        assertThatThrownBy(() -> service.update(id, updateRequest))
                .isInstanceOf(TableNotFoundException.class);
        assertThatThrownBy(() -> service.changeActive(id, activeRequest))
                .isInstanceOf(TableNotFoundException.class);
        assertThat(service.list(null, null, 0, 20, "tableNumber", "asc").content()).isEmpty();
        assertThat(create("1").id()).isNotEqualTo(table.id());
    }

    @Test
    void duplicateCreateAndUpdateCannotOverwriteExistingTable() {
        var first = create("1");
        var second = create("2");
        assertThatThrownBy(() -> create("  1  ")).isInstanceOf(DataIntegrityViolationException.class);
        UUID secondId = second.id();
        var duplicateRequest = new UpdateTableRequest("1", null, null, second.version());
        assertThatThrownBy(() -> service.update(secondId, duplicateRequest))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(service.get(first.id()).tableNumber()).isEqualTo("1");
        assertThat(service.get(second.id()).tableNumber()).isEqualTo("2");
    }

    @Test
    void filtersIndependentlyAndCombinesWithBoundedStablePagination() {
        create("A");
        var disabled = create("B");
        service.changeActive(disabled.id(), new ChangeTableActiveRequest(false, disabled.version()));
        create("C");
        assertThat(service.list(null, null, 0, 2, "tableNumber", "asc").content())
                .extracting(TableResponse::tableNumber).containsExactly("A", "B");
        var last = service.list(null, null, 1, 2, "tableNumber", "asc");
        assertThat(last.content()).extracting(TableResponse::tableNumber).containsExactly("C");
        assertThat(last.totalElements()).isEqualTo(3);
        assertThat(last.totalPages()).isEqualTo(2);
        assertThat(service.list(false, null, 0, 20, "createdAt", "desc").content()).hasSize(1);
        assertThat(service.list(null, TableOperationalStatus.AVAILABLE, 0, 20, "updatedAt", "asc").content()).hasSize(2);
        assertThat(service.list(true, TableOperationalStatus.DISABLED, 0, 20, "tableNumber", "asc").content()).isEmpty();
        assertThat(service.list(false, TableOperationalStatus.DISABLED, 0, 20, "tableNumber", "desc").content()).hasSize(1);
        assertThat(service.list(null, null, 10000, 100, "tableNumber", "asc").content()).isEmpty();
    }

    @Test
    void concurrentDuplicateCreatesHaveExactlyOneWinner() throws Exception {
        var outcomes = race(() -> create("same-number"), () -> create("same-number"));
        assertThat(outcomes.stream().filter(TableResponse.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(DataIntegrityViolationException.class::isInstance)).hasSize(1);
        assertThat(service.list(null, null, 0, 20, "tableNumber", "asc").totalElements()).isEqualTo(1);
    }

    @Test
    void realConcurrentJpaUpdatesRejectLostUpdate() throws Exception {
        var table = create("1");
        var barrier = new CyclicBarrier(2);
        var outcomes = race(() -> updateInTransaction(table.id(), "2", barrier),
                () -> updateInTransaction(table.id(), "3", barrier));
        assertThat(outcomes.stream().filter(String.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(OptimisticLockingFailureException.class::isInstance)).hasSize(1);
        assertThat(service.get(table.id()).version()).isEqualTo(1);
    }

    private String updateInTransaction(UUID id, String number, CyclicBarrier barrier) {
        return new TransactionTemplate(transactions).execute(transaction -> {
            var table = repository.findByRestaurantIdAndId(restaurant.get(), id).orElseThrow();
            await(barrier);
            table.updateDetails(number, null, null);
            repository.flush();
            return number;
        });
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException(exception);
        }
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

    @ParameterizedTest
    @ValueSource(strings = {"capacity=0", "capacity=-1", "operational_status='UNKNOWN'", "version=-1",
            "table_number=' '", "table_number=E'\\t'", "table_number=' leading'", "display_name=' '",
            "is_active=false", "updated_at=created_at - interval '1 second'"})
    void postgresConstraintsRejectInvalidState(String assignment) {
        var table = create("1");
        // Assignments are fixed test constants, never request data.
        String statement = "update dining_tables set " + assignment + " where id=?";
        UUID id = table.id();
        assertThatThrownBy(() -> jdbc.update(statement, id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void httpLifecycleHasSafeDtosLocationAndNoStore() throws Exception {
        api.perform(post("/api/v1/qr/tables").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableNumber\":\" A-1 \",\"displayName\":\" Bahçe \",\"capacity\":4}"))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tableNumber").value("A-1"));
        var table = service.list(null, null, 0, 20, "tableNumber", "asc").content().getFirst();
        api.perform(put("/api/v1/qr/tables/{id}", table.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableNumber\":\"A-2\",\"capacity\":6,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        api.perform(patch("/api/v1/qr/tables/{id}/active", table.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"isActive\":false,\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operationalStatus").value("DISABLED"));
        api.perform(get("/api/v1/qr/tables/{id}", table.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false)).andExpect(jsonPath("$.guestCount").doesNotExist());
        api.perform(get("/api/v1/qr/tables?isActive=false&operationalStatus=DISABLED&page=0&size=1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"tableNumber\":\" \"}", "{\"tableNumber\":\"1\",\"capacity\":0}",
            "{\"tableNumber\":\"1\",\"restaurantId\":\"spoofed\"}",
            "{\"tableNumber\":\"1\",\"operationalStatus\":\"OCCUPIED\"}", "{broken"})
    void httpRejectsInvalidBodiesAndMassAssignment(String body) throws Exception {
        api.perform(post("/api/v1/qr/tables").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.trace").doesNotExist());
        assertThat(service.list(null, null, 0, 20, "tableNumber", "asc").totalElements()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"operationalStatus=UNKNOWN", "operationalStatus=", "isActive=yes", "isActive=",
            "page=-1", "page=10001", "size=0", "size=101", "size=no", "sort=restaurantId", "direction=other"})
    void httpRejectsInvalidFiltersAndUnboundedQueries(String query) throws Exception {
        api.perform(get("/api/v1/qr/tables?" + query)).andExpect(status().isBadRequest());
    }

    @Test
    void httpConflictsAndMissingForeignIdsReturnSafeErrors() throws Exception {
        var table = create("1");
        api.perform(post("/api/v1/qr/tables").contentType(MediaType.APPLICATION_JSON).content("{\"tableNumber\":\"1\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("The operation conflicts with the current data. Reload and retry."));
        api.perform(put("/api/v1/qr/tables/{id}", table.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableNumber\":\"2\",\"version\":99}"))
                .andExpect(status().isConflict());
        api.perform(patch("/api/v1/qr/tables/{id}/active", table.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"isActive\":null,\"version\":0}")).andExpect(status().isBadRequest());
        api.perform(get("/api/v1/qr/tables/not-a-uuid")).andExpect(status().isBadRequest());
        api.perform(get("/api/v1/qr/tables/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
        restaurant.set(UUID.randomUUID());
        api.perform(get("/api/v1/qr/tables/{id}", table.id())).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OCCUPIED", "CLEANING"})
    void cannotDisableInUseTableThroughHttp(String state) throws Exception {
        var table = create("1");
        jdbc.update("update dining_tables set operational_status=? where id=?", state, table.id());
        api.perform(patch("/api/v1/qr/tables/{id}/active", table.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"isActive\":false,\"version\":0}"))
                .andExpect(status().isConflict());
        assertThat(service.get(table.id()).isActive()).isTrue();
    }
}
