package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.QrCodeResponse;
import com.restofy.qr.api.dto.response.QrCodePageResponse;
import com.restofy.qr.domain.entity.QrCode;
import com.restofy.qr.domain.exception.TableNotFoundException;
import com.restofy.qr.infrastructure.persistence.repository.DiningTableRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.restofy.qr.domain.exception.QrCodeNotFoundException;
import com.restofy.qr.domain.exception.QrCodeConflictException;
import com.restofy.qr.domain.enums.QrCodeStatus;
import com.restofy.qr.infrastructure.config.QrEntryUrl;
import com.restofy.qr.infrastructure.image.QrPngRenderer;
import com.restofy.qr.infrastructure.security.QrPayloadCodec;
import com.restofy.qr.infrastructure.persistence.repository.QrCodeRepository;
import com.restofy.qr.infrastructure.security.TableManagementAccess;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class QrCodeQueryService {
    private final QrCodeRepository codes;
    private final TableManagementAccess access;
    private final QrPayloadCodec codec;
    private final QrEntryUrl entryUrl;
    private final QrPngRenderer renderer;
    private final DiningTableRepository tables;

    public QrCodeQueryService(QrCodeRepository codes, TableManagementAccess access,
                              QrPayloadCodec codec, QrEntryUrl entryUrl, QrPngRenderer renderer,
                              DiningTableRepository tables) {
        this.codes = codes;
        this.access = access;
        this.codec = codec;
        this.entryUrl = entryUrl;
        this.renderer = renderer;
        this.tables = tables;
    }

    public QrCodePageResponse list(UUID tableId, int page, int size) {
        UUID restaurantId = access.requireRestaurantId();
        PageLimits.validate(page, size);
        tables.findByRestaurantIdAndId(restaurantId, tableId).orElseThrow(TableNotFoundException::new);
        var result = codes.findByRestaurantIdAndTableId(restaurantId, tableId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "issuedAt", "id")));
        return new QrCodePageResponse(result.map(QrCodeQueryService::metadata).getContent(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    public QrCodeResponse detail(UUID tableId, UUID qrId) {
        UUID restaurantId = access.requireRestaurantId();
        var code = codes.findByRestaurantIdAndTableIdAndId(restaurantId, tableId, qrId)
                .orElseThrow(QrCodeNotFoundException::new);
        return metadata(code);
    }

    public byte[] image(UUID tableId, UUID qrId) {
        UUID restaurantId = access.requireRestaurantId();
        var code = codes.findByRestaurantIdAndTableIdAndId(restaurantId, tableId, qrId)
                .orElseThrow(QrCodeNotFoundException::new);
        if (code.getStatus() == QrCodeStatus.REVOKED) {
            throw new QrCodeConflictException();
        }
        String token = codec.decrypt(code.getEncryptedPayload(), restaurantId, tableId, qrId);
        if (!codec.digest(token).equals(code.getTokenDigest())) {
            throw new IllegalStateException("QR credential storage is inconsistent.");
        }
        return renderer.render(entryUrl.forToken(token));
    }

    private static QrCodeResponse metadata(QrCode code) {
        return new QrCodeResponse(code.getId(), code.getRestaurantId(), code.getTableId(), code.getStatus(),
                code.getVersion(), code.getIssuedAt(), code.getRevokedAt(), code.getExpiresAt());
    }
}
