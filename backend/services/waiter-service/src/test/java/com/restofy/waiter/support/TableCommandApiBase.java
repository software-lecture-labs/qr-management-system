package com.restofy.waiter.support;

import com.restofy.waiter.application.service.NotificationDispatcher;
import com.restofy.waiter.application.service.TableCommandReconciler;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared set-up and request helpers of the table closure and cleaning tests. The QR and Order services are local
 * HTTP stubs ({@link FakeBackends}, {@link FakeQrCommands}): the close follows QR's K11 paths and answer shapes, the
 * rest Waiter's DRAFT contracts. Nothing here proves the behaviour of the real services.
 */
public abstract class TableCommandApiBase extends ApiTestBase {

    protected static final UUID S1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    protected static final UUID S2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    protected static final UUID S3 = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    protected static final UUID WAITER_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    protected static final UUID WAITER_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    protected static final String CLOSE_POST = "/api/v1/internal/tables/[0-9a-f-]{36}/sessions/[0-9a-f-]{36}/close";
    protected static final String CLOSE_STATUS_GET =
            "/api/v1/internal/tables/[0-9a-f-]{36}/sessions/[0-9a-f-]{36}/close-commands/[0-9a-f-]{36}";
    protected static final String CLEAN_POST = "/api/v1/internal/tables/[0-9a-f-]{36}/cleaned";
    protected static final String CLEAN_STATUS_GET = "/api/v1/internal/tables/[0-9a-f-]{36}/clean-commands/[0-9a-f-]{36}";

    @Autowired
    protected NotificationDispatcher dispatcher;

    @Autowired
    protected TableCommandReconciler reconciler;

    protected FakeBackends.Table table;

    protected FakeQrCommands qrCommands;

    @BeforeEach
    void occupiedTable() {
        qrCommands = new FakeQrCommands(backends).installOn(STUB);
        table = addTable("12", "OCCUPIED", S1);
    }

    protected record Answer(int status, String body) {
        public String code() {
            JsonNode code = FakeBackends.read(body).get("code");
            return code == null ? null : code.asString();
        }
    }

    // ----- requests -----

    protected static String versionBody(long version) {
        return "{\"expectedVersion\":" + version + "}";
    }

    protected ResultActions close(RequestPostProcessor staff, UUID tableId, UUID session, String key, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/waiters/tables/" + tableId + "/sessions/" + session + "/close")
                .with(staff).with(csrf().asHeader()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** The staff member closes the table's current session under a fresh key, quoting the session version. */
    protected ResultActions closeSession(long version) throws Exception {
        return close(asWaiter(WAITER_A), table.id(), S1, newKey(), versionBody(version));
    }

    protected ResultActions clean(RequestPostProcessor staff, UUID tableId, String key, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/waiters/tables/" + tableId + "/cleaned")
                .with(staff).with(csrf().asHeader()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions cleanTable(long version) throws Exception {
        return clean(asWaiter(WAITER_A), table.id(), newKey(), versionBody(version));
    }

    protected ResultActions commandStatus(UUID tableId, UUID commandId) throws Exception {
        return mockMvc.perform(get("/api/v1/waiters/tables/" + tableId + "/commands/" + commandId).with(asWaiter(WAITER_A)));
    }

    protected ResultActions tableView() throws Exception {
        return mockMvc.perform(get("/api/v1/waiters/tables/" + table.id()).with(asWaiter(WAITER_A)));
    }

    // ----- data -----

    /** An order that Order reports as served and verifiably paid: nothing in the way of closing. */
    protected FakeBackends.Order settledServedOrder(String number) {
        FakeBackends.Order order = addOrder(table, S1, number, "ACCEPTED", "SERVED", "READY", "2026-10-16T09:00:00Z");
        return withPayment(order, "PAID");
    }

    protected FakeBackends.Order withPayment(FakeBackends.Order order, String payment) {
        FakeBackends.Order updated = order.withPayment(payment);
        backends.orders.replaceAll(existing -> existing.id().equals(order.id()) ? updated : existing);
        return updated;
    }

    /** A call row of the table's session, in the given state, from a guest session of its own. */
    protected UUID call(UUID session, String type, String status) {
        return insertServiceRequest(RESTAURANT, table.id(), session, UUID.randomUUID(), type, status, Instant.now());
    }

    protected String callStatus(UUID callId) {
        return jdbc.queryForObject("SELECT status FROM service_requests WHERE id = ?", String.class, callId);
    }

    protected Map<String, Object> commandRow() {
        return jdbc.queryForMap("SELECT * FROM table_commands");
    }

    protected UUID commandId() {
        return jdbc.queryForObject("SELECT id FROM table_commands", UUID.class);
    }

    protected int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    protected List<DownstreamStub.Request> closePosts() {
        return STUB.requests("POST", CLOSE_POST);
    }

    protected List<DownstreamStub.Request> cleanPosts() {
        return STUB.requests("POST", CLEAN_POST);
    }

    protected static JsonNode json(ResultActions actions) throws Exception {
        return FakeBackends.read(actions.andReturn().getResponse().getContentAsString());
    }

    // ----- races -----

    protected Callable<Answer> asTask(Callable<ResultActions> request) {
        return () -> {
            var response = request.call().andReturn().getResponse();
            return new Answer(response.getStatus(), response.getContentAsString());
        };
    }

    protected List<Answer> race(List<Callable<Answer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Answer>> futures = new ArrayList<>();
        for (Callable<Answer> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        List<Answer> answers = new ArrayList<>();
        for (Future<Answer> future : futures) answers.add(future.get());
        pool.shutdown();
        return answers;
    }
}
