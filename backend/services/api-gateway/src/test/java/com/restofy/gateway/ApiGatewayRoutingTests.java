package com.restofy.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = "logging.level.root=WARN")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiGatewayRoutingTests {

    private static final GatewayDownstreams DOWNSTREAMS = new GatewayDownstreams();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();

    @Value("${local.server.port}")
    private int gatewayPort;

    @Autowired
    private GatewayMvcProperties routeProperties;

    @Autowired
    private WebApplicationContext context;

    @DynamicPropertySource
    static void downstreamAddresses(DynamicPropertyRegistry registry) {
        DOWNSTREAMS.register(registry);
    }

    @AfterAll
    static void closeHttpResources() {
        CLIENT.close();
        DOWNSTREAMS.close();
    }

    static Stream<Arguments> routePaths() {
        return GatewayDownstreams.ROUTES.stream().flatMap(route -> Stream.of(
                Arguments.of(route.service(), route.prefix()),
                Arguments.of(route.service(), route.prefix() + route.child())));
    }

    private HttpRequest.Builder gatewayRequest(String path, String id) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                .timeout(Duration.ofSeconds(5)).header(GatewayDownstreams.REQUEST_ID, id);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private GatewayDownstreams.Received onlyRequest(String id, String service) {
        var requests = DOWNSTREAMS.requests(id);
        assertEquals(1, requests.size(), "Exactly one downstream must receive the request");
        assertEquals(service, requests.getFirst().service(), "No other service may receive this request");
        return requests.getFirst();
    }

    private void assertResponse(HttpResponse<String> response, String service, int status) {
        assertEquals(status, response.statusCode());
        assertEquals(GatewayDownstreams.responseBody(service), response.body());
        assertEquals(service, response.headers().firstValue("X-Downstream-Service").orElseThrow());
    }

    @Test
    void productionPropertiesBindExactlyTwelveUniqueMvcRoutesUsingEnvironmentUris() throws Exception {
        var routes = routeProperties.getRoutes();
        assertEquals(12, routes.size());
        assertTrue(routeProperties.getRoutesMap().isEmpty());
        assertEquals(12, new HashSet<>(routes.stream().map(route -> route.getId()).toList()).size());
        assertEquals(GatewayDownstreams.ROUTES.stream().map(GatewayDownstreams.Route::service).collect(Collectors.toSet()),
                routes.stream().map(route -> route.getId()).collect(Collectors.toSet()));
        assertNotNull(context.getServletContext());
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty(), "Gateway tests must not create a database");
        var source = new Properties();
        try (var input = new ClassPathResource("application.properties").getInputStream()) {
            source.load(input);
        }
        for (int index = 0; index < GatewayDownstreams.ROUTES.size(); index++) {
            var contract = GatewayDownstreams.ROUTES.get(index);
            var bound = routes.stream().filter(route -> contract.service().equals(route.getId())).findFirst().orElseThrow();
            assertEquals(DOWNSTREAMS.address(contract.service()), bound.getUri());
            assertNotEquals(gatewayPort, bound.getUri().getPort());
            assertTrue(bound.getFilters().isEmpty(), "Path rewriting is outside the routing contract");
            assertEquals("${" + contract.environmentKey() + "}",
                    source.getProperty(GatewayMvcProperties.PREFIX + ".routes[" + index + "].uri"));
        }
    }

    @ParameterizedTest(name = "{0}: GET {1}")
    @MethodSource("routePaths")
    void forwardsEveryRootAndChildPathToExactlyTheCorrectService(String service, String path) throws Exception {
        String id = UUID.randomUUID().toString();
        var response = send(gatewayRequest(path, id).GET().build());
        assertResponse(response, service, 200);
        var received = onlyRequest(id, service);
        assertEquals("GET", received.method());
        assertEquals(path, received.path(), "API prefix must be preserved");
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/v1/auth/login,auth-service,201",
            "PUT,/api/v1/orders/123,order-service,200",
            "DELETE,/api/v1/menu/categories/42,menu-service,200"})
    void forwardsMethodsAndJsonBodiesWithoutChangingBytes(String method, String path, String service, int status) throws Exception {
        String id = UUID.randomUUID().toString();
        byte[] body = method.equals("DELETE") ? new byte[0] : "{\n  \"name\": \"Türkçe çay\", \"quantity\": 2\n}\n".getBytes(StandardCharsets.UTF_8);
        var response = send(gatewayRequest(path, id).header("Content-Type", "application/json")
                .header(GatewayDownstreams.RESPONSE_STATUS, String.valueOf(status))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build());
        assertResponse(response, service, status);
        var received = onlyRequest(id, service);
        assertEquals(method, received.method());
        assertEquals(path, received.path());
        assertArrayEquals(body, received.body());
        var mediaType = MediaType.parseMediaType(received.header("Content-Type"));
        assertEquals("application", mediaType.getType());
        assertEquals("json", mediaType.getSubtype());
        if (mediaType.getCharset() != null) assertEquals(StandardCharsets.UTF_8, mediaType.getCharset());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/carts", "/api/v1/carts/current", "/api/v1/carts/current/items/123"})
    void forwardsGuestCartPathsToTheOrderServiceKeepingThePrefix(String path) throws Exception {
        String id = UUID.randomUUID().toString();
        var response = send(gatewayRequest(path, id).header("Authorization", "Bearer fixture-not-a-real-token")
                .GET().build());
        assertResponse(response, "order-service", 200);
        var received = onlyRequest(id, "order-service");
        assertEquals(path, received.path());
        assertEquals("Bearer fixture-not-a-real-token", received.header("Authorization"));
    }

    @Test
    void preservesQueryParametersIncludingRepeatedAndEncodedValues() throws Exception {
        String id = UUID.randomUUID().toString();
        String query = "page=1&size=10&search=%C3%A7ay%20%26%20kahve&tag=a&tag=b";
        var response = send(gatewayRequest("/api/v1/orders?" + query, id).GET().build());
        assertResponse(response, "order-service", 200);
        var received = onlyRequest(id, "order-service");
        assertEquals("/api/v1/orders", received.path());
        assertEquals(query, received.query());
    }

    @Test
    void forwardsStandardAndCustomHeadersWithoutImplementingAuthorization() throws Exception {
        String id = UUID.randomUUID().toString();
        var response = send(gatewayRequest("/api/v1/auth/login", id)
                .header("Authorization", "Bearer fixture-not-a-real-token")
                .header("X-Correlation-Id", id).header("Accept", "application/json").GET().build());
        assertResponse(response, "auth-service", 200);
        var received = onlyRequest(id, "auth-service");
        assertEquals("Bearer fixture-not-a-real-token", received.header("Authorization"));
        assertEquals(id, received.header("X-Correlation-Id"));
        assertEquals("application/json", received.header("Accept"));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 400, 404, 500})
    void preservesDownstreamResponseStatusesAndBodies(int status) throws Exception {
        String id = UUID.randomUUID().toString();
        var response = send(gatewayRequest("/api/v1/orders/123", id)
                .header(GatewayDownstreams.RESPONSE_STATUS, String.valueOf(status)).GET().build());
        assertResponse(response, "order-service", status);
        onlyRequest(id, "order-service");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/unknown", "/api/v1/authentication", "/api/v1/orders-extra", "/api/v1/carts-extra", "/api/v1/menu-item", "/api/v1/ratings-extra"})
    void unknownAndSimilarPrefixesReturn404WithoutContactingAnyDownstream(String path) throws Exception {
        String id = UUID.randomUUID().toString();
        var response = send(gatewayRequest(path, id).GET().build());
        assertEquals(404, response.statusCode());
        assertTrue(DOWNSTREAMS.requests(id).isEmpty());
    }

    @Test
    void gatewayHealthIsLocalAndDoesNotForwardToAnyDownstream() throws Exception {
        String id = UUID.randomUUID().toString();
        var response = send(gatewayRequest("/actuator/health", id).GET().build());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().matches("(?s).*\"status\"\\s*:\\s*\"UP\".*"));
        assertTrue(DOWNSTREAMS.requests(id).isEmpty());
    }
}
