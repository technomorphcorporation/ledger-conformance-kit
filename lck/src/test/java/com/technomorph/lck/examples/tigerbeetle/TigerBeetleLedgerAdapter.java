package com.technomorph.lck.examples.tigerbeetle;

import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.NotRepresentable;
import com.technomorph.lck.spi.Model.EntryType;
import com.technomorph.lck.spi.Model.JournalEntry;
import com.technomorph.lck.spi.Model.Leg;
import com.technomorph.lck.spi.Model.PostResult;
import com.technomorph.lck.spi.Model.Transaction;

import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.AccountFlags;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateAccountResult;
import com.tigerbeetle.CreateAccountResultBatch;
import com.tigerbeetle.CreateTransferResult;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.QueryFilter;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.UInt128;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The suite's adapter for TigerBeetle.
 *
 * <p>Lives in the test tree and cannot do otherwise: the client is a third-party jar carrying a
 * JNI native library, and {@code lck} has zero runtime dependencies. That is not a limitation to
 * work around — this adapter exists to calibrate the kit against the ledger built by the people
 * most serious about correctness, not to ship.
 *
 * <p>Every behaviour relied on below is asserted in {@link TigerBeetleAssumptionsTest} against a
 * real cluster, so a wrong claim about TigerBeetle fails in a test named after the claim rather
 * than as a false finding against the ledger.
 *
 * <h2>The mapping</h2>
 *
 * <table border="1">
 *   <caption>Model mapping</caption>
 *   <tr><th>Kit</th><th>TigerBeetle</th></tr>
 *   <tr><td>{@code idempotencyKey}</td><td>the transfer {@code id}, a u128 the client supplies</td></tr>
 *   <tr><td>{@code DUPLICATE}</td><td>{@code Exists}</td></tr>
 *   <tr><td>{@code REJECTED}</td><td>{@code ExceedsCredits}, {@code ExceedsDebits}, a ledger mismatch</td></tr>
 *   <tr><td>{@code APPLIED}</td><td>no entry in the result batch — a success is reported by absence</td></tr>
 *   <tr><td>{@code currency}</td><td>the {@code ledger} field, a non-zero u32</td></tr>
 *   <tr><td>{@code balance()}</td><td>{@code creditsPosted - debitsPosted}</td></tr>
 *   <tr><td>{@code reset()}</td><td>a fresh namespace; see below</td></tr>
 * </table>
 *
 * <h2>Four decisions worth arguing with</h2>
 *
 * <p><b>1. {@code reset()} changes a namespace rather than emptying anything.</b> TigerBeetle has
 * no truncate and no delete: a data file is formatted once and only ever appended to. So every id
 * this adapter derives is salted with a per-run token and a generation counter that {@code reset()}
 * increments. After a reset, every account is one TigerBeetle has never seen — balance zero by
 * construction — and {@code journal()} filters on the same salt, so earlier generations are
 * invisible rather than merely ignored. Nothing is ever destroyed, which is the right property
 * for a tool pointed at somebody else's ledger.
 *
 * <p><b>2. The overdraft guard is an account property here, and a transaction property in the
 * kit.</b> This is the real mismatch. TigerBeetle enforces sufficient funds with
 * {@code DEBITS_MUST_NOT_EXCEED_CREDITS}, set when an account is created and immutable
 * afterwards; the kit says {@code allowOverdraft} per transaction. They do not compose, so the
 * flag is decided at account creation by the first transaction that mentions the account: the
 * debit side of an {@code allowOverdraft} transaction is created unguarded, everything else
 * guarded.
 *
 * <p>That rule is only sound because {@code reset()} runs once per invariant, so each invariant
 * gets its own namespace and a given account name has one consistent intent within it. The
 * residue is that a guarded account can refuse a transfer the kit was willing to overdraw — which
 * lowers the applied count and cannot manufacture a finding, because every invariant asserts on
 * what the ledger applied rather than on what was submitted.
 *
 * <p><b>3. Account and currency names live in this adapter, not in the ledger.</b> TigerBeetle
 * identifies accounts by u128 and currencies by a u32 {@code ledger} field; it has no idea that
 * one of them is called {@code acct:hot} or {@code USD}. Both directions are derived by hashing,
 * and the reverse maps are held here so {@code journal()} can report names. The transfers
 * themselves are read back from the cluster — only the labels come from memory, and a transfer
 * the ledger dropped would be absent from the journal and caught by INV-13.
 *
 * <p><b>4. A cross-currency transaction is sent, not refused locally.</b> Legs are paired within
 * a currency, as for any source-to-destination ledger. What is left unpaired is deliberately sent
 * anyway, as a single transfer between accounts in different ledgers — which TigerBeetle refuses
 * itself, with {@code AccountsMustHaveTheSameLedger}. That is the difference from the Formance
 * adapter, where the same transaction has no representation at all and the refusal has to come
 * from the adapter; here INV-11 is a real result about the ledger.
 *
 * <h2>Capabilities</h2>
 *
 * <p>{@code OVERDRAFT_GUARD} because the flag exists and fires. {@code REPLAY} because the
 * transfer log is complete and reaches the kit by a different path from the account balances, so
 * INV-13 compares two independent answers. {@code COMPENSATION} because TigerBeetle appends and
 * never rewrites, which is all INV-12 asks. {@code HASH_CHAIN} is not declared: TigerBeetle's
 * integrity machinery is real but is not a per-entry chain this adapter can hand over, and
 * declaring it would make INV-03 check hashes the adapter computed itself.
 */
