package com.restofy.kitchen.api.dto.request;

import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boundaries of the availability request: every violation is caught before anything reaches the Menu port. */
class ChangeProductAvailabilityRequestTests {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	private boolean valid(ProductAvailabilityStatus status, String code, String text) {
		return validator.validate(new ChangeProductAvailabilityRequest(status, code, text)).isEmpty();
	}

	@Test
	void aMinimalAndAFullRequestAreValid() {
		assertTrue(valid(ProductAvailabilityStatus.OUT_OF_STOCK, "OUT_OF_STOCK", null));
		assertTrue(valid(ProductAvailabilityStatus.OUT_OF_STOCK, "OUT_OF_STOCK", "Malzeme tükendi, yarın gelecek"));
		assertTrue(valid(ProductAvailabilityStatus.TEMPORARILY_CLOSED, "EQUIPMENT_FAILURE", "x".repeat(500)));
	}

	@Test
	void onlyTheStatusIsMandatory() {
		assertFalse(valid(null, "OUT_OF_STOCK", null));
		assertFalse(valid(null, null, null));
		assertTrue(valid(ProductAvailabilityStatus.OUT_OF_STOCK, null, null));
		assertTrue(valid(ProductAvailabilityStatus.TEMPORARILY_CLOSED, null, null));
	}

	@Test
	void aMissingOrBlankReasonIsStoredAsNoneAndNothingIsInvented() {
		for (ProductAvailabilityStatus status : new ProductAvailabilityStatus[] { ProductAvailabilityStatus.OUT_OF_STOCK,
				ProductAvailabilityStatus.TEMPORARILY_CLOSED }) {
			for (String blank : new String[] { null, "", "   ", "\t" }) {
				ChangeProductAvailabilityRequest request = new ChangeProductAvailabilityRequest(status, blank, blank);
				assertNull(request.reasonCode(), "blank code '" + blank + "'");
				assertNull(request.reasonText(), "blank text '" + blank + "'");
				assertTrue(validator.validate(request).isEmpty());
			}
		}
	}

	@Test
	void eitherReasonFieldMayBeGivenAlone() {
		assertTrue(valid(ProductAvailabilityStatus.TEMPORARILY_CLOSED, "EQUIPMENT_FAILURE", null));
		assertTrue(valid(ProductAvailabilityStatus.TEMPORARILY_CLOSED, null, "Fritöz bozuldu"));
		assertTrue(valid(ProductAvailabilityStatus.OUT_OF_STOCK, null, "Malzeme tükendi"));
	}

	@Test
	void reasonCodeMustBeAShortUpperCaseToken() {
		for (String bad : new String[] { "out_of_stock", "Out_Of_Stock", "1ABC", "A", "OUT OF STOCK", "OUT-OF-STOCK", "ÇOK",
				"A".repeat(41), "OUT_OF_STOCK\n", "'; DROP TABLE x;--" }) {
			assertFalse(valid(ProductAvailabilityStatus.OUT_OF_STOCK, bad, null), "must reject '" + bad + "'");
		}
		assertTrue(valid(ProductAvailabilityStatus.OUT_OF_STOCK, "A".repeat(40), null));
		assertTrue(valid(ProductAvailabilityStatus.OUT_OF_STOCK, "AB", null));
	}

	@Test
	void reasonTextIsLimitedAndSingleLine() {
		assertFalse(valid(ProductAvailabilityStatus.OUT_OF_STOCK, "OUT_OF_STOCK", "x".repeat(501)));
		assertFalse(valid(ProductAvailabilityStatus.OUT_OF_STOCK, "OUT_OF_STOCK", "satır1\nsatır2"));
		assertFalse(valid(ProductAvailabilityStatus.OUT_OF_STOCK, "OUT_OF_STOCK", "tab\there"));
		assertFalse(valid(ProductAvailabilityStatus.OUT_OF_STOCK, "OUT_OF_STOCK", "nul\u0000byte"));
		assertTrue(valid(ProductAvailabilityStatus.OUT_OF_STOCK, "OUT_OF_STOCK", "<b>HTML</b> ve \"tırnak\" metin olarak saklanır"));
	}

	@Test
	void surroundingBlanksAreDroppedAndABlankDescriptionCountsAsNone() {
		assertEquals("Tükendi",
				new ChangeProductAvailabilityRequest(ProductAvailabilityStatus.OUT_OF_STOCK, "OUT_OF_STOCK", "  Tükendi  ").reasonText());
		assertNull(new ChangeProductAvailabilityRequest(ProductAvailabilityStatus.OUT_OF_STOCK, "OUT_OF_STOCK", "   ").reasonText());
	}
}
