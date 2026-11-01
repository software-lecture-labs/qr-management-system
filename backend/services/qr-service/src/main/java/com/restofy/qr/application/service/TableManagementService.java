package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.request.*;
import com.restofy.qr.api.dto.response.*;
import com.restofy.qr.application.mapper.TableMapper;
import com.restofy.qr.domain.entity.DiningTable;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.exception.TableNotFoundException;
import com.restofy.qr.infrastructure.persistence.repository.DiningTableRepository;
import com.restofy.qr.infrastructure.security.TableManagementAccess;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TableManagementService {
    private final DiningTableRepository repository;
    private final TableMapper mapper;
    private final TableManagementAccess access;

    public TableManagementService(DiningTableRepository repository, TableMapper mapper, TableManagementAccess access) {
        this.repository = repository;
        this.mapper = mapper;
        this.access = access;
    }

    @Transactional
    public TableResponse create(CreateTableRequest request) {
        var table = new DiningTable(access.requireRestaurantId(), request.tableNumber(), request.displayName(), request.capacity());
        return mapper.toResponse(repository.saveAndFlush(table));
    }

    @Transactional
    public TableResponse update(UUID id, UpdateTableRequest request) {
        var table = requireTable(access.requireRestaurantId(), id);
        table.requireVersion(request.version());
        table.updateDetails(request.tableNumber(), request.displayName(), request.capacity());
        repository.flush();
        return mapper.toResponse(table);
    }

    @Transactional
    public TableResponse changeActive(UUID id, ChangeTableActiveRequest request) {
        var table = requireTable(access.requireRestaurantId(), id);
        table.requireVersion(request.version());
        table.changeActive(request.isActive());
        repository.flush();
        return mapper.toResponse(table);
    }

    public TableResponse get(UUID id) {
        return mapper.toResponse(requireTable(access.requireRestaurantId(), id));
    }

    public TablePageResponse list(Boolean active, TableOperationalStatus status, int page, int size,
                                   String sort, String direction) {
        UUID restaurantId = access.requireRestaurantId();
        PageLimits.validate(page, size);
        String property = switch (sort) {
            case "tableNumber", "createdAt", "updatedAt" -> sort;
            default -> throw new IllegalArgumentException("Unsupported sort field.");
        };
        Sort.Direction sortDirection = switch (direction) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new IllegalArgumentException("Direction must be asc or desc.");
        };
        var tables = repository.findTables(restaurantId, active, status,
                PageRequest.of(page, size, Sort.by(sortDirection, property).and(Sort.by("id"))));
        return new TablePageResponse(tables.map(mapper::toResponse).getContent(), page, size,
                tables.getTotalElements(), tables.getTotalPages());
    }

    private DiningTable requireTable(UUID restaurantId, UUID id) {
        return repository.findByRestaurantIdAndId(restaurantId, id).orElseThrow(TableNotFoundException::new);
    }
}