final class TigerBeetleLedgerAdapter implements LedgerAdapter {

    /** Non-zero, as TigerBeetle requires; the kit has no use for the field beyond that. */
    private static final int CODE = 1;

    private final Client client;
    private final int pageLimit;
    private final String run;
    private final AtomicInteger generation = new AtomicInteger();

    private volatile String namespace;
    private volatile byte[] salt;

    /** accountId|currency to the u128 it was created as, and back, for the current namespace. */
    private final Map<String, byte[]> accountIds = new ConcurrentHashMap<>();
    private final Map<String, String> accountNames = new ConcurrentHashMap<>();
    private final Map<Integer, String> currencies = new ConcurrentHashMap<>();

    /** 8189 is the client's maximum for one query, and the right choice outside a test. */
    TigerBeetleLedgerAdapter(Client client) { this(client, 8189); }

    /**
     * @param pageLimit transfers fetched per {@code queryTransfers} call. A test seam: at the
     *                  production limit a paging test would need 8190 transfers to prove the loop
     *                  works, so the test lowers it and forces the second page cheaply. The first
     *                  version of that test posted 300 against a limit of 8189 and never paged at
     *                  all, while claiming in its name that it did.
     */
    TigerBeetleLedgerAdapter(Client client, int pageLimit) {
        this.client = client;
        this.pageLimit = pageLimit;
        this.run = Long.toUnsignedString(System.currentTimeMillis(), 36);
        rotate();
    }

    @Override public String name() { return "tigerbeetle"; }

    @Override public Set<Capability> capabilities() {
        return EnumSet.of(Capability.OVERDRAFT_GUARD, Capability.REPLAY, Capability.COMPENSATION);
    }

    @Override public void reset() { rotate(); }

    private void rotate() {
        namespace = run + "-" + generation.incrementAndGet();
        salt = hash(namespace);
        accountIds.clear();
        accountNames.clear();
        currencies.clear();
    }

    // ------------------------------------------------------------------ writes

