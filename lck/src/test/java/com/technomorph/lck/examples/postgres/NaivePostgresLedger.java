package com.technomorph.lck.examples.postgres;

import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Model.*;

import java.sql.*;
import java.util.*;

/**
 * The charge method from the article, in SQL: an idempotency check, a sufficient-funds check,
 * and a transaction wrapping both. Three defences, none of which survives concurrency.
 *
 * <p>Every choice is one that ships. The idempotency check is a query in application code with
 * no constraint behind it. The balance is read, modified in Java, and written back. The funds
 * check runs before the write rather than as part of it. And it is all inside one transaction,
 * which is the reassuring part and the mistaken one: a transaction gives atomicity, not
 * isolation, and at READ COMMITTED two of these can read the same row, both act on what they
 * read, and both commit without violating anything.
 */
public final class NaivePostgresLedger implements LedgerAdapter {

    static final String SCHEMA = "naive";
    private final Pg pg;

    public NaivePostgresLedger(Pg pg) throws SQLException {
        this.pg = pg;
        pg.ddl(SCHEMA);
        pg.tx(c -> {
            try (Statement s = c.createStatement()) {
                // No primary key on the key column. That is the defect: the check below is the
                // only thing stopping a duplicate, and a check is not a constraint.
                s.execute("""
                    CREATE TABLE IF NOT EXISTS naive.idempotency_keys (
                      idempotency_key TEXT NOT NULL,
                      transaction_id  TEXT NOT NULL)""");
            }
            return null;
        });
    }

    @Override public String name() { return "naive-postgres"; }

    @Override public Set<Capability> capabilities() {
        return EnumSet.of(Capability.OVERDRAFT_GUARD, Capability.COMPENSATION, Capability.REPLAY);
    }

    @Override public void reset() throws SQLException { pg.truncate(SCHEMA); }

    @Override public PostResult post(Transaction txn) throws SQLException {
        if (txn.netByCurrency().values().stream().anyMatch(v -> v != 0L))
            return PostResult.rejected(txn.transactionId(), "unbalanced");

        return pg.tx(c -> {
            // 1. Have we seen this key? A read, with nothing to stop a second reader
            //    reaching the same conclusion before either writes.
            try (PreparedStatement q = c.prepareStatement(
                    "SELECT transaction_id FROM naive.idempotency_keys WHERE idempotency_key = ?")) {
                q.setString(1, txn.idempotencyKey());
                try (ResultSet rs = q.executeQuery()) {
                    if (rs.next()) return PostResult.duplicate(rs.getString(1));
                }
            }

            // 2. Sufficient funds? Read now, write later.
            if (!txn.allowOverdraft())
                for (Leg l : txn.legs())
                    if (l.type() == EntryType.DEBIT && read(c, l.accountId(), l.currency()) < l.amountSubunits())
                        return PostResult.rejected(txn.transactionId(), "insufficient funds");

            // 3. Read, modify, write. One of two concurrent writers loses.
            for (Leg l : txn.legs()) {
                long current = read(c, l.accountId(), l.currency());
                try (PreparedStatement u = c.prepareStatement("""
                        INSERT INTO naive.accounts (account_id, currency, balance_subunits)
                        VALUES (?, ?, ?)
                        ON CONFLICT (account_id, currency) DO UPDATE SET balance_subunits = ?""")) {
                    u.setString(1, l.accountId());
                    u.setString(2, l.currency());
                    u.setLong(3, current + l.signed());
                    u.setLong(4, current + l.signed());
                    u.executeUpdate();
                }
                try (PreparedStatement j = c.prepareStatement("""
                        INSERT INTO naive.journal_entries
                          (transaction_id, account_id, entry_type, amount_subunits, currency, account_sequence)
                        VALUES (?, ?, ?, ?, ?,
                          (SELECT COALESCE(MAX(account_sequence), 0) + 1
                             FROM naive.journal_entries WHERE account_id = ?))""")) {
                    j.setString(1, txn.transactionId());
                    j.setString(2, l.accountId());
                    j.setString(3, l.type().name());
                    j.setLong(4, l.amountSubunits());
                    j.setString(5, l.currency());
                    j.setString(6, l.accountId());
                    j.executeUpdate();
                }
            }

            try (PreparedStatement k = c.prepareStatement(
                    "INSERT INTO naive.idempotency_keys (idempotency_key, transaction_id) VALUES (?, ?)")) {
                k.setString(1, txn.idempotencyKey());
                k.setString(2, txn.transactionId());
                k.executeUpdate();
            }
            return PostResult.applied(txn.transactionId());
        });
    }

    private static long read(Connection c, String account, String currency) throws SQLException {
        try (PreparedStatement q = c.prepareStatement(
                "SELECT balance_subunits FROM naive.accounts WHERE account_id = ? AND currency = ?")) {
            q.setString(1, account);
            q.setString(2, currency);
            try (ResultSet rs = q.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
        }
    }

    @Override public long balance(String account, String currency) throws SQLException {
        return pg.tx(c -> read(c, account, currency));
    }

    @Override public List<JournalEntry> journal() throws SQLException {
        return pg.tx(c -> Journal.read(c, SCHEMA));
    }
}
