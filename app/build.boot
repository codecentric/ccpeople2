(set-env! :project 'app
          :version "0.1.0"
          :dependencies '[[org.clojure/tools.nrepl   "0.2.12" :scope "test"]
                          [adzerk/boot-cljs          "1.7.228-1" :scope "test"]
                          [adzerk/boot-cljs-repl     "0.3.0" :scope "test"]
                          [adzerk/boot-reload        "0.4.8" :scope "test"]
                          [com.cemerick/piggieback   "0.2.1" :scope "test"]
                          [weasel                    "0.7.0" :scope "test"]

                          [org.clojure/clojure "1.8.0"]
                          [org.clojure/clojurescript "1.7.228"]
                          [org.clojure/test.check "0.9.0"]
                          ;[datascript "0.13.3"]
                          [aleph "0.4.1-beta2"]
                          [com.cognitect/transit-cljs "0.8.237"]
                          [com.cognitect/transit-clj "0.8.285"]
                          [environ "1.0.1"]
                          [org.clojure/tools.namespace "0.2.11"]
                          [org.apache.httpcomponents/httpclient "4.5.1"]
                          [duct "0.4.2"]
                          [enlive "1.1.6"]
                          [meta-merge "0.1.1"]
                          [ring-middleware-format "0.7.0"]
                          [cljs-ajax "0.5.3"]
                          [prismatic/schema "1.0.5"]
                          [prismatic/plumbing "0.5.2"]
                          [com.stuartsierra/component "0.3.1"]
                          [io.rkn/conformity "0.3.5"]
                          [org.postgresql/postgresql "9.4.1207"]
                          [com.datomic/datomic-pro "0.9.5344"
                           :exclusions [org.slf4j/slf4j-nop
                                        joda-time org.slf4j/slf4j-log4j12
                                       org.slf4j/slf4j-api]]
                          [ch.qos.logback/logback-classic "1.1.3"]
                          [org.clojure/core.async "0.2.374"]
                          [bidi "1.25.0"]
                          [ring/ring-core "1.4.0"]
                          [ring/ring-devel "1.4.0"]
                          [ring/ring-defaults "0.1.5"]
                          [clj-time "0.11.0"]
                          [hiccup "1.0.5"]
                          [reagent "0.6.0-alpha"]
                          [cljsjs/react "0.14.3-0"]
                          [com.andrewmcveigh/cljs-time "0.4.0"]
                          [net.oauth.core/oauth "20090617"]
                          [net.oauth.core/oauth-httpclient4 "20090617"]
                          [buddy/buddy-auth "0.9.0"]
                          [cljsjs/d3 "3.5.7-1"]
                          [cljsjs/nvd3 "1.8.2-1"]
                          [cljsjs/react-select "1.0.0-beta13-0"]]

          :repositories #(conj % ["my.datomic.com" {:url "https://my.datomic.com/repo"
                                                    :username (System/getenv "DATOMIC_USER")
                                                    :password (System/getenv "DATOMIC_PASSWORD")}]
                                 ["artifactory.codecentric.de" {:url "https://artifactory.codecentric.de/artifactory/repo"
                                                                :username (System/getenv "CCARTUSER")
                                                                :password (System/getenv "CCARTPASS")}])
          :source-paths #{"src"}
          :resource-paths #{"resources/public"}
          )

(require '[adzerk.boot-cljs      :refer [cljs]]
         '[adzerk.boot-cljs-repl :refer [cljs-repl]]
         '[adzerk.boot-reload    :refer [reload]])

(task-options! cljs {:source-map true}
               pom {:project (get-env :project)
                    :version (get-env :version)})

(deftask client []
  "Compiles the Client for dev"
  (comp
   (watch)
   (reload :on-jsload 'ccdashboard.client.core/on-js-reload)
   (cljs-repl)
   (cljs)
   (sift :move {#"^main.js$" "js/main.js"})
   (target :dir #{"public"})))

(deftask build []
  (comp (aot :all true)
        (pom)
        (uber)
        (jar :main 'ccdashboard.main
             :file "ccdashboard.jar")
        (sift :include #{#"^ccdashboard.jar$"})
        (target :dir #{"target"})))

(deftask ship []
  (comp (cljs :optimizations :advanced)
        (build)))
