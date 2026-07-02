(ns junbi.core
  "junbi (準備) — etzhayyim reserve treasury core (ADR-2607021800).

   Pure .cljc: reserve-asset registry, HAKARI (秤) basket params validation
   (gates J2–J5, J9), NAV, weight drift and rebalance *proposals* (J10).
   No on-chain I/O here — base-l2 / banking wiring is R1.

   HAKARI is an SDR-style unit of account. It is NEVER minted as a token
   (J1, Charter Rider §2(b) / ADR-2605172100 Alt C).")

;; ── reserve asset registry (ADR-2607021800 D2/D3) ───────────────────────────

(def assets
  "Issuer whitelist. Tier 1 = on-chain Base L2 stablecoins from trusted,
   attested issuers, active by ADR. Tier 2 = CBDC on permissioned rails,
   attestation-observed, weight 0 until Council Lv7+ unanimity activation."
  {:usdc  {:issuer "Circle Internet Financial" :kind :fiat-stablecoin
           :tier 1 :chain :base-l2 :decimals 6 :currency "USD"}
   :eurc  {:issuer "Circle Internet Financial" :kind :fiat-stablecoin
           :tier 1 :chain :base-l2 :decimals 6 :currency "EUR"}
   :jpyc  {:issuer "JPYC株式会社" :kind :fiat-stablecoin
           :tier 1 :chain :base-l2 :decimals 18 :currency "JPY"}
   :ecny  {:issuer "中国人民銀行 (PBOC)" :kind :cbdc
           :tier 2 :chain :permissioned :decimals 2 :currency "CNY"}
   :d-eur {:issuer "European Central Bank" :kind :cbdc
           :tier 2 :chain :permissioned :decimals 2 :currency "EUR"}
   :d-jpy {:issuer "日本銀行 (BoJ)" :kind :cbdc
           :tier 2 :chain :permissioned :decimals 2 :currency "JPY"}})

(def excluded-assets
  "J3 — structurally rejected. Readmission = Council Lv7+ unanimity + ADR
   amendment, not a params edit."
  #{:usdt})

(def max-band-bps
  "J5 — mid-market rate band ceiling, = kawase-yui KAWASE_MAX_BAND_BPS."
  50)

(def ^:private weight-epsilon 1e-9)

;; ── basket params validation (J2–J5, J9) ────────────────────────────────────

(defn- violation [gate msg] {:gate gate :msg msg})

(defn validate-params
  "Validate a HAKARI basket params blob:

     {:version \"1.0.0\" :numeraire :usd :band-bps 50
      :weights {:usdc 0.40 :jpyc 0.40 :eurc 0.20}
      :activated #{:ecny}}         ; Council-activated Tier-2 assets

   Returns {:valid? bool :violations [{:gate :jN :msg ...}]}."
  [{:keys [weights band-bps activated] :as _params}]
  (let [activated (or activated #{})
        vs (cond-> []
             ;; J3 before J2: excluded assets get the specific gate
             (some excluded-assets (keys weights))
             (conj (violation :j3 (str "excluded asset in weights: "
                                       (filterv excluded-assets (keys weights)))))

             (seq (remove #(or (contains? assets %) (excluded-assets %))
                          (keys weights)))
             (conj (violation :j2 (str "unknown asset(s): "
                                       (vec (remove #(or (contains? assets %)
                                                         (excluded-assets %))
                                                    (keys weights))))))

             (some neg? (vals weights))
             (conj (violation :j4 "negative weight"))

             (> (abs (- 1.0 (reduce + 0.0 (vals weights)))) weight-epsilon)
             (conj (violation :j4 (str "Σ weights ≠ 1.0: "
                                       (reduce + 0.0 (vals weights)))))

             (and band-bps (> band-bps max-band-bps))
             (conj (violation :j5 (str "band-bps " band-bps " > " max-band-bps
                                       " (cannot be widened)")))

             (seq (for [[a w] weights
                        :when (and (pos? w)
                                   (= 2 (:tier (assets a)))
                                   (not (activated a)))]
                    a))
             (conj (violation :j9 (str "Tier-2 asset with positive weight but no "
                                       "Council activation: "
                                       (vec (for [[a w] weights
                                                  :when (and (pos? w)
                                                             (= 2 (:tier (assets a)))
                                                             (not (activated a)))]
                                              a))))))]
    {:valid? (empty? vs) :violations vs}))

;; ── effective weights (Tier-2 renormalization, D3) ──────────────────────────

(defn effective-weights
  "Weights over *active* assets, renormalized to Σ = 1. An asset is active
   when Tier 1, or Tier 2 with Council activation. An unactivated /
   custody-unavailable CBDC therefore never breaks the basket."
  [{:keys [weights activated]}]
  (let [activated (or activated #{})
        active (into {}
                     (filter (fn [[a w]]
                               (and (pos? w)
                                    (contains? assets a)
                                    (or (= 1 (:tier (assets a)))
                                        (activated a))))
                             weights))
        total (reduce + 0.0 (vals active))]
    (when (pos? total)
      (into {} (map (fn [[a w]] [a (/ w total)]) active)))))

;; ── NAV (J5 rates are mid-market oracle attestations) ───────────────────────

(defn nav
  "Value of 1 HAKARI in the numeraire.
   `rates` = {asset {:mid <numeraire per 1 asset unit> ...}}.
   Uses effective (renormalized) weights. Returns nil when a needed rate
   is missing — never guesses a price."
  [params rates]
  (let [ws (effective-weights params)]
    (when (and ws (every? #(get-in rates [% :mid]) (keys ws)))
      (reduce + 0.0 (map (fn [[a w]] (* w (get-in rates [a :mid]))) ws)))))

(defn band-ok?
  "J5/J11 — is an execution price within band-bps of the oracle mid?"
  [price mid band-bps]
  (and (pos? mid)
       (<= (abs (- price mid)) (* mid band-bps 1e-4))))

;; ── holdings valuation, drift, rebalance proposal (J10) ─────────────────────

(defn holding-value
  "Value of one holding {:amount <int smallest unit>} of `asset` in the
   numeraire at the attested mid rate."
  [asset {:keys [amount]} rates]
  (let [{:keys [decimals]} (assets asset)
        mid (get-in rates [asset :mid])]
    (when (and decimals mid)
      #?(:clj  (* (/ (double amount) (Math/pow 10 decimals)) mid)
         :cljs (* (/ amount (js/Math.pow 10 decimals)) mid)))))

(defn actual-weights
  "Observed reserve weights from holdings {asset {:amount n}} at attested
   rates. Returns {asset weight} with Σ = 1, or nil on empty/valueless."
  [holdings rates]
  (let [vals* (into {}
                    (keep (fn [[a h]]
                            (when-let [v (holding-value a h rates)]
                              [a v])))
                    holdings)
        total (reduce + 0.0 (vals vals*))]
    (when (pos? total)
      (into {} (map (fn [[a v]] [a (/ v total)]) vals*)))))

(defn drift-bps
  "Per-asset drift of actual vs target (effective) weights, in basis points.
   Positive = overweight."
  [params holdings rates]
  (let [target (effective-weights params)
        actual (actual-weights holdings rates)]
    (when (and target actual)
      (into {}
            (map (fn [a]
                   [a (* 10000.0 (- (get actual a 0.0) (get target a 0.0)))]))
            (into #{} (concat (keys target) (keys actual)))))))

(def default-trigger-bps
  "Rebalance proposal trigger: |drift| ≥ 500 bps (5% of basket weight)."
  500)

(defn rebalance-proposal
  "J10 — proposal ONLY. When any asset drifts past `trigger-bps`, return

     {:proposal/type :rebalance
      :proposal/only true
      :trades [{:asset a :side :buy|:sell :value <numeraire amount>} ...]}

   Trades restore target weights at mid-market value (J11). Returns nil when
   no drift crosses the trigger. Execution is a separate, human-approved act."
  [params holdings rates & {:keys [trigger-bps] :or {trigger-bps default-trigger-bps}}]
  (let [target (effective-weights params)
        drifts (drift-bps params holdings rates)]
    (when (and target drifts
               (some #(>= (abs %) trigger-bps) (vals drifts)))
      (let [total (reduce + 0.0
                          (keep (fn [[a h]] (holding-value a h rates)) holdings))
            trades (->> (keys target)
                        (keep (fn [a]
                                (let [target-v (* (target a) total)
                                      actual-v (or (holding-value a (holdings a) rates) 0.0)
                                      dv (- target-v actual-v)]
                                  (when (> (abs dv) weight-epsilon)
                                    {:asset a
                                     :side (if (pos? dv) :buy :sell)
                                     :value (abs dv)}))))
                        vec)]
        {:proposal/type :rebalance
         :proposal/only true
         :trades trades}))))

;; ── minimal double-entry check (J8; replaced by kotoba-lang/banking at R1) ──

(defn balanced?
  "J8 — entries [{:account s :side :debit|:credit :amount int :currency s}]
   balance when Σdebit = Σcredit per currency."
  [entries]
  (and (seq entries)
       (every? (fn [[_cur es]]
                 (= (reduce + 0 (map :amount (filter #(= :debit (:side %)) es)))
                    (reduce + 0 (map :amount (filter #(= :credit (:side %)) es)))))
               (group-by :currency entries))))
