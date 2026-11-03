#!/usr/bin/env bb
;; icon.bb: regenerates the app icon and the menu bar glyph from the SVGs in
;; Icon/ (see Icon/README.md). Splits the chosen concept into the Icon
;; Composer document AgentMail/AppIcon.icon: the background's fill becomes
;; the icon's fill and each top-level <g id> one group, front to back. Then
;; renders Icon/menubar.svg into the MenuBarIcon template image set. Run with
;; `just icon` in mac/.
;;   bb scripts/icon.bb                        the concept named below
;;   bb scripts/icon.bb --concept two-agents   another one, for this run
;;   bb scripts/icon.bb --previews DIR         also previews of every concept,
;;                                             rendered by Icon Composer's ictool
(ns icon
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.data.xml :as xml]
            [clojure.string :as str]))

;; The concept the app ships: a file in Icon/concepts/, without ".svg".
(def concept "envelope-prompt")

(def concepts-dir "Icon/concepts")
(def menubar-svg "Icon/menubar.svg")
(def icon-doc "AgentMail/AppIcon.icon")
(def catalog "AgentMail/Assets.xcassets")
(def image-set (str catalog "/MenuBarIcon.imageset"))
(def renderer-src "scripts/render_image.swift")
;; Icon Composer allows four groups.
(def max-groups 4)

(defn fail [& msg]
  (binding [*out* *err*] (println (str/join " " msg)))
  (System/exit 1))

(defn sh! [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} (map str args))]
    (when-not (zero? (:exit r))
      (fail "failed:" (str/join " " args) "\n" (str/trim (str (:out r) (:err r)))))
    (:out r)))

(defn write-json! [file data]
  (spit (str file) (str (json/generate-string data {:pretty true}) "\n")))

;; SVG in and out. data.xml's emitter would rename the default xmlns, so the
;; layers are written by hand; comments are dropped on parsing.

(defn parse-svg [file]
  (xml/parse-str (slurp (str file)) :namespace-aware false :skip-whitespace true))

