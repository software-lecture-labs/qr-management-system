package com.restofy.order.domain.history;

import com.restofy.order.application.port.StaffActor;

import java.util.UUID;

/**
 * Who or what caused a status change, in a form that is safe to keep: SYSTEM (the order service itself), GUEST (an
 * internal guest session id, never a token), STAFF (verified user id and role) or SERVICE (a verified service name).
 */
public record ChangeActor(Type type, String id, String role) {

    public enum Type { SYSTEM, GUEST, STAFF, SERVICE }

    public static ChangeActor system() {
        return new ChangeActor(Type.SYSTEM, null, null);
    }

    public static ChangeActor guest(UUID guestSessionId) {
        return new ChangeActor(Type.GUEST, guestSessionId.toString(), null);
    }

    public static ChangeActor staff(StaffActor actor) {
        return new ChangeActor(Type.STAFF, actor.userId().toString(), actor.role().name());
    }

    public static ChangeActor service(String serviceName) {
        return new ChangeActor(Type.SERVICE, serviceName, serviceName);
    }
}
