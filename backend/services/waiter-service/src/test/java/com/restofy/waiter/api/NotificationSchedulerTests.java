package com.restofy.waiter.api;

import com.restofy.waiter.support.ApiTestBase;
import com.restofy.waiter.support.FakeBackends;
import com.restofy.waiter.support.FakeNotification.Fault;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The background trigger itself: nobody calls the dispatcher here, the scheduler does. The context is closed
 * after the class so that its trigger cannot compete with the deterministic delivery tests of other classes.
 */
@SpringBootTest(properties = {"restofy.notification.dispatcher.enabled=true",
        "restofy.notification.dispatcher.interval-ms=100",
        "restofy.notification.dispatcher.initial-backoff-seconds=1",
        "restofy.notification.dispatcher.max-backoff-seconds=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationSchedulerTests extends ApiTestBase {

    @Test
    void aCreatedCallReachesNotificationWithoutAnyoneStartingTheDispatcher() throws Exception {
        FakeBackends.Table table = addTable("1", "OCCUPIED", UUID.randomUUID());
        raiseCall(addGuest(table), "BILL").andExpect(status().isCreated());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(1, notification.receivedWithOutcome("APPLIED").size()));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals("DELIVERED", jdbc.queryForObject("SELECT status FROM notification_deliveries", String.class)));
    }

    @Test
    void anOutageIsRiddenOutByTheRetryScheduleAfterwards() throws Exception {
        FakeBackends.Table table = addTable("1", "OCCUPIED", UUID.randomUUID());
        notification.failNext(Fault.UNAVAILABLE, 2);
        raiseCall(addGuest(table), "HELP").andExpect(status().isCreated());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertEquals("DELIVERED", jdbc.queryForObject("SELECT status FROM notification_deliveries", String.class)));
        assertEquals(3, jdbc.queryForObject("SELECT attempt_count FROM notification_deliveries", Integer.class));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM service_requests", String.class));
    }
}
