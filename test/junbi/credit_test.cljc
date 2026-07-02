(ns junbi.credit-test
  (:require [clojure.test :refer [deftest is testing]]
            [junbi.credit :as credit]))

(def params
  {:version "t" :numeraire :usd :band-bps 50
   :weights {:usdc 0.6 :eurc 0.4}})

(def rates
  {:usdc {:mid 1.0} :eurc {:mid 1.08}})
;; nav = 0.6·1.0 + 0.4·1.08 = 1.032

(deftest limit-sizing
  (testing "$100 USDC stake at 50% LTV → 50/1.032 HAKARI"
    (let [{:keys [stake-value nav limit-hakari limit-en]}
          (credit/credit-limit params rates {:usdc {:amount 100000000}})]
      (is (< (abs (- 100.0 stake-value)) 1e-9))
      (is (< (abs (- 1.032 nav)) 1e-9))
      (is (< (abs (- (/ 50.0 1.032) limit-hakari)) 1e-9))
      (is (= limit-hakari limit-en)))))                 ; en-per-hakari 1.0

(deftest per-member-cap
  (testing "large stake clamps at :max-limit-hakari"
    (let [{:keys [limit-hakari]}
          (credit/credit-limit params rates {:usdc {:amount 10000000000000}})]
      (is (= 1000.0 limit-hakari)))))

(deftest unpriceable-never-sizes
  (testing "missing rate attestation → nil (J5/J9: never guess)"
    (is (nil? (credit/credit-limit params (dissoc rates :eurc)
                                   {:usdc {:amount 100000000}})))
    (is (nil? (credit/credit-limit params rates
                                   {:jpyc {:amount 1000000000000000000}})))))

(deftest en-conversion-is-a-council-param
  (let [{:keys [limit-hakari limit-en]}
        (credit/credit-limit params rates {:usdc {:amount 100000000}}
                             (assoc credit/default-policy :en-per-hakari 2.5))]
    (is (< (abs (- (* 2.5 limit-hakari) limit-en)) 1e-9))))
