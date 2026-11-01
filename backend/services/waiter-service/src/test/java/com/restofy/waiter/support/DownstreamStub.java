package com.restofy.waiter.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Real local HTTP peer that plays the QR and Order services in tests. It is a test double of the DRAFT
 * contract only: a passing test proves the Waiter adapter against this stub, never against the real services.
 */
public final class DownstreamStub implements AutoCloseable {

    public record Request(String method, String path, String rawQuery, String body, Map<String, List<String>> headers) {

        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null ? null : values.getFirst();
        }

        public Map<String, String> query() {
            Map<String, String> values = new HashMap<>();
            if (rawQuery == null || rawQuery.isEmpty()) return values;
            for (String pair : rawQuery.split("&")) {
                String[] parts = pair.split("=", 2);
                values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
            }
            return values;
        }
    }

    public record Response(int status, String contentType, String body, long delayMillis) {

        public static Response json(int status, String body) {
            return new Response(status, "application/json", body, 0);
        }

        public Response delayed(long millis) {
            return new Response(status, contentType, body, millis);
        }
    }

    private record Route(String method, Pattern path, Function<Request, Response> handler) {
    }

    private final HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<Route> routes = new CopyOnWriteArrayList<>();

    private DownstreamStub(HttpServer server) {
        this.server = server;
    }

    public static DownstreamStub start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            DownstreamStub stub = new DownstreamStub(http);
            http.setExecutor(stub.workers);
            http.createContext("/", stub::handle);
            http.start();
            return stub;
        } catch (IOException error) {
            throw new IllegalStateException("Could not start the local test peer", error);
        }
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Registers a route; the first matching route wins and unmatched requests answer 404. */
    public void route(String method, String pathRegex, Function<Request, Response> handler) {
        routes.add(new Route(method, Pattern.compile(pathRegex), handler));
    }

    /** Registers a route that takes precedence over earlier ones, to simulate a misbehaving downstream. */
    public void override(String method, String pathRegex, Function<Request, Response> handler) {
        routes.add(0, new Route(method, Pattern.compile(pathRegex), handler));
    }

    public void reset() {
        requests.clear();
        routes.clear();
    }

    public List<Request> requests() {
        return new ArrayList<>(requests);
    }

    public List<Request> requests(String method, String pathRegex) {
        Pattern pattern = Pattern.compile(pathRegex);
        return requests.stream().filter(r -> r.method().equals(method) && pattern.matcher(r.path()).matches()).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Map<String, List<String>> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), headers);
            requests.add(request);
            Response response = routes.stream()
                    .filter(route -> route.method().equals(request.method()) && route.path().matcher(request.path()).matches())
                    .findFirst().map(route -> route.handler().apply(request))
                    .orElse(Response.json(404, "{\"error\":\"unrouted\"}"));
            if (response.delayMillis() > 0) {
                try {
                    Thread.sleep(response.delayMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
        } catch (IOException ignoredAfterClientTimeout) {
            // The Waiter side may already have given up (timeout tests); nothing to report.
        }
    }

    @Override
    public void close() {
        server.stop(0);
        workers.close();
    }
}
