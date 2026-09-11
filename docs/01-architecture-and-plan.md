# Mini Payment Router Simulator — Architecture & Development Plan

> **Step 1 deliverable. No application code yet.**
> This document is the contract we build against for Steps 2–10.
> If a later step contradicts this document, we update this document.

---

## 0. Workspace inspection (done before designing)

| Check | Result |
|---|---|
| Project directory | **Empty** — clean slate, nothing to preserve |
| Git repository | **No** (`git init` not yet run — your call, per your rules I will not run git commands) |
| Java | **21** (Zulu OpenJDK 21+35) — LTS, supported by Spring Boot 3.x |
| Maven | **Not installed** → we will use the Maven Wrapper (`mvnw`) |
| Node / npm | **20.19.1 / 9.9.4** — fine for React + Vite |
| Docker | **29.6.1**, Compose **v5.3.0** (`docker compose`, v2 syntax) |

Nothing existed to inspect, so every decision below is greenfield.

---

## 1. The assignment, restated in my own words

> Build a small system that sits **between** two make-believe mobile-money providers and moves
> money between them.
>
> The system must be able to answer **"what will this cost?"** (a *quote*) and then
> **"do it"** (a *transfer*). It must reject nonsense input politely, keep a log file of what it did,
> have a web page a human can drive it from, and start up as several cooperating containers
> with one command. It must be explainable.

That is it. Everything else is scaffolding around those two verbs.

---

## 2. Mandatory vs. optional

### Mandatory (from the assignment — non-negotiable)
| # | Requirement | Where it lands |
|---|---|---|
| M1 | Quote API between dummy DFSPs | Step 3 (+ real DFSP calls in Step 6) |
| M2 | Transfer API between dummy DFSPs | Step 6 |
| M3 | Basic request validation | Step 4 |
| M4 | Logging **to a file** | Step 7 |
| M5 | Docker **multi-service** setup | Step 9 |
| M6 | Frontend **and** backend | Steps 2–7 (backend), Step 8 (frontend) |
| M7 | README/docs: architecture, setup, inner workings | Step 10 (this doc feeds it) |
| M8 | Backend = Java + Spring Boot; Frontend = React; DFSPs = dummy Spring Boot services | throughout |
| M9 | **No database** unless explicitly approved | in-memory everywhere |
| M10 | **No real payment provider integration** | dummy DFSPs only |

### Optional — our design choices (we pick, and we must justify)
- How many DFSPs (we choose **2**) and whether they share a codebase (we choose **separate**).
- Whether DFSP APIs are identical or different (we choose **different** — see D-4).
- Who pays the fee (we choose **sender-pays**).
- How the router decides which DFSP to call (we choose **account-identifier routing** — see D-14).
- HTTP client (`RestClient`), build tool (Maven), frontend tooling (Vite), error JSON shape,
  status-code mapping, correlation IDs, test strategy, folder layout, port numbers.

### Deliberately out of scope
Authentication, TLS, rate limiting, retries/circuit breakers, persistence, distributed transactions,
message queues, service discovery, Kubernetes, monitoring stacks.

---

## 3. What is a DFSP?

**DFSP = Digital Financial Service Provider.** An institution that holds customer money digitally
and lets customers move it: bKash, Nagad, Rocket, Upay, a bank's wallet arm.

Three properties matter for us:

1. **It is a closed ledger.** DFSP-A knows DFSP-A's customers and DFSP-A's balances. It has no idea
   who a DFSP-B customer is and cannot touch a DFSP-B balance.
2. **It has its own API and its own fee rules.** bKash's API is not Nagad's API. Field names,
   auth, fee formulas, error codes — all different.
3. **It is the only thing allowed to debit or credit its own accounts.** The router *asks*;
   the DFSP *decides*.

In this project a DFSP is a small Spring Boot service holding a `Map<String, Account>` in memory.
`dfsp-a` = **"AlphaPay"** (bKash analogy). `dfsp-b` = **"BetaCash"** (Nagad analogy).
They are dummies. There is no real bKash/Nagad code anywhere.

---

## 4. What is the Payment Router?

Suppose there are 5 DFSPs and each wants to send money to each other. Point-to-point,
that is **N × (N−1) = 20 integrations**. Every new provider forces everyone else to do work.
This does not scale — it is the exact reason interoperability hubs exist.

Put one thing in the middle and every DFSP integrates **once**: **N integrations**.

```
   point-to-point (N×(N-1))            hub / router (N)
        A ── B                             A     B
        │╲ ╱│                               ╲   ╱
        │ ╳ │                                ╲ ╱
        │╱ ╲│                          C ──── R ──── D
        C ── D                                │
                                              E
```

**The Payment Router in this project is that hub.** Its responsibilities:

| It DOES | It does NOT |
|---|---|
| Resolve which DFSP owns each account | Hold customer money |
| Ask DFSPs for their fees, assemble a total price | Keep a ledger of balances |
| Issue and expire quotes | Decide whether a customer has funds (the DFSP decides) |
| Orchestrate debit → credit in the right order | Talk to any real bank or PSP |
| Translate between each DFSP's dialect and one common model | Store data permanently (no DB) |
| Validate requests, standardise errors, log the whole journey | |

**The router is a coordinator and a translator, not an account holder.** That single sentence is
the best one-line answer to "what does your project do?".

(Real-world equivalents: Mojaloop's *Switch*, a card network, an ACH hub, Bangladesh's NPSB.)

---

## 5. Quote vs Transfer

| | **QUOTE** | **TRANSFER** |
|---|---|---|
| Nature | A **question** | A **command** |
| Money moves? | **No** | **Yes** |
| Idempotent / repeatable? | Yes — ask 100 times, harmless | No — must happen exactly once |
| Produces | `quoteId` + fee breakdown + `expiresAt` | `transferId` + final status |
| HTTP semantics | `POST` (it creates a server-side quote record) → `200 OK` | `POST` → `201 Created` |
| Lifetime | short (we use **5 minutes**) | permanent record |
| Failure cost | zero — just retry | money may be in limbo |

### Why are these two separate APIs?

1. **The user must see the price before committing.** Showing a fee *after* debiting is a
   regulatory and trust failure everywhere in the world.
2. **Only the DFSP knows its own fee rules**, and those rules depend on the amount, the provider
   pair, sometimes the channel. So the price must be *computed*, which means it must be *asked for*.
3. **It locks the price.** The transfer references a `quoteId`, so the client cannot be quoted
   23.50 and charged 40.00.
4. **It gives idempotency for free.** A `quoteId` may be spent **once**. Double-clicking "Confirm"
   cannot send money twice — the second attempt hits `QUOTE_ALREADY_USED`.
