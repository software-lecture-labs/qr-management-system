package com.restofy.qr.infrastructure;

import com.restofy.qr.api.controller.QrCodeReadController;
import com.restofy.qr.api.dto.request.ChangeQrCodeActiveRequest;
import com.restofy.qr.api.dto.request.CreateTableRequest;
import com.restofy.qr.api.dto.request.QrCodeVersionRequest;
import com.restofy.qr.api.dto.response.CreatedQrCodeResponse;
import com.restofy.qr.api.exception.ApiExceptionHandler;
import com.restofy.qr.application.service.*;
import com.restofy.qr.domain.enums.QrCodeStatus;
import com.restofy.qr.domain.exception.QrCodeNotFoundException;
import com.restofy.qr.infrastructure.persistence.repository.QrCodeRepository;
import com.restofy.qr.infrastructure.persistence.repository.DiningTableRepository;
import com.restofy.qr.infrastructure.security.TableManagementAccess;
import com.restofy.qr.infrastructure.security.QrPayloadCodec;
import com.restofy.qr.infrastructure.config.QrEntryUrl;
import com.restofy.qr.infrastructure.image.QrPngRenderer;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "qr.public-entry-url=https://guest.example.test/qr"})
class QrReadPostgresIntegrationTests {
    @Autowired QrCodeCreationService creation;
    @Autowired QrCodeLifecycleService lifecycle;
    @Autowired QrCodeQueryService queries;
    @Autowired TableManagementService tables;
    @Autowired QrCodeRepository codes;
    @Autowired DiningTableRepository tableRepository;
    @Autowired QrPayloadCodec codec;
    @Autowired JdbcTemplate jdbc;
    @Autowired RequestMappingHandlerAdapter adapter;
    // Only the test tenant seam is mocked; persistence and all use cases are real.
    @MockitoBean TableManagementAccess access;
    private final AtomicReference<UUID> restaurant = new AtomicReference<>();
    private MockMvc api;

    @BeforeEach
    void setup() {
        restaurant.set(UUID.randomUUID());
        when(access.requireRestaurantId()).thenAnswer(invocation -> restaurant.get());
        api = MockMvcBuilders.standaloneSetup(new QrCodeReadController(queries))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(adapter.getMessageConverters().toArray(HttpMessageConverter[]::new)).build();
    }

    private CreatedQrCodeResponse create() {
        return creation.create(tables.create(new CreateTableRequest("1", null, null)).id());
    }

    private String path(CreatedQrCodeResponse code) {
        return "/api/v1/qr/tables/" + code.tableId() + "/qr-codes/" + code.id();
    }

    @Test
    void metadataContainsCurrentVersionWithoutDisclosingCredentialStorageOrChangingIt() throws Exception {
        var code = create();
        var before = codes.findById(code.id()).orElseThrow();
        for (int request = 0; request < 2; request++) {
            api.perform(get(path(code)))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.id").value(code.id().toString()))
                    .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.qrToken").doesNotExist())
                    .andExpect(jsonPath("$.tokenDigest").doesNotExist())
                    .andExpect(jsonPath("$.encryptedPayload").doesNotExist());
        }
        var after = codes.findById(code.id()).orElseThrow();
        assertThat(after.getVersion()).isZero();
        assertThat(after.getEncryptedPayload()).isEqualTo(before.getEncryptedPayload());
        assertThat(after.getTokenDigest()).isEqualTo(before.getTokenDigest());
    }

    @Test
    void metadataShowsInactiveAndRevokedRecordsWithoutReactivatingThem() {
        var code = create();
        lifecycle.changeActive(code.tableId(), code.id(), new ChangeQrCodeActiveRequest(false, 0L));
        assertThat(queries.detail(code.tableId(), code.id()).status()).isEqualTo(QrCodeStatus.INACTIVE);
        lifecycle.changeActive(code.tableId(), code.id(), new ChangeQrCodeActiveRequest(true, 1L));
        var replacement = creation.rotate(code.tableId(), code.id(), new QrCodeVersionRequest(2L));
        var revoked = queries.detail(code.tableId(), code.id());
        assertThat(revoked.status()).isEqualTo(QrCodeStatus.REVOKED);
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(revoked.version()).isEqualTo(3);
        assertThat(queries.detail(code.tableId(), replacement.id()).status()).isEqualTo(QrCodeStatus.ACTIVE);
    }

