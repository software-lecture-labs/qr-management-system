package com.restofy.order.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The OpenAPI document (enabled only for this test) is the machine-readable API contract of the service. */
@SpringBootTest(properties = {"springdoc.api-docs.enabled=true"})
@AutoConfigureMockMvc
class OpenApiDocumentationTests {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper json;

    private JsonNode document() throws Exception {
        return json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    @Test
    void documentsEveryImplementedEndpointWithItsSecurityScheme() throws Exception {
        JsonNode paths = document().path("paths");
        assertTrue(paths.has("/api/v1/carts/current"));
        assertTrue(paths.has("/api/v1/carts/current/items"));
        assertTrue(paths.has("/api/v1/carts/current/items/{cartItemId}"));
        assertTrue(paths.has("/api/v1/orders"));
        // GET current; POST + DELETE items; PATCH + DELETE item
        assertEquals(5, paths.path("/api/v1/carts/current").size() + paths.path("/api/v1/carts/current/items").size()
                + paths.path("/api/v1/carts/current/items/{cartItemId}").size());

        JsonNode scheme = document().path("components").path("securitySchemes").path("guestSession");
        assertEquals("http", scheme.path("type").asString());
        assertEquals("bearer", scheme.path("scheme").asString());
    }

    @Test
    void orderPlacementDocumentsTheIdempotencyKeyAndTheErrorResponses() throws Exception {
        JsonNode post = document().path("paths").path("/api/v1/orders").path("post");
        boolean keyIsRequiredHeader = false;
        for (JsonNode parameter : post.path("parameters")) {
            if ("Idempotency-Key".equals(parameter.path("name").asString())) {
                keyIsRequiredHeader = "header".equals(parameter.path("in").asString())
                        && parameter.path("required").asBoolean();
            }
        }
        assertTrue(keyIsRequiredHeader, "Idempotency-Key must be documented as a required header");
        for (String code : new String[]{"201", "400", "401", "409", "422", "503"}) {
            assertTrue(post.path("responses").has(code), "response " + code + " must be documented");
        }
        assertTrue(post.path("security").toString().contains("guestSession"));
    }

    @Test
    void theDocumentListsOrderReadingAndNoPathsThatDoNotExistYet() throws Exception {
        JsonNode paths = document().path("paths");
        assertTrue(paths.has("/api/v1/orders/{orderId}"));
        assertTrue(paths.has("/api/v1/orders/{orderId}/history"));
        JsonNode list = paths.path("/api/v1/orders").path("get");
        assertTrue(list.has("operationId"), "the order list must be documented");
        assertTrue(list.path("security").toString().contains("staff")
                && list.path("security").toString().contains("guestSession"));
        assertTrue(list.path("parameters").toString().contains("tableSessionId"));
        assertTrue(paths.path("/api/v1/orders/{orderId}").path("get").path("security").toString().contains("staff"));
        assertTrue(paths.has("/api/v1/orders/{orderId}/fulfillment-status"));
        assertTrue(paths.path("/api/v1/orders/{orderId}/fulfillment-status").path("patch").path("security").toString()
                .contains("staff"), paths.path("/api/v1/orders/{orderId}/fulfillment-status").toString());
        assertTrue(paths.path("/api/v1/internal/order-events/kitchen").path("post").path("security").toString()
                .contains("service"));
        assertTrue(paths.path("/api/v1/internal/order-events/payment").path("post").path("security").toString()
                .contains("service"));
        assertTrue(!paths.has("/api/v1/orders/{orderId}/payment-status"), "no client can set a payment status");
    }

    @Test
    void theCancellationEndpointIsDocumentedWithItsSecurityAndIdempotencyKey() throws Exception {
        JsonNode post = document().path("paths").path("/api/v1/orders/{orderId}/cancellations").path("post");
        assertTrue(post.has("operationId"), "cancelling an order must be documented");
        String security = post.path("security").toString();
        assertTrue(security.contains("guestSession") && security.contains("staff"), security);
        assertTrue(!security.contains("service"), "no service credential cancels an order here");
        assertTrue(post.path("parameters").toString().contains("Idempotency-Key"));
        assertTrue(post.path("responses").has("201") && post.path("responses").has("409")
                && post.path("responses").has("422"));
        JsonNode request = document().path("components").path("schemas").path("CancelOrderRequest").path("properties");
        for (String field : new String[]{"cancelAll", "items", "reasonCodes", "reasonText"}) {
            assertTrue(request.has(field), field);
        }
        assertTrue(!request.has("restaurantId") && !request.has("amountMinor") && !request.has("refundedMinor"),
                "prices, tenant and refunds are never accepted from the client");
    }

    @Test
    void theKitchensOwnRequestAndItsAnswerAreSeparateServiceOnlyEndpoints() throws Exception {
        JsonNode paths = document().path("paths");
        JsonNode request = paths.path("/api/v1/internal/order-events/kitchen-cancellation-request").path("post");
        JsonNode answer = paths.path("/api/v1/internal/order-events/kitchen-cancellation").path("post");
        assertTrue(request.has("operationId") && answer.has("operationId"));
        Assertions.assertNotEquals(request.path("operationId").asString(),
                answer.path("operationId").asString());
        for (JsonNode operation : new JsonNode[]{request, answer}) {
            String security = operation.path("security").toString();
            assertTrue(security.contains("service") && !security.contains("staff") && !security.contains("guestSession"),
                    security);
        }
        JsonNode schemas = document().path("components").path("schemas");
        JsonNode properties = schemas.path("KitchenCancellationRequest").path("properties");
        for (String field : new String[]{"eventId", "restaurantId", "orderId", "cancelAll", "items", "reasonCodes",
                "reasonText", "requestedByStaffId"}) {
            assertTrue(properties.has(field), field);
        }
        assertTrue(!properties.has("amountMinor") && !properties.has("role") && !properties.has("cancellationId"),
                "the request cannot name a role, an amount or a cancellation");
        assertTrue(schemas.path("KitchenCancellationRequest").path("required").toString().contains("reasonCodes"),
                "the reason is required");
    }

    @Test
    void theCancellationReportIsDocumentedAsStaffOnlyWithItsFilters() throws Exception {
        JsonNode get = document().path("paths").path("/api/v1/admin/order-cancellations").path("get");
        assertTrue(get.has("operationId"), "the report must be documented");
        String security = get.path("security").toString();
        assertTrue(security.contains("staff") && !security.contains("guestSession") && !security.contains("service"),
                security);
        String parameters = get.path("parameters").toString();
        for (String name : new String[]{"from", "to", "reasonCode", "scope", "status", "overdue", "sort", "page", "limit"}) {
            assertTrue(parameters.contains("\"" + name + "\""), name);
        }
        assertTrue(!parameters.contains("restaurantId"), "the tenant is never a request parameter");
    }

    @Test
    void theCancellationDecisionReasonAndListEndpointsAreDocumentedWithTheirCallers() throws Exception {
        JsonNode paths = document().path("paths");
        assertTrue(paths.path("/api/v1/internal/order-events/kitchen-cancellation").path("post").path("security")
                .toString().contains("service"));
        JsonNode reason = paths.path("/api/v1/orders/{orderId}/cancellations/{cancellationId}/reason").path("patch");
        assertTrue(reason.path("security").toString().contains("staff")
                && !reason.path("security").toString().contains("guestSession"), reason.toString());
        JsonNode list = paths.path("/api/v1/orders/{orderId}/cancellations").path("get");
        assertTrue(list.path("security").toString().contains("guestSession")
                && list.path("security").toString().contains("staff"));
        JsonNode decision = document().path("components").path("schemas").path("KitchenCancellationDecisionRequest")
                .path("properties");
        assertTrue(decision.has("cancellationId") && decision.has("decision") && decision.has("rejectionCode"));
        assertTrue(!decision.has("amountMinor") && !decision.has("refundedMinor"));
    }
}
