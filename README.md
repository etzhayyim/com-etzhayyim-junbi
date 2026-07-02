# junbi (準備) — etzhayyim reserve treasury actor + HAKARI (秤) basket

**DID**: `did:web:etzhayyim.github.io:com-etzhayyim-junbi`
**Namespace**: `com.etzhayyim.junbi.*`
**ADR**: ADR-2607021800 (R0 scaffold, 2026-07-02)
**Status**: R0 scaffold — pure `.cljc` core + tests
**Sibling actors**: kawase-yui (ADR-2605282200 — multi-stable pool precedent),
toritate (accounting), chigiri (disputes)

## Overview

junbi holds etzhayyim's **reserve of fiat-backed currencies issued by trusted
organizations** and maintains **HAKARI (秤)** — an SDR-style **basket unit of
account** over that reserve. HAKARI is *never* minted as a token
(Charter Rider §2(b) / ADR-2605172100 Alt C stay fully intact).

| Tier | Assets | Custody |
|---|---|---|
| 1 (on-chain) | **USDC**, **EURC** (Circle), **JPYC** (JPYC株式会社) | Base L2 ERC-4337 smart account / Safe; kotoba-custody t-of-N Shamir |
| 2 (CBDC) | **e-CNY** (中国人民銀行); digital euro / digital yen placeholders | authorized-operator wallet; **attestation-observed only**; weight 0 until Council Lv7+ unanimity |

**USDT is structurally excluded** (owner decision 2026-07-02, gate J3).

## Gates J1..J12 (CI-greppable)

| Gate | Invariant | Enforced in |
|---|---|---|
| J1 | no token mint — HAKARI is a unit of account | `governor/forbidden-actions` |
| J2 | issuer whitelist only | `core/assets` + `core/validate-params` |
| J3 | USDT structurally rejected | `core/excluded-assets` |
| J4 | Σ weights = 1.0, weights ≥ 0 | `core/validate-params` |
| J5 | ±0.5% mid-market band (`max-band-bps 50`) | `core/band-ok?` + params validation |
| J6 | no yield / DeFi / lending of reserve | `governor/forbidden-actions` |
| J7 | no fiat custody | `governor/forbidden-actions` |
| J8 | double-entry balanced postings only | `core/balanced?` (banking at R1) |
| J9 | Tier-2 CBDC weight 0 until Council activation | `core/validate-params` + `effective-weights` |
| J10 | rebalance = proposal only; execution needs human approval | `core/rebalance-proposal` + `governor/review-execute` |
| J11 | no spread profit — mid-market locked | `governor/review-execute` |
| J12 | EN net-zero untouched | `governor/forbidden-actions` |

## HAKARI params (Council-versioned)

```clojure
{:version "1.0.0" :numeraire :usd :band-bps 50
 :weights {:usdc 0.40 :jpyc 0.40 :eurc 0.20}}
;; after Council activates e-CNY (R3):
;; {:weights {:usdc 0.35 :jpyc 0.35 :eurc 0.20 :ecny 0.10} :activated #{:ecny}}
```

NAV = Σ wᵢ·midᵢ from Chainlink mid-market attestations; unactivated Tier-2
weights renormalize away (`effective-weights`), so an unavailable CBDC never
breaks the basket.

## Relation to EN (縁 / ENGI)

EN stays net-zero, non-minted mutual credit
(`kotoba/docs/ADR-engi-mutual-credit-on-chain.md`). HAKARI generalizes the
cross-boundary settlement asset from single-USDC to the basket and may
denominate EN **credit-limit sizing**. Nothing here mints or burns EN (J12).

## Phase ladder

| Phase | Scope |
|---|---|
| **R0** (this) | pure `.cljc` core: params validation, NAV, drift, rebalance proposals, TreasuryGovernor |
| R1 | USDC+JPYC live (base-l2 ERC-4337 reads); kotoba-lang/banking wiring; langgraph-clj StateGraph |
| R2 | +EURC live; rebalance-proposer cell; toritate cross-ref; EN credit-limit denomination |
| R3 | +e-CNY Tier-2 (jurisdiction legal analysis + Council Lv7+ unanimity) |

## Develop

```bash
clojure -M:lint     # clj-kondo (errors fail)
clojure -M:test     # core + governor tests
```
