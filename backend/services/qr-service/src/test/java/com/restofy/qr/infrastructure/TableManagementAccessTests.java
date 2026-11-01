package com.restofy.qr.infrastructure;

import com.restofy.qr.application.mapper.TableMapper;
import com.restofy.qr.application.service.TableManagementService;
import com.restofy.qr.api.dto.request.*;
import com.restofy.qr.infrastructure.persistence.repository.DiningTableRepository;
import com.restofy.qr.infrastructure.security.TableManagementAccess;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TableManagementAccessTests {
    @Test
    void everyUseCaseDeniesAccessBeforeQueryingWithoutVerifiedContext() {
        var repository = mock(DiningTableRepository.class);
        var service = new TableManagementService(repository, new TableMapper(), new TableManagementAccess());
        UUID id = UUID.randomUUID();
        var createRequest = new CreateTableRequest("1", null, null);
        var updateRequest = new UpdateTableRequest("1", null, null, 0L);
        var activeRequest = new ChangeTableActiveRequest(false, 0L);
        assertThatThrownBy(() -> service.create(createRequest))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> service.update(id, updateRequest))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> service.changeActive(id, activeRequest))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> service.list(null, null, 0, 20, "tableNumber", "asc"))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        verifyNoInteractions(repository);
    }
}
