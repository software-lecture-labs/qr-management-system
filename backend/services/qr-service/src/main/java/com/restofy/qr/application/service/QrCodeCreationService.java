package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.CreatedQrCodeResponse;
import com.restofy.qr.api.dto.request.QrCodeVersionRequest;
import com.restofy.qr.domain.entity.QrCode;
import com.restofy.qr.domain.enums.QrCodeStatus;
import com.restofy.qr.domain.exception.QrCodeConflictException;
import com.restofy.qr.domain.exception.QrCodeNotFoundException;
import com.restofy.qr.domain.exception.TableNotFoundException;
import com.restofy.qr.infrastructure.persistence.repository.DiningTableRepository;
import com.restofy.qr.infrastructure.persistence.repository.QrCodeRepository;
import com.restofy.qr.infrastructure.security.QrPayloadCodec;
import com.restofy.qr.infrastructure.security.TableManagementAccess;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QrCodeCreationService {
    private final DiningTableRepository tables;
    private final QrCodeRepository codes;
    private final TableManagementAccess access;
    private final QrPayloadCodec codec;

    public QrCodeCreationService(DiningTableRepository tables, QrCodeRepository codes,
                                 TableManagementAccess access, QrPayloadCodec codec) {
        this.tables = tables;
        this.codes = codes;
        this.access = access;
        this.codec = codec;
    }

    @Transactional
    public CreatedQrCodeResponse create(UUID tableId) {
        UUID restaurantId = access.requireRestaurantId();
        var table = tables.findForQrManagement(restaurantId, tableId).orElseThrow(TableNotFoundException::new);
        if (!table.isActive() || codes.existsByRestaurantIdAndTableIdAndStatus(restaurantId, tableId, QrCodeStatus.ACTIVE)) {
            throw new QrCodeConflictException();
        }
        return issue(restaurantId, tableId);
    }

    @Transactional
    public CreatedQrCodeResponse rotate(UUID tableId, UUID qrId, QrCodeVersionRequest request) {
        UUID restaurantId = access.requireRestaurantId();
        var table = tables.findForQrManagement(restaurantId, tableId).orElseThrow(TableNotFoundException::new);
        if (!table.isActive()) {
            throw new QrCodeConflictException();
        }
        var previous = codes.findByRestaurantIdAndTableIdAndId(restaurantId, tableId, qrId)
                .orElseThrow(QrCodeNotFoundException::new);
        previous.revoke(request.version());
        // Flush the revocation before INSERT to release the partial unique index slot.
        // Both writes still commit or roll back in this same local transaction.
        codes.flush();
        return issue(restaurantId, tableId);
    }

    private CreatedQrCodeResponse issue(UUID restaurantId, UUID tableId) {
        UUID id = UUID.randomUUID();
        String token = codec.generateToken();
        var code = codes.saveAndFlush(new QrCode(id, restaurantId, tableId,
                codec.digest(token), codec.encrypt(token, restaurantId, tableId, id)));
        return new CreatedQrCodeResponse(code.getId(), code.getRestaurantId(), code.getTableId(),
                code.getStatus(), code.getVersion(), code.getIssuedAt(), code.getExpiresAt(), token);
    }
}
