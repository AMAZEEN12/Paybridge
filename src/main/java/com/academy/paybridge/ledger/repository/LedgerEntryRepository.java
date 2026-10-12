package com.academy.paybridge.ledger.repository;

import com.academy.paybridge.ledger.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * The read queries return plain rows (Object[]) on purpose: that avoids column-name case problems between
 * Postgres and Spring's projections. Each query says which column is which.
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /** Rows: [journal_key, total debits, total credits] for journals whose two sides differ. At most 50. */
    @Query(value = """
            select journal_key,
                   coalesce(sum(case when direction = 'DEBIT' then amount_kobo else 0 end), 0)::bigint,
                   coalesce(sum(case when direction = 'CREDIT' then amount_kobo else 0 end), 0)::bigint
            from ledger_entries
            group by journal_key
            having sum(case when direction = 'DEBIT' then amount_kobo else 0 end)
                <> sum(case when direction = 'CREDIT' then amount_kobo else 0 end)
            order by journal_key
            limit 50
            """, nativeQuery = true)
    List<Object[]> findUnbalancedJournals();

    /** Rows: [account_number, credits minus debits] for every account that has ledger lines. */
    @Query(value = """
            select account_number,
                   coalesce(sum(case when direction = 'CREDIT' then amount_kobo else -amount_kobo end), 0)::bigint
            from ledger_entries
            group by account_number
            """, nativeQuery = true)
    List<Object[]> netByAccount();

    @Query(value = "select count(distinct journal_key) from ledger_entries", nativeQuery = true)
    long countJournals();
}