;; Smoke test: exercises primitives, booleans, extrudes, transforms, and STL
;; export. Run with: clojure -M:smoke
;;
;; Prints SMOKE PASSED on success. Note the process may still exit nonzero
;; because Blender's teardown segfaults after all work completes (a known,
;; harmless quirk); trust the printed verdict, not the exit code.
(require '[suzanne.core :as s])

(s/init!)
(s/clean-scene!)

(let [obj (s/build! (s/difference (s/cube 30) (s/sphere 19) (s/cylinder 8 40)))
      stats (s/mesh-stats obj)]
  (assert (pos? (:faces stats)) "CSG produced no faces")
  (assert (zero? (:non-manifold stats)) "CSG result is not watertight")
  (println "csg:" stats))

(let [obj (s/build! (s/linear-extrude {:height 20 :twist 45}
                                      (s/with-segments 6 (s/circle 10))))
      stats (s/mesh-stats obj)]
  (assert (zero? (:non-manifold stats)) "extrude result is not watertight")
  (println "extrude:" stats))

(let [obj (s/build! (s/rotate-extrude {} (s/polygon (map (fn [[x y]] [(+ x 15) y])
                                                         (:points (s/circle 4))))))
      stats (s/mesh-stats obj)]
  (assert (zero? (:non-manifold stats)) "revolve result is not watertight")
  (println "revolve:" stats))

(let [obj (s/build! (s/translate [5 5 5] (s/rotate [0 0 (/ Math/PI 4)] (s/cube 10))))
      stats (s/mesh-stats obj)]
  (assert (= 6 (:faces stats)) "transformed cube should still have 6 faces")
  (println "transform:" stats))

(let [path (s/preview! (s/union (s/cube 20) (s/sphere 13))
                       {:open? false
                        :path (str (System/getProperty "java.io.tmpdir") "/suzanne-smoke.stl")})]
  (assert (.exists (clojure.java.io/file path)) "STL export missing")
  (println "stl:" path))

(println "SMOKE PASSED")
(System/exit 0)
