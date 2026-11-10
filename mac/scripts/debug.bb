#!/usr/bin/env bb
;; debug.bb: drives a Debug build of the app that was started with
;; AGENT_MAIL_DEBUG_HOOKS=1 (see AgentMail/App/DebugHooks.swift), for coding
;; agents that can't see the screen. Posts the distributed notification
;; through JXA, then prints what the app wrote back.
;;   bb scripts/debug.bb dump
;;   bb scripts/debug.bb select 2026-10-31-deploy-the-new-site
;;   bb scripts/debug.bb key r --mods cmd      (also: tab, return, space, esc)
;;   bb scripts/debug.bb snapshot /tmp/window.png [--window General]
;;   bb scripts/debug.bb sidebarsnapshot /tmp/sidebar.png [--window 240]  (the
;;     sidebar drawn offscreen, that wide: a window snapshot leaves it out)
;;   bb scripts/debug.bb js "document.title"      (in the thread's web view)
;;   bb scripts/debug.bb clickweb "#m1 a.button"  (a real click on an element)
;;   bb scripts/debug.bb recordopens              (log opens, launch nothing)
;;   bb scripts/debug.bb appearance dark          (also: light, system)
;;   bb scripts/debug.bb filter sent              (the participant filter)
;;   bb scripts/debug.bb compose                  (New Message: the draft)
;;   bb scripts/debug.bb forward ID[/FILE]        (Forward; discard drops a draft)
;;   bb scripts/debug.bb attach "/a.png|/b.txt"   (files for the message being written)
;;   bb scripts/debug.bb action paste:            (pastes the clipboard: files and images attach)
;;   bb scripts/debug.bb sidebarmenu agent:infra  (a sidebar row's context menu)
;;   bb scripts/debug.bb sidebarpick "agent:infra|On Call"  (picks one of its items)
;;   bb scripts/debug.bb addfolder /path/to/folder (Add Folder… once the panel returns)
(ns debug
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn post! [cmd]
  (let [js (str "ObjC.import('Foundation');"
                "$.NSDistributedNotificationCenter.defaultCenter"
                ".postNotificationNameObjectUserInfoDeliverImmediately("
                "'dev.agent-mail.debug', " (json/generate-string (json/generate-string cmd)) ", $(), true);")]
    (p/shell {:out :string :err :string} "osascript" "-l" "JavaScript" "-e" js)))

(defn wait-for [f ms]
  (loop [n 0]
    (cond (fs/exists? f) (slurp (str f))
          (> (* n 50) ms) nil
          :else (do (Thread/sleep 50) (recur (inc n))))))

(defn -main [[cmd arg & more]]
  (when-not cmd
    (println "usage: debug.bb CMD [ARG] [--mods cmd,shift] [--window TITLE]")
    (System/exit 2))
  (let [opt (fn [k] (second (drop-while #(not= k %) more)))
        mods (opt "--mods")
        window (opt "--window")
        snapshot? (contains? #{"snapshot" "sidebarsnapshot"} cmd)
        out (if snapshot?
              (str (fs/absolutize (or arg "window.png")))
              (str (fs/create-temp-file {:prefix "agent-mail-debug-" :suffix ".txt"})))
        _ (fs/delete-if-exists out)
        arg (if (= cmd "key") (get {"tab" "\t" "return" "\r" "space" " " "esc" "\u001b"} arg arg) arg)
        msg (cond-> {"cmd" cmd "out" out}
              (and arg (not snapshot?)) (assoc "arg" arg)
              mods (assoc "mods" mods)
              window (assoc "window" window))]
    (post! msg)
    (if-let [result (wait-for out 5000)]
      (println (if snapshot? out (str/trimr result)))
      (do (println "no answer: is a Debug build running with AGENT_MAIL_DEBUG_HOOKS=1?")
          (System/exit 1)))))

(-main *command-line-args*)
