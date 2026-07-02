(ns junbi.chain
  "Base L2 read-only reserve observation (R1, ADR-2607021800 D7).

   kotoba read+verify posture: this namespace ONLY reads ERC-20 balances
   through an injected `kotoba.lang.base-l2.rpc/ITransport` — no keys, no
   signing, no sends (custody/signing is etzhayyim-exclusive; J7). JVM-only
   `.clj`, mirroring base-l2's own layer contract (pure core over injected
   transport, zero vendor SDK)."
  (:require [kotoba.lang.base-l2.abi :as abi]
            [kotoba.lang.base-l2.rpc :as rpc]))

(def base-chain-id 8453)

(def token-registry
  "Council-attested token contracts on Base L2 (Tier-1 reserve, gate J2).
   An asset with :address nil is NOT readable yet — never fake a read.
   JPYC's canonical Base deployment awaits Council attestation (kawase-yui
   R2 notes the Polygon-bridged-via-LayerZero route needs a Council audit)."
  {:usdc {:address "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913" :decimals 6}
   :eurc {:address "0x60a3E35Cc302bFA44Cb288Bc5a4F316Fdb1adb42" :decimals 6}
   :jpyc {:address nil :decimals 18}})

(defn balance-of-calldata
  "ERC-20 `balanceOf(address)` calldata for `holder` (0x… hex string)."
  [holder]
  (abi/encode-function-call "balanceOf(address)" ["address"] [holder]))

(defn read-balance
  "Smallest-unit balance of `asset` held by `holder`, as a BigInteger.
   Throws when the asset has no Council-attested address on Base."
  [transport rpc-url asset holder]
  (let [{:keys [address]} (token-registry asset)]
    (when-not address
      (throw (ex-info "no Council-attested token address on Base (J2/J9)"
                      {:asset asset})))
    (let [ret (rpc/eth-call transport rpc-url address (balance-of-calldata holder))
          [bal] (abi/decode-function-result ["uint256"] ret)]
      bal)))

(defn read-holdings
  "Observe all readable Tier-1 holdings of `holder`.
   Returns {:holdings {asset {:amount N}} :unreadable [asset ...]} in the
   shape junbi.core expects. Unattested assets are reported, not guessed."
  [transport rpc-url holder]
  (reduce-kv
   (fn [acc asset {:keys [address]}]
     (if address
       (assoc-in acc [:holdings asset]
                 {:amount (read-balance transport rpc-url asset holder)})
       (update acc :unreadable conj asset)))
   {:holdings {} :unreadable []}
   token-registry))