(defn elements [node] (filter #(and (map? %) (keyword? (:tag %))) (:content node)))

(defn escape [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn emit [node]
  (if (map? node)
    (let [tag (name (:tag node))
          attrs (apply str (for [[k v] (:attrs node)] (str " " (name k) "=\"" (escape v) "\"")))
          kids (remove #(and (string? %) (str/blank? %)) (:content node))]
      (if (seq kids)
        (str "<" tag attrs ">" (apply str (map emit kids)) "</" tag ">")
        (str "<" tag attrs "/>")))
    (escape node)))

;; Colors and the background fill, in icon.json's notation.

(defn srgb [hex]
  (let [h (str/replace (str hex) #"^#" "")]
    (when-not (re-matches #"[0-9a-fA-F]{6}" h)
      (fail "colors must be #RRGGBB, not" (pr-str hex)))
    (let [n (Long/parseLong h 16)
          c #(format "%.5f" (/ (bit-and (bit-shift-right n %) 0xff) 255.0))]
      (str "srgb:" (c 16) "," (c 8) "," (c 0) ",1.00000"))))

(defn fraction [s default]
  (cond (nil? s) default
        (str/ends-with? s "%") (/ (parse-double (subs s 0 (dec (count s)))) 100)
        :else (parse-double s)))

(defn by-id [svg id]
  (first (filter #(= id (get-in % [:attrs :id])) (tree-seq map? elements svg))))

(defn background-fill
  "The fill of the element with id \"background\": a color, or a two-stop
   linear gradient across the canvas."
  [svg file]
  (let [fill (get-in (by-id svg "background") [:attrs :fill])
        gradient-id (second (re-matches #"url\(#(.+)\)" (str fill)))]
    (cond
      (nil? fill) (fail file "has no element with id \"background\" and a fill")
      (nil? gradient-id) {"solid" (srgb fill)}
      :else
      (let [g (by-id svg gradient-id)
            stops (filter #(= :stop (:tag %)) (elements g))
            a (:attrs g)]
        (when-not (and (= :linearGradient (:tag g)) (= 2 (count stops)))
          (fail file "- the background needs a linearGradient with two stops"))
        {"linear-gradient" (mapv #(srgb (get-in % [:attrs :stop-color])) stops)
         "orientation" {"start" {"x" (fraction (:x1 a) 0) "y" (fraction (:y1 a) 0)}
                        "stop" {"x" (fraction (:x2 a) 1) "y" (fraction (:y2 a) 0)}}}))))

;; Layers: one group of one layer per top-level <g id>, back to front in the
;; SVG. data-glass="false", data-translucency="0.2" and opacity="0.6" on the
;; <g> set what Icon Composer would; they're removed from the layer's SVG.

(defn layers [svg file]
  (let [gs (filter #(and (= :g (:tag %)) (get-in % [:attrs :id])) (elements svg))]
    (when (empty? gs) (fail file "has no top-level <g id=\"...\"> layers"))
    (when (> (count gs) max-groups) (fail file "has" (count gs) "layers; Icon Composer allows" max-groups))
    (for [g gs]
      (let [{:keys [id opacity data-glass data-translucency]} (:attrs g)
            root (:attrs svg)]
        {:id id
         :svg (emit {:tag :svg
                     :attrs (select-keys root [:xmlns :width :height :viewBox])
                     :content [(update g :attrs dissoc :opacity :data-glass :data-translucency)]})
         :glass (not= "false" data-glass)
         :opacity (some-> opacity parse-double)
         :translucency (some-> data-translucency parse-double)}))))

(defn group-json [{:keys [id glass opacity translucency]}]
  {"layers" [(cond-> {"glass" glass "image-name" (str id ".svg") "name" id}
               opacity (assoc "opacity" opacity))]
   "shadow" {"kind" "neutral" "opacity" 0.5}
   "translucency" (if (some? translucency)
                    {"enabled" (pos? translucency) "value" (if (pos? translucency) translucency 0.5)}
                    {"enabled" true "value" 0.5})})

(defn write-icon!
  "Writes the Icon Composer document for one concept SVG into dir, replacing it."
  [svg-file dir]
  (let [svg (parse-svg svg-file)
        ls (layers svg svg-file)
        assets (fs/path dir "Assets")]
    (fs/delete-tree dir)
    (fs/create-dirs assets)
    (doseq [{:keys [id svg]} ls]
      (spit (str (fs/path assets (str id ".svg"))) (str svg "\n")))
    (write-json! (fs/path dir "icon.json")
                 {"fill" (background-fill svg svg-file)
                  "groups" (mapv group-json (reverse ls))
                  "supported-platforms" {"squares" ["macOS"]}})))

;; Rasters, drawn by a small AppKit program compiled once per run.

(defn compile-renderer! [tmp]
  (let [exe (fs/path tmp "render_image")]
    (sh! "xcrun" "swiftc" "-O" "-o" exe renderer-src)
    (str exe)))

(defn render! [renderer in out w h & opts]
  (apply sh! renderer in out w h opts))

(defn write-menubar! [renderer]
  (fs/create-dirs image-set)
  (when-not (fs/exists? (fs/path catalog "Contents.json"))
    (write-json! (fs/path catalog "Contents.json") {"info" {"author" "xcode" "version" 1}}))
  (render! renderer menubar-svg (fs/path image-set "menubar.png") 18 18 "--scale" 1)
  (render! renderer menubar-svg (fs/path image-set "menubar@2x.png") 36 36 "--scale" 2)
  (write-json! (fs/path image-set "Contents.json")
               {"images" [{"filename" "menubar.png" "idiom" "universal" "scale" "1x"}
                          {"filename" "menubar@2x.png" "idiom" "universal" "scale" "2x"}]
                "info" {"author" "xcode" "version" 1}
                "properties" {"template-rendering-intent" "template"}}))

(defn ictool []
  (let [dev (str/trim (sh! "xcode-select" "-p"))
        exe (fn [app] (fs/path app "Contents" "Executables" "ictool"))]
    (or (first (filter fs/executable? [(exe (fs/path dev ".." "Applications" "Icon Composer.app"))
                                       (exe "/Applications/Icon Composer.app")]))
        (fail "no ictool: previews need Icon Composer, which comes with Xcode 26 or later"))))

(defn write-previews!
  "Each concept as macOS draws it (mask, Liquid Glass) on a neutral
   background, 512 px, and the menu bar glyph at 2x and 8x."
  [renderer tmp out-dir]
  (let [ic (ictool)]
    (fs/create-dirs out-dir)
    (doseq [svg (sort (map str (fs/glob concepts-dir "*.svg")))]
      (let [name (fs/strip-ext (fs/file-name svg))
            doc (fs/path tmp (str name ".icon"))
            raw (fs/path tmp (str name ".png"))
            out (fs/path out-dir (str name ".png"))]
        (write-icon! svg doc)
        (sh! ic doc "--export-image" "--output-file" raw "--platform" "macOS" "--rendition" "Default"
             "--width" 432 "--height" 432 "--scale" 1)
        (render! renderer raw out 512 512 "--inset" 40 "--background" "e8e8ed")
        (println "preview" (str out))))
    (doseq [[file px scale] [["menubar@2x.png" 36 2] ["menubar@8x.png" 144 8]]]
      (render! renderer menubar-svg (fs/path out-dir file) px px "--scale" scale "--background" "f2f2f2")
      (println "preview" (str (fs/path out-dir file))))))

(defn -main [args]
  (let [opt (fn [k] (second (drop-while #(not= k %) args)))
        chosen (or (opt "--concept") concept)
        source (fs/path concepts-dir (str chosen ".svg"))
        previews (opt "--previews")]
    (when-not (fs/exists? source) (fail "no concept" (str source)))
    (fs/with-temp-dir [tmp {:prefix "agent-mail-icon-"}]
      (write-icon! source icon-doc)
      (println "wrote" icon-doc "from" (str source))
      (let [renderer (compile-renderer! tmp)]
        (write-menubar! renderer)
        (println "wrote" image-set)
        (when previews (write-previews! renderer tmp previews))))))

(-main *command-line-args*)
