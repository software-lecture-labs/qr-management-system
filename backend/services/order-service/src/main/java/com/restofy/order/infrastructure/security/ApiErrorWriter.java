package com.restofy.order.infrastructure.security;

import com.restofy.order.api.exception.ErrorResponse;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Writes the API error body from servlet-level code (filters, entry points) that sits outside MVC advice. */
@Component
public class ApiErrorWriter {

    private final JsonMapper jsonMapper;

    public ApiErrorWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (code == ErrorCode.UNAUTHENTICATED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        jsonMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(code, message, null, MDC.get(CorrelationIdFilter.MDC_KEY)));
    }
}
