package com.restofy.kitchen.application.mapper;

import com.restofy.kitchen.application.contract.OptionsSnapshot;
import com.restofy.kitchen.application.contract.OrderAcceptedMessage;
import com.restofy.kitchen.domain.enums.OptionAction;
import com.restofy.kitchen.domain.exception.InvalidOrderMessageException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderAcceptedMessageReaderTests {

	private final OrderAcceptedMessageReader reader = new OrderAcceptedMessageReader();

	private static String json(String itemExtra, String optionExtra, String rootExtra, String quantity, String action) {
		return """
				{
				  "eventId": "11111111-1111-1111-1111-111111111111",
				  "schemaVersion": 1,
				  "occurredAt": "2026-10-17T15:04:05Z",
				  "correlationId": "corr-1",
				  "restaurantId": "22222222-2222-2222-2222-222222222222",
				  "orderId": "33333333-3333-3333-3333-333333333333",
				  "aggregateVersion": 1,
				  "orderNumber": "A-100",
				  "tableSessionId": "44444444-4444-4444-4444-444444444444",
				  "tableNumber": "12"%s,
				  "items": [
				    {
				      "orderItemId": "55555555-5555-5555-5555-555555555555",
				      "productId": "66666666-6666-6666-6666-666666666666",
				      "productName": "Burger",
				      "quantity": %s,
				      "note": null%s,
				      "options": [
				        { "action": "%s", "optionId": "77777777-7777-7777-7777-777777777777",
				          "name": "Soğan", "quantity": 1, "note": null%s }
				      ]
				    }
				  ]
				}
				""".formatted(rootExtra, quantity, itemExtra, action, optionExtra);
	}

	private static String valid() {
		return json("", "", "", "2", "REMOVE");
	}

	@Test
	void validPayloadIsReadIntoTheTypedContract() {
		OrderAcceptedMessage message = reader.read(valid());

		assertEquals(UUID.fromString("33333333-3333-3333-3333-333333333333"), message.orderId());
		assertEquals("A-100", message.orderNumber());
		assertEquals(2, message.items().get(0).quantity());
		assertEquals(OptionAction.REMOVE, message.items().get(0).options().get(0).action());
		assertEquals("Soğan", message.items().get(0).options().get(0).name());
	}

	@Test
	void unexpectedFieldsAreRejectedAtEveryLevelIncludingAnyPriceField() {
		assertThrows(InvalidOrderMessageException.class, () -> reader.read(json("", "", ", \"totalPrice\": 10.5", "2", "ADD")));
		assertThrows(InvalidOrderMessageException.class, () -> reader.read(json(", \"unitPrice\": 9.9", "", "", "2", "ADD")));
		assertThrows(InvalidOrderMessageException.class, () -> reader.read(json("", ", \"priceDelta\": 1", "", "2", "ADD")));
	}

	@Test
	void wrongTypesUnknownActionsAndStructuralProblemsAreRejected() {
		assertThrows(InvalidOrderMessageException.class, () -> reader.read(json("", "", "", "\"2\"", "ADD")));
		assertThrows(InvalidOrderMessageException.class, () -> reader.read(json("", "", "", "2", "REPLACE")));
		assertThrows(InvalidOrderMessageException.class, () -> reader.read(json("", "", "", "2", "add")));
		assertThrows(InvalidOrderMessageException.class, () -> reader.read(json("", "", "", "2.5", "ADD")));
		assertThrows(InvalidOrderMessageException.class, () -> reader.read("{ not json"));
		assertThrows(InvalidOrderMessageException.class, () -> reader.read("[]"));
		assertThrows(InvalidOrderMessageException.class, () -> reader.read("null"));
		assertThrows(InvalidOrderMessageException.class, () -> reader.read(null));
		assertThrows(InvalidOrderMessageException.class, () -> reader.read("   "));
	}

	@Test
	void duplicateKeysAreRejectedSoNothingCanBeSmuggledPastValidation() {
		String duplicated = valid().replace("\"tableNumber\": \"12\"", "\"tableNumber\": \"12\", \"tableNumber\": \"99\"");
		assertThrows(InvalidOrderMessageException.class, () -> reader.read(duplicated));
	}

	@Test
	void oversizedPayloadIsRejectedBeforeParsing() {
		String huge = valid().replace("Burger", "B".repeat(OrderAcceptedMessageReader.MAX_PAYLOAD_CHARS));
		assertThrows(InvalidOrderMessageException.class, () -> reader.read(huge));
	}

	@Test
	void errorMessagesNeverEchoPayloadContent() {
		var e = assertThrows(InvalidOrderMessageException.class,
				() -> reader.read(json("", "", ", \"secretToken\": \"TOP-SECRET-VALUE\"", "2", "ADD")));
		assertFalse(e.getMessage().contains("TOP-SECRET-VALUE"));
		assertFalse(e.getMessage().contains("secretToken"));
		assertTrue(e.getViolations().size() == 1);
	}

	@Test
	void storedSnapshotOfAnUnknownSchemaVersionIsRefusedNotGuessed() {
		var codec = new OptionsSnapshotCodec(JsonMapper.builder().build());
		OptionsSnapshot ok = codec.fromJson("{\"schemaVersion\":1,\"options\":[]}");
		assertTrue(ok.options().isEmpty());
		assertThrows(IllegalStateException.class, () -> codec.fromJson("{\"schemaVersion\":2,\"options\":[]}"));
		assertThrows(IllegalStateException.class, () -> codec.fromJson("{\"schemaVersion\":1}"));
		assertThrows(IllegalStateException.class, () -> codec.fromJson("{\"schemaVersion\":1,\"options\":[{\"action\":\"EAT\"}]}"));
	}
}
