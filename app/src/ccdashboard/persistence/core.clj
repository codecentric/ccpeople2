(ns ccdashboard.persistence.core
  (:require [datomic.api :refer [db q] :as d]
            [io.rkn.conformity :as c]
            [ccdashboard.domain.datscript :as datscript]
            [clojure.java.io :as io]
            [environ.core :refer [env]]
            [ccdashboard.util :refer [matching]]
            [plumbing.core :refer [update-in-when]]
            [com.stuartsierra.component :as component]
            ccdashboard.data-readers.local-date
            [ccdashboard.domain.data-model :as model]
            [ccdashboard.domain.core :as domain])
  (:import (java.util.concurrent ExecutionException)))

(def people-tempid
  (partial d/tempid :partition/people))

(defn load-resource [filename] (read-string (slurp (io/reader (io/resource filename)))))

(defn q-one [query & inputs]
  (ffirst (apply q query inputs)))

(defn create-openid-user
  [conn user]
  (try
    (let [user-id (d/squuid)
          r @(d/transact conn [
                               (assoc user
                                 :user/id user-id
                                 :db/id [:user/email (:user/email user)])])
          ;db-after (:db-after r)
          ;tempids (:tempids r)
          ]
      ;(d/resolve-tempid db-after tempids user-tempid)
      user-id)
    (catch ExecutionException ex
      (def lexex ex)
      (when (= (:db/error (ex-data (.getCause ex)))
               :db.error/not-an-entity)
        (throw (ex-info (str "unknown user: " (:user/email user))
                        (assoc (ex-data (.getCause ex))
                          :error :error/unknown-user
                          :user user))))
      (throw ex)
      #_(let [c (.getCause ex)]
        (if (and (instance? IllegalArgumentException c)
                 ))))))

(defn all-usernames [dbval]
  (->> (d/q '{:find  [(pull ?e [:user/jira-username :user/display-name])]
              :in    [$]
              :where [[?e :user/jira-username ?username]]}
            dbval)
       (map first)
       (sort-by :user/jira-username)))

(defn entity-id-by-username [dbval username]
  (q-one '{:find  [?e]
           :in    [$ ?username]
           :where [[?e :user/jira-username ?username]]}
         dbval
         username))

(defn user-id-by-email [dbval email]
  (q-one '{:find  [?id]
           :in    [$ ?email]
           :where [[?u :user/email ?email]
                   [?u :user/id ?id]]}
         dbval
         email))

(defn user-id-by-external-user-id [dbval external-user-id]
  (q-one '{:find [?u]
           :in [$ ?external-user-id]
           :where [[?u :user/id ?external-user-id]]}
         dbval
         external-user-id))

(defn user-id-by-email-or-create [conn domain-user]
  (if-let [id (user-id-by-email (db conn) (:user/email domain-user))]
    id
    (create-openid-user conn domain-user)))

(def as-map
  (comp (partial into {}) d/touch))

(defn pull-without [& ks]
  (comp #(apply dissoc % ks)
        as-map))

(defn domain-worklog [db-worklog]
  (-> db-worklog
      ;(as-map)
      (dissoc :db/id :worklog/description)
      (update :worklog/user :user/jira-username)
      (update :worklog/ticket :ticket/id)
      (model/to-domain-worklog)))

(defn domain-ticket [db-ticket]
  (-> db-ticket
      ;(as-map)
      (dissoc :db/id)
      (update-in-when [:ticket/customer] :customer/id)
      (model/to-domain-ticket)))

(defn domain-customer [db-customer]
  (-> db-customer
      ;(as-map)
      (dissoc :db/id)
      (model/to-domain-customer)))

(defn domain-user [db-user]
  (-> db-user
      ;(as-map)
      (dissoc :db/id)
      (update-in-when [:user/team] :team/id)
      (model/to-domain-user)))

(defn all-worklogs [dbval]
  (into []
        (map first)
        (d/q '{:find  [(pull ?w [* {:worklog/ticket [:ticket/id]} {:worklog/user [:user/jira-username]}])]
               :where [[?w :worklog/id]]}
             dbval)))

(defn all-tickets [dbval]
  (into []
        (map (comp (fn [ticket]
                     (-> ticket
                         (dissoc :ticket/type)
                         (update :ticket/invoicing (fn [ttype] (d/ident dbval (:db/id ttype))))))
                   first))
        (d/q '{:find  [(pull ?w [* {:ticket/customer [:customer/id]}])]
          :where [[?w :ticket/id]]}
        dbval)))

(defn all-customers [dbval]
  (into []
        (map first)
        (d/q '{:find  [(pull ?w [*])]
               :where [[?w :customer/id]]}
             dbval)))

