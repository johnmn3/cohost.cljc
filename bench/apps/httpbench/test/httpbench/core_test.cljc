(ns httpbench.core-test
  "Tests for the Clojure-only parts: stats math, URL parsing, the report
   shapes. They run on the JVM and on jank via cohost -X:test."
  (:require [clojure.test :as t]
            [httpbench.core :as core]
            [httpbench.stats :as stats]))

(t/deftest percentile-test
  (let [v (vec (range 1 101))]
    (t/is (= 50 (stats/percentile v 0.5)))
    (t/is (= 90 (stats/percentile v 0.9)))
    (t/is (= 99 (stats/percentile v 0.99)))
    (t/is (= 100 (stats/percentile v 1.0)))
    (t/is (= 1 (stats/percentile v 0.0)))
    (t/is (nil? (stats/percentile [] 0.5)))))

(t/deftest rates-test
  (t/is (= 1000.0 (stats/requests-per-second 1000 1000000000)))
  (t/is (nil? (stats/requests-per-second 0 1000)))
  (t/is (= 500.0 (:req-per-sec (stats/summarize {:count 500 :wall-ns 1000000000})))))

(t/deftest parse-url-test
  (t/is (= {:host "127.0.0.1" :port 8080 :path "/"} (core/parse-url "http://127.0.0.1:8080/")))
  (t/is (= {:host "example.com" :port 80 :path "/a/b"} (core/parse-url "http://example.com/a/b")))
  (t/is (thrown? ExceptionInfo (core/parse-url "127.0.0.1:8080"))))

(t/deftest report-test
  (let [m (stats/summarize {:count 1000 :errors 2 :bytes 1124000 :wall-ns 2000000000
                            :p50 1000 :p90 2000 :p99 3000 :p99.9 4000 :max 5000})
        text (core/format-report m)
        json (core/format-json m)]
    (t/is (clojure.string/includes? text "requests/s: 500.0"))
    (t/is (clojure.string/includes? text "p99:        3000 ns"))
    (t/is (clojure.string/starts-with? json "{"))
    (t/is (clojure.string/includes? json "\"count\": 1000"))
    (t/is (clojure.string/includes? json "\"p99.9\": 3000"))))

(defn run [_]
  (let [{:keys [fail error]} (t/run-tests 'httpbench.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
