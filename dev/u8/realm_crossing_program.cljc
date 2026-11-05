(do
  (defn probe [nm f]
    (try (println (str "PROBE|" nm "|OK " (pr-str (f))))
         (catch Throwable t
           (println (str "PROBE|" nm "|FAIL " (.getName (class t)) " :: " (ex-message t))))))
  ;; a value made in another worker's heap, published into an atom the
  ;; caller made, read back here and from a further future
  (probe "made-elsewhere" (fn [] (let [a (atom nil)]
                                   (deref (future (reset! a {:made :elsewhere :v (vec (range 3))})))
                                   [(deref a) (deref (future (update (deref a) :v conj 3)))])))
  ;; an atom created in another worker's heap, returned, then swapped here
  (probe "atom-born-elsewhere" (fn [] (let [b (deref (future (atom {:born :elsewhere})))]
                                        (swap! b assoc :touched :here)
                                        (deref b))))
  ;; both sides swapping one atom: exact
  (probe "contended-swaps" (fn [] (let [c (atom 0)
                                        f (future (dotimes [_ 500] (swap! c inc)) :done)]
                                    (dotimes [_ 500] (swap! c inc))
                                    [(deref f) (deref c)])))
  ;; a binding frame and a captured local, through the atom
  (probe "frame-and-local" (fn [] (let [x 40]
                                    (binding [*print-length* 2]
                                      (deref (future [(+ x 2) *print-length*]))))))
  (println "PROBE|done|OK")
  (try (shutdown-agents) (catch Throwable _))
  (try (System/exit 0) (catch Throwable _)))
