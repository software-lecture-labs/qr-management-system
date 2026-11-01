package com.restofy.qr.api.dto.response;

import java.util.List;

public record QrCodePageResponse(List<QrCodeResponse> items, int page, int size,
                                  long totalElements, int totalPages) {
}