5. **It separates the safe operation from the dangerous one**, which means we can be relaxed about
   retrying quotes and very strict about retrying transfers.

This two-phase split is exactly how Mojaloop, card networks, and FX platforms work. It is not
something we invented to look clever.

---

## 6. Complete business flow — 1000 BDT from DFSP-A to DFSP-B

**Cast:** Rahim holds account `A-1001` at AlphaPay (DFSP-A), balance 5,000.
Karim holds account `B-2001` at BetaCash (DFSP-B), balance 200.
Rahim wants Karim to receive **1000.00 BDT**.

### Phase 1 — QUOTE (nothing moves)

```
Browser                Router                      DFSP-A                 DFSP-B
   │                      │                           │                      │
   │ POST /api/quotes     │                           │                      │
   │ {A-1001, B-2001,     │                           │                      │
   │  1000.00, BDT}       │                           │                      │
   │─────────────────────>│                           │                      │
   │                      │ 1. structural validation  │                      │
   │                      │    (amount>0, currency…)  │                      │
   │                      │ 2. ROUTING DECISION:      │                      │
   │                      │    "A-…" -> DFSP_A        │                      │
   │                      │    "B-…" -> DFSP_B        │                      │
   │                      │ 3. ask payer's DFSP       │                      │
   │                      │    for its fee            │                      │
   │                      │──────────────────────────>│                      │
   │                      │  POST /alphapay/quote     │                      │
   │                      │<──────────────────────────│                      │
   │                      │  {fee: 18.50, ok: true}   │                      │
   │                      │ 4. check payee can receive                       │
   │                      │────────────────────────────────────────────────> │
   │                      │  POST /betacash/v1/party-check                   │
   │                      │<──────────────────────────────────────────────── │
   │                      │  {exists: true, name: "Karim"}                   │
   │                      │ 5. add router fee 5.00    │                      │
   │                      │ 6. build Quote, store     │                      │
   │                      │    in memory, expiry +5m  │                      │
   │ 200 OK               │                           │                      │
   │<─────────────────────│                           │                      │
   │ {quoteId, dfspFee 18.50, routerFee 5.00,         │                      │
   │  totalFee 23.50, payerDebit 1023.50,             │                      │
   │  payeeReceive 1000.00, expiresAt}                │                      │
```

Browser shows Rahim: *"You will pay **1,023.50**. Karim receives **1,000.00**. Fee **23.50**.
This quote expires in 5:00."* Balances are still 5,000 and 200. **Nothing has moved.**

### Phase 2 — TRANSFER (money moves)

```
Browser                Router                      DFSP-A                 DFSP-B
   │ POST /api/transfers  │                           │                      │
   │ {quoteId}            │                           │                      │
   │─────────────────────>│                           │                      │
   │                      │ 1. look up quote          │                      │
   │                      │ 2. exists? not expired?   │                      │
   │                      │    not already used?      │                      │
   │                      │ 3. mark quote CONSUMED    │                      │
   │                      │ 4. DEBIT payer 1023.50    │                      │
   │                      │──────────────────────────>│  balance 5000 -> 3976.50
   │                      │<──────────────────────────│                      │
   │                      │  {status: SUCCESS}        │                      │
   │                      │ 5. CREDIT payee 1000.00                          │
   │                      │────────────────────────────────────────────────> │ 200 -> 1200.00
   │                      │<──────────────────────────────────────────────── │
   │                      │  {status: SUCCESS}                               │
   │                      │ 6. record Transfer COMPLETED                     │
   │ 201 Created          │                           │                      │
   │<─────────────────────│                           │                      │
   │ {transferId, status COMPLETED, completedAt}      │                      │
```

Final state: Rahim **3,976.50**, Karim **1,200.00**, router earned **5.00**, AlphaPay earned **18.50**.
Arithmetic check: 5000 − 1023.50 = 3976.50 ✓ and 1023.50 = 1000 + 18.50 + 5.00 ✓

### The unhappy paths (each one becomes a test case)

| What goes wrong | Where it is caught | Result |
|---|---|---|
| `amount` = −5 or missing | Bean Validation (Step 4) | `400 VALIDATION_FAILED`, no calls made |
| Account prefix unknown (`Z-9`) | Router routing/registry | `400 UNKNOWN_DFSP` |
| Payer and payee on the same DFSP | Router business rule | `400 SAME_DFSP_TRANSFER` |
| Payee account does not exist | DFSP-B during quote | `404 PAYEE_NOT_FOUND` |
| Quote used after 5 min | Router, on transfer | `409 QUOTE_EXPIRED` |
| Quote reused (double-click) | Router, on transfer | `409 QUOTE_ALREADY_USED` |
| Payer has insufficient funds | DFSP-A during debit | `422 REJECTED`, **no money moved** |
| DFSP-B down **after** debit succeeded | Router | reversal → `REJECTED`; if reversal fails → `REQUIRES_RECONCILIATION` |

---

## 7. Architecture — and an evaluation of your proposed shape

Your sketch:

```
React Frontend  ->  Payment Router (Spring Boot)  ->  DFSP-A
                                                 ->  DFSP-B
```

**Verdict: correct, and it is the right shape.** Not because it is the only option, but because
it has three properties that matter:

1. **The router is the only thing the frontend talks to.** One base URL, one error contract,
   one place to add logging/validation. If the browser called DFSPs directly there would be no
   router and no project.
2. **The arrows point outward from the router.** The router is the *client* of the DFSPs; the
   DFSPs never call back. That makes the whole system a simple request/response tree with no
   callbacks, no webhooks, no async correlation — a huge simplification, and legitimate for a
   synchronous quote/transfer flow.
3. **It matches the real topology** of a payment switch, so every explanation transfers to the
   real world.

**What your sketch is missing**, and what I am adding:

- **Where state lives.** Router holds quotes + transfers in memory; each DFSP holds accounts in
  memory. Worth drawing, because "who owns the money" is the core of the design.
- **The log file and its volume.** It is requirement M4; it deserves a box.
- **nginx.** The React app is not a running Node server in production — it is static files served
  by nginx. People miss this and it is a good interview detail.
- **The Docker network boundary**, which is what makes `http://dfsp-a:8081` resolve.

### The corrected, complete diagram

