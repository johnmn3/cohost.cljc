(ns httpbench.core
  "httpbench: an HTTP/1.1 benchmark whose hot half is C++ (httpbench.native:
   uWebSockets server, uSockets client, simdjson validation, the latency
   histogram) and whose cold half is ordinary Clojure -- tools.cli args,
   config, the report. Two modes in one binary:
     httpbench serve --port P
     httpbench bench --url http://127.0.0.1:P/ --requests N --concurrency C [--json]"
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [httpbench.native :as native]
            [httpbench.stats :as stats]))

(defn parse-url
  "\"http://127.0.0.1:8080/a/b\" -> {:host \"127.0.0.1\" :port 8080 :path \"/a/b\"}.
   The port defaults to 80, the path to \"/\"."
  [url]
  (let [[_ host port path] (re-matches #"https?://([^/:]+)(?::(\d+))?(/.*)?" (str url))]
    (when-not host (throw (ex-info (str "httpbench: cannot parse url " url) {:url url})))
    {:host host :port (if port (parse-long port) 80) :path (or path "/")}))

(def ^:private serve-spec
  [["-p" "--port PORT" "port to listen on" :parse-fn parse-long :default 8080]
   ["-h" "--help"]])

(def ^:private bench-spec
  [["-u" "--url URL" "the URL to bench" :default "http://127.0.0.1:8080/"]
   ["-n" "--requests N" "total requests" :parse-fn parse-long :default 100000]
   ["-c" "--concurrency C" "keep-alive connections" :parse-fn parse-long :default 100]
   [nil "--json" "print the result as JSON"]
   ["-h" "--help"]])

(defn- ns->ms [ns] (/ ns 1000000.0))

(defn format-report
  "The plain-text report of a bench result map (httpbench.stats/summarize
   shape): one line per number, latencies in ns and ms."
  [m]
  (str/join "\n"
            [(str "requests:   " (:count m))
             (str "wall:       " (format "%.3f" (ns->ms (:wall-ns m))) " ms")
             (str "requests/s: " (format "%.1f" (:req-per-sec m)))
             (str "errors:     " (:errors m))
             (str "bytes:      " (:bytes m))
             (str "p50:        " (:p50 m) " ns (" (format "%.3f" (ns->ms (:p50 m))) " ms)")
             (str "p90:        " (:p90 m) " ns (" (format "%.3f" (ns->ms (:p90 m))) " ms)")
             (str "p99:        " (:p99 m) " ns (" (format "%.3f" (ns->ms (:p99 m))) " ms)")
             (str "p99.9:      " (:p99.9 m) " ns (" (format "%.3f" (ns->ms (:p99.9 m))) " ms)")
             (str "max:        " (:max m) " ns (" (format "%.3f" (ns->ms (:max m))) " ms)")]))

(def ^:private json-keys
  [:count :req-per-sec :errors :bytes :wall-ns :p50 :p90 :p99 :p99.9 :max])

(defn format-json
  "The same report as one JSON object (stock core only: the values are all
   numbers, so this is a join, not an encoder)."
  [m]
  (str "{"
       (str/join ", "
                 (map (fn [k] (str "\"" (str/replace (name k) "-" "_") "\": "
                                   (let [v (get m k)] (if (integer? v) v (format "%.1f" v)))))
                      json-keys))
       "}"))

(defn run-bench
  "Run one bench from an options map ({:url :requests :concurrency :json})
   -> the printed report string."
  [{:keys [url requests concurrency json]}]
  (let [{:keys [host port path]} (parse-url url)]
    (native/bench! host port path requests concurrency)
    (let [m (stats/summarize (native/result))]
      (if json (format-json m) (format-report m)))))

(defn -main [& args]
  (let [[mode & more] args]
    (case mode
      "serve" (let [{:keys [options summary]} (cli/parse-opts more serve-spec)]
                (if (:help options)
                  (println summary)
                  (try (native/serve! (:port options))
                       (catch ExceptionInfo e (println (ex-message e))))))
      "bench" (let [{:keys [options summary]} (cli/parse-opts more bench-spec)]
                (if (:help options)
                  (println summary)
                  (try (println (run-bench options))
                       (catch ExceptionInfo e (println (ex-message e))))))
      (do (println "httpbench needs jank")
          (println "usage: httpbench serve --port P | httpbench bench --url URL --requests N --concurrency C [--json]")))))

(defn run
  "The template's -X:run entry."
  [{:keys [who]}]
  (println (str "httpbench says hello to " who)))
