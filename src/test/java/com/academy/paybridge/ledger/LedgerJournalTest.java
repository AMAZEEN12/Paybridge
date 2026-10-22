package com.academy.paybridge.ledger;

import com.academy.paybridge.ledger.api.JournalLine;
import com.academy.paybridge.ledger.api.NairaJournals;
import com.academy.paybridge.ledger.domain.Direction;
import com.academy.paybridge.ledger.domain.LedgerEntry;
import com.academy.paybridge.ledger.repository.LedgerEntryRepository;
import com.academy.paybridge.ledger.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.academy.paybridge.ledger.api.JournalLine.Side.CREDIT;
import static com.academy.paybridge.ledger.api.JournalLine.Side.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The double-entry rule per currency. No database and no Spring: the repository is a stand-in. */
class LedgerJournalTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private LedgerEntryRepository repo;
    private LedgerService ledger;
    private List<LedgerEntry> saved;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(LedgerEntryRepository.class);
        saved = new ArrayList<>();
        ledger = new LedgerService(repo, clock);
        when(repo.saveAll(any())).thenAnswer(inv -> {
            ((Iterable<LedgerEntry>) inv.getArgument(0)).forEach(saved::add);
            return saved;
        });
    }

    @Test
    void aJournalThatBalancesInEachCurrencyIsSaved() {
        ledger.recordJournal("CONV:1", "ref", "CONVERSION", List.of(
                new JournalLine("2000000001:NGN", DEBIT, "NGN", 1_000_000),
                new JournalLine("EXT:FX_POOL:NGN", CREDIT, "NGN", 1_000_000),
                new JournalLine("EXT:FX_POOL:USD", DEBIT, "USD", 650),
                new JournalLine("2000000001:USD", CREDIT, "USD", 650)));

        assertThat(saved).hasSize(4);
        assertThat(saved).extracting(LedgerEntry::getCurrency).containsExactly("NGN", "NGN", "USD", "USD");
        assertThat(saved).extracting(LedgerEntry::getAccountNumber)
                .containsExactly("2000000001:NGN", "EXT:FX_POOL:NGN", "EXT:FX_POOL:USD", "2000000001:USD");
        assertThat(saved.get(2).getDirection()).isEqualTo(Direction.DEBIT);
        assertThat(saved.get(2).getAmountMinor()).isEqualTo(650L);
    }

    @Test
    void anUnbalancedSecondCurrencySavesNothing() {
        assertThatThrownBy(() -> ledger.recordJournal("CONV:2", null, "CONVERSION", List.of(
                new JournalLine("2000000001:NGN", DEBIT, "NGN", 1_000_000),
                new JournalLine("EXT:FX_POOL:NGN", CREDIT, "NGN", 1_000_000),
                new JournalLine("EXT:FX_POOL:USD", DEBIT, "USD", 650),
                new JournalLine("2000000001:USD", CREDIT, "USD", 640))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("USD");
        verify(repo, never()).saveAll(any());
    }

    @Test
    void oneCurrencyCannotBalanceAnother() {
        // 100 USD out and 100 EUR in: the totals match as numbers but not per currency.
        assertThatThrownBy(() -> ledger.recordJournal("CONV:3", null, "CONVERSION", List.of(
                new JournalLine("EXT:FX_POOL:USD", DEBIT, "USD", 100),
                new JournalLine("EXT:FX_POOL:EUR", CREDIT, "EUR", 100))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unbalanced");
        verify(repo, never()).saveAll(any());
    }

    @Test
    void aWalletKeyAndItsCurrencyMustAgree() {
        assertThatThrownBy(() -> ledger.recordJournal("CONV:4", null, "CONVERSION", List.of(
                new JournalLine("2000000001:USD", DEBIT, "NGN", 100),
                new JournalLine("EXT:FX_POOL:NGN", CREDIT, "NGN", 100))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("key says USD");
        verify(repo, never()).saveAll(any());
    }

    @Test
    void badLinesAreRefused() {
        assertThatThrownBy(() -> ledger.recordJournal("J", null, "T", List.of(
                new JournalLine("EXT:FUNDING", DEBIT, "USD", 0),
                new JournalLine("EXT:IN_FLIGHT", CREDIT, "USD", 0)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.recordJournal("J", null, "T", List.of(
                new JournalLine("EXT:FUNDING", DEBIT, "usd", 5),
                new JournalLine("EXT:IN_FLIGHT", CREDIT, "usd", 5)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.recordJournal("J", null, "T", List.of())).isInstanceOf(IllegalStateException.class);
        verify(repo, never()).saveAll(any());
    }

    @Test
    void theNairaMethodsStillWriteNairaWalletKeys() {
        NairaJournals.funding(ledger, "2000000001", 5_000);

        assertThat(saved).extracting(LedgerEntry::getAccountNumber).containsExactly("EXT:FUNDING", "2000000001:NGN");
        assertThat(saved).extracting(LedgerEntry::getCurrency).containsOnly("NGN");
    }
}