```
 ┌─────────────────────────────────────── docker network: payment-net ───────────────────────────────────────┐
 │                                                                                                            │
 │   ┌──────────────────────────┐                                                                             │
 │   │  frontend                │   React 18 + Vite, built to static files,                                   │
 │   │  nginx :80  ->  host 3000│   served by nginx                                                           │
 │   └────────────┬─────────────┘                                                                             │
 │                │ JSON / HTTP   POST /api/quotes , POST /api/transfers                                      │
 │                v                                                                                           │
 │   ┌──────────────────────────────────────────────────────┐                                                 │
 │   │  payment-router        :8080                         │                                                 │
 │   │  ────────────────────────────────────────────────    │                                                 │
 │   │   controller/   HTTP in/out, @Valid, status codes    │                                                 │
 │   │        │                                             │                                                 │
 │   │        v                                             │                                                 │
 │   │   service/      fees, quote lifecycle, orchestration │      ┌───────────────────────────┐              │
 │   │        │                                             │      │ IN-MEMORY STATE           │              │
 │   │        v                                             │─────>│  Map<String,Quote>        │              │
 │   │   connector/    DfspConnector (interface)            │      │  Map<String,Transfer>     │              │
 │   │        ├── DfspAConnector   ──┐                      │      └───────────────────────────┘              │
 │   │        └── DfspBConnector   ──┼──┐                   │                                                 │
 │   │   config/ · dto/ · model/ · exception/               │                                                 │
 │   └───────────┬──────────────────┼──┼───────────────────-┘                                                 │
 │               │ logback          │  │                                                                      │
 │               v                  │  │  HTTP + X-Correlation-Id                                             │
 │   ┌────────────────────┐         │  │                                                                      │
 │   │ volume ./logs      │         │  │                                                                      │
 │   │ payment-router.log │         │  │                                                                      │
 │   └────────────────────┘         │  │                                                                      │
 │                                  v  v                                                                      │
 │        ┌──────────────────────────┐  ┌──────────────────────────┐                                          │
 │        │ dfsp-a  "AlphaPay" :8081 │  │ dfsp-b  "BetaCash" :8082 │                                          │
 │        │ percentage fee 1.85%     │  │ flat fee 15.00           │                                          │
 │        │ API style: /alphapay/*   │  │ API style: /betacash/v1/*│   <- deliberately DIFFERENT              │
 │        │ Map<String,Account>      │  │ Map<String,Account>      │                                          │
 │        │  A-1001 Rahim  5000.00   │  │  B-2001 Karim   200.00   │                                          │
 │        └──────────────────────────┘  └──────────────────────────┘                                          │
 └────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Services and component responsibilities

### Services (4 containers)

| Service | Tech | Port | Responsibility | Explicitly NOT its job |
|---|---|---|---|---|
| `frontend` | React 18 + Vite + nginx | 3000→80 | Collect input, show fee breakdown, confirm, display result/errors | Any fee maths, any DFSP knowledge, any validation it trusts |
| `payment-router` | Java 21 + Spring Boot 3 | 8080 | Validate, route, quote, orchestrate transfer, log, standardise errors | Holding balances; deciding if funds suffice |
| `dfsp-a` (AlphaPay) | Java 21 + Spring Boot 3 | 8081 | Own accounts; quote its own fee; debit/credit/reverse its own accounts | Knowing DFSP-B exists |
| `dfsp-b` (BetaCash) | Java 21 + Spring Boot 3 | 8082 | Same, with a **different** API shape and fee model | Knowing DFSP-A exists |

### Components inside `payment-router`

| Component | Responsibility | Knows about HTTP? | Knows business rules? |
|---|---|---|---|
| `QuoteController` / `TransferController` | URL → method, `@Valid`, DTO in/out, status code | **Yes** | No |
| `QuoteService` | fee assembly, quote creation, expiry, consumption | No | **Yes** |
| `TransferService` | quote lookup, debit→credit orchestration, compensation | No | **Yes** |
| `DfspConnector` (interface) | the router's *common vocabulary* for any DFSP | — | No |
| `DfspAConnector` / `DfspBConnector` | translate common model ⇄ that DFSP's wire format | Yes (outbound) | No |
| `DfspRegistry` | **routing**: account identifier → which connector | No | Yes (routing rules) |
| `QuoteStore` / `TransferStore` | in-memory `ConcurrentHashMap` persistence | No | No |
| `GlobalExceptionHandler` | exception → `ApiError` + status code, one place | **Yes** | No |
| `CorrelationIdFilter` | assign/propagate `X-Correlation-Id`, put in MDC | Yes | No |
| `RouterProperties` | typed config: fees, DFSP base URLs, timeouts, TTL | No | No |

**The rule that makes this clean:** *only* `controller/`, `connector/`, `exception/` and the filter
are allowed to know that HTTP exists. `service/` and `model/` are plain Java. That is what makes
the business rules unit-testable without a server.

---

## 9. API list

### Router — public API (consumed by React)

| Method | Path | Purpose | Success |
|---|---|---|---|
| `POST` | `/api/quotes` | Create a quote | `200 OK` |
| `GET` | `/api/quotes/{quoteId}` | Fetch a quote (debug/UI refresh) | `200 OK` |
| `POST` | `/api/transfers` | Execute a transfer from a quote | `201 Created` |
| `GET` | `/api/transfers/{transferId}` | Fetch a transfer's status | `200 OK` |
| `GET` | `/api/dfsps` | List configured DFSPs + sample accounts (drives the UI dropdowns) | `200 OK` |
| `GET` | `/actuator/health` | Liveness for Docker healthcheck | `200 OK` |

### DFSP-A "AlphaPay" — internal API (called only by the router)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/alphapay/quote` | fee for an amount + payer validity |
| `POST` | `/alphapay/debit` | debit payer |
| `POST` | `/alphapay/credit` | credit payee |
| `POST` | `/alphapay/reverse` | compensating reversal of a debit |
| `GET`  | `/alphapay/accounts` | list accounts (demo/verification) |

### DFSP-B "BetaCash" — internal API (**intentionally different**)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/betacash/v1/pricing` | fee for an amount |
| `POST` | `/betacash/v1/party-check` | does this account exist? |
| `POST` | `/betacash/v1/ledger/withdraw` | debit |
| `POST` | `/betacash/v1/ledger/deposit` | credit |
| `POST` | `/betacash/v1/ledger/rollback` | reversal |
| `GET`  | `/betacash/v1/accounts` | list accounts |

The divergence is the *point*. It is what the `DfspConnector` interface exists to hide.

---

## 10. Example request/response bodies

### `POST /api/quotes`
```jsonc
// request
{
  "payerAccountId": "A-1001",
  "payeeAccountId": "B-2001",
  "amount": 1000.00,
  "currency": "BDT"
}
```
```jsonc
// 200 OK
{
  "quoteId": "9f2c7b41-3d6e-4a11-88c0-2b5a1e77d0aa",
  "payerAccountId": "A-1001",
  "payeeAccountId": "B-2001",
  "payerDfsp": "DFSP_A",
  "payeeDfsp": "DFSP_B",
  "amount": 1000.00,
  "dfspFee": 18.50,
  "routerFee": 5.00,
  "totalFee": 23.50,
  "payerDebitAmount": 1023.50,
  "payeeReceiveAmount": 1000.00,
  "currency": "BDT",
  "createdAt": "2026-09-11T20:00:00Z",
  "expiresAt": "2026-09-11T20:05:00Z"
}
```

