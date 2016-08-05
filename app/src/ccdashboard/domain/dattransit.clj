(ns ccdashboard.domain.dattransit
  (:require
    [datascript.core :as d]
    [datascript.db   :as db]
    [cognitect.transit :as t])
  (:import
    [datascript.db DB Datom]
    [datascript.btset BTSet]
    [java.io ByteArrayInputStream ByteArrayOutputStream]
    (org.joda.time LocalDate)))

(def write-handlers
  {DB    (t/write-handler "datascript/DB"
                          (fn [db]
                            {:schema (:schema db)
                             :datoms (:eavt db)}))
   Datom (t/write-handler "datascript/Datom"
                          (fn [^Datom d]
                            [(.-e d) (.-a d) (.-v d) (.-tx d)]))
   BTSet (get t/default-write-handlers java.util.List)
   LocalDate (t/write-handler "d/l"
                              (fn [^LocalDate local-date]
                                [(.getYear local-date)
                                 (.getMonthOfYear local-date)
                                 (.getDayOfMonth local-date)]))})


(defn write-transit [o os]
  (t/write (t/writer os :json { :handlers write-handlers }) o))

(defn write-transit-bytes ^bytes [o]
  (let [os (ByteArrayOutputStream.)]
    (write-transit o os)
    (.toByteArray os)))


(defn write-transit-str [o]
  (String. (write-transit-bytes o) "UTF-8"))
