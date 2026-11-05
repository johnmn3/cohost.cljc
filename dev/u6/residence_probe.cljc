;; Eve-in-cohost unit 6 PREP: the disk-resident-root probe (MEMORY-PLAN.md
;; "Unit 6 -- Slab residence on the jank seat", gate item: "a deref of a
;; disk-resident atom root answering after a process restart (residence is
;; real, not cached)"). Design: doc/eve-cohost/UNIT6-DESIGN.md.
;;
;; One program, two roles. dev/u6/residence.sh runs each role as its OWN
;; process, the reader only after the writer has exited:
;;
;;   write <base-path> <tag>  open/create Eve's mmap atom domain at base-path,
;;                            reset! the probe's named atom to the fixed probe
;;                            value (carrying tag), force it to disk
;;                            (eve.atom/sync!), print the ROOT line, exit.
;;   read  <base-path>        a fresh process: join the same domain, deref the
;;                            named atom, print the ROOT line, exit.
;;
;; ROOT line: "ROOT role=<r> pid=<pid> digest=<8 i32 words, comma-joined> value=<pr-str>".
;; The digest is Eve's own root digest (eve.digest/root-digest: SHA-256 over
;; the root's canonical codec bytes, latched in the root's write-once cache
;; block), taken from the resident root under a pinned epoch
;; (eve.atom/read-pinned). The runner compares the writer's and the reader's
;; lines: same digest, same value, different pid = the root is resident.
;;
;; Everything this program calls is Eve's real API (eve.atom, eve.digest,
;; eve.deftype-proto.data on Eve main d241de3); there is no stand-in. On a
;; seat where Eve does not load yet (jank, before the Eve-on-jank flagship
;; gate) the require fails, the program prints "GATED <reason>" and exits 3:
;; the leg is reported gated, never faked.
(ns u6.residence-probe)

(def gated-exit 3)

(defn- exit! [code]
  #?(:jank (cpp/exit code)
     :clj (System/exit code)))

(defn- pid []
  #?(:jank (cpp/getpid)
     :clj (.pid (java.lang.ProcessHandle/current))))

(defn- load-eve!
  "nil when Eve's atom, digest and data namespaces load on this seat, else
   the reason they did not."
  []
  (some (fn [ns-sym]
          (try
            (require ns-sym)
            nil
            (catch #?(:jank :default :clj Throwable) e
              ;; jank 0.1 binds a failed require's error as nil (measured
              ;; 2026-11-05), so on jank the reason is the namespace alone
              (str "(require '" ns-sym ") failed on this seat"
                   (when (some? e)
                     (str ": " #?(:jank (pr-str e) :clj (.getMessage ^Throwable e))))))))
        '[eve.atom eve.digest eve.deftype-proto.data]))

(defn- v
  "Eve's var for sym, resolved at run time: this file must read and compile
   on a seat where Eve is not loadable yet, so it names no Eve var
   statically."
  [sym]
  @(resolve sym))

(def atom-id :u6.residence-probe/root)

(defn probe-value
  "The fixed probe value: each collection kind unit 4 re-points, nested,
   plus the writer's tag, so a stale domain cannot pass for a fresh write."
  [tag]
  {:tag tag
   :vec [1 2 3 [4 5] "six"]
   :map {:a 1 "b" [2] :c {:d #{:e :f}}}
   :set #{1 :two "three"}
   :list '(7 8 9)
   :n 1234567890123})

(defn- open-atom [base]
  ((v 'eve.atom/atom) {:id atom-id :persistent base} nil))

(defn- root-digest
  "Eve's digest of the atom's resident root, read under a pinned epoch."
  [a]
  (let [eve-sio (v 'eve.deftype-proto.data/-eve-sio)
        hdr-off (v 'eve.deftype-proto.data/-root-header-off)
        digest (v 'eve.digest/root-digest)]
    ((v 'eve.atom/read-pinned) a
     (fn [root] (digest (eve-sio root) (hdr-off root) root)))))

(defn- root-line [role a]
  (str "ROOT role=" role
       " pid=" (pid)
       ;; comma-joined, no spaces: the runner reads fields up to a space
       " digest=" (if-let [d (root-digest a)] (apply str (interpose "," d)) "nil")
       " value=" (pr-str @a)))

(defn -main [& [role base tag]]
  (when-not (contains? #{"write" "read"} role)
    (println "usage: write <base-path> <tag> | read <base-path>")
    (exit! 2))
  (when-let [why (load-eve!)]
    (println "GATED" why)
    (exit! gated-exit))
  (let [a (open-atom base)]
    (when (= role "write")
      (reset! a (probe-value tag))
      ((v 'eve.atom/sync!) a))
    (println (root-line role a))
    ((v 'eve.atom/close!) a))
  (exit! 0))

#?(:jank nil
   :clj (apply -main *command-line-args*))
