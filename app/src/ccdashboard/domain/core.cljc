(ns ccdashboard.domain.core
  (:require [ccdashboard.domain.days :as days]
            [plumbing.core :refer [map-vals]]
            [ajax.core :as ajax]
            [cognitect.transit :as transit]
            [ccdashboard.analytics.mixpanel :as mixpanel]
            [ccdashboard.util :refer [matching]]
            [ccdashboard.graph :as graph]
    #?@(:cljs [[reagent.core :refer [atom]]
               [goog.array :as garray]
               [cljs-time.core :as time]
               [goog.dom :as dom]
               cljs-time.extend
               [plumbing.core :refer-macros [fnk]]]
        :clj  [[clj-time.core :as time]
               [graphviz.core :refer [fnk]]])
            [clojure.set :as set]))

(def standard-billable-days-goal 180)

#?(:cljs
   (defn get-viewport-size []
     (let [window (dom/getWindow)
           viewport-size (dom/getViewportSize window)]
       {:width (.-width viewport-size)
        :height (.-height viewport-size)}))
   :clj
   (defn get-viewport-size []
     {:width 500
      :height 300}))

(def initial-state {:today         (time/today)
                    :viewport/size (get-viewport-size)})

(defonce app-state (atom initial-state))

(defn error-handler-fn [{:keys [status status-text]}]
  (reset! app-state
          (cond->
            (assoc-in initial-state [:user :user/signed-in?] false)
            (not= 401 status)
            (assoc :error :error/unexpected-api-response)))
  (mixpanel/track "hit")
  (println (str "api response: " status " " status-text)))

(defn change-selected-consultant [consultant]
  (swap! app-state
         assoc-in
         [:consultant :consultant/selected]
         consultant))

(defn track-user [jira-username]
  (mixpanel/identify jira-username)
  (mixpanel/track "signin"))

(defn handle-initial-api-response [data]
  (let [selected-username (get-in data [:user :user/jira-username])]
    (swap! app-state
           merge
           (-> data
               (assoc-in [:user :user/signed-in?] true)
               (assoc-in [:consultant :consultant/selected] selected-username)))
    (track-user selected-username)))

(defn handle-consultant-api-response [data]
  (swap! app-state
         merge
         (-> data
             (assoc-in [:user :user/signed-in?] true)
             (assoc-in [:consultant :consultant/selected]
                       (get-in data [:user :user/jira-username])))))

(defn handle-team-stats-api-response [teams]
  (swap! app-state merge {:team/stats teams}))

(def GET-template {:error-handler   error-handler-fn
                   :response-format :transit
                   :keywords?       true})

(defn call-api [& [params]]
  (ajax/GET "/api"
      (cond-> (merge GET-template
                     {:handler         (if (:consultant params)
                                         handle-consultant-api-response
                                         handle-initial-api-response)
                      :reader          (transit/reader :json
                                                       {:handlers
                                                        {"date/local" (fn [date-fields]
                                                                        (apply time/local-date date-fields))}})})
              params
              (assoc :params params))))


(add-watch app-state
           :load-data-for-consultant
           (fn [_ _ old-state new-state]
             (when (get-in old-state [:user :user/signed-in?])
               (let [prev-consultant (get-in old-state [:consultant :consultant/selected])
                     new-consultant (get-in new-state [:consultant :consultant/selected])]
                 (when (and (not= prev-consultant new-consultant)
                            (some? new-consultant))
                   (call-api {:consultant new-consultant})
                   (mixpanel/track "consultant/search"))))))


(defn call-team-stats-api []
  (ajax/GET "/team-stats"
      (assoc GET-template :handler handle-team-stats-api-response)))

(defn current-period-start [today]
  ;; period is closed on 5th of next month, unless in January
  (if (and (<= (time/day today) 5) (> (time/month today) 1))
    (-> today
        (time/minus (-> 1 (time/months)))
        (time/first-day-of-the-month))
    (time/first-day-of-the-month today)))

(defn customer-id-by-name [customer-name customers]
  (->> customers
       (filter (matching :customer/name customer-name))
       (first)
       (:customer/id)))

(defn sum-of [k]
  (fn [vs]
    (reduce + (map k vs))))

;; vacation ticket TS-2
(def vacation-ticket-id 68000)

;; illness ticket TS-5
(def sick-leave-ticket-id 68003)

;; parental leave TS-345
(def parental-leave-ticket-id 71746)

;; 20% time TS-7
(def plus-one-ticket-id 68005)

;; TS-6
(def travel-ticket-id 68004)

;; TS-3
(def pre-sales-ticket-id 68001)

;; TS-4
(def recruiting-ticket-id 68002)

