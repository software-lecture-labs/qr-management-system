package com.restofy.qr.infrastructure.image;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public class QrPngRenderer {
    private static final int SIZE = 512;

    public byte[] render(String payload) {
        if (payload == null || payload.isEmpty() || payload.length() > 1024) {
            throw new IllegalArgumentException("Invalid QR image payload.");
        }
        try {
            var matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, SIZE, SIZE,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4));
            var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "PNG", output)) {
                throw new IOException();
            }
            return output.toByteArray();
        } catch (WriterException | IOException _) {
            throw new IllegalStateException("QR image could not be generated.");
        }
    }
}
