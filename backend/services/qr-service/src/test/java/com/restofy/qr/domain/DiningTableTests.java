package com.restofy.qr.domain;

import com.restofy.qr.domain.entity.DiningTable;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.exception.TableConflictException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class DiningTableTests {
    @Test
    void createsNormalizedActiveAvailableTable() {
        UUID restaurant = UUID.randomUUID();
        var table = new DiningTable(restaurant, "  A-1 \t", "  Bahçe  ", 4);
        assertThat(table.getId()).isNotNull();
        assertThat(table.getRestaurantId()).isEqualTo(restaurant);
        assertThat(table.getTableNumber()).isEqualTo("A-1");
        assertThat(table.getDisplayName()).isEqualTo("Bahçe");
        assertThat(table.getCapacity()).isEqualTo(4);
        assertThat(table.isActive()).isTrue();
        assertThat(table.getOperationalStatus()).isEqualTo(TableOperationalStatus.AVAILABLE);
        table.requireVersion(0);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsBlankNumber(String number) {
        assertThatIllegalArgumentException().isThrownBy(() -> new DiningTable(UUID.randomUUID(), number, null, null));
    }

    @Test
    void validatesLimitsBeforeMutating() {
        var table = new DiningTable(UUID.randomUUID(), "a".repeat(40), "b".repeat(120), Integer.MAX_VALUE);
        assertThatIllegalArgumentException().isThrownBy(() -> table.updateDetails("a".repeat(41), null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> table.updateDetails("2", "a".repeat(121), null));
        assertThatIllegalArgumentException().isThrownBy(() -> table.updateDetails("2", null, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> table.updateDetails("2", null, -1));
        assertThat(table.getTableNumber()).hasSize(40);
        assertThat(table.getCapacity()).isEqualTo(Integer.MAX_VALUE);
        table.updateDetails("2", "  ", null);
        assertThat(table.getDisplayName()).isNull();
        assertThat(table.getCapacity()).isNull();
    }

    @Test
    void separatesActiveFromOperationalStateAndSupportsIdempotentChanges() {
        var table = new DiningTable(UUID.randomUUID(), "1", null, null);
        table.changeActive(true);
        table.changeActive(false);
        assertThat(table.isActive()).isFalse();
        assertThat(table.getOperationalStatus()).isEqualTo(TableOperationalStatus.DISABLED);
        table.changeActive(false);
        table.changeActive(true);
        assertThat(table.getOperationalStatus()).isEqualTo(TableOperationalStatus.AVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OCCUPIED", "CLEANING"})
    void refusesDisablingTablesInUse(String state) {
        var table = new DiningTable(UUID.randomUUID(), "1", null, null);
        ReflectionTestUtils.setField(table, "operationalStatus", TableOperationalStatus.valueOf(state));
        assertThatThrownBy(() -> table.changeActive(false)).isInstanceOf(TableConflictException.class);
        assertThat(table.isActive()).isTrue();
    }

    @Test
    void rejectsStaleAndNegativeVersion() {
        var table = new DiningTable(UUID.randomUUID(), "1", null, null);
        assertThatThrownBy(() -> table.requireVersion(-1)).isInstanceOf(TableConflictException.class);
        assertThatThrownBy(() -> table.requireVersion(1)).isInstanceOf(TableConflictException.class);
        assertThatNullPointerException().isThrownBy(() -> new DiningTable(null, "1", null, null));
    }

    @Test
    void openingASessionOccupiesAnActiveAvailableTableAndPreventsDisablingIt() {
        var table = new DiningTable(UUID.randomUUID(), "1", null, null);
        table.occupy();
        assertThat(table.isActive()).isTrue();
        assertThat(table.getOperationalStatus()).isEqualTo(TableOperationalStatus.OCCUPIED);
        assertThatThrownBy(table::occupy).isInstanceOf(TableConflictException.class);
        assertThatThrownBy(() -> table.changeActive(false)).isInstanceOf(TableConflictException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISABLED", "OCCUPIED", "CLEANING"})
    void unavailableOperationalStatesCannotBeOccupiedAgain(String state) {
        var table = new DiningTable(UUID.randomUUID(), "1", null, null);
        var status = TableOperationalStatus.valueOf(state);
        ReflectionTestUtils.setField(table, "operationalStatus", status);
        ReflectionTestUtils.setField(table, "active", status != TableOperationalStatus.DISABLED);
        assertThatThrownBy(table::occupy).isInstanceOf(TableConflictException.class);
        assertThat(table.getOperationalStatus()).isEqualTo(status);
    }
}
