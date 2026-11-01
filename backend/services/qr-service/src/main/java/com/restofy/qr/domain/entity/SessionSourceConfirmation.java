package com.restofy.qr.domain.entity;

import com.restofy.qr.domain.SessionTransition.Confirmation;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Append-only acknowledgement of QR's local outcome, never an Order or Payment ledger. */
@Entity
@Table(name = "session_source_confirmations")
public class SessionSourceConfirmation {
    @Id @Column(name = "operation_id") private UUID operationId;
    @Column(name = "restaurant_id", nullable = false, updatable = false) private UUID restaurantId;
    @Column(name = "confirmation_json", nullable = false, updatable = false, columnDefinition = "text") private String confirmationJson;
    @Column(name = "received_at", nullable = false, updatable = false) private Instant receivedAt;

    protected SessionSourceConfirmation() { }

    public SessionSourceConfirmation(Confirmation confirmation, String json, Instant receivedAt) {
        this.operationId = confirmation.operationId(); this.restaurantId = confirmation.restaurantId();
        this.confirmationJson = json; this.receivedAt = receivedAt;
    }

    public String getConfirmationJson() { return confirmationJson; }
}
