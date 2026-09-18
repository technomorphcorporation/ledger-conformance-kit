package com.technomorph.lck.examples.postgres;

import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Model.*;

import java.sql.*;
import java.util.*;

/**
 * {@link TutorialLedger} with two constraints added, and nothing else changed.
 *
 * <p>This exists so the report has a remedy that was run rather than a remedy that was asserted.
 * A report saying "the pattern you copied fails four invariants" is an accusation; the same report
 * with the constraints that close them, and evidence they close them, is a contribution. The
 * application code, the table shape, the derived balance and the isolation level are all identical
 * to the tutorial version — the difference is entirely in the schema, which is the point.
 *
 * <h2>1. A unique index, so an idempotency key means something</h2>
 *
 * <p>The tutorials have no idempotency concept at all, which is what INV-04 and INV-05 find. The
 * fix is a table whose primary key <em>is</em> the key, claimed in the same transaction as the
 * entries:
 *
 * <pre>{@code
 * CREATE TABLE applied_transactions (idempotency_key TEXT PRIMARY KEY, ...)
 * INSERT ... ON CONFLICT (idempotency_key) DO NOTHING    -- zero rows == someone else has it
 * }</pre>
 *
 * <p>The constraint is the guarantee, not the check. A {@code SELECT} first and an {@code INSERT}
 * after is the version that loses the race, and it is the version that looks correct in review.
 *
 * <h2>2. A deferred constraint trigger, so a transaction has to balance</h2>
 *
 * <p>INV-01 and INV-11 find that nothing in the schema stops a transaction debiting 24.00 and
 * crediting 23.00, or closing a USD debit with a EUR credit. Both are enforceable, and the reason
 * the enforcement has to be <b>deferred</b> is the whole subtlety: the legs are inserted one at a
 * time, so the sums only balance at the end of the statement. A plain row-level trigger fires
 * after the first insert and rejects every transaction there is.
 *
 * <pre>{@code
 * CREATE CONSTRAINT TRIGGER entries_balance
 *   AFTER INSERT ON entries DEFERRABLE INITIALLY DEFERRED
 *   FOR EACH ROW EXECUTE FUNCTION assert_transaction_balances();
 * }</pre>
 *
 * <p>Enforcing it per currency is what makes it cover INV-11 as well: a transaction that nets to
 * zero across two currencies nets to zero in neither.
 *
 * <h2>What is deliberately still missing</h2>
 *
 * <p>No sufficient-funds rule, so {@link Capability#OVERDRAFT_GUARD} stays undeclared and INV-09
 * and INV-15 stay not applicable. That is not an oversight: the tutorials do not have a guard, the
 * two constraints above are the minimum that closes what was actually found, and adding a third
 * change would make the comparison measure something other than the two.
 */
final class ConstrainedTutorialLedger implements LedgerAdapter {

    static final String SCHEMA = "constrained";

    private final Pg pg;

