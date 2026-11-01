package com.restofy.waiter.support;

import com.restofy.waiter.support.DownstreamStub.Request;
import com.restofy.waiter.support.DownstreamStub.Response;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory QR and Order behind a {@link DownstreamStub}. It behaves like a compliant implementation of the
 * DRAFT contract: it filters by restaurant, applies filters and ordering to the whole data set, then pages.
 * Misbehaving downstreams are simulated per test with their own routes.
 */
public final class FakeBackends {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    /** {@code version} is QR's version of the table, {@code sessionVersion} QR's version of its live session. */
    public record Table(UUID id, UUID restaurantId, String number, String status, UUID sessionId, long version,
                        long sessionVersion) {

        public Table(UUID id, UUID restaurantId, String number, String status, UUID sessionId) {
            this(id, restaurantId, number, status, sessionId, 7, 3);
        }

        public Table with(String newStatus, UUID newSessionId) {
            return new Table(id, restaurantId, number, newStatus, newSessionId, version + 1, sessionVersion);
        }
    }

    /**
     * Test order; {@code preparation} is null, WAITING, PREPARING or READY, {@code payment} NOT_PAID, PENDING, PAID
     * or UNKNOWN. The version is Order's optimistic-locking version.
     */
    public record Order(UUID id, UUID restaurantId, UUID tableId, UUID sessionId, String number, String business,
                        String delivery, String preparation, Instant acceptedAt, Instant readyAt, Instant servedAt,
                        long version, String payment) {

        public Order(UUID id, UUID restaurantId, UUID tableId, UUID sessionId, String number, String business,
                     String delivery, String preparation, Instant acceptedAt, Instant readyAt, Instant servedAt) {
            this(id, restaurantId, tableId, sessionId, number, business, delivery, preparation, acceptedAt, readyAt,
                    servedAt, 4, "NOT_PAID");
        }

        Order served(Instant at) {
            return new Order(id, restaurantId, tableId, sessionId, number, business, "SERVED", preparation, acceptedAt,
                    readyAt, at, version + 1, payment);
        }

        Order cancelled() {
            return new Order(id, restaurantId, tableId, sessionId, number, "CANCELLED", delivery, preparation, acceptedAt,
                    readyAt, servedAt, version + 1, payment);
        }

        public Order withPayment(String newPayment) {
            return new Order(id, restaurantId, tableId, sessionId, number, business, delivery, preparation, acceptedAt,
                    readyAt, servedAt, version, newPayment);
        }

        public Order withBusiness(String newBusiness) {
            return new Order(id, restaurantId, tableId, sessionId, number, newBusiness, delivery, preparation, acceptedAt,
                    readyAt, servedAt, version, payment);
        }
    }

    /** What the Order stub recorded for a serve command id: the request digest and Order's answer. */
    record ServeRecord(String digest, boolean applied, int rejectionStatus, String rejectionCode) {
    }

    /** What the Order stub does wrong on the next serve command, to simulate an unreliable network or service. */
    public enum ServeFault {
        /** Order applies the command but the answer arrives after Waiter's read timeout. */
        LOST_ANSWER,
        /** Order is slow and never applies the command within Waiter's read timeout. */
        HANG_BEFORE_APPLY,
        /** Order answers 503 without applying the command. */
        SERVER_ERROR,
        /** Order rejects Waiter itself (no service identity). */
        UNAUTHORIZED,
        /** Order applies the command and answers 200 with an unreadable body. */
        GARBAGE_ANSWER
    }

    /** D-02 is open: whether a partially cancelled order may be served is Order's decision, not Waiter's. */
    public enum PartialCancellation { ALLOW, REJECT }

    /** A guest credential as QR would know it; the statuses are the DRAFT contract values. */
    public record Guest(String token, UUID restaurantId, UUID tableId, UUID tableSessionId, UUID guestSessionId,
                        String tableSessionStatus, String guestStatus) {

        public Guest withStatuses(String tableSession, String guestSession) {
            return new Guest(token, restaurantId, tableId, tableSessionId, guestSessionId, tableSession, guestSession);
        }
    }

    public final List<Table> tables = new CopyOnWriteArrayList<>();
    public final List<Order> orders = new CopyOnWriteArrayList<>();
    private final Map<UUID, ServeRecord> serveRecords = new HashMap<>();
    private final Deque<ServeFault> serveFaults = new ArrayDeque<>();
    public volatile PartialCancellation partialCancellation = PartialCancellation.ALLOW;
    /** Known guest credentials by token; an unknown token is answered with 404. */
    public final Map<String, Guest> guests = new ConcurrentHashMap<>();

