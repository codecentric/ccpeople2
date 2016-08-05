(ns ccdashboard.domain.datscript
  (:require [datascript.core :as datascript]
            [datascript.transit :as dt]
            #?@(:cljs [[plumbing.core :refer-macros [fnk]]]
                :clj [[plumbing.core :refer [fnk]]])
            [plumbing.graph :as graph]))


(def client-schema {:worklog/id {:db/unique :db.unique/identity}
                    :worklog/ticket {:db/valueType :db.type/ref}
                    :worklog/user {:db/valueType :db.type/ref}
                    :ticket/id {:db/unique :db.unique/identity}
                    :ticket/customer {:db/valueType :db.type/ref}
                    :customer/id {:db/unique :db.unique/identity}
                    :user/jira-username {:db/unique :db.unique/identity}
                    :user/team {:db/valueType :db.type/ref}
                    :team/id {:db/unique :db.unique/identity}
                    })

(def conn (datascript/create-conn client-schema))



(def init-graph {:user-tx (fnk [users]
                            users)
                 :customer-tx (fnk [customers]
                                customers)
                 :ticket-tx (fnk [tickets]
                              (into []
                                    (map (fn [ticket]
                                           (update ticket :ticket/customer #(vector :customer/id %))))
                                    tickets))
                 :worklog-tx (fnk [worklogs]
                               (into []
                                     (map (fn [worklog]
                                            (-> worklog
                                                (update :worklog/ticket #(vector :ticket/id %))
                                                (update :worklog/user #(vector :user/jira-username %)))))
                                     worklogs))
                 :all-txes (fnk [user-tx customer-tx ticket-tx worklog-tx]
                             [user-tx
                              customer-tx
                              ticket-tx
                              worklog-tx])})

(def init-state (graph/compile init-graph))

(defn init! [server-state]
  (let [result (init-state server-state)
        _ (def jfjf result)]
    (doseq [tx (:all-txes result)]
      (datascript/transact! conn tx))
    :done))

(defn get-state []
  (datascript/db conn))

(defn non-codecentric-customers [dbval]
  (->> (datascript/q '{:find  [?c ?cn]
                   :where [[?c :customer/name ?cn]]}
                 dbval)
       (keep (fn [[customer-entid customer-name]]
               (when-not (= customer-name "codecentric")
                 customer-entid)))))

(defn billable-invoicing-type [dbval]
  (->> (datascript/q '{:find  [?invoicing-type]
                       :where [[?i :ticket/invoicing ?invoicing-type]]}
                     dbval)
       (map first)
       (remove #{:invoicing/not-billable})))

(defn billable [user-jiraname billable-invoice-types non-codecentric-customers]
  (datascript/q '{:find  [(sum ?hours)]
                  :in    [$ ?user-name [?billable-invoicing ...] [?billable-customer ...]]
                  :with  [?worklog]
                  :where [[?user :user/jira-username ?user-name]
                          [?worklog :worklog/user ?user]
                          [?ticket :ticket/customer ?billable-customer]
                          [?ticket :ticket/invoicing ?billable-invoicing]
                          [?worklog :worklog/ticket ?ticket]
                          [?worklog :worklog/hours ?hours]]}
                (datascript/db conn)
                user-jiraname
                billable-invoice-types
                non-codecentric-customers))
