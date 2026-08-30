package com.technomorph.lck.spi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * The whole contract. Records only, java.base only, no dependencies.
 *
 * Zero transitive dependencies is a deliberate architectural constraint, not an
 * accident: a test tool with 200 jars on its classpath does not clear third-party
 * review at a regulated firm, and it cannot be retrofitted later.
 */
public final class Model {
    private Model() {}

    public enum EntryType { DEBIT, CREDIT }

    public enum PostStatus { APPLIED, DUPLICATE, REJECTED }


    /** Money is a long count of currency minor units. Never double, never BigDecimal on the hot path. */
    public record Leg(String accountId, EntryType type, long amountSubunits, String currency) {
        public Leg {
            if (amountSubunits <= 0)
                throw new IllegalArgumentException("legs are unsigned; direction is carried by EntryType");
            Objects.requireNonNull(accountId);
            Objects.requireNonNull(currency);
        }
        public static Leg debit(String acct, long amt)  { return new Leg(acct, EntryType.DEBIT,  amt, "USD"); }
        public static Leg credit(String acct, long amt) { return new Leg(acct, EntryType.CREDIT, amt, "USD"); }
        public long signed() { return type == EntryType.CREDIT ? amountSubunits : -amountSubunits; }
    }

    public record Transaction(String idempotencyKey, List<Leg> legs, String transactionId,
                              boolean allowOverdraft, Map<String, String> metadata) {

        public static Transaction transfer(String src, String dst, long amt, String key) {
            return transfer(src, dst, amt, key, false);
        }

        public static Transaction transfer(String src, String dst, long amt, String key, boolean overdraft) {
            return new Transaction(key,
                    List.of(Leg.debit(src, amt), Leg.credit(dst, amt)),
                    UUID.randomUUID().toString(), overdraft, Map.of());
        }

        public Map<String, Long> netByCurrency() {
            Map<String, Long> out = new HashMap<>();
            for (Leg l : legs) out.merge(l.currency(), l.signed(), Long::sum);
            return out;
        }
    }

    public record JournalEntry(String entryId, String transactionId, String accountId,
                               EntryType type, long amountSubunits, String currency,
                               long sequence, long accountSequence,
                               String prevHash, String entryHash) {
        public long signed() { return type == EntryType.CREDIT ? amountSubunits : -amountSubunits; }
    }

    public record PostResult(PostStatus status, String transactionId, String reason) {
        public static PostResult applied(String id)          { return new PostResult(PostStatus.APPLIED, id, null); }
        public static PostResult duplicate(String id)        { return new PostResult(PostStatus.DUPLICATE, id, "idempotency key already applied"); }
        public static PostResult rejected(String id, String r){ return new PostResult(PostStatus.REJECTED, id, r); }
    }

    public static String hashEntry(String prevHash, String txnId, String acct, String type,
                                   long amount, String ccy, long seq) {
        String payload = String.join("|", prevHash, txnId, acct, type,
                Long.toString(amount), ccy, Long.toString(seq));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