    private static final Pattern TABLE_ID = Pattern.compile("/api/v1/internal/tables/([0-9a-f-]{36})");
    private static final Pattern ORDER_ID = Pattern.compile("/api/v1/internal/orders/([0-9a-f-]{36})");

    public FakeBackends installOn(DownstreamStub stub) {
        stub.route("GET", "/api/v1/internal/tables", this::listTables);
        stub.route("GET", TABLE_ID.pattern(), this::getTable);
        stub.route("POST", "/api/v1/internal/orders/session-summaries", this::sessionSummaries);
        stub.route("POST", "/api/v1/internal/orders/search", this::searchOrders);
        stub.route("GET", ORDER_ID.pattern(), this::getOrder);
        stub.route("POST", "/api/v1/internal/guest-sessions/verify", this::verifyGuest);
        stub.route("POST", "/api/v1/internal/orders/[0-9a-f-]{36}/serve", this::serveOrder);
        stub.route("GET", "/api/v1/internal/orders/[0-9a-f-]{36}/serve-commands/[0-9a-f-]{36}", this::serveStatus);
        return this;
    }

    // ----- QR -----

    private Response listTables(Request request) {
        Map<String, String> query = request.query();
        UUID restaurantId = UUID.fromString(query.get("restaurantId"));
        int page = Integer.parseInt(query.get("page"));
        int size = Integer.parseInt(query.get("size"));
        List<Table> mine = tables.stream().filter(t -> t.restaurantId().equals(restaurantId))
                .sorted(Comparator.comparing(Table::id)).toList();
        int from = Math.min(page * size, mine.size());
        int to = Math.min(from + size, mine.size());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", mine.subList(from, to).stream().map(FakeBackends::tableJson).toList());
        body.put("page", page);
        body.put("size", size);
        body.put("totalElements", mine.size());
        return Response.json(200, write(body));
    }

    private Response getTable(Request request) {
        Matcher match = TABLE_ID.matcher(request.path());
        match.matches();
        UUID id = UUID.fromString(match.group(1));
        UUID restaurantId = UUID.fromString(request.query().get("restaurantId"));
        return tables.stream().filter(t -> t.id().equals(id) && t.restaurantId().equals(restaurantId)).findFirst()
                .map(t -> Response.json(200, write(tableJson(t))))
                .orElse(Response.json(404, "{\"status\":404}"));
    }

    public synchronized Table table(UUID id) {
        return tables.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    public synchronized void replace(Table updated) {
        for (int index = 0; index < tables.size(); index++) {
            if (tables.get(index).id().equals(updated.id())) tables.set(index, updated);
        }
    }

    public static Map<String, Object> tableJson(Table table) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", table.id());
        json.put("restaurantId", table.restaurantId());
        json.put("tableNumber", table.number());
        json.put("displayName", "Table " + table.number());
        json.put("capacity", 4);
        json.put("operationalStatus", table.status());
        json.put("active", !"DISABLED".equals(table.status()));
        json.put("version", table.version());
        if (table.sessionId() == null) {
            json.put("currentSession", null);
        } else {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("id", table.sessionId());
            session.put("status", "OPEN");
            session.put("openedAt", "2026-10-16T09:00:00Z");
            session.put("lastActivityAt", "2026-10-16T09:30:00Z");
            session.put("scanCount", 2);
            session.put("version", table.sessionVersion());
            json.put("currentSession", session);
        }
        return json;
    }

    // ----- Order: the serve command (DRAFT contract) -----

    public synchronized void failNextServe(ServeFault fault) {
        serveFaults.add(fault);
    }

    /** Order-side cancellation racing with a serve command; returns false if the order was already served. */
    public synchronized boolean cancel(UUID orderId) {
        for (int index = 0; index < orders.size(); index++) {
            Order order = orders.get(index);
            if (order.id().equals(orderId)) {
                if (order.delivery().equals("SERVED") || order.business().equals("CANCELLED")) return false;
                orders.set(index, order.cancelled());
                return true;
            }
        }
        return false;
    }

    public synchronized Order order(UUID orderId) {
        return orders.stream().filter(o -> o.id().equals(orderId)).findFirst().orElseThrow();
    }

