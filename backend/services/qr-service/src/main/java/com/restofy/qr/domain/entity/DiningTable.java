package com.restofy.qr.domain.entity;

import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.exception.TableConflictException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "dining_tables")
public class DiningTable {
    @Id
    private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;
    @Column(name = "table_number", nullable = false, length = 40)
    private String tableNumber;
    @Column(name = "display_name", length = 120)
    private String displayName;
    private Integer capacity;
    @Enumerated(EnumType.STRING)
    @Column(name = "operational_status", nullable = false, length = 20)
    private TableOperationalStatus operationalStatus;
    @Column(name = "is_active", nullable = false)
    private boolean active;
    @Version
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DiningTable() {
        // JPA
    }

    public DiningTable(UUID restaurantId, String tableNumber, String displayName, Integer capacity) {
        this.id = UUID.randomUUID();
        this.restaurantId = Objects.requireNonNull(restaurantId, "Restaurant is required.");
        updateDetails(tableNumber, displayName, capacity);
        this.active = true;
        this.operationalStatus = TableOperationalStatus.AVAILABLE;
    }

    public static String normalizeNumber(String value) {
        if (value == null || value.strip().isBlank() || value.strip().length() > 40) {
            throw new IllegalArgumentException("Table number must contain 1 to 40 characters.");
        }
        return value.strip();
    }

    public void updateDetails(String number, String name, Integer seats) {
        String normalizedNumber = normalizeNumber(number);
        String normalizedName = name == null || name.strip().isBlank() ? null : name.strip();
        if (normalizedName != null && normalizedName.length() > 120) {
            throw new IllegalArgumentException("Display name must contain at most 120 characters.");
        }
        if (seats != null && seats <= 0) {
            throw new IllegalArgumentException("Capacity must be positive.");
        }
        this.tableNumber = normalizedNumber;
        this.displayName = normalizedName;
        this.capacity = seats;
    }

    public void requireVersion(long expectedVersion) {
        if (expectedVersion < 0 || version != expectedVersion) {
            throw new TableConflictException("Table has changed. Reload before updating.");
        }
    }

    public void changeActive(boolean enabled) {
        if (active == enabled) {
            return;
        }
        if (operationalStatus != TableOperationalStatus.AVAILABLE
                && operationalStatus != TableOperationalStatus.DISABLED) {
            throw new TableConflictException("An occupied or cleaning table cannot be activated or disabled.");
        }
        this.active = enabled;
        this.operationalStatus = enabled ? TableOperationalStatus.AVAILABLE : TableOperationalStatus.DISABLED;
    }

    public void occupy() {
        if (!active || operationalStatus != TableOperationalStatus.AVAILABLE) {
            throw new TableConflictException("Only an active available table can open a main session.");
        }
        operationalStatus = TableOperationalStatus.OCCUPIED;
    }

    public void releaseAfterIdle() {
        if (!active || operationalStatus != TableOperationalStatus.OCCUPIED) {
            throw new TableConflictException("Only an active occupied table can be released after idle expiry.");
        }
        operationalStatus = TableOperationalStatus.AVAILABLE;
    }

    public void beginCleaning() {
        if (!active || operationalStatus != TableOperationalStatus.OCCUPIED) {
            throw new TableConflictException("Only an active occupied table can enter cleaning.");
        }
        operationalStatus = TableOperationalStatus.CLEANING;
    }

    public void finishCleaning() {
        if (!active || operationalStatus != TableOperationalStatus.CLEANING) {
            throw new TableConflictException("Only an active cleaning table can become available.");
        }
        operationalStatus = TableOperationalStatus.AVAILABLE;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getRestaurantId() { return restaurantId; }
    public String getTableNumber() { return tableNumber; }
    public String getDisplayName() { return displayName; }
    public Integer getCapacity() { return capacity; }
    public TableOperationalStatus getOperationalStatus() { return operationalStatus; }
    public boolean isActive() { return active; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
