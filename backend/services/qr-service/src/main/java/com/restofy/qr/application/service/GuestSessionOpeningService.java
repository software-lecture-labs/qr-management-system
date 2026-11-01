package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.GuestSessionResponse;
import com.restofy.qr.api.dto.response.OpenedGuestSessionResponse;
import com.restofy.qr.domain.entity.GuestSession;
import com.restofy.qr.domain.entity.QrScanEvent;
import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.infrastructure.persistence.repository.GuestSessionRepository;
import com.restofy.qr.infrastructure.persistence.repository.QrScanEventRepository;
import com.restofy.qr.infrastructure.security.GuestTokenCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GuestSessionOpeningService {
    private final TableSessionOpeningService mainSessions;
    private final GuestSessionRepository guests;
    private final GuestTokenCodec tokens;
    private final QrScanEventRepository scans;
    private final TransactionTemplate writes;
    private final TransactionTemplate mandatory;

    public GuestSessionOpeningService(TableSessionOpeningService mainSessions, GuestSessionRepository guests,
                                      GuestTokenCodec tokens, QrScanEventRepository scans, PlatformTransactionManager manager) {
        this.mainSessions = mainSessions; this.guests = guests; this.tokens = tokens; this.scans = scans;
        writes = new TransactionTemplate(manager);
        mandatory = new TransactionTemplate(manager);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    @Transactional(propagation = Propagation.NEVER)
    public OpenedGuestSessionResponse open(String qrToken) {
        var state = mainSessions.prepare(qrToken);
        return writes.execute(status -> openLocked(qrToken, state));
    }

    public OpenedGuestSessionResponse openLocked(String qrToken, OrderSessionState state) {
        return mandatory.execute(status -> createGuest(qrToken, state));
    }

    private OpenedGuestSessionResponse createGuest(String qrToken, OrderSessionState state) {
        // The mandatory template enforces the boundary even for calls within this class.
        var opened = mainSessions.openLocked(qrToken, state);
        var main = opened.session();
        var joinedAt = opened.checkedAt();
        String token = tokens.generateToken();
        var guest = guests.saveAndFlush(new GuestSession(main.restaurantId(), main.id(), tokens.digest(token), joinedAt));
        scans.saveAndFlush(new QrScanEvent(main.restaurantId(), main.tableId(), opened.qrCodeId(), main.id(), guest.getId(), joinedAt));
        return new OpenedGuestSessionResponse(main,
                new GuestSessionResponse(guest.getId(), guest.getRestaurantId(), guest.getTableSessionId(), guest.getStatus(),
                        guest.getVersion(), guest.getJoinedAt(), guest.getLastActivityAt(), guest.getExpiresAt(), false), token);
    }
}