    private static final Pattern SERVE_PATH = Pattern.compile("/api/v1/internal/orders/([0-9a-f-]{36})/serve");
    private static final Pattern SERVE_STATUS_PATH =
            Pattern.compile("/api/v1/internal/orders/([0-9a-f-]{36})/serve-commands/([0-9a-f-]{36})");

    /**
     * Decides atomically (the whole method is one critical section, as Order's database transaction must be):
     * ownership, session, version, state, and idempotency by command id. This models the behaviour the DRAFT
     * contract demands from Order; it does not prove that the real Order will provide it.
     */
    private synchronized Response serveOrder(Request request) {
        ServeFault fault = serveFaults.poll();
        if (fault == ServeFault.SERVER_ERROR) return Response.json(503, "{}");
        if (fault == ServeFault.UNAUTHORIZED) return Response.json(401, "{}");
        if (fault == ServeFault.HANG_BEFORE_APPLY) return Response.json(504, "{}").delayed(1500);
        Matcher match = SERVE_PATH.matcher(request.path());
        match.matches();
        UUID orderId = UUID.fromString(match.group(1));
        JsonNode body = read(request.body());
        UUID commandId = UUID.fromString(body.get("commandId").asString());
        if (!commandId.toString().equals(request.header("Idempotency-Key"))) return rejection(400, "ORDER_REJECTED");
        UUID restaurantId = UUID.fromString(body.get("restaurantId").asString());
        UUID sessionId = UUID.fromString(body.get("tableSessionId").asString());
        long expectedVersion = body.get("expectedVersion").asLong();
        String digest = orderId + "|" + sessionId + "|" + expectedVersion + "|" + body.get("requestedByUserId").asString();

        ServeRecord known = serveRecords.get(commandId);
        if (known != null) {
            if (!known.digest().equals(digest)) return rejection(409, "ORDER_COMMAND_CONFLICT");
            if (!known.applied()) return rejection(known.rejectionStatus(), known.rejectionCode());
            return Response.json(200, write(serveAnswer(commandId, "DUPLICATE", order(orderId))));
        }
        int index = indexOf(orderId, restaurantId);
        if (index < 0) return rejection(404, "ORDER_NOT_FOUND");   // not recorded: nothing to replay
        Order order = orders.get(index);
        String refusal = refusalFor(order, sessionId, expectedVersion);
        if (refusal != null) {
            serveRecords.put(commandId, new ServeRecord(digest, false, 409, refusal));
            return rejection(409, refusal);
        }
        orders.set(index, order.served(Instant.parse("2026-10-16T10:30:00Z")));
        serveRecords.put(commandId, new ServeRecord(digest, true, 0, null));
        Response answer = fault == ServeFault.GARBAGE_ANSWER ? Response.json(200, "not json")
                : Response.json(200, write(serveAnswer(commandId, "APPLIED", orders.get(index))));
        return fault == ServeFault.LOST_ANSWER ? answer.delayed(1500) : answer;
    }

    private String refusalFor(Order order, UUID sessionId, long expectedVersion) {
        if (!order.sessionId().equals(sessionId)) return "ORDER_SESSION_MISMATCH";
        if (order.version() != expectedVersion) return "ORDER_VERSION_CONFLICT";
        if (order.delivery().equals("SERVED")) return "ORDER_ALREADY_SERVED";
        if (order.business().equals("CANCELLED")) return "ORDER_CANCELLED";
        if (!"READY".equals(order.preparation())) return "ORDER_NOT_READY";
        if (order.business().equals("PARTIALLY_CANCELLED") && partialCancellation == PartialCancellation.REJECT) {
            return "ORDER_NOT_DELIVERABLE";
        }
        return null;   // A PAID order is not refused: prepayment neither blocks nor requires serving.
    }

    private int indexOf(UUID orderId, UUID restaurantId) {
        for (int index = 0; index < orders.size(); index++) {
            Order order = orders.get(index);
            if (order.id().equals(orderId) && order.restaurantId().equals(restaurantId)) return index;
        }
        return -1;
    }

