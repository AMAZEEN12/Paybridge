package com.academy.paybridge.compliance.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * The corridor rule table under paybridge.corridors in application.yml. Order matters: the first matching rule wins.
 * No rules at all means every cross-border corridor is refused.
 *
 * In YAML, quote the codes ("NG", "*"). An unquoted NO (Norway) is read as the boolean false.
 */
@ConfigurationProperties(prefix = "paybridge.corridors")
public record CorridorProperties(List<Rule> rules) {

    public CorridorProperties {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** from and to are two-letter country codes, or "*" for any. A rule with no enabled value is off. */
    public record Rule(String from, String to, boolean enabled) {

        public Rule {
            from = from == null ? "" : from.trim().toUpperCase(Locale.ROOT);
            to = to == null ? "" : to.trim().toUpperCase(Locale.ROOT);
        }
    }
}