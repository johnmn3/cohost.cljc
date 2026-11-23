;; The JavaScript surface that cljs/core.cljs (and its related files)
;; calls, mapped onto the host's own JavaScript values. cohost.boot-cljs
;; turns
;;
;;   js/Error                      into  cohost.js-shim/Error
;;   (js/Error. "msg")             into  (cohost.js-shim/Error. "msg")
;;   js/Number.POSITIVE_INFINITY   into  (.-POSITIVE_INFINITY cohost.js-shim/Number)
;;   (.log js/console ...)         into  (.log cohost.js-shim/console ...)
;;
;; so every var here is one API root of the census
;; (cljs-parity/census/core-js-census.txt, cljs 1.12.134): a constructor
;; (Error, Date, RegExp, ...), a namespace-like object (console, process,
;; performance, Number's constants ride the constructor itself), a global
;; function (parseInt, isNaN, setTimeout, eval, ...), or a global value
;; (Infinity, COMPILED, global, window, self).
;;
;; This file is the reference implementation, and its own js/* references
;; ARE its host surface, stated once: a host realm provides the standard
;; JS globals of the same names (a JS engine does; a non-JS host supplies
;; equivalents behind these same vars, the way cohost.shim builds what a
;; host lacks). Each var below delegates to the host global of its name,
;; so the semantics are the host's own: a shim Error IS the realm's Error,
;; its strings and arrays ARE the realm's, carrying the standard methods
;; core.cljs calls on them -- strings: substring, replace, charAt,
;; charCodeAt, split, indexOf, toString; arrays: push, join, sort, map,
;; forEach, indexOf, length; RegExp: exec. A member missing here is
;; reported, not papered over: the rewriter emits missing-*, which
;; throws when reached.
;;
;; Vars marked LIBRARY are not touched by core.cljs or its related files;
;; they are the JS API most used by the majority of libraries (the
;; hiccup-class panel of the census), carried so those libraries run out
;; of the box.
(ns cohost.js-shim
  ;; require is this shim's own var below (the realm's node require),
  ;; not cljs.core's
    (:refer-clojure :exclude [require]))

;; ---------------------------------------------------------------------------
;; errors: core.cljs's throw sites construct these (Error 122x in the
;; census, TypeError and ReferenceError behind it)

(def Error js/Error)
(def TypeError js/TypeError)
(def ReferenceError js/ReferenceError)
;; LIBRARY: the rest of the standard error family
(def SyntaxError js/SyntaxError)
(def RangeError js/RangeError)
(def EvalError js/EvalError)
(def URIError js/URIError)

;; ---------------------------------------------------------------------------
;; constructors and their constant fields (js/Number.POSITIVE_INFINITY is
;; a property read on the Number var itself)

(def Number js/Number)
(def String js/String)
(def Boolean js/Boolean) ;; LIBRARY (core reads it only as a type name)
(def Array js/Array)
(def Object js/Object)
(def Function js/Function)
(def Symbol js/Symbol)
(def Date js/Date)
(def RegExp js/RegExp)
(def DataView js/DataView)
(def ArrayBuffer js/ArrayBuffer) ;; LIBRARY-adjacent: DataView's pair
(def Float64Array js/Float64Array)
;; LIBRARY: the remaining typed arrays and ES6 collections
(def Int8Array js/Int8Array)
(def Uint8Array js/Uint8Array)
(def Uint8ClampedArray js/Uint8ClampedArray)
(def Int16Array js/Int16Array)
(def Uint16Array js/Uint16Array)
(def Int32Array js/Int32Array)
(def Uint32Array js/Uint32Array)
(def Float32Array js/Float32Array)
(def Map js/Map)
(def Set js/Set)
(def WeakMap js/WeakMap)
(def WeakSet js/WeakSet)
(def Promise js/Promise)
(def BigInt js/BigInt)
;; LIBRARY: BigInt's typed arrays (eve's codec names them, exists?-guarded)
(def BigInt64Array (js* "typeof BigInt64Array !== 'undefined' ? BigInt64Array : void 0"))
(def BigUint64Array (js* "typeof BigUint64Array !== 'undefined' ? BigUint64Array : void 0"))

;; ---------------------------------------------------------------------------
;; namespace-like globals

(def console js/console)
(def process (js* "typeof process !== 'undefined' ? process : void 0"))
(def performance (js* "typeof performance !== 'undefined' ? performance : void 0"))
;; Node interop roots, for library code that runs in a node realm (eve's
;; cljs adapters, an app's npm tasks): the realm global the :node seat
;; installs -- a require anchored at the seat's :require-root, the module
;; object, Node's Buffer. Absent on the graal and browser realms, where
;; these are void 0 and the libraries' (exists? js/require) guards read
;; false, exactly as before these vars existed.
(def require (js* "typeof require !== 'undefined' ? require : void 0"))
(def module (js* "typeof module !== 'undefined' ? module : void 0"))
(def Buffer (js* "typeof Buffer !== 'undefined' ? Buffer : void 0"))
(def Math js/Math)   ;; LIBRARY
(def JSON js/JSON)   ;; goog.string and libraries; core.cljs proper does not

;; ---------------------------------------------------------------------------
;; LIBRARY: the wider platform surface the library census names -- eve's
;; cljs adapters and app code (encoding's TextEncoder/TextDecoder pair is
;; the load-bearing one: eve.encoding constructs both at namespace load).
;; Every var is typeof-guarded like process/performance above: a realm
;; that lacks the global leaves the var undefined, so the libraries'
;; (exists? js/X) guards keep their per-host meaning.

(def TextEncoder (js* "typeof TextEncoder !== 'undefined' ? TextEncoder : void 0"))
(def TextDecoder (js* "typeof TextDecoder !== 'undefined' ? TextDecoder : void 0"))
(def SharedArrayBuffer (js* "typeof SharedArrayBuffer !== 'undefined' ? SharedArrayBuffer : void 0"))
(def Atomics (js* "typeof Atomics !== 'undefined' ? Atomics : void 0"))
(def WebAssembly (js* "typeof WebAssembly !== 'undefined' ? WebAssembly : void 0"))
(def Blob (js* "typeof Blob !== 'undefined' ? Blob : void 0"))
(def URL (js* "typeof URL !== 'undefined' ? URL : void 0"))
(def MessageChannel (js* "typeof MessageChannel !== 'undefined' ? MessageChannel : void 0"))
(def Worker (js* "typeof Worker !== 'undefined' ? Worker : void 0"))
(def WorkerGlobalScope (js* "typeof WorkerGlobalScope !== 'undefined' ? WorkerGlobalScope : void 0"))
(def Window (js* "typeof Window !== 'undefined' ? Window : void 0"))
(def XMLHttpRequest (js* "typeof XMLHttpRequest !== 'undefined' ? XMLHttpRequest : void 0"))
(def crypto (js* "typeof crypto !== 'undefined' ? crypto : void 0"))
(def document (js* "typeof document !== 'undefined' ? document : void 0"))
(def location (js* "typeof location !== 'undefined' ? location : void 0"))
(def navigator (js* "typeof navigator !== 'undefined' ? navigator : void 0"))

;; ---------------------------------------------------------------------------
;; global functions and values

(def parseInt js/parseInt)
(def parseFloat js/parseFloat)
(def isNaN js/isNaN)
(def isFinite js/isFinite)
(def setTimeout js/setTimeout)
(def eval js/eval)
(def Infinity js/Infinity)
;; LIBRARY: the rest of the commonly used global functions
(def clearTimeout js/clearTimeout)
(def setInterval js/setInterval)
(def clearInterval js/clearInterval)
(def encodeURIComponent js/encodeURIComponent)
(def decodeURIComponent js/decodeURIComponent)
(def encodeURI js/encodeURI)
(def decodeURI js/decodeURI)
;; LIBRARY: guarded like the platform vars above (setImmediate is node's;
;; fetch and the base64 pair are absent from some realms)
(def atob (js* "typeof atob !== 'undefined' ? atob : void 0"))
(def btoa (js* "typeof btoa !== 'undefined' ? btoa : void 0"))
(def fetch (js* "typeof fetch !== 'undefined' ? fetch : void 0"))
(def setImmediate (js* "typeof setImmediate !== 'undefined' ? setImmediate : void 0"))

;; ---------------------------------------------------------------------------
;; the realm's global object, under the names core.cljs asks for it.
;; COMPILED is the Closure define: false in an uncompiled (booted) realm,
;; which is the branch core.cljs's own setup code expects.

(def global js/globalThis)
(def globalThis js/globalThis) ;; LIBRARY: named directly by eve's adapters
(def goog js/goog) ;; LIBRARY: eve's thread adapters resolve entry fns through
                   ;; goog.getObjectByName; the realm's Closure base defines
                   ;; the object before any graph file compiles.
(def NaN (js* "typeof NaN !== 'undefined' ? NaN : void 0")) ;; LIBRARY: js/NaN in eve's numeric code
(def undefined (js* "void 0")) ;; LIBRARY: js/undefined in eve's http adapter
(def window (js* "typeof window !== 'undefined' ? window : void 0"))
(def self (js* "typeof self !== 'undefined' ? self : void 0"))
(def COMPILED false)

;; js/Java is the GraalJS host-interop global, read by core.cljs's
;; graaljs branch. Undefined where the host has none, so (exists? js/Java)
;; keeps its meaning per host.
(def Java (js* "typeof Java !== 'undefined' ? Java : void 0"))
