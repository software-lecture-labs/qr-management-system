package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.contract.ClosedProduct;
import com.restofy.kitchen.application.contract.ClosedProductsPage;
import com.restofy.kitchen.application.contract.ClosedProductsQuery;
import com.restofy.kitchen.application.port.MenuClosedProductsPort;
import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.admin;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** KITCHEN-03 / 3.3: GET /api/v1/kitchen/products/closed against a programmable stand-in for the Menu service. */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenClosedProductsApiTests {

	private static final String PATH = "/api/v1/kitchen/products/closed";
	private static final Instant T = Instant.parse("2026-10-20T09:00:00Z");

	@Autowired
	private MockMvc mvc;
	@MockitoBean
	private MenuClosedProductsPort menu;

	private static ClosedProduct product(ProductAvailabilityStatus status, Instant changedAt) {
		return new ClosedProduct(UUID.randomUUID(), "Burger", status, "STOCK", "Bitti", changedAt, UUID.randomUUID(), 2L, true);
	}

	private void menuAnswers(List<ClosedProduct> items, long total) {
		doReturn(MenuClosedProductsPort.Result.ok(new ClosedProductsPage(items, total))).when(menu).list(any());
	}

	private ResultActions read(UUID restaurant, String query) throws Exception {
		return mvc.perform(get(PATH + query).with(kitchen(restaurant)));
	}

	private ClosedProductsQuery asked() {
		ArgumentCaptor<ClosedProductsQuery> captor = ArgumentCaptor.forClass(ClosedProductsQuery.class);
		verify(menu).list(captor.capture());
		return captor.getValue();
	}

	@Test
	void withoutParametersBothClosedStatusesAreAskedForWithTheDefaultPage() throws Exception {
		UUID restaurant = UUID.randomUUID();
		menuAnswers(List.of(), 0);

		read(restaurant, "").andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
				.andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0))
				.andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20));

		assertEquals(new ClosedProductsQuery(restaurant,
				EnumSet.of(ProductAvailabilityStatus.OUT_OF_STOCK, ProductAvailabilityStatus.TEMPORARILY_CLOSED), 0, 20), asked());
	}

	@Test
	void eachClosedStatusCanBeAskedForAloneAndTheTenantIsAlwaysThePrincipalsNeverAHeader() throws Exception {
		UUID restaurant = UUID.randomUUID();
		menuAnswers(List.of(), 0);

		mvc.perform(get(PATH + "?status=OUT_OF_STOCK&page=2&size=100").header("X-Restaurant-Id", UUID.randomUUID().toString())
				.with(kitchen(restaurant))).andExpect(status().isOk());
		mvc.perform(get(PATH + "?status=TEMPORARILY_CLOSED&size=1").with(kitchen(restaurant))).andExpect(status().isOk());

		ArgumentCaptor<ClosedProductsQuery> captor = ArgumentCaptor.forClass(ClosedProductsQuery.class);
		verify(menu, times(2)).list(captor.capture());
		assertEquals(new ClosedProductsQuery(restaurant, EnumSet.of(ProductAvailabilityStatus.OUT_OF_STOCK), 2, 100),
				captor.getAllValues().get(0));
		assertEquals(new ClosedProductsQuery(restaurant, EnumSet.of(ProductAvailabilityStatus.TEMPORARILY_CLOSED), 0, 1),
				captor.getAllValues().get(1));
	}

	@Test
	void presentButEmptyUnknownRepeatedOrOpenStatusAndBadPagingAreBadRequestsThatNeverReachMenu() throws Exception {
		UUID restaurant = UUID.randomUUID();
		for (String query : List.of("?status=", "?status=AVAILABLE", "?status=BROKEN", "?status=out_of_stock", "?status=%20",
				"?status=OUT_OF_STOCK&status=TEMPORARILY_CLOSED", "?status=OUT_OF_STOCK,TEMPORARILY_CLOSED", "?status=ACTIVE",
				"?page=", "?page=-1", "?page=abc", "?page=1.5", "?page=99999999999", "?size=", "?size=0", "?size=101", "?size=abc",
				"?size=-5", "?page=%20&size=20")) {
			read(restaurant, query).andExpect(status().isBadRequest());
		}
		verifyNoInteractions(menu);
	}

	@Test
	void whatMenuReportsIsPassedOnExactlyIncludingMissingNameAndUnknownPublication() throws Exception {
		UUID restaurant = UUID.randomUUID();
		ClosedProduct named = product(ProductAvailabilityStatus.OUT_OF_STOCK, T.plusSeconds(10));
		ClosedProduct bare = new ClosedProduct(UUID.randomUUID(), null, ProductAvailabilityStatus.TEMPORARILY_CLOSED, null, null, T, null, 1L,
				null);
		menuAnswers(List.of(named, bare), 7);

		read(restaurant, "?size=2").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(7))
				.andExpect(jsonPath("$.totalPages").value(4))
				.andExpect(jsonPath("$.content[0].productId").value(named.productId().toString()))
				.andExpect(jsonPath("$.content[0].productName").value("Burger"))
				.andExpect(jsonPath("$.content[0].reasonCode").value("STOCK"))
				.andExpect(jsonPath("$.content[0].changedAt").value("2026-10-20T09:00:10Z"))
				.andExpect(jsonPath("$.content[0].version").value(2)).andExpect(jsonPath("$.content[0].published").value(true))
				.andExpect(jsonPath("$.content[1].productName").value((Object) null))
				.andExpect(jsonPath("$.content[1].reasonCode").value((Object) null))
				.andExpect(jsonPath("$.content[1].reasonText").value((Object) null))
				.andExpect(jsonPath("$.content[1].changedByUserId").value((Object) null))
				.andExpect(jsonPath("$.content[1].published").value((Object) null))
				.andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
	}

	@Test
	void aPageBeyondTheEndIsAnEmptyListWithTheRealTotalNotAnError() throws Exception {
		menuAnswers(List.of(), 3);

		read(UUID.randomUUID(), "?page=50&size=20").andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
				.andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(1));
	}

	@Test
	void whenMenuCannotAnswerTheResultIsAnErrorNeverAnEmptyList() throws Exception {
		UUID restaurant = UUID.randomUUID();

		doReturn(MenuClosedProductsPort.Result.unavailable("TIMEOUT")).when(menu).list(any());
		read(restaurant, "").andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "2"))
				.andExpect(jsonPath("$.code").value("MENU_UNAVAILABLE")).andExpect(jsonPath("$.retryable").value(true))
				.andExpect(jsonPath("$.content").doesNotExist());

		doThrow(new IllegalStateException("jdbc:postgresql://secret-host:5432/menu password=hunter2")).when(menu).list(any());
		String leaked = read(restaurant, "").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MENU_UNAVAILABLE"))
				.andReturn().getResponse().getContentAsString();
		assertFalse(leaked.contains("hunter2") || leaked.contains("secret-host"), leaked);

		doReturn(null).when(menu).list(any());
		read(restaurant, "").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MENU_UNAVAILABLE"));
	}

	@Test
	void anAnswerThatContradictsTheQuestionIsNeverPassedOn() throws Exception {
		UUID restaurant = UUID.randomUUID();
		ClosedProduct stock = product(ProductAvailabilityStatus.OUT_OF_STOCK, T);
		List<ClosedProductsPage> contradictions = new ArrayList<>();
		// a product that is not closed
		contradictions.add(new ClosedProductsPage(List.of(product(ProductAvailabilityStatus.AVAILABLE, T)), 1));
		// more items than asked for
		contradictions.add(new ClosedProductsPage(List.of(stock, product(ProductAvailabilityStatus.OUT_OF_STOCK, T),
				product(ProductAvailabilityStatus.OUT_OF_STOCK, T)), 3));
		// the same product twice
		contradictions.add(new ClosedProductsPage(List.of(stock, stock), 2));
		// not newest first
		contradictions.add(new ClosedProductsPage(List.of(product(ProductAvailabilityStatus.OUT_OF_STOCK, T),
				product(ProductAvailabilityStatus.OUT_OF_STOCK, T.plusSeconds(5))), 2));
		// a total smaller than what was just listed, or negative
		contradictions.add(new ClosedProductsPage(List.of(stock), 0));
		contradictions.add(new ClosedProductsPage(List.of(), -1));
		// missing list, missing element, product without id or time
		contradictions.add(new ClosedProductsPage(null, 1));
		List<ClosedProduct> withNull = new ArrayList<>();
		withNull.add(null);
		contradictions.add(new ClosedProductsPage(withNull, 1));
		contradictions.add(new ClosedProductsPage(List.of(new ClosedProduct(null, "x", ProductAvailabilityStatus.OUT_OF_STOCK, null, null, T,
				null, 1L, null)), 1));
		contradictions.add(new ClosedProductsPage(List.of(new ClosedProduct(UUID.randomUUID(), "x", ProductAvailabilityStatus.OUT_OF_STOCK,
				null, null, null, null, 1L, null)), 1));

		for (ClosedProductsPage page : contradictions) {
			doReturn(MenuClosedProductsPort.Result.ok(page)).when(menu).list(any());
			read(restaurant, "?size=2&status=OUT_OF_STOCK").andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.code").value("MENU_UNAVAILABLE")).andExpect(jsonPath("$.content").doesNotExist());
		}
		// the repaired answer on the same request is fine
		menuAnswers(List.of(stock), 1);
		read(restaurant, "?size=2&status=OUT_OF_STOCK").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void onlyAuthenticatedKitchenAndAdminMayReadAndNobodyMayWriteThisPath() throws Exception {
		UUID restaurant = UUID.randomUUID();
		menuAnswers(List.of(), 0);

		mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
		mvc.perform(get(PATH).header("X-Role", "KITCHEN").header("X-Restaurant-Id", restaurant.toString())).andExpect(status().isUnauthorized());
		mvc.perform(get(PATH).with(admin(restaurant))).andExpect(status().isOk());
		mvc.perform(get(PATH).with(kitchen(restaurant))).andExpect(status().isOk());
		for (var request : List.of(post(PATH), put(PATH), delete(PATH))) {
			mvc.perform(request.with(admin(restaurant)).with(csrf())).andExpect(status().isForbidden());
			mvc.perform(request.with(kitchen(restaurant)).with(csrf())).andExpect(status().isMethodNotAllowed());
		}
		verify(menu, times(2)).list(any());
	}
}
