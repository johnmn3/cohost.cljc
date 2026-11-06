;; The ValueScript side of cohost.hosts.auto. VS's loader resolves a `.vs` file
;; before the `.cljc` beside it (vs-wasm-leg lib_path: "vs clj cljc cljs"), and
;; every other host's loader never reads a `.vs` — the per-host file convention
;; of this directory (jank_host.jank, basilisp.lpy, phel.phel). The .cljc
;; dispatches by reader conditional, which VS reads as :clj (a .cljc is :clj
;; there, John's 2026-10-11 ruling; VS's reader has no :vs platform key).
(ns cohost.hosts.auto
  (:require [cohost.hosts.vs]))
