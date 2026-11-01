package com.restofy.order.application.service;

import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.application.port.ProductSnapshot.OptionType;
import com.restofy.order.application.port.ProductSnapshot.ProductOption;
import com.restofy.order.application.service.CartSelectionResolver.ExtraChoice;
import com.restofy.order.domain.enums.SelectionAction;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CartSelectionResolverTests {

    private static final UUID INACTIVE_EXTRA = UUID.fromString("00000000-0000-4000-8000-00000000c0f1");
    private static final UUID INACTIVE_INGREDIENT = UUID.fromString("00000000-0000-4000-8000-00000000c0f2");

    private final CartSelectionResolver resolver = new CartSelectionResolver();
    private final ProductSnapshot burger = withInactiveOptions(DevFixtures.burgerSnapshot());

    private static ProductSnapshot withInactiveOptions(ProductSnapshot base) {
        var options = new ArrayList<>(base.options());
        options.add(new ProductOption(INACTIVE_EXTRA, "Retired extra", OptionType.ADD, 100L, 0, 1, false));
        options.add(new ProductOption(INACTIVE_INGREDIENT, "Retired ingredient", OptionType.INCLUDED, 0L, 0, 1, false));
        return new ProductSnapshot(base.productId(), base.name(), true, true, base.currency(),
                base.basePriceGrossMinor(), base.taxRateBps(), options);
    }

    private BusinessException rejected(List<UUID> removed, List<ExtraChoice> extras) {
        return assertThrows(BusinessException.class, () -> resolver.resolve(burger, removed, extras, null));
    }

    @Test
    void resolvesRemovalsAndExtrasFromTheCatalogOnly() {
        ResolvedSelection selection = resolver.resolve(burger, List.of(DevFixtures.OPT_ONION),
                List.of(new ExtraChoice(DevFixtures.OPT_CHEESE, 2), new ExtraChoice(DevFixtures.OPT_BACON, 1)), null);

        assertEquals(2_500L * 2 + 4_000L, selection.extrasUnitDeltaMinor());
        assertEquals(3, selection.options().size());
        assertEquals(1, selection.options().stream().filter(o -> o.action() == SelectionAction.REMOVE).count());
        assertEquals("Onion", selection.options().stream()
                .filter(o -> o.action() == SelectionAction.REMOVE).findFirst().orElseThrow().name());
    }

    @Test
    void emptySelectionIsTheStandardProduct() {
        ResolvedSelection standard = resolver.resolve(burger, null, null, null);
        assertEquals(0L, standard.extrasUnitDeltaMinor());
        assertEquals(0, standard.options().size());
        assertEquals(standard.selectionKey(), resolver.resolve(burger, List.of(), List.of(), "  ").selectionKey());
    }

    @Test
    void selectionKeyIgnoresOrderButNotContent() {
        String key = resolver.resolve(burger, List.of(DevFixtures.OPT_ONION, DevFixtures.OPT_PICKLE),
                List.of(new ExtraChoice(DevFixtures.OPT_CHEESE, 1), new ExtraChoice(DevFixtures.OPT_BACON, 1)), null)
                .selectionKey();
        String reordered = resolver.resolve(burger, List.of(DevFixtures.OPT_PICKLE, DevFixtures.OPT_ONION),
                List.of(new ExtraChoice(DevFixtures.OPT_BACON, 1), new ExtraChoice(DevFixtures.OPT_CHEESE, 1)), null)
                .selectionKey();
        assertEquals(key, reordered);

        assertNotEquals(key, resolver.resolve(burger, List.of(DevFixtures.OPT_ONION),
                List.of(new ExtraChoice(DevFixtures.OPT_CHEESE, 1), new ExtraChoice(DevFixtures.OPT_BACON, 1)), null)
                .selectionKey());
        assertNotEquals(key, resolver.resolve(burger, List.of(DevFixtures.OPT_ONION, DevFixtures.OPT_PICKLE),
                List.of(new ExtraChoice(DevFixtures.OPT_CHEESE, 2), new ExtraChoice(DevFixtures.OPT_BACON, 1)), null)
                .selectionKey());
    }

    @Test
    void differentNotesAreDifferentLinesButWhitespaceIsNormalized() {
        String plain = resolver.resolve(burger, null, null, null).selectionKey();
        String noted = resolver.resolve(burger, null, null, "well done").selectionKey();
        assertNotEquals(plain, noted);
        assertEquals(noted, resolver.resolve(burger, null, null, "  well done \n").selectionKey());
        assertNull(resolver.resolve(burger, null, null, "   ").note());
    }

    @Test
    void rejectsIngredientsThatDoNotBelongToTheProduct() {
        assertEquals(ErrorCode.UNKNOWN_OPTION, rejected(List.of(UUID.randomUUID()), List.of()).code());
        assertEquals(ErrorCode.UNKNOWN_OPTION,
                rejected(List.of(), List.of(new ExtraChoice(UUID.randomUUID(), 1))).code());
    }

    @Test
    void rejectsRemovingNonRemovableIngredientsExtrasAndRetiredOnes() {
        assertEquals(ErrorCode.OPTION_NOT_REMOVABLE, rejected(List.of(DevFixtures.OPT_BUN), List.of()).code());
        assertEquals(ErrorCode.OPTION_NOT_REMOVABLE, rejected(List.of(DevFixtures.OPT_CHEESE), List.of()).code());
        assertEquals(ErrorCode.OPTION_NOT_REMOVABLE, rejected(List.of(INACTIVE_INGREDIENT), List.of()).code());
    }

    @Test
    void rejectsExtrasThatAreNotPaidExtrasOrAreRetired() {
        assertEquals(ErrorCode.OPTION_NOT_EXTRA,
                rejected(List.of(), List.of(new ExtraChoice(DevFixtures.OPT_ONION, 1))).code());
        assertEquals(ErrorCode.OPTION_NOT_EXTRA,
                rejected(List.of(), List.of(new ExtraChoice(INACTIVE_EXTRA, 1))).code());
    }

    @Test
    void rejectsDuplicatesAndContradictions() {
        assertEquals(ErrorCode.DUPLICATE_OPTION,
                rejected(List.of(DevFixtures.OPT_ONION, DevFixtures.OPT_ONION), List.of()).code());
        assertEquals(ErrorCode.DUPLICATE_OPTION, rejected(List.of(), List.of(
                new ExtraChoice(DevFixtures.OPT_CHEESE, 1), new ExtraChoice(DevFixtures.OPT_CHEESE, 1))).code());
        assertEquals(ErrorCode.CONFLICTING_OPTION, rejected(List.of(DevFixtures.OPT_ONION),
                List.of(new ExtraChoice(DevFixtures.OPT_ONION, 1))).code());
    }

    @Test
    void enforcesExtraQuantityBoundsFromTheCatalog() {
        // cheese: max 2
        assertEquals(ErrorCode.OPTION_QUANTITY_INVALID,
                rejected(List.of(), List.of(new ExtraChoice(DevFixtures.OPT_CHEESE, 3))).code());
        assertEquals(ErrorCode.OPTION_QUANTITY_INVALID,
                rejected(List.of(), List.of(new ExtraChoice(DevFixtures.OPT_CHEESE, 0))).code());
        assertEquals(ErrorCode.OPTION_QUANTITY_INVALID,
                rejected(List.of(), List.of(new ExtraChoice(DevFixtures.OPT_BACON, 2))).code());
        assertEquals(5_000L, resolver.resolve(burger, List.of(), List.of(new ExtraChoice(DevFixtures.OPT_CHEESE, 2)),
                null).extrasUnitDeltaMinor());
    }

    @Test
    void rejectsControlCharactersInNotesButKeepsHtmlAsPlainText() {
        BusinessException control = assertThrows(BusinessException.class,
                () -> resolver.resolve(burger, null, null, "bad\u0000note"));
        assertEquals(ErrorCode.INVALID_NOTE, control.code());
        assertThrows(BusinessException.class, () -> resolver.resolve(burger, null, null, "esc\u001b[31m"));

        String html = "<script>alert(1)</script>";
        assertEquals(html, resolver.resolve(burger, null, null, html).note());
        assertEquals("line1\nline2", resolver.resolve(burger, null, null, "line1\nline2").note());
    }
}
