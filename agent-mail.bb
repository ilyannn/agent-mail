#!/usr/bin/env bb
;; agent-mail: durable, file-based mail between agents (and you) on one machine.
;; One write-once Markdown file per message; a thread's owner and status are
;; derived from its messages, never edited. Run `agent-mail help`.
;; Store: $AGENT_MAIL_DIR, else ~/.local/share/agent-mail (its own git repo).
;; The pure functions live in src/agent_mail/core.clj next to this script.
(ns agent-mail
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def repo-dir (fs/parent (fs/absolutize *file*)))
(cp/add-classpath (str (fs/path repo-dir "src")))
(require '[agent-mail.core :as core])

;; ---------------------------------------------------------------- store

(def store (fs/path (or (System/getenv "AGENT_MAIL_DIR") (fs/xdg-data-home "agent-mail"))))
(def threads-dir (fs/path store "threads"))
(def duty-dir (fs/path store "duty"))
(def seen-dir (fs/path store "seen"))
(def lock-dir (fs/path store ".lock"))
(def stale-s 30)

(defn fail
  "A refusal: thrown so that locks and other `finally` blocks are released,
   reported by -main on stderr with exit code 1."
  [& msg]
  (throw (apply core/usage-error msg)))

(defn git [& args]
  (apply p/shell {:dir (str store) :out :string :err :string :continue true} "git" args))

(defn- lock-age-ms []
  (try (- (System/currentTimeMillis) (.toMillis (fs/last-modified-time lock-dir)))
       (catch java.io.IOException _ 0)))

(defn locked
  "Runs f holding <store>/.lock, a directory created atomically, so two
   commands never pick the same thread number or commit over each other. A
   lock older than `stale-s` is taken over as left behind by a dead process."
  [f]
  (loop [n 0]
    (if (try (fs/create-dir lock-dir) true
             (catch java.nio.file.FileAlreadyExistsException _ false))
      (try (f) (finally (fs/delete-if-exists lock-dir)))
      (do (when (> (lock-age-ms) (* 1000 stale-s))
            (fs/delete-if-exists lock-dir))
          (when (>= n 300) (fail "the store is locked by another agent-mail command (" (str lock-dir) ")"))
          (Thread/sleep 100)
          (recur (inc n))))))

(declare ensure-numbers!)

(defn ensure-identity!
  "Commits need an author; use a local one when git has none configured (CI,
   fresh machines) rather than failing silently."
  []
  (when-not (zero? (:exit (git "config" "user.email")))
    (git "config" "user.name" "agent-mail")
    (git "config" "user.email" "agent-mail@localhost")))

(defn ensure-store! []
  (fs/create-dirs threads-dir)
  (fs/create-dirs duty-dir)
  (when-not (fs/exists? (fs/path store ".git"))
    (locked (fn []
              (when-not (fs/exists? (fs/path store ".git"))
                (git "init" "-q")
                (ensure-identity!)
                (spit (str (fs/path store ".gitignore")) "duty/\ndrafts/\nview/\nseen/\n")
                (git "add" "-A")
                (git "commit" "-q" "-m" "📬 init: agent-mail store")))))
  (ensure-numbers!))

(defn commit! [msg]
  (ensure-identity!)
  (loop [n 0]
    (git "add" "-A")
    (let [r (git "commit" "-q" "-m" msg)]
      (when (and (pos? (:exit r))
                 (str/includes? (str (:err r) (:out r)) "index.lock")
                 (< n 20))
        (Thread/sleep 200)
        (recur (inc n))))))

;; ---------------------------------------------------------------- threads

