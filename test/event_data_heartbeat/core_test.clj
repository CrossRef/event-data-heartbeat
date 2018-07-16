(ns event-data-heartbeat.core-test
  (:require [clojure.test :refer :all]
            [event-data-heartbeat.core :as core]
            [clj-time.core :as clj-time]
            [clj-time.coerce :as clj-time-coerce]
            [clojure.data.json :as json]
            [ring.mock.request :as mock]))

(deftest match-benchmark-rule
  (testing "If every key-value present in the rule matches the message, return success."
    (is (core/match-benchmark-rule
          {:matcher {:a "1"} :name "My Rule"}
          {:a "1" :b "2"})))

  (testing "If not all keys and values match, return nil."
    (is (not (core/match-benchmark-rule
              {:matcher {:a "1" :c "3"} :name "My Rule"}
              {:a "1" :b "2"}))))

  (testing "If keys exist but values don't, return nil."
    (is (not (core/match-benchmark-rule
              {:matcher {:a "1" :c "3"} :name "My Rule"}
              {:a "1" :c "2"}))))

  (testing "If rule is empty, always return success.
            This is an unexpected corner-case but should be documented."
    (is (core/match-benchmark-rule
         {:matcher {}}
         {:a "1" :c "2"}))))

(deftest match-for-message
  (testing "When there are no rules, should return empty."
    (let [compiled-benchmark-rules (core/compile-benchmark-rules [])]
      (is (empty? (core/match-for-message compiled-benchmark-rules {:a "1"})))))

  (testing "When one rule matches, should return that rule."
    (let [compiled-benchmark-rules (core/compile-benchmark-rules
                           [{:matcher {:a "1"} :name "My Rule"}])]
      (is (= (core/match-for-message
               compiled-benchmark-rules
               {:a "1"}))
             [{:matcher {:a "1"} :name "My Rule"}])))

  (testing "When one rule matches out of many, should return that rule."
    (let [compiled-benchmark-rules (core/compile-benchmark-rules
                           [{:matcher {:a "1"} :name "Rule 1"}
                            {:matcher {:a "2"} :name "Rule 2"}
                            {:matcher {:a "3"} :name "Rule 3"}])]
   
      (is (= (core/match-for-message
              compiled-benchmark-rules
              {:a "3"})
              {:matcher {:a "3"} :name "Rule 3"})))))

(deftest update-benchmark-state
  (testing "Update-state with a non-matching rule returns the same input"
    (let [input-benchmark-state {"example" 9876}]
      ; Mock out matching to return failure.
      (with-redefs [core/match-for-message (constantly nil)]
        ; Nil rules as we're mocking out the matching.
        (is (= (core/update-benchmark-state nil input-benchmark-state {:some :message})
               input-benchmark-state))
            "State returned unchanged.")))

  (testing "Update-state with a matching rule and blank state adds the timestamp against the rule name."
    (let [input-benchmark-state {}]
      ; Mock out matching to return success.
      (with-redefs [core/match-for-message (constantly {:name "my-rule"})]
        ; Nil rules as we're mocking out the matching.
        (is (= (core/update-benchmark-state nil input-benchmark-state {:some :message :t 1234})
               {"my-rule" 1234})
            "State has timestamp of message added."))))

  (testing "Update-state with a matching rule adds the timestamp against the rule name."
    (let [input-benchmark-state {"example" 9876 "my-rule" 8000}]
      ; Mock out matching to return success.
      (with-redefs [core/match-for-message (constantly {:name "my-rule"})]
        ; Nil rules as we're mocking out the matching.
        (is (= (core/update-benchmark-state nil input-benchmark-state {:some :message :t 1234})
               {"my-rule" 1234
                "example" 9876})
            "State has timestamp of message replaced.")))))