;; TS-1
(def admin-time-ticket-id 67998)

(def special-codecentric-ticket-ids->worktype
  {vacation-ticket-id       :vacation
   sick-leave-ticket-id     :sickness
   parental-leave-ticket-id :parental-leave
   plus-one-ticket-id       :plus-one
   travel-ticket-id :travel
   pre-sales-ticket-id :pre-sales
   recruiting-ticket-id :recruiting
   admin-time-ticket-id :admin-time})

(def worktype-order [:billable :plus-one :pre-sales :recruiting :admin-time :other :vacation :travel :sickness :parental-leave])

(def worktype->display-name {:vacation "Holidays"
                             :parental-leave "Parental leave"
                             :sickness "Sickness"
                             :other "Other"
                             :billable "Billable hours"
                             :plus-one "20% time"
                             :travel "Travel"
                             :pre-sales "Pre Sales"
                             :recruiting "Recruiting"
                             :admin-time "Admin time"})

(defn worktype-fn [ticket->worktype]
  (fn [worklog]
    (get ticket->worktype (:worklog/ticket worklog) :other)))

(defn hours-by-month [worklogs ticket->worktype]
  (->> worklogs
       (group-by (comp time/month :worklog/work-date))
       (map-vals (fn [monthly-worklogs]
                   (->> monthly-worklogs
                        (group-by (worktype-fn ticket->worktype))
                        (map-vals (sum-of :worklog/hours)))))))

