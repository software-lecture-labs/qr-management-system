package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.request.ChangeQrCodeActiveRequest;
import com.restofy.qr.api.dto.request.QrCodeVersionRequest;
import com.restofy.qr.api.dto.response.QrCodeResponse;
import com.restofy.qr.domain.entity.QrCode;
import com.restofy.qr.domain.enums.QrCodeStatus;
import com.restofy.qr.domain.exception.QrCodeConflictException;
import com.restofy.qr.domain.exception.QrCodeNotFoundException;
import com.restofy.qr.domain.exception.TableNotFoundException;
import com.restofy.qr.infrastructure.persistence.repository.DiningTableRepository;
import com.restofy.qr.infrastructure.persistence.repository.QrCodeRepository;
import com.restofy.qr.infrastructure.security.TableManagementAccess;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class QrCodeLifecycleService {
    private final DiningTableRepository tables;
    private final QrCodeRepository codes;
    private final TableManagementAccess access;

    public QrCodeLifecycleService(DiningTableRepository tables, QrCodeRepository codes, TableManagementAccess access) {
        this.tables = tables;
        this.codes = codes;
        this.access = access;
    }

    public QrCodeResponse changeActive(UUID tableId, UUID qrId, ChangeQrCodeActiveRequest request) {
        UUID restaurantId = access.requireRestaurantId();
        var table = tables.findForQrManagement(restaurantId, tableId).orElseThrow(TableNotFoundException::new);
        var code = requireCode(restaurantId, tableId, qrId);
        if (request.isActive() && (!table.isActive() || (code.getStatus() != QrCodeStatus.ACTIVE
                && codes.existsByRestaurantIdAndTableIdAndStatus(restaurantId, tableId, QrCodeStatus.ACTIVE)))) {
            throw new QrCodeConflictException();
        }
        code.changeActive(request.isActive(), request.version());
        codes.flush();
        return new QrCodeResponse(code.getId(), code.getRestaurantId(), code.getTableId(), code.getStatus(),
                code.getVersion(), code.getIssuedAt(), code.getRevokedAt(), code.getExpiresAt());
    }

    public void delete(UUID tableId, UUID qrId, QrCodeVersionRequest request) {
        UUID restaurantId = access.requireRestaurantId();
        tables.findForQrManagement(restaurantId, tableId).orElseThrow(TableNotFoundException::new);
        var code = requireCode(restaurantId, tableId, qrId);
        code.requireVersion(request.version());
        // Physical deletion only. Existing referencing FKs must reject deletion, never cascade history.
        codes.delete(code);
        codes.flush();
    }

    private QrCode requireCode(UUID restaurantId, UUID tableId, UUID qrId) {
        return codes.findByRestaurantIdAndTableIdAndId(restaurantId, tableId, qrId)
                .orElseThrow(QrCodeNotFoundException::new);
    }
}
