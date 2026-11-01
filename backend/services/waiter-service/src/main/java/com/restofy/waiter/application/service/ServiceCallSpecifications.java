package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.entity.ServiceRequest;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the call listing query from validated filter values only. Every value is a bind parameter and the
 * restaurant condition is always present, so a listing can never cross a tenant boundary.
 */
final class ServiceCallSpecifications {

    private ServiceCallSpecifications() {
    }

    static Specification<ServiceRequest> matching(UUID restaurantId, CallFilter filter) {
        return (root, query, builder) -> {
            List<Predicate> conditions = new ArrayList<>();
            conditions.add(builder.equal(root.get("restaurantId"), restaurantId));
            if (!filter.types().isEmpty()) conditions.add(root.get("requestType").in(filter.types()));
            if (!filter.statuses().isEmpty()) conditions.add(root.get("status").in(filter.statuses()));
            if (filter.tableId() != null) conditions.add(builder.equal(root.get("tableId"), filter.tableId()));
            if (filter.tableSessionId() != null) {
                conditions.add(builder.equal(root.get("tableSessionId"), filter.tableSessionId()));
            }
            if (filter.createdFrom() != null) {
                conditions.add(builder.greaterThanOrEqualTo(root.get("createdAt"), filter.createdFrom()));
            }
            if (filter.createdTo() != null) {
                conditions.add(builder.lessThan(root.get("createdAt"), filter.createdTo()));
            }
            return builder.and(conditions.toArray(Predicate[]::new));
        };
    }
}
