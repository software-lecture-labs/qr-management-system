package com.restofy.waiter.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * Fixed downstream base addresses, mapped from the existing QR_SERVICE_URL, ORDER_SERVICE_URL and
 * NOTIFICATION_SERVICE_URL variables. They come only from configuration, never from request data, so a caller
 * cannot steer server-side requests.
 */
@ConfigurationProperties("restofy.services")
public record ServiceUrlProperties(URI qrUrl, URI orderUrl, URI notificationUrl) {

    public ServiceUrlProperties {
        qrUrl = requireHttpBaseUrl("qr-url", qrUrl);
        orderUrl = requireHttpBaseUrl("order-url", orderUrl);
        notificationUrl = requireHttpBaseUrl("notification-url", notificationUrl);
    }

    private static URI requireHttpBaseUrl(String name, URI value) {
        if (value == null) throw new IllegalArgumentException(name + " must be configured");
        String scheme = value.getScheme();
        boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!http || value.getHost() == null || value.getUserInfo() != null) {
            throw new IllegalArgumentException(name + " must be an absolute http(s) URL without credentials");
        }
        return value;
    }
}
