(ns ccdashboard.client.global-stats
  (:require [reagent.core :as reagent]
            [ccdashboard.client.dataviz :as dataviz]
            [clojure.set :as set]
            [ccdashboard.domain.core :as domain]
            [ccdashboard.analytics.mixpanel :as mixpanel]))

(defn global-stats [state-atom component-name]
  [:div {:style {:margin-left  "auto"
                 :margin-right "auto"}
         :id    component-name}
   [:svg]])

(defn global-stats-did-mount [state-atom component-name]
  (let [state @state-atom
        stats (:team/stats state)
        global-hour-stats (map (fn [team]
                                (update team :value (partial * (/ 8))))
                              (set/rename stats {:team/billable-hours :value}))
        global-member-count (set/rename stats {:team/member-count :value})]
    (dataviz/global-stats-multibarchart component-name
                                      (get-in state [:viewport/size :width])
                                      [{:key    "Billable Work Days"
                                        :color  "#A5E2ED"
                                        :values (sort-by :name global-hour-stats)}
                                       {:key    "Members"
                                        :color  "#F1DB4B"
                                        :values (sort-by :name global-member-count)}])))

(defn global-component [state-atom component-name]
  (if (nil? (:team/stats @state-atom))
    [:p "Loading Stats..."]
    (reagent/create-class {:reagent-render      (partial global-stats state-atom component-name)
                           :component-did-mount (partial global-stats-did-mount state-atom component-name)})))

(defn global-page [_]
  (mixpanel/track "global")
  [global-component domain/app-state "globalstats"])
