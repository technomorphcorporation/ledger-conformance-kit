package com.technomorph.lck.examples.postgres;

import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Model.*;

import java.sql.*;
import java.util.*;

/**
 * The double-entry ledger as the widely-copied PostgreSQL tutorials teach it.
 *
 * <p>This is not a ledger written to fail. {@link NaivePostgresLedger} is that — it implements the
 * charge method from our own article, with defects we chose. This one implements what is actually
 * published and actually copied, and the point is to find out what that does rather than to
 * demonstrate something already known.
 *
 * <h2>Every choice, and where it comes from</h2>
 *
 * <p>The shape below is the common denominator of the schemas circulating as "how to build a
 * ledger in Postgres": an accounts table, an append-only entries table with a positive amount and
 * a direction, and a balance that is <b>derived by summing the entries</b> rather than stored.
 * The most-linked example of it is a public gist that has been copied for a decade
 * ({@code gist.github.com/NYKevin/9433376}); it keeps balances in a materialised view over the
 * entries, which is the same idea with a refresh step.
 *
 * <ol>
 *   <li><b>Balances are derived, not stored.</b> No {@code balance} column anywhere. This is the
 *       single most consequential thing the tutorials get right, and it is worth being explicit
 *       that they do: there is no read-modify-write on a balance, so the entire lost-update class
 *       has nowhere to occur.</li>
 *   <li><b>Entries are append-only, and the amount is positive with a direction.</b>
 *       {@code CHECK (amount_subunits > 0)} plus {@code entry_type IN ('DEBIT','CREDIT')}, which
 *       is what nearly every published schema has.</li>
 *   <li><b>No idempotency key. None.</b> Not a weak one, not an unconstrained one — the concept is
 *       absent from the schema, as it is absent from the sources. This is the gap the report is
 *       about.</li>
 *   <li><b>No sufficient-funds rule.</b> The only constraint on an amount is that it is positive.
 *       So {@link Capability#OVERDRAFT_GUARD} is <b>not declared</b>, and INV-09 is reported as
 *       not applicable rather than failed. A ledger is not broken for lacking a feature it never
 *       claimed, and failing it for that would be a finding about the invariant.</li>
 *   <li><b>Default isolation, one transaction per posting.</b> READ COMMITTED, because that is
 *       what PostgreSQL gives you and none of the sources change it.</li>
 * </ol>
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p>It does not use {@code DOUBLE} for money, or drop the positive-amount check, or write the
 * two entries in separate transactions, or omit the currency. Those would all make it fail more
 * invariants and none of them is what the sources say. A report is only worth something if the
 * thing measured is the thing people actually copy, and every temptation to make the subject worse
 * than its sources has been declined — including the one that would have been easiest to justify.
 *
 * <p>The careful modern implementations are not this. {@code pgledger} deduplicates on a
 * client-supplied transfer id with {@code ON CONFLICT (id) DO NOTHING} and enforces balance rules
 * at the account level, which is precisely the two things missing here. That it exists is the
 * evidence that these are known problems with known solutions, not unavoidable ones.
 */
final class TutorialLedger implements LedgerAdapter {

    static final String SCHEMA = "tutorial";

    private final Pg pg;

    TutorialLedger(Pg pg) throws SQLException {
        this.pg = pg;
        pg.tx(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
                // No balance column: the balance is a query over the entries. And no idempotency
                // table at all -- the tutorials do not have one, so neither does this.
                s.execute("""
                    CREATE TABLE IF NOT EXISTS tutorial.entries (
                      entry_id         BIGSERIAL PRIMARY KEY,
                      transaction_id   TEXT    NOT NULL,
                      account_id       TEXT    NOT NULL,
                      entry_type       TEXT    NOT NULL CHECK (entry_type IN ('DEBIT','CREDIT')),
                      amount_subunits  BIGINT  NOT NULL CHECK (amount_subunits > 0),
                      currency         CHAR(3) NOT NULL,
                      created_at       TIMESTAMPTZ NOT NULL DEFAULT now())""");
                s.execute("CREATE INDEX IF NOT EXISTS entries_account "
                        + "ON tutorial.entries (account_id, currency)");
            }
            return null;
        });
    }

    @Override public String name() { return "tutorial-postgres"; }

    @Override public Set<Capability> capabilities() {
        // OVERDRAFT_GUARD is absent because the guard is absent. REPLAY is declared because the
        // balance genuinely is derivable from the entries -- see the note on it in the report,
        // because for this ledger that row is true and uninformative at the same time.
        return EnumSet.of(Capability.COMPENSATION, Capability.REPLAY);
    }

    @Override public void reset() throws SQLException {
        pg.tx(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("TRUNCATE tutorial.entries");
            }
            return null;
        });
    }

    @Override public PostResult post(Transaction txn) throws SQLException {
        return pg.tx(c -> {
            // The whole posting in one transaction, which is what the sources do and what feels
            // sufficient. It buys atomicity: either both entries land or neither does. It buys
            // nothing at all about two concurrent callers, and nothing about a retry.
            try (PreparedStatement ins = c.prepareStatement("""
                    INSERT INTO tutorial.entries
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
    }

    @Override public long balance(String accountId, String currency) throws SQLException {
        return pg.tx(c -> {
            try (PreparedStatement q = c.prepareStatement("""
                    SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT'
                                             THEN amount_subunits ELSE -amount_subunits END), 0)
                      FROM tutorial.entries
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
                           FROM tutorial.entries
                          ORDER BY entry_id""")) {
                while (rs.next()) {
                    String acct = rs.getString("account_id");
                    String ccy = rs.getString("currency");
                    out.add(new JournalEntry(
                            String.valueOf(rs.getLong("entry_id")),
                            rs.getString("transaction_id"),
                            acct,
                            EntryType.valueOf(rs.getString("entry_type")),
                            rs.getLong("amount_subunits"),
                            ccy,
                            rs.getLong("entry_id"),
                            perAccount.merge(acct + "|" + ccy, 1L, Long::sum),
                            // No hash chain in any of the sources, so none here, and HASH_CHAIN is
                            // not declared. Inventing one would satisfy INV-03 with a value this
                            // adapter computed.
                            null, null));
                }
            }
            return out;
        });
    }
}