    ConstrainedTutorialLedger(Pg pg) throws SQLException {
        this.pg = pg;
        pg.tx(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
                s.execute("""
                    CREATE TABLE IF NOT EXISTS constrained.entries (
                      entry_id         BIGSERIAL PRIMARY KEY,
                      transaction_id   TEXT    NOT NULL,
                      account_id       TEXT    NOT NULL,
                      entry_type       TEXT    NOT NULL CHECK (entry_type IN ('DEBIT','CREDIT')),
                      amount_subunits  BIGINT  NOT NULL CHECK (amount_subunits > 0),
                      currency         CHAR(3) NOT NULL,
                      created_at       TIMESTAMPTZ NOT NULL DEFAULT now())""");
                s.execute("CREATE INDEX IF NOT EXISTS c_entries_account "
                        + "ON constrained.entries (account_id, currency)");

                // Fix 1. The key is the primary key. Nothing in application code decides this.
                s.execute("""
                    CREATE TABLE IF NOT EXISTS constrained.applied_transactions (
                      idempotency_key TEXT PRIMARY KEY,
                      transaction_id  TEXT NOT NULL,
                      applied_at      TIMESTAMPTZ NOT NULL DEFAULT now())""");

                // Fix 2. Deferred, so it sees the whole transaction rather than its first leg.
                s.execute("""
                    CREATE OR REPLACE FUNCTION constrained.assert_transaction_balances()
                    RETURNS TRIGGER AS $$
                    DECLARE offending RECORD;
                    BEGIN
                      SELECT currency,
                             SUM(CASE WHEN entry_type = 'CREDIT'
                                      THEN amount_subunits ELSE -amount_subunits END) AS net
                        INTO offending
                        FROM constrained.entries
                       WHERE transaction_id = NEW.transaction_id
                       GROUP BY currency
                      HAVING SUM(CASE WHEN entry_type = 'CREDIT'
                                      THEN amount_subunits ELSE -amount_subunits END) <> 0
                       LIMIT 1;
                      IF FOUND THEN
                        RAISE EXCEPTION
                          'transaction % does not balance in %: net %',
                          NEW.transaction_id, offending.currency, offending.net;
                      END IF;
                      RETURN NULL;
                    END; $$ LANGUAGE plpgsql""");
                s.execute("DROP TRIGGER IF EXISTS entries_balance ON constrained.entries");
                s.execute("""
                    CREATE CONSTRAINT TRIGGER entries_balance
                      AFTER INSERT ON constrained.entries
                      DEFERRABLE INITIALLY DEFERRED
                      FOR EACH ROW EXECUTE FUNCTION constrained.assert_transaction_balances()""");
            }
            return null;
        });
    }

    @Override public String name() { return "constrained-tutorial"; }

    @Override public Set<Capability> capabilities() {
        return EnumSet.of(Capability.COMPENSATION, Capability.REPLAY);
    }

    @Override public void reset() throws SQLException {
        pg.tx(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("TRUNCATE constrained.entries, constrained.applied_transactions");
            }
            return null;
        });
    }

    @Override public PostResult post(Transaction txn) throws SQLException {
        try {
            return pg.tx(c -> {
                try (PreparedStatement claim = c.prepareStatement("""
                        INSERT INTO constrained.applied_transactions
                          (idempotency_key, transaction_id) VALUES (?, ?)
                        ON CONFLICT (idempotency_key) DO NOTHING""")) {
                    claim.setString(1, txn.idempotencyKey());
                    claim.setString(2, txn.transactionId());
                    // Zero rows means the unique index refused it, which means somebody else has
                    // the key. Not a check that might be stale: the index decided, in this
                    // transaction, and the answer cannot change under us.
                    if (claim.executeUpdate() == 0)
                        return PostResult.duplicate(txn.transactionId());
                }
                try (PreparedStatement ins = c.prepareStatement("""
                        INSERT INTO constrained.entries
                          (transaction_id, account_id, entry_type, amount_subunits, currency)
                        VALUES (?, ?, ?, ?, ?)""")) {
                    for (Leg l : txn.legs()) {
                        ins.setString(1, txn.transactionId());
                        ins.setString(2, l.accountId());
                        ins.setString(3, l.type().name());
                        ins.setLong(4, l.amountSubunits());
                        ins.setString(5, l.currency());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                return PostResult.applied(txn.transactionId());
            });
        } catch (SQLException e) {
            // The deferred trigger fires at commit, so an unbalanced transaction arrives here as a
            // failed commit rather than as a failed insert. It is a business rejection and has to
            // be reported as one: nothing was written, so nothing was lost.
            if (String.valueOf(e.getMessage()).contains("does not balance"))
                return PostResult.rejected(txn.transactionId(), e.getMessage().trim());
            throw e;
        }
    }

    @Override public long balance(String accountId, String currency) throws SQLException {
        return pg.tx(c -> {
            try (PreparedStatement q = c.prepareStatement("""
                    SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT'
                                             THEN amount_subunits ELSE -amount_subunits END), 0)
                      FROM constrained.entries
                     WHERE account_id = ? AND currency = ?""")) {
                q.setString(1, accountId);
                q.setString(2, currency);
                try (ResultSet rs = q.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    @Override public List<JournalEntry> journal() throws SQLException {
        return pg.tx(c -> {
            List<JournalEntry> out = new ArrayList<>();
            Map<String, Long> perAccount = new HashMap<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("""
                         SELECT entry_id, transaction_id, account_id, entry_type,
                                amount_subunits, currency
                           FROM constrained.entries
                          ORDER BY entry_id""")) {
                while (rs.next()) {
                    String acct = rs.getString("account_id");
                    String ccy = rs.getString("currency");
                    out.add(new JournalEntry(
                            String.valueOf(rs.getLong("entry_id")),
                            rs.getString("transaction_id"), acct,
                            EntryType.valueOf(rs.getString("entry_type")),
                            rs.getLong("amount_subunits"), ccy,
                            rs.getLong("entry_id"),
                            perAccount.merge(acct + "|" + ccy, 1L, Long::sum),
                            null, null));
                }
            }
            return out;
        });
    }
}
