package com.restofy.waiter.infrastructure.persistence.repository;

import com.restofy.waiter.domain.entity.TableCommand;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.TableCommandStatus;
import com.restofy.waiter.domain.enums.TableCommandType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TableCommandRepository extends JpaRepository<TableCommand, UUID> {

    /** Tenant- and table-scoped lookup: another restaurant's or another table's command id looks like a missing one. */
    Optional<TableCommand> findByIdAndRestaurantIdAndTableId(UUID id, UUID restaurantId, UUID tableId);

    Optional<TableCommand> findByRestaurantIdAndRequestedByUserIdAndIdempotencyKey(
            UUID restaurantId, UUID requestedByUserId, String idempotencyKey);

    /** Commands of the restaurant without a final answer of QR, newest first (one small query per view). */
    List<TableCommand> findByRestaurantIdAndStatusInOrderByCreatedAtDesc(UUID restaurantId,
                                                                         Collection<TableCommandStatus> statuses);

    boolean existsByRestaurantIdAndTableSessionIdAndTypeAndStatus(UUID restaurantId, UUID tableSessionId,
                                                                  TableCommandType type, TableCommandStatus status);

    boolean existsByRestaurantIdAndTableIdAndTypeAndStatusIn(UUID restaurantId, UUID tableId, TableCommandType type,
                                                             Collection<TableCommandStatus> statuses);

    /** Open commands that QR has not been asked about for a while, oldest first: the reconciler's work list. */
    @Query("""
            select c from TableCommand c
            where c.status in :statuses and c.updatedAt <= :checkedBefore and c.createdAt >= :notOlderThan
            order by c.updatedAt asc, c.id asc
            """)
    List<TableCommand> findDueForReconciliation(Collection<TableCommandStatus> statuses, Instant checkedBefore,
                                                Instant notOlderThan, Pageable page);

    /**
     * Completed closes whose session still has an unresolved call. A call can be written between QR's check of a
     * guest and the close itself; it belongs to a session QR already confirmed closed, so it ends with it.
     */
    default List<TableCommand> findClosedSessionsWithUnresolvedCalls(Pageable page) {
        return findByTypeAndStatusWithCallsIn(TableCommandType.CLOSE, TableCommandStatus.COMPLETED,
                ServiceRequestStatus.UNRESOLVED, page);
    }

    /** The enum values are bound as parameters, so the query needs no fully qualified enum literals. */
    @Query("""
            select c from TableCommand c
            where c.type = :type and c.status = :status
              and exists (select 1 from ServiceRequest s
                          where s.restaurantId = c.restaurantId and s.tableSessionId = c.tableSessionId
                            and s.status in :callStatuses)
            order by c.completedAt asc, c.id asc
            """)
    List<TableCommand> findByTypeAndStatusWithCallsIn(TableCommandType type, TableCommandStatus status,
                                                      Collection<ServiceRequestStatus> callStatuses, Pageable page);
}
