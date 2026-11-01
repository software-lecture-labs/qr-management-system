package com.restofy.qr.domain;

import com.restofy.qr.domain.entity.QrScanEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QrScanEventTests {
    @Test
    void acceptedEventRetainsOnlyItsOwnedReferencesAndTime() {
        UUID restaurant = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID qr = UUID.randomUUID();
        UUID main = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        Instant now = Instant.now();
        var event = new QrScanEvent(restaurant, table, qr, main, guest, now);
        assertThat(event.getId()).isNotNull();
        assertThat(event.getRestaurantId()).isEqualTo(restaurant);
        assertThat(event.getTableId()).isEqualTo(table);
        assertThat(event.getQrCodeId()).isEqualTo(qr);
        assertThat(event.getTableSessionId()).isEqualTo(main);
        assertThat(event.getGuestSessionId()).isEqualTo(guest);
        assertThat(event.getScannedAt()).isEqualTo(now);
        assertThat(event.getOutcome()).isEqualTo("ACCEPTED");
    }

    @Test
    void incompleteOwnershipOrTimeCannotConstructAnAcceptedEvent() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        assertThatNullPointerException().isThrownBy(() -> new QrScanEvent(null, id, id, id, id, now));
        assertThatNullPointerException().isThrownBy(() -> new QrScanEvent(id, null, id, id, id, now));
        assertThatNullPointerException().isThrownBy(() -> new QrScanEvent(id, id, null, id, id, now));
        assertThatNullPointerException().isThrownBy(() -> new QrScanEvent(id, id, id, null, id, now));
        assertThatNullPointerException().isThrownBy(() -> new QrScanEvent(id, id, id, id, null, now));
        assertThatNullPointerException().isThrownBy(() -> new QrScanEvent(id, id, id, id, id, null));
    }
}