(defn thread-dir [id] (fs/path threads-dir id))
(defn thread-ids []
  (when (fs/exists? threads-dir)
    (->> (fs/list-dir threads-dir)
         (filter #(fs/exists? (fs/path % "thread.md")))
         (map #(str (fs/file-name %))) sort)))

(defn read-msg [f]
  (let [[fm body] (core/parse-doc (slurp (str f)))]
    (assoc fm :file (str (fs/file-name f)) :body (str/trim body))))

(defn msg-files [id]
  (let [dir (fs/path (thread-dir id) "msgs")]
    (when (fs/exists? dir) (sort (fs/glob dir "*.md")))))

(defn fold
  "A thread's state, derived from its messages."
  [id]
  (let [[meta _] (core/parse-doc (slurp (str (fs/path (thread-dir id) "thread.md"))))]
    (core/thread-state meta id (map read-msg (msg-files id)))))

(defn resolve-id [s] (core/resolve-id s (thread-ids) #(:number (fold %))))

(defn all-threads [] (map fold (thread-ids)))

;; What each participant has seen: <store>/seen/<who>.edn maps thread id to the
;; last message file read. Not committed; losing it only means re-reading.
(defn seen-map [who]
  (let [f (fs/path seen-dir (str who ".edn"))]
    (if (fs/exists? f) (edn/read-string (slurp (str f))) {})))
(defn mark-seen! [who th]
  (when-let [last-file (:file (last (:msgs th)))]
    (fs/create-dirs seen-dir)
    (spit (str (fs/path seen-dir (str who ".edn")))
          (pr-str (assoc (seen-map who) (:id th) last-file)))))
(defn unread
  "Messages in `th` that `who` has not seen and did not write."
  [th who]
  (core/unread (:msgs th) who (get (seen-map who) (:id th))))

(defn open-for [who] (filter #(and (= who (:owner %)) (= "open" (:status %))) (all-threads)))

(defn pick-thread
  "No thread given: let the user choose. Candidates are the open threads whose
   turn is `me`'s, else every open thread. One candidate is taken as is;
   otherwise fzf, or a numbered prompt without it."
  [me]
  (let [cands (let [mine (open-for me)]
                (if (seq mine) mine (filter #(= "open" (:status %)) (all-threads))))
        line (fn [t] (format "#%d  %s  (from %s, %d msgs%s)" (:number t) (:subject t) (:last-from t) (:count t)
                             (let [u (count (unread t me))] (if (pos? u) (str ", " u " new") ""))))
        chosen (fn [out] (some-> out str/trim not-empty (str/split #"\s+") first (subs 1) parse-long))]
    (cond
      (empty? cands) (fail "no open threads")
      (= 1 (count cands)) (:id (first cands))
      (not (System/console)) (fail "which thread? give a number from `agent-mail list`")
      :else
      (let [n (if (fs/which "fzf")
                (let [r (p/shell {:in (str/join "\n" (map line cands)) :out :string :err :inherit :continue true}
                                 "fzf" "--height" "40%" "--reverse" "--prompt" "thread> ")]
                  (if (zero? (:exit r)) (chosen (:out r)) (fail "nothing chosen")))
                (do (run! println (map line cands))
                    (print "thread number> ") (flush)
                    (chosen (read-line))))]
        (or (some #(when (= n (:number %)) (:id %)) cands) (fail "no such thread"))))))

(defn fresh-or-last
  "The unread messages, or the last one when nothing is unread."
  [th who]
  (let [u (unread th who)] (if (seq u) u (take-last 1 (:msgs th)))))

(defn next-number [] (inc (reduce max 0 (keep :number (all-threads)))))

(defn ensure-numbers!
  "Threads created before numbering get one, in creation order, once."
  []
  (doseq [id (thread-ids)
          :let [f (str (fs/path (thread-dir id) "thread.md"))
                [meta _] (core/parse-doc (slurp f))]
          :when (nil? (:number meta))]
    (spit f (str (core/fm-str (assoc meta :number (next-number))) "\n"))))

(defn check-attachments!
  "Refuses before anything is written when an attachment is missing."
  [paths]
  (doseq [p paths :when (not (fs/regular-file? p))]
    (fail "attachment not found:" (str p))))

(defn attach!
  "Copies the files into the thread; a name already taken by different content
   gets a short content-hash prefix. Returns the `files/<name>` references."
  [id paths]
  (let [dir (fs/path (thread-dir id) "files")]
    (fs/create-dirs dir)
    (vec (for [p paths]
           (let [p (fs/path p)
                 nm (str (fs/file-name p))
                 content (slurp (str p))
                 nm (if (and (fs/exists? (fs/path dir nm))
                             (not= content (slurp (str (fs/path dir nm)))))
                      (str (format "%06x" (bit-and (hash content) 0xffffff)) "-" nm)
                      nm)]
             (fs/copy p (fs/path dir nm) {:replace-existing true})
             (str "files/" nm))))))

(defn write-msg! [id {:keys [from to date in-reply-to status attachments body]}]
  (let [dir (fs/path (thread-dir id) "msgs")
        last-date (some-> (seq (msg-files id)) last read-msg :date)
        date (core/after (or date (core/now-utc)) last-date)
        nm (str (core/stamp->name date) "__" from "__" (format "%04x" (rand-int 65536)) ".md")
        tmp (fs/path dir (str "." nm ".tmp"))]
    (fs/create-dirs dir)
    (spit (str tmp)
          (str (core/fm-str (cond-> {:from from :to to :date date}
                              in-reply-to (assoc :in-reply-to in-reply-to)
                              status (assoc :status status)
                              (seq attachments) (assoc :attachments attachments)))
               "\n" (str/trim body) "\n"))
    (fs/move tmp (fs/path dir nm))
    nm))

;; ---------------------------------------------------------------- identity, duty

(defn repo-name
  "Basename of the main working tree of the repo the command runs in, so a
   worktree still counts as its repo. nil outside any repo."
  []
  (let [r (p/shell {:out :string :err :string :continue true} "git" "rev-parse" "--git-common-dir")]
    (when (zero? (:exit r))
      (-> (:out r) str/trim fs/path fs/absolutize fs/normalize fs/parent fs/file-name str))))

(defn agent-session?
  "True inside a coding agent's shell. Claude Code (2.1.172+) sets
   CLAUDE_CODE_CHILD_SESSION in the processes it launches itself (Bash, hooks,
   status line) and nowhere else. The older CLAUDECODE is deliberately not
   used: IDE extensions set it in their integrated terminals too, where it is
   the user typing."
  []
  (not (str/blank? (System/getenv "CLAUDE_CODE_CHILD_SESSION"))))

(defn caller
  "Who runs the command: --as or AGENT_MAIL_AS if set; otherwise the user in
   the user's own shell, and in an agent's shell (see agent-session?) the repo's
   name, or outside any repo the directory's name with a note on stderr (not
   from the hook, which must stay silent). An agent never becomes the user by
   accident, and the user never becomes a repo."
  [opts]
  (let [explicit (or (:as opts) (System/getenv "AGENT_MAIL_AS"))
        me (or explicit
               (if-not (agent-session?)
                 "user"
                 (or (repo-name)
                     (let [dir (str (fs/file-name (fs/normalize (fs/absolutize "."))))]
                       (when-not (:quiet opts)
                         (binding [*out* *err*]
                           (println (str "agent-mail: not in a git repo; acting as " (pr-str dir)
                                         " (AGENT_MAIL_AS=NAME or --as NAME to choose)"))))
                       dir))))]
    (when-not (core/valid-name? me) (fail "bad name for the caller:" (pr-str me)))
    me))

(defn recipient [opts]
  (let [to (or (:to opts) (fail "send needs --to NAME (or `agent-mail @NAME text`)"))]
    (when-not (core/valid-name? to) (fail "bad recipient name:" (pr-str to)))
    to))

(defn duty-records []
  (when (fs/exists? duty-dir)
    (->> (fs/glob duty-dir "*.edn") (map #(edn/read-string (slurp (str %)))) (sort-by :heartbeat))))
(defn fresh? [rec] (< (core/age-s (:heartbeat rec)) stale-s))
(defn session-alive?
  "Whether the agent session that armed this wait is still running: nil when
   the record has no session pid (not started from Claude Code), else a boolean."
  [rec]
  (when-let [pid (:session-pid rec)]
    (.isPresent (java.lang.ProcessHandle/of pid))))
(defn on-duty?
  "A fresh record in any state counts: \"idle\" or \"working\" with a recent
   heartbeat means the wait just exited (timeout or mail) and the session is
   about to arm again. A \"working\" record never heartbeats, so past 30 s it
   counts as long as its session is still alive: it answers, then re-arms."
  [rec]
  (or (fresh? rec)
      (and (= "working" (:state rec)) (true? (session-alive? rec)))))
(defn on-duty [who] (filter #(and (= who (:repo %)) (on-duty? %)) (duty-records)))
(defn write-duty!
  "The duty record also carries the pid of the agent session that runs this
   wait (Claude Code exports CLAUDE_PID), so others can tell a busy session
   from a dead one."
  [who session state]
  (fs/create-dirs duty-dir)
  (spit (str (fs/path duty-dir (str who "__" session ".edn")))
        (pr-str (cond-> {:repo who :session session :state state :heartbeat (core/now-utc)
                         :pid (.pid (java.lang.ProcessHandle/current))}
                  (some-> (System/getenv "CLAUDE_PID") parse-long) (assoc :session-pid (parse-long (System/getenv "CLAUDE_PID")))))))

;; ---------------------------------------------------------------- delivery notes

(def launcher (str (fs/path repo-dir "bin" "agent-mail")))
(def macos? (str/includes? (System/getProperty "os.name") "Mac"))

(defn notify-user!
  "macOS notification for mail to the user. With terminal-notifier installed and
   allowed, a click opens the thread for reading and replying; a plain osascript
   banner is the fallback. Returns :clickable, :banner or nil."
  [from id subject]
  (when-not (System/getenv "AGENT_MAIL_NO_NOTIFY")
    (let [title (str "agent-mail: from " from)
          tn (when (fs/which "terminal-notifier")
               (p/shell {:continue true :out :string :err :string}
                        "terminal-notifier" "-title" title "-subtitle" id "-message" subject
                        "-group" (str "agent-mail/" id) "-execute" (str launcher " open " id)))]
      (cond
        (and tn (zero? (:exit tn))) :clickable
        (fs/which "osascript")
        (let [q #(str "\"" (str/replace (str/replace % "\\" "\\\\") "\"" "\\\"") "\"")]
          (p/shell {:continue true :out :string :err :string}
                   "osascript" "-e"
                   (str "display notification " (q subject) " with title " (q title) " subtitle " (q id)))
          (if tn :banner-not-allowed :banner))))))

(defn delivered! [from to id subject]
  (binding [*out* *err*]
    (if (= to "user")
      (println (case (notify-user! from id subject)
                 :clickable "→ user: notified (click the banner to open and reply); shown at every session start too"
                 :banner-not-allowed "→ user: notified with a plain banner: terminal-notifier isn't allowed to notify yet (System Settings > Notifications); shown at every session start too"
                 :banner "→ user: notified; shown at every session start too"
                 "→ user: shown at every session start"))
      (if-let [d (seq (on-duty to))]
        (println (str "→ " to ": on duty (session "
                      (str/join ", " (map #(str (:session %) (cond (= "waiting" (:state %)) ""
                                                                     (fresh? %) ", between waits"
                                                                     :else ", busy")) d))
                      "); nudge it with SendMessage carrying just the thread id"))
        (println (str "→ " to ": nobody on duty; it will see this at its next session start"))))))

(defn body-from [args]
  (if (seq args)
    (str/join " " args)
    (do (when (System/console)
          (binding [*out* *err*] (println "agent-mail: reading the body from stdin, end with Ctrl-D")))
        (slurp *in*))))

;; ---------------------------------------------------------------- commands

(defn cmd-send [opts args]
  (ensure-store!)
  (let [from (caller opts)
        to (recipient opts)
        _ (when (= from to) (fail "refusing to mail yourself (" from ")"))
        body (body-from args)
        _ (when (str/blank? body) (fail "empty body"))
        _ (check-attachments! (:attach opts))
        subject (or (:subject opts) (core/truncate (core/first-line body) 70))
        created (or (core/normalize-date (:date opts)) (core/now-utc))]
    (locked
     (fn []
       (let [id (or (:id opts) (core/new-id subject created (thread-ids)))
             number (next-number)]
         (when (fs/exists? (thread-dir id)) (fail "thread exists:" id))
         (fs/create-dirs (thread-dir id))
         (spit (str (fs/path (thread-dir id) "thread.md"))
               (str (core/fm-str {:subject subject :from from :created created :number number}) "\n"))
         (let [atts (attach! id (:attach opts))]
           (write-msg! id {:from from :to to :date created :attachments atts :body body}))
         (commit! (str "📨 " from " → " to ": " subject))
         (println id)
         (delivered! from to id subject))))))

(declare cmd-compose)

(defn reply-to
  "The recipient of a reply from `me` to `th`: --to, else whoever wrote last
   that isn't me. Never me."
  [opts th me]
  (let [to (or (:to opts) (core/other-party th me) (fail "nobody to reply to; use --to NAME"))]
    (when-not (core/valid-name? to) (fail "bad recipient name:" (pr-str to)))
    (when (= to me) (fail "refusing to mail yourself (" me ")"))
    to))

(defn cmd-reply [opts args {:keys [close?]}]
  (ensure-store!)
  (when (and (not close?) (empty? (rest args)) (or (:edit opts) (System/console)))
    (cmd-compose opts args)
    (System/exit 0))
  (let [me (caller opts)
        id (if (first args) (resolve-id (first args)) (pick-thread me))
        body (body-from (rest args))
        body (if (and close? (str/blank? body)) "Closed." body)
        _ (when (str/blank? body) (fail "empty body"))
        _ (check-attachments! (:attach opts))
        date (core/normalize-date (:date opts))]
    (locked
     (fn []
       (let [th (fold id)
             to (reply-to opts th me)
             atts (attach! id (:attach opts))]
         (write-msg! id {:from me :to to :date date
                         :in-reply-to (:file (last (:msgs th))) :status (when close? "done")
                         :attachments atts :body body})
         (commit! (str (if close? "✅ " "↩️ ") me " → " to ": " (:subject th)))
         (mark-seen! me (fold id))
         (println id)
         (when-not close? (delivered! me to id (:subject th))))))))

(defn files-dir [id] (str (fs/path (thread-dir id) "files")))
(defn render-thread
  ([th] (core/render-thread th (files-dir (:id th))))
  ([th msgs] (core/render-thread th (files-dir (:id th)) msgs)))

(defn editor-cmd
  "AGENT_MAIL_EDITOR, else $VISUAL, else $EDITOR, else on macOS `open -W` (the
   default Markdown app, waiting until you quit it)."
  []
  (let [env #(let [v (System/getenv %)] (when-not (str/blank? v) v))]
    (str/split (or (env "AGENT_MAIL_EDITOR") (env "VISUAL") (env "EDITOR")
                   (when (and macos? (fs/which "open")) "open -W")
                   (fail "no editor: set AGENT_MAIL_EDITOR or EDITOR"))
               #"\s+")))

(defn cmd-open
  "Renders the thread, unquoted, to <store>/view/<id>.md and opens it in the
   default app for Markdown (AGENT_MAIL_VIEWER overrides). Does not wait."
  [opts args]
  (ensure-store!)
  (let [me (caller opts)
        id (if (first args) (resolve-id (first args)) (pick-thread me))
        th (fold id)
        dir (fs/path store "view")
        f (str (fs/path dir (str id ".md")))
        viewer (or (System/getenv "AGENT_MAIL_VIEWER") (when (and macos? (fs/which "open")) "open"))]
    (fs/create-dirs dir)
    (spit f (render-thread th (fresh-or-last th me)))
    (mark-seen! me th)
    (if viewer
      (p/shell {:continue true} viewer f)
      (binding [*out* *err*] (println "agent-mail: no viewer (set AGENT_MAIL_VIEWER); rendered to" f)))
    (when (:json opts) (println f))))

(defn cmd-compose
  "Reply from the editor: the thread goes to <store>/drafts/<id>.md quoted with
   `>`, the editor is waited for, and what the user wrote is sent as the reply."
  [opts args]
  (ensure-store!)
  (let [me (caller opts)
        id (if (first args) (resolve-id (first args)) (pick-thread me))
        th (fold id)
        dir (fs/path store "drafts")
        f (str (fs/path dir (str id ".md")))
        _ (fs/create-dirs dir)
        _ (spit f (str (core/quote-lines (render-thread th (fresh-or-last th me))) "\n\n---\n" core/reply-marker "\n\n"))
        cmd (conj (editor-cmd) f)
        r (apply p/shell {:continue true} cmd)
        _ (when (pos? (:exit r)) (fail "editor failed:" (str/join " " cmd)))
        body (core/reply-from-draft (slurp f))]
    (if (str/blank? body)
      (binding [*out* *err*] (println "no reply written; nothing sent"))
      (locked
       (fn []
         (let [th (fold id)
               to (or (:to opts) (core/other-party th me) (fail "nobody to reply to; draft kept at" f))]
           (write-msg! id {:from me :to to :in-reply-to (:file (last (:msgs th))) :body body})
           (commit! (str "↩️ " me " → " to ": " (:subject th)))
           (mark-seen! me (fold id))
           (fs/delete f)
           (println id)
           (delivered! me to id (:subject th))))))))

(defn cmd-read [opts args]
  (ensure-numbers!)
  (let [me (caller opts)
        th (fold (if (first args) (resolve-id (first args)) (pick-thread me)))]
    (mark-seen! me th)
    (if (:json opts)
      (println (json/generate-string th {:pretty true}))
      (print (render-thread th)))))

(defn summary [th] (dissoc th :msgs))

(defn cmd-list [opts]
  (ensure-numbers!)
  (let [me (caller opts)
        ths (if (:all opts) (all-threads) (open-for me))
        ths (map #(assoc % :unread (count (unread % me))) (sort-by :updated ths))]
    (if (:json opts)
      (println (json/generate-string (map summary ths) {:pretty true}))
      (doseq [t ths]
        (println (format "#%-3d %-34s %-14s %-5s %2d msgs %s  %s  %s"
                         (:number t) (:id t) (str (:last-from t) "→" (:owner t)) (:status t) (:count t)
                         (if (pos? (:unread t)) (format "%d new" (:unread t)) "     ")
                         (core/local-time (:updated t)) (:subject t)))))))

(defn cmd-wait [opts]
  (ensure-store!)
  (let [me (caller opts)
        session (or (:session opts) (str "pid" (.pid (java.lang.ProcessHandle/current))))
        interval (or (:interval opts) 3)
        deadline (when-let [t (:timeout opts)] (+ (System/currentTimeMillis) (* 1000 t)))]
    (loop []
      (write-duty! me session "waiting")
      (let [ids (map :id (open-for me))]
        (cond (seq ids) (do (write-duty! me session "working") (run! println ids) (System/exit 0))
              (and deadline (>= (System/currentTimeMillis) deadline))
              (do (write-duty! me session "idle") (System/exit 3))
              :else (do (Thread/sleep (* 1000 interval)) (recur)))))))

(defn cmd-who [opts]
  (let [recs (map #(assoc % :age-s (core/age-s (:heartbeat %)) :stale (not (fresh? %))
                          :session-alive (session-alive? %) :on-duty (on-duty? %))
                  (duty-records))]
    (if (:json opts)
      (println (json/generate-string recs {:pretty true}))
      (doseq [r recs]
        (println (format "%-20s %-28s %-8s %5ds ago%s" (:repo r) (:session r) (:state r) (:age-s r)
                         (cond (false? (:session-alive r)) "  (session gone)"
                               (and (:stale r) (= "waiting" (:state r))) "  (stale)"
                               (and (:stale r) (:on-duty r)) "  (session alive)"
                               :else "")))))))

(defn cmd-hook
  "SessionStart hook mode: one line listing the threads waiting on this repo;
   silent otherwise; never fails. Mail for the user is the user's business (they
   get notifications), so sessions are not told about it."
  [opts]
  (try
    (ensure-numbers!)
    (let [me (caller (assoc opts :quiet true))
          line (fn [who ths]
                 (println (format "agent-mail: %d thread%s waiting on %s: %s. Read with `agent-mail read <number>`."
                                  (count ths) (if (= 1 (count ths)) "" "s") who
                                  (str/join "; " (map #(format "#%d %s \"%s\" from %s" (:number %) (:id %) (:subject %) (:last-from %)) ths)))))]
      (when-let [m (seq (open-for me))] (line me m)))
    (catch Throwable _ nil))
  (System/exit 0))

(def help "agent-mail: durable mail between agents (and you) on this machine.

  agent-mail @NAME text…                     new thread to NAME (subject = first line)
  agent-mail send --to NAME --subject S [--attach FILE]… [text… | < body.md]
  agent-mail reply ID [--to NAME] [--attach FILE]… [text… | < body.md]
  agent-mail reply ID [--edit]                no text on a terminal: what's new opens quoted in
                                              your editor; what you write (inline or at the end)
                                              is sent when you close it
  agent-mail close ID [text…]                 marks the thread done
  (open, reply, read and close without ID offer a picker: fzf when installed)
  agent-mail list [--all] [--json]            open threads whose turn is mine
  agent-mail read ID [--json]                 the whole thread; ID is the number from `list`
                                              (7 or #7), the full id, or a unique fragment
  agent-mail open ID                          open what's new to you in your Markdown app
  agent-mail wait [--session NAME] [--timeout S] [--interval S]
  agent-mail who [--json]                     who is on duty
  agent-mail hook                             SessionStart hook output
  agent-mail whoami                           who the caller is

Viewer for open: AGENT_MAIL_VIEWER, else the macOS default app for .md.
Editor for reply: AGENT_MAIL_EDITOR, else $VISUAL, else $EDITOR, else `open -W`
(your Markdown app, until you quit it). E.g. AGENT_MAIL_EDITOR='zed --wait'.
A notification click runs `agent-mail open ID` (needs terminal-notifier).
Who am I: the repo I run in (main worktree name), `user` outside any repo;
--as NAME or AGENT_MAIL_AS overrides. Bodies are Markdown; attachments are
copied into the thread and `read` prints their absolute paths. --date backfills
a timestamp when importing old threads; otherwise the tool stamps time.
Store: $AGENT_MAIL_DIR, else ~/.local/share/agent-mail.")

(defn -main [argv]
  (try
    (let [{:keys [args opts]} (core/parse-argv (core/expand-shorthand argv))
          [cmd & args] args]
      (case cmd
        "send" (cmd-send opts args)
        "reply" (cmd-reply opts args {})
        "close" (cmd-reply opts args {:close? true})
        "read" (cmd-read opts args)
        "open" (cmd-open opts args)
        "list" (cmd-list opts)
        "wait" (cmd-wait opts)
        "who" (cmd-who opts)
        "hook" (cmd-hook opts)
        "whoami" (println (caller opts))
        (nil "help") (println help)
        (fail "unknown command" cmd "(try `agent-mail help`)")))
    (catch clojure.lang.ExceptionInfo e
      (when-not (core/usage? e) (throw e))
      (binding [*out* *err*] (println (str "agent-mail: " (ex-message e))))
      (System/exit 1))))

(-main *command-line-args*)
