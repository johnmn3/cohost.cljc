;; Same nbody, with ^double/^long on the hot locals.
(def pi 3.141592653589793)
(def solar-mass (* 4.0 pi pi))
(def dpy 365.24)

(defn bodies []
  [[0.0 0.0 0.0 0.0 0.0 0.0 solar-mass]
   [4.84143144246472090
    -1.16032004402742839
    -0.103622044471123109
    (* 0.00166007664274403694 dpy)
    (* 0.00769901118419740425 dpy)
    (* -0.0000690460016972063023 dpy)
    (* 0.000954791938424326609 solar-mass)]
   [8.34336671824457987
    4.12479856412430479
    -0.403523417114321381
    (* -0.00276742510726862411 dpy)
    (* 0.00499852801234917238 dpy)
    (* 0.0000230417293174541192 dpy)
    (* 0.000285885980666130812 solar-mass)]
   [12.8943695621391310
    -15.1111514016986312
    -0.223307578892655734
    (* 0.00296460137564761618 dpy)
    (* 0.00237847173959480950 dpy)
    (* -0.0000296589568540237556 dpy)
    (* 0.0000436624404335158288 solar-mass)]
   [15.3796971148509165
    -25.9193146099879641
    0.179258772950371181
    (* 0.00268067772490389322 dpy)
    (* 0.00162824170038242295 dpy)
    (* -0.0000951592254519715870 dpy)
    (* 0.0000515138902046611451 solar-mass)]])

(defn offset [bs]
  (let [p (reduce (fn [p b]
                    [(+ (p 0) (* (b 3) (b 6)))
                     (+ (p 1) (* (b 4) (b 6)))
                     (+ (p 2) (* (b 5) (b 6)))])
                  [0.0 0.0 0.0] bs)
        sun (bs 0)
        sun (assoc sun
                   3 (/ (- (p 0)) solar-mass)
                   4 (/ (- (p 1)) solar-mass)
                   5 (/ (- (p 2)) solar-mass))]
    (assoc bs 0 sun)))

(defn energy [bs]
  (let [n (count bs)]
    (loop [i 0 e 0.0]
      (if (= i n)
        e
        (let [b (bs i)
              ^double vx (b 3) ^double vy (b 4) ^double vz (b 5) ^double m (b 6)
              e (+ e (* 0.5 m (+ (* vx vx) (* vy vy) (* vz vz))))]
          (recur (inc i)
                 (loop [j (inc i) e e]
                   (if (= j n)
                     e
                     (let [c (bs j)
                           ^double dx (- (b 0) (c 0))
                           ^double dy (- (b 1) (c 1))
                           ^double dz (- (b 2) (c 2))
                           ^double dist (__sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]
                       (recur (inc j) (- e (/ (* m (c 6)) dist))))))))))))

(defn advance [bs ^double dt]
  (let [n (count bs)
        bs (loop [i 0 bs bs]
             (if (= i n)
               bs
               (let [b (bs i)]
                 (recur (inc i)
                        (loop [j (inc i) bs bs b b]
                          (if (= j n)
                            (assoc bs i b)
                            (let [c (bs j)
                                  ^double dx (- (b 0) (c 0))
                                  ^double dy (- (b 1) (c 1))
                                  ^double dz (- (b 2) (c 2))
                                  ^double dist (__sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
                                  ^double mag (/ dt (* dist dist dist))
                                  bm (b 6)
                                  cm (c 6)
                                  b (assoc b
                                           3 (- (b 3) (* dx cm mag))
                                           4 (- (b 4) (* dy cm mag))
                                           5 (- (b 5) (* dz cm mag)))
                                  c (assoc c
                                           3 (+ (c 3) (* dx bm mag))
                                           4 (+ (c 4) (* dy bm mag))
                                           5 (+ (c 5) (* dz bm mag)))]
                              (recur (inc j) (assoc bs j c) b))))))))]
    (mapv (fn [b]
            (assoc b
                   0 (+ (b 0) (* dt (b 3)))
                   1 (+ (b 1) (* dt (b 4)))
                   2 (+ (b 2) (* dt (b 5)))))
          bs)))

(defn -run [s]
  (let [^long n (__long s)]
    (loop [i 0 bs (offset (bodies))]
      (if (= i n)
        (__round (* (energy bs) 1000000.0))
        (recur (inc i) (advance bs 0.01))))))
