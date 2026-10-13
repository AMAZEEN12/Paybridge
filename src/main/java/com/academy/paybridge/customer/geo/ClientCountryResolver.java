package com.academy.paybridge.customer.geo;

import com.academy.paybridge.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides the country of a sign-up request (R-CTY-1, R-CTY-4, R-CTY-5). Plain Java, no Spring and no servlet types,
 * so it is easy to test.
 *
 * Rules:
 *  - The country override is for tests and local development. It only works when allowOverride is true, and the
 *    application refuses to start if an override is set while allowOverride is false (so it cannot be switched on
 *    in production by accident).
 *  - Behind a proxy (Render), the client address comes from one configured header (default True-Client-IP). When that
 *    header is configured and missing, the TCP peer address is NOT used: behind a proxy it is the proxy's own address,
 *    and a wrong country is worse than a refused sign-up.
 *  - The value must be one public IP address. Lists, private addresses and anything that is not an IP are refused
 *    without calling the geo service. Nothing is ever resolved through DNS.
 */
public class ClientCountryResolver {

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private final GeoLocator geo;
    private final String overrideCountry;
    private final String clientIpHeader;

    public ClientCountryResolver(GeoLocator geo, String overrideCountry, boolean allowOverride, String clientIpHeader) {
        this.geo = geo;
        this.overrideCountry = overrideCountry == null ? "" : overrideCountry.trim().toUpperCase(Locale.ROOT);
        this.clientIpHeader = clientIpHeader == null ? "" : clientIpHeader.trim();
        if (!this.overrideCountry.isEmpty()) {
            if (!allowOverride) {
                throw new IllegalStateException("paybridge.geo.override-country is set but paybridge.geo.allow-override "
                        + "is false. The country override is for tests and local development only. Remove it from "
                        + "this environment.");
            }
            if (!this.overrideCountry.matches("[A-Z]{2}")) {
                throw new IllegalStateException("paybridge.geo.override-country must be a two-letter country code.");
            }
        }
    }

    /** The request header that holds the client address, or empty if the TCP peer address should be used. */
    public String clientIpHeader() {
        return clientIpHeader;
    }

    /**
     * @param headerValue   the value of {@link #clientIpHeader()} on the request, or null if it was absent
     * @param remoteAddress the TCP peer address of the request
     * @return the ISO country code
     * @throws ApiException 503 COUNTRY_UNDETECTED when the country cannot be worked out
     */
    public String countryOf(String headerValue, String remoteAddress) {
        if (!overrideCountry.isEmpty()) {
            return overrideCountry;
        }
        InetAddress ip = parsePublicAddress(clientIpHeader.isEmpty() ? remoteAddress : headerValue);
        if (ip == null) {
            throw undetected();
        }
        return geo.countryOf(ip.getHostAddress()).orElseThrow(ClientCountryResolver::undetected);
    }

    static ApiException undetected() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "COUNTRY_UNDETECTED",
                "We could not work out your country right now, so we could not create your account. Please try again.");
    }

    /** One public IP address, or null. Never does a DNS lookup. */
    static InetAddress parsePublicAddress(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 45) {
            return null;
        }
        try {
            InetAddress address;
            Matcher v4 = IPV4.matcher(value);
            if (v4.matches()) {
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(v4.group(i + 1));
                    if (octet > 255) {
                        return null;
                    }
                    bytes[i] = (byte) octet;
                }
                address = InetAddress.getByAddress(bytes);
            } else if (value.indexOf(':') >= 0 && IPV6.matcher(value).matches()) {
                address = InetAddress.getByName(value);       // contains ':', so it is parsed as a literal, never looked up
            } else {
                return null;
            }
            return isPublic(address) ? address : null;
        } catch (UnknownHostException | NumberFormatException e) {
            return null;
        }
    }

    private static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            if (first == 0 || first >= 240) {
                return false;                                  // 0.0.0.0/8 and the reserved 240.0.0.0/4
            }
            return !(first == 100 && second >= 64 && second <= 127);   // carrier-grade NAT 100.64.0.0/10
        }
        return (b[0] & 0xFE) != 0xFC;                          // IPv6 unique-local fc00::/7
    }
}