### `POST /api/transfers`
```jsonc
// request
{ "quoteId": "9f2c7b41-3d6e-4a11-88c0-2b5a1e77d0aa" }
```
```jsonc
// 201 Created
{
  "transferId": "4c81a0de-77b2-4f39-9a10-6ee0b2f4c913",
  "quoteId": "9f2c7b41-3d6e-4a11-88c0-2b5a1e77d0aa",
  "status": "COMPLETED",
  "payerAccountId": "A-1001",
  "payeeAccountId": "B-2001",
  "payerDebitAmount": 1023.50,
  "payeeReceiveAmount": 1000.00,
  "totalFee": 23.50,
  "currency": "BDT",
  "completedAt": "2026-09-11T20:01:12Z"
}
```

### Error envelope — **every** non-2xx response has exactly this shape
```jsonc
// 400 Bad Request
{
  "timestamp": "2026-09-11T20:01:12Z",
  "path": "/api/quotes",
  "status": 400,
  "errorCode": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "details": [
    "amount: must be greater than 0",
    "payeeAccountId: must not be blank"
  ],
  "correlationId": "c1f9a7b2"
}
```

### Status-code map

| Situation | Status | `errorCode` |
|---|---|---|
| Malformed / missing / out-of-range field | 400 | `VALIDATION_FAILED` |
| Unknown account prefix | 400 | `UNKNOWN_DFSP` |
| Payer and payee on same DFSP | 400 | `SAME_DFSP_TRANSFER` |
| Quote / transfer id not found | 404 | `QUOTE_NOT_FOUND` / `TRANSFER_NOT_FOUND` |
| Account not found at a DFSP | 404 | `ACCOUNT_NOT_FOUND` |
| Quote expired | 409 | `QUOTE_EXPIRED` |
| Quote already spent | 409 | `QUOTE_ALREADY_USED` |
| DFSP declined (insufficient funds) | 422 | `TRANSFER_REJECTED` |
| DFSP unreachable / timed out | 503 | `DFSP_UNAVAILABLE` |
| Debit done, credit + reversal both failed | 500 | `REQUIRES_RECONCILIATION` |
| Anything unexpected | 500 | `INTERNAL_ERROR` (no stack trace leaked) |

### `TransferStatus` enum
`COMPLETED` · `REJECTED` (declined, no money moved) · `FAILED` (broke before the debit) ·
`REQUIRES_RECONCILIATION` (debit succeeded, credit failed, reversal failed → human intervention)

---

## 11. How the Router decides which DFSP to call — the routing decision

**Decision D-14: the account identifier is the routing key.** The request contains only
`payerAccountId` and `payeeAccountId`. The router resolves each to a DFSP via a `DfspRegistry`
driven by configuration:

```yaml
router:
  dfsps:
    DFSP_A: { name: AlphaPay, prefix: "A-", baseUrl: "http://dfsp-a:8081" }
    DFSP_B: { name: BetaCash, prefix: "B-", baseUrl: "http://dfsp-b:8082" }
```

```
resolve("A-1001") -> prefix "A-" matches DFSP_A -> DfspAConnector
resolve("B-2001") -> prefix "B-" matches DFSP_B -> DfspBConnector
resolve("Z-9999") -> no match                   -> 400 UNKNOWN_DFSP
```

Routing rules, in order:
1. Resolve payer DFSP from `payerAccountId`; unknown → `400 UNKNOWN_DFSP`.
2. Resolve payee DFSP from `payeeAccountId`; unknown → `400 UNKNOWN_DFSP`.
3. If payer DFSP == payee DFSP → `400 SAME_DFSP_TRANSFER` (this is an *inter*-DFSP router;
   an "on-us" transfer never needs the switch).
4. Fee quote comes from the **payer's** DFSP (sender-pays, D-7).
5. Existence of the payee is checked at the **payee's** DFSP.

### Why identifier-based routing rather than "the client tells us the DFSP"?

- **It is what real switches do.** In Mojaloop this is a whole component — the *Account Lookup
  Service*: you give it an MSISDN and it tells you which DFSP holds that party. A phone number
  does not carry a provider name; the switch must know.
- **It makes the router actually route.** If the client names the DFSP, the "router" is just a
  proxy and requirement (1) and (2) become trivial plumbing.
- **It cannot be lied about.** A client claiming `payerDfsp: DFSP_B` for account `A-1001` would
  create an inconsistency we would then have to validate away.
- **It is configuration, not code.** Adding DFSP-C is a YAML entry plus one connector class.

**Alternative considered:** request carries explicit `payerDfsp` / `payeeDfsp` fields. Simpler by
about ten lines, but the router stops routing. Rejected. *If you prefer the explicit version, say so
before Step 3 — it is a one-field change at that point and expensive later.*

> **Simplification I am making knowingly:** a prefix rule is a stand-in for a real lookup table /
> directory service. I will say so in the README rather than pretend a prefix is production-grade.

---

## 12. How quote calculation works

### Configured fee rules
| Party | Rule | Rationale |
|---|---|---|
| DFSP-A "AlphaPay" | **1.85 %** of amount, min 5.00, max 50.00 | percentage model (bKash-like) |
| DFSP-B "BetaCash" | **flat 15.00** | flat model (Nagad-like) |
| Router | **flat 5.00** switching fee | the hub charges for the service |

Different fee *models* — not just different numbers — so the connectors have genuinely different
things to translate.

### The formula (sender-pays, D-7)
```
dfspFee          = fee charged by the PAYER's DFSP for this amount
routerFee        = 5.00
totalFee         = dfspFee + routerFee
payerDebitAmount = amount + totalFee
payeeReceiveAmount = amount
```
All values `BigDecimal`, `setScale(2, RoundingMode.HALF_UP)`, currency fixed to `BDT` in this
simulator (the field exists so the contract is honest, and is validated to equal `BDT`).

### Worked example — 1000.00, A → B
```
dfspFee            = 1000.00 × 0.0185 = 18.50   (≥ 5.00, ≤ 50.00 → 18.50)
routerFee          = 5.00
totalFee           = 23.50
payerDebitAmount   = 1023.50
payeeReceiveAmount = 1000.00
```
Reverse direction, 1000.00, B → A: `dfspFee = 15.00` (flat), `totalFee = 20.00`,
`payerDebitAmount = 1020.00`. **Direction changes the price** — that is the whole reason a quote
API exists rather than a constant in the frontend.

