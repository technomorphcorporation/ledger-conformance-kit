package com.technomorph.lck.examples.postgres;

import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Model.*;

import java.sql.*;
import java.util.*;

/**
 * The same ledger with the article's three fixes applied. Every one is the same shape: stop
 * separating the decision from the write.
 *
 * <ul>
 *   <li>Idempotency is a constraint, not a query. {@code ON CONFLICT DO NOTHING} affecting zero
 *       rows is the atomic answer to "has someone already claimed this".</li>
 *   <li>The sufficient-funds predicate is in the {@code UPDATE}. No {@code SELECT} first, so
 *       there is no window between deciding and doing; zero rows means insufficient funds, and
 *       that is a business outcome rather than an exception.</li>
 *   <li>The idempotency record has three states. A duplicate arriving while the first is still
 *       IN_FLIGHT waits — it is never told the payment already succeeded, because that would be
 *       a response to a transaction that has not happened.</li>
 * </ul>
 */
public final class PostgresLedger implements LedgerAdapter {

    static final String SCHEMA = "correct";
    private final Pg pg;

    public PostgresLedger(Pg pg) throws SQLException {
        this.pg = pg;
        pg.ddl(SCHEMA);
        pg.tx(c -> {
            try (Statement s = c.createStatement()) {
                // The key is the primary key. That is the guarantee; everything in front of it,
                // including any Redis check, is latency reduction.
                s.execute("""
                    CREATE TABLE IF NOT EXISTS correct.idempotency_keys (
                      idempotency_key TEXT PRIMARY KEY,
                      transaction_id  TEXT NOT NULL,
                      state           TEXT NOT NULL
                        CHECK (state IN ('IN_FLIGHT','COMMITTED','FAILED')))""");
            }
            return null;
        });
    }

    @Override public String name() { return "postgres"; }

    @Override public Set<Capability> capabilities() {
        return EnumSet.of(Capability.OVERDRAFT_GUARD, Capability.COMPENSATION, Capability.REPLAY);
    }

    @Override public void reset() throws SQLException { pg.truncate(SCHEMA); }

    @Override public PostResult post(Transaction txn) throws SQLException {
        if (txn.legs().isEmpty()) return PostResult.rejected(txn.transactionId(), "no legs");
        if (txn.netByCurrency().values().stream().anyMatch(v -> v != 0L))
            return PostResult.rejected(txn.transactionId(), "unbalanced per currency");

        while (true) {
            Outcome o = attempt(txn);
            if (o.result != null) return o.result;
            // The key is held by someone whose outcome is not yet known. Wait for them rather
            // than answer for them, then look again.
            try { Thread.sleep(2); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted waiting on an in-flight idempotency key", e);
            }
        }
    }

    private record Outcome(PostResult result) { }

