package com.restofy.order.application.service;

import com.restofy.order.api.dto.response.CancellationReportResponse;
import com.restofy.order.api.dto.response.CancellationReportResponse.Item;
import com.restofy.order.api.dto.response.CancellationReportResponse.ReasonCount;
import com.restofy.order.api.dto.response.CancellationReportResponse.Summary;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.enums.CancellationReasonCode;
import com.restofy.order.domain.enums.StaffRole;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.api.dto.response.CancellationResponse;
import com.restofy.order.infrastructure.persistence.CancellationReportStore;
import com.restofy.order.infrastructure.persistence.CancellationReportStore.Filter;
import com.restofy.order.infrastructure.persistence.CancellationReportStore.Line;
import com.restofy.order.infrastructure.persistence.CancellationReportStore.ReasonLine;
import com.restofy.order.infrastructure.config.CancellationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

/**
 * The cancellation report for the restaurant's administrator: who cancelled what, why, and what it meant for the money.
 * ADMIN only, and only the administrator's own restaurant (taken from the verified identity, never from the request).
 * The page and the summary are read in one read-only transaction so they describe the same data.
 */
@Service
public class CancellationReportService {

    private final CancellationReportStore store;
    private final CancellationProperties properties;
    private final Clock clock;

    public CancellationReportService(CancellationReportStore store, CancellationProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CancellationReportResponse report(StaffActor admin, CancellationReportQuery query) {
        if (admin.role() != StaffRole.ADMIN) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "Only an administrator may read the cancellation report");
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(properties.pendingAttentionAfter());
        Filter filter = new Filter(admin.restaurantId(), query.from(), query.to(),
                query.reasonCode() == null ? null : query.reasonCode().name(),
                query.scope() == null ? null : query.scope().name(),
                query.status() == null ? null : query.status().name(), query.overdueOnly() ? cutoff : null);
        long total = store.count(filter);
        List<Line> lines = store.page(filter, query.oldestFirst(), query.limit(), (long) query.page() * query.limit());
        Map<UUID, List<CancellationReportResponse.Reason>> reasons = new HashMap<>();
        for (ReasonLine reason : store.reasonsOf(lines.stream().map(Line::id).toList())) {
            reasons.computeIfAbsent(reason.cancellationId(), id -> new ArrayList<>())
                    .add(new CancellationReportResponse.Reason(reason.source(), reason.code(), reason.text()));
        }
        List<Item> items = lines.stream().map(line -> item(line, reasons.getOrDefault(line.id(), List.of()), now, cutoff))
                .toList();
        List<ReasonCount> byReason = store.countByReason(filter).stream()
                .map(count -> new ReasonCount(count.code(),
                        CancellationReasonCode.parse(count.code()).map(CancellationReasonCode::kitchenGroup).orElse(null),
                        count.count())).toList();
        var waiting = store.pending(filter, cutoff);
        var pending = new CancellationReportResponse.Pending(properties.pendingAttentionAfter().toSeconds(),
                waiting.overdueCount(), waiting.oldestRequestedAt());
        var summary = new Summary(total, store.countByStatus(filter), store.totals(filter), byReason,
                store.countWithoutReason(filter), pending);
        int pages = (int) ((total + query.limit() - 1) / query.limit());
        return new CancellationReportResponse(items, query.page(), query.limit(), total, pages, summary);
    }

    private static Item item(Line line, List<CancellationReportResponse.Reason> reasons, Instant now, Instant cutoff) {
        var amounts = new CancellationResponse.Amounts(line.currency(), line.previousDueMinor(), line.cancelledGrossMinor(),
                line.cancelledNetMinor(), line.cancelledVatMinor(), line.newDueMinor(), line.collectedMinor(),
                line.refundRequiredMinor(), line.refundedMinor());
        boolean waiting = "REQUESTED".equals(line.status());
        Long waitingFor = waiting ? Math.max(0L, Duration.between(line.requestedAt(), now).toSeconds()) : null;
        var requester = actor(line);
        return new Item(line.id(), line.orderId(), line.orderNo(), line.tableId(), line.scope(), line.status(),
                line.kitchenConfirmationRequired(), line.decisionSource(), requester, line.requestedAt(),
                line.decidedAt(), line.rejectionCode(), line.units(), reasons, reasons.isEmpty(), amounts, waitingFor,
                waiting && line.requestedAt().isBefore(cutoff));
    }

    /** This report is ADMIN only: a staff member's or a service's id is shown, and a service's claim apart from it. */
    private static CancellationResponse.Actor actor(Line line) {
        String type = line.requestedByType();
        boolean identified = "STAFF".equals(type) || "SERVICE".equals(type);
        return new CancellationResponse.Actor(type, line.requestedByRole(), identified ? line.requestedById() : null,
                line.reportedStaffId() == null ? null : line.reportedStaffId().toString());
    }
}
