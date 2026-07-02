(ns junbi.cell-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [langchain.db :as db]
            [junbi.audit :as audit]
            [junbi.cell :as cell]
            [junbi.graph :as graph]
            [kotoba.lang.base-l2.rpc :as rpc]))

(def now 1782000000)
(def holder "0x1111111111111111111111111111111111111111")

(defn- words->hex [& vals]
  (apply str (map #(format "%064x" (biginteger %)) vals)))

(def routes
  "to-address (lowercase) → ABI-encoded result. Tokens answer balanceOf,
   feeds answer latestRoundData."
  {;; USDC token: 900 USDC
   "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913" (words->hex 900000000)
   ;; EURC token: 370.370370 EURC (≈ $400 at 1.08)
   "0x60a3e35cc302bfa44cb288bc5a4f316fdb1adb42" (words->hex 370370370)
   ;; USDC/USD feed: 1.00000000
   "0x7e860098f58bbfc8648a4311b374b1d669a2bc6b" (words->hex 42 100000000 (- now 90) (- now 60) 42)
   ;; EURC/USD feed: 1.08000000
   "0xdae398520e2b67cd3f27aef9cf14d93d927f8250" (words->hex 42 108000000 (- now 90) (- now 60) 42)})

(defn- routed-transport []
  (reify rpc/ITransport
    (-post [_ _url body]
      (let [b (str/lower-case body)
            hit (some (fn [[addr res]] (when (str/includes? b addr) res)) routes)]
        (if hit
          {:status 200
           :body (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" hit "\"}")}
          {:status 500 :body "{\"error\":{\"message\":\"unrouted\"}}"})))))

(defn- new-store [] (audit/store db/api (db/create-conn {})))

(def usdc-eurc-params
  {:version "t" :numeraire :usd :band-bps 50
   :weights {:usdc 0.6 :eurc 0.4}})

(deftest observation-is-honest
  (let [{:keys [holdings unreadable rates unattested]}
        (cell/observe (routed-transport) "http://rpc" holder now)]
    (is (= (biginteger 900000000) (get-in holdings [:usdc :amount])))
    (is (= [:jpyc] unreadable))
    (is (= 1.08 (get-in rates [:eurc :mid])))
    (is (= [:jpyc] unattested))))

(deftest skewed-reserve-tick-proposes-and-commits
  (testing "usdc overweight (≈89% vs 60%) → proposal → governed commit + full audit trail"
    (let [store (new-store)
          actor (graph/build store)
          {:keys [run]} (cell/tick! actor store
                                    {:transport (routed-transport)
                                     :rpc-url "http://rpc"
                                     :holder holder
                                     :params usdc-eurc-params
                                     :now now
                                     :thread-id "tick-1"})]
      (is (= :done (:status run)))
      (is (= :commit (:disposition (:state run))))
      (is (= [:rate-attested :rate-attested :commit]
             (mapv :junbi.audit/type (audit/entries store)))))))

(deftest tier2-attestations-flow-through-the-tick
  (testing "R3a — accepted + rejected CBDC attestations are merged and audited"
    (let [store (new-store)
          actor (graph/build store)
          operator "did:web:custodian.example"
          {:keys [observation]}
          (cell/tick! actor store
                      {:transport (routed-transport)
                       :rpc-url "http://rpc"
                       :holder holder
                       :params usdc-eurc-params
                       :now now
                       :thread-id "tick-cbdc"
                       :cbdc {:attestations
                              [{:asset :ecny :amount-atomic 500000
                                :attester-did operator :attested-at (- now 60)
                                :ref "bafy-doc" :sig "good-sig"}
                               {:asset :ecny :amount-atomic 900000
                                :attester-did "did:web:stranger"
                                :attested-at (- now 60) :sig "good-sig"}]
                              :attesters #{operator}
                              :verify-fn (fn [_ _ sig] (= "good-sig" sig))}})]
      (is (= 500000 (get-in observation [:tier2 :holdings :ecny :amount])))
      (is (= 1 (count (get-in observation [:tier2 :rejected]))))
      (let [types (frequencies (map :junbi.audit/type (audit/entries store)))]
        (is (= 1 (:cbdc-attested types)))
        (is (= 1 (:cbdc-rejected types)))))))

(deftest balanced-reserve-tick-is-noop
  (testing "on-target reserve → no proposal → :noop (rate attestations still audited)"
    (let [store (new-store)
          actor (graph/build store)
          ;; 600 USDC / $400 of EURC at 1.08 = exactly on target
          balanced (assoc routes
                          "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913" (words->hex 600000000))
          transport (reify rpc/ITransport
                      (-post [_ _url body]
                        (let [b (str/lower-case body)
                              hit (some (fn [[addr res]] (when (str/includes? b addr) res)) balanced)]
                          {:status 200
                           :body (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" hit "\"}")})))
          {:keys [run]} (cell/tick! actor store
                                    {:transport transport
                                     :rpc-url "http://rpc"
                                     :holder holder
                                     :params usdc-eurc-params
                                     :now now
                                     :thread-id "tick-2"})]
      (is (= :noop (:disposition (:state run))))
      (is (= [:rate-attested :rate-attested]
             (mapv :junbi.audit/type (audit/entries store)))))))
