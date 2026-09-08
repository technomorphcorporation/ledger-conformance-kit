package com.technomorph.lck.examples.postgres;

import com.technomorph.lck.spi.Model.EntryType;
import com.technomorph.lck.spi.Model.JournalEntry;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Reading the journal is identical in both ledgers; only writing it differs. */
final class Journal {
    private Journal() { }

    static List<JournalEntry> read(Connection c, String schema) throws SQLException {
        List<JournalEntry> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                 SELECT entry_id, transaction_id, account_id, entry_type,
                        amount_subunits, currency, account_sequence
                   FROM %s.journal_entries ORDER BY entry_id""".formatted(schema))) {
            while (rs.next())
                out.add(new JournalEntry(
                        String.valueOf(rs.getLong("entry_id")), rs.getString("transaction_id"),
                        rs.getString("account_id"), EntryType.valueOf(rs.getString("entry_type")),
                        rs.getLong("amount_subunits"), rs.getString("currency").trim(),
                        rs.getLong("entry_id"), rs.getLong("account_sequence"), "", ""));
        }
        return out;
    }
}