    @Override public PostResult post(Transaction txn) throws Exception {
        Paired paired = pair(txn.legs());
        List<Pair> pairs = paired.transfers();
        if (!paired.unpaired().isEmpty())
            // A TigerBeetle transfer moves one amount from one account to another, so an
            // unbalanced transaction has no representation. This used to return a rejection, which
            // made INV-01 report a pass the cluster had not earned.
            throw new NotRepresentable(
                    "a TigerBeetle transfer is balanced by construction -- one amount, one debit "
                            + "account, one credit account -- and these legs leave "
                            + paired.unpaired() + " with no counterparty, so the transaction "
                            + "cannot be submitted at all");
        if (pairs.isEmpty())
            return PostResult.rejected(txn.transactionId(), "no legs to post");

        byte[] transferId = id("txn|" + txn.idempotencyKey());
        TransferBatch batch = new TransferBatch(pairs.size());
        for (Pair p : pairs) {
            byte[] debit = account(p.debit, p.debitCurrency, !txn.allowOverdraft());
            byte[] credit = account(p.credit, p.creditCurrency, true);
            batch.add();
            // Several transfers from one submission must all apply or none: LINKED chains an event
            // to the next, and a failure anywhere fails the whole chain. Without it a partially
            // applied multi-leg transaction would look like the ledger losing money.
            batch.setId(pairs.size() == 1 ? transferId : id("txn|" + txn.idempotencyKey()
                    + "|" + batch.getPosition()));
            batch.setDebitAccountId(debit);
            batch.setCreditAccountId(credit);
            batch.setAmount(BigInteger.valueOf(p.amount));
            batch.setLedger(ledger(p.debitCurrency));
            batch.setCode(CODE);
            batch.setUserData128(salt);
            if (pairs.size() > 1 && batch.getPosition() < pairs.size() - 1)
                batch.setFlags(com.tigerbeetle.TransferFlags.LINKED);
        }

        CreateTransferResultBatch res = client.createTransfers(batch);
        if (res.getLength() == 0) return PostResult.applied(txn.transactionId());

        res.next();
        CreateTransferResult first = res.getResult();
        return switch (first) {
            case Exists, ExistsWithDifferentAmount, ExistsWithDifferentDebitAccountId,
                 ExistsWithDifferentCreditAccountId, ExistsWithDifferentLedger,
                 ExistsWithDifferentCode, ExistsWithDifferentFlags, ExistsWithDifferentUserData128,
                 ExistsWithDifferentUserData64, ExistsWithDifferentUserData32,
                 ExistsWithDifferentPendingId, ExistsWithDifferentTimeout ->
                    PostResult.duplicate(txn.transactionId());
            // IdAlreadyFailed is emphatically NOT a duplicate. TigerBeetle is telling the caller
            // that this id was submitted before and did not apply -- and it will never apply,
            // because the id is now burned. Reporting that as DUPLICATE would tell the caller
            // their payment had already succeeded when nothing was written, which is the exact
            // defect INV-15 exists to catch. It caught it here, in this adapter.
            case IdAlreadyFailed ->
                    PostResult.rejected(txn.transactionId(),
                            "IdAlreadyFailed: this transfer id was already submitted and refused, "
                                    + "and TigerBeetle will not reconsider it");
            // A refused write posts nothing and loses nothing. The guard firing, and a refusal to
            // move value between two different currencies, are both business outcomes.
            case ExceedsCredits, ExceedsDebits, AccountsMustHaveTheSameLedger,
                 TransferMustHaveTheSameLedgerAsAccounts, AccountsMustBeDifferent,
                 DebitAccountAlreadyClosed, CreditAccountAlreadyClosed ->
                    PostResult.rejected(txn.transactionId(), first.toString());
            case LinkedEventFailed ->
                    // The chain failed because a later event did; report what actually refused it.
                    PostResult.rejected(txn.transactionId(), chainReason(res));
            default -> throw new IllegalStateException(
                    "createTransfers -> " + first + " for key " + txn.idempotencyKey()
                            + ". This is the adapter and TigerBeetle disagreeing about the "
                            + "contract, not a finding about the ledger.");
        };
    }

    /** The first result in a linked chain that is not merely "the chain failed". */
    private static String chainReason(CreateTransferResultBatch res) {
        res.beforeFirst();
        while (res.next()) {
            CreateTransferResult r = res.getResult();
            if (r != CreateTransferResult.LinkedEventFailed
                    && r != CreateTransferResult.LinkedEventChainOpen) return r.toString();
        }
        return CreateTransferResult.LinkedEventFailed.toString();
    }

    // ------------------------------------------------------------------ reads

