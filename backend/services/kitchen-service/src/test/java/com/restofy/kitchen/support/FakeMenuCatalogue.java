package com.restofy.kitchen.support;

import com.restofy.kitchen.application.contract.ClosedProduct;
import com.restofy.kitchen.application.contract.ClosedProductsPage;
import com.restofy.kitchen.application.contract.ClosedProductsQuery;
import com.restofy.kitchen.application.contract.MenuAvailabilityCommand;
import com.restofy.kitchen.application.contract.ProductAvailabilityState;
import com.restofy.kitchen.application.port.MenuAvailabilityPort;
import com.restofy.kitchen.application.port.MenuClosedProductsPort;
import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TEST-ONLY stand-in for the Menu service: a small stateful catalogue that behaves the way the proposed Menu contract
 * says (Menu is the only author of availability, honours the command id, keeps a version per product, reports the
 * closed products ordered {@code changedAt DESC, productId ASC}). It proves how Kitchen behaves towards that
 * contract; it proves nothing about a real Menu service, which does not exist yet.
 */
public class FakeMenuCatalogue implements MenuAvailabilityPort, MenuClosedProductsPort {

	private static final Instant START = Instant.parse("2026-10-20T08:00:00Z");

	private record Entry(UUID restaurantId, UUID productId, String name, boolean published, boolean active,
			ProductAvailabilityStatus status, String reasonCode, String reasonText, Instant changedAt, UUID changedBy, long version) {
	}

	private final Map<UUID, Entry> products = new HashMap<>();
	private final Map<String, MenuAvailabilityCommand> seenCommands = new HashMap<>();
	private final Map<String, MenuAvailabilityPort.Result> answers = new HashMap<>();
	private int clock;
	private boolean reachable = true;
	private int commandCalls;
	private int listCalls;

	/** Simulates a Menu service that cannot be reached (timeout, connection refused): every call is "unknown". */
	public synchronized void setReachable(boolean reachable) {
		this.reachable = reachable;
	}

	/** How many commands Kitchen has sent to Menu, reachable or not (a restart must add none). */
	public synchronized int commandCalls() {
		return commandCalls;
	}

	public synchronized int listCalls() {
		return listCalls;
	}

	/** Adds a catalogue product that is sold (AVAILABLE). */
	public synchronized UUID addProduct(UUID restaurantId, String name, boolean published, boolean active) {
		UUID id = UUID.randomUUID();
		products.put(id, new Entry(restaurantId, id, name, published, active, ProductAvailabilityStatus.AVAILABLE, null, null, null, null, 0L));
		return id;
	}

	public synchronized ProductAvailabilityStatus statusOf(UUID productId) {
		return products.get(productId).status();
	}

	public synchronized long versionOf(UUID productId) {
		return products.get(productId).version();
	}

	@Override
	public synchronized MenuAvailabilityPort.Result change(MenuAvailabilityCommand command) {
		commandCalls++;
		if (!reachable) {
			return MenuAvailabilityPort.Result.unavailable("MENU_DOWN");
		}
		String key = command.restaurantId() + ":" + command.commandId();
		MenuAvailabilityCommand earlier = seenCommands.get(key);
		if (earlier != null) {
			if (!earlier.equals(command)) {
				return MenuAvailabilityPort.Result.rejected(MenuAvailabilityPort.Kind.COMMAND_CONFLICT, "COMMAND_CONFLICT");
			}
			return answers.get(key);
		}
		Entry entry = products.get(command.productId());
		if (entry == null || !entry.restaurantId().equals(command.restaurantId())) {
			return MenuAvailabilityPort.Result.rejected(MenuAvailabilityPort.Kind.PRODUCT_NOT_FOUND, "PRODUCT_NOT_FOUND");
		}
		if (!entry.active()) {
			return MenuAvailabilityPort.Result.rejected(MenuAvailabilityPort.Kind.PRODUCT_INACTIVE, "PRODUCT_INACTIVE");
		}
		Entry changed = new Entry(entry.restaurantId(), entry.productId(), entry.name(), entry.published(), entry.active(),
				command.targetStatus(), command.reasonCode(), command.reasonText(), START.plusSeconds(++clock),
				command.requestedByUserId(), entry.version() + 1);
		products.put(changed.productId(), changed);
		MenuAvailabilityPort.Result result = MenuAvailabilityPort.Result.applied(new ProductAvailabilityState(changed.productId(),
				changed.status(), changed.reasonCode(), changed.reasonText(), changed.changedAt(), changed.changedBy(),
				changed.version(), changed.published()));
		seenCommands.put(key, command);
		answers.put(key, result);
		return result;
	}

	@Override
	public synchronized MenuClosedProductsPort.Result list(ClosedProductsQuery query) {
		listCalls++;
		if (!reachable) {
			return MenuClosedProductsPort.Result.unavailable("MENU_DOWN");
		}
		List<Entry> matching = new ArrayList<>(products.values().stream()
				.filter(e -> e.restaurantId().equals(query.restaurantId()) && e.active() && query.statuses().contains(e.status())).toList());
		matching.sort(Comparator.comparing(Entry::changedAt).reversed().thenComparing(Entry::productId));
		int from = (int) Math.min((long) query.page() * query.size(), matching.size());
		int to = Math.min(from + query.size(), matching.size());
		List<ClosedProduct> items = matching.subList(from, to).stream()
				.map(e -> new ClosedProduct(e.productId(), e.name(), e.status(), e.reasonCode(), e.reasonText(), e.changedAt(),
						e.changedBy(), e.version(), e.published()))
				.toList();
		return MenuClosedProductsPort.Result.ok(new ClosedProductsPage(items, matching.size()));
	}
}
