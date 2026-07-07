(ns examples.showcase
  "A guided tour of suzanne's DSL, focused on HOW TO COMPOSE the pieces:
  shapes are plain data, so ordinary Clojure (let, for, map, apply, defn) is
  the composition mechanism. Each example below introduces one or two
  patterns; the gallery at the bottom puts them all in one scene.

  Run from a checkout REPL:

      (load-file \"examples/showcase.clj\")
      (in-ns 'examples.showcase)
      (s/preview! gallery)          ;; or (s/start-live-viewer!) then
      (s/live-view! gallery)        ;; ... for the orbitable version

  Every def here is pure data. Nothing touches Blender until preview!,
  live-view!, or build!."
  (:require [suzanne.core :as s]
            [libpython-clj2.python :as py]))

;; ---------------------------------------------------------------------------
;; 1. A die (a single dice), generating cutters with `for` and splicing with `apply`
;;
;; Patterns: data-driven geometry (pip layouts as a map), a seq of shapes
;; spliced into a variadic boolean with `apply`, and the classic
;; intersection-with-a-sphere trick for rounding a cube.

(def ^:private pip-layouts
  "Pip grid offsets per face value, in [-1 0 1] units."
  {1 [[0 0]]
   2 [[-1 -1] [1 1]]
   3 [[-1 -1] [0 0] [1 1]]
   4 [[-1 -1] [-1 1] [1 -1] [1 1]]
   5 [[-1 -1] [-1 1] [0 0] [1 -1] [1 1]]
   6 [[-1 -1] [-1 0] [-1 1] [1 -1] [1 0] [1 1]]})

(defn- pip-centers
  "3D centers for the pips of `value` on the die face along `axis`."
  [value axis]
  (for [[i j] (pip-layouts value)]
    (case axis
      :+z [(* 5 i) (* 5 j) 10]    :-z [(* 5 i) (* 5 j) -10]
      :+x [10 (* 5 i) (* 5 j)]    :-x [-10 (* 5 i) (* 5 j)]
      :+y [(* 5 i) 10 (* 5 j)]    :-y [(* 5 i) -10 (* 5 j)])))

(def die
  ;; Opposite faces of a die sum to 7.
  (apply s/difference
         (s/intersection (s/cube 20) (s/sphere 13.5)) ;; rounded cube
         (for [[axis value] {:+z 1 :-z 6 :+x 2 :-x 5 :+y 3 :-y 4}
               c            (pip-centers value axis)]
           (s/translate c (s/with-segments 24 (s/sphere 2.2))))))

;; ---------------------------------------------------------------------------
;; 2. A vase — computing a revolve profile with math
;;
;; Patterns: outline points are just data, so generate them (here a
;; sine-modulated radius), and build hollow revolved forms by walking the
;; outline up the outer wall and back down the inner wall.

(def vase
  (let [height    40
        steps     30
        wall      1.8
        r-at      (fn [z] (+ 10 (* 3.5 (Math/sin (/ z 8.0)))))
        outer     (for [k (range (inc steps))]
                    (let [z (* height (/ (double k) steps))]
                      [(r-at z) z]))
        ;; inner wall: the same curve, inset, traversed back down,
        ;; stopping above the floor
        inner     (->> outer
                       (map (fn [[x z]] [(- x wall) z]))
                       (filter (fn [[_ z]] (> z wall)))
                       reverse)
        profile   (concat [[0 0]] outer inner [[0 wall]])]
    (s/rotate-extrude {:segments 64} (s/polygon profile))))

;; ---------------------------------------------------------------------------
;; 3. A star knob — reusable outline functions
;;
;; Patterns: write outline generators as plain functions, then feed them to
;; the extrudes. Also: cutters should overshoot the material they cut
;; (the -1/+2 below) so booleans never fight over coplanar faces.

(defn star-outline
  "A 2D star with n points, alternating between outer and inner radius."
  [n outer inner]
  (s/polygon
    (for [k (range (* 2 n))]
      (let [r (if (even? k) outer inner)
            t (* k (/ Math/PI n))]
        [(* r (Math/cos t)) (* r (Math/sin t))]))))

(def star-knob
  (s/difference
    (s/union
      (s/linear-extrude {:height 8} (star-outline 8 16 11))
      (s/cylinder 9 14 :center false))                       ;; boss on top
    (s/translate [0 0 -1]                                    ;; overshoot both ends
      (s/linear-extrude {:height 16}
                        (s/with-segments 6 (s/circle 5)))))) ;; hex bore

;; ---------------------------------------------------------------------------
;; 4. A pipe elbow — partial revolves
;;
;; Patterns: rotate-extrude with :angle < 360 caps the swept ends; hollow
;; pipes are an outer sweep minus an inner sweep. The inner cutter is swept
;; one degree further and backed up half a degree so its end faces clear the
;; outer solid's end faces (the same overshoot idea as the knob bore).

(defn- offset-outline
  "Shift a circle outline sideways so it revolves at ring-radius from the axis."
  [ring-radius r segs]
  (s/polygon (map (fn [[x y]] [(+ x ring-radius) y])
                  (:points (s/with-segments segs (s/circle r))))))

(def elbow
  (s/difference
    (s/rotate-extrude {:angle 90 :segments 48} (offset-outline 20 6 32))
    (s/rotate [0 0 (Math/toRadians -0.5)]
      (s/rotate-extrude {:angle 91 :segments 48} (offset-outline 20 4.5 32)))))

;; ---------------------------------------------------------------------------
;; 5. An L-bracket — engineering style with let-bound dimensions
;;
;; Patterns: name every dimension in a let so the geometry reads like a
;; drawing; :center false is usually more natural for stock-and-holes work;
;; rotate cylinders to drill along other axes.

(def bracket
  (let [t 4 leg 40 w 30 hole-r 2.6]
    (s/difference
      (s/union
        (s/cube leg w t :center false)      ;; flat leg along +x
        (s/cube t w leg :center false))     ;; upright leg along +z
      ;; two holes down through the flat leg
      (s/translate [(* leg 0.7) (* w 0.28) -1] (s/cylinder hole-r (+ t 2) :center false))
      (s/translate [(* leg 0.7) (* w 0.72) -1] (s/cylinder hole-r (+ t 2) :center false))
      ;; one hole through the upright, drilled along x
      (s/translate [-1 (/ w 2.0) (* leg 0.7)]
        (s/rotate (/ Math/PI 2) [0 1 0]
          (s/cylinder hole-r (+ t 2) :center false))))))

;; ---------------------------------------------------------------------------
;; 6. Washers — parameterized parts and laying out variants
;;
;; Patterns: a part is a function returning data, so families of parts are
;; just map calls. For scenes, prefer a VECTOR of shapes over union: build!
;; and preview! accept seqs, and skipping the boolean is much faster when
;; the parts don't actually touch.

(defn washer [outer-d inner-d thickness]
  (s/difference
    (s/cylinder (/ outer-d 2.0) thickness)
    (s/cylinder (/ inner-d 2.0) (+ thickness 2))))  ;; overshoot again

(def washer-row
  (map-indexed
    (fn [i [od id t]]
      (s/translate [(* i 22) 0 0] (washer od id t)))
    [[20 10 3] [16 8 3] [12 6 2.5] [9 4.5 2]]))

;; ---------------------------------------------------------------------------
;; 7. Below the DSL — loft-mesh
;;
;; When a form is "a stack of cross-sections", skip CSG entirely: loft-mesh
;; bridges any sequence of equal-length [x y z] rings with quads. Here the
;; classic square-to-round transition duct, morphing one outline into
;; another with plain interpolation. loft-mesh returns {:verts ... :faces ...}
;; for mesh-object!, so it gets realized inside build-extras! below rather
;; than as pure data.

(def duct-mesh
  (let [round  (:points (s/with-segments 48 (s/circle 12)))
        ;; the same 48 points projected onto a square boundary, so the two
        ;; rings pair up point-for-point
        square (map (fn [[x y]]
                      (let [m (max (Math/abs (double x)) (Math/abs (double y)))]
                        [(* 14 (/ x m)) (* 14 (/ y m))]))
                    round)
        levels 12
        height 34
        ring   (fn [t z]
                 (mapv (fn [[sx sy] [cx cy]]
                         [(+ sx (* t (- cx sx))) (+ sy (* t (- cy sy))) z])
                       square round))]
    (s/loft-mesh (for [k (range (inc levels))]
                   (ring (/ (double k) levels) (* height (/ (double k) levels))))
                 {:caps? true})))

;; ---------------------------------------------------------------------------
;; The gallery: everything above in one scene
;;
;; Patterns: a scene is a vector (no unions between separate parts), colors
;; are per-part, and layout is plain translate arithmetic.

(def gallery
  [(s/translate [20 30 10]  (s/color [0.92 0.88 0.82 1.0] die))
   (s/translate [65 30 0]   (s/color [0.45 0.62 0.85 1.0] vase))
   (s/translate [-30 30 0]  (s/color [0.85 0.35 0.30 1.0] star-knob))
   (s/translate [-70 -40 6]
     (s/rotate [0 0 (Math/toRadians 135)] (s/color [0.55 0.55 0.58 1.0] elbow)))
   (s/translate [-35 -55 0] (s/color [0.35 0.7 0.5 1.0] bracket))
   (s/translate [30 -45 1]  (s/color [0.75 0.65 0.3 1.0] washer-row))
   ;; duct-mesh is realized separately in build-extras! (see below)
   ])

(defn build-extras!
  "Realize the non-DSL example (the lofted duct) into the current scene."
  []
  (let [obj (s/mesh-object! "duct" duct-mesh)]
    (py/set-attr! obj "location" [-80 30 0])
    obj))

(defn render-gallery!
  "Build the whole gallery and render it to a PNG (used for doc/gallery.png)."
  [path]
  (s/clean-scene!)
  (s/build! gallery)
  (build-extras!)
  (s/render-preview! path :resolution 1000 :samples 48
                     :center [0 -5 10] :size 125))

(comment
  ;; individual parts
  (s/preview! die)
  (s/preview! vase)
  (s/preview! star-knob)

  ;; the whole scene
  (s/preview! gallery)
  (s/start-live-viewer!)
  (s/live-view! gallery)

  ;; debugging aids: temporarily isolate or drop parts without deleting code
  (s/preview! (s/union bracket (s/show-only elbow)))  ;; builds only the elbow
  (s/preview! (s/union bracket (s/disable elbow)))    ;; builds only the bracket
  (s/preview! (s/union bracket (s/highlight elbow)))  ;; elbow in red

  ;; print one part
  (do (s/clean-scene!)
      (s/export-stl! (s/build! star-knob) "star-knob.stl"))

  (render-gallery! "doc/gallery.png"))
