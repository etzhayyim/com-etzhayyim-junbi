(ns junbi.chain-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [junbi.chain :as chain]
            [kotoba.lang.base-l2.rpc :as rpc]))

(def holder "0x1111111111111111111111111111111111111111")

(defn- mock-transport
  "ITransport returning a canned uint256 result for every eth_call."
  [balance]
  (reify rpc/ITransport
    (-post [_ _url _body]
      {:status 200
       :body (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\""
                  (format "0x%064x" balance) "\"}")})))

(deftest calldata-shape
  (testing "balanceOf(address) selector + padded holder"
    (let [cd (chain/balance-of-calldata holder)]
      (is (str/starts-with? cd "0x70a08231"))
      (is (= (+ 2 8 64) (count cd))))))

(deftest read-balance-decodes-uint256
  (testing "400 USDC (6 dp) round-trips through the ABI"
    (is (= (biginteger 400000000)
           (chain/read-balance (mock-transport 400000000) "http://rpc" :usdc holder)))))

(deftest unattested-asset-never-fakes-a-read
  (testing "JPYC has no Council-attested Base address yet (J2/J9)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (chain/read-balance (mock-transport 1) "http://rpc" :jpyc holder)))))

(deftest read-holdings-reports-unreadable
  (let [{:keys [holdings unreadable]}
        (chain/read-holdings (mock-transport 1000000) "http://rpc" holder)]
    (is (= (biginteger 1000000) (get-in holdings [:usdc :amount])))
    (is (= (biginteger 1000000) (get-in holdings [:eurc :amount])))
    (is (= [:jpyc] unreadable))))
