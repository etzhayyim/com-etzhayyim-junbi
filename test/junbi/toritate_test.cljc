(ns junbi.toritate-test
  (:require [clojure.test :refer [deftest is testing]]
            [junbi.ledger :as ledger]
            [junbi.toritate :as toritate]))

(def settlement
  {:created-at "2026-07-02T12:00:00Z"
   :tx-cid "0xabc123"
   :amount-usd 100.0
   :counterparty-did "did:web:exchange.example"
   :asset :usdc
   :amount-native-atomic 100000000})

(deftest acquisition-entry-is-valid
  (let [p (ledger/acquisition-posting "P1" :usdc 100000000)
        e (toritate/acquisition->entry p settlement)]
    (is (:valid? (toritate/validate e)))
    (is (= "asset-acquisition" (:category e)))
    (is (= 10000000 (:amountUsdMillicents e)))   ; $100 → USD×100000
    (is (= "usdc" (:nativeAsset e)))
    (is (= "base-l2" (:chain e)))
    (is (= "P1" (:linkedAttestationCid e)))
    (is (= toritate/attesting-cell-did (:attestingCellDid e)))))

(deftest eurc-maps-to-n-a-until-toritate-extends
  (let [e (toritate/ledger-entry (assoc settlement
                                        :category "asset-acquisition"
                                        :asset :eurc))]
    (is (= "n-a" (:nativeAsset e)))
    (is (:valid? (toritate/validate e)))))

(deftest rebalance-emits-two-cross-linked-legs
  (let [p (ledger/rebalance-posting "P2" :usdc 200000000 :eurc 185185185)
        [sell buy] (toritate/rebalance->entries
                    p {:created-at "2026-07-02T12:00:00Z" :tx-cid "0xdef"
                       :sell-asset :usdc :sell-usd 200.0
                       :buy-asset :eurc :buy-usd 200.0
                       :counterparty-did "did:web:pool.example"})]
    (is (:valid? (toritate/validate sell)))
    (is (:valid? (toritate/validate buy)))
    (is (= ["P2" "P2"] [(:linkedAttestationCid sell) (:linkedAttestationCid buy)]))
    (is (= "uncategorized" (:category sell)))))

(deftest g12-payroll-never-valid
  (testing "constructive-employment categories rejected at every layer"
    (doseq [c ["payroll" "wage" "salary" "bonus" "commission"]]
      (let [{:keys [valid? violations]}
            (toritate/validate (toritate/ledger-entry (assoc settlement :category c)))]
        (is (not valid?))
        (is (some #(= :toritate/g12 (:gate %)) violations))))))

(deftest missing-tx-cid-rejected
  (testing "G3/G4 — no off-chain primary ledger; an entry requires the on-chain tx"
    (let [{:keys [valid? violations]}
          (toritate/validate (toritate/ledger-entry
                              (-> settlement
                                  (dissoc :tx-cid)
                                  (assoc :category "asset-acquisition"))))]
      (is (not valid?))
      (is (some #(= :toritate/required (:gate %)) violations)))))
