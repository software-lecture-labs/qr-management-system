package com.restofy.kitchen.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real HTTP over the embedded Tomcat (not MockMvc): the anonymous contract of the running service.
 * Only the health endpoint is anonymous; every other actuator endpoint and every business path stays closed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KitchenHttpSecurityTests {

	@Value("${local.server.port}")
	private int port;

	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

	private HttpResponse<String> send(String method, String path, String... headers) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.method(method, HttpRequest.BodyPublishers.noBody());
		for (int i = 0; i < headers.length; i += 2) {
			builder.header(headers[i], headers[i + 1]);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
		return send("GET", path, headers);
	}

	@Test
	void healthIsAnonymousReturns200UpAndExposesNoDetails() throws Exception {
		HttpResponse<String> response = get("/actuator/health");

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"status\":\"UP\""), response.body());
		for (String leak : List.of("components", "details", "db", "diskSpace", "jdbc", "postgres", "url", "password",
				"username", "restofy_", "127.0.0.1")) {
			assertFalse(response.body().toLowerCase().contains(leak.toLowerCase()), "health leaked '" + leak + "': " + response.body());
		}
	}

	@Test
	void livenessAndReadinessGroupsAreAnonymousAndUp() throws Exception {
		for (String path : List.of("/actuator/health/liveness", "/actuator/health/readiness")) {
			HttpResponse<String> response = get(path);
			assertEquals(200, response.statusCode(), path);
			assertTrue(response.body().contains("\"status\":\"UP\""), path + " " + response.body());
		}
	}

	@Test
	void everyOtherActuatorEndpointIsClosedToAnonymousCallers() throws Exception {
		for (String path : List.of("/actuator", "/actuator/info", "/actuator/env", "/actuator/beans", "/actuator/metrics",
				"/actuator/loggers", "/actuator/configprops", "/actuator/mappings", "/actuator/heapdump",
				"/actuator/threaddump", "/actuator/prometheus", "/actuator/shutdown", "/actuator/anything-else",
				"/actuator/health/unknown-group/../../env")) {
			HttpResponse<String> response = get(path);
			assertNotEquals(200, response.statusCode(), path + " must not be anonymous");
			assertTrue(response.statusCode() == 401 || response.statusCode() == 400, path + " -> " + response.statusCode());
			assertFalse(response.body().contains("propertySources"), path);
		}
	}

	@Test
	void businessEndpointsAndAllOtherPathsStayClosedWithoutAuthentication() throws Exception {
		for (String path : List.of("/api/v1/kitchen/orders", "/api/v1/kitchen/orders?status=ACTIVE",
				"/api/v1/kitchen/orders/" + UUID.randomUUID(), "/api/v1/kitchen/anything", "/api/v1/other", "/",
				"/error", "/v3/api-docs", "/swagger-ui/index.html", "/h2-console", "/login")) {
			HttpResponse<String> response = get(path);
			assertEquals(401, response.statusCode(), path);
			assertFalse(response.body().contains("orderId"), path);
		}
	}

	@Test
	void forgedIdentityHeadersAndBasicCredentialsGrantNothing() throws Exception {
		String restaurant = UUID.randomUUID().toString();
		HttpResponse<String> spoofed = get("/api/v1/kitchen/orders", "X-Restaurant-Id", restaurant, "X-Role", "ADMIN",
				"X-User-Id", UUID.randomUUID().toString());
		assertEquals(401, spoofed.statusCode());

		HttpResponse<String> basic = get("/api/v1/kitchen/orders",
				"Authorization", "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes()));
		assertEquals(401, basic.statusCode());
		HttpResponse<String> bearer = get("/api/v1/kitchen/orders", "Authorization", "Bearer not-a-real-token");
		assertEquals(401, bearer.statusCode());
	}

	@Test
	void healthOnlyAcceptsReadsAndStateChangingCallsAreNotSuccessful() throws Exception {
		for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
			HttpResponse<String> response = send(method, "/actuator/health");
			assertFalse(response.statusCode() / 100 == 2, method + " -> " + response.statusCode());
		}
		for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
			assertNotEquals(200, send(method, "/api/v1/kitchen/orders").statusCode(), method);
		}
	}
}
