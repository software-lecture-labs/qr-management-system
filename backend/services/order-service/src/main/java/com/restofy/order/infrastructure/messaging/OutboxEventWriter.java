package com.restofy.order.infrastructure.messaging;

import com.restofy.order.domain.event.EventDestination;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox writer. It must run inside the business transaction ({@code MANDATORY}), so an event exists
 * if and only if the change it describes was committed. It only records the event and one PENDING delivery per
 * destination; there is no dispatcher yet, so nothing has been delivered to any other service.
 */
@Slf4j
@Component
public class OutboxEventWriter {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public OutboxEventWriter(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID eventId, UUID restaurantId, String aggregateType, UUID aggregateId, long aggregateVersion,
                      String eventType, int schemaVersion, String correlationId, Object payload, Instant occurredAt,
                      List<EventDestination> destinations) {
        jdbc.update("""
                INSERT INTO outbox_events (id, restaurant_id, aggregate_type, aggregate_id, aggregate_version,
                                           event_type, schema_version, correlation_id, payload, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, eventId, restaurantId, aggregateType, aggregateId, aggregateVersion, eventType, schemaVersion,
                correlationId, json.writeValueAsString(payload), Timestamp.from(occurredAt));
        for (EventDestination destination : destinations) {
            jdbc.update("""
                    INSERT INTO outbox_deliveries (id, event_id, destination, status, attempt_count, next_attempt_at)
                    VALUES (?, ?, ?, 'PENDING', 0, ?)
                    """, UUID.randomUUID(), eventId, destination.name(), Timestamp.from(occurredAt));
        }
        log.info("Outbox event recorded: type={} eventId={} aggregateId={} destinations={}", eventType, eventId,
                aggregateId, destinations);
    }
}
