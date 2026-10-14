package com.academy.paybridge.fx.repository;

import com.academy.paybridge.fx.domain.RateSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Repository
public class JdbcRateSnapshotRepository implements RateSnapshotRepository {

    private final JdbcTemplate jdbc;

    public JdbcRateSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public RateSnapshot save(RateSnapshot s) {
        Long id = jdbc.queryForObject(
                "insert into fx_rate_snapshots (provider, base_currency, provider_timestamp, fetched_at) "
                        + "values (?, ?, ?, ?) returning id",
                Long.class, s.provider(), s.baseCurrency(), utc(s.providerTimestamp()), utc(s.fetchedAt()));
        List<Object[]> rows = new ArrayList<>();
        s.rates().forEach((code, rate) -> rows.add(new Object[]{id, code, rate}));
        jdbc.batchUpdate("insert into fx_snapshot_rates (snapshot_id, currency, rate) values (?, ?, ?)", rows);
        return s.withId(id);
    }

    @Override
    public Optional<RateSnapshot> findLatest() {
        List<RateSnapshot> heads = jdbc.query(
                "select id, provider, base_currency, provider_timestamp, fetched_at from fx_rate_snapshots "
                        + "order by provider_timestamp desc, id desc limit 1",
                (rs, n) -> new RateSnapshot(rs.getLong("id"), rs.getString("provider"), rs.getString("base_currency"),
                        rs.getObject("provider_timestamp", OffsetDateTime.class).toInstant(),
                        rs.getObject("fetched_at", OffsetDateTime.class).toInstant(), Map.of()));
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        RateSnapshot head = heads.get(0);
        Map<String, BigDecimal> rates = new TreeMap<>();
        jdbc.query("select currency, rate from fx_snapshot_rates where snapshot_id = ?",
                (RowCallbackHandler) rs -> rates.put(rs.getString("currency"), rs.getBigDecimal("rate")),
                head.id());
        return Optional.of(new RateSnapshot(head.id(), head.provider(), head.baseCurrency(),
                head.providerTimestamp(), head.fetchedAt(), rates));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}