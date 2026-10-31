(ns cohost.hosts.auto
  "Requires the adapter for whichever host is reading this file. (:jolt and
   :cljw are listed before :clj on purpose: both read :clj arms too, and the
   first matching arm wins; `bb lint:reader-order` checks the order.)"
  (:require #?(:lpy [cohost.hosts.basilisp]
               :jolt [cohost.hosts.jolt]
               :cljs [cohost.hosts.cljs]
               :cljw [cohost.hosts.cljw]
               :phel [cohost.hosts.phel]
               :lg [cohost.hosts.lg]
               :cljrs [cohost.hosts.cljrs]
               :jank [cohost.hosts.jank]
               :clj [cohost.hosts.jvm])))
