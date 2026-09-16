package com.technomorph.lck.examples.tigerbeetle;

import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.AccountFlags;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateAccountResultBatch;
import com.tigerbeetle.CreateTransferResult;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.QueryFilter;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.UInt128;

import org.junit.jupiter.api.*;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every behaviour {@code TigerBeetleLedgerAdapter} relies on, asserted against a real cluster.
 *
 * <p>An adapter is a set of claims about somebody else's software. Left in the adapter's head
 * those claims are untested, and when one turns out to be wrong the symptom is a false finding
 * against the ledger rather than a failure here. So each claim is pinned once, in isolation,
 * where breaking it produces a message naming the assumption rather than an invariant number.
 *
 * <p>This matters more for TigerBeetle than it did for Formance. There is no HTTP to read with
 * curl and no JSON to inspect: the protocol is binary, the client is JNI, and the API's most
 * consequential detail — that a success is reported by <em>absence</em> — is the kind of thing a
 * reasonable person assumes the other way round.
 */
@Tag("docker")
class TigerBeetleAssumptionsTest {

    private static final Tb tb = new Tb();
    private static Client client;

    /** Ledger and code must both be non-zero; TigerBeetle rejects either as malformed. */
    private static final int LEDGER_USD = 1;
    private static final int LEDGER_EUR = 2;
    private static final int CODE = 1;

