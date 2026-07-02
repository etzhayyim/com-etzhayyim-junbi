(ns junbi.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [junbi.core :as core]))

(def r1-params
  {:version "1.0.0" :numeraire :usd :band-bps 50
   :weights {:usdc 0.40 :jpyc 0.40 :eurc 0.20}})

(def r3-params
  {:version "2.0.0" :numeraire :usd :band-bps 50
   :weights {:usdc 0.35 :jpyc 0.35 :eurc 0.20 :ecny 0.10}
   :activated #{:ecny}})

(def rates
  {:usdc {:mid 1.0}
   :jpyc {:mid 0.0066}
   :eurc {:mid 1.08}
   :ecny {:mid 0.14}})

(deftest params-validation
  (testing "R1 weights are valid"
    (is (:valid? (core/validate-params r1-params))))
  (testing "R3 weights with Council-activated e-CNY are valid"
    (is (:valid? (core/validate-params r3-params))))
  (testing "J3 — USDT is structurally rejected"
    (let [{:keys [valid? violations]}
          (core/validate-params (assoc r1-params :weights
                                       {:usdc 0.4 :jpyc 0.4 :usdt 0.2}))]
      (is (not valid?))
      (is (some #(= :j3 (:gate %)) violations))))
  (testing "J2 — unknown asset rejected"
    (let [{:keys [valid? violations]}
          (core/validate-params (assoc r1-params :weights
                                       {:usdc 0.5 :doge 0.5}))]
      (is (not valid?))
      (is (some #(= :j2 (:gate %)) violations))))
  (testing "J4 — weights must sum to 1"
    (let [{:keys [valid? violations]}
          (core/validate-params (assoc r1-params :weights
                                       {:usdc 0.5 :jpyc 0.4}))]
      (is (not valid?))
      (is (some #(= :j4 (:gate %)) violations))))
  (testing "J4 — negative weight rejected"
    (let [{:keys [valid? violations]}
          (core/validate-params (assoc r1-params :weights
                                       {:usdc 1.2 :jpyc -0.2}))]
      (is (not valid?))
      (is (some #(= :j4 (:gate %)) violations))))
  (testing "J5 — band cannot be widened past 50 bps"
    (let [{:keys [valid? violations]}
          (core/validate-params (assoc r1-params :band-bps 100))]
      (is (not valid?))
      (is (some #(= :j5 (:gate %)) violations))))
  (testing "J9 — Tier-2 CBDC weight requires Council activation"
    (let [{:keys [valid? violations]}
          (core/validate-params (dissoc r3-params :activated))]
      (is (not valid?))
      (is (some #(= :j9 (:gate %)) violations)))))

(deftest effective-weights-renormalization
  (testing "unactivated Tier-2 weight renormalizes away (D3)"
    (let [ws (core/effective-weights (dissoc r3-params :activated))]
      (is (nil? (:ecny ws)))
      (is (< (abs (- 1.0 (reduce + 0.0 (vals ws)))) 1e-9))
      ;; 0.35/0.90 etc.
      (is (< (abs (- (/ 0.35 0.90) (:usdc ws))) 1e-9))))
  (testing "activated Tier-2 keeps its declared weight"
    (let [ws (core/effective-weights r3-params)]
      (is (< (abs (- 0.10 (:ecny ws))) 1e-9)))))

(deftest nav-computation
  (testing "1 HAKARI at R1 weights"
    (is (< (abs (- 0.61864 (core/nav r1-params rates))) 1e-9)))
  (testing "missing rate → nil, never a guessed price"
    (is (nil? (core/nav r1-params (dissoc rates :eurc))))))

(deftest band-check
  (is (core/band-ok? 1.0 1.0 50))
  (is (core/band-ok? 1.004 1.0 50))
  (is (not (core/band-ok? 1.006 1.0 50)))
  (is (not (core/band-ok? 0.99 1.0 50))))

(deftest drift-and-proposal
  (let [;; perfectly balanced holdings for R1 weights at `rates`, total = $1000
        holdings {:usdc {:amount 400000000}          ; 400 USDC (6 dp)
                  :jpyc {:amount 60606060606060606060606} ; ≈ 60,606 JPY (18 dp) ≈ $400
                  :eurc {:amount 185185185}}          ; ≈ 185.19 EURC ≈ $200
        drifts (core/drift-bps r1-params holdings rates)]
    (testing "balanced reserve has sub-trigger drift"
      (is (every? #(< (abs %) 100) (vals drifts)))
      (is (nil? (core/rebalance-proposal r1-params holdings rates))))
    (testing "overweight USDC triggers a proposal (J10 — proposal only)"
      (let [skewed (assoc holdings :usdc {:amount 900000000}) ; 900 USDC
            p (core/rebalance-proposal r1-params skewed rates)]
        (is (some? p))
        (is (:proposal/only p))
        (is (= :sell (:side (first (filter #(= :usdc (:asset %)) (:trades p))))))
        (is (= :buy (:side (first (filter #(= :jpyc (:asset %)) (:trades p))))))))))

(deftest double-entry
  (testing "J8 — balanced posting"
    (is (core/balanced? [{:account "reserve:usdc" :side :debit :amount 100 :currency "USD"}
                         {:account "settlement" :side :credit :amount 100 :currency "USD"}]))
    (is (not (core/balanced? [{:account "reserve:usdc" :side :debit :amount 100 :currency "USD"}
                              {:account "settlement" :side :credit :amount 90 :currency "USD"}])))
    (is (not (core/balanced? [])))))
