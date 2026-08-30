package com.technomorph.lck.spi;

import com.technomorph.lck.spi.Model.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The seam. Five methods, and everything the suite asserts is derivable from them.
 *
 * Loaded by class name — {@code --adapter com.acme.AcmeLedgerAdapter}, or
 * {@code @LedgerConformance(adapter = AcmeLedgerAdapter.class)} — so a firm can keep their
 * adapter in their own jar without forking the kit, which matters when it contains internal
 * account naming they will never let out of the building. A public no-arg constructor is the
 * only requirement. ({@link java.util.ServiceLoader} discovery is not implemented.)
 */
public interface LedgerAdapter {

    String name();

    default Set<Capability> capabilities() { return EnumSet.noneOf(Capability.class); }

    /** Empty state. Called before every invariant. Point this at a scratch schema. */
    void reset() throws Exception;

    /** Commit atomically or reject. Must be idempotent on the key, including concurrently. */
    PostResult post(Transaction txn) throws Exception;

    /** Balance in minor units, as the read path reports it. */
    long balance(String accountId, String currency) throws Exception;

    /** The append-only journal, in commit order. */
    List<JournalEntry> journal() throws Exception;

    default long balance(String accountId) throws Exception { return balance(accountId, "USD"); }

    default boolean supports(Capability c) { return capabilities().contains(c); }

    /** Rebuild from the log alone: the recovery path, and INV-13. */
    default long replayBalance(String accountId, String currency) throws Exception {
        return journal().stream()
                .filter(e -> e.accountId().equals(accountId) && e.currency().equals(currency))
                .mapToLong(JournalEntry::signed).sum();
    }

    default void seed(String accountId, long amount) throws Exception {
        post(new Transaction("seed:" + accountId + ":" + UUID.randomUUID(),
                List.of(Leg.debit("external:funding", amount), Leg.credit(accountId, amount)),
                UUID.randomUUID().toString(), true, java.util.Map.of()));
    }
}
