(ns cohost.shim-names
  "A compile-time helper for the ClojureScript build: the rewriter needs the
   set of names cohost.shim defines, and self-hosted ClojureScript has no
   ns-publics at runtime. This macro captures them while the compiler has the
   namespace in hand."
  (:require [cljs.analyzer :as ana]
            [cljs.analyzer.api :as api]))

(defmacro public-names
  "The unqualified public names of the namespace being compiled, as a quoted set."
  []
  (list 'quote (set (keys (api/ns-publics ana/*cljs-ns*)))))
