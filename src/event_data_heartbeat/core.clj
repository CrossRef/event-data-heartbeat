(ns event-data-heartbeat.core
  (:require [event-data-common.artifact :as artifact]
            [event-data-common.date :refer [->yyyy-mm-dd]]
            [clj-time.core :as clj-time]
            [clj-time.coerce :as clj-time-coerce]
            [clojure.pprint]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as server]
            [org.httpkit.client :as http]
            [config.core :refer [env]]
            [compojure.core :refer [defroutes GET POST]]
            [ring.util.response :as ring-response]
            [liberator.core :refer [defresource]]
            [taoensso.timbre :as timbre]
            [ring.middleware.params :as middleware-params]
            [clostache.parser :as parser])
  (:import [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecords]
           [javax.net.ssl SNIHostName SNIServerName SSLEngine SSLParameters]
           [java.net URI])
  (:gen-class))

(def version
  (System/getProperty "event-data-heartbeat.version"))

(defn match-benchmark-rule
  "If given message matches the rule, return that rule."
  [rule message]  
  (let [matcher (:matcher rule)]
    (when
      (= matcher (select-keys message (keys matcher)))
      rule)))

(defn compile-benchmark-rules
  "Take a sequence of rules, return function that returns a seq of rules matches."
  ; As this is a pre-step for match-for-message, it's unit tested via the via match-for-message.
  [rules]
  (when (seq rules)
    (apply juxt (map #(partial match-benchmark-rule %) rules))))

(defn match-for-message
  "Given a collection of rules, return the first rules that matches, or nil."
  [compiled-benchmark-rules message]
  (when compiled-benchmark-rules
    (first (filter identity (compiled-benchmark-rules message)))))

(defn update-benchmark-state
  "Return new state with latest timestamp of message matching rule.
   State is a map of rule-name to most recent timestamp."
  [compiled-benchmark-rules benchmark-state message]
  (if-let [matched-rule (match-for-message compiled-benchmark-rules message)]
    
    ; Associate rule name -> most recent timestamp.
    (assoc benchmark-state (:name matched-rule) (:t message))

    ; Or leave unchanged if nothing matches.
    benchmark-state))

(defn benchmark-rule-status
  "What's the status of the given benchmark rule. Return tuple of lag and status.
   Status is one of:
   :ok if the timestamp falls within the rule's target
   :fail if the timestamp falls outside the rule's target
   :no-data if there have been no messages"
  [benchmark-state current-timestamp rule]
  (let [state-timestamp (->> rule :name (get benchmark-state))
        lag (when state-timestamp
              (- current-timestamp state-timestamp))
        status (if-not lag
                  :no-data
                  (if (< lag (:target rule))
                    :ok :fail))]

    [lag status]))


(defn build-format-variables
  "Build a hashmap of variables and values, at current time.
   Currently this is only :yesterday ."
  []
  (let [now (clj-time/now)
        ; Yesterday adds six hours of grace time, as we're going to be using it to monitor
        ; daily snapshots. Explained in README.md
        yesterday (clj-time/minus now (clj-time/hours 30))]
    
    {:yesterday (->yyyy-mm-dd yesterday)}))

(defn template-url
  [input]
  (let [variables (build-format-variables)]
    (parser/render input variables)))

(defn sni-configure
  [^SSLEngine ssl-engine ^URI uri]
  (let [^SSLParameters ssl-params (.getSSLParameters ssl-engine)]
    (.setServerNames ssl-params [(SNIHostName. (.getHost uri))])
    (.setSSLParameters ssl-engine ssl-params)))

(def sni-client (org.httpkit.client/make-client
                  {:ssl-configurer sni-configure}))

(defn run-url-check
  "Run the rule and return a result containing:
   - url-checked  - the templated URL checked
   - response-code - the HTTP response code
   - success - one of :ok or :error
   - name
   - description
   - url
   - comment"
  [rule]
  (let [; Safe default to HEAD
        method (-> rule :method {"GET" :get "HEAD" :head} (or :head))
        url (-> rule :url template-url)
        result (try
                  @(http/request {:method method
                                  :url url
                                  :client sni-client})
                  (catch Exception ex
                    (do (log/error "Error:" (.getMessage ex))
                        {:status :error})))
        response-code (:status result)
        success (#{200} response-code)]
    (log/info "Checked URL" url "method" method "got status code" (:status result))
    (merge
      (select-keys rule [:name :description :url :comment])
      {:url-checked url
       :response-code response-code
       :success (if (#{200} response-code) :ok :error)})))

(defn build-response
  "Build a structure that represents the current status for all rules."
  [url-rules benchmark-rules benchmark-state current-timestamp]
  (let [; Into tuples of [rule, [lag status]].
        applied-benchmark-rules (map (juxt identity (partial benchmark-rule-status benchmark-state current-timestamp)) benchmark-rules)
        
        benchmark-results (map
                            (fn [[rule [value status]]]
                              (-> rule
                                  (select-keys [:name :description :comment :target])
                                  (assoc :value value
                                         ; Don't use key of "status" in case the checker is naively
                                         ; checking for this string anywhere in the document.
                                         :success status)))
                            applied-benchmark-rules)

        url-results (map run-url-check url-rules)
        
        all-okay (every? #{:ok} (map :success (concat benchmark-results url-results)))]
    {:status (if all-okay :ok :error)
     :benchmarks benchmark-results
     :urls url-results}))

(defn build-ring-app
  "Return ring HTTP server to display the state."
  [benchmark-state-atom artifact-url url-rules benchmark-rules num-messages-processed]
    (defresource heartbeat
      :available-media-types ["application/json"]

      :service-available?
      (fn
        ; Build a full HTTP response page at this state, then detect if it's OK.
        [ctx]
        (let [; comma-separated into a set.
              benchmark-filter (when-let [filter-str (get-in ctx [:request :params "benchmarks"])]
                                 (set (clojure.string/split filter-str #",")))

              ; By default use all rules. However, if a benchmark filter is supplied, filter to just that rule.
              benchmark-rules (if-not benchmark-filter
                            benchmark-rules
                            (filter #(benchmark-filter (:name %)) benchmark-rules))


              ; comma-separated into a set.
              url-filter (when-let [filter-str (get-in ctx [:request :params "urls"])]
                           (set (clojure.string/split filter-str #",")))

              ; By default use all rules. However, if a benchmark filter is supplied, filter to just that rule.
              url-rules (if-not url-filter
                          url-rules
                          (filter #(url-filter (:name %)) url-rules))

              response (build-response url-rules benchmark-rules @benchmark-state-atom (clj-time-coerce/to-long (clj-time/now)))
              response (assoc response :version version
                                       :benchmark-artifact artifact-url
                                       :messages-processed @num-messages-processed)]

          [(-> response :status #{:ok})
           {::response-body response
            ; service-not-available bypasses the content-negotiation stage,
            ; so we need to do it explicitly.
            :representation {:media-type "application/json"}}]))

      :handle-ok ::response-body 
      :handle-service-not-available ::response-body)

    (defroutes routes
      (GET "/heartbeat" [] heartbeat))

    (-> routes middleware-params/wrap-params))

(defn run []
  (let [artifact-name (:heartbeat-artifact env)
        artifact-url (artifact/fetch-latest-version-link artifact-name)
        benchmark-rules (-> artifact-name artifact/fetch-latest-artifact-string (json/read-str :key-fn keyword) :benchmarks)
        url-rules (-> artifact-name artifact/fetch-latest-artifact-string (json/read-str :key-fn keyword) :urls)
        compiled-benchmark-rules (compile-benchmark-rules benchmark-rules)
        topic-name (:global-status-topic env)
        benchmark-state (atom {})
        num-messages-processed (atom 0)]

     (async/thread
       (log/info "Start Log listener in thread")
       (try 
         (let [consumer (KafkaConsumer.
                         {"bootstrap.servers" (:global-kafka-bootstrap-servers env)     
                          ; The artifact corresponds provides the config for this instances.
                          ; So use the artifact name as the group name to ensure one group per instance.
                          ; Also append a timestamp so each restart of the service is a new consumer group,
                          ; and starts from scratch. Otherwise we wouldn't see older stuff.
                          "group.id" (str "heartbeat-" (:heartbeat-artifact env) "-" (System/currentTimeMillis))
                          "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"
                          "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"
                          ; Always start from the beginning of time. 
                          ; This means ingesting a few weeks of data on startup, but it's quick.
                          "auto.offset.reset" "earliest"
                          ; 50 MiB. On start-up we need to churn through a lot of back-logs.
                          "fetch.max.bytes" "52428800"})]

          (log/info "Subscribing to" topic-name)
          (.subscribe consumer (list topic-name))
          (log/info "Subscribed to" topic-name)
          (loop []
            (let [^ConsumerRecords records (.poll consumer (int 1000))]
              (doseq [^ConsumerRecords record records]
                (swap! num-messages-processed inc)
                (swap! benchmark-state #(update-benchmark-state
                                 compiled-benchmark-rules
                                 %
                                 (json/read-str (.value record) :key-fn keyword)))))
             (recur)))
  
         (catch Exception e (log/error "Error in Topic listener " (.getMessage e))))
           (log/error "Stopped listening to Topic"))
    

    ; Now let the server block.
    (log/info "Start server on " (:heartbeat-port env))
    (server/run-server
      (build-ring-app benchmark-state artifact-url url-rules benchmark-rules num-messages-processed)
      {:port (Integer/parseInt (:heartbeat-port env))})))

(defn -main
  [& args]
  (timbre/merge-config!
    {:ns-blacklist [
       ; Kafka's DEBUG is overly chatty.
       "org.apache.kafka.clients.consumer.internals.*"
       "org.apache.kafka.clients.NetworkClient"]
     :level :info})

  (run))