(defn all-users [dbval]
  (into []
        (map first)
        (d/q '{:find  [(pull ?w [*])]
               :where [[?w :user/jira-username]]}
             dbval)))

(defn all-state [dbval]
  (let [db-worklogs (all-worklogs dbval)
        tickets (all-tickets dbval)
        customers (all-customers dbval)]
    {:users     (mapv domain-user (all-users dbval))                                            ;(domain-user user-entity)
     :worklogs  (mapv domain-worklog db-worklogs)
     :tickets   (mapv domain-ticket tickets)
     :customers (mapv domain-customer customers)}))

(defn existing-user-data [dbval id]
  (let [                                                    ;user-entity (d/entity dbval id)
        db-worklogs (all-worklogs dbval)                    ;(:worklog/_user user-entity)
        tickets (all-tickets dbval)                         ;(into #{} (map :worklog/ticket) db-worklogs)
        customers (all-customers dbval)                                           ;(into #{} (keep :ticket/customer) tickets)
        ]
    {:db (datscript/get-state)}
    #_{:users     (mapv domain-user (all-users dbval))                                            ;(domain-user user-entity)
     :worklogs  (mapv domain-worklog db-worklogs)
     :tickets   (mapv domain-ticket tickets)
     :customers (mapv domain-customer customers)}))

(defrecord DatomicDatabase [uri]
  component/Lifecycle
  (start [component]
    (d/create-database uri)
    component)
  (stop [component]
    (d/shutdown false)
    component))

(defn new-datomic-database [config]
  (map->DatomicDatabase {:uri (:connect-url config)}))

(defrecord DatomicConnection []
  component/Lifecycle
  (start [this] (d/connect (get-in this [:database :uri])))
  (stop [this] this))

(defn new-datomic-connection []
  (->DatomicConnection))

(defrecord DatomicSchema [schema-file schema-name]
  component/Lifecycle
  (start [component]
    (c/ensure-conforms (:conn component)
                       (load-resource schema-file)
                       [schema-name])
    component)
  (stop [this] this))

(defn new-conform-schema [options]
  (map->DatomicSchema {:schema-file (:schema-file options)
                       :schema-name (:schema-name options)}))

(defn with-all-users [dbval m]
  (assoc m :users/all (all-usernames dbval)))

(defn add-identity [user-data]
  (assoc user-data :user/identity (get-in user-data [:user :user/jira-username])))

(defn remove-sickness-worklogs [user-data]
  (update user-data :worklogs (fn [worklogs]
                                (->> worklogs
                                     (remove (matching :worklog/ticket domain/sick-leave-ticket-id))
                                     (into [])))))

(defn existing-user-data-for-user [conn user-id]
  (let [dbval (db conn)]
    (some->> (user-id-by-external-user-id dbval user-id)
             (existing-user-data dbval)
             (with-all-users dbval)
             (add-identity))))


(defn all-team-informations [dbval]
  (into #{}
        (map (comp model/to-domain-team
                   (fn [team] (dissoc team :db/id))
                   first))
        (d/q '{:find  [(pull ?t [*])]
               :where [[?t :team/id]]}
             dbval)))

(defn existing-user-data-by-username [conn consultant-username]
  (let [dbval (db conn)]
    (some->> (entity-id-by-username dbval consultant-username)
             (existing-user-data dbval)
             (remove-sickness-worklogs)
             )))

(defn billable-hours-for-teams [dbval]
  (into #{}
        (map (fn [[hours team]]
               (assoc team :team/billable-hours hours)))
        (d/q '{:find  [(sum ?hours) (pull ?team [:team/id :team/name])]
               :with  [?worklog]
               :where [[?ticket :ticket/id]
                       (not-join [?ticket]
                                 [?cc :customer/name "codecentric"]
                                 [?ticket :ticket/customer ?cc]
                                 (not [?ticket :ticket/invoicing :invoicing/support]))
                       (not [?ticket :ticket/invoicing :invoicing/not-billable])
                       [?worklog :worklog/ticket ?ticket]
                       [?worklog :worklog/hours ?hours]
                       [?worklog :worklog/user ?user]
                       [?user :user/team ?team]]}
             dbval)))

(defn members-in-teams-map [dbval]
  (into {}
        (d/q '{:find [?id (count ?members)]
               :where [[?team :team/id ?id]
                       [?members :user/team ?team]]}
             dbval)))

(defn teams-stats-seq [dbval]
  (let [members-map (members-in-teams-map dbval)
        billable-hours-seq (billable-hours-for-teams dbval)]
      (into #{}
            (for [team billable-hours-seq]
              (assoc team :team/member-count (get members-map (:team/id team)))))))
