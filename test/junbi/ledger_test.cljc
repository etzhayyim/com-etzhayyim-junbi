(ns junbi.ledger-test
  (:require [clojure.test :refer [deftest is testing]]
            [junbi.ledger :as ledger]
            [junbi.governor :as gov]))

(deftest chart-covers-tier-1
  (let [c (ledger/chart)]
    (is (= #{:usdc :eurc :jpyc} (set (keys c))))
    (is (= "JPY" (get-in c [:jpyc :account/currency])))))

(deftest acquisition-balances
  (let [p (ledger/acquisition-posting "P1" :usdc 100000000)]
    (is (ledger/posting-ok? p))
    (is (= :approve (:verdict (gov/review {:action/type :ledger/post :posting p}))))))

(deftest rebalance-balances-per-currency
  (testing "sell USDC / buy JPYC squares through fx-clearing (J8/J11)"
    (let [p (ledger/rebalance-posting "P2" :usdc 200000000 :jpyc 30303030303030303030303)]
      (is (ledger/posting-ok? p))
      (is (= :approve (:verdict (gov/review {:action/type :ledger/post :posting p})))))))

(deftest tampered-posting-rejected
  (let [p (ledger/acquisition-posting "P3" :eurc 5000000)
        tampered (update p :ledger/entries
                         (fn [es] (assoc-in (vec es) [0 :ledger/amount] 4999999)))]
    (is (not (ledger/posting-ok? tampered)))
    (is (= :j8 (:gate (gov/review {:action/type :ledger/post :posting tampered}))))))
