(ns junbi.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [junbi.governor :as gov]))

(deftest structurally-forbidden
  (testing "J1 — no token is ever minted (HAKARI is a unit of account)"
    (is (= :reject (:verdict (gov/review {:action/type :token/mint}))))
    (is (= :j1 (:gate (gov/review {:action/type :token/deploy-erc20})))))
  (testing "J6 — no yield / DeFi / lending of the reserve"
    (is (= :j6 (:gate (gov/review {:action/type :defi/swap-amm}))))
    (is (= :j6 (:gate (gov/review {:action/type :reserve/lend}))))
    (is (= :j6 (:gate (gov/review {:action/type :reserve/stake})))))
  (testing "J7 — no fiat custody"
    (is (= :j7 (:gate (gov/review {:action/type :fiat/custody})))))
  (testing "J12 — EN mutual credit is untouchable from treasury code"
    (is (= :j12 (:gate (gov/review {:action/type :en/mint}))))
    (is (= :j12 (:gate (gov/review {:action/type :en/burn}))))))

(deftest acquisitions
  (testing "whitelisted Tier-1 asset approved"
    (is (= :approve (:verdict (gov/review {:action/type :reserve/acquire :asset :usdc}))))
    (is (= :approve (:verdict (gov/review {:action/type :reserve/acquire :asset :jpyc})))))
  (testing "J3 — USDT rejected"
    (is (= :j3 (:gate (gov/review {:action/type :reserve/acquire :asset :usdt})))))
  (testing "J2 — non-whitelisted asset rejected"
    (is (= :j2 (:gate (gov/review {:action/type :reserve/acquire :asset :doge})))))
  (testing "J9 — e-CNY needs Council activation"
    (is (= :j9 (:gate (gov/review {:action/type :reserve/acquire :asset :ecny}))))
    (is (= :approve (:verdict (gov/review {:action/type :reserve/acquire
                                           :asset :ecny
                                           :activated #{:ecny}}))))))

(deftest rebalance-flow
  (testing "proposing is always allowed (J10 proposals are inert)"
    (is (= :approve (:verdict (gov/review {:action/type :rebalance/propose})))))
  (testing "J10 — execution without human approval routes to :human"
    (is (= :human (:verdict (gov/review {:action/type :rebalance/execute
                                         :fills [{:price 1.0 :mid 1.0}]})))))
  (testing "approved + in-band execution approved"
    (is (= :approve (:verdict (gov/review {:action/type :rebalance/execute
                                           :human-approved? true
                                           :fills [{:price 1.002 :mid 1.0}]})))))
  (testing "J5/J11 — approved but out-of-band fill rejected"
    (is (= :reject (:verdict (gov/review {:action/type :rebalance/execute
                                          :human-approved? true
                                          :fills [{:price 1.02 :mid 1.0}]}))))))

(deftest params-and-ledger
  (testing "valid params update still needs Council (:human)"
    (is (= :human (:verdict (gov/review
                             {:action/type :params/update
                              :params {:weights {:usdc 0.5 :jpyc 0.5} :band-bps 50}})))))
  (testing "invalid params update rejected with the violated gate"
    (is (= :j3 (:gate (gov/review
                       {:action/type :params/update
                        :params {:weights {:usdc 0.5 :usdt 0.5} :band-bps 50}})))))
  (testing "J8 — unbalanced posting rejected"
    (is (= :j8 (:gate (gov/review
                       {:action/type :ledger/post
                        :entries [{:account "a" :side :debit :amount 100 :currency "USD"}
                                  {:account "b" :side :credit :amount 90 :currency "USD"}]}))))
    (is (= :approve (:verdict (gov/review
                               {:action/type :ledger/post
                                :entries [{:account "a" :side :debit :amount 100 :currency "USD"}
                                          {:account "b" :side :credit :amount 100 :currency "USD"}]})))))
  (testing "unknown action never default-approves"
    (is (= :human (:verdict (gov/review {:action/type :surprise/thing}))))))