    @Override public long balance(String accountId, String currency) {
        byte[] id = accountIds.get(accountId + "|" + currency);
        if (id == null) return 0L;                  // never created, so nothing has been posted

        IdBatch ids = new IdBatch(1);
        ids.add();
        ids.setId(id);
        AccountBatch found = client.lookupAccounts(ids);
        if (!found.next()) return 0L;
        // longValueExact, not longValue: TigerBeetle counts in u128 and the kit in long subunits,
        // so a value that does not fit must throw rather than wrap into a plausible balance.
        return found.getCreditsPosted().longValueExact() - found.getDebitsPosted().longValueExact();
    }

    @Override public List<JournalEntry> journal() {
        List<JournalEntry> out = new ArrayList<>();
        Map<String, Long> perAccount = new java.util.HashMap<>();
        long seq = 0;
        long after = 0;

        // Paging to exhaustion is not optional: a short read understates the journal, and INV-13
        // would then report a ledger unable to rebuild its own balances — a false finding produced
        // by the harness. The filter is the namespace salt, so earlier generations cannot appear.
        while (true) {
            QueryFilter f = new QueryFilter();
            f.setUserData128(salt);
            f.setTimestampMin(after + 1);
            f.setLimit(pageLimit);
            TransferBatch page = client.queryTransfers(f);
            if (page.getLength() == 0) break;

            while (page.next()) {
                long amount = page.getAmount().longValueExact();
                String ccy = currencies.getOrDefault(page.getLedger(), "?" + page.getLedger());
                String debit = nameOf(page.getDebitAccountId());
                String credit = nameOf(page.getCreditAccountId());
                String txnId = UInt128.asBigInteger(page.getId()).toString();

                out.add(entry(txnId + ":d", txnId, debit, EntryType.DEBIT, amount, ccy,
                        ++seq, perAccount.merge(debit + "|" + ccy, 1L, Long::sum)));
                out.add(entry(txnId + ":c", txnId, credit, EntryType.CREDIT, amount, ccy,
                        ++seq, perAccount.merge(credit + "|" + ccy, 1L, Long::sum)));
                after = page.getTimestamp();
            }
        }
        return out;
    }

    private String nameOf(byte[] accountId) {
        String n = accountNames.get(key(accountId));
        // A transfer carrying this run's salt but naming an account this adapter did not create
        // cannot happen, and inventing a label for it would hide that.
        return n == null ? "unknown:" + UInt128.asBigInteger(accountId) : n;
    }

    private static JournalEntry entry(String id, String txnId, String acct, EntryType type,
                                      long amount, String ccy, long seq, long acctSeq) {
        // No prev/entry hash, which is why HASH_CHAIN is not declared: computing one here would
        // satisfy INV-03 with a value this adapter invented.
        return new JournalEntry(id, txnId, acct, type, amount, ccy, seq, acctSeq, null, null);
    }

    // ------------------------------------------------------------------ accounts

    /**
     * Returns the u128 for an account, creating it on first use.
     *
     * @param guarded whether to set {@code DEBITS_MUST_NOT_EXCEED_CREDITS}. Only consulted the
     *                first time an account is seen — the flag is immutable in TigerBeetle, which
     *                is decision 2 in the class javadoc.
     */
    private byte[] account(String accountId, String currency, boolean guarded) {
        String k = accountId + "|" + currency;
        byte[] existing = accountIds.get(k);
        if (existing != null) return existing;

        byte[] id = id("acct|" + k);
        AccountBatch batch = new AccountBatch(1);
        batch.add();
        batch.setId(id);
        batch.setLedger(ledger(currency));
        batch.setCode(CODE);
        batch.setUserData128(salt);
        batch.setFlags(guarded ? AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS : AccountFlags.NONE);

        CreateAccountResultBatch res = client.createAccounts(batch);
        if (res.getLength() > 0) {
            res.next();
            CreateAccountResult r = res.getResult();
            // Exists is benign and expected under concurrency: two threads can race to create the
            // same account, and the loser simply uses the winner's. Anything else is a real
            // disagreement about the contract.
            if (r != CreateAccountResult.Exists
                    && r != CreateAccountResult.ExistsWithDifferentFlags
                    && r != CreateAccountResult.ExistsWithDifferentLedger
                    && r != CreateAccountResult.ExistsWithDifferentCode
                    && r != CreateAccountResult.ExistsWithDifferentUserData128
                    && r != CreateAccountResult.ExistsWithDifferentUserData64
                    && r != CreateAccountResult.ExistsWithDifferentUserData32)
                throw new IllegalStateException("createAccounts(" + k + ") -> " + r);
        }

        accountNames.put(key(id), accountId);
        byte[] won = accountIds.putIfAbsent(k, id);
        return won == null ? id : won;
    }