### Steps the router performs
1. Validate structure (Step 4) → 2. Resolve both DFSPs (§11) → 3. Ask payer DFSP for its fee →
4. Ask payee DFSP that the payee exists → 5. Add router fee, compute totals →
6. Create `Quote{id, …, createdAt, expiresAt = now + 5m, status = ACTIVE}` → 7. Store in memory →
8. Return the breakdown.

> In **Step 3** the DFSP calls are stubbed inside the router (the DFSPs do not exist until Step 5).
> **Step 6** replaces the stubs with real HTTP through `DfspConnector`. The *shape* of `QuoteService`
> does not change — that is the payoff of designing against an interface.

### Why a 5-minute expiry?
A quote is a *price promise*. Prices and balances change. An infinite quote would let someone hold a
price forever. Five minutes is long enough for a human to confirm and short enough to be demonstrable
in a test (and it gives us a clean `409 QUOTE_EXPIRED` case).

---

## 13. How transfer works

```
POST /api/transfers { quoteId }
   │
   ├─ 1. load quote            missing?  -> 404 QUOTE_NOT_FOUND
   ├─ 2. check expiry          expired?  -> 409 QUOTE_EXPIRED
   ├─ 3. check status          consumed? -> 409 QUOTE_ALREADY_USED
   ├─ 4. atomically mark quote CONSUMED   <-- the idempotency gate
   │
   ├─ 5. DEBIT payer DFSP (payerDebitAmount)
   │       declined (no funds) -> record REJECTED   -> 422   [no money moved]
   │       unreachable         -> record FAILED     -> 503   [no money moved]
   │
   ├─ 6. CREDIT payee DFSP (payeeReceiveAmount)
   │       success  -> record COMPLETED -> 201
   │       failure  -> 7. COMPENSATE: reverse the debit at payer DFSP
   │                      reversal ok   -> record REJECTED -> 422
   │                      reversal fails-> record REQUIRES_RECONCILIATION -> 500 + ERROR log
   │
   └─ 8. store Transfer, return TransferResponse
```

### Why debit first, then credit?
If you credit first and the debit then fails, you have **created money** — the payee has funds
nobody paid for, and you cannot claw them back. If you debit first and the credit fails, money is
*stuck*, which is bad but recoverable by reversal. **Always fail in the direction you can undo.**

### The honest limitation
This is **compensation-based eventual consistency**, not an atomic distributed transaction.
The gap: if the router process dies between step 5 and step 6, nothing retries — our state is in
memory and gone. Real switches solve this with a persisted transaction log + reserve/commit
two-phase protocol + a reconciliation job.

**We will name this explicitly in the README.** Knowing why your design is insufficient for
production is a stronger interview answer than claiming it is sufficient.

### Why mark the quote CONSUMED *before* calling the DFSPs?
Because the gate must close before the dangerous work starts. If we marked it consumed at the end,
two simultaneous requests with the same `quoteId` would both pass the check and both debit.

---

## 14. Validation responsibilities

Validation is layered. **Each layer validates only what it can actually know.**

| Layer | Validates | Mechanism | Example |
|---|---|---|---|
| **Frontend** | UX only — never trusted | HTML5 + React state | required field, disable button |
| **Router — structural** | Is the request *well-formed*? | Bean Validation `@Valid` on DTOs | `amount` not null, > 0, ≤ 2 decimals; `currency` = `BDT`; ids not blank, match `^[A-Z]-\d{4}$` |
| **Router — routing** | Are these accounts *routable*? | `DfspRegistry` | unknown prefix; same-DFSP transfer |
| **Router — business/state** | Is this action *allowed right now*? | service layer + custom exceptions | quote expired; quote already used; transfer id unknown |
| **DFSP — authoritative** | Does this account exist and have funds? | inside each DFSP | `ACCOUNT_NOT_FOUND`, `INSUFFICIENT_FUNDS` |

### Why this split?

- An **annotation cannot know runtime state.** `@Positive` can say the amount is positive without
  looking anything up. Nothing declarative can say "this quote expired 3 seconds ago" — that needs
  the clock and the store. So stateless rules go in annotations, stateful rules go in the service.
- **The DFSP must be the authority on balances**, not the router. The router does not hold the money;
  if the router decided "sufficient funds", it would be guessing about someone else's ledger and
  would race with every other transaction at that DFSP.
- **The frontend is never trusted.** Anyone can `curl` the router. Frontend validation is purely a
  courtesy to the user; the router validates everything again.

### Why Bean Validation instead of hand-written `if` statements?
Declarative, self-documenting on the DTO, produces a *uniform* `MethodArgumentNotValidException`
we handle in **one** place, and collects **all** violations at once (the user sees three problems in
one response instead of discovering them one at a time). Manual `if`s drift, get duplicated across
endpoints, and produce inconsistent messages.

---

## 15. Logging responsibilities

### The stack
`your code → SLF4J (API) → Logback (implementation) → console appender + rolling file appender`

**Why SLF4J + Logback?** SLF4J is a *facade* — your code says `LoggerFactory.getLogger(...)` and
never names Logback. Swapping the backend is a dependency change, not a code change. Logback is
Spring Boot's default, so it costs **zero extra dependencies**. (Log4j2 is a fine alternative; it
just is not free here.)

### What each service logs

| Service | Logs |
|---|---|
| `payment-router` | every inbound request (method, path, correlationId); routing decision; quote created (id, amounts, fee breakdown); every outbound DFSP call + its outcome + duration; transfer state transitions; **every** error with its `errorCode` |
| `dfsp-a` / `dfsp-b` | inbound call, account, amount, decision (approved/declined + reason), resulting balance |

### Log levels — and when to use which
| Level | Meaning here |
|---|---|
| `ERROR` | Money may be in a bad state, or an unexpected exception. `REQUIRES_RECONCILIATION` is always ERROR. |
| `WARN` | Expected-but-notable rejection: expired quote, insufficient funds, DFSP timeout. |
| `INFO` | The business narrative: quote created, transfer completed. The story you read to explain what happened. |
| `DEBUG` | Wire-level payloads. Off in normal running. |

### Correlation ID — the single most useful piece here
A servlet `Filter` reads or generates `X-Correlation-Id`, puts it in the **MDC** (Mapped Diagnostic
Context — a per-thread map Logback can print), includes it in the log pattern, forwards it on every
outbound DFSP call, returns it in the error body, and **clears it in a `finally` block** (thread
pools reuse threads; a leaked MDC value would mislabel the next request).

Without it, ten concurrent payments interleave into unreadable noise. With it:
```
grep "c1f9a7b2" logs/*.log
```
gives you one payment's complete journey, across all three services, in order.