(defn get-worktypes [monthly-hours]
  (sort-by #(.indexOf worktype-order %) (distinct (mapcat keys (vals monthly-hours)))))

(def i->month {1 "Jan"
               2 "Feb"
               3 "Mar"
               4 "Apr"
               5 "May"
               6 "Jun"
               7 "Jul"
               8 "Aug"
               9 "Sep"
               10 "Oct"
               11 "Nov"
               12 "Dec"})

(def worktype->color {:vacation "#e36588"
                      :parental-leave "#f1db4b"
                      :sickness "#a5e2ed"
                      :other "#1FB7D4"
                      :billable "#7FFBC6"
                      :plus-one "#F4A63A"
                      :travel "#e555ed"
                      :pre-sales "#b7ab4b"
                      :recruiting "#F1B0D1"
                      :admin-time "#4b4481"})

(defn get-monthly-hours-by-worktype [monthly-hours worktype]
  (reduce (fn [hours-per-month month]
            (conj hours-per-month [(get i->month month) (get-in monthly-hours [month worktype] 0)]))
          []
          (range 1 (inc (apply max (keys monthly-hours))))))

(defn get-stacked-hours-data [monthly-hours]
  (let [worktypes (get-worktypes monthly-hours)
        worktype->data (map (juxt identity (partial get-monthly-hours-by-worktype monthly-hours)) worktypes)]
    (into []
          (map (fn [[worktype data]] {:key    (get worktype->display-name worktype)
                                      :values data
                                      :color (get worktype->color worktype)}))
          worktype->data)))

(defn working-days-left-without-today [today]
  (dec (count (days/workdays-till-end-of-year today))))



(defn ticket-days [ticket-id worklogs]
  (into #{}
        (comp (filter (matching :worklog/ticket ticket-id))
              (map :worklog/work-date))
        worklogs))

(defn sick-leave-hours [{:keys [worklogs]}]
  (->> worklogs
       (into []
             (comp (filter (matching :worklog/ticket sick-leave-ticket-id))
                   (map :worklog/hours)))
       (reduce +)))

(defn sum-vacation-hours [interval-pred app-state today]
  (->> (:worklogs app-state)
       (filter (every-pred
                 (matching :worklog/ticket vacation-ticket-id)
                 (interval-pred today)))
       (map :worklog/hours)
       (apply +)))

(def sum-past-vacation-hours
  "vacation booked in the past including today"
  (partial sum-vacation-hours (fn [today]
                                (fn [{:keys [worklog/work-date]}]
                                  (or (= work-date today)
                                      (time/before? work-date today))))))

(def sum-planned-vacation-hours
  "vacation booked in the future, excluding today"
  (partial sum-vacation-hours (fn [today]
                                (fn [{:keys [worklog/work-date]}]
                                  (time/after? work-date today)))))

(defn user-start-date [state]
  (-> state (:user) (:user/start-date)))

(defn goal-start-date
  "If a consultant starts working during the current year, then the :user/start-date is used, otherwise 1st of January."
  [state]
  (let [current-year (time/year (:today state))]
    (if-let [start-date (user-start-date state)]
      (if (= current-year (time/year start-date))
        start-date
        (time/local-date current-year 1 1))
      (time/local-date current-year 1 1))))

(defn goal-start-date-scaled-percentage
  "Depending on the goal-start-date we have to scale goals and vacation days. Returns the percentage of the year the
  consultant works for codecentric. Calculates the percentage based on months."
  ;; TODO does it happen that people start on days other than 1st and 15th?
  [start-date]
  (let [start-month (time/month start-date)
        number-of-complete-months (- 12 start-month)
        start-month-percentage (condp = (time/day start-date)
                                 1 1
                                 15 0.5
                                 (let [start-month-days (time/number-of-days-in-the-month start-date)
                                       number-of-worked-days-in-start-month (- start-month-days
                                                                               (dec (time/day start-date)))]
                                   (/ number-of-worked-days-in-start-month start-month-days)))]
    (/ (+ number-of-complete-months start-month-percentage) 12)))

;; TODO make user dependent
(def vacation-per-year 30)


#?(:cljs
   (extend-type js/goog.date.Date
     IEquiv
     (-equiv [o other]
       (and (instance? js/goog.date.Date other)
            (.equals o other)))
     IHash
     (-hash [o]
       (+ (.getTime o)
          (* 37 (.getTimezoneOffset o))))
     IComparable
     (-compare [this other]
       (if (instance? js/goog.date.Date other)
         (compare (.getTime this) (.getTime other))
         (throw (js/Error. (str "Cannot compare " this " to " other)))))))

(def min-hours-per-day 4)

(def unbooked-days-graph
  {:yesterday                 (fnk [today]
                                (-> today (time/minus (-> 1 time/days))))
   :period-days               (fnk [current-period-start-date yesterday]
                                (days/workdays-between current-period-start-date yesterday))
   :worklogs-in-period        (fnk [worklogs current-period-start-date today]
                                (filter (fn [{:keys [worklog/work-date]}]
                                          ;; the end of the period has to be today, because within excludes dates equal to end
                                          (time/within? current-period-start-date today work-date))
                                        worklogs))
   :day->worklogs             (fnk [worklogs-in-period]
                                (group-by :worklog/work-date worklogs-in-period))
   :day->worked-hours         (fnk [day->worklogs]
                                (map (fn [[day worklogs]]
                                       [day (reduce + (map :worklog/hours worklogs))])
                                     day->worklogs))
   :days-above-min-threshold  (fnk [day->worked-hours]
                                (->> day->worked-hours
                                     (filter (fn [[_ worked-hours]]
                                               (>= worked-hours min-hours-per-day)))
                                     (map (comp first))
                                     (into #{})))
   :days-with-some-hours      (fnk [day->worklogs]
                                (into #{} (keys day->worklogs)))
   :days-without-booked-hours (fnk [days-with-some-hours period-days]
                                (remove days-with-some-hours period-days))
   :days-below-threshold      (fnk [days-with-some-hours days-above-min-threshold period-days]
                                (set/intersection (set period-days) (set/difference days-with-some-hours days-above-min-threshold)))})

(def compute-unbooked-days-stats (graph/compile-cancelling unbooked-days-graph))

(defn user-sign-in-state [state]
  (get-in state [:user :user/signed-in?]))

(def app-model-graph
  {:customer-codecentric-id               (fnk [state]
                                            (customer-id-by-name "codecentric" (:customers state)))
   :non-billable-codecentric-ticket-ids   (fnk [state customer-codecentric-id]
                                            (->> state
                                                 (:tickets)
                                                 (filter (every-pred (matching :ticket/customer customer-codecentric-id)
                                                                     (complement (matching :ticket/invoicing :invoicing/support))))
                                                 (into #{} (map :ticket/id))))
   :billable-ticket-ids                   (fnk [state non-billable-codecentric-ticket-ids]
                                            (->> state
                                                 (:tickets)
                                                 (filter (fn [ticket]
                                                           (not= (:ticket/invoicing ticket)
                                                                 :invoicing/not-billable)))
                                                 (remove (comp non-billable-codecentric-ticket-ids :ticket/id))
                                                 (into #{} (map :ticket/id))))
   :worklogs-billable                     (fnk [state billable-ticket-ids]
                                            (filter (comp billable-ticket-ids :worklog/ticket)
                                                    (:worklogs state)))
   :ticket-id->worktype                   (fnk [billable-ticket-ids]
                                            (-> (zipmap billable-ticket-ids (repeat :billable))
                                                (merge special-codecentric-ticket-ids->worktype)))
   :monthly-hours                         (fnk [state ticket-id->worktype]
                                            (hours-by-month (:worklogs state) ticket-id->worktype))
   ;; the consultant specific goal start date...
   ;; 1st of January if consultant employment did not start this year, otherwise employment start.
   :consultant-start-date                 (fnk [state]
                                            (goal-start-date state))

   ;; the current open period for booking billable hours, unless before the consultant start date
   :current-period-start-date             (fnk [state consultant-start-date]
                                            (let [period-start (current-period-start (:today state))]
                                              (if (time/before? period-start consultant-start-date)
                                                consultant-start-date
                                                period-start)))

   :hours-billed                          (fnk [worklogs-billable]
                                            (reduce + (map :worklog/hours
                                                           worklogs-billable)))
   :parental-leave-days                   (fnk [state]
                                            (into #{}
                                                  (comp (filter (matching :worklog/ticket parental-leave-ticket-id))
                                                        (map :worklog/work-date))
                                                  (:worklogs state)))
   :number-parental-leave-days            (fnk [parental-leave-days]
                                            (count parental-leave-days))
   :parental-leave-days-till-today        (fnk [consultant-start-date state parental-leave-days]
                                            (into #{}
                                                  (filter (days/in-range-pred consultant-start-date (:today state)))
                                                  parental-leave-days))
   :number-parental-leave-days-till-today (fnk [parental-leave-days-till-today]
                                            (count parental-leave-days-till-today))
   :work-duration-scale-factor            (fnk [consultant-start-date]
                                            (goal-start-date-scaled-percentage consultant-start-date))
   :billable-days-goal-scaled             (fnk [number-parental-leave-days work-duration-scale-factor]
                                            (- (* standard-billable-days-goal work-duration-scale-factor)
                                               number-parental-leave-days))
   :days-to-reach-goal                    (fnk [billable-days-goal-scaled hours-billed]
                                            (- billable-days-goal-scaled
                                               (/ hours-billed 8)))
   ;; TODO implement vacation reduction when first of month is booked on parental leave ticket while last day of previous month is not
   :vacation-per-year-scaled              (fnk [work-duration-scale-factor]
                                            (* vacation-per-year work-duration-scale-factor))
   :number-taken-vacation-days            (fnk [state]
                                            (/ (sum-past-vacation-hours state (:today state)) 8.))
   :number-planned-vacation-days          (fnk [state]
                                            (/ (sum-planned-vacation-hours state (:today state)) 8.))
   :number-holidays-remaining             (fnk [number-taken-vacation-days number-planned-vacation-days vacation-per-year-scaled]
                                            (- vacation-per-year-scaled number-taken-vacation-days number-planned-vacation-days))
   :workdays-left-except-today            (fnk [state]
                                            (working-days-left-without-today (:today state)))
   :workdays-left-actually                (fnk [number-holidays-remaining workdays-left-except-today number-planned-vacation-days]
                                            (max 0 (- workdays-left-except-today number-holidays-remaining number-planned-vacation-days)))
   :number-workdays-till-today            (fnk [consultant-start-date state]
                                            (count (days/workdays-between consultant-start-date (:today state))))

   :workdays-total                        (fnk [consultant-start-date vacation-per-year-scaled number-parental-leave-days]
                                            (- (count (days/workdays-till-end-of-year consultant-start-date))
                                               vacation-per-year-scaled
                                               number-parental-leave-days))
   :burndown-hours-per-workday            (fnk [billable-days-goal-scaled workdays-total]
                                            (/ (* billable-days-goal-scaled 8)
                                               workdays-total))
   :hour-goal-today                       (fnk [number-workdays-till-today
                                                number-taken-vacation-days
                                                number-parental-leave-days-till-today
                                                burndown-hours-per-workday]
                                            (* (- number-workdays-till-today
                                                  number-taken-vacation-days
                                                  number-parental-leave-days-till-today)
                                               burndown-hours-per-workday))
   :unbooked-days-stats                   (fnk [state current-period-start-date]
                                            (compute-unbooked-days-stats
                                              (-> state
                                                  (select-keys [:today :worklogs])
                                                  (assoc :current-period-start-date current-period-start-date))))
   :days-without-booked-hours             (fnk [unbooked-days-stats]
                                            (:days-without-booked-hours unbooked-days-stats))
   :days-below-threshold                  (fnk [unbooked-days-stats]
                                            (sort (:days-below-threshold unbooked-days-stats)))
   :my-stats?                             (fnk [state]
                                            (= (:user/identity state) (-> state :consultant :consultant/selected)))
   :number-sick-leave-days                (fnk [my-stats? state]
                                            (if my-stats?
                                              (/ (sick-leave-hours state) 8)
                                              "X"))
   })

(def app-model (graph/compile-cancelling app-model-graph))
