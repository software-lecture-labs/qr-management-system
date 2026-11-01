package com.restofy.qr.infrastructure.config;

import com.restofy.qr.domain.exception.QrImageUnavailableException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class QrEntryUrl {
    private static final int MAX_ENTRY_URL_LENGTH = 512;
    private final String entryUrl;

    public QrEntryUrl(@Value("${qr.public-entry-url:}") String configured, Environment environment) {
        if (configured.isEmpty()) {
            entryUrl = null;
            return;
        }
        try {
            URI uri = URI.create(configured);
            boolean localHttp = "http".equals(uri.getScheme())
                    && environment.acceptsProfiles(Profiles.of("dev", "test"))
                    && !environment.acceptsProfiles(Profiles.of("prod"))
                    && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            if (configured.length() > MAX_ENTRY_URL_LENGTH || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (!"https".equals(uri.getScheme()) && !localHttp)
                    || uri.getPort() > 65535 || uri.getPort() == 0) {
                throw new IllegalArgumentException();
            }
            entryUrl = uri.toASCIIString();
            if (entryUrl.length() > MAX_ENTRY_URL_LENGTH) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException _) {
            throw new IllegalStateException("QR public entry URL must be a bounded HTTPS URL without credentials, query or fragment; "
                    + "loopback HTTP is allowed only in dev/test.");
        }
    }

    public String forToken(String token) {
        if (entryUrl == null) {
            throw new QrImageUnavailableException();
        }
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid QR credential format.");
        }
        // The future customer page reads the fragment; it is not sent in HTTP requests.
        return entryUrl + "#token=" + token;
    }
}
