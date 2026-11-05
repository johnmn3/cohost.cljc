(ns cohost.memory
  "Eve's value layer, hosted in cohost: the seam-identity contract for
   Eve-backed values (MEMORY-PLAN.md, unit 3).

   A cohosted program's values are host values (Zone H). An Eve-backed
   value (Zone E) reaches the program as a stand-in: a plain map tagged
   :cohost/kind ::root, holding the Eve root under :memory/root and the
   value's canonical Clojure-level form under :memory/form. Eve values
   are immutable, so the canonical form is computed once, when the
   root is wrapped (on the JVM seat: cohost.memory-eve), and never
   changes under the stand-in.

   Equality and hashing are seam-level contracts, not representation
   properties. This namespace defines them:

   - equiv: two values are equiv when their canonical forms are equal
     as host values. A stand-in's canonical form is its :memory/form;
     any other value is its own canonical form. So a Zone-E vector
     equals the Zone-H vector with the same elements, in both argument
     orders, and two stand-ins wrapping equal roots are equiv --
     retiring the tagged-map distance unit 1 carried (equality = map
     equality of the stand-in), under which a stand-in equalled
     nothing but a byte-identical stand-in.
   - hasheq: the host hash of the canonical form -- the Murmur-family
     collection hash over the value's Clojure-level form, exactly as
     stock defines it for the Zone-H twin. Eve's SHA-256 Merkle
     digest is the root's identity inside Eve; it never crosses the
     seam as a hash answer (John's ruling: the mapping owed is
     semantic, never numeric).

   The shim's equality/hash dispatch (Util-equiv, Util-hasheq,
   -equiv, -hasheq, -hashCode in cohost.shim) routes through here
   whenever either side is an Eve-backed stand-in; every other value
   takes the host's own answer, unchanged.

   Named distances, kept from the plan:
   - identical? never crosses zones (plan D2): a stand-in is not its
     root, and two stand-ins are not identical however equal.
   - The contract holds at the seam's dispatch points for the values
     themselves. A stand-in nested inside a Zone-H collection is
     compared by the host collection's own machinery, which on the
     JVM seat does not route through the shim; unit 4's Eve-backed
     collections are where nested values meet this layer again."
  (:refer-clojure :exclude [equiv hasheq]))

(defn eve-value?
  "True when x is an Eve-backed stand-in of this layer."
  [x]
  (and (map? x) (= ::root (:cohost/kind x))))

(defn wrap
  "Build the stand-in for an Eve root. kind names the root's
   collection kind (:vector, :map, :set, :list, or :value for a
   scalar root); root is the Eve value itself (opaque to this
   layer); form is root's canonical Clojure-level form -- a host
   value equal to the root, with every nested Eve value likewise
   canonicalized."
  [kind root form]
  {:cohost/kind ::root
   :memory/kind kind
   :memory/root root
   :memory/form form})

(defn kind
  "The collection kind of an Eve-backed stand-in's root."
  [x]
  (:memory/kind x))

(defn root
  "The Eve root an Eve-backed stand-in holds."
  [x]
  (:memory/root x))

(defn canonical-form
  "x's canonical Clojure-level form: a stand-in's cached form; any
   other value is its own canonical form."
  [x]
  (if (eve-value? x) (:memory/form x) x))

(defn equiv
  "Seam equality: canonical forms equal as host values. Sequential
   forms keep stock semantics (a Zone-E vector equals a Zone-H
   list with the same elements), because the canonical forms are
   host values compared by the host's own =."
  [a b]
  (= (canonical-form a) (canonical-form b)))

(defn hasheq
  "Seam hash: the host hash of the canonical form -- the same
   Murmur-family collection hash stock computes for the Zone-H
   twin, so hasheq agrees wherever equiv holds. Never the Eve
   root's Merkle digest."
  [x]
  (hash (canonical-form x)))