    @Test
    void missingWrongTableAndForeignTenantAreHidden() throws Exception {
        var code = create();
        UUID tableId = code.tableId();
        UUID qrId = code.id();
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> queries.detail(missing, qrId)).isInstanceOf(QrCodeNotFoundException.class);
        assertThatThrownBy(() -> queries.detail(tableId, missing)).isInstanceOf(QrCodeNotFoundException.class);
        restaurant.set(UUID.randomUUID());
        api.perform(get(path(code))).andExpect(status().isNotFound());
    }

    @Test
    void deletedCodeCannotBeReadAgain() throws Exception {
        var code = create();
        lifecycle.delete(code.tableId(), code.id(), new QrCodeVersionRequest(0L));
        api.perform(get(path(code))).andExpect(status().isNotFound());
    }

    @Test
    void missingVerifiedContextCannotReadMetadata() throws Exception {
        var code = create();
        when(access.requireRestaurantId()).thenThrow(new AuthenticationCredentialsNotFoundException("Test context absent"));
        api.perform(get(path(code))).andExpect(status().isUnauthorized());
    }

    @Test
    void malformedIdentifiersProduceControlledBadRequest() throws Exception {
        api.perform(get("/api/v1/qr/tables/not-a-uuid/qr-codes/not-a-uuid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Request could not be processed."));
    }

    @Test
    void viewAndDownloadDecodeToTheSameOriginalCredentialWithoutIssuingOrChangingIt() throws Exception {
        var code = create();
        byte[] view = api.perform(get(path(code) + "/image").accept(MediaType.IMAGE_PNG))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow, noarchive"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"qr-" + code.id() + ".png\""))
                .andReturn().getResponse().getContentAsByteArray();
        byte[] download = api.perform(get(path(code) + "/download").accept(MediaType.IMAGE_PNG))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"qr-" + code.id() + ".png\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(download).isEqualTo(view);
        assertThat(queries.image(code.tableId(), code.id())).isEqualTo(view);
        assertThat(QrImageTests.decode(view)).isEqualTo("https://guest.example.test/qr#token=" + code.qrToken());
        assertThat(codes.findById(code.id()).orElseThrow().getVersion()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from qr_codes where table_id=?", Integer.class, code.tableId())).isEqualTo(1);
    }

    @Test
    void inactiveQrAndTableCanBePrintedWithoutBeingEnabled() throws Exception {
        var code = create();
        lifecycle.changeActive(code.tableId(), code.id(), new ChangeQrCodeActiveRequest(false, 0L));
        var table = tables.get(code.tableId());
        tables.changeActive(code.tableId(), new com.restofy.qr.api.dto.request.ChangeTableActiveRequest(false, table.version()));
        assertThat(QrImageTests.decode(queries.image(code.tableId(), code.id()))).endsWith("#token=" + code.qrToken());
        assertThat(queries.detail(code.tableId(), code.id()).status()).isEqualTo(QrCodeStatus.INACTIVE);
        assertThat(queries.detail(code.tableId(), code.id()).version()).isEqualTo(1);
        assertThat(tables.get(code.tableId()).isActive()).isFalse();
    }

    @Test
    void rotationStopsExportingOldQrAndNewImageContainsNewToken() throws Exception {
        var code = create();
        var next = creation.rotate(code.tableId(), code.id(), new QrCodeVersionRequest(0L));
        for (String suffix : new String[]{"/image", "/download"}) {
            api.perform(get(path(code) + suffix).accept(MediaType.IMAGE_PNG)).andExpect(status().isConflict());
        }
        assertThat(QrImageTests.decode(queries.image(next.tableId(), next.id())))
                .isEqualTo("https://guest.example.test/qr#token=" + next.qrToken());
    }

    @Test
    void imageAndDownloadHideForeignWrongTableMissingAndDeletedQr() throws Exception {
        var code = create();
        for (String suffix : new String[]{"/image", "/download"}) {
            api.perform(get("/api/v1/qr/tables/" + UUID.randomUUID() + "/qr-codes/" + code.id() + suffix))
                    .andExpect(status().isNotFound());
        }
        restaurant.set(UUID.randomUUID());
        api.perform(get(path(code) + "/image")).andExpect(status().isNotFound());
        api.perform(get(path(code) + "/download")).andExpect(status().isNotFound());
        restaurant.set(code.restaurantId());
        lifecycle.delete(code.tableId(), code.id(), new QrCodeVersionRequest(0L));
        api.perform(get(path(code) + "/image")).andExpect(status().isNotFound());
        api.perform(get(path(code) + "/download")).andExpect(status().isNotFound());
    }

    @Test
    void corruptedCiphertextAndDigestNeverProduceQrImagesOrLeakStorage() throws Exception {
        var code = create();
        jdbc.update("update qr_codes set token_digest=? where id=?", "0".repeat(64), code.id());
        api.perform(get(path(code) + "/image").accept(MediaType.IMAGE_PNG)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("The request could not be completed."));
        jdbc.update("update qr_codes set encrypted_payload=? where id=?", "v1:" + "A".repeat(95), code.id());
        api.perform(get(path(code) + "/download").accept(MediaType.IMAGE_PNG)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("The request could not be completed."));
    }

    @Test
    void missingConfiguredAddressReturns503WhileMetadataRemainsAvailable() throws Exception {
        var code = create();
        var disabled = new QrCodeQueryService(codes, access, codec, new QrEntryUrl("", new MockEnvironment()), new QrPngRenderer(), tableRepository);
        var disabledApi = MockMvcBuilders.standaloneSetup(new QrCodeReadController(disabled))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        disabledApi.perform(get(path(code) + "/image").accept(MediaType.IMAGE_PNG))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.detail").value(
                        "QR image rendering is unavailable until the public entry URL is configured."));
        assertThat(disabled.detail(code.tableId(), code.id()).id()).isEqualTo(code.id());
    }

    @Test
    void missingVerifiedContextCannotExportImages() throws Exception {
        var code = create();
        when(access.requireRestaurantId()).thenThrow(new AuthenticationCredentialsNotFoundException("Test context absent"));
        api.perform(get(path(code) + "/image")).andExpect(status().isUnauthorized());
        api.perform(get(path(code) + "/download")).andExpect(status().isUnauthorized());
    }

    @Test
    void reloadCanDiscoverQrIdsAndVersionsWithBoundedPaginationAndNoCredentialFields() throws Exception {
        var first = create();
        var second = creation.rotate(first.tableId(), first.id(), new QrCodeVersionRequest(0L));
        api.perform(get("/api/v1/qr/tables/" + first.tableId() + "/qr-codes").queryParam("size", "1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].id").value(second.id().toString()))
                .andExpect(jsonPath("$.items[0].version").value(0)).andExpect(jsonPath("$.items[0].qrToken").doesNotExist())
                .andExpect(jsonPath("$.items[0].tokenDigest").doesNotExist())
                .andExpect(jsonPath("$.items[0].encryptedPayload").doesNotExist());
        var previousPage = queries.list(first.tableId(), 1, 1);
        assertThat(previousPage.items()).hasSize(1);
        assertThat(previousPage.items().getFirst().id()).isEqualTo(first.id());
        assertThat(previousPage.items().getFirst().status()).isEqualTo(QrCodeStatus.REVOKED);
        assertThat(queries.list(first.tableId(), 2, 1).items()).isEmpty();
    }

    @Test
    void listHidesForeignOrMissingTablesAndHandlesEmptyOwnedTables() throws Exception {
        var empty = tables.create(new CreateTableRequest("empty", null, null));
        assertThat(queries.list(empty.id(), 0, 20).items()).isEmpty();
        restaurant.set(UUID.randomUUID());
        api.perform(get("/api/v1/qr/tables/" + empty.id() + "/qr-codes")).andExpect(status().isNotFound());
        api.perform(get("/api/v1/qr/tables/" + UUID.randomUUID() + "/qr-codes")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=10001", "size=0", "size=101", "page=invalid"})
    void invalidPageBoundsAndTypesAreRejected(String query) throws Exception {
        var code = create();
        api.perform(get("/api/v1/qr/tables/" + code.tableId() + "/qr-codes?" + query)).andExpect(status().isBadRequest());
    }
}
