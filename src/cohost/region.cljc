(ns cohost.region
    "Window regions: a file read in place through a memory mapping -- the
   bulk-data companion of cohost.pool's shared atoms. A producer writes
   a region file (decoded samples, frames); a consumer reads the bytes
   it needs at their offset, without the file's value ever becoming an
   atom's root. The machinery is Eve's region substrate, hosted here."
    (:require #?@(:clj [[eve.region :as region]
                        [eve.mem :as mem]])))

(defn read-bytes
      "The bytes of the region file at path, [offset, offset+length): the
   file mapped, the range read in place. Answers a byte array on the
   JVM seat; other seats' arms arrive with their members."
      [path offset length]
      #?(:clj (let [r (region/open-mmap-region path (+ offset length))]
                   (mem/-read-bytes r offset length))
         :default (throw (ex-info "cohost.region: no region arm on this seat" {:path path}))))
