package com.restofy.qr.application.mapper;

import com.restofy.qr.api.dto.response.TableResponse;
import com.restofy.qr.domain.entity.DiningTable;
import org.springframework.stereotype.Component;

@Component
public class TableMapper {
    public TableResponse toResponse(DiningTable table) {
        return new TableResponse(table.getId(), table.getRestaurantId(), table.getTableNumber(),
                table.getDisplayName(), table.getCapacity(), table.getOperationalStatus(),
                table.isActive(), table.getVersion(), table.getCreatedAt(), table.getUpdatedAt());
    }
}
