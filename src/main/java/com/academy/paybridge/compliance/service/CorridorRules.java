package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.CorridorResult;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure matching for the corridor table: rules and two countries in, a decision out. No Spring, no database. */
public final class CorridorRules {

    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    private CorridorRules() {
    }

    public static CorridorResult evaluate(List<CorridorProperties.Rule> rules, String senderCountry,
                                          String destinationCountry) {
        String from = normalise(senderCountry);
        String to = normalise(destinationCountry);

        if (from == null || to == null) {
            return CorridorResult.refuse("The sender or destination country is not known.");
        }
        // A payout inside one country is not a corridor: the table decides cross-border pairs only (R-COR-3).
        if (from.equals(to)) {
            return CorridorResult.allow("Domestic: " + from + " to " + to + ".");
        }

        for (CorridorProperties.Rule rule : rules) {
            if (matches(rule.from(), from) && matches(rule.to(), to)) {       // first match wins (R-COR-1)
                String pair = rule.from() + " to " + rule.to();
                return rule.enabled()
                        ? CorridorResult.allow("Allowed by rule " + pair + ".")
                        : CorridorResult.refuse("Switched off by rule " + pair + ".");
            }
        }
        return CorridorResult.refuse("No corridor rule for " + from + " to " + to + ".");   // no rule: refused
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