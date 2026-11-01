package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.ValidatedQrCodeResponse;
import com.restofy.qr.domain.exception.InvalidQrCodeException;
import com.restofy.qr.infrastructure.persistence.repository.DiningTableRepository;
import com.restofy.qr.infrastructure.persistence.repository.QrCodeRepository;
import com.restofy.qr.infrastructure.security.QrPayloadCodec;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QrCodeValidationService {
    private final QrCodeRepository codes;
    private final DiningTableRepository tables;
    private final QrPayloadCodec codec;
    private final Clock clock;

    public QrCodeValidationService(QrCodeRepository codes, DiningTableRepository tables,
                                    QrPayloadCodec codec, Clock clock) {
        this.codes = codes;
        this.tables = tables;
        this.codec = codec;
        this.clock = clock;
    }

    @Transactional
    public ValidatedQrCodeResponse validate(String token) {
        String digest = codec.digest(token);
        var location = codes.locateByDigest(digest).orElseThrow(InvalidQrCodeException::new);
        // Reuse the lifecycle lock, then read fresh state. A successful lookup is
        // only a snapshot; session opening must revalidate in its own transaction.
        var table = tables.findForQrManagement(location.getRestaurantId(), location.getTableId())
                .orElseThrow(InvalidQrCodeException::new);
        var code = codes.findByRestaurantIdAndTableIdAndId(location.getRestaurantId(), location.getTableId(), location.getId())
                .orElseThrow(InvalidQrCodeException::new);
        var now = clock.instant();
        if (!table.isActive() || !code.isUsableAt(now) || !digest.equals(code.getTokenDigest())) {
            throw new InvalidQrCodeException();
        }
        return new ValidatedQrCodeResponse(code.getId(), code.getRestaurantId(), table.getId(),
                table.getTableNumber(), table.getDisplayName(), now);
    }
}
