package com.restofy.kitchen.infrastructure.config;

import com.restofy.kitchen.application.contract.MenuAvailabilityCommand;
import com.restofy.kitchen.application.port.MenuAvailabilityPort;
import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Without a Menu adapter the production wiring is fail closed: it never confirms anything. */
@SpringBootTest
class MenuIntegrationConfigurationTests {

	@Autowired
	private MenuAvailabilityPort port;

	@Test
	void theDefaultPortReportsMenuNotIntegratedAndNeverASuccess() {
		MenuAvailabilityPort.Result result = port.change(new MenuAvailabilityCommand(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), ProductAvailabilityStatus.OUT_OF_STOCK, "OUT_OF_STOCK", null, UUID.randomUUID()));

		assertEquals(MenuAvailabilityPort.Kind.UNAVAILABLE, result.kind());
		assertEquals("MENU_NOT_INTEGRATED", result.errorCode());
		assertNull(result.state(), "no confirmation is ever invented");
	}
}
