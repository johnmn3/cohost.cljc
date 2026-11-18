(ns cohost.thread-host
  "Worker threads and their mailboxes, on the seats where a program is
   more than one thread of one image (a browser's workers, Node's
   worker threads): start-threads! starts workers running a named
   entry with a boot map; send! and receive! are the mailboxes their
   messages cross by (a message is data); self is this thread's
   address; attempt runs a fn and answers [true value] or [false
   error]; run-thread! is the entry dispatch every thread of the image
   calls first -- in a worker it runs the worker's entry and answers
   true, on the main thread it answers false.

   The machinery is Eve's thread host, hosted here the way cohost
   hosts Eve everywhere: the Eve names stay on this side of the wall,
   and the program's side is clojure-shaped throughout."
  (:require [eve.thread-host :as th]))

(defn self
  "This thread's address: what send! takes and a boot map carries."
  [] (th/self))

(defn send!
  "Write msg into the mailbox of the thread at addr; never blocks. A
   message must be data."
  [addr msg] (th/send! addr msg))

(defn receive!
  "This thread's next message, waiting up to ms (0: poll). A thread
   that cannot block (a page's main thread) may only poll."
  [ms] (th/receive! ms))

(defn attempt
  "Run f, answering [true value] or [false error]."
  [f] (th/attempt f))

(defn start-threads!
  "Start n workers, each running the entry named by the symbol entry
   with the boot map (its :self assoc'd); their addresses, at once."
  [n entry boot opts] (th/start-threads! n entry boot opts))

(defn run-thread!
  "In a worker this program started: run its entry and answer true.
   On the main thread: false."
  [] (th/run-thread!))
