package com.restofy.qr.domain.entity;

import com.restofy.qr.domain.SessionTransition.*;
import com.restofy.qr.domain.exception.SessionClosureConflictException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable QR intent and immutable LOCAL receipt; no Order/Payment business state is copied here. */
@Entity
@Table(name = "session_transition_operations")
public class SessionTransitionOperation {
    @Id private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false) private UUID restaurantId;
    @Column(name = "table_session_id", nullable = false, updatable = false) private UUID sessionId;
    @Column(name = "request_json", nullable = false, updatable = false, columnDefinition = "text") private String requestJson;
    @Column(name = "request_digest", nullable = false, updatable = false, length = 64) private String requestDigest;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Stage stage;
    @Column(name = "result_json", columnDefinition = "text") private String resultJson;
    @Column(name = "requested_at", nullable = false, updatable = false) private Instant requestedAt;
    @Column(name = "last_attempt_at") private Instant lastAttemptAt;
    @Column(nullable = false) private int attempts;
    @Version private long version;

    protected SessionTransitionOperation() { }

    public SessionTransitionOperation(Command command, String json) {
        id = command.operationId(); restaurantId = command.snapshot().restaurantId(); sessionId = command.snapshot().sessionId();
        requestJson = Objects.requireNonNull(json); requestDigest = command.digest();
        requestedAt = command.requestedAt(); stage = Stage.REQUESTED;
    }

    public void attempt(Instant now) {
        if (now.isBefore(requestedAt) || (lastAttemptAt != null && now.isBefore(lastAttemptAt))) {
            throw new SessionClosureConflictException();
        }
        attempts = Math.incrementExact(attempts); lastAttemptAt = now;
    }

    public void apply(String receipt) {
        if (stage != Stage.REQUESTED || resultJson != null) { throw new SessionClosureConflictException(); }
        resultJson = Objects.requireNonNull(receipt); stage = Stage.APPLIED;
    }

    public void requireAbort() {
        if (stage != Stage.REQUESTED && stage != Stage.ABORTING) { throw new SessionClosureConflictException(); }
        stage = Stage.ABORTING;
    }

    public void acknowledge() {
        if (stage != Stage.APPLIED || resultJson == null) { throw new SessionClosureConflictException(); }
        stage = Stage.COMPLETED;
    }

    public void abort() {
        if (stage != Stage.ABORTING || resultJson != null) { throw new SessionClosureConflictException(); }
        stage = Stage.ABORTED;
    }

    public UUID getId() { return id; }
    public UUID getRestaurantId() { return restaurantId; }
    public UUID getSessionId() { return sessionId; }
    public String getRequestJson() { return requestJson; }
    public String getRequestDigest() { return requestDigest; }
    public Stage getStage() { return stage; }
    public String getResultJson() { return resultJson; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public int getAttempts() { return attempts; }
    public long getVersion() { return version; }
}
