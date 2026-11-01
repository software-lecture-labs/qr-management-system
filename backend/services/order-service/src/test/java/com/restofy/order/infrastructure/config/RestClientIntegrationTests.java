package com.restofy.order.infrastructure.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RestClientIntegrationTests {

    public record Payload(String text, int quantity) { }
    public record Reply(String status) { }
    private record Received(String method, String path, String query, byte[] body, Map<String,List<String>> headers) {
        String header(String name) {
            var values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null ? null : values.getFirst();
        }
    }

    private HttpServer peer;
    private ExecutorService workers;
    private String baseUrl;
    private boolean peerStopped;
    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
    private final CountDownLatch requestArrived = new CountDownLatch(1);
    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    private final CountDownLatch responseBodyStarted = new CountDownLatch(1);
    private volatile boolean delayResponse;
    private volatile boolean headersBeforeDelay;
    private volatile int status = 200;
    private volatile String contentType = "application/json";
    private volatile String responseBody = "{\"status\":\"ok\"}";

    @BeforeEach
    void startLocalHttpPeer() throws Exception {
        workers = Executors.newVirtualThreadPerTaskExecutor();
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        peer.setExecutor(workers);
        baseUrl = "http://127.0.0.1:" + peer.getAddress().getPort();
        peer.createContext("/", exchange -> {
            try (exchange) {
                var headers = new HashMap<String,List<String>>();
                exchange.getRequestHeaders().forEach((name,values) -> headers.put(name.toLowerCase(Locale.ROOT),List.copyOf(values)));
                received.add(new Received(exchange.getRequestMethod(),exchange.getRequestURI().getRawPath(),
                        exchange.getRequestURI().getRawQuery(),exchange.getRequestBody().readAllBytes(),Map.copyOf(headers)));
                requestArrived.countDown();
                byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type",contentType);
                exchange.getResponseHeaders().set("X-Peer","test-only");
                if (headersBeforeDelay) {
                    exchange.sendResponseHeaders(status,body.length);
                    exchange.getResponseBody().write(body,0,1);
                    exchange.getResponseBody().flush();
                    responseBodyStarted.countDown();
                }
                if (delayResponse) {
                    try {
                        if (!releaseResponse.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test response was not released");
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (headersBeforeDelay) {
                    exchange.getResponseBody().write(body,1,body.length-1);
                } else {
                    exchange.sendResponseHeaders(status,body.length);
                    exchange.getResponseBody().write(body);
                }
            }
        });
        peer.start();
    }

    @AfterEach
    void closeLocalHttpResources() {
        releaseResponse.countDown();
        if (peer != null && !peerStopped) peer.stop(0);
        if (workers != null) workers.close();
    }

    private Map<String,Object> environment() {
        var env = new HashMap<String,Object>();
        env.put("MENU_SERVICE_URL",baseUrl);
        env.put("REST_CLIENT_CONNECT_TIMEOUT_MS","2000");
        env.put("REST_CLIENT_READ_TIMEOUT_MS","3000");
        return env;
    }

    private ApplicationContextRunner runner(Map<String,Object> env) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
                .withUserConfiguration(RestClientConfiguration.class)
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.addFirst(new SystemEnvironmentPropertySource("test-http-environment",env));
                    try {
                        sources.addLast(new ResourcePropertySource("classpath:application.properties"));
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                });
    }

    private RestClient client(ApplicationContext context) {
        return context.getBean(RestClient.Builder.class).clone()
                .baseUrl(context.getEnvironment().getRequiredProperty("MENU_SERVICE_URL")).build();
    }

    private Received onlyRequest() {
        assertEquals(1,received.size(),"A request must not be automatically repeated");
        return received.element();
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current=error;current!=null;current=current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    @Test
    void environmentBaseUrlAndTimeoutsApplyToBootBuilderAndRealGet() {
        runner(environment()).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
            assertNotSame(context.getBean(RestClient.Builder.class),context.getBean(RestClient.Builder.class));
            assertEquals(Duration.ofMillis(2000),context.getBean("serviceHttpClient",HttpClient.class).connectTimeout().orElseThrow());
            assertEquals(3000,context.getBean(RestClientProperties.class).readTimeoutMs());
            var response = client(context).get().uri("/technical/check").retrieve().toEntity(Reply.class);
            assertEquals(200,response.getStatusCode().value());
            assertEquals(new Reply("ok"),response.getBody());
            assertEquals("test-only",response.getHeaders().getFirst("X-Peer"));
            var request = onlyRequest();
            assertEquals("GET",request.method());
            assertEquals("/technical/check",request.path());
            assertNull(request.header("Authorization"),"No authentication token may be invented by infrastructure");
        });
    }

    @Test
    void jsonPostUsesRealMessageConversionAndPreservesPayload() {
        runner(environment()).run(context -> {
            var payload = new Payload("Türkçe çay",2);
            assertEquals(new Reply("ok"),client(context).post().uri("/technical/echo")
                    .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(Reply.class));
            var request = onlyRequest();
            assertEquals("POST",request.method());
            assertEquals(payload,JsonMapper.builder().build().readValue(request.body(),Payload.class));
            assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.header("Content-Type"))));
        });
    }

    @Test
    void queryParametersAndCustomHeadersReachTheDownstream() {
        runner(environment()).run(context -> {
            client(context).get().uri(builder -> builder.path("/technical/query")
                    .queryParam("page",1).queryParam("size",10).queryParam("tag","a","b").build())
                    .header("X-Correlation-Id","test-correlation").retrieve().body(Reply.class);
            var request = onlyRequest();
            assertEquals("page=1&size=10&tag=a&tag=b",request.query());
            assertEquals("test-correlation",request.header("X-Correlation-Id"));
        });
    }

    @ParameterizedTest
    @ValueSource(ints={400,401,403,404,422,500,503})
    void defaultHttpErrorsPreserveStatusAndBodyAndDoNotRetryPost(int responseStatus) {
        status = responseStatus;
        responseBody = "{\"error\":\"test-only\"}";
        runner(environment()).run(context -> {
            var error = assertThrows(RestClientResponseException.class,() -> client(context).post()
                    .uri("/technical/error").contentType(MediaType.APPLICATION_JSON)
                    .body(new Payload("test",1)).retrieve().body(Reply.class));
            assertEquals(responseStatus,error.getStatusCode().value());
            assertEquals(responseBody,error.getResponseBodyAsString());
            assertTrue(responseStatus<500 ? error instanceof HttpClientErrorException : error instanceof HttpServerErrorException);
            assertEquals("POST",onlyRequest().method());
        });
    }

    @Test
    void refusedConnectionProducesResourceAccessException() {
        peer.stop(0);
        peerStopped = true;
        runner(environment()).run(context -> {
            var response = client(context).get().uri("/technical/check").retrieve();
            var error = assertThrows(ResourceAccessException.class, () -> response.body(Reply.class));
            assertTrue(hasCause(error,ConnectException.class));
            assertTrue(received.isEmpty());
        });
    }

    @Test
    void heldResponseTriggersConfiguredReadTimeoutWithoutRepeatingPost() {
        delayResponse = true;
        var env = environment();
        env.put("REST_CLIENT_READ_TIMEOUT_MS","500");
        runner(env).run(context -> {
            var restClient = client(context);
            var pending = workers.submit(() -> restClient.post().uri("/technical/slow")
                    .contentType(MediaType.APPLICATION_JSON).body(new Payload("slow",1)).retrieve().body(Reply.class));
            try {
                assertTrue(requestArrived.await(5,TimeUnit.SECONDS),"Peer must receive the request before the response is held");
                var failure = assertThrows(ExecutionException.class,() -> pending.get(8,TimeUnit.SECONDS));
                assertInstanceOf(ResourceAccessException.class,failure.getCause());
                assertTrue(hasCause(failure,HttpTimeoutException.class)||hasCause(failure,SocketTimeoutException.class));
                assertFalse(hasCause(failure,HttpConnectTimeoutException.class));
                assertEquals("POST",onlyRequest().method());
            } finally {
                releaseResponse.countDown();
            }
        });
    }

    @Test
    void configuredTimeoutAlsoClosesAStalledResponseBodyAfterHeaders() {
        delayResponse = true;
        headersBeforeDelay = true;
        var env = environment();
        env.put("REST_CLIENT_READ_TIMEOUT_MS","500");
        runner(env).run(context -> {
            var restClient = client(context);
            var pending = workers.submit(() -> restClient.get().uri("/technical/slow-body").retrieve().body(Reply.class));
            try {
                assertTrue(responseBodyStarted.await(5,TimeUnit.SECONDS),"Peer must flush headers and the first response byte");
                var failure = assertThrows(ExecutionException.class,() -> pending.get(8,TimeUnit.SECONDS));
                assertInstanceOf(RestClientException.class,failure.getCause());
                assertTrue(hasCause(failure,IOException.class));
                onlyRequest();
            } finally {
                releaseResponse.countDown();
            }
        });
    }

    @Test
    void stalledLocalTlsHandshakeTriggersConnectTimeout() throws Exception {
        try (var listener = new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            var accepted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            workers.submit(() -> {
                try (var _ = listener.accept()) {
                    accepted.countDown();
                    if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("TLS test peer was not released");
                }
                return null;
            });
            var env = environment();
            env.put("MENU_SERVICE_URL","https://127.0.0.1:"+listener.getLocalPort());
            env.put("REST_CLIENT_CONNECT_TIMEOUT_MS","500");
            env.put("REST_CLIENT_READ_TIMEOUT_MS","5000");
            try {
                runner(env).run(context -> {
                    var restClient = client(context);
                    var pending = workers.submit(() -> restClient.get().uri("/technical/connect").retrieve().body(Reply.class));
                    try {
                        assertTrue(accepted.await(5,TimeUnit.SECONDS));
                        var failure = assertThrows(ExecutionException.class,() -> pending.get(8,TimeUnit.SECONDS));
                        assertInstanceOf(ResourceAccessException.class,failure.getCause());
                        assertTrue(hasCause(failure,HttpConnectTimeoutException.class),
                                "Connect phase must time out, not the read phase: "+failure.getCause());
                    } finally {
                        release.countDown();
                    }
                });
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void malformedJsonResponseIsNotSilentlyAccepted() {
        responseBody = "{not valid JSON";
        runner(environment()).run(context -> {
            var response = client(context).get().uri("/technical/bad-json").retrieve();
            var error = assertThrows(RestClientException.class, () -> response.body(Reply.class));
            assertTrue(hasCause(error,HttpMessageNotReadableException.class));
            onlyRequest();
        });
    }

    @Test
    void unexpectedResponseContentTypeIsRejected() {
        contentType = "text/plain";
        responseBody = "not a response DTO";
        runner(environment()).run(context -> {
            var response = client(context).get().uri("/technical/wrong-type").retrieve();
            assertThrows(UnknownContentTypeException.class, () -> response.body(Reply.class));
            onlyRequest();
        });
    }

    @ParameterizedTest
    @CsvSource({"REST_CLIENT_CONNECT_TIMEOUT_MS,0","REST_CLIENT_CONNECT_TIMEOUT_MS,-1",
            "REST_CLIENT_CONNECT_TIMEOUT_MS,invalid","REST_CLIENT_CONNECT_TIMEOUT_MS,2147483648",
            "REST_CLIENT_CONNECT_TIMEOUT_MS,''",
            "REST_CLIENT_READ_TIMEOUT_MS,0","REST_CLIENT_READ_TIMEOUT_MS,-1",
            "REST_CLIENT_READ_TIMEOUT_MS,invalid","REST_CLIENT_READ_TIMEOUT_MS,2147483648","REST_CLIENT_READ_TIMEOUT_MS,''"})
    void invalidEnvironmentTimeoutsPreventStartup(String key,String value) {
        var env = environment();
        env.put(key,value);
        runner(env).run(context -> assertNotNull(context.getStartupFailure()));
        assertTrue(received.isEmpty());
    }

    @Test
    void propertyDefaultsAreFiniteAndHttpClientClosesWithSpringContext() {
        var env = environment();
        env.remove("REST_CLIENT_CONNECT_TIMEOUT_MS");
        env.remove("REST_CLIENT_READ_TIMEOUT_MS");
        var owned = new AtomicReference<HttpClient>();
        runner(env).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(new RestClientProperties(3000,5000),context.getBean(RestClientProperties.class));
            owned.set(context.getBean("serviceHttpClient",HttpClient.class));
            client(context).get().uri("/technical/lifecycle").retrieve().body(Reply.class);
            onlyRequest();
        });
        assertTrue(owned.get().isTerminated(),"Spring must close the owned HTTP client");
    }
}
