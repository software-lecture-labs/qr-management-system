package com.restofy.order.application.service;

import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.application.port.ProductSnapshot.OptionType;
import com.restofy.order.application.port.ProductSnapshot.ProductOption;
import com.restofy.order.application.service.ResolvedSelection.ResolvedOption;
import com.restofy.order.domain.enums.SelectionAction;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Validates a client's ingredient choices against the trusted catalog snapshot and normalizes them.
 * The client may only reference option ids; names and prices always come from the catalog.
 */
@Component
public class CartSelectionResolver {

    private static final String INGREDIENT_ID = "ingredientId";

    public record ExtraChoice(UUID optionId, int quantity) {
    }

    public ResolvedSelection resolve(ProductSnapshot product, List<UUID> removedOptionIds,
                                     List<ExtraChoice> extraChoices, String rawNote) {
        List<UUID> removedIds = removedOptionIds == null ? List.of() : removedOptionIds;
        List<ExtraChoice> extras = extraChoices == null ? List.of() : extraChoices;
        String note = normalizeNote(rawNote);

        Map<UUID, ProductOption> catalogOptions = new HashMap<>();
        product.options().forEach(option -> catalogOptions.put(option.optionId(), option));

        List<ResolvedOption> resolved = new ArrayList<>();
        Set<UUID> seenRemoved = new HashSet<>();
        for (UUID optionId : removedIds) {
            ProductOption option = lookup(catalogOptions, optionId);
            if (!seenRemoved.add(optionId)) {
                throw new BusinessException(ErrorCode.DUPLICATE_OPTION,
                        "The same ingredient cannot be removed twice", Map.of(INGREDIENT_ID, optionId));
            }
            if (option.type() != OptionType.INCLUDED || !option.removable()) {
                throw new BusinessException(ErrorCode.OPTION_NOT_REMOVABLE,
                        "This ingredient cannot be removed from the product", Map.of(INGREDIENT_ID, optionId));
            }
            resolved.add(new ResolvedOption(optionId, SelectionAction.REMOVE, 1, option.name(), 0L));
        }

        Set<UUID> seenExtras = new HashSet<>();
        long extrasDelta = 0L;
        for (ExtraChoice choice : extras) {
            ProductOption option = lookup(catalogOptions, choice.optionId());
            if (!seenExtras.add(choice.optionId())) {
                throw new BusinessException(ErrorCode.DUPLICATE_OPTION,
                        "The same extra can only be selected once", Map.of(INGREDIENT_ID, choice.optionId()));
            }
            if (seenRemoved.contains(choice.optionId())) {
                throw new BusinessException(ErrorCode.CONFLICTING_OPTION,
                        "An ingredient cannot be both removed and added", Map.of(INGREDIENT_ID, choice.optionId()));
            }
            if (option.type() != OptionType.ADD || !option.active()) {
                throw new BusinessException(ErrorCode.OPTION_NOT_EXTRA,
                        "This ingredient cannot be added as an extra", Map.of(INGREDIENT_ID, choice.optionId()));
            }
            int minimum = Math.max(1, option.minQuantity());
            if (choice.quantity() < minimum || choice.quantity() > option.maxQuantity()) {
                throw new BusinessException(ErrorCode.OPTION_QUANTITY_INVALID,
                        "Extra quantity must be between " + minimum + " and " + option.maxQuantity(),
                        Map.of(INGREDIENT_ID, choice.optionId()));
            }
            try {
                extrasDelta = Math.addExact(extrasDelta,
                        Math.multiplyExact(option.priceDeltaMinor(), (long) choice.quantity()));
            } catch (ArithmeticException _) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Selected extras exceed the allowed amount");
            }
            resolved.add(new ResolvedOption(choice.optionId(), SelectionAction.ADD, choice.quantity(),
                    option.name(), option.priceDeltaMinor()));
        }

        resolved.sort(Comparator.comparing(ResolvedOption::action).thenComparing(ResolvedOption::optionId));
        return new ResolvedSelection(List.copyOf(resolved), extrasDelta, note, selectionKey(resolved, note));
    }

    private static ProductOption lookup(Map<UUID, ProductOption> catalogOptions, UUID optionId) {
        ProductOption option = catalogOptions.get(optionId);
        if (option == null) {
            throw new BusinessException(ErrorCode.UNKNOWN_OPTION,
                    "The ingredient does not belong to this product", Map.of(INGREDIENT_ID, optionId));
        }
        return option;
    }

    /** Trims; blank becomes null; rejects control characters other than line breaks and tabs. Stored as plain text. */
    static String normalizeNote(String rawNote) {
        if (rawNote == null) {
            return null;
        }
        String note = rawNote.strip();
        if (note.isEmpty()) {
            return null;
        }
        for (int i = 0; i < note.length(); i++) {
            char c = note.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                throw new BusinessException(ErrorCode.INVALID_NOTE, "The note contains unsupported characters");
            }
        }
        return note;
    }

    private static String selectionKey(List<ResolvedOption> sortedOptions, String note) {
        StringBuilder canonical = new StringBuilder("v1");
        for (ResolvedOption option : sortedOptions) {
            canonical.append('|').append(option.action()).append(':').append(option.optionId())
                    .append(':').append(option.quantity());
        }
        canonical.append("|note:").append(note == null ? "" : note);
        return IdempotencyKeys.sha256(canonical.toString());
    }
}
