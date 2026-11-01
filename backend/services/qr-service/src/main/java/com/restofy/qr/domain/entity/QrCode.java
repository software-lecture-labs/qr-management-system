package com.restofy.qr.domain.entity;

import com.restofy.qr.domain.enums.QrCodeStatus;
import com.restofy.qr.domain.exception.QrCodeConflictException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "qr_codes")
public class QrCode {
    @Id
    private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;
    @Column(name = "table_id", nullable = false, updatable = false)
    private UUID tableId;
    @Column(name = "token_digest", nullable = false, updatable = false, length = 64)
    private String tokenDigest;
    @Column(name = "encrypted_payload", nullable = false, updatable = false, columnDefinition = "text")
    private String encryptedPayload;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QrCodeStatus status;
    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;
    @Version
    private long version;

    protected QrCode() {
        // JPA
    }

    public QrCode(UUID id, UUID restaurantId, UUID tableId, String tokenDigest, String encryptedPayload) {
        this.id = Objects.requireNonNull(id);
        this.restaurantId = Objects.requireNonNull(restaurantId);
        this.tableId = Objects.requireNonNull(tableId);
        if (tokenDigest == null || !tokenDigest.matches("[0-9a-f]{64}")
                || encryptedPayload == null || !encryptedPayload.matches("v1:[A-Za-z0-9_-]{95}")) {
            throw new IllegalArgumentException("Invalid QR credential storage.");
        }
        this.tokenDigest = tokenDigest;
        this.encryptedPayload = encryptedPayload;
        this.status = QrCodeStatus.ACTIVE;
        this.issuedAt = Instant.now();
    }

    public void requireVersion(long expectedVersion) {
        if (expectedVersion < 0 || version != expectedVersion) {
            throw new QrCodeConflictException();
        }
    }

    public boolean isUsableAt(Instant now) {
        Objects.requireNonNull(now);
        return status == QrCodeStatus.ACTIVE && revokedAt == null && !issuedAt.isAfter(now)
                && (expiresAt == null || expiresAt.isAfter(now));
    }

    public void changeActive(boolean enabled, long expectedVersion) {
        requireVersion(expectedVersion);
        if (status == QrCodeStatus.REVOKED) {
            throw new QrCodeConflictException();
        }
        status = enabled ? QrCodeStatus.ACTIVE : QrCodeStatus.INACTIVE;
    }

    public void revoke(long expectedVersion) {
        requireVersion(expectedVersion);
        if (status != QrCodeStatus.ACTIVE) {
            throw new QrCodeConflictException();
        }
        status = QrCodeStatus.REVOKED;
        revokedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getRestaurantId() { return restaurantId; }
    public UUID getTableId() { return tableId; }
    public String getTokenDigest() { return tokenDigest; }
    public String getEncryptedPayload() { return encryptedPayload; }
    public QrCodeStatus getStatus() { return status; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public long getVersion() { return version; }
}
