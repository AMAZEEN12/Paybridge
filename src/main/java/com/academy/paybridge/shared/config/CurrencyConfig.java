package com.academy.paybridge.shared.config;

import com.academy.paybridge.shared.money.CurrencyInfo;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

/**
 * Builds the CurrencyRegistry once, at start-up, from the currencies table (seeded by V10) and the exclusion list in
 * application.yml (paybridge.currencies.excluded, comma separated). Adding a currency is a new row, not new code.
 */
@Configuration
public class CurrencyConfig {

    @Bean
    public CurrencyRegistry currencyRegistry(JdbcTemplate jdbc,
                                             @Value("${paybridge.currencies.excluded:IRR,KPW,SYP,CUP}") String excluded) {
        List<CurrencyInfo> rows = jdbc.query(
                "select code, name, symbol, minor_units, enabled from currencies",
                (rs, n) -> new CurrencyInfo(rs.getString("code"), rs.getString("name"), rs.getString("symbol"),
                        rs.getInt("minor_units"), rs.getBoolean("enabled")));
        return new CurrencyRegistry(rows, Arrays.stream(excluded.split(",")).map(String::trim).toList());
    }
}