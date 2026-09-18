package com.technomorph.lck.spi;

/**
 * Thrown by {@link LedgerAdapter#post} when the ledger's data model cannot express the submitted
 * transaction at all, so the ledger was never asked and has no opinion to report.
 *
 * <p>This is a third answer, and the suite needed it. The other two are that the ledger applied
 * the transaction or that it refused it — both of which are findings about the ledger. "I could
 * not put this question to it" is a fact about the adapter, and reporting it as either of the
 * other two says something untrue.
 *
 * <p>Two real cases made the gap unavoidable, on two different invariants:
 *
 * <ul>
 *   <li><b>Formance and INV-11.</b> A posting is single-asset and balanced by construction, so a
 *       transaction whose legs do not balance within a currency has no representation. The
 *       adapter had to refuse it locally, and INV-11 then reported a pass the ledger had not
 *       earned.</li>
 *   <li><b>TigerBeetle and INV-01.</b> A transfer moves one amount from one account to another,
 *       so an unbalanced transaction cannot be submitted either. Same unearned pass, different
 *       invariant.</li>
 * </ul>
 *
 * <p>In both cases the outcome happened to be right — neither ledger can create money that way —
 * but the mechanism was not what the row implied, and a reader who checked would have found the
 * table overstating. An unearned pass is a quieter problem than a false failure and a worse one,
 * because nothing prompts anybody to look.
 *
 * <p><b>What the runner does with it:</b> the invariant is reported as not applicable, with this
 * exception's message as the reason. Not a pass, not a failure — a property left unmeasured,
 * which is the same standing as an invariant needing a {@link Capability} the adapter has not
 * declared.
 *
 * <p><b>When not to throw it.</b> Only when the transaction is genuinely inexpressible. A ledger
 * that <em>could</em> accept the submission and chooses to refuse it is a rejection: return
 * {@link Model.PostResult#rejected} and let the finding stand. Reaching for this to quieten an
 * invariant that is failing legitimately converts a finding into silence, which is the one thing
 * the suite must never do on the adapter's say-so. The message should name what could not be
 * represented and why, in terms a reader of the report can weigh.
 *
 * <pre>{@code
 * if (!unpaired.isEmpty())
 *     throw new NotRepresentable("a transfer is balanced by construction, and these legs leave "
 *             + unpaired + " with no counterparty");
 * }</pre>
 */
public class NotRepresentable extends Exception {

    private static final long serialVersionUID = 1L;

    public NotRepresentable(String message) {
        super(message);
    }

    public NotRepresentable(String message, Throwable cause) {
        super(message, cause);
    }
}
