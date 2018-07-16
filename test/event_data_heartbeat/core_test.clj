(ns event-data-heartbeat.core-test
  (:require [clojure.test :refer :all]
            [event-data-heartbeat.core :as core]
            [clj-time.core :as clj-time]
            [clj-time.coerce :as clj-time-coerce]
            [clojure.data.json :as json]
            [ring.mock.request :as mock]
            [org.httpkit.fake :refer [with-fake-http]]))

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

(deftest build-format-variables
  (testing ":yesterday means yesterday, but only at 6am"
    (clj-time/do-at (clj-time/date-time 2018 1 15 6 1)
      (= (:yesterday (core/build-format-variables)) "2018-01-14")))

  (testing ":yesterday means day before yesterday, before 6am"
    (clj-time/do-at (clj-time/date-time 2018 1 15 5 59)
      (= (:yesterday (core/build-format-variables)) "2018-01-13"))))

(deftest format-url
  (testing "All variables will be templated in all places."
    (clj-time/do-at (clj-time/date-time 2018 1 15 6 1)
      (is (= (core/template-url "http://www.example.com/{{yesterday}}/one/{{yesterday}}/two/{{yesterday}}")
             "http://www.example.com/2018-01-14/one/2018-01-14/two/2018-01-14")))))

(deftest run-url-check
  (clj-time/do-at (clj-time/date-time 2018 1 15 6 1)
    (let [rule {:name "evidence-log-json",
                :description "Yesterday's Evidence Log should be present.",
                :url "https://test-host/log/{{yesterday}}.txt",
                :method "HEAD",
                :comment "Daily Evidence Log should be present."}]

      (testing "Methods GET and HEAD should be respected, defaulting to HEAD in absence of method."
        (let [rule (dissoc rule :method)]
          (with-fake-http [{:url "https://test-host/log/2018-01-14.txt" :method :head}
                           {:status 200}]
             (is (= (:response-code (core/run-url-check rule)) 200)
                 "No method defaults to head.")))

        (let [rule (assoc rule :method "GET")]
          (with-fake-http [{:url "https://test-host/log/2018-01-14.txt" :method :get}
                           {:status 200}]
             (is (= (:response-code (core/run-url-check rule)) 200)
                 "GET method should be used when specified.")))

        (let [rule (assoc rule :method "HEAD")]
          (with-fake-http [{:url "https://test-host/log/2018-01-14.txt" :method :head}
                           {:status 200}]
             (is (= (:response-code (core/run-url-check rule)) 200)
                 "HEAD method should be used when specified"))))

      (testing "Fields should be merged to construct response."
        (with-fake-http [{:url "https://test-host/log/2018-01-14.txt" :method :head}
                         {:status 200}]
                         
           (is (= (core/run-url-check rule)
                  {:name "evidence-log-json"
                   :description "Yesterday's Evidence Log should be present."
                   :url "https://test-host/log/{{yesterday}}.txt"
                   :comment "Daily Evidence Log should be present."
                   :url-checked "https://test-host/log/2018-01-14.txt"
                   :response-code 200
                   :success :ok})
               "Expected response should contain templated URL, status code and success.")))

      (testing "Failure is recorded."
        (with-fake-http [{:url "https://test-host/log/2018-01-14.txt" :method :head}
                         {:status 500}]
                         
           (is (= (:status (core/run-url-check rule) 500))))

        (with-fake-http [{:url "https://test-host/log/2018-01-14.txt" :method :head}
                         (fn [_ _ _] (throw (Exception. "Something awful.")))]
           (is (= (:status (core/run-url-check rule) :error))))))))


(deftest ring-app-heartbeat
  "Top-level heartbeat should be served up via server."
  (with-fake-http [{:url "https://example1.com" :method :head} {:status 200}
                   {:url "https://example2.net" :method :head} {:status 200}
                   {:url "https://example3.org" :method :head} {:status 200}]

    (clj-time/do-at (clj-time/date-time 2018 1 15)
      (let [benchmark-state-atom (atom {})
            artifact-url "http://example.com/abcd"
            benchmark-rules [{:name "rule-1" :description "First Rule" :comment "Un" :target 10000}
                             {:name "rule-2" :description "Second Rule" :comment "Deux" :target 20000}
                             {:name "rule-3" :description "Third Rule" :comment "Trois" :target 30000}]
            url-rules [{:name "url-rule-1" :description "First URL rule" :comment "Une.com" :url "https://example1.com"}
                       {:name "url-rule-2" :description "Second URL rule" :comment "Deux.com" :url "https://example2.net"}
                       {:name "url-rule-3" :description "Third URL rule" :comment "Trois.fr" :url "https://example3.org"}]
            app (core/build-ring-app benchmark-state-atom artifact-url url-rules benchmark-rules (atom 0))

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

      (testing "Filter query param allows selection of just one benchmark resource, and top-level response reflects thats."
        ; Rule-1 was triggered an hour ago, so it's bad. The other two are still good.
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
          (is (= (-> parsed-body :benchmarks count) 2) "Selected two benchmarks returned from filter")))

    (testing "Filter query param allows selection of just one URL resource, and top-level response reflects thats."
      
      ; Now url check number two throws an error.
      (with-fake-http [{:url "https://example1.com" :method :head} {:status 200}
                       {:url "https://example2.net" :method :head} {:status 503}
                       {:url "https://example3.org" :method :head} {:status 200}]

        ; Make benchmarks all ok.
        (reset! benchmark-state-atom {"rule-1" second-ago-timestamp "rule-2" second-ago-timestamp "rule-3" second-ago-timestamp})

        (let [response (app (-> (mock/request :get "/heartbeat") (mock/query-string "urls=url-rule-1")))
              parsed-body (json/read-str (:body response) :key-fn keyword)]
              
          (is (= (:status response) 200))
          (is (= (:status parsed-body) "ok"))
          (is (= (-> parsed-body :urls count) 1) "Only one url returned from filter"))

        (let [response (app (-> (mock/request :get "/heartbeat") (mock/query-string "urls=url-rule-2")))
              parsed-body (json/read-str (:body response) :key-fn keyword)]
          (is (= (:status response) 503))
          (is (= (:status parsed-body) "error"))
          (is (= (-> parsed-body :urls count) 1) "Only one url returned from filter"))

        (let [response (app (-> (mock/request :get "/heartbeat") (mock/query-string "urls=url-rule-3")))
              parsed-body (json/read-str (:body response) :key-fn keyword)]
          (is (= (:status response) 200))
          (is (= (:status parsed-body) "ok"))
          (is (= (-> parsed-body :urls count) 1) "Only one url returned from filter"))

        (let [response (app (-> (mock/request :get "/heartbeat") (mock/query-string "urls=url-rule-1,url-rule-2")))
              parsed-body (json/read-str (:body response) :key-fn keyword)]

          (is (= (:status response) 503) "One bad url means the whole thing is reported bad.")
          (is (= (:status parsed-body) "error"))
          (is (= (-> parsed-body :urls count) 2) "Selected two urls returned from filter"))))))))
