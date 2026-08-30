# HTTP adapter contract

Four test-only endpoints make any ledger, in any language, testable by the same suite.
Roughly 80 lines to implement.

## Safety first

Mount these behind a build profile or feature flag that **cannot exist in a production
build**, and have the handler refuse to start unless the datasource name matches a scratch
pattern. The suite writes, and `reset()` truncates.

## Endpoints

### `POST /_lck/reset`
Empty all state. Returns `200` with any body.

### `POST /_lck/post`
```json
{
  "idempotencyKey": "race-key",
  "transactionId": "7f3a...",
  "allowOverdraft": false,
  "legs": [
    {"accountId": "acct:a", "type": "DEBIT",  "amountSubunits": 2500, "currency": "USD"},
    {"accountId": "acct:b", "type": "CREDIT", "amountSubunits": 2500, "currency": "USD"}
  ]
}
```
```json
{"status": "APPLIED", "transactionId": "7f3a...", "reason": null}
```
`status` is `APPLIED`, `DUPLICATE` or `REJECTED`. A business rejection is **`REJECTED`
with 200**, never a 4xx and never an exception — the suite cannot distinguish a thrown
rejection from a crash.

### `GET /_lck/balance?account=acct:a&currency=USD`
```json
{"subunits": 12345}
```
Minor units. `12345` means £123.45. Returning `123` is the most common adapter bug and the
TCK will catch it.

### `GET /_lck/journal`
```json
[
  {"entryId":"...","transactionId":"...","accountId":"acct:a","type":"DEBIT",
   "amountSubunits":2500,"currency":"USD","sequence":1,"accountSequence":1,
   "prevHash":"","entryHash":""}
]
```
Commit order. Return a snapshot, not a live view. `prevHash` and `entryHash` may be empty
unless you declare `HASH_CHAIN`.

## Running it

```bash
lck run --adapter http://ledger-test.internal:8080 \
        --capabilities OVERDRAFT_GUARD,REPLAY \
        --seed 42
```

Declare only the capabilities your ledger genuinely has. Under-declaring is safe;
over-declaring produces false findings.

## Connection pool sizing

The suite fires up to 500 simultaneous requests. If your test service is pool-starved, a
correct ledger will look serial and INV-06 may pass for the wrong reason. Size the pool
above the concurrency you run with.
