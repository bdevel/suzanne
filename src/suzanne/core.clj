(ns suzanne.core
  "Blender as a Clojure modeling backend, via bpy in-process (libpython-clj).

  Two layers:

  1. A scad-clj-style data DSL mirroring the OpenSCAD vocabulary: sphere,
     cube, cylinder, polyhedron, circle, square, polygon, linear-extrude,
     rotate-extrude, import-model, translate, rotate, scale, mirror, color,
     union, difference, intersection, disable, show-only, with-segments.
     Shape constructors are pure (they return data); nothing touches Blender
     until (build! shape), (preview! shape), or (live-view! shape).

  2. Low-level utilities: init!, clean-scene!, mesh-object!, boolean-op!,
     export-stl!, export-glb!, save-blend!, mesh-stats, render-preview!, and
     the pure loft-mesh generator for sweep-style construction.

  Conventions follow scad-clj, not raw OpenSCAD, so scad-clj code ports
  directly: primitives are centered by default, rotate takes RADIANS, while
  :twist and :angle options are degrees (as in scad-clj's extrude fns).

  REPL workflow: compose shapes as data, then either (preview! shape), which
  exports an STL and opens it in the OS viewer (Preview.app on macOS has a
  rotatable 3D view), or (start-live-viewer!) once and (live-view! shape),
  which pushes each build into a real Blender GUI viewport that keeps your
  camera orbit between evals. See the comment block at the end.

  Requirements:
  - a Python venv with the bpy wheel: python3.11 -m venv bpy-venv &&
    bpy-venv/bin/pip install bpy==4.5.3 (or scripts/setup-bpy-venv.sh).
    Point SUZANNE_PYTHON or *python-executable* at its python binary.
  - a JVM matching bpy's architecture (on Apple silicon that means an arm64
    JDK, not an Intel build under Rosetta).

  Note: Blender's teardown can segfault when the JVM process exits, after all
  work is complete. Exports are unaffected, and in a long-lived REPL it never
  happens at all; batch scripts should check outputs rather than exit codes."
  (:require [libpython-clj2.python :as py]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

;; --------------------------------------------------------------------------
;; Python / Blender bootstrap

(def ^:dynamic *python-executable*
  "Path to the python binary of the venv holding the bpy wheel.
  Defaults to the SUZANNE_PYTHON env var, else ./bpy-venv/bin/python."
  (or (System/getenv "SUZANNE_PYTHON") "bpy-venv/bin/python"))

(defonce ^:private modules (atom nil))

(defn- python-library-path
  "Ask the venv python where its libpython shared library lives.
  libpython-clj's own probing loads the wrong name on macOS, so we hand it
  the exact path."
  [python-exe]
  (let [code (str "import sysconfig, os, sys\n"
                  "if sys.platform == 'darwin':\n"
                  "    name = 'libpython' + sysconfig.get_python_version() + '.dylib'\n"
                  "else:\n"
                  "    # Linux: the unversioned .so only exists with -dev packages;\n"
                  "    # INSTSONAME is the real runtime name (e.g. libpython3.11.so.1.0)\n"
                  "    name = (sysconfig.get_config_var('INSTSONAME')\n"
                  "            or 'libpython' + sysconfig.get_python_version() + '.so')\n"
                  "print(os.path.join(sysconfig.get_config_var('LIBDIR'), name))")
        {:keys [out exit err]} (sh python-exe "-c" code)]
    (when-not (zero? exit)
      (throw (ex-info "Could not locate libpython shared library"
                      {:python python-exe :err err})))
    (str/trim out)))

(defn init!
  "Initialize embedded Python and import bpy/bmesh/mathutils. Idempotent;
  every fn that talks to Blender calls this on demand. Options:
  :python-executable overrides *python-executable*."
  ([] (init! {}))
  ([{:keys [python-executable]}]
   (or @modules
       (let [exe (or python-executable *python-executable*)]
         (when (and (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
                    (not= "aarch64" (System/getProperty "os.arch")))
           (throw (ex-info (str "bpy on Apple silicon needs an arm64 JVM but this one is "
                                (System/getProperty "os.arch")
                                ". Use an arm64 JDK (e.g. brew install openjdk, then "
                                "JAVA_HOME=/opt/homebrew/opt/openjdk, or lein's :java-cmd).")
                           {})))
         (py/initialize! :python-executable exe
                         :library-path (python-library-path exe))
         (reset! modules {:bpy       (py/import-module "bpy")
                          :bmesh     (py/import-module "bmesh")
                          :mathutils (py/import-module "mathutils")
                          :builtins  (py/import-module "builtins")})))))

(defn bpy       [] (:bpy (init!)))
(defn bmesh     [] (:bmesh (init!)))
(defn mathutils [] (:mathutils (init!)))
(defn- builtins [] (:builtins (init!)))

;; --------------------------------------------------------------------------
;; Segments ($fn analog)

(def ^:dynamic *segments*
  "Resolution for curved primitives (circle, sphere, cylinder, revolve).
  The $fn analog. Captured when a shape is constructed."
  48)

(defmacro with-segments
  "Evaluate body with *segments* bound to n, like $fn or scad-clj's
  with-smoothness. (with-segments 6 (circle r)) is a hexagon."
  [n & body]
  `(binding [*segments* ~n] ~@body))

;; --------------------------------------------------------------------------
;; Pure matrix math (row-major 4x4, applied as M·v)

(def ^:private mat-identity
  [[1.0 0.0 0.0 0.0] [0.0 1.0 0.0 0.0] [0.0 0.0 1.0 0.0] [0.0 0.0 0.0 1.0]])

(defn- mat-mul [a b]
  (vec (for [i (range 4)]
         (vec (for [j (range 4)]
                (reduce + (for [k (range 4)] (* (get-in a [i k]) (get-in b [k j])))))))))

(defn- mat-translate [[x y z]]
  [[1.0 0.0 0.0 (double x)] [0.0 1.0 0.0 (double y)] [0.0 0.0 1.0 (double z)] [0.0 0.0 0.0 1.0]])

(defn- mat-scale [[x y z]]
  [[(double x) 0.0 0.0 0.0] [0.0 (double y) 0.0 0.0] [0.0 0.0 (double z) 0.0] [0.0 0.0 0.0 1.0]])

(defn- mat-rotate-axis
  "Rotation of angle radians around unit axis (Rodrigues)."
  [angle [x y z]]
  (let [n (Math/sqrt (+ (* x x) (* y y) (* z z)))
        [x y z] [(/ x n) (/ y n) (/ z n)]
        c (Math/cos angle) s (Math/sin angle) t (- 1.0 c)]
    [[(+ c (* t x x))       (- (* t x y) (* s z)) (+ (* t x z) (* s y)) 0.0]
     [(+ (* t x y) (* s z)) (+ c (* t y y))       (- (* t y z) (* s x)) 0.0]
     [(- (* t x z) (* s y)) (+ (* t y z) (* s x)) (+ c (* t z z))       0.0]
     [0.0 0.0 0.0 1.0]]))

(defn- mat-rotate-euler
  "OpenSCAD rotate([x y z]): rotate around X, then Y, then Z. Radians."
  [[rx ry rz]]
  (mat-mul (mat-rotate-axis rz [0 0 1])
           (mat-mul (mat-rotate-axis ry [0 1 0])
                    (mat-rotate-axis rx [1 0 0]))))

(defn- mat-mirror
  "Reflection across the plane through the origin with normal n (I - 2nnᵀ)."
  [[x y z]]
  (let [l (Math/sqrt (+ (* x x) (* y y) (* z z)))
        [x y z] [(/ x l) (/ y l) (/ z l)]]
    [[(- 1.0 (* 2 x x)) (* -2 x y)        (* -2 x z)        0.0]
     [(* -2 x y)        (- 1.0 (* 2 y y)) (* -2 y z)        0.0]
     [(* -2 x z)        (* -2 y z)        (- 1.0 (* 2 z z)) 0.0]
     [0.0 0.0 0.0 1.0]]))

;; --------------------------------------------------------------------------
;; Pure geometry: lofts (no Blender involved)

(defn loft-mesh
  "Vertex/face data connecting a sequence of equal-length rings of [x y z]
  points with quads. wrap? connects the last ring back to the first (a full
  revolve); caps? closes both ends with flat ngons."
  [rings {:keys [wrap? caps?]}]
  (let [rings (vec rings)
        n     (count rings)
        m     (count (first rings))
        verts (vec (apply concat rings))
        pairs (concat (map vector (range (dec n)) (rest (range n)))
                      (when wrap? [[(dec n) 0]]))
        side-faces (for [[r r2] pairs
                         j (range m)]
                     (let [j2 (mod (inc j) m)]
                       [(+ (* r m) j)
                        (+ (* r m) j2)
                        (+ (* r2 m) j2)
                        (+ (* r2 m) j)]))
        cap-faces (when caps?
                    [(vec (reverse (range m)))
                     (vec (range (* (dec n) m) (* n m)))])]
    {:verts verts
     :faces (vec (concat cap-faces side-faces))}))

;; --------------------------------------------------------------------------
;; Blender scene helpers

(defn clean-scene!
  "Remove every object from the scene and purge orphaned data blocks."
  []
  (let [b (bpy)]
    (doseq [o (vec (py/py.. b -data -objects))]
      (py/py.. b -data -objects (remove o :do_unlink true)))
    (doseq [coll [(py/py.. b -data -meshes)
                  (py/py.. b -data -materials)]]
      (doseq [item (vec coll)]
        (when (zero? (py/py.- item users))
          (py/py.. coll (remove item)))))))

(defn- select-only! [obj]
  (let [b (bpy)]
    (py/py.. b -ops -object (select_all :action "DESELECT"))
    (py/py.. obj (select_set true))
    (py/set-attr! (py/py.. b -context -view_layer -objects) "active" obj)))

(defn- finalize-mesh!
  "Recalculate outward normals, optionally welding vertices closer than weld
  (needed after revolves that touch the axis)."
  [obj & {:keys [weld]}]
  (let [bm   (py/py.. (bmesh) (new))
        mesh (py/py.- obj data)]
    (py/py.. bm (from_mesh mesh))
    (when weld
      (py/py.. (bmesh) -ops (remove_doubles bm :verts (py/py.- bm verts) :dist weld)))
    (py/py.. (bmesh) -ops (recalc_face_normals bm :faces (py/py.- bm faces)))
    (py/py.. bm (to_mesh mesh))
    (py/py.. bm (free))))

(defn mesh-object!
  "Create a scene object named name from {:verts [[x y z] ...] :faces [[i ...] ...]}.

  The verts and faces are bulk-copied into native Python lists before
  from_pydata sees them. Handing it the Clojure vectors directly works too,
  but then Python iterates them element by element across the JVM bridge
  (hundreds of thousands of crossings for a few thousand vertices), which
  made this call ~16x slower and dominated mesh-heavy pipelines."
  [name {:keys [verts faces]} & {:keys [weld]}]
  (let [b    (bpy)
        mesh (py/py.. b -data -meshes (new name))
        obj  (py/py.. b -data -objects (new name mesh))]
    (py/py.. mesh (from_pydata (py/->python verts) [] (py/->python faces)))
    (py/py.. mesh (validate))
    (py/py.. mesh (update))
    (py/py.. b -context -scene -collection -objects (link obj))
    (finalize-mesh! obj :weld weld)
    obj))

(defn boolean-op!
  "Apply op (\"UNION\", \"DIFFERENCE\", \"INTERSECT\") of others onto target
  in place with the EXACT solver, deleting the consumed objects."
  [op target others]
  (let [b (bpy)]
    (doseq [other others]
      (let [mod (py/py.. target -modifiers (new "bool" "BOOLEAN"))]
        (py/set-attr! mod "operation" op)
        (py/set-attr! mod "object" other)
        (py/set-attr! mod "solver" "EXACT")
        (select-only! target)
        (py/py.. b -ops -object (modifier_apply :modifier (py/py.- mod name)))
        (py/py.. b -data -objects (remove other :do_unlink true))))
    target))

(defn mesh-stats
  "Vert/face counts plus how many edges are non-manifold (0 = watertight)."
  [obj]
  (let [bm (py/py.. (bmesh) (new))]
    (py/py.. bm (from_mesh (py/py.- obj data)))
    (let [edges (py/py.. (builtins) (list (py/py.- bm edges)))
          bad   (count (remove #(py/py.- % is_manifold) edges))]
      (py/py.. bm (free))
      {:verts        (py/py.. (py/py.- obj data) -vertices (__len__))
       :faces        (py/py.. (py/py.- obj data) -polygons (__len__))
       :non-manifold bad})))

(defn export-stl!
  "Export objs (one or a seq) to an STL at path."
  [objs path]
  (let [b    (bpy)
        objs (if (sequential? objs) objs [objs])
        path (.getAbsolutePath (io/file path))]
    (py/py.. b -ops -object (select_all :action "DESELECT"))
    (doseq [o objs] (py/py.. o (select_set true)))
    (py/py.. b -ops -wm (stl_export :filepath path :export_selected_objects true))
    path))

(defn export-glb!
  "Export the whole current scene as a binary glTF (.glb), which preserves
  the colors that STL drops."
  [path]
  (let [path (.getAbsolutePath (io/file path))]
    (py/py.. (bpy) -ops -export_scene (gltf :filepath path :export_format "GLB"))
    path))

(defn save-blend!
  "Save the current scene as a .blend file for inspection in the Blender GUI."
  [path]
  (let [path (.getAbsolutePath (io/file path))]
    (py/py.. (bpy) -ops -wm (save_as_mainfile :filepath path))
    path))

(defn- scene-bounds
  "World-space bounding box over all mesh objects: {:min [x y z] :max [x y z]}."
  []
  (let [b  (bpy)
        mu (mathutils)
        corners (for [o (vec (py/py.. b -data -objects))
                      :when (= "MESH" (py/py.- o type))
                      c (vec (py/py.- o bound_box))
                      :let [w (py/py.. (py/py.- o matrix_world)
                                       (__matmul__ (py/py.. mu (Vector (vec c)))))]]
                  [(py/py.- w x) (py/py.- w y) (py/py.- w z)])]
    (when (seq corners)
      {:min (apply mapv min corners)
       :max (apply mapv max corners)})))

(defn render-preview!
  "Render the current scene to a PNG with an auto-placed camera and sun light.
  With no center/size the shot frames all mesh objects automatically; pass
  them to control the look-at point and extent."
  [path & {:keys [center size resolution samples]
           :or   {resolution 800 samples 32}}]
  (let [b        (bpy)
        mu       (mathutils)
        {lo :min hi :max} (when-not (and center size) (scene-bounds))
        center   (or center (mapv #(/ (+ %1 %2) 2.0) lo hi))
        size     (or size (apply max (mapv - hi lo)))
        scene    (py/py.. b -context -scene)
        path     (.getAbsolutePath (io/file path))
        cam-data (py/py.. b -data -cameras (new "preview_cam"))
        cam      (py/py.. b -data -objects (new "preview_cam" cam-data))
        sun-data (py/py.. b -data -lights (new "preview_sun" "SUN"))
        sun      (py/py.. b -data -objects (new "preview_sun" sun-data))
        world    (py/py.. b -data -worlds (new "preview_world"))
        look-at  (py/py.. mu (Vector center))
        cam-pos  (py/py.. mu (Vector [(+ (first center) (* 1.7 size))
                                      (+ (second center) (* -1.7 size))
                                      (+ (last center) (* 1.1 size))]))
        cam-dir  (py/py.. look-at (__sub__ cam-pos))]
    (py/py.. b -context -scene -collection -objects (link cam))
    (py/py.. b -context -scene -collection -objects (link sun))
    (py/set-attr! cam "location" cam-pos)
    (py/set-attr! cam "rotation_euler" (py/py.. cam-dir (to_track_quat "-Z" "Y") (to_euler)))
    (py/set-attr! sun-data "energy" 3.0)
    (py/set-attr! sun "rotation_euler" [0.7 0.2 0.9])
    (py/set-attr! world "color" [0.85 0.85 0.85])
    (py/set-attr! scene "world" world)
    (py/set-attr! scene "camera" cam)
    (py/set-attr! (py/py.- scene render) "engine" "CYCLES")
    (py/set-attr! (py/py.- scene cycles) "samples" samples)
    (py/set-attr! (py/py.- scene render) "resolution_x" resolution)
    (py/set-attr! (py/py.- scene render) "resolution_y" resolution)
    (py/set-attr! (py/py.- scene render) "filepath" path)
    (py/py.. b -ops -render (render :write_still true))
    (doseq [o [cam sun]] (py/py.. b -data -objects (remove o :do_unlink true)))
    path))

;; --------------------------------------------------------------------------
;; The DSL: pure shape constructors (OpenSCAD vocabulary, scad-clj semantics)

(defn sphere
  "sphere(r). Centered at the origin."
  [r]
  {:type :sphere :r r :segments *segments*})

(defn cube
  "cube([w,d,h], center=true). (cube s) is a cube of side s.
  Centered by default like scad-clj; :center false puts a corner at the origin."
  ([s] (if (sequential? s) (apply cube s) (cube s s s)))
  ([x y z & {:keys [center] :or {center true}}]
   {:type :cube :size [x y z] :center center}))

(defn cylinder
  "cylinder(h, r) / cylinder(h, r1, r2). (cylinder r h) or (cylinder [r1 r2] h),
  as in scad-clj. Centered on z by default; :center false puts the base at z=0."
  [rs h & {:keys [center] :or {center true}}]
  (let [[r1 r2] (if (sequential? rs) rs [rs rs])]
    {:type :cylinder :r1 r1 :r2 r2 :h h :center center :segments *segments*}))

(defn polyhedron
  "polyhedron(points, faces). Arbitrary mesh from vertex and face index lists."
  [points faces]
  {:type :polyhedron :verts (mapv vec points) :faces (mapv vec faces)})

(defn import-model
  "import(\"file.stl\") — also reads .obj. The mesh lands as-is at its own
  coordinates; wrap in translate/rotate/scale as needed."
  [path]
  {:type :import :path (str path)})

;; 2D outlines (inputs for the extrudes)

(defn circle
  "circle(r) as a *segments*-sided outline. (with-segments 6 (circle r)) is a
  hexagon, like $fn=6."
  [r]
  {:type    :outline
   :points  (vec (for [i (range *segments*)]
                   (let [t (* i (/ (* 2.0 Math/PI) *segments*))]
                     [(* r (Math/cos t)) (* r (Math/sin t))])))})

(defn square
  "square([w,h], center=true). (square s) is a square of side s. Centered by
  default like scad-clj."
  ([s] (if (sequential? s) (apply square s) (square s s)))
  ([w h & {:keys [center] :or {center true}}]
   (let [[x0 y0] (if center [(/ w -2.0) (/ h -2.0)] [0.0 0.0])]
     {:type   :outline
      :points [[x0 y0] [(+ x0 w) y0] [(+ x0 w) (+ y0 h)] [x0 (+ y0 h)]]})))

(defn polygon
  "polygon(points): a closed outline from counterclockwise [x y] points.
  Holes (the paths argument) are not supported yet."
  [points]
  {:type :outline :points (mapv vec points)})

(defn- outline-points [shape]
  (cond
    (and (map? shape) (= :outline (:type shape))) (:points shape)
    (sequential? shape) (mapv vec shape)
    :else (throw (ex-info "Expected a 2D outline (circle/square/polygon) or points"
                          {:got shape}))))

(defn linear-extrude
  "linear_extrude(height, center, twist, slices) over a 2D outline.
  :twist in degrees over the full height, positive twisting clockwise like
  OpenSCAD. Options map first, like scad-clj's extrude-linear."
  [{:keys [height twist slices center] :or {twist 0 center false}} outline]
  {:type    :extrude
   :points  (outline-points outline)
   :height  height :twist twist :center center
   :slices  (or slices (if (zero? twist) 1 (max 8 (int (/ (Math/abs (double twist)) 10)))))})

(def extrude-linear "scad-clj alias for linear-extrude." linear-extrude)

(defn rotate-extrude
  "rotate_extrude(angle) of a 2D outline around the Z axis: outline x becomes
  radius, outline y becomes z. Outline x must be >= 0. :angle in degrees,
  default 360."
  [{:keys [angle segments] :or {angle 360}} outline]
  {:type     :revolve
   :points   (outline-points outline)
   :angle    angle
   :segments (or segments *segments*)})

(def extrude-rotate "scad-clj alias for rotate-extrude." rotate-extrude)

;; Transformations (each wraps its children; multiple children behave as a group)

(defn- collect-shapes
  "Flatten nested seqs so combinators accept shapes, seqs of shapes, or any
  mix: (union a [b c]) == (union a b c). Shape maps are left intact."
  [shapes]
  (vec (flatten shapes)))

(defn- transform-node [matrix shapes]
  {:type :transform :matrix matrix :children (collect-shapes shapes)})

(defn translate
  "translate([x y z])."
  [v & shapes]
  (transform-node (mat-translate v) shapes))

(defn rotate
  "rotate([rx ry rz]) in RADIANS (scad-clj convention, unlike raw OpenSCAD),
  applied X then Y then Z. Or (rotate angle [x y z]) around an axis."
  [a & args]
  (if (sequential? a)
    (transform-node (mat-rotate-euler a) args)
    (transform-node (mat-rotate-axis a (first args)) (rest args))))

(defn scale
  "scale([x y z]); a single number scales uniformly."
  [v & shapes]
  (transform-node (mat-scale (if (sequential? v) v [v v v])) shapes))

(defn mirror
  "mirror([x y z]): reflect across the plane through the origin with that normal."
  [n & shapes]
  (transform-node (mat-mirror n) shapes))

;; Booleans and modifiers

(defn union
  "union(). All combinators also accept seqs of shapes: (union a [b c])."
  [& shapes]
  {:type :union :children (collect-shapes shapes)})

(defn difference
  "difference(): the first shape minus all the rest."
  [& shapes]
  {:type :difference :children (collect-shapes shapes)})

(defn intersection
  "intersection()."
  [& shapes]
  {:type :intersection :children (collect-shapes shapes)})

(defn color
  "color([r g b a]): sets viewport and render color, like scad-clj's color.
  Visible in live-view! and render-preview!; STL export drops it."
  [rgba & shapes]
  {:type :color :rgba (vec rgba) :children (collect-shapes shapes)})

(defn disable
  "The * modifier: the wrapped shapes are ignored entirely."
  [& _shapes]
  {:type :disable})

(defn show-only
  "The ! modifier: when present anywhere in a tree passed to build!/preview!,
  only the first show-only subtree is built."
  [& shapes]
  {:type :show-only :children (collect-shapes shapes)})

(defn highlight
  "The # modifier, approximated: renders the shapes in red."
  [& shapes]
  (apply color [1.0 0.25 0.25 1.0] shapes))

;; --------------------------------------------------------------------------
;; The interpreter: shape data -> Blender objects

(defn- py-matrix [m] (py/py.. (mathutils) (Matrix m)))

(defn- bake!
  "Fold the accumulated transform (and any transform the object arrived with,
  e.g. from an import) into the mesh data, leaving the object at identity."
  [obj m]
  (let [mu    (mathutils)
        total (py/py.. (py-matrix m) (__matmul__ (py/py.- obj matrix_world)))]
    (py/py.. (py/py.- obj data) (transform total))
    (py/set-attr! obj "matrix_world" (py/py.. mu -Matrix (Identity 4)))
    (finalize-mesh! obj)
    obj))

(defn- active-object []
  (py/py.. (bpy) -context -view_layer -objects -active))

(defn- extrude-rings [{:keys [points height twist center slices]}]
  (let [z0 (if center (/ height -2.0) 0.0)]
    (for [k (range (inc slices))]
      (let [frac (/ (double k) slices)
            a    (Math/toRadians (* -1.0 twist frac))
            c    (Math/cos a) s (Math/sin a)]
        (mapv (fn [[x y]]
                [(- (* x c) (* y s)) (+ (* x s) (* y c)) (+ z0 (* frac height))])
              points)))))

(defn- revolve-rings [{:keys [points angle segments]}]
  (let [full? (>= angle 360)
        steps (if full? segments (max 2 (int (Math/ceil (* segments (/ angle 360.0))))))
        n     (if full? steps (inc steps))]
    {:full? full?
     :rings (for [k (range n)]
              (let [a (Math/toRadians (* angle (/ (double k) steps)))
                    c (Math/cos a) s (Math/sin a)]
                (mapv (fn [[x y]] [(* x c) (* x s) y]) points)))}))

(declare build-node)

(defn- build-children
  "Build each child under matrix m, dropping disabled/empty ones."
  [children m]
  (vec (keep #(build-node % m) children)))

(defn- build-group
  "Build children and union them into a single object (no-op for one child)."
  [children m]
  (let [objs (build-children children m)]
    (when (seq objs)
      (boolean-op! "UNION" (first objs) (rest objs)))))

(defn- build-node [node m]
  (case (:type node)
    :sphere    (let [{:keys [r segments]} node]
                 (py/py.. (bpy) -ops -mesh
                          (primitive_uv_sphere_add :radius r :segments segments
                                                   :ring_count (max 3 (quot segments 2))))
                 (bake! (active-object) m))

    :cube      (let [{:keys [size center]} node
                     offset (if center [0 0 0] (mapv #(/ % 2.0) size))]
                 (py/py.. (bpy) -ops -mesh (primitive_cube_add :size 1.0))
                 (bake! (active-object)
                        (mat-mul m (mat-mul (mat-translate offset) (mat-scale size)))))

    :cylinder  (let [{:keys [r1 r2 h center segments]} node
                     offset (if center [0 0 0] [0 0 (/ h 2.0)])]
                 (if (= (double r1) (double r2))
                   (py/py.. (bpy) -ops -mesh
                            (primitive_cylinder_add :radius r1 :depth h :vertices segments))
                   (py/py.. (bpy) -ops -mesh
                            (primitive_cone_add :radius1 r1 :radius2 r2 :depth h
                                                :vertices segments)))
                 (bake! (active-object) (mat-mul m (mat-translate offset))))

    :polyhedron (bake! (mesh-object! "polyhedron" node) m)

    :extrude   (bake! (mesh-object! "extrude" (loft-mesh (extrude-rings node) {:caps? true})) m)

    :revolve   (let [{:keys [full? rings]} (revolve-rings node)]
                 (bake! (mesh-object! "revolve"
                                      (loft-mesh rings {:wrap? full? :caps? (not full?)})
                                      :weld 1e-5)
                        m))

    :import    (let [b    (bpy)
                     ext  (str/lower-case (or (re-find #"\.[^.]+$" (:path node)) ""))
                     path (.getAbsolutePath (io/file (:path node)))
                     before (set (map #(py/py.- % name) (vec (py/py.. b -data -objects))))]
                 (case ext
                   ".stl" (py/py.. b -ops -wm (stl_import :filepath path))
                   ".obj" (py/py.. b -ops -wm (obj_import :filepath path))
                   (throw (ex-info "import-model supports .stl and .obj" {:path path})))
                 (let [objs (mapv #(bake! % m)
                                  (remove #(before (py/py.- % name))
                                          (vec (py/py.. b -data -objects))))]
                   (when (seq objs)
                     (boolean-op! "UNION" (first objs) (rest objs)))))

    :transform (build-group (:children node) (mat-mul m (:matrix node)))

    :union     (build-group (:children node) m)

    :difference (let [objs (build-children (:children node) m)]
                  (when (seq objs)
                    (boolean-op! "DIFFERENCE" (first objs) (rest objs))))

    :intersection (let [objs (build-children (:children node) m)]
                    (when (seq objs)
                      (boolean-op! "INTERSECT" (first objs) (rest objs))))

    :color     (when-let [obj (build-group (:children node) m)]
                 (let [mat (py/py.. (bpy) -data -materials (new "color"))]
                   (py/set-attr! mat "diffuse_color" (:rgba node))
                   (py/py.. (py/py.- (py/py.- obj data) materials) (append mat))
                   (py/set-attr! obj "color" (:rgba node)))
                 obj)

    :disable   nil

    :show-only (build-group (:children node) m)

    :outline   (throw (ex-info "2D outlines must go through linear-extrude or rotate-extrude"
                               {:node (:type node)}))))

(defn- find-show-only [node]
  (when (map? node)
    (if (= :show-only (:type node))
      node
      (some find-show-only (:children node)))))

(defn build!
  "Realize a shape tree (or a seq of trees) in the Blender scene; returns the
  resulting object(s). Honors show-only. Does not clear the scene first."
  [shape]
  (init!)
  (if (sequential? shape)
    (mapv build! shape)
    (build-node (or (find-show-only shape) shape) mat-identity)))

(defn preview!
  "The quick REPL feedback loop: clear the scene, build shape, export an STL,
  and open it in the OS viewer (Preview.app on macOS has a rotatable 3D
  view). Accepts a single shape or a seq of shapes. For a persistent Blender
  viewport that keeps your orbit between evals, use live-view! instead. STL
  drops colors; live-view! keeps them."
  ([shape] (preview! shape {}))
  ([shape {:keys [path open?] :or {path "preview.stl" open? true}}]
   (clean-scene!)
   (let [objs (build! shape)
         out  (export-stl! (remove nil? (flatten [objs])) path)]
     (when open?
       (sh "open" "-g" out))
     out)))

;; --------------------------------------------------------------------------
;; Live view: a Blender GUI running the bundled listener

(def ^:dynamic *live-view-port*
  (or (some-> (System/getenv "SUZANNE_LIVE_PORT") Long/parseLong) 4777))

(defn start-live-viewer!
  "Launch the Blender GUI with the live view listener active. Returns the
  Process. The BLENDER env var overrides the Blender executable path."
  []
  (let [addon  (or (io/resource "suzanne/live_addon.py")
                   (throw (ex-info "suzanne/live_addon.py not on classpath" {})))
        tmp    (io/file (System/getProperty "java.io.tmpdir") "suzanne_live_addon.py")
        _      (spit tmp (slurp addon))
        blender (or (System/getenv "BLENDER")
                    "/Applications/Blender.app/Contents/MacOS/Blender")]
    (-> (ProcessBuilder. [blender "--python" (str tmp)])
        (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
        (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)
        (.start))))

(defn- live-send! [line]
  (with-open [sock (java.net.Socket. "127.0.0.1" (int *live-view-port*))
              out  (io/writer sock)
              in   (io/reader sock)]
    (.write out (str line "\n"))
    (.flush out)
    (.readLine ^java.io.BufferedReader in)))

(defonce ^:private live-framed? (atom false))

(defn live-view!
  "The full live preview: build shape in the embedded Blender, export a GLB,
  and push it into the Blender GUI started by (start-live-viewer!). The GUI
  viewport keeps your orbit between evals (it zooms to fit only on the first
  load, or when :frame? is true). Returns the listener's reply."
  ([shape] (live-view! shape {}))
  ([shape {:keys [frame?]}]
   (clean-scene!)
   (build! shape)
   (let [path  (.getAbsolutePath (io/file (System/getProperty "java.io.tmpdir")
                                          "suzanne-live.glb"))
         _     (export-glb! path)
         frame (boolean (or frame? (not @live-framed?)))
         reply (try
                 (live-send! (format "{\"cmd\":\"load\",\"path\":%s,\"frame\":%s}"
                                     (pr-str path) frame))
                 (catch java.net.ConnectException _
                   (throw (ex-info (str "Live viewer is not running. Start it with "
                                        "(start-live-viewer!) then re-eval.")
                                   {:port *live-view-port*}))))]
     (reset! live-framed? true)
     reply)))

(comment
  ;; the loop: compose data, preview!, repeat.
  ;; preview! opens an STL in the OS viewer (rotatable, but colorless);
  ;; live-view! is fully live in a real Blender viewport.
  (preview!
    (difference
      (cube 30)
      (sphere 19)
      (cylinder 8 40)))

  (start-live-viewer!)
  (live-view!
    (color [0.85 0.3 0.25 1.0]
      (difference (cube 30) (sphere 19) (cylinder 8 40))))

  ;; hexagon with a twist ($fn analog picks the side count)
  (live-view!
    (linear-extrude {:height 40 :twist 90}
                    (with-segments 6 (circle 15))))

  ;; donut: revolve a circle offset from the axis
  (live-view!
    (rotate-extrude {} (polygon (map (fn [[x y]] [(+ x 20) y])
                                     (:points (circle 6))))))

  ;; export for printing
  (let [obj (build! (difference (cube 30) (sphere 19)))]
    (export-stl! obj "demo.stl")))
