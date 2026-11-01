#!/usr/bin/env bb
;; install.bb: builds the Release app unsigned, signs it inside-out (no
;; --deep) with the "Agent Mail Local" identity (`just signing-cert`),
;; verifies the signature, replaces ~/Applications/Agent Mail.app (quitting a
;; running copy), registers it with LaunchServices and launches it. Run with
;; `just install` in mac/. AGENT_MAIL_SIGN_IDENTITY picks another identity
;; ("-" for ad hoc). `--dry-run` stops after signing and verifying the copy in
;; build/, touching nothing outside the repo.
(ns install
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def bundle-id "dev.agent-mail.AgentMail")
(def app-name "Agent Mail.app")
(def sign-identity (or (System/getenv "AGENT_MAIL_SIGN_IDENTITY") "Agent Mail Local"))
(def derived "build/DerivedData-Release")
(def built (fs/path derived "Build" "Products" "Release" app-name))
(def dest-dir (fs/path (fs/home) "Applications"))
(def dest (fs/path dest-dir app-name))
(def lsregister "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister")

(defn fail [& msg]
  (binding [*out* *err*] (println (str/join " " msg)))
  (System/exit 1))

(defn sh! [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} (map str args))]
    (when-not (zero? (:exit r))
      (fail "failed:" (str/join " " args) "\n" (str/trim (str (:out r) (:err r)))))
    (:out r)))

(defn check-identity! []
  (when-not (= "-" sign-identity)
    (let [ids (:out (p/shell {:out :string :continue true} "security" "find-identity" "-v" "-p" "codesigning"))]
      (when-not (str/includes? ids (str "\"" sign-identity "\""))
        (fail (str "no code-signing identity \"" sign-identity "\"; run `just signing-cert` first"))))))

(defn build! []
  (println "building Release…")
  (let [xb ["xcodebuild" "-project" "AgentMail.xcodeproj" "-scheme" "AgentMail" "-configuration" "Release"
            "-destination" "platform=macOS" "-derivedDataPath" derived "CODE_SIGNING_ALLOWED=NO" "build"]
        procs (if (fs/which "xcbeautify")
                (p/pipeline (p/pb xb {:err :out}) (p/pb ["xcbeautify" "--quiet"] {:out :inherit :err :inherit}))
                [(p/process xb {:out :inherit :err :inherit})])]
    (mapv deref procs)
    (when-not (zero? (:exit @(first procs))) (fail "xcodebuild failed"))
    (when-not (fs/exists? built) (fail "no app at" (str built)))))

(defn nested-code
  "Code inside the bundle, deepest first, so each is signed before whatever
   contains it."
  [app]
  (let [contents (fs/path app "Contents")
        bundles (filter #(re-find #"\.(framework|dylib|appex|xpc|app|bundle)$" (str %))
                        (fs/glob contents "**"))
        main (fs/path contents "MacOS" "Agent Mail")
        helpers (when (fs/exists? (fs/path contents "MacOS"))
                  (remove #(= (str %) (str main)) (fs/list-dir (fs/path contents "MacOS"))))]
    (->> (concat bundles helpers)
         (remove #(str/includes? (str %) "/Versions/Current"))
         distinct
         (sort-by #(- (count (fs/components %)))))))

(defn sign! [app]
  (doseq [code (nested-code app)]
    (println "signing" (str (fs/relativize app code)))
    (sh! "codesign" "--force" "--options" "runtime" "--timestamp=none" "--sign" sign-identity code))
  (println "signing" app-name "with" (pr-str sign-identity))
  (sh! "codesign" "--force" "--options" "runtime" "--timestamp=none" "--sign" sign-identity app)
  (sh! "codesign" "--verify" "--strict" "--verbose=2" app)
  (let [r (p/shell {:out :string :err :string :continue true} "codesign" "-d" "-r-" (str app))]
    (println (str/trim (str (:out r) (:err r))))))

(defn quit-running! []
  (let [exe (str (fs/path dest "Contents" "MacOS" "Agent Mail"))]
    (when (zero? (:exit (p/shell {:out :string :continue true} "pgrep" "-f" exe)))
      (println "quitting the running copy…")
      ;; The app quits normally on SIGTERM.
      (p/shell {:continue true} "pkill" "-TERM" "-f" exe)
      (loop [n 0]
        (when (and (< n 50) (zero? (:exit (p/shell {:out :string :continue true} "pgrep" "-f" exe))))
          (Thread/sleep 100)
          (recur (inc n)))))))

(defn install! []
  (fs/create-dirs dest-dir)
  (let [staging (fs/path dest-dir (str "." app-name ".installing"))
        old (fs/path dest-dir (str "." app-name ".old"))]
    (fs/delete-tree staging)
    (fs/delete-tree old)
    (sh! "ditto" built staging)
    (quit-running!)
    (when (fs/exists? dest) (fs/move dest old))
    (fs/move staging dest)
    (fs/delete-tree old)
    (println "installed" (str dest))
    ;; The build product would otherwise be a second registered copy.
    (p/shell {:continue true :out :string :err :string} lsregister "-u" (str (fs/absolutize built)))
    (sh! lsregister "-f" dest)
    (sh! "open" dest)
    (println "launched" bundle-id)))

(defn -main [args]
  (let [dry? (some #{"--dry-run"} args)]
    (check-identity!)
    (build!)
    (sign! built)
    (if dry?
      (println "dry run: signed and verified" (str built) "; nothing installed")
      (install!))))

(-main *command-line-args*)
