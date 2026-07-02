(ns junbi.cell
  "rebalance-proposer cell (R2, ADR-2607021800 D6).

   ONE tick = ONE bounded observation → (at most) one governed TreasuryActor
   run. There is deliberately NO internal loop: the durable outer loop
   (lease / tick / budget — CLAUDE.md Actors) invokes `tick!` once per
   period and every tick is fully audited. Observation is read-only
   (junbi.chain + junbi.oracle over the injected ITransport); any resulting
   rebalance is a *proposal* that still crosses the governor and the human
   approval interrupt (J10)."
  (:require [langgraph.graph :as g]
            [junbi.chain :as chain]
            [junbi.oracle :as oracle]
            [junbi.audit :as audit]))

(defn observe
  "Read-only reserve observation at unix time `now`:
   holdings (token balances) + attested rates. Nothing is guessed —
   unreadable assets / unattested or invalid feeds are reported as such."
  [transport rpc-url holder now]
  (let [{:keys [holdings unreadable]} (chain/read-holdings transport rpc-url holder)
        {:keys [rates attestations invalid unattested]}
        (oracle/attest-rates transport rpc-url now)]
    {:holdings holdings :unreadable unreadable
     :rates rates :attestations attestations
     :invalid invalid :unattested unattested}))

(defn tick!
  "One bounded cell run: observe → audit the rate attestations → drive the
   TreasuryActor with a :rebalance/propose request. Returns
   {:observation .. :run ..} (the run stops at :interrupted when execution
   would need a human treasurer)."
  [actor store {:keys [transport rpc-url holder params now thread-id]}]
  (let [{:keys [rates attestations invalid] :as obs}
        (observe transport rpc-url holder now)]
    (doseq [a attestations]
      (audit/append! store {:type :rate-attested :detail (pr-str a)}))
    (doseq [a invalid]
      (audit/append! store {:type :rate-invalid :detail (pr-str a)}))
    (let [run (g/run* actor
                      {:request {:action/type :rebalance/propose}
                       :params params
                       :holdings (:holdings obs)
                       :rates rates}
                      {:thread-id thread-id})]
      {:observation obs :run run})))
