package com.restofy.waiter.support;

import com.restofy.waiter.support.DownstreamStub.Request;
import com.restofy.waiter.support.DownstreamStub.Response;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The table commands of QR: close a main session on QR's K11 internal paths ({@code /api/v1/internal}, QR commit
 * 1f98fbb) and mark a table cleaned on Waiter's DRAFT path, which QR does not offer. It decides atomically (the
 * whole handler is one critical section, as QR's transaction must be) and is idempotent by command id. It models
 * the answers the contract describes; a passing test proves the Waiter side against this stub and says nothing
 * about the real QR service, whose Waiter endpoints stay closed until service identity and staff delegation exist.
 *
 * <p>What QR would verify with Order and Payment at closing time is not simulated: a test says what QR refuses
 * with ({@link #refuseCloseWith}), so the stub never duplicates the rules that belong to the owners.
 */
public final class FakeQrCommands {

    /** What QR does wrong on the next command, to simulate an unreliable network or service. */
    public enum Fault {
        /** QR applies the command but the answer arrives after Waiter's read timeout. */
        LOST_ANSWER,
        /** QR is slow and never applies the command within Waiter's read timeout. */
        HANG_BEFORE_APPLY,
        /** QR answers 503 without applying the command. */
        SERVER_ERROR,
        /** QR rejects Waiter itself (no service identity). */
        UNAUTHORIZED,
        /** QR applies the command and answers 200 with an unreadable body. */
        GARBAGE_ANSWER,
        /** A close only gets as far as CLOSING: accepted, main session not yet closed. */
        CLOSING_ONLY
    }

    /** What QR recorded for a close command id. */
    private record CloseRecord(String digest, String state, String rejectionCode, List<String> blockers, int guests,
                               int endedGuests, UUID tableId, UUID sessionId) {
    }

    /** What QR recorded for a cleaning command id. */
    private record CleanRecord(String digest, boolean applied, String rejectionCode, UUID tableId) {
    }

    private static final String ID = "([0-9a-f-]{36})";
    private static final Pattern CLOSE_PATH = Pattern.compile("/api/v1/internal/tables/" + ID + "/sessions/" + ID + "/close");
    private static final Pattern CLOSE_STATUS_PATH =
            Pattern.compile("/api/v1/internal/tables/" + ID + "/sessions/" + ID + "/close-commands/" + ID);
    private static final Pattern CLEAN_PATH = Pattern.compile("/api/v1/internal/tables/" + ID + "/cleaned");
    private static final Pattern CLEAN_STATUS_PATH = Pattern.compile("/api/v1/internal/tables/" + ID + "/clean-commands/" + ID);
    private static final Instant CLOSED_AT = Instant.parse("2026-10-16T11:00:00Z");

    private final FakeBackends backends;
    private final Map<UUID, CloseRecord> closes = new HashMap<>();
    private final Map<UUID, CleanRecord> cleans = new HashMap<>();
    private final Deque<Fault> closeFaults = new ArrayDeque<>();
    private final Deque<Fault> cleanFaults = new ArrayDeque<>();
    private volatile List<String> refuseCloseWith = List.of();
    /** Guest sessions the main session has; QR ends them all when it closes. */
    public volatile int guestSessions = 2;
    /** When not negative, QR claims CLOSED while only this many guest sessions ended: a contradictory answer. */
    public volatile int endedGuestsReported = -1;
    /** K13: Order found a scope drift after QR closed locally; QR still says CLOSED and names RECONCILIATION_REQUIRED. */
    public volatile boolean scopeDrift = false;

    public FakeQrCommands(FakeBackends backends) {
        this.backends = backends;
    }

    public FakeQrCommands installOn(DownstreamStub stub) {
        stub.route("POST", CLOSE_PATH.pattern(), this::close);
        stub.route("GET", CLOSE_STATUS_PATH.pattern(), this::closeStatus);
        stub.route("POST", CLEAN_PATH.pattern(), this::clean);
        stub.route("GET", CLEAN_STATUS_PATH.pattern(), this::cleanStatus);
        return this;
    }

    public synchronized void failNextClose(Fault fault) {
        closeFaults.add(fault);
    }

    public synchronized void failNextClean(Fault fault) {
        cleanFaults.add(fault);
    }

    /** QR will refuse the next closes as blocked, naming these blockers (what Order and Payment told it). */
    public void refuseCloseWith(String... blockers) {
        refuseCloseWith = List.of(blockers);
    }

    /** QR finishes a close that had only reached CLOSING: the main session and all guest sessions end now. */
    public synchronized void finishClosing(UUID commandId) {
        CloseRecord record = closes.get(commandId);
        FakeBackends.Table table = backends.table(record.tableId());
        backends.replace(table.with("CLEANING", null));
        closes.put(commandId, new CloseRecord(record.digest(), "CLOSED", null, closedBlockers(), record.guests(), record.guests(),
                record.tableId(), record.sessionId()));
    }

    private List<String> closedBlockers() {
        return scopeDrift ? List.of("RECONCILIATION_REQUIRED") : List.of();
    }

    /** QR closes the session on its own (for example because it expired), outside any Waiter command. */
    public synchronized void closeOutsideWaiter(UUID tableId) {
        backends.replace(backends.table(tableId).with("CLEANING", null));
    }

    // ----- close -----

    private synchronized Response close(Request request) {
        Fault fault = closeFaults.poll();
        if (fault == Fault.SERVER_ERROR) return Response.json(503, "{}");
        if (fault == Fault.UNAUTHORIZED) return Response.json(401, "{}");
        if (fault == Fault.HANG_BEFORE_APPLY) return Response.json(504, "{}").delayed(1500);
        Matcher match = CLOSE_PATH.matcher(request.path());
        match.matches();
        UUID tableId = UUID.fromString(match.group(1));
        UUID sessionId = UUID.fromString(match.group(2));
        JsonNode body = FakeBackends.read(request.body());
        UUID commandId = UUID.fromString(body.get("commandId").asString());
        if (!commandId.toString().equals(request.header("Idempotency-Key"))) return refusal(400, "COMMAND_CONFLICT", List.of());
        UUID restaurantId = UUID.fromString(body.get("restaurantId").asString());
        long expectedVersion = body.get("expectedVersion").asLong();
        String reason = body.hasNonNull("reason") ? body.get("reason").asString() : "";
        String digest = tableId + "|" + sessionId + "|" + expectedVersion + "|" + body.get("requestedByUserId").asString()
                + "|" + reason;

        CloseRecord known = closes.get(commandId);
        if (known != null) {
            if (!known.digest().equals(digest)) return refusal(409, "COMMAND_CONFLICT", List.of());
            return replay(commandId, known);
        }
        FakeBackends.Table table = backends.tables.stream()
                .filter(t -> t.id().equals(tableId) && t.restaurantId().equals(restaurantId)).findFirst().orElse(null);
        if (table == null) return Response.json(404, "{\"status\":404}");
        String refusal = null;
        if (!sessionId.equals(table.sessionId())) refusal = "SESSION_NOT_OPEN";
        else if (table.sessionVersion() != expectedVersion) refusal = "SESSION_VERSION_CONFLICT";
        else if (!refuseCloseWith.isEmpty()) refusal = "CLOSURE_BLOCKED";
        if (refusal != null) {
            List<String> blockers = "CLOSURE_BLOCKED".equals(refusal) ? refuseCloseWith : List.of();
            closes.put(commandId, new CloseRecord(digest, "REJECTED", refusal, blockers, 0, 0, tableId, sessionId));
            return refusal(409, refusal, blockers);
        }
        if (fault == Fault.CLOSING_ONLY) {
            closes.put(commandId, new CloseRecord(digest, "CLOSING", null, List.of(), guestSessions, 0, tableId, sessionId));
            return Response.json(202, FakeBackends.write(answer(commandId, closes.get(commandId))));
        }
        backends.replace(table.with("CLEANING", null));
        int ended = endedGuestsReported >= 0 ? endedGuestsReported : guestSessions;
        closes.put(commandId, new CloseRecord(digest, "CLOSED", null, closedBlockers(), guestSessions, ended, tableId, sessionId));
        Response answer = fault == Fault.GARBAGE_ANSWER ? Response.json(200, "not json")
                : Response.json(200, FakeBackends.write(answer(commandId, closes.get(commandId))));
        return fault == Fault.LOST_ANSWER ? answer.delayed(1500) : answer;
    }

    private Response replay(UUID commandId, CloseRecord record) {
        return switch (record.state()) {
            case "CLOSED" -> Response.json(200, FakeBackends.write(answer(commandId, record)));
            case "CLOSING" -> Response.json(202, FakeBackends.write(answer(commandId, record)));
            default -> refusal(409, record.rejectionCode(), record.blockers());
        };
    }

    private synchronized Response closeStatus(Request request) {
        Matcher match = CLOSE_STATUS_PATH.matcher(request.path());
        match.matches();
        UUID commandId = UUID.fromString(match.group(3));
        CloseRecord record = closes.get(commandId);
        if (record == null) return Response.json(404, "{\"status\":404}");
        return Response.json(200, FakeBackends.write(answer(commandId, record)));
    }

    /** QR's recorded outcome of a close, as the command answer and the status answer both show it. */
    private Map<String, Object> answer(UUID commandId, CloseRecord record) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("commandId", commandId);
        answer.put("state", record.state());
        answer.put("tableId", record.tableId());
        answer.put("tableSessionId", record.sessionId());
        answer.put("closedAt", "CLOSED".equals(record.state()) ? CLOSED_AT : null);
        answer.put("guestSessions", record.guests() == 0 && "REJECTED".equals(record.state()) ? null
                : Map.of("total", record.guests(), "ended", record.endedGuests()));
        answer.put("table", FakeBackends.tableJson(backends.table(record.tableId())));
        answer.put("rejectionCode", record.rejectionCode());
        answer.put("blockers", record.blockers());
        return answer;
    }

    private static Response refusal(int status, String code, List<String> blockers) {
        return Response.json(status, FakeBackends.write(Map.of("status", status, "code", code, "blockers", blockers)));
    }

    // ----- cleaned -----

    private synchronized Response clean(Request request) {
        Fault fault = cleanFaults.poll();
        if (fault == Fault.SERVER_ERROR) return Response.json(503, "{}");
        if (fault == Fault.UNAUTHORIZED) return Response.json(401, "{}");
        if (fault == Fault.HANG_BEFORE_APPLY) return Response.json(504, "{}").delayed(1500);
        Matcher match = CLEAN_PATH.matcher(request.path());
        match.matches();
        UUID tableId = UUID.fromString(match.group(1));
        JsonNode body = FakeBackends.read(request.body());
        UUID commandId = UUID.fromString(body.get("commandId").asString());
        if (!commandId.toString().equals(request.header("Idempotency-Key"))) return refusal(400, "COMMAND_CONFLICT", List.of());
        UUID restaurantId = UUID.fromString(body.get("restaurantId").asString());
        long expectedVersion = body.get("expectedVersion").asLong();
        String digest = tableId + "|" + expectedVersion + "|" + body.get("requestedByUserId").asString();

        CleanRecord known = cleans.get(commandId);
        if (known != null) {
            if (!known.digest().equals(digest)) return refusal(409, "COMMAND_CONFLICT", List.of());
            return known.applied() ? Response.json(200, FakeBackends.write(cleanAnswer(commandId, "DUPLICATE", tableId)))
                    : refusal(409, known.rejectionCode(), List.of());
        }
        FakeBackends.Table table = backends.tables.stream()
                .filter(t -> t.id().equals(tableId) && t.restaurantId().equals(restaurantId)).findFirst().orElse(null);
        if (table == null) return Response.json(404, "{\"status\":404}");
        String refusal = null;
        if (table.sessionId() != null) refusal = "TABLE_SESSION_ACTIVE";
        else if (table.version() != expectedVersion) refusal = "TABLE_VERSION_CONFLICT";
        else if (!"CLEANING".equals(table.status())) refusal = "TABLE_NOT_CLEANING";
        if (refusal != null) {
            cleans.put(commandId, new CleanRecord(digest, false, refusal, tableId));
            return refusal(409, refusal, List.of());
        }
        backends.replace(table.with("AVAILABLE", null));
        cleans.put(commandId, new CleanRecord(digest, true, null, tableId));
        Response answer = fault == Fault.GARBAGE_ANSWER ? Response.json(200, "not json")
                : Response.json(200, FakeBackends.write(cleanAnswer(commandId, "APPLIED", tableId)));
        return fault == Fault.LOST_ANSWER ? answer.delayed(1500) : answer;
    }

    private synchronized Response cleanStatus(Request request) {
        Matcher match = CLEAN_STATUS_PATH.matcher(request.path());
        match.matches();
        UUID tableId = UUID.fromString(match.group(1));
        UUID commandId = UUID.fromString(match.group(2));
        CleanRecord record = cleans.get(commandId);
        if (record == null) return Response.json(404, "{\"status\":404}");
        if (!record.applied()) {
            Map<String, Object> rejected = new LinkedHashMap<>();
            rejected.put("commandId", commandId);
            rejected.put("status", "REJECTED");
            rejected.put("rejectionCode", record.rejectionCode());
            rejected.put("table", null);
            return Response.json(200, FakeBackends.write(rejected));
        }
        return Response.json(200, FakeBackends.write(cleanAnswer(commandId, "APPLIED", tableId)));
    }

    private Map<String, Object> cleanAnswer(UUID commandId, String status, UUID tableId) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("commandId", commandId);
        answer.put("status", status);
        answer.put("rejectionCode", null);
        answer.put("table", FakeBackends.tableJson(backends.table(tableId)));
        return answer;
    }
}
