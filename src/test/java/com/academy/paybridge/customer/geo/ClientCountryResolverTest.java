package com.academy.paybridge.customer.geo;

import com.academy.paybridge.shared.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientCountryResolverTest {

    private final List<String> asked = new ArrayList<>();
    private final GeoLocator geo = ip -> {
        asked.add(ip);
        return (ip.equals("8.8.8.8") || ip.startsWith("2001:4860")) ? Optional.of("US") : Optional.empty();
    };
    private final ClientCountryResolver behindProxy = new ClientCountryResolver(geo, "", false, "True-Client-IP");

    private void assertUndetected(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo("COUNTRY_UNDETECTED");
            assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        });
    }

    @Test
    void aPublicIpFromTheConfiguredHeaderIsLookedUp() {
        assertThat(behindProxy.countryOf("8.8.8.8", "10.0.0.1")).isEqualTo("US");
        assertThat(behindProxy.countryOf("2001:4860:4860::8888", "10.0.0.1")).isEqualTo("US");
    }

    @Test
    void badOrNonPublicAddressesAreRefusedWithoutCallingTheGeoService() {
        String[] bad = {"", "10.1.2.3", "192.168.0.9", "127.0.0.1", "172.16.0.1", "100.64.1.1", "0.0.0.0",
                "999.1.1.1", "8.8.8.8, 1.1.1.1", "bad.host.name", "example.com", "::1", "fd00::1", "fe80::1",
                "::ffff:10.0.0.1"};
        for (String value : bad) {
            assertUndetected(() -> behindProxy.countryOf(value, "8.8.8.8"));
        }
        assertUndetected(() -> behindProxy.countryOf(null, "8.8.8.8"));
        assertThat(asked).isEmpty();
    }

    @Test
    void aMissingHeaderDoesNotFallBackToThePeerAddress() {
        assertUndetected(() -> behindProxy.countryOf(null, "8.8.8.8"));
        assertThat(asked).isEmpty();
    }

    @Test
    void withNoHeaderConfiguredThePeerAddressIsUsed() {
        ClientCountryResolver direct = new ClientCountryResolver(geo, "", false, "");
        assertThat(direct.countryOf(null, "8.8.8.8")).isEqualTo("US");
    }

    @Test
    void whenTheGeoServiceCannotAnswerSignUpIsRefused() {
        assertUndetected(() -> behindProxy.countryOf("1.2.3.4", "x"));
    }

    @Test
    void theOverrideIsRefusedAtStartUpUnlessExplicitlyAllowed() {
        assertThatThrownBy(() -> new ClientCountryResolver(geo, "NG", false, "True-Client-IP"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-override");
        assertThatThrownBy(() -> new ClientCountryResolver(geo, "NIG", true, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAllowedOverrideNeedsNoIpAddress() {
        ClientCountryResolver local = new ClientCountryResolver(geo, " ng ", true, "True-Client-IP");
        assertThat(local.countryOf(null, null)).isEqualTo("NG");
        assertThat(asked).isEmpty();
    }
}