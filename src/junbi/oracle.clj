(ns junbi.oracle
  "Chainlink mid-market rate attestation (R2, ADR-2607021800 D5/D6, gate J5).

   Reads AggregatorV3 `latestRoundData()` on Base through the injected
   base-l2 ITransport (read-only — same posture as junbi.chain). Every read
   is validated (positive answer, staleness within the feed heartbeat) and
   returned as an attestation map for junbi.core `rates` / the audit ledger.
   Feed addresses are Council-attested; a nil address is never guessed.

   Canonical proxies verified against the Chainlink reference data directory
   (feeds-ethereum-mainnet-base-1.json, 2026-07-02): non-SVR paths
   usdc-usd / eurc-usd. There is NO JPY/USD feed on Base as of 2026-07-02 —
   the JPYC rate stays unattested until the Council attests an alternative
   oracle route."
  (:require [kotoba.lang.base-l2.abi :as abi]
            [kotoba.lang.base-l2.rpc :as rpc]))

(def feed-registry
  "Council-attested AggregatorV3 proxies on Base L2 (numeraire = USD)."
  {:usdc {:address "0x7e860098F58bBFC8648a4311b374B1D669a2bc6B"
          :pair "USDC/USD" :decimals 8 :heartbeat 86400}
   :eurc {:address "0xDAe398520e2B67cd3f27aeF9Cf14D93D927f8250"
          :pair "EURC/USD" :decimals 8 :heartbeat 86400}
   :jpyc {:address nil
          :pair "JPY/USD" :decimals 8 :heartbeat nil}})

(def staleness-slack-s
  "Grace period beyond the feed heartbeat before a round is stale."
  300)

(defn latest-round-calldata []
  (abi/encode-function-call "latestRoundData()" [] []))

(defn read-round
  "Raw `latestRoundData()` of `asset`'s feed. Throws when the asset has no
   Council-attested feed address."
  [transport rpc-url asset]
  (let [{:keys [address]} (feed-registry asset)]
    (when-not address
      (throw (ex-info "no Council-attested oracle feed on Base (J5)"
                      {:asset asset})))
    (let [ret (rpc/eth-call transport rpc-url address (latest-round-calldata))
          [round-id answer started-at updated-at answered-in]
          (abi/decode-function-result
           ["uint80" "int256" "uint256" "uint256" "uint80"] ret)]
      {:round-id round-id :answer answer :started-at started-at
       :updated-at updated-at :answered-in-round answered-in})))

(defn attest
  "Read + validate one feed at unix time `now` (injected — no wall clock in
   library code). Returns
     {:valid? true  :asset a :mid <double> :oracle <str> :round-id .. :updated-at .. :attested-at now}
   or
     {:valid? false :asset a :reason :non-positive|:stale ...}."
  [transport rpc-url asset now]
  (let [{:keys [pair decimals heartbeat address]} (feed-registry asset)
        {:keys [round-id answer updated-at]} (read-round transport rpc-url asset)
        answer-l (long answer)
        updated-l (long updated-at)
        age (- now updated-l)]
    (cond
      (not (pos? answer-l))
      {:valid? false :asset asset :reason :non-positive :answer answer-l}

      (> age (+ heartbeat staleness-slack-s))
      {:valid? false :asset asset :reason :stale
       :age-s age :heartbeat heartbeat :updated-at updated-l}

      :else
      {:valid? true
       :asset asset
       :mid (/ (double answer-l) (Math/pow 10 decimals))
       :oracle (str "chainlink:base:" pair "@" address)
       :round-id (str round-id)
       :updated-at updated-l
       :attested-at now})))

(defn attest-rates
  "Attest every feed with a Council-attested address. Returns
     {:rates {asset {:mid ..}}    ; junbi.core `rates` shape (valid only)
      :attestations [..]          ; full attestation maps (valid only)
      :invalid [..]               ; failed validations
      :unattested [asset ..]}     ; no feed address — reported, never guessed"
  [transport rpc-url now]
  (reduce-kv
   (fn [acc asset {:keys [address]}]
     (if-not address
       (update acc :unattested conj asset)
       (let [a (attest transport rpc-url asset now)]
         (if (:valid? a)
           (-> acc
               (assoc-in [:rates asset] {:mid (:mid a)})
               (update :attestations conj a))
           (update acc :invalid conj a)))))
   {:rates {} :attestations [] :invalid [] :unattested []}
   feed-registry))
