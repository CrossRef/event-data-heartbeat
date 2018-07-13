(ns event-data-heartbeat.core-test
  (:require [clojure.test :refer :all]
            [event-data-heartbeat.core :as core]
            [clj-time.core :as clj-time]
            [clj-time.coerce :as clj-time-coerce]
            [clojure.data.json :as json]
            [ring.mock.request :as mock]))

(deftest match-rule
  (testing "If every key-value present in the rule matches the message, return success."
    (is (core/match-rule
          {:matcher {:a "1"} :name "My Rule"}
          {:a "1" :b "2"})))

  (testing "If not all keys and values match, return nil."
    (is (not (core/match-rule
              {:matcher {:a "1" :c "3"} :name "My Rule"}
              {:a "1" :b "2"}))))

  (testing "If keys exist but values don't, return nil."
    (is (not (core/match-rule
              {:matcher {:a "1" :c "3"} :name "My Rule"}
              {:a "1" :c "2"}))))

  (testing "If rule is empty, always return success.
            This is an unexpected corner-case but should be documented."
    (is (core/match-rule
         {:matcher {}}
         {:a "1" :c "2"}))))

(deftest match-for-message
  (testing "When there are no rules, should return empty."
    (let [compiled-rules (core/compile-rules [])]
      (is (empty? (core/match-for-message compiled-rules {:a "1"})))))

  (testing "When one rule matches, should return that rule."
    (let [compiled-rules (core/compile-rules
                           [{:matcher {:a "1"} :name "My Rule"}])]
      (is (= (core/match-for-message
               compiled-rules
               {:a "1"}))
             [{:matcher {:a "1"} :name "My Rule"}])))

  (testing "When one rule matches out of many, should return that rule."
    (let [compiled-rules (core/compile-rules
                           [{:matcher {:a "1"} :name "Rule 1"}
                            {:matcher {:a "2"} :name "Rule 2"}
                            {:matcher {:a "3"} :name "Rule 3"}])]
   
      (is (= (core/match-for-message
              compiled-rules
              {:a "3"})
              {:matcher {:a "3"} :name "Rule 3"})))))

(deftest update-state
  (testing "Update-state with a non-matching rule returns the same input"
    (let [input-state {"example" 9876}]
      ; Mock out matching to return failure.
      (with-redefs [core/match-for-message (constantly nil)]
        ; Nil rules as we're mocking out the matching.
        (is (= (core/update-state nil input-state {:some :message})
               input-state))
            "State returned unchanged.")))

  (testing "Update-state with a matching rule and blank state adds the timestamp against the rule name."
    (let [input-state {}]
      ; Mock out matching to return success.
      (with-redefs [core/match-for-message (constantly {:name "my-rule"})]
        ; Nil rules as we're mocking out the matching.
        (is (= (core/update-state nil input-state {:some :message :t 1234})
               {"my-rule" 1234})
            "State has timestamp of message added."))))

  (testing "Update-state with a matching rule adds the timestamp against the rule name."
    (let [input-state {"example" 9876 "my-rule" 8000}]
      ; Mock out matching to return success.
      (with-redefs [core/match-for-message (constantly {:name "my-rule"})]
        ; Nil rules as we're mocking out the matching.
        (is (= (core/update-state nil input-state {:some :message :t 1234})
               {"my-rule" 1234
                "example" 9876})
            "State has timestamp of message replaced.")))))


(deftest rule-status
  (let [rules [{:name "rule-one" :target 10000}
               {:name "rule-" :target 20000}
               {:name "rule-" :target 30000}]]
    (testing "When there's no state, :no-data is returned."
      (is (= (core/rule-status nil
                               1000000000000
                               {:name "rule-one" :target 10000})
             [nil :no-data])))

    (testing "When state has no timestamp for this rule yet, :no-data is returned."
      (is (= (core/rule-status ; Data for other rules but not this one.
                               {"rule-two" 99999}
                               1000000000000
                               {:name "rule-one" :target 10000})
             [nil :no-data])))

    (testing "When state has recent timestamp for this rule, :ok is returned."
      (is (= (core/rule-status ; Rule one was last triggered one millsecond ago.
                               {"rule-one" 999999999999}
                               1000000000000
                               {:name "rule-one" :target 1000})
             [1 :ok])))

    (testing "When state has old timestamp for this rule, :fail is returned."
      (is (= (core/rule-status ; Rule one was last triggered 5 seconds ago. 
                               {"rule-one" 999999995000}
                               1000000000000
                               {:name "rule-one" :target 1000})
             [5000 :fail])))))


