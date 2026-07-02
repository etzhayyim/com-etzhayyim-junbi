(ns junbi.oracle-test
  (:require [clojure.test :refer [deftest is testing]]
            [junbi.oracle :as oracle]
            [kotoba.lang.base-l2.rpc :as rpc]))

(def now 1782000000)

(defn- words->hex [& vals]
  (str "0x" (apply str (map #(format "%064x" (biginteger %)) vals))))

(defn- round-transport
  "ITransport returning a canned latestRoundData() for every call."
  [answer updated-at]
  (reify rpc/ITransport
    (-post [_ _url _body]
      {:status 200
       :body (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\""
                  (words->hex 42 answer (- updated-at 30) updated-at 42)
                  "\"}")})))

(deftest fresh-round-attests
  (let [a (oracle/attest (round-transport 99993210 (- now 60)) "http://rpc" :usdc now)]
    (is (:valid? a))
    (is (< (abs (- 0.9999321 (:mid a))) 1e-9))
    (is (= "chainlink:base:USDC/USD@0x7e860098F58bBFC8648a4311b374B1D669a2bc6B"
           (:oracle a)))
    (is (= "42" (:round-id a)))
    (is (= now (:attested-at a)))))

(deftest stale-round-is-invalid
  (testing "older than heartbeat + slack → :stale, never a usable mid"
    (let [a (oracle/attest (round-transport 100000000 (- now 90000)) "http://rpc" :usdc now)]
      (is (not (:valid? a)))
      (is (= :stale (:reason a))))))

(deftest non-positive-answer-is-invalid
  (let [a (oracle/attest (round-transport 0 (- now 60)) "http://rpc" :eurc now)]
    (is (not (:valid? a)))
    (is (= :non-positive (:reason a)))))

(deftest jpy-has-no-base-feed
  (testing "no Chainlink JPY/USD on Base (verified 2026-07-02) — throws, never guesses"
    (is (thrown? clojure.lang.ExceptionInfo
                 (oracle/read-round (round-transport 1 now) "http://rpc" :jpyc)))))

(deftest attest-rates-shape
  (let [{:keys [rates attestations invalid unattested]}
        (oracle/attest-rates (round-transport 100000000 (- now 60)) "http://rpc" now)]
    (is (= 1.0 (get-in rates [:usdc :mid])))
    (is (= 1.0 (get-in rates [:eurc :mid])))
    (is (= 2 (count attestations)))
    (is (empty? invalid))
    (is (= [:jpyc] unattested))))
