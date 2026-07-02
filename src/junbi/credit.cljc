(ns junbi.credit
  "HAKARI-denominated EN credit-limit sizing (R2, ADR-2607021800 D8).

   EN (縁 / ENGI) is net-zero mutual credit — a member's per-agent credit
   limit is *reputation state, not EN* (ADR-engi-mutual-credit-on-chain).
   This namespace SIZES that limit from the mid-market value of a member's
   attested reserve stake, denominated in HAKARI. It never mints, burns or
   transfers EN (J12): the output is a pure limit recommendation the EN
   layer may adopt.

   Policy is Council-versioned (same pattern as basket weights):
     {:ltv-bps 5000            ; ≤50% of stake value counts
      :max-limit-hakari 1000.0 ; per-member cap
      :en-per-hakari 1.0}      ; EN units per 1 HAKARI (Council param)"
  (:require [junbi.core :as core]))

(def default-policy
  {:ltv-bps 5000
   :max-limit-hakari 1000.0
   :en-per-hakari 1.0})

(defn stake-value
  "Mid-market value (numeraire) of a member's whitelisted reserve stake.
   Returns nil when any staked asset lacks an attested rate — an
   unpriceable stake never sizes a limit."
  [holdings rates]
  (let [vs (map (fn [[a h]] (core/holding-value a h rates)) holdings)]
    (when (and (seq vs) (every? some? vs))
      (reduce + 0.0 vs))))

(defn credit-limit
  "Size a member's EN credit limit from their stake.
   Returns {:stake-value v :nav n :limit-hakari h :limit-en e} or nil when
   the stake or the basket is unpriceable (missing attestation — J5/J9:
   never guess). Never touches EN itself (J12)."
  ([params rates holdings] (credit-limit params rates holdings default-policy))
  ([params rates holdings {:keys [ltv-bps max-limit-hakari en-per-hakari]
                           :or {ltv-bps 5000 max-limit-hakari 1000.0
                                en-per-hakari 1.0}}]
   (let [nav (core/nav params rates)
         v (stake-value holdings rates)]
     (when (and nav (pos? nav) v)
       (let [collateral (* v (/ ltv-bps 10000.0))
             limit-h (min max-limit-hakari (/ collateral nav))]
         {:stake-value v
          :nav nav
          :limit-hakari limit-h
          :limit-en (* limit-h en-per-hakari)})))))
