(ns junbi.governor
  "TreasuryGovernor — the independent censor for junbi (ADR-2607021800).

   Standard actor invariant: the treasury NEVER performs a write/disclose/
   actuate the governor rejects. The intelligence node only *proposes*;
   this namespace decides :approve / :reject / :human.

   Pure .cljc — no I/O. Verdicts reference the CI-greppable gates J1..J12."
  (:require [junbi.core :as core]))

(def forbidden-actions
  "Action types that are structurally impossible — rejected regardless of
   payload (constitutional gates, not policy)."
  {:token/mint          :j1   ; HAKARI is a unit of account, never a token
   :token/deploy-erc20  :j1
   :reserve/lend        :j6
   :reserve/stake       :j6
   :defi/swap-amm       :j6
   :defi/lp-provide     :j6
   :defi/yield          :j6
   :fiat/custody        :j7
   :en/mint             :j12  ; EN is net-zero mutual credit — untouchable here
   :en/burn             :j12})

(defn- reject [gate & reasons]
  {:verdict :reject :gate gate :reasons (vec reasons)})

(defn- human [gate & reasons]
  {:verdict :human :gate gate :reasons (vec reasons)})

(def ^:private approve {:verdict :approve})

(defn- review-acquire
  "J2/J3/J9 — only whitelisted, non-excluded, activation-satisfying assets
   may enter the reserve."
  [{:keys [asset activated]}]
  (let [activated (or activated #{})]
    (cond
      (core/excluded-assets asset)
      (reject :j3 (str asset " is structurally excluded (owner decision 2026-07-02)"))

      (not (contains? core/assets asset))
      (reject :j2 (str asset " is not on the issuer whitelist"))

      (and (= 2 (:tier (core/assets asset))) (not (activated asset)))
      (reject :j9 (str asset " is Tier-2 CBDC without Council Lv7+ activation"))

      :else approve)))

(defn- review-execute
  "J10/J5/J11 — execution needs prior human approval AND every fill locked
   to the oracle mid within band."
  [{:keys [human-approved? fills band-bps] :or {band-bps core/max-band-bps}}]
  (cond
    (not human-approved?)
    (human :j10 "rebalance execution requires human approval (interrupt-before)")

    (not (every? (fn [{:keys [price mid]}] (core/band-ok? price mid band-bps))
                 fills))
    (reject :j5 "fill outside mid-market band" :j11)

    :else approve))

(defn review
  "Review one proposed action map {:action/type kw ...}.
   Returns {:verdict :approve|:reject|:human ...}.
   Unknown action types route to :human — never default-approve."
  [{:keys [action/type] :as action}]
  (cond
    (contains? forbidden-actions type)
    (reject (forbidden-actions type) (str type " is structurally forbidden"))

    (= type :reserve/acquire)
    (review-acquire action)

    (= type :params/update)
    (let [{:keys [valid? violations]} (core/validate-params (:params action))]
      (if valid?
        (human :council "params update needs Council ratification")
        (reject (:gate (first violations)) violations)))

    (= type :rebalance/propose)
    approve                                            ; proposals are always safe (J10)

    (= type :rebalance/execute)
    (review-execute action)

    (= type :ledger/post)
    (if (core/balanced? (:entries action))
      approve
      (reject :j8 "unbalanced double-entry posting"))

    :else
    (human :unknown (str "unknown action type " type " — human review"))))
