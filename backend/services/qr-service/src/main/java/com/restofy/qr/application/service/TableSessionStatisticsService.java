package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.TableSessionStatisticsResponse;
import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.TableNotFoundException;
import com.restofy.qr.infrastructure.persistence.repository.*;
import com.restofy.qr.infrastructure.security.TableManagementAccess;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TableSessionStatisticsService {
    private final TableManagementAccess access;
    private final DiningTableRepository tables;
    private final TableSessionRepository sessions;
    private final GuestSessionRepository guests;
    private final QrScanEventRepository scans;
    private final Clock clock;
    private final SessionOrderProtection protection;
    private final TransactionTemplate reads;

    public TableSessionStatisticsService(TableManagementAccess access, DiningTableRepository tables,
                                          TableSessionRepository sessions, GuestSessionRepository guests,
                                          QrScanEventRepository scans, Clock clock, SessionOrderProtection protection,
                                          PlatformTransactionManager manager) {
        this.access = access; this.tables = tables; this.sessions = sessions; this.guests = guests;
        this.scans = scans; this.clock = clock; this.protection = protection;
        reads = new TransactionTemplate(manager); reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public TableSessionStatisticsResponse get(UUID tableId) {
        UUID restaurantId = access.requireRestaurantId();
        var location = sessions.findByRestaurantIdAndTableIdAndStatusIn(restaurantId, tableId,
                List.of(TableSessionStatus.OPEN, TableSessionStatus.CLOSING));
        // A caller may already hold locks. Preserve REQUIRES_NEW local statistics without remote I/O under those locks.
        var state = TransactionSynchronizationManager.isActualTransactionActive() ? null
                : location.map(main -> protection.inspect(restaurantId, main.getId())).orElse(null);
        return reads.execute(status -> snapshot(restaurantId, tableId, state));
    }

    private TableSessionStatisticsResponse snapshot(UUID restaurantId, UUID tableId, OrderSessionState state) {
        var table = tables.findByRestaurantIdAndId(restaurantId, tableId).orElseThrow(TableNotFoundException::new);
        var now = clock.instant();
        var live = sessions.findByRestaurantIdAndTableIdAndStatusIn(restaurantId, tableId,
                List.of(TableSessionStatus.OPEN, TableSessionStatus.CLOSING));
        long tableScans = scans.countByRestaurantIdAndTableId(restaurantId, tableId);
        if (live.isEmpty()) { return new TableSessionStatisticsResponse(restaurantId, tableId, null, null, 0, 0, tableScans, now); }
        var main = live.orElseThrow();
        boolean usable = table.isActive() && table.getOperationalStatus() == TableOperationalStatus.OCCUPIED
                && main.isOpenAt(now, protection.protectsMain(state, main, now) || protection.mainCancellationWindow(state, main, now));
        Set<UUID> protectedIds = new java.util.HashSet<>();
        if (protection.current(state, restaurantId, main.getId(), now)) {
            protectedIds.addAll(state.protectedGuests());
            for (var guest : guests.findByRestaurantIdAndTableSessionIdAndStatus(restaurantId, main.getId(), GuestSessionStatus.ACTIVE)) {
                if (protection.guestCancellationWindow(state, main, guest, now)) { protectedIds.add(guest.getId()); }
            }
        }
        long active = usable ? guests.countUsable(restaurantId, main.getId(), GuestSessionStatus.ACTIVE,
                now.truncatedTo(ChronoUnit.MICROS), protectedIds) : 0;
        return new TableSessionStatisticsResponse(restaurantId, tableId, main.getId(), main.getStatus(), active,
                scans.countByRestaurantIdAndTableSessionId(restaurantId, main.getId()), tableScans, now);
    }
}
