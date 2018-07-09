(ns event-data-heartbeat.core
  (:require [event-data-common.artifact :as artifact]
            [clj-time.core :as clj-time]
            [clj-time.coerce :as clj-time-coerce]
            [clojure.pprint]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as server]
            [config.core :refer [env]]
            [compojure.core :refer [defroutes GET POST]]
            [ring.util.response :as ring-response]
            [liberator.core :refer [defresource]])
  (:import [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecords])
  (:gen-class))

(def version
  (System/getProperty "event-data-heartbeat.version"))

(defn match-rule
  "If given message matches the rule, return that rule."
  [rule message]  
  (let [matcher (:matcher rule)]
    (when
      (= matcher (select-keys message (keys matcher)))
      rule)))

(defn compile-rules
  "Take a sequence of rules, return function that returns a seq of rules matches."
  ; As this is a pre-step for match-for-message, it's unit tested via the via match-for-message.
  [rules]
  (when (seq rules)
    (apply juxt (map #(partial match-rule %) rules))))

(defn match-for-message
  "Given a collection of rules, return the first rules that matches, or nil."
  [compiled-rules message]
  (when compiled-rules
    (first (filter identity (compiled-rules message)))))

(defn update-state
  "Return new state with latest timestamp of message matching rule.
   State is a map of rule-name to most recent timestamp."
  [compiled-rules state message]
  (if-let [matched-rule (match-for-message compiled-rules message)]
    
    ; Associate rule name -> most recent timestamp.
    (assoc state (:name matched-rule) (:t message))

    ; Or leave unchanged if nothing matches.
    state))

(defn rule-status
  "What's the status of the given rule. Return tuple of lag and status.
   Status is one of:
   :ok if the timestamp falls within the rule's target
   :fail if the timestamp falls outside the rule's target
   :no-data if there have been no messages"
  [state current-timestamp rule]
  (let [state-timestamp (->> rule :name (get state))
        lag (when state-timestamp
              (- current-timestamp state-timestamp))
        status (if-not lag
                  :no-data
                  (if (< lag (:target rule))
                    :ok :fail))]

    [lag status]))


(defn build-response
  "Build a structure that represents the current status for all rules."
  [rules state current-timestamp]
  (let [; Into tuples of [rule, [lag status]].
        applied-rules (map (juxt identity (partial rule-status state current-timestamp)) rules)
        all-okay (every? #{:ok} (map (comp second second) applied-rules))
        benchmark-results (map
                            (fn [[rule [value status]]]
                              (-> rule
                                  (select-keys [:name :description :comment :target])
                                  (assoc :value value
                                         ; Don't use key of "status" in case the checker is naively
                                         ; checking for this string anywhere in the document.
                                         :success status)))
                            applied-rules)]

    {:status (if all-okay :ok :error)
     :benchmarks benchmark-results}))

(defn build-ring-app
  "Return ring HTTP server to display the state."
  [state-atom artifact-url rules]
    (defresource heartbeat
      :available-media-types ["application/json"]

      :service-available?
      (fn
        ; Build a full HTTP response page at this state, then detect if it's OK.
        [ctx]
        (let [response (build-response rules @state-atom (clj-time-coerce/to-long (clj-time/now)))
              response (assoc response :version version
                                       :benchmark-artifact artifact-url)]

          [(-> response :status #{:ok})
           {::response-body response
            ; service-not-available bypasses the content-negotiation stage,
            ; so we need to do it explicitly.
            :representation {:media-type "application/json"}}]))

      :handle-ok ::response-body 
      :handle-service-not-available ::response-body)

    (defroutes routes
      (GET "/heartbeat" [] heartbeat))

    routes)

(defn run []
  (let [artifact-name (:heartbeat-artifact env)
        artifact-url (artifact/fetch-latest-version-link artifact-name)
        rules (-> artifact-name artifact/fetch-latest-artifact-string (json/read-str :key-fn keyword) :benchmarks)
        compiled-rules (compile-rules rules)
        topic-name (:global-status-topic env)
        state (atom {})]

     (async/thread
       (log/info "Start Log listener in thread")
       (try 
       
         (let [consumer (KafkaConsumer.
                         {"bootstrap.servers" (:global-kafka-bootstrap-servers env)     
                  ; The artifact corresponds provides the config for this instances.
                  ; So use the artifact name as the group name to ensure one group per instance.
                  "group.id" (str "heartbeat-" (:heartbeat-artifact env))
                  "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"
                  "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"
                  ; Always start from the beginning of time. 
                  ; This means ingesting a few weeks of data on startup, but it's quick.
                  "auto.offset.reset" "earliest"})]

          (log/info "Subscribing to" topic-name)
          (.subscribe consumer (list topic-name))
          (log/info "Subscribed to" topic-name)
          (loop []
            (let [^ConsumerRecords records (.poll consumer (int 10000))]
              (doseq [^ConsumerRecords record records]
                (swap! state #(update-state
                                 compiled-rules
                                 %
                                 (json/read-str (.value record) :key-fn keyword)))))
             (recur)))
  
         (catch Exception e (log/error "Error in Topic listener " (.getMessage e))))
           (log/error "Stopped listening to Topic"))
    

    ; Now let the server block.
    (log/info "Start server on " (:heartbeat-port env))
    (server/run-server
      (build-ring-app state artifact-url rules)
      {:port (Integer/parseInt (:heartbeat-port env))})))

(defn -main
  [& args]
  (run))
