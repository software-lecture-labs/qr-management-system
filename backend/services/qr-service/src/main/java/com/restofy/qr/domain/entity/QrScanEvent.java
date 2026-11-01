package com.restofy.qr.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "qr_scan_events")
public class QrScanEvent {
    @Id
    private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;
    @Column(name = "table_id", nullable = false, updatable = false)
    private UUID tableId;
    @Column(name = "qr_code_id", nullable = false, updatable = false)
    private UUID qrCodeId;
    @Column(name = "table_session_id", nullable = false, updatable = false)
    private UUID tableSessionId;
    @Column(name = "guest_session_id", nullable = false, updatable = false)
    private UUID guestSessionId;
    @Column(name = "scanned_at", nullable = false, updatable = false)
    private Instant scannedAt;
    @Column(nullable = false, updatable = false, length = 20)
    private String outcome;

    protected QrScanEvent() {
        // JPA
    }

    public QrScanEvent(UUID restaurantId, UUID tableId, UUID qrCodeId, UUID tableSessionId, UUID guestSessionId, Instant scannedAt) {
        this.id = UUID.randomUUID();
        this.restaurantId = Objects.requireNonNull(restaurantId);
        this.tableId = Objects.requireNonNull(tableId);
        this.qrCodeId = Objects.requireNonNull(qrCodeId);
        this.tableSessionId = Objects.requireNonNull(tableSessionId);
        this.guestSessionId = Objects.requireNonNull(guestSessionId);
        this.scannedAt = Objects.requireNonNull(scannedAt);
        this.outcome = "ACCEPTED";
    }

    public UUID getId() { return id; }
    public UUID getRestaurantId() { return restaurantId; }
    public UUID getTableId() { return tableId; }
    public UUID getQrCodeId() { return qrCodeId; }
    public UUID getTableSessionId() { return tableSessionId; }
    public UUID getGuestSessionId() { return guestSessionId; }
    public Instant getScannedAt() { return scannedAt; }
    public String getOutcome() { return outcome; }
}
