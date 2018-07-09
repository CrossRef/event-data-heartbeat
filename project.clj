(defproject event-data-heartbeat "0.1.0"
  :description "Crossref Event Data Hearbeat"
  :url "http://eventdata.crossref.org"
  :license {:name "The MIT License (MIT)"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.apache.kafka/kafka-clients "0.10.2.0"]
                 [yogthos/config "0.8"]
                 [event-data-common "0.1.43"]
                 [clj-time "0.14.4"]
                 [http-kit "2.3.0-alpha5"]
                 [liberator "0.14.1"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/tools.logging "0.3.1"]
                 [ring/ring-mock "0.3.2"]]
  :main ^:skip-aot event-data-heartbeat.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
