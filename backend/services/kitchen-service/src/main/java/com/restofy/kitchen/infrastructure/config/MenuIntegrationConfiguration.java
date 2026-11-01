package com.restofy.kitchen.infrastructure.config;

import com.restofy.kitchen.application.contract.ClosedProductsQuery;
import com.restofy.kitchen.application.contract.MenuAvailabilityCommand;
import com.restofy.kitchen.application.port.MenuAvailabilityPort;
import com.restofy.kitchen.application.port.MenuClosedProductsPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-closed default for the Menu boundary: while no adapter exists every call is reported as "Menu not integrated"
 * (an unknown outcome), never as a success. A real adapter, once the Menu contract and service identity are agreed,
 * is simply another {@link MenuAvailabilityPort} / {@link MenuClosedProductsPort} bean and replaces this one.
 */
@Configuration(proxyBeanMethods = false)
public class MenuIntegrationConfiguration {

	public static final String NOT_INTEGRATED = "MENU_NOT_INTEGRATED";

	@Bean
	@ConditionalOnMissingBean(MenuAvailabilityPort.class)
	MenuAvailabilityPort menuNotIntegrated() {
		return (MenuAvailabilityCommand command) -> MenuAvailabilityPort.Result.unavailable(NOT_INTEGRATED);
	}

	@Bean
	@ConditionalOnMissingBean(MenuClosedProductsPort.class)
	MenuClosedProductsPort menuClosedProductsNotIntegrated() {
		return (ClosedProductsQuery query) -> MenuClosedProductsPort.Result.unavailable(NOT_INTEGRATED);
	}
}