(deftest benchmark-rule-status
  (let [rules [{:name "rule-one" :target 10000}
               {:name "rule-" :target 20000}
               {:name "rule-" :target 30000}]]
    (testing "When there's no state, :no-data is returned."
      (is (= (core/benchmark-rule-status nil
                               1000000000000
                               {:name "rule-one" :target 10000})
             [nil :no-data])))

    (testing "When state has no timestamp for this rule yet, :no-data is returned."
      (is (= (core/benchmark-rule-status ; Data for other rules but not this one.
                               {"rule-two" 99999}
                               1000000000000
                               {:name "rule-one" :target 10000})
             [nil :no-data])))

    (testing "When state has recent timestamp for this rule, :ok is returned."
      (is (= (core/benchmark-rule-status ; Rule one was last triggered one millsecond ago.
                               {"rule-one" 999999999999}
                               1000000000000
                               {:name "rule-one" :target 1000})
             [1 :ok])))

    (testing "When state has old timestamp for this rule, :fail is returned."
      (is (= (core/benchmark-rule-status ; Rule one was last triggered 5 seconds ago. 
                               {"rule-one" 999999995000}
                               1000000000000
                               {:name "rule-one" :target 1000})
             [5000 :fail])))))


(deftest ring-app-heartbeat
  "Top-level heartbeat should be served up via server."
  (clj-time/do-at (clj-time/date-time 2018 1 15)
    (let [benchmark-state-atom (atom {})
          artifact-url "http://example.com/abcd"
          benchmark-rules [{:name "rule-1" :description "First Rule" :comment "Un" :target 10000}
                           {:name "rule-2" :description "Second Rule" :comment "Deux" :target 20000}
                           {:name "rule-3" :description "Third Rule" :comment "Trois" :target 30000}]
          app (core/build-ring-app benchmark-state-atom artifact-url benchmark-rules (atom 0))

          second-ago-timestamp (clj-time-coerce/to-long (clj-time/minus (clj-time/now) (clj-time/seconds 1)))
          hour-ago-timestamp (clj-time-coerce/to-long (clj-time/minus (clj-time/now) (clj-time/hours 1))) ]


    (testing "When all OK, HTTP and status field should be OK"
      ; All rules fulfilled.
      (reset! benchmark-state-atom {"rule-1" second-ago-timestamp
                                    "rule-2" second-ago-timestamp
                                    "rule-3" second-ago-timestamp})
      (let [response (app (mock/request :get "/heartbeat"))
            parsed-body (json/read-str (:body response) :key-fn keyword)]
        ; The full response is tested elsewhere in build-response
        (is (= (:status response) 200))
        (is (= (:status parsed-body) "ok"))))

    (testing "When one not OK, HTTP and status field should show error."
      ; Oops, rule-1 was last triggered an hour ago.
      (reset! benchmark-state-atom {"rule-1" hour-ago-timestamp
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
      (reset! benchmark-state-atom {"rule-1" hour-ago-timestamp
                          "rule-2" second-ago-timestamp
                          "rule-3" second-ago-timestamp})

    (let [response (app (-> (mock/request :get "/heartbeat") (mock/query-string "benchmarks=rule-1")))
          parsed-body (json/read-str (:body response) :key-fn keyword)]
      ; The full response is tested elsewhere in build-response
      (is (= (:status response) 503))
      (is (= (:status parsed-body) "error"))
      (is (= (-> parsed-body :benchmarks count) 1) "Only one benchmark returned from filter"))

    (let [response (app (-> (mock/request :get "/heartbeat") (mock/query-string "benchmarks=rule-2")))
          parsed-body (json/read-str (:body response) :key-fn keyword)]
      ; The full response is tested elsewhere in build-response
      (is (= (:status response) 200))
      (is (= (:status parsed-body) "ok"))
      (is (= (-> parsed-body :benchmarks count) 1) "Only one benchmark returned from filter"))

    (let [response (app (-> (mock/request :get "/heartbeat") (mock/query-string "benchmarks=rule-3")))
          parsed-body (json/read-str (:body response) :key-fn keyword)]
      ; The full response is tested elsewhere in build-response
      (is (= (:status response) 200))
      (is (= (:status parsed-body) "ok"))
      (is (= (-> parsed-body :benchmarks count) 1) "Only one benchmark returned from filter"))

    (let [response (app (-> (mock/request :get "/heartbeat") (mock/query-string "benchmarks=rule-3,rule-1")))
          parsed-body (json/read-str (:body response) :key-fn keyword)]
      ; The full response is tested elsewhere in build-response
      (is (= (:status response) 503) "One bad benchmark means the whole thing is reported bad.")
      (is (= (:status parsed-body) "error"))
      (is (= (-> parsed-body :benchmarks count) 2) "Selected two benchmarks returned from filter"))))))
