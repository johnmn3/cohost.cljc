;; Eve-in-cohost unit 7: bindings conveyance across processes, riding the
;; shared atom. Two JVM processes, run one after the other on one <path>:
;;   dev/u7/clj-eve.sh dev/u7/convey.clj publish <path>   ; A binds *tenant*, captures it in a future
;;   dev/u7/clj-eve.sh dev/u7/convey.clj receive <path>   ; B's future, no binding of its own
;; Program text is the same in both: a shared :disk atom with :eve/conveys
;; true, and a dynamic var whose meta names it (:eve/convey). B's future
;; must answer A's binding; B's own binding still wins over A's frame.
(require 'cohost.hosts.auto)
(require '[cohost.atom :as al]
         '[cohost.atom-eve :as hosting]
         '[cohost.thread :as thread])

(hosting/install!)
(al/set-fill! true)

(let [[mode path] *command-line-args*]
  (def shared (al/make-atom {} {:meta {:eve/kind :disk :eve/path path :eve/name "u7/shared"
                                       :eve/conveys true}}))
  (def ^{:dynamic true :eve/convey shared} *tenant* :unbound-default)
  (def ^:dynamic *local-only* :plain)
  (case mode
    "publish"
    (let [seen (binding [*tenant* "acme" *local-only* :a-side]
                 (thread/deref-future (thread/future-call (fn [] [*tenant* *local-only*]))))]
      (println (str "CONVEY " (pr-str {:side :A :future-saw seen}))))
    "receive"
    (let [seen (thread/deref-future (thread/future-call (fn [] [*tenant* *local-only*])))
          own (binding [*tenant* "b-own"]
                (thread/deref-future (thread/future-call (fn [] *tenant*))))]
      (println (str "CONVEY " (pr-str {:side :B :future-saw seen :own-binding-wins own}))))
    "refuse"
    ;; an unrepresentable binding value for a conveyed var fails loudly at capture
    (println (str "CONVEY " (pr-str {:side :A :refused
                                     (try (binding [*tenant* (fn [] :x)]
                                            (thread/future-call (fn [] *tenant*)) :no-throw)
                                          (catch Throwable e (:cohost.atom/error (ex-data e))))}))))
  (hosting/close-disk-domain! path))
(shutdown-agents)
(System/exit 0)
