package com.restofy.kitchen.api.dto.request;

import com.restofy.kitchen.domain.enums.CancellationReason;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundaries of the whole-order cancellation request: every violation is caught before anything is stored. The reason is
 * MANDATORY here, unlike the optional reason of a product availability change.
 */
class RequestOrderCancellationRequestTests {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	private boolean valid(CancellationReason reason, String text) {
		return validator.validate(new RequestOrderCancellationRequest(reason, text, null, null)).isEmpty();
	}

	@Test
	void theReasonCatalogueIsExactlyTheFourAgreedCodes() {
		assertEquals(List.of("OUT_OF_STOCK", "CUSTOMER_REQUEST", "KITCHEN_ERROR", "OTHER"),
				Arrays.stream(CancellationReason.values()).map(Enum::name).toList());
	}

	@Test
	void theReasonCodeIsMandatory() {
		assertFalse(valid(null, null));
		assertFalse(valid(null, "Müşteri vazgeçti"));
		for (CancellationReason reason : CancellationReason.values()) {
			assertTrue(valid(reason, null), reason.name());
		}
	}

	@Test
	void theTextIsOptionalLimitedAndSingleLine() {
		assertTrue(valid(CancellationReason.OTHER, null), "OTHER needs no explanation (no product decision says otherwise)");
		assertTrue(valid(CancellationReason.OTHER, "x".repeat(500)));
		assertFalse(valid(CancellationReason.OTHER, "x".repeat(501)));
		assertFalse(valid(CancellationReason.OTHER, "satır1\nsatır2"));
		assertFalse(valid(CancellationReason.OTHER, "tab\there"));
		assertFalse(valid(CancellationReason.OTHER, "nul\u0000byte"));
		assertTrue(valid(CancellationReason.OTHER, "<b>HTML</b> ve \"tırnak\" metin olarak saklanır"));
	}

	@Test
	void aBlankTextCountsAsNoneAndSurroundingBlanksAreDropped() {
		assertNull(new RequestOrderCancellationRequest(CancellationReason.OTHER, "   ", null, null).reasonText());
		assertNull(new RequestOrderCancellationRequest(CancellationReason.OTHER, "", null, null).reasonText());
		assertNull(new RequestOrderCancellationRequest(CancellationReason.OTHER, null, null, null).reasonText());
		assertEquals("Tükendi", new RequestOrderCancellationRequest(CancellationReason.OTHER, "  Tükendi  ", null, null).reasonText());
	}
}