### File configuration
`logback-spring.xml` in each service: `RollingFileAppender`, daily rollover + 10 MB size cap,
7 days retained, writing to `/app/logs/<service>.log`, bind-mounted to `./logs/` on the host.

### Why a file, when containers normally log to stdout?
Requirement M4 says file. But the honest engineering answer is worth knowing: **in a container,
stdout is the idiomatic sink** (12-factor) because the platform collects, rotates and ships it —
a file inside a container disappears when the container does, unless you mount a volume.
So we do **both**: console for the container platform, rolling file for the requirement, and a
volume so the file survives. *Being able to explain this tension is a real interview differentiator.*

---

## 16. How the services communicate

| Aspect | Choice | Why |
|---|---|---|
| Protocol | **HTTP/1.1, JSON** | universal, debuggable with curl, human-readable, no schema compiler |
| Style | **Synchronous request/response** | the caller literally cannot proceed without the answer — a quote is a question |
| Client library | **Spring `RestClient`** | modern synchronous client, included in `spring-boot-starter-web`, fluent and readable |
| Addressing | **Docker DNS by service name** — `http://dfsp-a:8081` | Compose gives each service a hostname on the shared network; no IPs, no service registry |
| Config | `@ConfigurationProperties` + env vars | same image runs locally (`localhost:8081`) and in Compose (`dfsp-a:8081`) with no rebuild |
| Timeouts | connect 2 s, read 5 s | **a payment call must never hang forever** — an unbounded wait exhausts the thread pool and takes the router down |
| Tracing | `X-Correlation-Id` header forwarded on every hop | one grep reconstructs the whole journey |
| Errors | DFSP returns 4xx/5xx + JSON error → connector maps it to a typed exception | the router's service layer reasons about *domain* failures, not HTTP codes |

**Why not gRPC?** Faster and typed, but needs `.proto` files, a code generator, and is not
curl-able — a large teaching cost for no benefit at this scale.
**Why not a message queue?** Queues are for *fire-and-forget* work. A quote must return a price
*now*. Adding Kafka/RabbitMQ here would be the textbook definition of fake complexity.

---

## 17. Docker architecture

### Four images, one network, one volume

```
docker-compose.yml
  networks:  payment-net (bridge)
  volumes:   ./logs -> /app/logs   (bind mount, so logs survive container death)

  services:
    dfsp-a          build ./dfsp-a           8081:8081   healthcheck /actuator/health
    dfsp-b          build ./dfsp-b           8082:8082   healthcheck /actuator/health
    payment-router  build ./payment-router   8080:8080   depends_on: dfsp-a, dfsp-b (healthy)
                    env: DFSP_A_URL=http://dfsp-a:8081
                         DFSP_B_URL=http://dfsp-b:8082
    frontend        build ./frontend         3000:80     depends_on: payment-router
```

### Multi-stage builds — and why they matter

**Java services:**
```dockerfile
# stage 1 — build (needs Maven + full JDK, ~800 MB)
FROM maven:3.9-eclipse-temurin-21 AS build
COPY pom.xml .            # copied FIRST, alone
RUN mvn dependency:go-offline   # cached layer: re-runs only if pom.xml changes
COPY src ./src
RUN mvn clean package -DskipTests

# stage 2 — run (needs only a JRE, ~200 MB)
FROM eclipse-temurin:21-jre-alpine
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]
```
Only the second stage ships. **Why?** The final image carries no Maven, no source, no build cache —
smaller, faster to pull, and a smaller attack surface. Copying `pom.xml` before `src` is the
layer-caching trick: dependencies re-download only when dependencies actually change.

**Frontend:** `node:20-alpine` builds `dist/`, then `nginx:alpine` serves it. **The final image
contains no Node at all** — React compiles to static files; nothing JavaScript needs to *run* on
the server. This surprises people and is a good thing to be able to explain.

### Why Docker?
"Works on my machine" dies. The image pins the JRE version, the OS, the dependencies and the
startup command. Your grader runs one command and gets exactly what you ran.

### Why Docker Compose?
Four services means four Dockerfiles, four `docker run` commands with the right ports, the right
env vars, in the right order, on the right network — by hand, every time. Compose makes that a
declarative YAML file and `docker compose up`. It also provides the **DNS** that makes
`http://dfsp-a:8081` resolve, which is the thing that makes the multi-service architecture work at all.

### Why `depends_on` + healthchecks?
`depends_on` alone only waits for the container to *start*, not for Spring Boot to finish booting
(~5–10 s). Waiting on `condition: service_healthy` means the router does not come up and
immediately fail its first DFSP call. Good demo hygiene; also a genuine distributed-systems point —
*startup order is not something you can assume*.

---

## 18. Proposed folder structure

```
Mini Payment Router Simulator/
├── README.md                      # Step 10: architecture, setup, how it works
├── docker-compose.yml             # Step 9
├── .gitignore
├── .env.example                   # ports / fee config for Compose
├── docs/
│   ├── 01-architecture-and-plan.md   # ← this file
│   └── api-examples.http             # curl/REST-client samples
├── logs/                          # bind-mounted; .gitkeep only, contents git-ignored
│
├── payment-router/                # ═══ THE CORE SERVICE ═══
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd / .mvn/
│   ├── Dockerfile
│   └── src/
│       ├── main/java/com/misl/paymentrouter/
│       │   ├── PaymentRouterApplication.java
│       │   ├── controller/    QuoteController.java  TransferController.java  DfspController.java
│       │   ├── service/       QuoteService.java     TransferService.java     FeeCalculator.java
│       │   ├── connector/     DfspConnector.java (interface)
│       │   │                  DfspAConnector.java   DfspBConnector.java      DfspRegistry.java
│       │   ├── store/         QuoteStore.java       TransferStore.java
│       │   ├── dto/           QuoteRequest.java     QuoteResponse.java
│       │   │                  TransferRequest.java  TransferResponse.java    ApiError.java
│       │   ├── model/         Quote.java  Transfer.java  Dfsp.java  TransferStatus.java  QuoteStatus.java
│       │   ├── exception/     QuoteNotFoundException.java  QuoteExpiredException.java
│       │   │                  UnknownDfspException.java    DfspUnavailableException.java
│       │   │                  TransferRejectedException.java  GlobalExceptionHandler.java
│       │   └── config/        RouterProperties.java  RestClientConfig.java  CorrelationIdFilter.java  CorsConfig.java
│       ├── main/resources/    application.yml  logback-spring.xml
│       └── test/java/...      controller / service / connector tests
│
├── dfsp-a/                        # ═══ "AlphaPay" — percentage fee ═══
│   ├── pom.xml  Dockerfile  mvnw…
│   └── src/main/java/com/misl/dfspa/
│       ├── DfspAApplication.java
│       ├── controller/AlphaPayController.java
│       ├── service/AccountService.java  FeeService.java
│       ├── model/Account.java
│       ├── dto/…
│       └── resources/ application.yml  logback-spring.xml
│
├── dfsp-b/                        # ═══ "BetaCash" — flat fee, different API shape ═══
│   └── … same layout, package com.misl.dfspb …
│
└── frontend/                      # ═══ React ═══
    ├── package.json  vite.config.js  index.html  Dockerfile  nginx.conf
    └── src/
        ├── main.jsx  App.jsx  App.css
        ├── api/paymentApi.js          # the ONLY file that knows the router's URL
        └── components/
            ├── TransferForm.jsx       # payer/payee/amount input
            ├── QuoteSummary.jsx       # fee breakdown + confirm button + countdown
            ├── TransferResult.jsx     # success / failure
            └── ErrorBanner.jsx        # renders the standard ApiError shape
```

