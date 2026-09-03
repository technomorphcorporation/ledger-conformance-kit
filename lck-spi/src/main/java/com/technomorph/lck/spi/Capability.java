package com.technomorph.lck.spi;

/**
 * Optional features an adapter declares.
 *
 * <p>Invariants requiring a capability the adapter does not declare are reported as
 * <b>not applicable</b>, never as a failure. The kit has opinions about correctness,
 * not about your feature set — a ledger with no overdraft concept is not broken for
 * lacking one, and telling a team otherwise is how a tool gets dismissed.
 *
 * <p>Adding a value here is a MINOR release. Removing one is MAJOR — which is the reason
 * every value below is exercised by an invariant today, and none is reserved for later. A
 * placeholder buys nothing: because adding is MINOR, a capability can be introduced at the
 * moment the invariant that needs it exists. Shipping one early only publishes a promise with
 * no test behind it, and makes it expensive to withdraw if the design changes.
 */
public enum Capability {

    /** Journal entries carry a verifiable prev/entry hash chain. Enables INV-03. */
    HASH_CHAIN,

    /** The ledger enforces a sufficient-funds rule on debit. Enables INV-09. */
    OVERDRAFT_GUARD,

    /** Reversals are booked as compensating entries rather than deletions. Enables INV-12. */
    COMPENSATION,

    /** Balances can be rebuilt from the journal alone. Enables INV-13. */
    REPLAY
}
