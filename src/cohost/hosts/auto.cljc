(ns cohost.hosts.auto
  "Requires the adapter for whichever host is reading this file. (:jolt is
   listed before :clj on purpose: jolt reads :clj arms too.)"
  (:require #?(:lpy [cohost.hosts.basilisp]
               :jolt [cohost.hosts.jolt]
               :cljs [cohost.hosts.cljs]
               :clj [cohost.hosts.jvm])))