; (deftest build-response
;   (let [rules [{:name "rule-1" :description "First Rule" :comment "Un" :target 1000}
;                {:name "rule-2" :description "Second Rule" :comment "Deux" :target 2000}
;                {:name "rule-3" :description "Third Rule" :comment "Trois" :target 3000}]]

;     (testing "Response should contain all rules, applied with current state and timestamp."
;         (is (= (core/build-response
;                  rules
;                  ; Two rules triggered five seconds ago (above threshold)
;                  {"rule-1" 5000 "rule-2" 5000}
;                  10000)

;                {:status :error
;                 :benchmarks
;                 [{:name "rule-1"
;                   :description "First Rule"
;                   :comment "Un"
;                   :target 1000
;                   :value 5000
;                   :success :fail}

;                  {:name "rule-2"
;                   :description "Second Rule"
;                   :comment "Deux"
;                   :target 2000
;                   :value 5000
;                   :success :fail}

;                  ; We have never had any rule-3 data yet.
;                  {:name "rule-3"
;                   :description "Third Rule"
;                   :comment "Trois"
;                   :target 3000
;                   :value nil
;                   :success :no-data}]}))


;             (is (= (core/build-response
;                       rules
;                       ; All rules triggered within their respective target.
;                       {"rule-1" 9500 "rule-2" 8500 "rule-3" 7500}
;                       10000)

;                {:status :ok
;                 :benchmarks
;                 [{:name "rule-1"
;                   :description "First Rule"
;                   :comment "Un"
;                   :target 1000
;                   :value 500
;                   :success :ok}

;                  {:name "rule-2"
;                   :description "Second Rule"
;                   :comment "Deux"
;                   :target 2000
;                   :value 1500
;                   :success :ok}

;                  ; We have never had any rule-3 data yet.
;                  {:name "rule-3"
;                   :description "Third Rule"
;                   :comment "Trois"
;                   :target 3000
;                   :value 2500
;                   :success :ok}]})))))

(deftest ring-app-heartbeat
  "Top-level heartbeat should be served up via server."
  (clj-time/do-at (clj-time/date-time 2018 1 15)
    (let [state-atom (atom {})
          artifact-url "http://example.com/abcd"
          rules [{:name "rule-1" :description "First Rule" :comment "Un" :target 10000}
                 {:name "rule-2" :description "Second Rule" :comment "Deux" :target 20000}
                 {:name "rule-3" :description "Third Rule" :comment "Trois" :target 30000}]
          app (core/build-ring-app state-atom artifact-url rules (atom 0))

          second-ago-timestamp (clj-time-coerce/to-long (clj-time/minus (clj-time/now) (clj-time/seconds 1)))
          hour-ago-timestamp (clj-time-coerce/to-long (clj-time/minus (clj-time/now) (clj-time/hours 1))) ]


    (testing "When all OK, HTTP and status field should be OK"
      ; All rules fulfilled.
      (reset! state-atom {"rule-1" second-ago-timestamp
                          "rule-2" second-ago-timestamp
                          "rule-3" second-ago-timestamp})
      (let [response (app (mock/request :get "/heartbeat"))
            parsed-body (json/read-str (:body response) :key-fn keyword)]
        ; The full response is tested elsewhere in build-response
        (is (= (:status response) 200))
        (is (= (:status parsed-body) "ok"))))

    (testing "When one not OK, HTTP and status field should show error."
      ; Oops, rule-1 was last triggered an hour ago.
      (reset! state-atom {"rule-1" hour-ago-timestamp
                          "rule-2" second-ago-timestamp
                          "rule-3" second-ago-timestamp})
      (let [response (app (mock/request :get "/heartbeat"))
            parsed-body (json/read-str (:body response) :key-fn keyword)]
        ; The full response is tested elsewhere in build-response
        (is (= (:status response) 503))
        (is (= (:status parsed-body) "error"))
        (is (= (-> parsed-body :benchmarks count) 3) "All three benchmark returned, as there's no filter")))

    (testing "Filter query param allows selection of just one resource, and top-level response reflects thats."
      ; Rule-1 was triggerd an hour ago, so it's bad. The other two are still good.
      (reset! state-atom {"rule-1" hour-ago-timestamp
                          "rule-2" second-ago-timestamp
                          "rule-3" second-ago-timestamp})

    (let [response (app (-> (mock/request :get "/heartbeat") (mock/query-string "benchmark=rule-1")))
          parsed-body (json/read-str (:body response) :key-fn keyword)]
      ; The full response is tested elsewhere in build-response
      (is (= (:status response) 503))
      (is (= (:status parsed-body) "error"))
      (is (= (-> parsed-body :benchmarks count) 1) "Only one benchmark returned from filter"))

    (let [response (app (-> (mock/request :get "/heartbeat") (mock/query-string "benchmark=rule-2")))
          parsed-body (json/read-str (:body response) :key-fn keyword)]
      ; The full response is tested elsewhere in build-response
      (is (= (:status response) 200))
      (is (= (:status parsed-body) "ok"))
      (is (= (-> parsed-body :benchmarks count) 1) "Only one benchmark returned from filter"))

    (let [response (app (-> (mock/request :get "/heartbeat") (mock/query-string "benchmark=rule-3")))
          parsed-body (json/read-str (:body response) :key-fn keyword)]
      ; The full response is tested elsewhere in build-response
      (is (= (:status response) 200))
      (is (= (:status parsed-body) "ok"))
      (is (= (-> parsed-body :benchmarks count) 1) "Only one benchmark returned from filter"))))))
