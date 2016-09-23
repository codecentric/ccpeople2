(ns ccdashboard.server.core
  (:require [ccdashboard.persistence.core :as storage]
            [environ.core :refer [env]]
            [hiccup.page :as page]
            [ccdashboard.oauth.core :as oauth]
            [clojure.java.io :as io]
            [ring.util.response :refer [response redirect content-type] :as resp]
            [buddy.auth :as auth :refer [authenticated?]]
            [com.stuartsierra.component :as component]
            [ring.util.response :as response]

            [graphviz.core :as graphviz]
            [cheshire.core :as json]
            [ccdashboard.ticket-import.core :as worklog]
            [datomic.api :as d :refer [db]])
  (:import (org.slf4j LoggerFactory)
           (java.util UUID)))

(def logger ^ch.qos.logback.classic.Logger (LoggerFactory/getLogger "ccdashboard.server.core"))

(comment
  (defn layout [body]
    (page/html5                                             ;; {:manifest "offline.appcache"}
      [:head
       [:title "CCPeople2"]
       [:link {:rel "stylesheet" :href "//netdna.bootstrapcdn.com/font-awesome/3.2.0/css/font-awesome.css"}]
       [:link {:rel "stylesheet" :href "//netdna.bootstrapcdn.com/twitter-bootstrap/2.3.1/css/bootstrap-combined.min.css"}]]
      body))
  )

(defrecord Endpoint [route handler-builder tag handler]
  component/Lifecycle
  (start [component]
    (if (:handler component)
      component
      (assoc component :handler (handler-builder component))))
  (stop [component]
    (if (:handler-builder component)
      (dissoc component :handler)
      component)))

(defn login-endpoint []
  (map->Endpoint {:route   "/login"
                  :handler (fn [req]
                             (oauth/login-handler))
                  :tag     :jira/oauth}))

(defn unauthorized-user-response [email-address]
  (-> {:status 400
       :body   (str "Unknown user: " email-address)}
      (response/content-type "text/html")))

(def ccdashboard-cookie-id "ccdid")

(defn logout-response []
  (-> (response/redirect (env :app-hostname))
      (response/set-cookie ccdashboard-cookie-id "" {:max-age 0})))

(defn logout-endpoint []
  (map->Endpoint {:route   "/logout"
                  :handler (fn [req]
                             (logout-response))
                  :tag     :logout}))

(defn auth-handler [conn req]
  (let [{:keys [result] :as cookie-result} (oauth/create-signed-cookie-if-auth-successful conn (:params req))]
    (cond (= :success result)
          (-> (response/redirect (env :app-hostname))
              (response/set-cookie ccdashboard-cookie-id (:token cookie-result) {:http-only true
                                                                                 :max-age (* 1 365 24 60 60)
                                                                                 :secure true}))
          (= :error result)
          (unauthorized-user-response (:email cookie-result))
          :else
          (throw (ex-info (str "unknown result type: " result) cookie-result)))))

(defn auth-endpoint []
  (component/using
    (map->Endpoint {:route           "/auth"
                    :handler-builder (fn [component]
                                       (partial auth-handler (:conn component)))
                    :tag             :auth})
    [:conn]))

(defn get-username [req]
  (get-in req [:headers "keycloak_username"]))

(defn api-handler [conn req]
  (def apireq req)
  (if-let [username (get-username req)]
    (if-let [consultant-username (get-in req [:params :consultant])]
      (response (storage/existing-user-data-by-username conn consultant-username))
      ;; the cookie is valid, but the user doesn't exist (anymore), can happen after re-bootstrapping the database
      ;; make the user login again, resulting in a cookie containing the current user-uuid.
      (if-let [user-data (storage/existing-user-data-for-own-user conn username)]
        (response user-data)
        (logout-response)))
    (-> (response {:error :error/unknown-user})
        (resp/status 401))))

(defn api-endpoint []
  (component/using
    (map->Endpoint {:route           "/api"
                    :handler-builder (fn [component]
                                       (partial api-handler (:conn component)))
                    :tag             :api})
    [:conn]))

(defn index-endpoint []
  (map->Endpoint {:route   "/"
                  ;; no component dependencies, so create handler directly
                  :handler (fn [req]
                             (-> (io/resource "public/index.html")
                                 (io/input-stream)
                                 (response)
                                 (content-type "text/html")))
                  :tag     :index}))

(defn team-stats-api-handler [conn req]
  (if (get-username req)
    (response (storage/teams-stats-seq (d/db conn)))
    (-> (response {:error :error/unknown-user})
        (resp/status 401))))

(defn team-stats-api-endpoint []
   (component/using
    (map->Endpoint {:route           "/team-stats"
                    :handler-builder (fn [component]
                                       (partial team-stats-api-handler (:conn component)))
                    :tag             :team-stats})
    [:conn]))

