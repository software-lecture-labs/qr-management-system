package com.restofy.waiter.support;

import com.restofy.waiter.support.DownstreamStub.Request;
import com.restofy.waiter.support.DownstreamStub.Response;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Local stand-in for Notification that behaves like a compliant receiver of the DRAFT contract: it answers
 * DUPLICATE for an event id it applied before, STALE for a call version it already has or has surpassed, and
 * APPLIED otherwise. Faults are injected per test. A passing test proves the Waiter side against this stub,
 * never against the real Notification service.
 */
public final class FakeNotification {

    public enum Fault {
        /** The service answers 503 without applying anything. */
        UNAVAILABLE(503),
        /** The service rejects Waiter (no service identity yet). */
        REJECTED(401),
        /** The service rejects the body as invalid. */
        BAD_REQUEST(400),
        /** The event is applied but the answer arrives after Waiter's read timeout: the answer is lost. */
        LOST_ANSWER(200),
        /** The service hangs and never applies the event within the read timeout. */
        HANG(200);

        final int status;

        Fault(int status) {
            this.status = status;
        }
    }

    public record Received(UUID eventId, String idempotencyKey, JsonNode body, String outcome) {
        public long callVersion() {
            return body.get("call").get("version").asLong();
        }

        public UUID callId() {
            return UUID.fromString(body.get("call").get("id").asString());
        }

        public String callStatus() {
            return body.get("call").get("status").asString();
        }
    }

    private final List<Received> received = new ArrayList<>();
    private final Set<UUID> applied = new HashSet<>();
    private final Map<UUID, Long> versionByCall = new HashMap<>();
    private final Deque<Fault> faults = new ArrayDeque<>();

    public FakeNotification installOn(DownstreamStub stub) {
        stub.route("POST", "/api/v1/internal/service-call-events", this::handle);
        return this;
    }

    public synchronized void failNext(Fault fault, int times) {
        for (int index = 0; index < times; index++) faults.add(fault);
    }

    /** Makes Notification already know the call at {@code version}, e.g. because a newer event arrived first. */
    public synchronized void preload(UUID callId, long version) {
        versionByCall.put(callId, version);
    }

    /** Every request that reached the stub, including duplicates and stale events, in arrival order. */
    public synchronized List<Received> received() {
        return List.copyOf(received);
    }

    public synchronized List<Received> receivedWithOutcome(String outcome) {
        return received.stream().filter(r -> r.outcome().equals(outcome)).toList();
    }

    public synchronized void clear() {
        received.clear();
        applied.clear();
        versionByCall.clear();
        faults.clear();
    }

    private synchronized Response handle(Request request) {
        Fault fault = faults.poll();
        if (fault == Fault.UNAVAILABLE || fault == Fault.REJECTED || fault == Fault.BAD_REQUEST) {
            return Response.json(fault.status, "{\"status\":" + fault.status + "}");
        }
        if (fault == Fault.HANG) return Response.json(200, "{}").delayed(1500);
        JsonNode body = FakeBackends.read(request.body());
        UUID eventId = UUID.fromString(body.get("eventId").asString());
        if (!eventId.toString().equals(request.header("Idempotency-Key"))) {
            return Response.json(400, "{\"status\":400}");
        }
        UUID callId = UUID.fromString(body.get("call").get("id").asString());
        long version = body.get("call").get("version").asLong();
        String outcome;
        if (applied.contains(eventId)) {
            outcome = "DUPLICATE";
        } else if (versionByCall.containsKey(callId) && versionByCall.get(callId) >= version) {
            outcome = "STALE";
            applied.add(eventId);
        } else {
            outcome = "APPLIED";
            applied.add(eventId);
            versionByCall.put(callId, version);
        }
        received.add(new Received(eventId, request.header("Idempotency-Key"), body, outcome));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("eventId", eventId);
        answer.put("outcome", outcome);
        Response response = Response.json(200, FakeBackends.write(answer));
        return fault == Fault.LOST_ANSWER ? response.delayed(1500) : response;
    }
}
