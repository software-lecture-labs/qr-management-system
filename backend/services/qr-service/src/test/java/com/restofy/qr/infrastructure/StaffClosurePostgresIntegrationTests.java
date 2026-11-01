package com.restofy.qr.infrastructure;

import com.restofy.qr.application.service.StaffClosureService;
import com.restofy.qr.api.controller.StaffClosureController;
import com.restofy.qr.api.controller.WaiterClosureController;
import com.restofy.qr.api.dto.request.StaffCloseRequest;
import com.restofy.qr.api.exception.ApiExceptionHandler;
import com.restofy.qr.api.dto.response.StaffClosureResponse.State;
import com.restofy.qr.domain.OrderSessionState.Knowledge;
import com.restofy.qr.domain.SessionClosure.Staff;
import com.restofy.qr.domain.SessionTransition.*;
import com.restofy.qr.domain.StaffClosure.*;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.*;
import com.restofy.qr.infrastructure.security.WaiterLifecycleAccess;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StaffClosurePostgresIntegrationTests extends TransitionIntegrationSupport {
    @Autowired StaffClosureService staffClosure;
    @Autowired JsonMapper json;
    @Autowired RequestMappingHandlerAdapter adapter;
    @MockitoBean WaiterLifecycleAccess waiterAccess;

    @BeforeEach void verifiedWaiterFixture() {
        when(waiterAccess.requireStaff()).thenAnswer(invocation -> new Staff(tenant, staffId));
    }

    private Request request(Fixture fixture) {
        UUID id = main(fixture);
        return new Request(UUID.randomUUID(), tenant, fixture.code().tableId(), id,
                sessions.findById(id).orElseThrow().getVersion(), staffId, "Staff requested closure", time.get());
    }
    private Request changed(Request request, String reason, long version) {
        return new Request(request.commandId(), request.restaurantId(), request.tableId(), request.tableSessionId(),
                version, request.requestedByUserId(), reason, request.requestedAt());
    }

    @ParameterizedTest @EnumSource(Origin.class)
    void verifiedStaffEndsEveryGuestAndRecordsTheRealActorWithoutASecondFinancialLedger(Origin origin) {
        var fixture = create();
        opening.open(fixture.code().qrToken());
        state(fixture, Knowledge.SETTLED, Set.of(fixture.owner().guestSession().id()));
        var request = request(fixture);
        var receipt = staffClosure.close(request, origin);
        assertThat(receipt.state()).isEqualTo(State.CLOSED);
        assertThat(receipt.guestSessions().total()).isEqualTo(2);
        assertThat(receipt.guestSessions().ended()).isEqualTo(2);
        assertThat(receipt.table().operationalStatus()).isEqualTo(TableOperationalStatus.CLEANING);
        assertThat(sessions.findById(main(fixture)).orElseThrow().getClosedByUserId()).isEqualTo(staffId);
        var command = transitions.findCommand(tenant, request.commandId()).orElseThrow();
        assertThat(command.initiatedBy().request()).isEqualTo(request);
        assertThat(command.initiatedBy().origin()).isEqualTo(origin);
        assertThat(operations.findById(request.commandId()).orElseThrow().getRequestJson())
                .doesNotContain(fixture.owner().guestToken(), fixture.code().qrToken());
        assertThat(staffClosure.close(request, origin)).isEqualTo(receipt);
        verify(source, times(1)).prepare(any());
    }

    @ParameterizedTest @ValueSource(strings = {"reason", "version", "actor", "origin"})
    void anAcceptedCommandCannotBeReusedForDifferentIntent(String field) {
        var fixture = create(); state(fixture, Knowledge.SETTLED, Set.of());
        var request = request(fixture); staffClosure.close(request, Origin.STAFF);
        Request modified = request;
        Origin origin = Origin.STAFF;
        if (field.equals("reason")) { modified = changed(request, "Different reason", request.expectedVersion()); }
        if (field.equals("version")) { modified = changed(request, request.reason(), request.expectedVersion() + 1); }
        if (field.equals("actor")) {
            UUID other = UUID.randomUUID();
            doReturn(new Staff(tenant, other)).when(staffAccess).requireStaff();
            modified = new Request(request.commandId(), tenant, request.tableId(), request.tableSessionId(),
                    request.expectedVersion(), other, request.reason(), request.requestedAt());
        }
        if (field.equals("origin")) { origin = Origin.WAITER_SERVICE; }
        Request finalRequest = modified;
        Origin finalOrigin = origin;
        assertThatThrownBy(() -> staffClosure.close(finalRequest, finalOrigin)).isInstanceOf(StaffClosureException.class);
        verify(source, times(1)).prepare(any());
    }

    @ParameterizedTest @ValueSource(strings = {"restaurant", "user"})
    void bodyIdentityClaimsDoNotGrantStaffAuthority(String field) {
        var fixture = create(); var valid = request(fixture);
        var forged = new Request(valid.commandId(), field.equals("restaurant") ? UUID.randomUUID() : tenant,
                valid.tableId(), valid.tableSessionId(), valid.expectedVersion(),
                field.equals("user") ? UUID.randomUUID() : staffId, null, valid.requestedAt());
        assertThatThrownBy(() -> staffClosure.close(forged, Origin.STAFF)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(source);
        assertThat(sessions.findById(main(fixture)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
    }

    @Test void verifiedStaffAloneCannotImpersonateTheWaiterService() {
        var fixture = create(); var request = request(fixture);
        doThrow(new AuthenticationCredentialsNotFoundException("test unavailable")).when(waiterAccess).requireStaff();
        assertThatThrownBy(() -> staffClosure.close(request, Origin.WAITER_SERVICE))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        verifyNoInteractions(source);
    }

    @ParameterizedTest @EnumSource(value = Knowledge.class, names = {"UNKNOWN", "NO_ORDERS", "OPEN_WORK"})
    void staffCannotBypassMissingPaymentOrIncompleteOrderAuthority(Knowledge knowledge) {
        var fixture = create(); state(fixture, knowledge, Set.of());
        var request = request(fixture);
        Class<? extends RuntimeException> expected = knowledge == Knowledge.UNKNOWN
                ? SessionClosureUnavailableException.class : StaffClosureException.class;
        assertThatThrownBy(() -> staffClosure.close(request, Origin.STAFF)).isInstanceOf(expected);
        assertThat(transitions.findCommand(tenant, request.commandId())).isEmpty();
        assertThat(sessions.findById(main(fixture)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
        verify(source, never()).prepare(any());
    }

    @ParameterizedTest @ValueSource(strings = {"version", "table", "future"})
    void staleOrMismatchedTargetsCannotCloseAMain(String field) {
        var fixture = create(); state(fixture, Knowledge.SETTLED, Set.of());
        var valid = request(fixture);
        var request = new Request(valid.commandId(), tenant, field.equals("table") ? UUID.randomUUID() : valid.tableId(),
                valid.tableSessionId(), valid.expectedVersion() + (field.equals("version") ? 1 : 0), staffId, null,
                field.equals("future") ? time.get().plusSeconds(1) : valid.requestedAt());
        Class<? extends RuntimeException> expected = field.equals("future") ? IllegalArgumentException.class : StaffClosureException.class;
        assertThatThrownBy(() -> staffClosure.close(request, Origin.STAFF)).isInstanceOf(expected);
        assertThat(transitions.findCommand(tenant, request.commandId())).isEmpty();
        assertThat(sessions.findById(main(fixture)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
    }

    @Test void lostPrepareReplyHasAReadOnlyStatusAndResumesTheSameDurableCommand() {
        var fixture = create(); state(fixture, Knowledge.SETTLED, Set.of());
        var request = request(fixture);
        doThrow(new SessionClosureUnavailableException()).when(source).prepare(any());
        assertThat(staffClosure.close(request, Origin.WAITER_SERVICE).state()).isEqualTo(State.CLOSING);
        clearInvocations(source);
        var status = staffClosure.status(tenant, request.tableId(), request.tableSessionId(), request.commandId(), Origin.WAITER_SERVICE);
        assertThat(status.state()).isEqualTo(State.CLOSING);
        verifyNoInteractions(source);
        assertThat(lifecycle.resume(tenant, request.commandId()).stage()).isEqualTo(Stage.COMPLETED);
        assertThat(staffClosure.status(tenant, request.tableId(), request.tableSessionId(), request.commandId(), Origin.WAITER_SERVICE).state())
                .isEqualTo(State.CLOSED);
    }

    @Test void lostAckDoesNotUndoLocalClosureOrTouchTheNextVisitAndPostReplaysTheOriginalReceipt() {
        var fixture = create(); state(fixture, Knowledge.SETTLED, Set.of());
        var request = request(fixture);
        doThrow(new SessionClosureUnavailableException()).when(source).acknowledge(any(), any());
        var receipt = staffClosure.close(request, Origin.STAFF);
        assertThat(receipt.state()).isEqualTo(State.CLOSED);
        assertThat(operations.findById(request.commandId()).orElseThrow().getStage()).isEqualTo(Stage.APPLIED);
        lifecycle.cleaned(main(fixture), receipt.table().version());
        time.set(time.get().plusSeconds(1));
        var next = opening.open(fixture.code().qrToken());
        doAnswer(invocation -> confirmed(invocation.getArgument(0), invocation.getArgument(1))).when(source).acknowledge(any(), any());
        assertThat(staffClosure.close(request, Origin.STAFF).table().operationalStatus()).isEqualTo(TableOperationalStatus.CLEANING);
        var status = staffClosure.status(tenant, request.tableId(), request.tableSessionId(), request.commandId(), Origin.STAFF);
        assertThat(status.state()).isEqualTo(State.CLOSED);
        assertThat(status.table().currentSession().id()).isEqualTo(next.tableSession().id());
        assertThat(guests.findById(next.guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.ACTIVE);
        verify(source, times(1)).prepare(any());
    }

    @Test void aDefinitiveSourceRefusalReopensOnlyAfterConfirmedAbort() {
        var fixture = create(); state(fixture, Knowledge.SETTLED, Set.of());
        var request = request(fixture);
        doAnswer(call -> authority(call.getArgument(0), SourceStatus.BLOCKED, Effect.NONE, Set.of())).when(source).prepare(any());
        doReturn(null).when(source).abort(any());
        assertThat(staffClosure.close(request, Origin.STAFF).state()).isEqualTo(State.CLOSING);
        doAnswer(invocation -> released(invocation.getArgument(0))).when(source).abort(any());
        assertThat(staffClosure.close(request, Origin.STAFF).state()).isEqualTo(State.REJECTED);
        assertThat(sessions.findById(main(fixture)).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
    }

    @Test void concurrentIdenticalStaffCommandsHaveOneIntentAndOneClosingEffect() throws Exception {
        var fixture = create(); state(fixture, Knowledge.SETTLED, Set.of());
        var request = request(fixture);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return staffClosure.close(request, Origin.STAFF); });
            var second = pool.submit(() -> { start.await(); return staffClosure.close(request, Origin.STAFF); });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).state()).isEqualTo(State.CLOSED);
            assertThat(second.get(20, TimeUnit.SECONDS).state()).isEqualTo(State.CLOSED);
        }
        assertThat(jdbc.queryForObject("select count(*) from session_transition_operations where id=?", Integer.class, request.commandId())).isEqualTo(1);
        assertThat(sessions.findById(main(fixture)).orElseThrow().getClosedByUserId()).isEqualTo(staffId);
    }

    @Test void oldAutomaticJsonAndDigestsRemainReadableWithoutMigrationChanges() {
        var fixture = create(); var now = time.get();
        var snapshot = new Snapshot(tenant, main(fixture), fixture.code().tableId(), 1, 1, now, now, null, Map.of());
        var old = new Command(UUID.randomUUID(), Purpose.SETTLED, snapshot, now);
        ObjectNode tree = (ObjectNode) json.readTree(json.writeValueAsString(old));
        tree.remove("initiatedBy");
        var restored = json.readValue(tree.toString(), Command.class);
        assertThat(restored).isEqualTo(old);
        assertThat(restored.digest()).isEqualTo(old.digest());
        assertThat(restored.initiatedBy()).isNull();
    }

    private MockMvc api() {
        return MockMvcBuilders.standaloneSetup(new StaffClosureController(staffClosure, staffAccess), new WaiterClosureController(staffClosure))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(adapter.getMessageConverters().toArray(HttpMessageConverter[]::new)).build();
    }

    private String body(Request request) {
        return json.writeValueAsString(new StaffCloseRequest(request.commandId(), request.restaurantId(), request.tableId(),
                request.tableSessionId(), request.expectedVersion(), request.requestedByUserId(), request.reason(), request.requestedAt()));
    }

    private String path(Request request, boolean internal) {
        return internal ? "/api/v1/internal/tables/" + request.tableId() + "/sessions/" + request.tableSessionId()
                : "/api/v1/qr/table-sessions/" + request.tableSessionId();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void httpCommandAndReadOnlyStatusUseWaiterCompatibleReceiptsAndAStableKey(boolean internal) throws Exception {
        var fixture = create(); state(fixture, Knowledge.SETTLED, Set.of());
        var request = request(fixture); var api = api();
        String base = path(request, internal);
        api.perform(post(base + "/close").header("Idempotency-Key", request.commandId().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body(request)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.commandId").value(request.commandId().toString()))
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.guestSessions.total").value(1)).andExpect(jsonPath("$.guestSessions.ended").value(1))
                .andExpect(jsonPath("$.table.operationalStatus").value("CLEANING")).andExpect(jsonPath("$.table.active").value(true));
        clearInvocations(source);
        api.perform(get(base + "/close-commands/" + request.commandId())
                .param(internal ? "restaurantId" : "tableId", internal ? tenant.toString() : request.tableId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CLOSED"));
        verifyNoInteractions(source);
    }

    @ParameterizedTest @ValueSource(strings = {"force", "paid", "role", "nullVersion", "negativeVersion", "numberReason", "longReason", "badKey", "missingKey", "wrongPath"})
    void malformedOrAuthorityClaimingHttpBodiesCannotCreateAnIntent(String kind) throws Exception {
        var fixture = create(); state(fixture, Knowledge.SETTLED, Set.of());
        var request = request(fixture);
        ObjectNode body = (ObjectNode) json.readTree(body(request));
        switch (kind) {
            case "force", "paid" -> body.put(kind, true);
            case "role" -> body.put("role", "ADMIN");
            case "nullVersion" -> body.putNull("expectedVersion");
            case "negativeVersion" -> body.put("expectedVersion", -1);
            case "numberReason" -> body.put("reason", 123);
            case "longReason" -> body.put("reason", "x".repeat(301));
            default -> { /* header/path cases use the unchanged body */ }
        }
        String base = kind.equals("wrongPath") ? "/api/v1/qr/table-sessions/" + UUID.randomUUID() : path(request, false);
        var post = post(base + "/close").contentType(MediaType.APPLICATION_JSON).content(body.toString());
        if (!kind.equals("missingKey")) { post.header("Idempotency-Key", kind.equals("badKey") ? UUID.randomUUID().toString() : request.commandId().toString()); }
        api().perform(post).andExpect(status().isBadRequest());
        assertThat(transitions.findCommand(tenant, request.commandId())).isEmpty();
        verifyNoInteractions(source);
    }

    @Test void httpUnknownPrepareReturnsAcceptedAndDefinitiveBlockReturnsConflict() throws Exception {
        var first = create(); state(first, Knowledge.SETTLED, Set.of());
        var pending = request(first);
        doThrow(new SessionClosureUnavailableException()).when(source).prepare(any());
        api().perform(post(path(pending, true) + "/close").header("Idempotency-Key", pending.commandId().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body(pending)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("CLOSING"));
        var second = create(); state(second, Knowledge.OPEN_WORK, Set.of());
        var blocked = request(second);
        api().perform(post(path(blocked, true) + "/close").header("Idempotency-Key", blocked.commandId().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body(blocked)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSURE_BLOCKED"));
    }

    @Test void statusCannotBeReadForAnotherTenantTargetOrAutomaticOperation() {
        var fixture = create(); state(fixture, Knowledge.SETTLED, Set.of());
        var request = request(fixture); staffClosure.close(request, Origin.STAFF);
        UUID otherTenant = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        UUID targetTableId = request.tableId();
        UUID targetSessionId = request.tableSessionId();
        UUID targetCommandId = request.commandId();
        assertThatThrownBy(() -> staffClosure.status(otherTenant, targetTableId, targetSessionId, targetCommandId, Origin.STAFF))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> staffClosure.status(tenant, otherTable, targetSessionId, targetCommandId, Origin.STAFF))
                .isInstanceOf(StaffClosureException.class);
        var automatic = create(); var closed = settle(automatic);
        UUID tableId = automatic.code().tableId(); UUID sessionId = main(automatic); UUID operationId = closed.operationId();
        assertThatThrownBy(() -> staffClosure.status(tenant, tableId, sessionId, operationId, Origin.STAFF))
                .isInstanceOf(StaffClosureException.class);
    }
}
