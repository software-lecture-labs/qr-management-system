package com.restofy.qr.api;

import com.restofy.qr.api.dto.request.*;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

class TableValidationTests {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() { FACTORY.close(); }

    static Stream<Object> invalidRequests() {
        return Stream.of(new CreateTableRequest(null, null, null), new CreateTableRequest(" ", null, null),
                new CreateTableRequest("x".repeat(41), null, null), new CreateTableRequest("1", "x".repeat(121), null),
                new CreateTableRequest("1", null, 0), new CreateTableRequest("1", null, -1),
                new UpdateTableRequest("1", null, 1, null), new UpdateTableRequest("1", null, 1, -1L),
                new ChangeTableActiveRequest(null, 0L), new ChangeTableActiveRequest(true, null),
                new ChangeTableActiveRequest(false, -1L),
                new QrCodeVersionRequest(null), new QrCodeVersionRequest(-1L),
                new ChangeQrCodeActiveRequest(null, 0L), new ChangeQrCodeActiveRequest(true, null),
                new ChangeQrCodeActiveRequest(false, -1L));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidRequests(Object request) {
        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @Test
    void acceptsOptionalFieldsAndBoundaryValues() {
        assertThat(VALIDATOR.validate(new CreateTableRequest("x".repeat(40), "x".repeat(120), 1))).isEmpty();
        assertThat(VALIDATOR.validate(new UpdateTableRequest("1", null, null, 0L))).isEmpty();
        assertThat(VALIDATOR.validate(new ChangeTableActiveRequest(false, 0L))).isEmpty();
        assertThat(VALIDATOR.validate(new QrCodeVersionRequest(0L))).isEmpty();
        assertThat(VALIDATOR.validate(new ChangeQrCodeActiveRequest(false, 0L))).isEmpty();
        assertThat(VALIDATOR.validate(new ValidateQrCodeRequest("A".repeat(43)))).isEmpty();
    }

    @Test
    void qrValidationRequestNeverPrintsItsCredential() {
        String token = "A".repeat(43);
        assertThat(new ValidateQrCodeRequest(token).toString()).contains("REDACTED").doesNotContain(token);
    }

    @Test
    void qrValidationRejectsIncorrectLengthsAndNonUrlSafeCharacters() {
        for (String token : new String[]{"A".repeat(42), "A".repeat(44), "A".repeat(42) + "+", " " + "A".repeat(43)}) {
            assertThat(VALIDATOR.validate(new ValidateQrCodeRequest(token))).isNotEmpty();
        }
    }
}
