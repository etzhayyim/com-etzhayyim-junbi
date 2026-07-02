(ns junbi.graph
  "TreasuryActor — one treasury operation = one supervised langgraph-clj
   StateGraph run (R1, ADR-2607021800 D1/D6).

     intake → advise → govern → decide → commit | hold | request-approval

   Actor pattern (robotaxi/gftd-talent/itonami 同型): the advisor is confined
   to one node and returns *proposals only*; junbi.governor censors every
   action (J1–J12); execution ALWAYS routes through a human approval
   interrupt (interrupt-before :request-approval, gate J10); every commit
   AND hold lands on the append-only audit ledger.

   Single invariant: the treasury never performs a write/acquire/execute
   the TreasuryGovernor rejects."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [junbi.core :as core]
            [junbi.governor :as gov]
            [junbi.audit :as audit]))

(defn mock-advisor
  "Default advisor — a pure function of state, no LLM. For a
   :rebalance/propose request it derives the trade list from observed
   holdings/rates via junbi.core (deterministic); any other request passes
   through unchanged as the action to be governed. A real LLM advisor
   (langchain.model) swaps in through the same seam and is equally bound
   by the governor."
  []
  (fn [{:keys [request params holdings rates]}]
    (if (= :rebalance/propose (:action/type request))
      (when-let [p (core/rebalance-proposal params holdings rates)]
        (merge request p))
      request)))

(defn- detail [action]
  (pr-str (dissoc action :activated)))

(defn build
  "Compile a TreasuryActor bound to an audit `store` (junbi.audit/store).
   opts: :advisor (default mock-advisor), :checkpointer (default in-mem)."
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request  {:default nil}
         :params   {:default nil}
         :holdings {:default nil}
         :rates    {:default nil}
         :action   {:default nil}
         :verdict  {:default nil}
         :disposition {:default nil}
         :approval {:default nil}
         :audit    {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [s] {:action (advisor s)}))

      ;; nothing to do (e.g. no drift past trigger) — an observed no-op.
      (g/add-node :noop
        (fn [_] {:disposition :noop}))

      (g/add-node :govern
        (fn [{:keys [action]}]
          {:verdict (gov/review action)}))

      (g/add-node :decide
        (fn [{:keys [verdict]}]
          {:disposition (case (:verdict verdict)
                          :approve :commit
                          :human   :escalate
                          :reject  :hold)}))

      ;; interrupt-before: a human treasurer resumes with
      ;; {:approval {:status :approved|:rejected :by <did>}} (J10).
      (g/add-node :request-approval
        (fn [{:keys [action approval]}]
          (if (= :approved (:status approval))
            (let [v (gov/review (assoc action :human-approved? true))]
              (if (= :approve (:verdict v))
                {:verdict v :disposition :commit}
                {:verdict v :disposition :hold}))
            {:disposition :hold
             :verdict {:verdict :reject :gate :j10
                       :reasons ["human approval rejected or absent"]}})))

      (g/add-node :commit
        (fn [{:keys [action verdict approval]}]
          (let [f (audit/append! store
                                 {:type :commit
                                  :verdict (:verdict verdict)
                                  :gate (when approval :j10)
                                  :detail (detail action)})]
            {:audit [f]})))

      (g/add-node :hold
        (fn [{:keys [action verdict]}]
          (let [f (audit/append! store
                                 {:type :hold
                                  :verdict (:verdict verdict)
                                  :gate (:gate verdict)
                                  :detail (detail action)})]
            {:audit [f]})))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-conditional-edges :advise
        (fn [{:keys [action]}] (if action :govern :noop)))
      (g/add-edge :govern :decide)
      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))
      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}] (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :noop)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer checkpointer :interrupt-before #{:request-approval}})))