**Why group by layer (`controller/`, `service/`) rather than by feature (`quote/`, `transfer/`)?**
At this size, layer-grouping makes the *architecture* visible: a reader opens the package list and
immediately sees the shape of the application. Feature-grouping (`quote/QuoteController.java`,
`quote/QuoteService.java`) scales better in large systems, but here it would hide exactly the
structure we are trying to teach. This is a deliberate, size-appropriate choice — and a good
question to be asked about.

---

## 19. Technologies we actually need

| Technology | What it does | Why we need it |
|---|---|---|
| **Java 21** | language/runtime | Required. LTS, already installed. Records make DTOs one line each. |
| **Spring Boot 3.x** | app framework | Embedded Tomcat, DI container, auto-configuration, `java -jar` → running service |
| `spring-boot-starter-web` | REST + Jackson + Tomcat + `RestClient` | the whole HTTP layer, inbound and outbound, in one dependency |
| `spring-boot-starter-validation` | Jakarta Bean Validation (Hibernate Validator) | `@Valid`, `@NotNull`, `@DecimalMin` — requirement M3 |
| `spring-boot-starter-actuator` | `/actuator/health` | Docker healthchecks in Step 9 |
| **Logback** (via `starter-web`) | logging backend | requirement M4; already on the classpath |
| **Jackson** (via `starter-web`) | JSON ⇄ Java | every request/response; already on the classpath |
| **Maven + wrapper** | build, dependencies, packaging | reproducible build with no global install |
| `spring-boot-starter-test` | JUnit 5, Mockito, AssertJ, MockMvc | Step 7 |
| **React 18** | UI | Required. Component + state model fits quote→confirm→result. |
| **Vite** | dev server + bundler | instant HMR; outputs plain static files for nginx |
| **`fetch`** | HTTP from browser | built in; two endpoints do not justify a library |
| **Docker + Compose** | containers + orchestration | requirements M5 |
| **nginx** (alpine image) | serve built React | static files need a static server, not Node |

Notice how short that list is. Four of the Java entries are *already inside* one starter.

---

## 20. Technologies we are explicitly NOT using — and why

| Not using | Why not |
|---|---|
| **Any database** (Postgres/MySQL/H2/Mongo) | Explicitly excluded by you. A simulator's value is the routing logic; entities, repositories and migrations would triple the code and teach persistence instead of routing. **Cost accepted: state is lost on restart, and we will document that.** |
| **JPA / Hibernate** | Follows from no DB. |
| **Kafka / RabbitMQ / any queue** | Queues are for asynchronous fire-and-forget. A quote must return a price *now*. Textbook fake complexity. |
| **Redis** | We need a `Map`. `ConcurrentHashMap` *is* the map, with no network hop and no extra container. |
| **Kubernetes** | Compose already satisfies "multi-service". K8s adds manifests, kubelet, ingress and a cluster to explain — for four containers on a laptop. |
| **Spring Security / OAuth / JWT** | No user accounts in scope. Auth would add filters and token plumbing that obscure the payment flow. We will *name* it as the first thing production needs. |
| **Spring Cloud (Eureka, Config, Gateway)** | Service discovery for two fixed services that Docker DNS already resolves. Solves a problem we do not have. |
| **OpenFeign** | Nice, but it hides the HTTP call behind an interface + annotations. For learning, seeing the `RestClient` call written out is more valuable. |
| **WebFlux / reactive** | `Mono`/`Flux` is a large conceptual jump, and our load is one user clicking a button. Blocking code is easier to read, debug and reason about here. |
| **Lombok** | Saves getters, but adds an annotation processor, IDE plugin requirements, and "where did this method come from?" confusion for a beginner. **Java 21 `record`s give us immutable DTOs with zero boilerplate and zero dependencies.** |
| **MapStruct** | Our mappings are a handful of fields; a hand-written `toDto()` is clearer than generated code. |
| **Axios** | `fetch` is in every browser. Two endpoints do not justify a dependency. |
| **Redux / Zustand** | Our state is: form fields, one quote, one result. `useState` covers it. Redux for this would be a red flag in review. |
| **TypeScript** | Genuinely valuable, but the assignment says React and you are learning Java and Spring simultaneously. Two type systems at once is the wrong load. *Reconsider if you want it — say so before Step 8.* |
| **Next.js** | SSR, routing and a Node server we do not need for one page. |
| **Tailwind / MUI** | A few hundred lines of plain CSS keeps the focus on the flow. |
| **Real bKash / Nagad APIs** | Explicitly forbidden, require commercial agreements, and would make the project un-runnable by a grader. bKash/Nagad appear **only as analogies**. |

---

## 21. Key design decisions (the defensible list)

