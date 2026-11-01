package com.restofy.qr.api;

import com.restofy.qr.api.controller.TableSessionLifecycleController;
import com.restofy.qr.api.exception.ApiExceptionHandler;
import com.restofy.qr.application.service.TableSessionClosureService;
import com.restofy.qr.domain.SessionClosure.Result;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Standalone cleaning mapping, not real Auth success E2E. Staff closure has its own controller and contract tests. */
class SessionLifecycleApiTests {
    private static MockMvc mvc(TableSessionClosureService service) {
        return MockMvcBuilders.standaloneSetup(new TableSessionLifecycleController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void mapsSeparateCleaningWithoutInventingAStaffClosureActor() throws Exception {
        var service = mock(TableSessionClosureService.class); UUID id = UUID.randomUUID();
        when(service.cleaned(id, 1)).thenReturn(new Result(id, UUID.randomUUID(), TableSessionStatus.CLOSED, 2,
                Instant.EPOCH, Instant.EPOCH, null, TableOperationalStatus.AVAILABLE, 7));
        mvc(service).perform(post("/api/v1/qr/table-sessions/{id}/cleaned", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("CLOSED")).andExpect(jsonPath("$.tableVersion").value(7));
        verify(service).cleaned(id, 1);
    }

    @Test void cleaningControllerDoesNotHandleTheSeparateStaffClosureContract() throws Exception {
        var service = mock(TableSessionClosureService.class);
        mvc(service).perform(post("/api/v1/qr/table-sessions/{id}/close", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"version\":null}", "{\"version\":-1}", "{\"version\":\"invalid\"}", "", "{broken"})
    void invalidCleaningVersionNeverReachesService(String body) throws Exception {
        var service = mock(TableSessionClosureService.class);
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var api = MockMvcBuilders.standaloneSetup(new TableSessionLifecycleController(service))
                    .setControllerAdvice(new ApiExceptionHandler()).setValidator(validator).build();
            api.perform(post("/api/v1/qr/table-sessions/{id}/cleaned", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"conflict", "unavailable"})
    void cleaningReportsSafeErrors(String failure) throws Exception {
        var service = mock(TableSessionClosureService.class); var id = UUID.randomUUID();
        when(service.cleaned(id, 0)).thenThrow(failure.equals("conflict") ? new SessionClosureConflictException() : new SessionClosureUnavailableException());
        mvc(service).perform(post("/api/v1/qr/table-sessions/{id}/cleaned", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
                .andExpect(status().is(failure.equals("conflict") ? 409 : 503));
    }
}
