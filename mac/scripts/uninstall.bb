#!/usr/bin/env bb
;; uninstall.bb: quits ~/Applications/Agent Mail.app, unregisters it from
;; LaunchServices and deletes it. `--purge` also removes its settings and
;; Application Support folder. Run with `just uninstall` in mac/. The store
;; and the CLI are never touched.
(ns uninstall
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def bundle-id "dev.agent-mail.AgentMail")
(def dest (fs/path (fs/home) "Applications" "Agent Mail.app"))
(def lsregister "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister")

(defn sh [& args] (apply p/shell {:out :string :err :string :continue true} (map str args)))

(defn -main [args]
  (let [purge? (some #{"--purge"} args)
        exe (str (fs/path dest "Contents" "MacOS" "Agent Mail"))]
    (when (zero? (:exit (sh "pgrep" "-f" exe)))
      (println "quitting Agent Mail…")
      (sh "pkill" "-TERM" "-f" exe)
      (loop [n 0]
        (when (and (< n 50) (zero? (:exit (sh "pgrep" "-f" exe))))
          (Thread/sleep 100)
          (recur (inc n)))))
    (if (fs/exists? dest)
      (do (sh lsregister "-u" dest)
          (fs/delete-tree dest)
          (println "removed" (str dest)))
      (println "nothing at" (str dest)))
    (when purge?
      (sh "defaults" "delete" bundle-id)
      (doseq [d [(fs/path (fs/home) "Library" "Application Support" bundle-id)
                 (fs/path (fs/home) "Library" "Caches" bundle-id)
                 (fs/path (fs/home) "Library" "HTTPStorages" bundle-id)
                 (fs/path (fs/home) "Library" "Saved Application State" (str bundle-id ".savedState"))]
              :when (fs/exists? d)]
        (fs/delete-tree d)
        (println "removed" (str d))))
    (println (str/join "\n" ["If “Open Agent Mail when you log in” was on, remove the stale entry in"
                             "System Settings › General › Login Items; notification settings stay in"
                             "System Settings › Notifications until you remove them there."]))))

(-main *command-line-args*)