    private static Map<String, Object> serveAnswer(UUID commandId, String outcome, Order order) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("commandId", commandId);
        answer.put("outcome", outcome);
        answer.put("order", orderJson(order));
        return answer;
    }

    private static Response rejection(int status, String code) {
        return Response.json(status, write(Map.of("status", status, "code", code)));
    }

    private synchronized Response serveStatus(Request request) {
        Matcher match = SERVE_STATUS_PATH.matcher(request.path());
        match.matches();
        UUID orderId = UUID.fromString(match.group(1));
        UUID commandId = UUID.fromString(match.group(2));
        ServeRecord known = serveRecords.get(commandId);
        if (known == null) return Response.json(404, "{\"status\":404}");
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("commandId", commandId);
        answer.put("status", known.applied() ? "APPLIED" : "REJECTED");
        answer.put("rejectionCode", known.rejectionCode());
        answer.put("order", known.applied() ? orderJson(order(orderId)) : null);
        return Response.json(200, write(answer));
    }

    private Response verifyGuest(Request request) {
        Guest guest = guests.get(read(request.body()).get("guestToken").asString());
        if (guest == null) return Response.json(404, "{\"status\":404}");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("restaurantId", guest.restaurantId());
        body.put("tableId", guest.tableId());
        body.put("tableSession", Map.of("id", guest.tableSessionId(), "status", guest.tableSessionStatus()));
        body.put("guestSession", Map.of("id", guest.guestSessionId(), "status", guest.guestStatus()));
        return Response.json(200, write(body));
    }

    // ----- Order -----

    private Response sessionSummaries(Request request) {
        JsonNode body = read(request.body());
        UUID restaurantId = UUID.fromString(body.get("restaurantId").asString());
        Set<UUID> sessionIds = new HashSet<>();
        body.get("tableSessionIds").forEach(node -> sessionIds.add(UUID.fromString(node.asString())));
        Map<UUID, Long> counts = new LinkedHashMap<>();
        for (Order order : orders) {
            boolean active = order.business().equals("ACCEPTED") || order.business().equals("PARTIALLY_CANCELLED");
            if (order.restaurantId().equals(restaurantId) && sessionIds.contains(order.sessionId()) && active) {
                counts.merge(order.sessionId(), 1L, Long::sum);
            }
        }
        List<Map<String, Object>> items = counts.entrySet().stream()
                .map(e -> Map.<String, Object>of("tableSessionId", e.getKey(), "activeOrderCount", e.getValue())).toList();
        return Response.json(200, write(Map.of("items", items)));
    }

    private Response searchOrders(Request request) {
        JsonNode body = read(request.body());
        UUID restaurantId = UUID.fromString(body.get("restaurantId").asString());
        UUID tableId = body.hasNonNull("tableId") ? UUID.fromString(body.get("tableId").asString()) : null;
        String view = body.hasNonNull("view") ? body.get("view").asString() : null;
        Set<UUID> sessions = null;
        if (body.hasNonNull("tableSessionIds")) {
            sessions = new HashSet<>();
            for (JsonNode node : body.get("tableSessionIds")) sessions.add(UUID.fromString(node.asString()));
        }
        int page = body.get("page").asInt();
        int size = body.get("size").asInt();
        Set<UUID> sessionFilter = sessions;
        Comparator<Order> order = switch (view == null ? "" : view) {
            case "READY_HISTORY" -> Comparator.comparing(Order::readyAt).reversed().thenComparing(Order::id);
            case "AWAITING_DELIVERY" -> Comparator.comparing(Order::readyAt).thenComparing(Order::id);
            default -> Comparator.comparing(Order::acceptedAt).reversed().thenComparing(Order::id);
        };
        List<Order> matching = orders.stream()
                .filter(o -> o.restaurantId().equals(restaurantId))
                .filter(o -> tableId == null || o.tableId().equals(tableId))
                .filter(o -> sessionFilter == null || sessionFilter.contains(o.sessionId()))
                .filter(o -> inView(o, view))
                .sorted(order).toList();
        int from = (int) Math.min((long) page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", matching.subList(from, to).stream().map(FakeBackends::orderJson).toList());
        response.put("page", page);
        response.put("size", size);
        response.put("totalElements", matching.size());
        return Response.json(200, write(response));
    }

    private Response getOrder(Request request) {
        Matcher match = ORDER_ID.matcher(request.path());
        match.matches();
        UUID id = UUID.fromString(match.group(1));
        UUID restaurantId = UUID.fromString(request.query().get("restaurantId"));
        return orders.stream().filter(o -> o.id().equals(id) && o.restaurantId().equals(restaurantId)).findFirst()
                .map(o -> Response.json(200, write(orderJson(o))))
                .orElse(Response.json(404, "{\"status\":404}"));
    }

    private static boolean inView(Order o, String view) {
        if (view == null) return true;
        boolean ready = "READY".equals(o.preparation()) && !o.business().equals("CANCELLED");
        return switch (view) {
            case "ACTIVE" -> o.business().equals("ACCEPTED") || o.business().equals("PARTIALLY_CANCELLED");
            case "READY_HISTORY" -> ready;
            case "AWAITING_DELIVERY" -> ready && o.delivery().equals("PENDING");
            case "CANCELLED" -> o.business().equals("CANCELLED");
            default -> throw new IllegalArgumentException("unknown view " + view);
        };
    }

    public static Map<String, Object> orderJson(Order o) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", o.id());
        json.put("restaurantId", o.restaurantId());
        json.put("orderNumber", o.number());
        json.put("tableId", o.tableId());
        json.put("tableSessionId", o.sessionId());
        json.put("businessStatus", o.business());
        json.put("deliveryStatus", o.delivery());
        json.put("acceptedAt", o.acceptedAt());
        json.put("servedAt", o.servedAt());
        if (o.preparation() == null) {
            json.put("preparation", null);
        } else {
            Map<String, Object> prep = new LinkedHashMap<>();
            prep.put("status", o.preparation());
            prep.put("startedAt", o.readyAt() == null ? null : o.readyAt().minusSeconds(300));
            prep.put("readyAt", o.readyAt());
            prep.put("sourceVersion", 3);
            prep.put("asOf", Instant.parse("2026-10-16T10:00:00Z"));
            json.put("preparation", prep);
        }
        json.put("payment", Map.of("status", o.payment(), "sourceVersion", 1, "asOf", Instant.parse("2026-10-16T10:00:00Z")));
        json.put("currency", "TRY");
        json.put("originalGrossTotal", new BigDecimal("150.00"));
        json.put("cancelledGrossTotal", cancelledGross(o));
        json.put("payableTotal", new BigDecimal("150.00").subtract(cancelledGross(o)));
        json.put("items", List.of(item(o)));
        json.put("cancellations", cancelledQuantity(o) > 0 ? List.of(cancellation(o)) : List.of());
        json.put("version", o.version());
        return json;
    }

    /** Of the two portions on the line: all for a cancelled order, one for a partially cancelled order. */
    private static int cancelledQuantity(Order o) {
        return switch (o.business()) {
            case "CANCELLED" -> 2;
            case "PARTIALLY_CANCELLED" -> 1;
            default -> 0;
        };
    }

    private static BigDecimal cancelledGross(Order o) {
        return new BigDecimal("75.00").multiply(BigDecimal.valueOf(cancelledQuantity(o)));
    }

    private static Map<String, Object> item(Order o) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", UUID.nameUUIDFromBytes(("item-" + o.id()).getBytes()));
        item.put("productId", UUID.nameUUIDFromBytes("product".getBytes()));
        item.put("productName", "Izgara Köfte");
        item.put("quantity", 2);
        item.put("cancelledQuantity", cancelledQuantity(o));
        item.put("unitGrossPrice", new BigDecimal("75.00"));
        item.put("lineGrossTotal", new BigDecimal("150.00"));
        item.put("note", "Az pişmiş, soğansız");
        item.put("options", List.of(
                Map.of("name", "Ekstra peynir", "action", "ADD", "quantity", 1, "unitPriceDelta", new BigDecimal("10.00")),
                Map.of("name", "Soğan", "action", "REMOVE", "quantity", 1, "unitPriceDelta", BigDecimal.ZERO)));
        return item;
    }

    private static Map<String, Object> cancellation(Order o) {
        Map<String, Object> cancellation = new LinkedHashMap<>();
        cancellation.put("id", UUID.nameUUIDFromBytes(("cancel-" + o.id()).getBytes()));
        cancellation.put("source", "KITCHEN");
        cancellation.put("status", "APPROVED");
        cancellation.put("reasonCode", "OUT_OF_STOCK");
        cancellation.put("reasonText", "Köfte bitti");
        cancellation.put("requestedAt", Instant.parse("2026-10-16T09:50:00Z"));
        cancellation.put("decidedAt", Instant.parse("2026-10-16T09:51:00Z"));
        cancellation.put("items", List.of(Map.of("orderItemId", UUID.nameUUIDFromBytes(("item-" + o.id()).getBytes()),
                "quantity", cancelledQuantity(o))));
        return cancellation;
    }

    // ----- helpers -----

    public static String write(Object value) {
        return JSON.writeValueAsString(value);
    }

    public static JsonNode read(String json) {
        return JSON.readTree(json);
    }
}
