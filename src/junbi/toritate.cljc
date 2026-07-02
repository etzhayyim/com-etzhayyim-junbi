(ns junbi.toritate
  "toritate (執帳) accounting cross-ref (R2, ADR-2607021800 D7 /
   ADR-2605262900). junbi emits `com.etzhayyim.toritate.ledgerEntry`-shaped
   records for every on-chain-settled reserve movement, so the accounting
   actor aggregates the treasury without a second primary ledger
   (toritate G3/G4: all financial state derives from on-chain tx —
   an entry REQUIRES txCid + chain + counterpartyDid).

   Honest R2 boundaries:
   - `nativeAsset` enum is toritate R0's {usdc, eth, n-a} — EURC/JPYC map to
     \"n-a\" until toritate extends its enum (toritate-side follow-up).
   - `category` has no reserve-rebalance value yet; rebalance legs emit as
     \"uncategorized\" with a memo (same follow-up).
   - G12: payroll/wage/salary/bonus/commission are NEVER valid (schema-layer
     invariant mirrored here)."
  (:require [clojure.string :as str]))

(def attesting-cell-did "did:web:etzhayyim.github.io:com-etzhayyim-junbi")

(def categories
  "com.etzhayyim.toritate.ledgerEntry category knownValues (lexicon verbatim)."
  #{"donation-income" "kisha-income" "grant-income"
    "tithe-split-90pct-operational" "tithe-split-10pct-public-fund"
    "public-fund-grant-disbursement" "council-operational-expense"
    "external-counsel-engagement" "external-auditor-engagement"
    "subsistence-flow" "vocation-flow" "liberation-flow" "care-flow"
    "reimbursement" "land-trust-acquisition" "asset-acquisition"
    "asset-depreciation" "internal-promo-expense" "uncategorized"})

(def g12-banned
  "Constructive-employment drift — never valid, at any layer."
  #{"payroll" "wage" "salary" "bonus" "commission"})

(defn- usd->millicents [usd]
  (long #?(:clj  (Math/round (* (double usd) 100000.0))
           :cljs (js/Math.round (* usd 100000.0)))))

(defn- native-asset [asset]
  (case asset
    :usdc "usdc"
    ;; toritate R0 enum is {usdc, eth, n-a}; EURC/JPYC → "n-a" until extended
    "n-a"))

(defn ledger-entry
  "Build a toritate ledgerEntry record from an on-chain-settled junbi
   movement. Required inputs: :created-at (datetime str) :tx-cid :category
   :amount-usd (double, ≥0) :counterparty-did. Optional: :asset (keyword),
   :amount-native-atomic, :linked-attestation-cid, :memo."
  [{:keys [created-at tx-cid category amount-usd counterparty-did
           asset amount-native-atomic linked-attestation-cid]}]
  (cond-> {:$type "com.etzhayyim.toritate.ledgerEntry"
           :createdAt created-at
           :txCid tx-cid
           :chain "base-l2"
           :category category
           :amountUsdMillicents (usd->millicents amount-usd)
           :counterpartyDid counterparty-did
           :attestingCellDid attesting-cell-did}
    asset (assoc :nativeAsset (native-asset asset))
    amount-native-atomic (assoc :amountNativeAtomic amount-native-atomic)
    linked-attestation-cid (assoc :linkedAttestationCid linked-attestation-cid)))

(defn validate
  "Validate a ledgerEntry against the lexicon's required fields + enums +
   the G12 invariant. Returns {:valid? bool :violations [..]}."
  [{:keys [createdAt txCid chain category amountUsdMillicents
           counterpartyDid attestingCellDid] :as _entry}]
  (let [vs (cond-> []
             (some #(str/blank? (str %))
                   [createdAt txCid chain counterpartyDid attestingCellDid])
             (conj {:gate :toritate/required
                    :msg "missing required field (G3/G4: on-chain tx only)"})

             (g12-banned category)
             (conj {:gate :toritate/g12
                    :msg (str "'" category "' is constructive employment — never valid")})

             (and category (not (g12-banned category))
                  (not (categories category)))
             (conj {:gate :toritate/category
                    :msg (str "unknown category " category)})

             (or (nil? amountUsdMillicents) (neg? amountUsdMillicents))
             (conj {:gate :toritate/amount
                    :msg "amountUsdMillicents must be an integer ≥ 0"}))]
    {:valid? (empty? vs) :violations vs}))

(defn acquisition->entry
  "toritate entry for a settled reserve acquisition (banking posting +
   on-chain settlement facts)."
  [posting {:keys [created-at tx-cid amount-usd counterparty-did asset
                   amount-native-atomic]}]
  (ledger-entry {:created-at created-at
                 :tx-cid tx-cid
                 :category "asset-acquisition"
                 :amount-usd amount-usd
                 :counterparty-did counterparty-did
                 :asset asset
                 :amount-native-atomic amount-native-atomic
                 :linked-attestation-cid (:ledger/posting posting)}))

(defn rebalance->entries
  "toritate entries for one settled rebalance (sell leg + buy leg). No
   reserve-rebalance category exists in toritate R0 — both legs emit as
   \"uncategorized\" cross-linked to the banking posting id, pending the
   toritate enum extension."
  [posting {:keys [created-at tx-cid sell-asset sell-usd buy-asset buy-usd
                   counterparty-did]}]
  [(ledger-entry {:created-at created-at :tx-cid tx-cid
                  :category "uncategorized"
                  :amount-usd sell-usd :counterparty-did counterparty-did
                  :asset sell-asset
                  :linked-attestation-cid (:ledger/posting posting)})
   (ledger-entry {:created-at created-at :tx-cid tx-cid
                  :category "uncategorized"
                  :amount-usd buy-usd :counterparty-did counterparty-did
                  :asset buy-asset
                  :linked-attestation-cid (:ledger/posting posting)})])
