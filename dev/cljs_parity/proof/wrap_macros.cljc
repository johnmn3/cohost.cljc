(ns proof.wrap-macros
  "Macros whose expansions introduce js/* symbols in code position --
   the shape the per-form rewrite cannot see, because the expansion
   materializes during analysis, after the source form was rewritten.
   A library or user macro written this way (or any macro on a
   self-hosted seat) routes around the shim unless expansions are
   wrapped. Each macro below names a root whose shim semantics differ
   from a raw realm global, so wrapped and unwrapped runs disagree
   observably:

   - js/COMPILED: the shim defines it false; as a raw global it is
     unbound -- a ReferenceError on seats that lack it.
   - js/process: typeof-guarded in the shim (undefined where the
     seat has none); a raw global reference throws the same way.
   - js/Error. and js/Number.POSITIVE_INFINITY: real globals on most
     seats, so these agree either way -- they prove the wrapped path
     still constructs and reads through the shim correctly.")

(defmacro shim-compiled
  "Expands to the symbol js/COMPILED in value position."
  []
  'js/COMPILED)

(defmacro shim-process
  "Expands to the symbol js/process in value position."
  []
  'js/process)

(defmacro make-err
  "Expands to a js/Error. construction."
  [msg]
  (list 'js/Error. msg))

(defmacro pos-inf
  "Expands to the dotted symbol js/Number.POSITIVE_INFINITY."
  []
  'js/Number.POSITIVE_INFINITY)
