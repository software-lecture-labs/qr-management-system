package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.entity.OrderServeCommand;
import com.restofy.waiter.domain.enums.ServeCommandStatus;
import com.restofy.waiter.domain.exception.ServeRejectedException;
import com.restofy.waiter.infrastructure.client.order.OrderClient;
import com.restofy.waiter.infrastructure.client.order.OrderRejectedException;
import com.restofy.waiter.infrastructure.client.order.ServeResponseDto;
import com.restofy.waiter.infrastructure.client.order.ServeStatusDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServeRulesUnitTests {

    private static final Instant NOW = Instant.parse("2026-10-16T09:00:00Z");

    private static OrderServeCommand command() {
        return OrderServeCommand.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 4,
                UUID.randomUUID(), "key-00000001", "a".repeat(64), NOW);
    }

    @Test
    void aNewCommandIsPendingAndUnanswered() {
        OrderServeCommand command = command();
        assertEquals(ServeCommandStatus.PENDING, command.getStatus());
        assertTrue(command.isUnanswered());
        assertNull(command.getCompletedAt());
        assertNull(command.getVersion(), "The version is assigned by persistence");
    }

    @Test
    void unansweredCommandsCanBeRetriedAndOnlyOrdersAnswerCompletesThem() {
        OrderServeCommand command = command();
        command.markUnknown(NOW.plusSeconds(1));
        assertEquals(ServeCommandStatus.UNKNOWN, command.getStatus());
        assertTrue(command.isUnanswered());
        command.markInFlight(NOW.plusSeconds(2));
        assertEquals(ServeCommandStatus.PENDING, command.getStatus());
        command.confirm(NOW.plusSeconds(3));
        assertEquals(ServeCommandStatus.CONFIRMED, command.getStatus());
        assertEquals(NOW.plusSeconds(3), command.getCompletedAt());
        assertFalse(command.isUnanswered());
    }

    @Test
    void anAnswerOfOrderIsFinalAndNeverReversed() {
        OrderServeCommand confirmed = command();
        confirmed.confirm(NOW);
        assertThrows(IllegalStateException.class, () -> confirmed.reject("ORDER_CANCELLED", NOW));
        assertThrows(IllegalStateException.class, () -> confirmed.markUnknown(NOW));
        assertThrows(IllegalStateException.class, () -> confirmed.markInFlight(NOW));

        OrderServeCommand rejected = command();
        rejected.reject("ORDER_NOT_READY", NOW);
        assertEquals("ORDER_NOT_READY", rejected.getRejectionCode());
        assertThrows(IllegalStateException.class, () -> rejected.confirm(NOW));
        assertThrows(IllegalStateException.class, () -> rejected.markUnknown(NOW));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDER_VERSION_CONFLICT", "ORDER_SESSION_MISMATCH", "ORDER_NOT_READY", "ORDER_CANCELLED",
            "ORDER_ALREADY_SERVED", "ORDER_NOT_DELIVERABLE", "ORDER_COMMAND_CONFLICT", "ORDER_NOT_FOUND"})
    void knownRefusalReasonsArePassedOn(String code) {
        assertEquals(code, OrderClient.knownRejection(code));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"<script>", "ORDER_PAID", "order_not_ready", "DEPENDENCY_REJECTED", "xxxxxxxxxx"})
    void anyOtherReasonBecomesTheGenericOne(String code) {
        assertEquals(OrderRejectedException.GENERIC, OrderClient.knownRejection(code));
    }

    @Test
    void rejectionsMapToTheirHttpStatus() {
        assertStatus(404, OrderRejectedException.NOT_FOUND);
        assertStatus(502, OrderServeService.DEPENDENCY_REJECTED);
        for (String code : new String[]{"ORDER_NOT_READY", "ORDER_CANCELLED", "ORDER_VERSION_CONFLICT", OrderRejectedException.GENERIC}) {
            assertStatus(409, code);
        }
    }

    private static void assertStatus(int expected, String code) {
        OrderServeCommand command = command();
        command.reject(code, NOW);
        ServeRejectedException exception = OrderServeService.rejection(command);
        assertEquals(expected, exception.status());
        assertEquals(code, exception.code());
        assertEquals(command.getId(), exception.commandId());
    }

    @Test
    void anAppliedAnswerMustBeAboutAServedOrder() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new ServeResponseDto(null, ServeResponseDto.Outcome.APPLIED, null));
        assertThrows(IllegalArgumentException.class, () -> new ServeResponseDto(id, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ServeResponseDto(id, ServeResponseDto.Outcome.APPLIED, null));
    }

    @Test
    void aRecordedStatusNeedsItsEvidence() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new ServeStatusDto(id, ServeStatusDto.Status.APPLIED, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ServeStatusDto(id, ServeStatusDto.Status.REJECTED, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ServeStatusDto(id, ServeStatusDto.Status.REJECTED, " ", null));
        assertThrows(IllegalArgumentException.class, () -> new ServeStatusDto(null, ServeStatusDto.Status.REJECTED, "ORDER_CANCELLED",
                null));
        assertEquals("ORDER_CANCELLED", new ServeStatusDto(id, ServeStatusDto.Status.REJECTED, "ORDER_CANCELLED", null).rejectionCode());
    }
}
