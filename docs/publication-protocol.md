# Publication & confidentiality protocol

This governs paid conformance engagements run by Technomorph Corporation.

**It does not apply to the tool.** The kit is Apache-2.0. Run it, fork it, and publish
whatever you find without asking anyone.

It is published here because a protocol you cannot read is not a protocol. It is fixed,
identical for every engagement, and not negotiated case by case — which is the only thing
that makes it worth anything.

---

## Why publication exists at all

Independent analysis is only worth something if it can be published. A report that can be
suppressed by whoever paid for it is marketing, not evidence. The value you get from an
independent run is the same value the next reader gets from yours.

## Always confidential

Never published, in any form:

- **Client identity**, unless the client asks in writing to be named
- Source code, schemas, credentials, infrastructure detail, architecture diagrams
- Transaction data, account data, customer data, volume or revenue figures
- Anything permitting **identification by elimination** — sector plus headcount plus
  region plus funding stage identifies a company even with no name attached
- Commercial terms

## What may be published

Only after the process below, and only in this form:

- The **failure class** and the invariant that caught it
- A **minimal reproduction written against the reference implementation**, never against
  the client's code — the bug shape, reconstructed
- The **fix pattern**, generalised
- Coarse context only: *"a payments platform"*, *"a brokerage"*

If a finding cannot be described without identifying the client, it is not published. That
constraint costs real material and it is not negotiable.

## Process

1. **During the engagement** — nothing is published or discussed publicly, including obliquely.
2. **On delivery** — the client receives the full report. Publication is not raised yet.
3. **Remediation window, 90 days minimum**, extended on request while a fix is genuinely in
   progress. If a finding is actively moving money incorrectly, the window runs until it is
   closed, however long that takes.
4. **Draft to client, 30 days before publication.** The client may:
   - Redact anything listed under *Always confidential* — **binding, no reason required**
   - Correct factual errors — **binding**
   - Attach a response of any length, published unedited alongside the report
   - Request a longer window — granted by default the first time
5. **Publication** — findings only, with the client's response if given.
6. **Veto** — the client may veto publication of any individual finding, in writing, once
   per engagement, without giving a reason.

## What will not happen

- No finding published that the client has not seen first
- No publication during an open remediation
- No trading of favourable framing for fee, renewal, referral or access
- **No certification or endorsement** of a system as "correct". A run reports which
  invariants held on the date it ran; that is all a run can honestly support
- No use of one client's findings to sell to a named competitor

## Clean runs

A run holding every BLOCKER-severity invariant may be published with permission, and with
the client's name if they want the credit. Those are among the most useful reports in the
corpus. Published as evidence about the run, never as an endorsement of the product.

## Scope and liability

A conformance run tests the properties in the suite, on the system as configured, on the
date it ran. It is not an audit, not an assurance engagement, not a regulatory opinion, and
not a warranty that the system is correct in any broader sense.

*This document describes intended practice. The binding terms are those in the engagement
contract.*
