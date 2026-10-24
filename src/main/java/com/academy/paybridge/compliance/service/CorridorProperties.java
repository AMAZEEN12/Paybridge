package com.academy.paybridge.compliance.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.List;
import java.util.Locale;

@ConfigurationProperties(prefix = "paybridge.corridors")
public record CorridorProperties(List<Rule> rules) {

    public CorridorProperties {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public record Rule(String from, String to, List<String> sourceCurrencies, boolean enabled) {

        @ConstructorBinding
        public Rule {
            from = from == null ? "" : from.trim().toUpperCase(Locale.ROOT);
            to = to == null ? "" : to.trim().toUpperCase(Locale.ROOT);
            sourceCurrencies = sourceCurrencies == null ? List.of()
                    : sourceCurrencies.stream().map(c -> c.trim().toUpperCase(Locale.ROOT)).toList();
        }

        /** A rule that covers every source currency. */
        public Rule(String from, String to, boolean enabled) {
            this(from, to, List.of(), enabled);
        }

        public boolean coversCurrency(String currency) {
            return sourceCurrencies.isEmpty()
                    || (currency != null && sourceCurrencies.contains(currency.trim().toUpperCase(Locale.ROOT)));
        }
    }
}