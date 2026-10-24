package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.CorridorResult;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class CorridorRules {

    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    private CorridorRules() {
    }

    public static CorridorResult evaluate(List<CorridorProperties.Rule> rules, String senderCountry,
                                          String destinationCountry) {
        return evaluate(rules, senderCountry, destinationCountry, null);
    }

    public static CorridorResult evaluate(List<CorridorProperties.Rule> rules, String senderCountry,
                                          String destinationCountry, String sourceCurrency) {
        String from = normalise(senderCountry);
        String to = normalise(destinationCountry);

        if (from == null || to == null) {
            return CorridorResult.refuse("The sender or destination country is not known.");
        }
        if (from.equals(to)) {
            return CorridorResult.allow("Domestic: " + from + " to " + to + ".");
        }

        for (CorridorProperties.Rule rule : rules) {
            if (matches(rule.from(), from) && matches(rule.to(), to)
                    && rule.coversCurrency(sourceCurrency)) {                       // first match wins
                String pair = rule.from() + " to " + rule.to()
                        + (rule.sourceCurrencies().isEmpty() ? "" : " from " + String.join("/", rule.sourceCurrencies()));
                return rule.enabled()
                        ? CorridorResult.allow("Allowed by rule " + pair + ".")
                        : CorridorResult.refuse("Switched off by rule " + pair + ".");
            }
        }
        return CorridorResult.refuse("No corridor rule for " + from + " to " + to + ".");
    }

    private static boolean matches(String ruleCountry, String country) {
        return "*".equals(ruleCountry) || ruleCountry.equals(country);
    }

    private static String normalise(String country) {
        if (country == null) {
            return null;
        }
        String c = country.trim().toUpperCase(Locale.ROOT);
        return COUNTRY.matcher(c).matches() ? c : null;
    }
}