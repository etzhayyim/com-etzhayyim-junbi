(ns junbi.cbdc
  "Tier-2 CBDC attestation intake + Council activation machinery
   (R3a, ADR-2607021800 D3 / gate J9).

   CBDCs (e-CNY / digital euro / digital yen) live on permissioned
   central-bank rails — there is nothing for junbi.chain to read. The ONLY
   admissible evidence of a Tier-2 holding is a **signed authorized-operator
   attestation**:

     {:asset :ecny :amount-atomic 1234 :custodian \"...\"
      :attester-did \"did:web:...\" :attested-at <unix> :ref <doc CID>
      :sig <hex>}

   Validation is structural + allowlist + freshness + signature (through an
   injected `verify-fn` — key resolution is host concern; an attestation
   with NO verifier is :unverified and never becomes a holding). Council
   ACTIVATION (weight > 0) is a separate, stricter act: `activate` validates
   the Lv7+-unanimity record (legal analysis + custody attestation) and
   returns the updated params — the actual vote and the actual custody are
   ops/legal acts this code cannot and does not fake."
  (:require [clojure.string :as str]
            [junbi.core :as core]))

(def max-attestation-age-s
  "Freshness window: a custody attestation older than this never counts."
  86400)

(defn tier2-asset? [asset]
  (= 2 (:tier (core/assets asset))))

(defn validate-attestation
  "Validate one operator attestation at unix time `now`.
   opts: :attesters (Council-attested DID allowlist, set)
         :verify-fn (fn [attester-did attestation-sans-sig sig] -> bool)
   Returns {:valid? bool :reason kw?}."
  [{:keys [asset amount-atomic attester-did attested-at sig] :as att}
   {:keys [attesters verify-fn now]}]
  (cond
    (not (tier2-asset? asset))
    {:valid? false :reason :not-tier2}

    (or (nil? amount-atomic) (neg? amount-atomic))
    {:valid? false :reason :bad-amount}

    (or (str/blank? (str attester-did))
        (not (contains? (or attesters #{}) attester-did)))
    {:valid? false :reason :attester-not-allowlisted}

    (or (nil? attested-at) (> (- now attested-at) max-attestation-age-s))
    {:valid? false :reason :stale}

    (nil? verify-fn)
    {:valid? false :reason :unverified}          ; no verifier → never a holding

    (not (verify-fn attester-did (dissoc att :sig) sig))
    {:valid? false :reason :bad-signature}

    :else {:valid? true}))

(defn tier2-holdings
  "Fold valid attestations into the junbi.core holdings shape
   {asset {:amount N}} (latest attestation per asset wins by :attested-at).
   Returns {:holdings .. :accepted [..] :rejected [{:attestation .. :reason ..}]}."
  [attestations opts]
  (reduce
   (fn [acc {:keys [asset amount-atomic attested-at] :as att}]
     (let [{:keys [valid? reason]} (validate-attestation att opts)]
       (if-not valid?
         (update acc :rejected conj {:attestation att :reason reason})
         (let [prev (get-in acc [:by-asset asset])]
           (if (and prev (>= (:attested-at prev) attested-at))
             (update acc :accepted conj att)
             (-> acc
                 (assoc-in [:by-asset asset] att)
                 (assoc-in [:holdings asset] {:amount amount-atomic})
                 (update :accepted conj att)))))))
   {:holdings {} :by-asset {} :accepted [] :rejected []}
   attestations))

(defn merge-holdings
  "Tier-1 (chain-read) + Tier-2 (attested) holdings. A Tier-2 asset never
   overwrites a Tier-1 read (they are disjoint by construction — tier is a
   registry property)."
  [tier1 tier2]
  (merge tier2 tier1))

;; ── Council activation (J2/J9 — weight > 0 requires ALL of this) ────────────

(defn validate-activation
  "Validate a Council Lv7+ activation record for a Tier-2 asset:

     {:asset :ecny
      :unanimous? true                 ; Lv7+ unanimity (the vote itself is ops)
      :legal-analysis-cid \"bafy...\"  ; per-jurisdiction analysis document
      :custody-attestation {..}}       ; a currently-valid operator attestation

   Returns {:valid? bool :violations [..]}."
  [{:keys [asset unanimous? legal-analysis-cid custody-attestation]} opts]
  (let [vs (cond-> []
             (not (tier2-asset? asset))
             (conj {:gate :j2 :msg (str asset " is not a whitelisted Tier-2 asset")})

             (not (true? unanimous?))
             (conj {:gate :j9 :msg "Council Lv7+ unanimity record absent"})

             (str/blank? (str legal-analysis-cid))
             (conj {:gate :j9 :msg "per-jurisdiction legal analysis missing"})

             (not (:valid? (validate-attestation (or custody-attestation {}) opts)))
             (conj {:gate :j9 :msg "no currently-valid custody attestation"}))]
    {:valid? (empty? vs) :violations vs}))

(defn activate
  "Apply a validated activation to basket params (adds the asset to
   :activated so a positive weight passes J9). Returns
   {:params <updated>} or {:valid? false :violations [..]} — never activates
   on an incomplete record."
  [params activation opts]
  (let [{:keys [valid? violations]} (validate-activation activation opts)]
    (if valid?
      {:params (update params :activated (fnil conj #{}) (:asset activation))}
      {:valid? false :violations violations})))