    private int ledger(String currency) {
        // Non-zero and stable: TigerBeetle refuses ledger 0, and the same currency must land on
        // the same number for the life of a namespace or transfers would stop matching accounts.
        int n = (int) (Integer.toUnsignedLong(("ccy|" + currency).hashCode()) % 2_000_000_000L) + 1;
        String clash = currencies.putIfAbsent(n, currency);
        if (clash != null && !clash.equals(currency))
            throw new IllegalStateException("currency codes " + clash + " and " + currency
                    + " both map to ledger " + n + "; the mapping needs widening");
        return n;
    }

    // ------------------------------------------------------------------ leg pairing

    record Pair(String debit, String debitCurrency,
                        String credit, String creditCurrency, long amount) { }

    /**
     * Pairs debits against credits within a currency, then pairs whatever is left across
     * currencies so the ledger gets to refuse it. See decision 4 in the class javadoc.
     */
    static Paired pair(List<Leg> legs) {
        List<Pair> out = new ArrayList<>();
        List<Object[]> debits = new ArrayList<>();      // name, currency, remaining
        List<Object[]> credits = new ArrayList<>();
        for (Leg l : legs)
            (l.type() == EntryType.DEBIT ? debits : credits)
                    .add(new Object[]{l.accountId(), l.currency(), l.amountSubunits()});

        // Same currency first, so a well-formed transaction never produces a cross-currency
        // transfer just because of the order its legs happened to arrive in.
        match(out, debits, credits, true);
        match(out, debits, credits, false);

        // Whatever still holds an amount had no counterparty at all. A TigerBeetle transfer is
        // balanced by construction -- one amount, debited from one account and credited to
        // another -- so an unbalanced transaction has no representation here, and sending the
        // part that does pair would apply a different transaction from the one submitted.
        List<String> unpaired = new ArrayList<>();
        for (Object[] d : debits)
            if ((long) d[2] > 0) unpaired.add("debit " + d[0] + " " + d[2] + " " + d[1]);
        for (Object[] c : credits)
            if ((long) c[2] > 0) unpaired.add("credit " + c[0] + " " + c[2] + " " + c[1]);
        return new Paired(out, unpaired);
    }

    /** Transfers, plus any leg that could not be paired. */
    record Paired(List<Pair> transfers, List<String> unpaired) { }

    private static void match(List<Pair> out, List<Object[]> debits, List<Object[]> credits,
                              boolean sameCurrencyOnly) {
        for (Object[] d : debits) {
            if ((long) d[2] == 0) continue;
            for (Object[] c : credits) {
                if ((long) c[2] == 0) continue;
                if (sameCurrencyOnly && !d[1].equals(c[1])) continue;
                long move = Math.min((long) d[2], (long) c[2]);
                out.add(new Pair((String) d[0], (String) d[1],
                        (String) c[0], (String) c[1], move));
                d[2] = (long) d[2] - move;
                c[2] = (long) c[2] - move;
                if ((long) d[2] == 0) break;
            }
        }
    }

    // ------------------------------------------------------------------ ids

    /** A namespace-salted u128, never zero and never int-max, both of which TigerBeetle refuses. */
    private byte[] id(String of) {
        byte[] h = hash(namespace + "|" + of);
        if (isZero(h)) h[0] = 1;
        return h;
    }

    private static byte[] hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(d, 16);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by every JRE", e);
        }
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String key(byte[] id) { return UInt128.asBigInteger(id).toString(); }
}
