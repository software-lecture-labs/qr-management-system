package com.restofy.order.infrastructure.client.mock;

import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.application.port.ProductSnapshot.OptionType;
import com.restofy.order.application.port.ProductSnapshot.ProductOption;

import java.util.List;
import java.util.UUID;

/**
 * Fixed fixture data for the dev/test MOCK adapters. This is NOT catalog or session data from the real Menu/QR
 * services; the tokens are placeholders that only exist while the {@code dev} or {@code test} profile is active.
 */
public final class DevFixtures {

    public static final UUID RESTAURANT_A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    public static final UUID RESTAURANT_B = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    public static final UUID TABLE_A1 = UUID.fromString("00000000-0000-4000-8000-00000000a101");
    public static final UUID TABLE_SESSION_A1 = UUID.fromString("00000000-0000-4000-8000-0000000a5e01");
    public static final UUID GUEST_A1 = UUID.fromString("00000000-0000-4000-8000-00000000ee01");
    public static final UUID GUEST_A2 = UUID.fromString("00000000-0000-4000-8000-00000000ee02");
    public static final UUID TABLE_B1 = UUID.fromString("00000000-0000-4000-8000-00000000b101");
    public static final UUID TABLE_SESSION_B1 = UUID.fromString("00000000-0000-4000-8000-0000000b5e01");
    public static final UUID GUEST_B1 = UUID.fromString("00000000-0000-4000-8000-00000000ee03");

    public static final UUID USER_ADMIN_A = UUID.fromString("00000000-0000-4000-8000-0000000d0a01");
    public static final UUID USER_WAITER_A = UUID.fromString("00000000-0000-4000-8000-0000000d0a02");
    public static final UUID USER_KITCHEN_A = UUID.fromString("00000000-0000-4000-8000-0000000d0a03");
    public static final UUID USER_ADMIN_B = UUID.fromString("00000000-0000-4000-8000-0000000d0b01");
    public static final UUID USER_WAITER_B = UUID.fromString("00000000-0000-4000-8000-0000000d0b02");

    /** Placeholder staff credentials of the MOCK Auth adapter (dev/test only). */
    public static final String STAFF_ADMIN_A = "dev-mock-staff-admin-a";
    public static final String STAFF_WAITER_A = "dev-mock-staff-waiter-a";
    public static final String STAFF_KITCHEN_A = "dev-mock-staff-kitchen-a";
    public static final String STAFF_ADMIN_B = "dev-mock-staff-admin-b";
    public static final String STAFF_WAITER_B = "dev-mock-staff-waiter-b";

    public static final String TOKEN_A1 = "dev-mock-guest-token-a1";
    public static final String TOKEN_A2 = "dev-mock-guest-token-a2";
    public static final String TOKEN_B1 = "dev-mock-guest-token-b1";

    public static final UUID BURGER = UUID.fromString("00000000-0000-4000-8000-0000000b0001");
    public static final UUID SALAD = UUID.fromString("00000000-0000-4000-8000-0000000b0002");
    public static final UUID OPT_ONION = UUID.fromString("00000000-0000-4000-8000-00000000c001");
    public static final UUID OPT_PICKLE = UUID.fromString("00000000-0000-4000-8000-00000000c002");
    public static final UUID OPT_BUN = UUID.fromString("00000000-0000-4000-8000-00000000c003");
    public static final UUID OPT_CHEESE = UUID.fromString("00000000-0000-4000-8000-00000000c004");
    public static final UUID OPT_BACON = UUID.fromString("00000000-0000-4000-8000-00000000c005");

    private DevFixtures() {
    }

    public static GuestSessionContext guestA1(GuestSessionContext.SessionState state) {
        return new GuestSessionContext(RESTAURANT_A, TABLE_A1, TABLE_SESSION_A1, GUEST_A1, state);
    }

    public static GuestSessionContext guestA2(GuestSessionContext.SessionState state) {
        return new GuestSessionContext(RESTAURANT_A, TABLE_A1, TABLE_SESSION_A1, GUEST_A2, state);
    }

    public static GuestSessionContext guestB1(GuestSessionContext.SessionState state) {
        return new GuestSessionContext(RESTAURANT_B, TABLE_B1, TABLE_SESSION_B1, GUEST_B1, state);
    }

    /** 250.00 TRY, 10% VAT: onion/pickle removable, bun standard (not removable), cheese/bacon paid extras. */
    public static ProductSnapshot burgerSnapshot() {
        return new ProductSnapshot(BURGER, "Dev Burger", true, true, "TRY", 25_000L, 1_000, List.of(
                new ProductOption(OPT_ONION, "Onion", OptionType.INCLUDED, 0L, 0, 1, true),
                new ProductOption(OPT_PICKLE, "Pickle", OptionType.INCLUDED, 0L, 0, 1, true),
                new ProductOption(OPT_BUN, "Bun", OptionType.INCLUDED, 0L, 1, 1, true),
                new ProductOption(OPT_CHEESE, "Extra cheese", OptionType.ADD, 2_500L, 0, 2, true),
                new ProductOption(OPT_BACON, "Bacon", OptionType.ADD, 4_000L, 0, 1, true)));
    }

    /** 90.00 TRY, 20% VAT, no options. */
    public static ProductSnapshot saladSnapshot() {
        return new ProductSnapshot(SALAD, "Dev Salad", true, true, "TRY", 9_000L, 2_000, List.of());
    }
}
