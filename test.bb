#!/usr/bin/env bb
;; Tests for agent-mail, run against a temporary store: `bb test.bb` or `just test`.
;; Each test calls the launcher (bin/agent-mail) the way agents and the user do.
(ns test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

(def launcher (str (fs/absolutize "bin/agent-mail")))
(def ^:dynamic *store* nil)

(defn am
  "Runs agent-mail as `who` (nil = detect from cwd) in `dir`, returning {:exit :out :err}."
  [{:keys [who dir in env]} & args]
  (apply p/shell {:out :string :err :string :continue true :in (or in "")
                  :dir (or dir (str *store*))
                  :extra-env (cond-> (merge {"AGENT_MAIL_DIR" (str *store*) "AGENT_MAIL_NO_NOTIFY" "1"} env)
                               who (assoc "AGENT_MAIL_AS" who))}
         launcher args))

(defn out [& args] (str/trim (:out (apply am args))))
(defn ids
  "Thread ids in `who`'s inbox (the column after the #number)."
  [who]
  (->> (str/split-lines (out {:who who} "list")) (remove str/blank?) (map #(second (str/split % #"\s+")))))

(defmacro with-store [& body]
  `(fs/with-temp-dir [d# {}]
     (binding [*store* (fs/path d# "store")]
       (fs/create-dirs *store*)
       ~@body)))

(deftest send-list-reply-close
  (with-store
    (let [id (out {:who "website"} "send" "--to" "infra" "--subject" "Vault" "Please add a vault.")]
      (testing "send prints the id and the receiver sees it"
        (is (= "2026" (subs id 0 4)))
        (is (str/ends-with? id "-vault"))
        (is (= [id] (ids "infra")))
        (is (empty? (ids "website"))))
      (testing "threads are numbered and the number is a handle"
        (is (str/starts-with? (out {:who "infra"} "list") "#1 "))
        (is (str/ends-with? (out {:who "x"} "@y" "second thread") "-second-thread"))
        (is (str/includes? (out {:who "y"} "list") "#2 "))
        (is (str/includes? (out {} "read" "2") "second thread"))
        (is (str/includes? (out {} "read" "#1") "# Vault")))
      (testing "a reply hands the turn back"
        (is (= id (out {:who "infra"} "reply" "1" "Done on my side.")))
        (is (= [id] (ids "website")))
        (is (empty? (ids "infra"))))
      (testing "read renders the whole thread in order"
        (let [r (out {} "read" "vault")]
          (is (str/includes? r "# Vault"))
          (is (< (str/index-of r "Please add a vault.") (str/index-of r "Done on my side.")))
          (is (str/includes? r "owner: website"))))
      (testing "close marks it done and it leaves every list"
        (is (= id (out {:who "website"} "close" id)))
        (is (empty? (ids "infra")))
        (is (str/includes? (out {} "list" "--all") "done"))))))

(deftest same-second-messages-keep-their-order
  (with-store
    (let [id (out {:who "a"} "@b" "quick one")]
      (out {:who "b"} "reply" id "first reply")
      (out {:who "a"} "reply" id "second")
      (is (= [id] (ids "b")) "the last writer was a, so the turn is b's"))))

(deftest at-shorthand-and-attachments
  (with-store
    (fs/with-temp-dir [d {}]
      (let [f (str (fs/path d "shot.txt"))
            _ (spit f "pixels")
            id (out {:who "user"} "@website" "--attach" f "Look at ![this](files/shot.txt) please")]
        (is (str/ends-with? id "-look-at-this-files-shot-txt-please"))
        (is (= "pixels" (slurp (str (fs/path *store* "threads" id "files" "shot.txt")))))
        (is (str/includes? (out {} "read" id) (str "](" (fs/path *store* "threads" id "files" "shot.txt") ")")))))))

(deftest wait-and-duty
  (with-store
    (testing "wait returns at once when mail is already waiting"
      (let [id (out {:who "a"} "@b" "hi")
            r (am {:who "b"} "wait" "--session" "b-1" "--timeout" "5")]
        (is (zero? (:exit r)))
        (is (= id (str/trim (:out r))))))
    (testing "wait times out silently with its own exit code"
      (let [r (am {:who "c"} "wait" "--session" "c-1" "--timeout" "1" "--interval" "1")]
        (is (= 3 (:exit r)))
        (is (str/blank? (:out r)))))
    (testing "who lists both"
      (let [w (out {} "who")]
        (is (str/includes? w "b-1"))
        (is (str/includes? w "c-1"))))))

(deftest caller-identity
  (with-store
    (fs/with-temp-dir [d {}]
      (let [repo (str (fs/path d "repo-x")) wt (str (fs/path d "repo-x-wt"))]
        (p/shell {:out :string} "git" "init" "-q" repo)
        (p/shell {:out :string :dir repo} "git" "commit" "-q" "--allow-empty" "-m" "i")
        (p/shell {:out :string :dir repo} "git" "worktree" "add" "-q" wt "-b" "wt")
        (is (= "repo-x" (out {:dir repo} "whoami")))
        (is (= "repo-x" (out {:dir wt} "whoami")) "a worktree counts as its repo")
        (is (= "user" (out {:dir (str d)} "whoami")) "outside any repo the caller is the user")
        (is (= "other" (out {:dir repo} "whoami" "--as" "other")))))))

(deftest hook-and-user-mail
  (with-store
    (is (str/blank? (out {:who "nobody"} "hook")) "silent when nothing waits")
    (let [id (out {:who "infra"} "send" "--to" "user" "--subject" "Need your OK" "Details.")]
      (is (= [id] (ids "user")))
      (let [h (out {:who "website"} "hook")]
        (is (str/includes? h "waiting on you (the user)"))
        (is (str/includes? h id))))))

(deftest reply-from-the-editor
  (with-store
    (fs/with-temp-dir [d {}]
      (let [typing (str (fs/path d "editor.sh"))
            silent (str (fs/path d "viewer.sh"))
            id (out {:who "infra"} "@user" "Need your OK")]
        (spit typing "#!/bin/sh\nprintf 'Approved, go ahead.\\n' >> \"$1\"\n")
        (spit silent "#!/bin/sh\nexit 0\n")
        (fs/set-posix-file-permissions typing "rwxr-xr-x")
        (fs/set-posix-file-permissions silent "rwxr-xr-x")
        (testing "an untouched draft sends nothing"
          (let [r (am {:who "user" :env {"AGENT_MAIL_EDITOR" silent}} "reply" id "--edit")]
            (is (zero? (:exit r)))
            (is (str/includes? (:err r) "nothing sent"))
            (is (= [id] (ids "user")))))
        (testing "an inline answer is sent with the quoted lines around it"
          (let [inline (str (fs/path d "inline.sh"))]
            (spit inline "#!/bin/sh\nawk '{print} /^> Need your OK$/{print \"Inline answer.\"}' \"$1\" > \"$1.new\" && mv \"$1.new\" \"$1\"\n")
            (fs/set-posix-file-permissions inline "rwxr-xr-x")
            (is (zero? (:exit (am {:who "user" :env {"AGENT_MAIL_EDITOR" inline}} "reply" id "--edit"))))
            (let [m (last (:msgs (json/parse-string (out {} "read" id "--json") true)))]
              (is (str/starts-with? (:body m) "> […]\n> ## infra → user"))
              (is (str/ends-with? (:body m) "\n>\n> Need your OK\nInline answer."))
              (is (= "infra" (:to m))))
            (out {:who "infra"} "reply" id "noted")))
        (testing "text below the marker becomes a reply to the other party"
          (let [r (am {:who "user" :env {"AGENT_MAIL_EDITOR" typing}} "reply" id "--edit")]
            (is (zero? (:exit r)))
            (is (= id (str/trim (:out r))))
            (is (= [id] (ids "infra")))
            (let [t (out {} "read" id)]
              (is (str/includes? t "Approved, go ahead."))
              (is (not (str/includes? t "agent-mail: write your reply")) "the marker is not part of the message"))))))))

(deftest open-shows-the-plain-render
  (with-store
    (fs/with-temp-dir [d {}]
      (let [viewer (str (fs/path d "viewer.sh"))
            seen (str (fs/path d "seen"))
            id (out {:who "infra"} "send" "--to" "user" "--subject" "Need your OK" "Details here.")]
        (spit viewer (str "#!/bin/sh\ncp \"$1\" " seen "\n"))
        (fs/set-posix-file-permissions viewer "rwxr-xr-x")
        (is (zero? (:exit (am {:who "user" :env {"AGENT_MAIL_VIEWER" viewer}} "open" id))))
        (let [v (slurp seen)]
          (is (str/starts-with? v "# Need your OK"))
          (is (not (str/includes? v "\n> ")) "unquoted")
          (is (not (str/includes? v "agent-mail: the thread above"))))
        (is (= [id] (ids "user")) "nothing was sent")
        (testing "open shows only what is new to the reader"
          (out {:who "user"} "reply" id "Yes.")
          (out {:who "infra"} "reply" id "Then also this?")
          (is (str/includes? (out {:who "user"} "list") "1 new"))
          (am {:who "user" :env {"AGENT_MAIL_VIEWER" viewer}} "open" id)
          (let [v (slurp seen)]
            (is (str/includes? v "Then also this?"))
            (is (not (str/includes? v "Details here.")) "already seen")
            (is (str/includes? v "showing 1 of 3 messages")))
          (is (not (str/includes? (out {:who "user"} "list") "new")) "no unread count after opening")
          (am {:who "user" :env {"AGENT_MAIL_VIEWER" viewer}} "open" id)
          (is (str/includes? (slurp seen) "Then also this?") "nothing new: the last message again"))))))

(deftest picker
  (with-store
    (testing "one candidate is picked without asking"
      (let [id (out {:who "a"} "@b" "only one")]
        (is (str/includes? (out {:who "b"} "read") "only one"))
        (out {:who "a"} "@b" "a second")
        (is (str/includes? (:err (am {:who "b"} "read")) "which thread?") "no terminal: ask for a number")
        (is (= id (out {:who "b"} "reply" "1" "ok")))))))

(deftest options-anywhere
  (with-store
    (let [id (out {:who "a"} "send" "--to" "b" "--subject" "S" "first" "--date" "2026-10-01")]
      (is (= id (out {:who "b"} "reply" id "--to" "a" "--date" "2026-10-02" "the reply")))
      (let [m (last (:msgs (json/parse-string (out {} "read" id "--json") true)))]
        (is (= "the reply" (:body m)))
        (is (= "a" (:to m)))
        (is (= "2026-10-02T00:00:00.000Z" (:date m))))
      (is (= id (out {:who "a"} "reply" id "--" "--not-an-option")))
      (is (str/includes? (out {} "read" id) "\n--not-an-option"))
      (is (pos? (:exit (am {:who "a"} "reply" id "--bogus" "x")))))))

(deftest refusals
  (with-store
    (is (pos? (:exit (am {:who "a"} "@a" "to myself"))))
    (is (pos? (:exit (am {:who "a"} "send" "--to" "b"))) "empty body")
    (is (pos? (:exit (am {:who "a"} "send" "--to" "b" "--date" "yesterday" "x"))))
    (is (pos? (:exit (am {} "read" "nothing-here"))))))

(let [{:keys [fail error]} (t/run-tests 'test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
