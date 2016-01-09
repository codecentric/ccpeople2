(ns app.test.worklog
  (:require [schema.experimental.complete :as c]
            [schema.experimental.generators :as g]
            [app.worklog :refer :all]
            [datomic.api :as d]
            [clj-time.core :as time]
            [clj-time.coerce :as time-coerce]
            [app.system :as system]
            [app.config :as config]
            [app.data-model :as model]
            [com.stuartsierra.component :as component]
            [app.storage :as storage]))

(def ^:const bob-baumeister "bob.baumeister")

(def ^:const bau-issue-id 123)

(def ^:const cc-plus-one-id 999)

(def ^:const bau-company "BAU")

(def ^:const bob-email "bob.baumeister@codecentric.de")

(def ^:const bob-google-id "123")

(def bob-bau-base-worklog {:username bob-baumeister
                           :issue_id bau-issue-id})

(defn complete-non-nil [schema]
  (fn [x]
    (c/complete x schema)))

(def create-scenario
  {:fixture           []                                                       ; empty fixture
   :jira-state        {:prefetched-worklogs (->> [(assoc bob-bau-base-worklog
                                                    :hours 8.
                                                    :work_date (time-coerce/to-date (time/date-time 2015 1 18))
                                                    :worklog_id 1)
                                                  (assoc bob-bau-base-worklog
                                                    :hours 5.
                                                    :work_date (time-coerce/to-date (time/date-time 2015 1 19))
                                                    :worklog_id 2)
                                                  (assoc bob-bau-base-worklog
                                                    :issue_id cc-plus-one-id
                                                    :hours 7.
                                                    :work_date (time-coerce/to-date (time/date-time 2015 1 20))
                                                    :worklog_id 3)]
                                                 (mapv (complete-non-nil model/JiraWorkLog)))
                       :prefetched-issues   (->> [{:id     bau-issue-id
                                                   :fields {:components        [{:id   100
                                                                                 :name bau-company}]
                                                            :customfield_12300 {:value "Nach Aufwand (T&M)"}
                                                            :issuetype         {:name "Quote"}}}
                                                  {:id     cc-plus-one-id
                                                   :fields {:components [{:id   99
                                                                          :name "codecentric"}]
                                                            :issuetype  {:name "Administrative Time"}}}]
                                                 (mapv (complete-non-nil model/JiraIssue)))
                       :prefetched-users    (->> [{:name         bob-baumeister
                                                   :emailAddress bob-email}]
                                                 (mapv (complete-non-nil model/JiraUser)))}
   :result-after-sync [{:key :worklogs
                        :id-key :worklog/id
                        :expected #{{:worklog/id        1
                                      :worklog/hours     8.
                                      :worklog/work-date (time/local-date 2015 1 18)
                                      :worklog/ticket    bau-issue-id}
                                     {:worklog/id        2
                                      :worklog/hours     5.
                                      :worklog/work-date (time/local-date 2015 1 19)
                                      :worklog/ticket    bau-issue-id}
                                     {:worklog/id        3
                                      :worklog/hours     7.
                                      :worklog/work-date (time/local-date 2015 1 20)
                                      :worklog/ticket    cc-plus-one-id}}}
                       {:key :tickets
                        :id-key :ticket/id
                        :expected #{{:ticket/id        bau-issue-id
                                      :ticket/customer  100,
                                      :ticket/invoicing :invoicing/time-monthly,
                                      :ticket/type      :ticket.type/quote}
                                     {:ticket/id       cc-plus-one-id
                                      :ticket/customer 99,
                                      :ticket/type     :ticket.type/admin}}}
                       {:key :customers
                        :id-key :customer/id
                        :expected #{{:customer/id 100, :customer/name "BAU"}
                                     {:customer/id 99, :customer/name "codecentric"}}}]})

(def delete-scenario
  {:fixture           [[{:db/id              (storage/people-tempid)
                         :user/email         bob-email
                         :user/jira-username bob-baumeister
                         :user/google-id     bob-google-id}]
                       [{:db/id            (storage/people-tempid)
                         :ticket/id        222
                         :ticket/key       "*"
                         :ticket/title     "DO IT"
                         :ticket/invoicing :invoicing/fixed-price}]
                       [{:db/id (storage/people-tempid)
                         :worklog/id          111
                         :worklog/hours       8.
                         :worklog/user        [:user/jira-username bob-baumeister]
                         :worklog/work-date   #inst "2016-01-06T00:00:00.000-00:00"
                         :worklog/description "egal"
                         :worklog/ticket      [:ticket/id 222]}]]
   :jira-state        {:prefetched-worklogs []
                       :prefetched-issues   []
                       :prefetched-users    []}
   :result-after-sync {:worklogs #{}}})

(def update-scenario
  {:fixture           [[{:db/id              (storage/people-tempid)
                         :user/email         bob-email
                         :user/jira-username bob-baumeister
                         :user/google-id     bob-google-id}]
                       ;; todo ignores customers
                       [{:db/id            (storage/people-tempid)
                         :ticket/id        222
                         :ticket/key       "*"
                         :ticket/title     "DO IT"
                         :ticket/invoicing :invoicing/fixed-price}]
                       [{:db/id (storage/people-tempid)
                         :worklog/id          333
                         :worklog/hours       8.
                         :worklog/user        [:user/jira-username bob-baumeister]
                         :worklog/work-date   #inst "2015-11-20T00:00:00.000-00:00"
                         :worklog/description "egal"
                         :worklog/ticket      [:ticket/id 222]}]]
   :jira-state        {:prefetched-worklogs [(c/complete (assoc bob-bau-base-worklog
                                                           :hours 2.
                                                           :work_date (java.util.Date.)
                                                           :worklog_id 333
                                                           :issue_id 222)
                                                         model/JiraWorkLog)]
                       :prefetched-issues   []
                       :prefetched-users    []}
   :result-after-sync {:worklogs #{{:worklog/id        333
                                    :worklog/hours     2.
                                    :worklog/work-date (time/today)
                                    :worklog/ticket    222}}}})

(defn in-memory-system [config]
  (assoc-in config [:datomic :connect-url] "datomic:mem://ccpeopletest7"))

(defn new-test-system [scenario]
  (let [schedule-atom (atom nil)]
    (-> (system/new-system (in-memory-system config/defaults))
        (assoc :jira-client (map->JiraFakeClient (:jira-state scenario)))
        (assoc :scheduler (reify Scheduler
                            (schedule [this f]
                              (reset! schedule-atom f))))
        (assoc :schedule-atom schedule-atom))))

(defn test-scenario [scenario]
  (let [sys (component/start (new-test-system scenario))
        conn (:conn sys)]
    (def sys-under-test sys)
    (try
      (doseq [tx (:fixture scenario)]
        @(d/transact conn tx))
      ;; execute the scheduled import synchronously
      (def impres (@(:schedule-atom sys)))
      (def rr (storage/existing-user-data-for-user conn {:user/email     bob-email
                                                         :user/google-id bob-google-id}))

      (finally
        (component/stop sys)))))

