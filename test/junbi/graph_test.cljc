(ns junbi.graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [langchain.db :as db]
            [junbi.audit :as audit]
            [junbi.graph :as graph]))

(defn- new-store []
  (audit/store db/api (db/create-conn {})))

(def r1-params
  {:version "1.0.0" :numeraire :usd :band-bps 50
   :weights {:usdc 0.40 :jpyc 0.40 :eurc 0.20}})

(def rates
  {:usdc {:mid 1.0} :jpyc {:mid 0.0066} :eurc {:mid 1.08}})

(def balanced-holdings
  {:usdc {:amount 400000000}
   :jpyc {:amount 60606060606060606060606}
   :eurc {:amount 185185185}})

(deftest propose-commit-flow
  (testing "skewed reserve → advisor proposes → governor approves → audit :commit"
    (let [store (new-store)
          actor (graph/build store)
          res (g/run* actor
                      {:request {:action/type :rebalance/propose}
                       :params r1-params
                       :holdings (assoc balanced-holdings :usdc {:amount 900000000})
                       :rates rates}
                      {:thread-id "t-propose"})]
      (is (= :done (:status res)))
      (is (= :commit (:disposition (:state res))))
      (let [es (audit/entries store)]
        (is (= 1 (count es)))
        (is (= :commit (:junbi.audit/type (first es))))))))

(deftest balanced-reserve-is-a-noop
  (testing "no drift past trigger → :noop, nothing written to the ledger"
    (let [store (new-store)
          actor (graph/build store)
          res (g/run* actor
                      {:request {:action/type :rebalance/propose}
                       :params r1-params
                       :holdings balanced-holdings
                       :rates rates}
                      {:thread-id "t-noop"})]
      (is (= :noop (:disposition (:state res))))
      (is (empty? (audit/entries store))))))

(deftest forbidden-action-holds-with-gate
  (testing "token mint is structurally rejected (J1) and audited as :hold"
    (let [store (new-store)
          actor (graph/build store)
          res (g/run* actor
                      {:request {:action/type :token/mint}}
                      {:thread-id "t-mint"})]
      (is (= :hold (:disposition (:state res))))
      (let [e (first (audit/entries store))]
        (is (= :hold (:junbi.audit/type e)))
        (is (= :j1 (:junbi.audit/gate e)))))))

(deftest execution-interrupts-for-human-approval
  (let [store (new-store)
        actor (graph/build store)
        req {:request {:action/type :rebalance/execute
                       :fills [{:price 1.002 :mid 1.0}]}}]
    (testing "J10 — execution stops at the approval interrupt"
      (let [res (g/run* actor req {:thread-id "t-exec"})]
        (is (= :interrupted (:status res)))
        (is (empty? (audit/entries store)))))
    (testing "human approval resumes → governor re-review → commit"
      (let [res (g/run* actor {:approval {:status :approved :by "did:web:treasurer"}}
                        {:thread-id "t-exec" :resume? true})]
        (is (= :done (:status res)))
        (is (= :commit (:disposition (:state res))))
        (is (= [:commit] (mapv :junbi.audit/type (audit/entries store))))))))

(deftest human-rejection-holds
  (let [store (new-store)
        actor (graph/build store)]
    (g/run* actor
            {:request {:action/type :rebalance/execute
                       :fills [{:price 1.002 :mid 1.0}]}}
            {:thread-id "t-rej"})
    (let [res (g/run* actor {:approval {:status :rejected :by "did:web:treasurer"}}
                      {:thread-id "t-rej" :resume? true})]
      (is (= :hold (:disposition (:state res))))
      (is (= :j10 (:junbi.audit/gate (first (audit/entries store))))))))

(deftest out-of-band-fill-rejected-even-with-approval
  (testing "J5/J11 — approval cannot override the mid-market band"
    (let [store (new-store)
          actor (graph/build store)]
      (g/run* actor
              {:request {:action/type :rebalance/execute
                         :fills [{:price 1.02 :mid 1.0}]}}
              {:thread-id "t-band"})
      (let [res (g/run* actor {:approval {:status :approved :by "did:web:treasurer"}}
                        {:thread-id "t-band" :resume? true})]
        (is (= :hold (:disposition (:state res))))
        (is (= :hold (:junbi.audit/type (first (audit/entries store)))))))))
