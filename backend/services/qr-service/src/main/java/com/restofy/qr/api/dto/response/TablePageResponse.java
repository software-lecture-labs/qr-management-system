package com.restofy.qr.api.dto.response;

import java.util.List;

public record TablePageResponse(List<TableResponse> content, int page, int size,
                                long totalElements, int totalPages) {
}
