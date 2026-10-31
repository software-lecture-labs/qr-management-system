package com.restofy.waiter.infrastructure.config;

import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.infrastructure.client.notification.NotificationClient;
import com.restofy.waiter.infrastructure.client.order.OrderClient;
import com.restofy.waiter.infrastructure.client.qr.QrClient;
import com.restofy.waiter.support.DownstreamStub;
import com.restofy.waiter.support.FakeBackends;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.ResourcePropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The existing environment contract (QR_SERVICE_URL, ORDER_SERVICE_URL, REST_CLIENT_*_TIMEOUT_MS) maps to typed
 * properties through the real application.properties; invalid values stop the service from starting.
 */
class RestClientConfigurationTests {

    private final DownstreamStub peer = DownstreamStub.start();

    @BeforeEach
    void routes() {
        peer.reset();
    }

    @AfterEach
    void stopPeer() {
        peer.close();
    }

    private Map<String, Object> environment() {
        Map<String, Object> env = new HashMap<>();
        env.put("QR_SERVICE_URL", peer.baseUrl().toString());
        env.put("ORDER_SERVICE_URL", peer.baseUrl().toString());
        env.put("NOTIFICATION_SERVICE_URL", peer.baseUrl().toString());
        env.put("REST_CLIENT_CONNECT_TIMEOUT_MS", "2000");
        env.put("REST_CLIENT_READ_TIMEOUT_MS", "3000");
        return env;
    }

