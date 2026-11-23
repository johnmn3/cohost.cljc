(ns hooks.cohost.memory-eve
  (:require [clj-kondo.hooks-api :as api]))

(defn defwrapper
  "Teach clj-kondo cohost.memory-eve/defwrapper:
     (defwrapper TypeName :kind {:ifn {...} :seq 'form}
       SomeInterface
       (method [self args] body...)
       ...)
   emits a deftype TypeName [root meta] whose method bodies close
   over the fields root/meta. Without teaching, kondo reads the
   method forms as ordinary calls (method names resolve to core
   vars, params read as free symbols). Rewrite to
     (do (deftype TypeName [root meta]) :kind {opts}
         (fn [root meta] root meta (fn [self args] body...)) ...)
   so the type registers as a type (constructor calls resolve and
   arity-check against the two fields), the fields count as used,
   and every method body is analyzed with its params bound.
   Interface symbols are the emitted deftype's concern, not
   expressions, and are dropped; the opts map's forms are quoted
   data and pass through untouched."
  [{:keys [node]}]
  (let [[_ name-node kind-node opts-node & extra] (:children node)
        method-nodes (filter (fn [n] (= :list (:tag n))) extra)
        fns (map (fn [m]
                   (let [[_mname args-node & body] (:children m)]
                     (api/list-node
                      [(api/token-node 'fn)
                       (api/vector-node [(api/token-node 'root)
                                         (api/token-node 'meta)])
                       (api/token-node 'root)
                       (api/token-node 'meta)
                       (api/list-node
                        (concat [(api/token-node 'fn) args-node] body))])))
                 method-nodes)
        new-node (api/list-node
                  (concat [(api/token-node 'do)
                           (api/list-node [(api/token-node 'deftype) name-node
                                           (api/vector-node [(api/token-node 'root)
                                                             (api/token-node 'meta)])])
                           kind-node
                           opts-node]
                          fns))]
    {:node new-node}))
