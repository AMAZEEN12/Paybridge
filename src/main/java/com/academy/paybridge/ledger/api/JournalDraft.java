package com.academy.paybridge.ledger.api;

import java.util.List;

/**
 * A journal worked out but not yet posted. The caller posts it inside its own transaction:
 * {@code ledger.recordJournal(d.key(), d.reference(), d.entryType(), d.lines())}.
 */
public record JournalDraft(String key, String reference, String entryType, List<JournalLine> lines) {

    public JournalDraft {
        lines = List.copyOf(lines);
    }
}