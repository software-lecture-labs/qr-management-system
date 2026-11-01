package com.restofy.order.infrastructure.client;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.MenuCatalogPort;
import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.application.port.ServiceIdentityPort;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.client.mock.InMemoryAuthContextAdapter;
import com.restofy.order.infrastructure.client.mock.InMemoryMenuCatalogAdapter;
import com.restofy.order.infrastructure.client.mock.InMemoryQrSessionAdapter;
import com.restofy.order.infrastructure.client.mock.InMemoryServiceIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Production safety: the MOCK QR/Menu adapters may only exist in the dev and test profiles. In every other profile
 * (including no profile at all) the fail-closed stand-ins are wired and nothing can be validated.
 */
class IntegrationProfileWiringTests {

    /**
     * The Maven build sets {@code spring.profiles.active=test} as a JVM property, so the environment here is a
     * {@link MockEnvironment} that ignores system properties and only knows the profiles given explicitly.
     */
    private ApplicationContextRunner contextFor(String... profiles) {
        return new ApplicationContextRunner()
                .withUserConfiguration(InMemoryQrSessionAdapter.class, InMemoryMenuCatalogAdapter.class,
                        UnavailableQrSessionAdapter.class, UnavailableMenuCatalogAdapter.class,
                        InMemoryAuthContextAdapter.class, UnavailableAuthContextAdapter.class,
                        InMemoryServiceIdentityAdapter.class, UnavailableServiceIdentityAdapter.class)
                .withInitializer(context -> {
                    MockEnvironment environment = new MockEnvironment();
                    if (profiles.length > 0) {
                        environment.setActiveProfiles(profiles);
                    }
                    context.setEnvironment(environment);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "staging", "qa"})
    void nonDevProfilesGetFailClosedAdaptersOnly(String profile) {
        contextFor(profile).run(context -> {
            assertEquals(1, context.getBeansOfType(QrSessionPort.class).size());
            assertEquals(1, context.getBeansOfType(MenuCatalogPort.class).size());
            assertInstanceOf(UnavailableQrSessionAdapter.class, context.getBean(QrSessionPort.class));
            assertInstanceOf(UnavailableMenuCatalogAdapter.class, context.getBean(MenuCatalogPort.class));
            assertEquals(1, context.getBeansOfType(AuthContextPort.class).size());
            assertInstanceOf(UnavailableAuthContextAdapter.class, context.getBean(AuthContextPort.class));
            assertEquals(0, context.getBeansOfType(InMemoryAuthContextAdapter.class).size(),
                    "the MOCK staff authentication must not exist outside dev/test");
            assertEquals(1, context.getBeansOfType(ServiceIdentityPort.class).size());
            assertInstanceOf(UnavailableServiceIdentityAdapter.class, context.getBean(ServiceIdentityPort.class));
            assertEquals(0, context.getBeansOfType(InMemoryServiceIdentityAdapter.class).size(),
                    "the MOCK service identity must not exist outside dev/test");
            assertEquals(0, context.getBeansOfType(InMemoryQrSessionAdapter.class).size());
            assertEquals(0, context.getBeansOfType(InMemoryMenuCatalogAdapter.class).size());
        });
    }

    @Test
    void noProfileAtAllAlsoGetsFailClosedAdapters() {
        contextFor().run(context -> {
            assertInstanceOf(UnavailableQrSessionAdapter.class, context.getBean(QrSessionPort.class));
            assertInstanceOf(UnavailableMenuCatalogAdapter.class, context.getBean(MenuCatalogPort.class));
        });
    }

    @Test
    void failClosedAdaptersNeverValidateAnythingAndReportUnavailable() {
        contextFor("prod").run(context -> {
            QrSessionPort qrPort = context.getBean(QrSessionPort.class);
            AuthContextPort staffPort = context.getBean(AuthContextPort.class);
            MenuCatalogPort menuPort = context.getBean(MenuCatalogPort.class);
            UUID restaurantId = UUID.randomUUID();
            Set<UUID> productIds = Set.of(UUID.randomUUID());
            BusinessException qr = assertThrows(BusinessException.class,
                    () -> qrPort.validateGuestSession("dev-mock-guest-token-a1"));
            assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, qr.code());
            BusinessException staff = assertThrows(BusinessException.class,
                    () -> staffPort.resolveStaff("dev-mock-staff-admin-a"));
            assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, staff.code());
            ServiceIdentityPort servicePort = context.getBean(ServiceIdentityPort.class);
            BusinessException service = assertThrows(BusinessException.class,
                    () -> servicePort.resolveService(InMemoryServiceIdentityAdapter.KITCHEN_CREDENTIAL));
            assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, service.code());
            BusinessException menu = assertThrows(BusinessException.class,
                    () -> menuPort.findProducts(restaurantId, productIds));
            assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, menu.code());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void devAndTestProfilesGetTheMockAdapters(String profile) {
        contextFor(profile).run(context -> {
            assertInstanceOf(InMemoryQrSessionAdapter.class, context.getBean(QrSessionPort.class));
            assertInstanceOf(InMemoryMenuCatalogAdapter.class, context.getBean(MenuCatalogPort.class));
            assertInstanceOf(InMemoryAuthContextAdapter.class, context.getBean(AuthContextPort.class));
            assertInstanceOf(InMemoryServiceIdentityAdapter.class, context.getBean(ServiceIdentityPort.class));
            assertEquals(0, context.getBeansOfType(UnavailableServiceIdentityAdapter.class).size());
            assertEquals(0, context.getBeansOfType(UnavailableAuthContextAdapter.class).size());
            assertEquals(0, context.getBeansOfType(UnavailableQrSessionAdapter.class).size());
        });
    }
}