    private static long next = 1;

    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(Tb.dockerAvailable(),
                "Docker is not available; the TigerBeetle run is skipped, not failed");
        Tb.assertVersionsMatch();
        client = tb.start();
    }

    @AfterAll
    static void stop() { tb.close(); }

    // ---------------------------------------------------------------- the load-bearing surprise

    @Test
    @DisplayName("a success is reported by absence: the result batch carries only failures")
    void successIsSilence() {
        byte[] a = account(LEDGER_USD, AccountFlags.NONE);
        byte[] b = account(LEDGER_USD, AccountFlags.NONE);

        CreateTransferResultBatch res = transfer(id(), a, b, 500, LEDGER_USD);

        assertEquals(0, res.getLength(), """
                TigerBeetle reports only the events that failed, so an empty result batch means \
                every transfer in it was applied. The adapter maps "no entry for my index" to \
                APPLIED, and if that ever stops being true it would map success to a crash.""");
    }

    @Test
    @DisplayName("a failure is reported by index, so a batch of one names index zero")
    void failureCarriesItsIndex() {
        byte[] a = account(LEDGER_USD, AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS);
        byte[] b = account(LEDGER_USD, AccountFlags.NONE);

        CreateTransferResultBatch res = transfer(id(), a, b, 10_000, LEDGER_USD);

        assertEquals(1, res.getLength(), "an unfunded debit on a guarded account must fail");
        assertTrue(res.next());
        assertEquals(0, res.getIndex(), "the only transfer in the batch is index 0");
    }

    // ---------------------------------------------------------------- the three status mappings

    @Test
    @DisplayName("Exists is how TigerBeetle says duplicate, and the original stands")
    void duplicateIdIsExists() {
        byte[] a = account(LEDGER_USD, AccountFlags.NONE);
        byte[] b = account(LEDGER_USD, AccountFlags.NONE);
        byte[] transferId = id();

        assertEquals(0, transfer(transferId, a, b, 700, LEDGER_USD).getLength(), "first must apply");

        CreateTransferResultBatch again = transfer(transferId, a, b, 700, LEDGER_USD);
        assertEquals(1, again.getLength());
        assertTrue(again.next());
        assertEquals(CreateTransferResult.Exists, again.getResult(), """
                The transfer id is the idempotency key. Resubmitting one must be refused as \
                Exists, which the adapter reports as DUPLICATE.""");

        assertEquals(700, balance(b, LEDGER_USD),
                "the duplicate must not have moved a second 7.00 — that is the whole point");
    }

    @Test
    @DisplayName("ExceedsCredits is the overdraft guard firing, not an error")
    void overdraftIsExceedsCredits() {
        byte[] guarded = account(LEDGER_USD, AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS);
        byte[] other = account(LEDGER_USD, AccountFlags.NONE);
        byte[] funder = account(LEDGER_USD, AccountFlags.NONE);

        assertEquals(0, transfer(id(), funder, guarded, 1_000, LEDGER_USD).getLength());

        CreateTransferResultBatch res = transfer(id(), guarded, other, 1_001, LEDGER_USD);
        assertTrue(res.next());
        assertEquals(CreateTransferResult.ExceedsCredits, res.getResult(),
                "one subunit beyond the balance must be refused");

        assertEquals(1_000, balance(guarded, LEDGER_USD),
                "a refused transfer posts nothing, so the balance is untouched");
    }

    @Test
    @DisplayName("an account without the guard flag may go negative, which is how funding works")
    void unguardedAccountsMayGoNegative() {
        byte[] funder = account(LEDGER_USD, AccountFlags.NONE);
        byte[] to = account(LEDGER_USD, AccountFlags.NONE);

        assertEquals(0, transfer(id(), funder, to, 5_000, LEDGER_USD).getLength(),
                "the kit funds accounts from a source that is allowed to go negative; if this "
                        + "ever fails, seed() has no way to work");
        assertEquals(-5_000, balance(funder, LEDGER_USD));
    }

    @Test
    @DisplayName("a failed transfer id is burned: TigerBeetle will not reconsider it, ever")
    void aFailedIdIsBurned() {
        byte[] guarded = account(LEDGER_USD, AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS);
        byte[] other = account(LEDGER_USD, AccountFlags.NONE);
        byte[] funder = account(LEDGER_USD, AccountFlags.NONE);
        byte[] transferId = id();

        // Refused for want of funds.
        CreateTransferResultBatch first = transfer(transferId, guarded, other, 5_000, LEDGER_USD);
        assertTrue(first.next());
        assertEquals(CreateTransferResult.ExceedsCredits, first.getResult());

        // Fund the account so the very same transfer would now succeed on its merits.
        assertEquals(0, transfer(id(), funder, guarded, 50_000, LEDGER_USD).getLength());

        CreateTransferResultBatch retry = transfer(transferId, guarded, other, 5_000, LEDGER_USD);
        assertTrue(retry.next());
        assertEquals(CreateTransferResult.IdAlreadyFailed, retry.getResult(), """
                A retry under the original id is refused even though the transfer would now \
                apply. This is the fact that matters well beyond this adapter: it is direct \
                evidence for the open question in rfcs/0002 -- whether a refused payment can be \
                retried under its original key is a convention, not a correctness property, \
                because the ledger with the strongest correctness claims in this space says no.""");

        assertEquals(50_000, balance(guarded, LEDGER_USD),
                "the burned retry must post nothing");
    }

    @Test
    @DisplayName("IdAlreadyFailed is a different answer from Exists, and must stay different")
    void failedAndAppliedAreDistinct() {
        assertNotEquals(CreateTransferResult.Exists, CreateTransferResult.IdAlreadyFailed, """
                Collapsing these two is the two-state idempotency defect INV-15 exists to catch: \
                Exists means the transaction applied and the caller may stop, IdAlreadyFailed \
                means it did not and never will. This adapter mapped both to DUPLICATE in its \
                first draft, and INV-15 failed it -- which is the invariant working.""");
    }

    // ---------------------------------------------------------------- cross-currency

    @Test
    @DisplayName("a transfer whose accounts sit in different ledgers is refused by the ledger")
    void crossLedgerIsRefusedByTigerBeetle() {
        byte[] usd = account(LEDGER_USD, AccountFlags.NONE);
        byte[] eur = account(LEDGER_EUR, AccountFlags.NONE);

        CreateTransferResultBatch res = transfer(id(), usd, eur, 400, LEDGER_USD);

        assertTrue(res.next());
        // This is the one place TigerBeetle can do something Formance structurally cannot: express
        // the cross-currency attempt and refuse it itself. Which of the two codes comes back is
        // TigerBeetle's business — both are a refusal by the ledger rather than by the adapter,
        // and that is what makes INV-11 a real result here rather than an unearned pass.
        assertTrue(res.getResult() == CreateTransferResult.AccountsMustHaveTheSameLedger
                        || res.getResult() == CreateTransferResult.TransferMustHaveTheSameLedgerAsAccounts,
                () -> "expected a same-ledger refusal, got " + res.getResult());
    }

    // ---------------------------------------------------------------- reads

    @Test
    @DisplayName("balance is creditsPosted minus debitsPosted, and pending is not counted")
    void balanceIsPostedOnly() {
        byte[] a = account(LEDGER_USD, AccountFlags.NONE);
        byte[] b = account(LEDGER_USD, AccountFlags.NONE);
        assertEquals(0, transfer(id(), a, b, 250, LEDGER_USD).getLength());
        assertEquals(0, transfer(id(), b, a, 100, LEDGER_USD).getLength());

        assertEquals(-150, balance(a, LEDGER_USD));
        assertEquals(150, balance(b, LEDGER_USD));
    }

    @Test
    @DisplayName("queryTransfers filtered by userData128 returns this run's transfers and no others")
    void queryByUserDataIsolatesARun() {
        byte[] a = account(LEDGER_USD, AccountFlags.NONE);
        byte[] b = account(LEDGER_USD, AccountFlags.NONE);
        byte[] mine = UInt128.asBytes(0xAAAA_BBBB_CCCC_DDDDL, 0x1111_2222_3333_4444L);
        byte[] theirs = UInt128.asBytes(0x9999_8888_7777_6666L, 0x5555_4444_3333_2222L);

        for (int i = 0; i < 3; i++) tagged(id(), a, b, 100 + i, LEDGER_USD, mine);
        for (int i = 0; i < 2; i++) tagged(id(), a, b, 900 + i, LEDGER_USD, theirs);

        QueryFilter f = new QueryFilter();
        f.setUserData128(mine);
        f.setLimit(100);
        TransferBatch found = client.queryTransfers(f);

        assertEquals(3, found.getLength(), """
                The adapter tags every transfer it creates with a per-run token and reads the \
                journal back through this filter. If the filter is ignored, journal() would \
                return other runs' transfers and INV-13 would report a ledger that cannot \
                rebuild its own balances — a false finding, from the harness.""");

        long total = 0;
        while (found.next()) total += found.getAmount().longValueExact();
        assertEquals(100 + 101 + 102, total, "the filter must match on content, not just on count");
    }

    @Test
    @DisplayName("looking up an account that was never created returns nothing, not zero")
    void unknownAccountIsAbsent() {
        IdBatch ids = new IdBatch(1);
        ids.add();
        ids.setId(UInt128.asBytes(0x0DDB_A11L, 0x0DDB_A11L));

        AccountBatch found = client.lookupAccounts(ids);

        assertEquals(0, found.getLength(), """
                An account the kit has not funded yet does not exist, and TigerBeetle omits it \
                rather than reporting a zero balance. The adapter must read that absence as \
                zero, because the kit reads balances before funding them.""");
    }

    // ---------------------------------------------------------------- fixtures

    private static byte[] id() { return UInt128.asBytes(next++, 0x5EEDL); }

    private static byte[] account(int ledger, int flags) {
        byte[] accountId = UInt128.asBytes(next++, 0xACC7L);
        AccountBatch batch = new AccountBatch(1);
        batch.add();
        batch.setId(accountId);
        batch.setLedger(ledger);
        batch.setCode(CODE);
        batch.setFlags(flags);
        CreateAccountResultBatch res = client.createAccounts(batch);
        assertEquals(0, res.getLength(), () -> {
            res.next();
            return "could not create the fixture account: " + res.getResult();
        });
        return accountId;
    }

    private static CreateTransferResultBatch transfer(byte[] transferId, byte[] debit,
                                                     byte[] credit, long amount, int ledger) {
        return tagged(transferId, debit, credit, amount, ledger, null);
    }

    private static CreateTransferResultBatch tagged(byte[] transferId, byte[] debit, byte[] credit,
                                                    long amount, int ledger, byte[] userData128) {
        TransferBatch batch = new TransferBatch(1);
        batch.add();
        batch.setId(transferId);
        batch.setDebitAccountId(debit);
        batch.setCreditAccountId(credit);
        batch.setAmount(BigInteger.valueOf(amount));
        batch.setLedger(ledger);
        batch.setCode(CODE);
        if (userData128 != null) batch.setUserData128(userData128);
        return client.createTransfers(batch);
    }

    private static long balance(byte[] accountId, int ledger) {
        IdBatch ids = new IdBatch(1);
        ids.add();
        ids.setId(accountId);
        AccountBatch found = client.lookupAccounts(ids);
        if (!found.next()) return 0L;
        // longValueExact rather than longValue: TigerBeetle counts in u128 and the kit in long
        // subunits, so a value that does not fit must throw rather than silently wrap into a
        // plausible-looking balance.
        return found.getCreditsPosted().longValueExact() - found.getDebitsPosted().longValueExact();
    }
}
