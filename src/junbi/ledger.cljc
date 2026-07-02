(ns junbi.ledger
  "Reserve double-entry ledger (R1, ADR-2607021800 D7 / gate J8) —
   kotoba-lang/banking postings, reused verbatim (do not reinvent).

   Chart: one asset account per reserve asset (`reserve:<asset>`, currency of
   the asset), a `settlement:<cur>` funding account per currency, and an
   `fx-clearing:<cur>` pair so cross-currency rebalances balance per currency
   (banking's `balanced?` groups by currency)."
  (:require [kotoba.banking :as bank]
            [junbi.core :as core]))

(defn- currency-of [asset]
  (get-in core/assets [asset :currency]))

(defn reserve-account
  "Banking account record for one reserve asset."
  [asset]
  (bank/account (str "reserve:" (name asset)) (currency-of asset)
                :holder "etzhayyim/junbi" :type :savings))

(defn chart
  "Chart of accounts over the whitelisted Tier-1 assets."
  []
  (into {}
        (for [[a {:keys [tier]}] core/assets
              :when (= 1 tier)]
          [a (reserve-account a)])))

(defn acquisition-posting
  "Acquire `amount` (smallest unit) of `asset` funded from settlement.
   Single-currency posting: debit the reserve asset account, credit
   settlement in the same currency."
  [id asset amount]
  (let [cur (currency-of asset)]
    (bank/posting id
                  [(bank/entry (str "reserve:" (name asset)) :debit amount cur)
                   (bank/entry (str "settlement:" cur) :credit amount cur)]
                  :memo (str "acquire " (name asset)))))

(defn rebalance-posting
  "Cross-currency rebalance: sell `sell-amount` of `sell-asset`, buy
   `buy-amount` of `buy-asset`, both legs squared through fx-clearing so the
   posting balances per currency (J8/J11 — amounts are mid-market values,
   spread-free by construction)."
  [id sell-asset sell-amount buy-asset buy-amount]
  (let [sc (currency-of sell-asset)
        bc (currency-of buy-asset)]
    (bank/posting id
                  [(bank/entry (str "reserve:" (name sell-asset)) :credit sell-amount sc)
                   (bank/entry (str "fx-clearing:" sc) :debit sell-amount sc)
                   (bank/entry (str "reserve:" (name buy-asset)) :debit buy-amount bc)
                   (bank/entry (str "fx-clearing:" bc) :credit buy-amount bc)]
                  :memo (str "rebalance " (name sell-asset) "→" (name buy-asset)))))

(defn posting-ok?
  "J8 — banking-validated balance check for a posting record."
  [posting]
  (and (map? posting)
       (boolean (:ledger/balanced? posting))
       (bank/balanced? (:ledger/entries posting))))
