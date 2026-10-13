package com.academy.paybridge.customer.geo;

import java.util.Optional;

/** Works out which country an IP address is in. The only place PayBridge talks to a geo-IP service. */
public interface GeoLocator {
    Optional<String> countryOf(String ip);
}