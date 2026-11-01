package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.entity.TableCommand;
import com.restofy.waiter.domain.enums.TableCommandStatus;
import com.restofy.waiter.domain.exception.CallConflictException;
import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.infrastructure.config.TableCommandReconcilerProperties;
import com.restofy.waiter.infrastructure.persistence.repository.TableCommandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Finds the outcome of table commands whose answer was lost, so that closing a table does not depend on the
 * panel's request still being alive: when the staff member's connection broke, the response was lost or the
 * process restarted after QR had closed the session, QR's own record decides what happened and the calls of
 * that session end as a consequence. The reconciler only asks QR what it recorded for a command id; it never
 * sends a command again (that stays a decision of the staff member, with the same Idempotency-Key). It also ends
 * calls that were written into a session QR had already confirmed closed.
 *
 * <p>A QR outage postpones the next check of the affected commands and nothing else: no call is reopened, no
 * session is touched, and a Notification outage cannot get in the way because the calls' events are queued by
 * the durable delivery path.
 */
@Service
public class TableCommandReconciler {

    private static final Logger log = LoggerFactory.getLogger(TableCommandReconciler.class);

    /** Counters of one round, mainly for tests and logs. */
    public record Round(int checked, int settled, int postponed, int lateCallsEnded) {
    }

    private final TableCommandRepository commands;
    private final TableCommandService service;
    private final TableCommandReconcilerProperties policy;

    public TableCommandReconciler(TableCommandRepository commands, TableCommandService service,
                                  TableCommandReconcilerProperties policy) {
        this.commands = commands;
        this.service = service;
        this.policy = policy;
    }

    public Round reconcileDue(Instant now) {
        List<TableCommand> due = commands.findDueForReconciliation(TableCommandStatus.OPEN,
                now.minus(policy.minAge()), now.minus(policy.maxAge()), PageRequest.of(0, policy.batchSize()));
        int settled = 0;
        int postponed = 0;
        for (TableCommand command : due) {
            try {
                TableCommandService.Result result = service.reconcile(command);
                if (!result.command().isOpen()) settled++;
            } catch (DependencyException | CallConflictException unavailable) {
                service.deferNextCheck(command.getId());
                postponed++;
                log.warn("Table command {} could not be reconciled now; the next check is postponed: {}",
                        command.getId(), unavailable.getMessage());
            } catch (RuntimeException unexpected) {
                service.deferNextCheck(command.getId());
                postponed++;
                log.error("Unexpected error while reconciling table command {}", command.getId(), unexpected);
            }
        }
        int lateCalls = service.endLateCalls(policy.batchSize());
        return new Round(due.size(), settled, postponed, lateCalls);
    }
}
