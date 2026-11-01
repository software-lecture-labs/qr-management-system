package com.restofy.order.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One loud, unmistakable warning when the Order Service starts with the {@code dev} profile. In that profile the guest,
 * staff and service identities are fixed placeholders that perform no authentication, and the API Gateway routes
 * requests to this service as it does in any other profile, so a shared server running it would let anybody act as an
 * ADMIN. The four mock adapters each still log their own line; this block makes the consequence impossible to miss.
 * It names no credential and is never produced outside {@code dev}: every other profile fails closed (503) instead.
 */
@Slf4j
@Component
@Profile("dev")
public class DevProfileWarning {

    static final List<String> LINES = List.of(
            "################################################################################",
            "#  ORDER SERVICE IS RUNNING WITH THE 'dev' PROFILE: NO AUTHENTICATION HAPPENS  #",
            "#  Fixed placeholder guest, staff (incl. ADMIN) and service identities are     #",
            "#  accepted, also when requests arrive through the API Gateway.                #",
            "#  Never use this profile on a shared, reachable or production server.         #",
            "################################################################################");

    @EventListener(ApplicationReadyEvent.class)
    void announce() {
        LINES.forEach(log::warn);
    }
}
