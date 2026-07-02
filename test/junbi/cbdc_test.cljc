(ns junbi.cbdc-test
  (:require [clojure.test :refer [deftest is testing]]
            [junbi.cbdc :as cbdc]))

(def now 1782000000)
(def operator "did:web:custodian.example")
(def stub-verify (fn [_did _payload sig] (= "good-sig" sig)))
(def opts {:attesters #{operator} :verify-fn stub-verify :now now})

(defn- att [& {:as over}]
  (merge {:asset :ecny :amount-atomic 500000 :custodian "PBOC-authorized-op"
          :attester-did operator :attested-at (- now 600)
          :ref "bafy-custody-doc" :sig "good-sig"}
         over))

(deftest attestation-validation
  (testing "fresh, allowlisted, verified → valid"
    (is (:valid? (cbdc/validate-attestation (att) opts))))
  (testing "Tier-1 asset never enters through the attestation door"
    (is (= :not-tier2 (:reason (cbdc/validate-attestation (att :asset :usdc) opts)))))
  (testing "non-allowlisted attester rejected"
    (is (= :attester-not-allowlisted
           (:reason (cbdc/validate-attestation
                     (att :attester-did "did:web:stranger") opts)))))
  (testing "stale attestation never counts"
    (is (= :stale (:reason (cbdc/validate-attestation
                            (att :attested-at (- now 100000)) opts)))))
  (testing "no verifier → :unverified (never a holding)"
    (is (= :unverified (:reason (cbdc/validate-attestation
                                 (att) (dissoc opts :verify-fn))))))
  (testing "bad signature rejected"
    (is (= :bad-signature (:reason (cbdc/validate-attestation
                                    (att :sig "forged") opts))))))

(deftest holdings-fold
  (testing "latest valid attestation per asset wins"
    (let [{:keys [holdings accepted rejected]}
          (cbdc/tier2-holdings
           [(att :amount-atomic 100 :attested-at (- now 3000))
            (att :amount-atomic 200 :attested-at (- now 600))
            (att :sig "forged")]
           opts)]
      (is (= 200 (get-in holdings [:ecny :amount])))
      (is (= 2 (count accepted)))
      (is (= [:bad-signature] (mapv :reason rejected)))))
  (testing "Tier-2 never overwrites a Tier-1 chain read"
    (let [merged (cbdc/merge-holdings {:usdc {:amount 1}} {:ecny {:amount 2}})]
      (is (= {:usdc {:amount 1} :ecny {:amount 2}} merged)))))

(deftest council-activation
  (testing "complete record (unanimity + legal analysis + valid custody) activates"
    (let [{:keys [params]}
          (cbdc/activate {:weights {:usdc 0.35 :jpyc 0.35 :eurc 0.20 :ecny 0.10}}
                         {:asset :ecny :unanimous? true
                          :legal-analysis-cid "bafy-legal"
                          :custody-attestation (att)}
                         opts)]
      (is (contains? (:activated params) :ecny))))
  (testing "no unanimity / no legal analysis / no custody → never activates"
    (doseq [broken [{:asset :ecny :legal-analysis-cid "bafy" :custody-attestation (att)}
                    {:asset :ecny :unanimous? true :custody-attestation (att)}
                    {:asset :ecny :unanimous? true :legal-analysis-cid "bafy"}]]
      (let [r (cbdc/activate {} broken opts)]
        (is (false? (:valid? r)))
        (is (nil? (:params r))))))
  (testing "Tier-1 asset cannot be 'activated' through this door (J2)"
    (let [r (cbdc/activate {} {:asset :usdc :unanimous? true
                               :legal-analysis-cid "bafy"
                               :custody-attestation (att)} opts)]
      (is (false? (:valid? r))))))
