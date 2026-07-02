(ns junbi.audit
  "Append-only treasury audit ledger (R1, ADR-2607021800 D7).

   Talks to the backend ONLY through the langchain.db `:db-api` map
   {:q :transact! :db :pull :entid} — so the same code runs on the
   in-process EAV store, real Datomic, or a kotoba pod (actor Store rule).
   Facts are never retracted: every commit AND hold is appended (the
   audit trail is the point)."
  )

(defn store
  "Bind a :db-api map + connection into an audit store handle."
  [db-api conn]
  {:db-api db-api :conn conn})

(defn- next-seq [{:keys [db-api conn]}]
  (let [db ((:db db-api) conn)
        n ((:q db-api)
           '[:find (count ?e) . :where [?e :junbi.audit/seq _]]
           db)]
    (inc (or n 0))))

(defn append!
  "Append one audit fact {:type kw :verdict kw? :gate kw? :detail str?}.
   Returns the fact as stored (with :junbi.audit/seq)."
  [{:keys [db-api conn] :as s} {:keys [type verdict gate detail]}]
  (let [n (next-seq s)
        fact (cond-> {:junbi.audit/seq n
                      :junbi.audit/type type}
               verdict (assoc :junbi.audit/verdict verdict)
               gate    (assoc :junbi.audit/gate gate)
               detail  (assoc :junbi.audit/detail detail))]
    ((:transact! db-api) conn [fact])
    fact))

(defn entries
  "All audit facts, oldest first."
  [{:keys [db-api conn]}]
  (let [db ((:db db-api) conn)
        eids ((:q db-api)
              '[:find [?e ...] :where [?e :junbi.audit/seq _]]
              db)]
    (->> eids
         (map #((:pull db-api) db '[*] %))
         (sort-by :junbi.audit/seq)
         vec)))