    private ApplicationContextRunner runner(Map<String, Object> env) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, RestClientAutoConfiguration.class))
                .withUserConfiguration(RestClientConfiguration.class, QrClient.class, OrderClient.class,
                        NotificationClient.class)
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.addFirst(new SystemEnvironmentPropertySource("test-environment", env));
                    try {
                        sources.addLast(new ResourcePropertySource("classpath:application.properties"));
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                });
    }

    @Test
    void environmentVariablesMapToTypedPropertiesAndTheHttpClient() {
        runner(environment()).run(context -> {
            assertNull(context.getStartupFailure());
            var urls = context.getBean(ServiceUrlProperties.class);
            assertEquals(peer.baseUrl(), urls.qrUrl());
            assertEquals(peer.baseUrl(), urls.orderUrl());
            assertEquals(peer.baseUrl(), urls.notificationUrl());
            assertEquals(new RestClientProperties(2000, 3000), context.getBean(RestClientProperties.class));
            assertEquals(Duration.ofMillis(2000), context.getBean("serviceHttpClient", HttpClient.class).connectTimeout().orElseThrow());
        });
    }

    @Test
    void timeoutDefaultsAreFiniteWhenTheVariablesAreAbsent() {
        Map<String, Object> env = environment();
        env.remove("REST_CLIENT_CONNECT_TIMEOUT_MS");
        env.remove("REST_CLIENT_READ_TIMEOUT_MS");
        AtomicReference<HttpClient> owned = new AtomicReference<>();
        runner(env).run(context -> {
            assertEquals(new RestClientProperties(3000, 5000), context.getBean(RestClientProperties.class));
            owned.set(context.getBean("serviceHttpClient", HttpClient.class));
        });
        assertTrue(owned.get().isTerminated(), "Spring must close the owned HTTP client");
    }

    @ParameterizedTest
    @ValueSource(strings = {"QR_SERVICE_URL", "ORDER_SERVICE_URL", "NOTIFICATION_SERVICE_URL"})
    void missingServiceUrlStopsStartup(String variable) {
        Map<String, Object> env = environment();
        env.remove(variable);
        runner(env).run(context -> assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "QR_SERVICE_URL|qr-service:8080", "QR_SERVICE_URL|ftp://qr-service:8080", "QR_SERVICE_URL|/relative",
            "QR_SERVICE_URL|http://user:secret@qr-service:8080", "QR_SERVICE_URL|''", "QR_SERVICE_URL|http://",
            "ORDER_SERVICE_URL|file:///etc/passwd", "ORDER_SERVICE_URL|http://user@order-service:8080",
            "ORDER_SERVICE_URL|order-service", "NOTIFICATION_SERVICE_URL|notification-service:8080",
            "NOTIFICATION_SERVICE_URL|http://user:secret@notification-service:8080", "NOTIFICATION_SERVICE_URL|ftp://notification-service"})
    void invalidServiceUrlsStopStartup(String variable, String value) {
        Map<String, Object> env = environment();
        env.put(variable, value);
        runner(env).run(context -> assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @CsvSource({"REST_CLIENT_CONNECT_TIMEOUT_MS,0", "REST_CLIENT_CONNECT_TIMEOUT_MS,-1", "REST_CLIENT_CONNECT_TIMEOUT_MS,invalid",
            "REST_CLIENT_READ_TIMEOUT_MS,0", "REST_CLIENT_READ_TIMEOUT_MS,-1", "REST_CLIENT_READ_TIMEOUT_MS,invalid"})
    void invalidTimeoutsStopStartup(String variable, String value) {
        Map<String, Object> env = environment();
        env.put(variable, value);
        runner(env).run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void clientsSendNoInventedCredentialsAndUseTheConfiguredPaths() {
        FakeBackends backends = new FakeBackends().installOn(peer);
        UUID restaurant = UUID.randomUUID();
        runner(environment()).run(context -> {
            assertTrue(context.getBean(QrClient.class).listAllTables(restaurant).isEmpty());
            assertTrue(context.getBean(QrClient.class).findTable(restaurant, UUID.randomUUID()).isEmpty());
            assertTrue(context.getBean(OrderClient.class).findOrder(restaurant, UUID.randomUUID()).isEmpty());
            assertTrue(context.getBean(OrderClient.class).activeOrderCounts(restaurant, List.of()).isEmpty());
        });
        assertNotNull(backends);
        assertEquals(3, peer.requests().size(), "An empty session list must not produce a request");
        for (DownstreamStub.Request request : peer.requests()) {
            assertNull(request.header("Authorization"), "No token may be invented before the service identity contract exists");
            assertNull(request.header("Cookie"));
            assertTrue(request.path().startsWith("/internal/v1/"), request.path());
        }
    }

    @Test
    void refusedConnectionIsUnavailableNotEmpty() {
        URI unusable = peer.baseUrl();
        peer.close();
        Map<String, Object> env = environment();
        env.put("QR_SERVICE_URL", unusable.toString());
        env.put("ORDER_SERVICE_URL", unusable.toString());
        env.put("NOTIFICATION_SERVICE_URL", unusable.toString());
        runner(env).run(context -> {
            DependencyException qr = assertThrows(DependencyException.class,
                    () -> context.getBean(QrClient.class).listAllTables(UUID.randomUUID()));
            assertEquals(DependencyFailure.UNAVAILABLE, qr.failure());
            assertEquals("qr", qr.dependency());
            DependencyException order = assertThrows(DependencyException.class,
                    () -> context.getBean(OrderClient.class).findOrder(UUID.randomUUID(), UUID.randomUUID()));
            assertEquals(DependencyFailure.UNAVAILABLE, order.failure());
            assertTrue(!order.getMessage().contains("127.0.0.1"), "Internal addresses must not appear in messages");
            DependencyException notification = assertThrows(DependencyException.class,
                    () -> context.getBean(NotificationClient.class).publish(UUID.randomUUID(), "{}"));
            assertEquals(DependencyFailure.UNAVAILABLE, notification.failure());
            assertEquals("notification", notification.dependency());
        });
    }

    @Test
    void clientsRefuseOversizedSessionListsBeforeTheyReachTheNetwork() {
        runner(environment()).run(context -> {
            OrderClient client = context.getBean(OrderClient.class);
            List<UUID> tooMany = IntStream.range(0, OrderClient.MAX_SESSION_IDS + 1)
                    .mapToObj(index -> UUID.randomUUID()).toList();
            UUID restaurant = UUID.randomUUID();
            assertThrows(IllegalArgumentException.class, () -> client.activeOrderCounts(restaurant, tooMany));
            assertThrows(IllegalArgumentException.class, () -> client.search(restaurant, null, tooMany, null, 0, 20));
            assertThrows(IllegalArgumentException.class, () -> client.search(restaurant, null, List.of(), null, 0, 20));
        });
        assertTrue(peer.requests().isEmpty());
    }
}