    private Outcome attempt(Transaction txn) throws SQLException {
        return pg.tx(c -> {
            int claimed;
            try (PreparedStatement k = c.prepareStatement("""
                    INSERT INTO correct.idempotency_keys (idempotency_key, transaction_id, state)
                    VALUES (?, ?, 'IN_FLIGHT') ON CONFLICT (idempotency_key) DO NOTHING""")) {
                k.setString(1, txn.idempotencyKey());
                k.setString(2, txn.transactionId());
                claimed = k.executeUpdate();       // zero rows: somebody else holds it
            }

            if (claimed == 0) {
                try (PreparedStatement q = c.prepareStatement(
                        "SELECT state, transaction_id FROM correct.idempotency_keys WHERE idempotency_key = ?")) {
                    q.setString(1, txn.idempotencyKey());
                    try (ResultSet rs = q.executeQuery()) {
                        if (!rs.next()) return new Outcome(null);            // released; try again
                        String state = rs.getString(1);
                        if ("COMMITTED".equals(state))
                            return new Outcome(PostResult.duplicate(rs.getString(2)));
                        if ("FAILED".equals(state)) {
                            // It never took effect, so the key is free. Release and retry.
                            try (PreparedStatement d = c.prepareStatement(
                                    "DELETE FROM correct.idempotency_keys WHERE idempotency_key = ? AND state = 'FAILED'")) {
                                d.setString(1, txn.idempotencyKey());
                                d.executeUpdate();
                            }
                            return new Outcome(null);
                        }
                        return new Outcome(null);                            // IN_FLIGHT: wait
                    }
                }
            }

            // A total order over the accounts touched, so two multi-leg transactions cannot
            // deadlock by taking the same rows in opposite orders.
            List<Leg> ordered = new ArrayList<>(txn.legs());
            ordered.sort(Comparator.comparing(Leg::accountId).thenComparing(Leg::currency));

            for (Leg l : ordered) {
                if (l.type() == EntryType.DEBIT && !txn.allowOverdraft()) {
                    // The predicate and the write are one statement. Zero rows is the answer.
                    int moved;
                    try (PreparedStatement u = c.prepareStatement("""
                            UPDATE correct.accounts SET balance_subunits = balance_subunits - ?
                             WHERE account_id = ? AND currency = ? AND balance_subunits >= ?""")) {
                        u.setLong(1, l.amountSubunits());
                        u.setString(2, l.accountId());
                        u.setString(3, l.currency());
                        u.setLong(4, l.amountSubunits());
                        moved = u.executeUpdate();
                    }
                    if (moved == 0) {
                        markFailed(c, txn.idempotencyKey());
                        return new Outcome(PostResult.rejected(
                                txn.transactionId(), "insufficient funds on " + l.accountId()));
                    }
                } else {
                    try (PreparedStatement u = c.prepareStatement("""
                            INSERT INTO correct.accounts (account_id, currency, balance_subunits)
                            VALUES (?, ?, ?)
                            ON CONFLICT (account_id, currency) DO UPDATE
                              SET balance_subunits = correct.accounts.balance_subunits + EXCLUDED.balance_subunits""")) {
                        u.setString(1, l.accountId());
                        u.setString(2, l.currency());
                        u.setLong(3, l.signed());
                        u.executeUpdate();
                    }
                }
            }

            // One writer at a time into the journal, so the sequence a reader sees is commit
            // order rather than insertion order. Without this a transaction that started later
            // and committed sooner would slot in behind an entry already visible, and the
            // append-only prefix would move under a reader.
            try (Statement lock = c.createStatement()) { lock.execute("SELECT pg_advisory_xact_lock(1)"); }

            for (Leg l : txn.legs()) {
                try (PreparedStatement j = c.prepareStatement("""
                        INSERT INTO correct.journal_entries
                          (transaction_id, account_id, entry_type, amount_subunits, currency, account_sequence)
                        VALUES (?, ?, ?, ?, ?,
                          (SELECT COALESCE(MAX(account_sequence), 0) + 1
                             FROM correct.journal_entries WHERE account_id = ?))""")) {
                    j.setString(1, txn.transactionId());
                    j.setString(2, l.accountId());
                    j.setString(3, l.type().name());
                    j.setLong(4, l.amountSubunits());
                    j.setString(5, l.currency());
                    j.setString(6, l.accountId());
                    j.executeUpdate();
                }
            }

            try (PreparedStatement done = c.prepareStatement(
                    "UPDATE correct.idempotency_keys SET state = 'COMMITTED' WHERE idempotency_key = ?")) {
                done.setString(1, txn.idempotencyKey());
                done.executeUpdate();
            }
            return new Outcome(PostResult.applied(txn.transactionId()));
        });
    }

    private static void markFailed(Connection c, String key) throws SQLException {
        try (PreparedStatement f = c.prepareStatement(
                "UPDATE correct.idempotency_keys SET state = 'FAILED' WHERE idempotency_key = ?")) {
            f.setString(1, key);
            f.executeUpdate();
        }
    }

    @Override public long balance(String account, String currency) throws SQLException {
        return pg.tx(c -> {
            try (PreparedStatement q = c.prepareStatement(
                    "SELECT balance_subunits FROM correct.accounts WHERE account_id = ? AND currency = ?")) {
                q.setString(1, account);
                q.setString(2, currency);
                try (ResultSet rs = q.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
            }
        });
    }

    @Override public List<JournalEntry> journal() throws SQLException {
        return pg.tx(c -> Journal.read(c, SCHEMA));
    }
}