| # | Decision | Core reason | Rejected alternative |
|---|---|---|---|
| D-1 | Java 21 + Spring Boot 3.x | embedded server, DI, auto-config; `java -jar` runs | plain Jakarta EE; Quarkus |
| D-2 | Maven + wrapper (`mvnw`) | Maven not installed; reproducible, Docker-friendly | Gradle; global Maven install |
| D-3 | Three independent Maven projects | independent build/version/ship; trivial Dockerfiles | multi-module parent POM |
| D-4 | **DFSP-A and DFSP-B have different APIs and fee models** | makes the `DfspConnector` interface *necessary*, not decorative | one shared dummy image run twice |
| D-5 | No DB; `ConcurrentHashMap` in memory | your constraint; keeps focus on routing | H2/JPA |
| D-6 | Synchronous HTTP via `RestClient` | request/response fits the domain; readable | WebClient; Feign; RestTemplate; MQ |
| D-7 | Sender-pays fees | matches bKash "Send Money"; easy to display | receiver-pays |
| D-8 | `BigDecimal`, scale 2, `HALF_UP` — never `double` | `0.1 + 0.2 != 0.3` in binary floating point | `double`; `long` minor units |
| D-9 | Two-layer validation (annotations vs service) | annotations cannot see runtime state | all-manual; all-annotation |
| D-10 | One `@RestControllerAdvice` | one error shape, produced in one place | try/catch per controller |
| D-11 | SLF4J→Logback, console **and** rolling file, + MDC correlation ID | M4; and one grep reconstructs a payment | `System.out`; Log4j2; ELK |
| D-12 | React + Vite + plain `fetch` | fast dev loop; static output for nginx | CRA; Next.js; axios |
| D-13 | Docker Compose, one bridge network, log volume | one command; DNS by service name | 4 manual `docker run`; K8s |
| D-14 | **Account identifier is the routing key** (prefix → DFSP, from config) | makes the router actually route; mirrors Mojaloop's Account Lookup Service | client supplies `payerDfsp`/`payeeDfsp` |
| D-15 | Debit first, then credit; compensate on credit failure | fail in the direction you can undo; never create money | credit-first; two-phase commit |
| D-16 | Quote marked CONSUMED **before** DFSP calls | closes the idempotency gate before the dangerous work | mark after success (allows double-spend) |
| D-17 | Java `record`s for DTOs | immutable, one line each, zero dependencies | Lombok `@Data`; hand-written POJOs |
| D-18 | Package by layer, not by feature | makes the architecture visible at this size | package by feature |

---

## 22. Known limitations (to be stated openly in the final README)

1. **No persistence** — quotes, transfers and balances vanish on restart (D-5).
2. **Not atomic** — compensation only; a router crash mid-transfer leaves no recoverable record (§13).
3. **No authentication, authorization, TLS, or rate limiting.**
4. **Prefix-based routing** is a stand-in for a real party-lookup directory.
5. **Fees are static config**, not a pricing engine.
6. **Single instance per service** — in-memory state would break under horizontal scaling.
7. **No retries or circuit breaker** — one timeout fails the request.
8. **Single currency (BDT)** — no FX.

Every one of these is a deliberate scope decision, not an oversight. Being able to say *which*
limitation you would fix first (persistence, then idempotency keys, then auth) is the mark of
someone who understands the system rather than just built it.

---

## 23. The 10-step plan

| Step | Deliverable | New concepts introduced |
|---|---|---|
| **1** | **This document** | DFSP, switch/hub, quote vs transfer, N×(N−1) problem |
| **2** | `payment-router` skeleton: Maven project, `@SpringBootApplication`, `application.yml`, health endpoint on :8080 | Spring Boot startup, auto-configuration, embedded Tomcat, DI container, project layout |
| **3** | **Quote API** — `QuoteController`, `QuoteService`, `FeeCalculator`, DTOs, `QuoteStore`, `DfspRegistry` routing. DFSP fees stubbed locally. | `@RestController`, `@PostMapping`, `@RequestBody`, constructor injection, records, `BigDecimal` |
| **4** | **Validation + error handling** — Bean Validation on DTOs, custom exceptions, `GlobalExceptionHandler`, `ApiError`, status-code map | `@Valid`, `@NotNull`, `@DecimalMin`, `MethodArgumentNotValidException`, `@RestControllerAdvice`, `@ExceptionHandler` |
| **5** | **dfsp-a + dfsp-b** — two Spring Boot services, in-memory seeded accounts, deliberately different APIs and fee models | building a provider, `@ConfigurationProperties`, divergent contracts, thread-safe in-memory state |
| **6** | **Transfer API + provider abstraction** — `DfspConnector` interface, two adapters, real `RestClient` calls, quote consumption, debit→credit, compensation | interfaces & polymorphism, adapter pattern, `RestClient`, timeouts, partial failure |
| **7** | **File logging + tests** — `logback-spring.xml` rolling appender, `CorrelationIdFilter` + MDC, JUnit 5 / MockMvc / Mockito across all three services | SLF4J vs Logback, appenders, log levels, MDC, `@WebMvcTest`, `@SpringBootTest`, mocking |
| **8** | **React frontend** — form → fee breakdown → confirm → result, error banner, CORS | components, `useState`, `fetch`/async-await, controlled inputs, CORS preflight |
| **9** | **Dockerfiles + Compose** — multi-stage builds ×3, nginx build, `docker-compose.yml`, network, log volume, healthchecks | images vs containers, layer caching, multi-stage builds, Compose DNS, volumes, `depends_on` |
| **10** | **Final review + README + interview prep** — full documentation and a "why" question bank | synthesis |

### Port map
| Service | Host port | In-Compose hostname |
|---|---|---|
| frontend | 3000 | `frontend:80` |
| payment-router | 8080 | `payment-router:8080` |
| dfsp-a | 8081 | `dfsp-a:8081` |
| dfsp-b | 8082 | `dfsp-b:8082` |

---

## 24. Interview question bank — Step 1 (concepts & architecture)

**Domain**
1. What is a DFSP, and why can't DFSP-A just credit a DFSP-B account directly?
2. What problem does a payment router/switch solve? Give the N×(N−1) argument.
3. Why are quote and transfer two separate APIs? Give three independent reasons.
4. Is a quote idempotent? Is a transfer? Why does that difference change your design?
5. Walk me through 1000 BDT from A to B, end to end, including who charges what.

**Architecture**
6. Why is the router the only service the frontend talks to?
7. Why are the DFSPs separate processes instead of classes inside the router?
8. Why do DFSP-A and DFSP-B deliberately have *different* APIs?
9. Why synchronous HTTP and not a message queue?
10. What state lives where, and what happens to it on restart?

**Design**
11. How does the router decide which DFSP to call? Why not let the client specify it?
12. Why `BigDecimal` and not `double` for money? Show me the failing example.
13. Why debit before credit, and not the other way round?
14. What happens if the debit succeeds and the credit fails? What are the three possible outcomes?
15. Why is the quote marked consumed *before* the DFSP calls rather than after?
16. Why does the quote expire? What would go wrong with an infinite quote?

**Practice**
17. Which validation goes in annotations and which goes in the service layer, and why?
18. Why does the DFSP, not the router, decide whether funds are sufficient?
19. What is a correlation ID and what breaks without one?
20. Why log to a file when containers normally log to stdout? What do you lose either way?
21. Why Docker Compose rather than four `docker run` commands?
22. Why a multi-stage Dockerfile? What is in your final image and what is not?

**Judgement (the ones that actually separate candidates)**
23. Name three things wrong with this system for production, ranked.
24. Your router crashes between the debit and the credit. What happens? How would you fix it properly?
25. What would you add first if this had to go live: persistence, idempotency keys, or auth? Defend the order.
26. Why did you *not* use Kafka / Redis / Kubernetes / a database here?
