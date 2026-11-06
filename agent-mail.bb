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
            [clojure.string :as str])
  (:import (java.net StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio ByteBuffer)
           (java.nio.channels SocketChannel)
           (java.nio.charset StandardCharsets)))

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

(defn read-edn
  "The EDN value in file `f`; nil when it is missing, half written or does
   not parse, so one bad file never stops a reader."
  [f]
  (try (when (fs/regular-file? f) (edn/read-string (slurp (str f))))
       (catch Exception _ nil)))

(defn spit-atomic!
  "Writes `content` to `f` through a temporary file and a move, so a reader
   never sees half of it. The temporary name starts with a dot and ends in
   .tmp, which no glob for *.edn picks up."
  [f content]
  (fs/create-dirs (fs/parent f))
  (let [tmp (fs/path (fs/parent f) (str "." (fs/file-name f) "." (.pid (java.lang.ProcessHandle/current)) ".tmp"))]
    (spit (str tmp) content)
    (fs/move tmp f {:replace-existing true :atomic-move true})))

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

(defn repo-root
  "The main working tree of the repo the command runs in: the parent of git's
   common dir, so a worktree still counts as its repo. nil outside any repo."
  []
  (let [r (p/shell {:out :string :err :string :continue true} "git" "rev-parse" "--git-common-dir")]
    (when (zero? (:exit r))
      (-> (:out r) str/trim fs/path fs/absolutize fs/normalize fs/parent))))

(defn repo-name
  "Basename of the main working tree of the repo the command runs in (see
   repo-root). nil outside any repo."
  []
  (some-> (repo-root) fs/file-name core/address-from-dir))

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
                     (let [dir (core/address-from-dir (fs/file-name (fs/normalize (fs/absolutize "."))))]
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

(defn duty-files
  "[path record] for each duty record: the *.edn files directly in the duty
   directory, not the on-call registrations and serve's state below it. A file
   that does not read is skipped."
  []
  (when (fs/exists? duty-dir)
    (keep #(when-let [r (read-edn %)] [% r]) (fs/glob duty-dir "*.edn"))))
(defn duty-records [] (sort-by :heartbeat (map second (duty-files))))
(defn fresh? [rec] (< (core/age-s (:heartbeat rec)) stale-s))
(defn pid-alive?
  "Whether a process with this pid runs; false for nil."
  [pid]
  (boolean (and pid (.isPresent (java.lang.ProcessHandle/of pid)))))
(defn session-alive?
  "Whether the agent session that armed this wait is still running: nil when
   the record has no session pid (not started from Claude Code), else a boolean."
  [rec]
  (when-let [pid (:session-pid rec)]
    (pid-alive? pid)))
(defn on-duty?
  "A fresh record in any state counts. A wait posting into its session
   heartbeats while \"waiting\" and \"working\" alike; \"idle\" with a recent
   heartbeat means a wait just timed out and the session is about to arm
   again, as does \"working\" from a wait that exited on mail (classic mode).
   That record never heartbeats again, so past 30 s it counts as long as its
   session is still alive: it answers, then re-arms."
  [rec]
  (or (fresh? rec)
      (and (= "working" (:state rec)) (true? (session-alive? rec)))))
(defn on-duty [who] (filter #(and (= who (:repo %)) (on-duty? %)) (duty-records)))

(defn claude-pid
  "The pid of the Claude Code session running this command, which Claude Code
   exports as CLAUDE_PID to the commands it runs; nil elsewhere."
  []
  (some-> (System/getenv "CLAUDE_PID") str/trim parse-long))

(defn registry-entry
  "Claude Code's session registry entry for process id `pid`, a map with
   string keys: <config>/sessions/<pid>.json, <config> being
   CLAUDE_CONFIG_DIR, else ~/.claude. nil when there is no such file or it
   does not parse; never throws."
  [pid]
  (try
    (let [config (let [d (System/getenv "CLAUDE_CONFIG_DIR")]
                   (if (str/blank? d) (fs/path (fs/home) ".claude") (fs/path d)))
          f (fs/path config "sessions" (str pid ".json"))
          e (when (fs/regular-file? f) (json/parse-string (slurp (str f))))]
      (when (map? e) e))
    (catch Exception _ nil)))

(defn registry-name
  "The name Claude Code's session registry gives the session with process id
   `pid` (see registry-entry). Unlike a name learned once, it follows
   restarts and renames. nil when there is none; never throws."
  [pid]
  (let [nm (get (registry-entry pid) "name")]
    (when (and (string? nm) (not (str/blank? nm))) nm)))

(defn session-status
  "What the registry says session `pid` is doing, {:status
   :status-updated-at}: status \"busy\" during a turn and \"idle\" between
   turns, changed at statusUpdatedAt (epoch ms). nil when the process is gone
   or has no entry, so a dead session's leftover entry never looks idle."
  [pid]
  (when (pid-alive? pid)
    (when-let [e (registry-entry pid)]
      {:status (get e "status") :status-updated-at (get e "statusUpdatedAt")})))

(defn session-name
  "The live name of the Claude Code session running this command, the one
   SendMessage reaches it by (see registry-name); nil outside Claude Code or
   when the registry has no readable entry for it."
  []
  (some-> (claude-pid) registry-name))

(defn live-session
  "The name to give for a duty record's session: the registry's name for its
   session pid when there is one, since a record written by an older version
   may carry a name the session has since lost; else the name recorded."
  [rec]
  (or (some-> (:session-pid rec) registry-name) (:session rec)))

(defn duty-file
  "Where `who`'s duty record for `session` lives. A slash in the name (Claude
   Code session names are the user's to choose) must not make a subdirectory."
  [who session]
  (fs/path duty-dir (str who "__" (str/replace session "/" "_") ".edn")))

(defn wezterm-pane
  "The WezTerm pane this command runs in: WezTerm exports WEZTERM_PANE to the
   processes it spawns. nil elsewhere."
  []
  (some-> (System/getenv "WEZTERM_PANE") str/trim parse-long))

(defn write-duty!
  "The duty record also carries the pid of the agent session that runs this
   wait (Claude Code exports CLAUDE_PID), so others can tell a busy session
   from a dead one; the directory it runs in; and its WezTerm pane, by which
   serve knows a tab it opened. Written in one move: serve must never read
   half a record and take the repo for off duty."
  [who session state]
  (spit-atomic! (duty-file who session)
                (pr-str (cond-> {:repo who :session session :state state :heartbeat (core/now-utc)
                                 :pid (.pid (java.lang.ProcessHandle/current))
                                 :dir (str (fs/normalize (fs/absolutize ".")))}
                          (claude-pid) (assoc :session-pid (claude-pid))
                          (wezterm-pane) (assoc :pane (wezterm-pane))))))

(defn duty-view
  "The duty records with what is derived for each: the session's live name
   (see live-session), age, staleness, whether the session that armed the wait
   lives, and whether it counts as on duty."
  []
  (map #(assoc % :session (live-session %)
               :age-s (core/age-s (:heartbeat %)) :stale (not (fresh? %))
               :session-alive (session-alive? %) :on-duty (on-duty? %))
       (duty-records)))

;; ---------------------------------------------------------------- on call

(def oncall-dir
  "Registrations, one <repo>.edn each ({:repo :dir}, or :off after `oncall
   remove`). Under duty/, which the store's git ignores."
  (fs/path duty-dir "oncall"))
(def serve-dir (fs/path duty-dir "serve"))
(def serve-pid-file (fs/path serve-dir "pid"))
(def serve-state-file
  "serve's heartbeat, interval and memory of the tabs it opened (see core)."
  (fs/path serve-dir "state.edn"))

(defn oncall-file [repo] (fs/path oncall-dir (str repo ".edn")))

(defn oncall-entries
  "Every on-call entry, removed ones (:off) included: repo -> entry."
  []
  (when (fs/exists? oncall-dir)
    (into {} (keep #(when-let [e (read-edn %)] (when (:repo e) [(:repo e) e])))
          (fs/glob oncall-dir "*.edn"))))

(defn oncall-registered
  "The repos on call: repo -> {:repo :dir}."
  []
  (into {} (remove (comp :off val)) (oncall-entries)))

(defn register-oncall! [repo dir]
  (spit-atomic! (oncall-file repo) (pr-str {:repo repo :dir (str dir) :added (core/now-utc)})))

(defn oncall-home
  "Where a listener tab for `me` should start: the main worktree when `me` is
   the repo the command runs in; outside any repo, the directory itself when
   `me` is its name. nil otherwise (an --as name), since a tab started there
   would not come on duty as `me`."
  [me]
  (if-let [root (repo-root)]
    (when (= me (core/address-from-dir (fs/file-name root))) (str root))
    (let [cwd (fs/normalize (fs/absolutize "."))]
      (when (= me (core/address-from-dir (fs/file-name cwd))) (str cwd)))))

(defn auto-register!
  "A wait in a Claude Code session puts its repo on call the first time, so
   serve can open a listener tab for it when mail comes and nobody is on
   duty. A repo taken off with `oncall remove` stays off."
  [me]
  (when-not (contains? (oncall-entries) me)
    (when-let [dir (oncall-home me)]
      (register-oncall! me dir))))

(defn serve-state [] (or (read-edn serve-state-file) {}))
(defn serve-pid [] (try (some-> (slurp (str serve-pid-file)) str/trim parse-long) (catch Exception _ nil)))

(defn serve-info
  "Whether serve runs, {:alive :pid}: its pid file names a live process and
   its heartbeat is fresh, within three rounds and at least a minute (a
   reused pid alone is not enough)."
  []
  (let [pid (serve-pid)
        st (serve-state)
        fresh? (and (:heartbeat st) (< (core/age-s (:heartbeat st)) (max 60 (* 3 (or (:interval st) 5)))))]
    {:alive (boolean (and fresh? (pid-alive? pid))) :pid pid}))

(defn sessions-of
  "Live session pid -> registry status (see session-status), for the session
   pids of `duty`."
  [duty]
  (into {} (keep (fn [pid] (when-let [s (session-status pid)] [pid s]))) (distinct (keep :session-pid duty))))

(defn oncall-view
  "The on-call repos with what serve is doing about each (core/oncall-view)."
  ([] (oncall-view (duty-view)))
  ([duty]
   (core/oncall-view {:now (System/currentTimeMillis) :oncall (oncall-registered)
                      :memory (:repos (serve-state)) :duty duty :sessions (sessions-of duty)})))

(defn serve-line
  "One line on whether serve runs, for `oncall` and `who`."
  []
  (let [{:keys [alive pid]} (serve-info)]
    (if alive
      (str "serve: running (pid " pid ")")
      "serve: not running, so no tab opens; start `agent-mail serve` (`just install-serve` in the agent-mail repo keeps it running)")))

(def wezterm-app "/Applications/WezTerm.app/Contents/MacOS/wezterm")

(defn wezterm-bin
  "The wezterm CLI: AGENT_MAIL_WEZTERM if set, else wezterm on PATH, else the
   macOS app's own copy; nil when there is none."
  []
  (let [e (System/getenv "AGENT_MAIL_WEZTERM")]
    (cond (not (str/blank? e)) e
          (fs/which "wezterm") (str (fs/which "wezterm"))
          (fs/executable? wezterm-app) wezterm-app)))

(defn wez!
  "Runs `wezterm cli --no-auto-start ARGS…`, {:exit :out :err}. Without
   --no-auto-start a CLI that finds no GUI starts a headless mux server, and a
   tab spawned there would run where nobody sees it. Exit 127 when there is
   no wezterm; never throws."
  [& args]
  (if-let [bin (wezterm-bin)]
    (try (apply p/shell {:out :string :err :string :in "" :continue true} bin "cli" "--no-auto-start" (map str args))
         (catch Exception e {:exit 127 :out "" :err (str (ex-message e))}))
    {:exit 127 :out "" :err "wezterm not found"}))

(defn wez-json
  "`wezterm cli ARGS… --format json`, parsed; nil on any failure."
  [& args]
  (let [r (apply wez! (concat args ["--format" "json"]))]
    (when (zero? (:exit r))
      (try (json/parse-string (:out r)) (catch Exception _ nil)))))

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

(defn oncall-note
  "The delivery note for `to`, which nobody is on duty for, when it is on
   call: a tab opens shortly while serve runs and the repo is not backing
   off; otherwise why not. nil when `to` is not on call."
  [to]
  (when-let [oc (first (filter #(= to (:repo %)) (oncall-view)))]
    (cond
      (not (:alive (serve-info)))
      (str "→ " to ": nobody on duty; on call, but `agent-mail serve` isn't running; it will see this at its next session start")
      (= "backing-off" (:state oc))
      (str "→ " to ": nobody on duty; on call, " (:text oc) "; it will see this at its next session start")
      :else (str "→ " to ": on call; a listener tab opens for it shortly"))))

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
                      (str/join ", " (map #(str (live-session %) (case (:state %)
                                                               "waiting" ""
                                                               "idle" ", between waits"
                                                               ", busy")) d))
                      "); nudge it with SendMessage carrying just the thread id"))
        (println (or (oncall-note to)
                     (str "→ " to ": nobody on duty; it will see this at its next session start")))))))

(defn stdin-content
  "What stands ready on stdin: the whole of a redirected file or a pipe that
   has been written to; nil at a terminal, at /dev/null and in an agent's
   shell, none of which must be read and waited on."
  []
  (when (or (try (fs/regular-file? "/dev/stdin") (catch Exception _ false))
            (pos? (.available System/in)))
    (slurp *in*)))

(defn body-from
  "The message body: the command-line text, then whatever stdin holds after a
   blank line, so `@NAME \"subject\" < body.md` reads as subject plus body;
   with no text, all of stdin (asking for it on a terminal). A lone `-` means
   \"the body is on stdin\": what stands ready there, never waited for."
  [args]
  (cond
    (= ["-"] (vec args)) (str (stdin-content))
    (seq args)
    (let [text (str/join " " args)
          more (stdin-content)]
      (if (str/blank? more) text (str text "\n\n" (str/trim more))))
    :else
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
        ;; close's note is optional, so with no text it takes what stands ready
        ;; on stdin and never waits: an agent's shell leaves stdin open and empty.
        body (if (and close? (empty? (rest args))) (stdin-content) (body-from (rest args)))
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

(defn cmd-forward
  "A new thread to --to carrying thread ID, or only its message --msg (a
   position or a file name), after the caller's note. The note is optional:
   the trailing text, then stdin as for send, but nothing is waited for on a
   terminal. The files the forwarded messages use are copied under their own
   names, so the links in them keep working, and listed on the new message
   with any --attach; thread.md records `forwarded-from`."
  [opts args]
  (ensure-store!)
  (let [from (caller opts)
        to (or (:to opts) (fail "forward needs --to NAME"))
        _ (when-not (core/valid-name? to) (fail "bad recipient name:" (pr-str to)))
        _ (when (= from to) (fail "refusing to mail yourself (" from ")"))
        src (if (first args) (resolve-id (first args)) (fail "forward needs a thread: agent-mail forward ID --to NAME"))
        th (fold src)
        msgs (if (:msg opts) [(core/pick-message th (:msg opts))] (vec (:msgs th)))
        _ (when (empty? msgs) (fail "thread" (str "#" (:number th)) "has no messages"))
        note (if (seq (rest args)) (body-from (rest args)) (stdin-content))
        _ (check-attachments! (:attach opts))
        refs (filter #(fs/regular-file? (fs/path (thread-dir src) %)) (core/file-refs msgs))
        subject (or (:subject opts) (core/forward-subject (:subject th)))
        body (core/forward-body note (core/forward-text th msgs))
        created (core/now-utc)]
    (locked
     (fn []
       (let [id (core/new-id subject created (thread-ids))
             number (next-number)]
         (fs/create-dirs (thread-dir id))
         (spit (str (fs/path (thread-dir id) "thread.md"))
               (str (core/fm-str {:subject subject :from from :created created :number number :forwarded-from src}) "\n"))
         (let [copied (attach! id (map #(fs/path (thread-dir src) %) refs))
               own (attach! id (:attach opts))]
           (write-msg! id {:from from :to to :date created :attachments (vec (distinct (concat copied own))) :body body}))
         (commit! (str "↪️ " from " → " to ": " subject))
         (println id)
         (delivered! from to id subject))))))

(defn files-dir [id] (str (fs/path (thread-dir id) "files")))
(defn render-thread
  "The thread as Markdown; a forward's header names its source by number too."
  ([th] (render-thread th (:msgs th)))
  ([th msgs]
   (let [src (:forwarded-from th)
         n (when src (try (:number (fold src)) (catch Exception _ nil)))]
     (core/render-thread (cond-> th n (assoc :forwarded-from-number n)) (files-dir (:id th)) msgs))))

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

(defn cmd-list [opts]
  (ensure-numbers!)
  (let [me (caller opts)
        ths (if (:all opts) (all-threads) (open-for me))
        ths (map #(assoc % :unread (count (unread % me))) (sort-by :updated ths))]
    (if (:json opts)
      (println (json/generate-string (map core/summary ths) {:pretty true}))
      (doseq [t ths]
        (println (format "#%-3d %-34s %-14s %-5s %2d msgs %s  %s  %s"
                         (:number t) (:id t) (str (:last-from t) "→" (:owner t)) (:status t) (:count t)
                         (if (pos? (:unread t)) (format "%d new" (:unread t)) "     ")
                         (core/local-time (:updated t)) (:subject t)))))))

(defn tab-title!
  "Titles the terminal tab when the command runs in a WezTerm pane, so listener
   tabs show their repo and whether mail is in: an explicit tab title sticks
   over the title Claude Code keeps rewriting. Silent elsewhere or on failure."
  [title]
  (when (wezterm-pane)
    (wez! "set-tab-title" title)))

(defn session-socket
  "The inbox of the Claude Code session running this command: Claude Code
   exports its unix socket as CLAUDE_CODE_MESSAGING_SOCKET to the commands it
   runs. nil when unset or when nothing is at that path."
  []
  (let [s (System/getenv "CLAUDE_CODE_MESSAGING_SOCKET")]
    (when (and (not (str/blank? s)) (fs/exists? s)) s)))

(defn post-to-session!
  "Posts `text` into the session behind unix socket `path` as a user message:
   an idle session starts a turn with it, a busy one reads it between tool
   calls. The connection opens with an auth line carrying
   CLAUDE_CODE_MESSAGING_TOKEN when that is set (optional on macOS and Linux,
   but it lets the session verify its own child). True once written; false on
   any failure. Never prints anything, so the token cannot leak."
  [path text]
  (try
    (with-open [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
      (.connect ch (UnixDomainSocketAddress/of ^String path))
      (let [token (System/getenv "CLAUDE_CODE_MESSAGING_TOKEN")
            lines (cond->> [{:type "user" :message {:role "user" :content text}}]
                    (not (str/blank? token)) (cons {:type "auth" :token token}))
            buf (ByteBuffer/wrap (.getBytes (str/join (map #(str (json/generate-string %) "\n") lines))
                                            StandardCharsets/UTF_8))]
        (while (.hasRemaining buf) (.write ch buf))
        true))
    (catch Exception _ false)))

(def skill-timeout
  "The --timeout the listen skill arms wait with in a Claude Code session: a
   safety net, not a cadence, since mail is posted into the session as it
   comes. A shorter one, or --session, is the shape of the skill before
   1 Nov 2026, whose loop re-armed hourly; a running listener never hears
   that the skill changed, so wait tells it."
  21600)
(defn old-shape-notice [opts sock]
  (when (and sock (or (:session opts) (some-> (:timeout opts) (< skill-timeout))))
    (str "agent-mail: the listen skill changed: arm with `agent-mail wait --timeout " skill-timeout
         "` (no --session; mail is posted into this session as it comes) and re-read /agent-mail-listen before re-arming")))

(defn cmd-wait
  "Waits for threads that are `me`'s turn. Classic mode (no session socket, or
   --once) prints their ids and exits 0. Socket mode keeps running and posts
   each thread into the session instead, again only when a newer message
   arrives; a failed post falls back to the classic exit, so the session is
   never left unreachable. Either way exit 3 on --timeout, duty \"idle\".
   The duty record names the session by its live name, read again every
   round (see session-name), so neither a restart nor a rename leaves a name
   nobody answers to; --session names it only where there is no registry.
   Socket mode puts the repo on call the first time (see auto-register!),
   and ends, removing its record, once the session it posts to is gone (a
   tab serve closed): a wait outliving its session would keep the repo on
   duty with nobody to answer."
  [opts]
  (ensure-store!)
  (let [me (caller opts)
        given (:session opts)
        fallback (str "pid" (.pid (java.lang.ProcessHandle/current)))
        interval (or (:interval opts) 3)
        deadline (when-let [t (:timeout opts)] (+ (System/currentTimeMillis) (* 1000 t)))
        sock (when-not (:once opts) (session-socket))
        notice (old-shape-notice opts sock)
        notice! #(when notice (binding [*out* *err*] (println notice)))
        print-ids! (fn [pending] (run! println (map :id pending)) (System/exit 0))]
    (notice!)
    (when sock (auto-register! me))
    (tab-title! (str "📪 " me))
    (loop [announced {}, titled-mail? false, prev-session nil, warned? false]
      (let [live (session-name)
            session (or live given fallback)
            mismatch? (boolean (and live given (not= live given)))
            pending (open-for me)
            mail? (boolean (seq pending))
            {:keys [fresh] :as next-state} (core/announce announced pending)]
        (when (and sock (claude-pid) (not (pid-alive? (claude-pid))))
          (fs/delete-if-exists (duty-file me (or prev-session session)))
          (System/exit 3))
        (when (and mismatch? (not warned?))
          (binding [*out* *err*]
            (println (str "agent-mail: this session is named " (pr-str live) ", not " (pr-str given)
                          "; using the live name"))))
        (when (and prev-session (not= prev-session session))
          (fs/delete-if-exists (duty-file me prev-session)))
        (write-duty! me session (if mail? "working" "waiting"))
        (when (not= titled-mail? mail?) (tab-title! (str (if mail? "📬 " "📪 ") me)))
        (cond
          (and mail? (not sock)) (print-ids! pending)
          (and (seq fresh) (not (post-to-session! sock (core/announcement me fresh))))
          (do (binding [*out* *err*]
                (println "agent-mail: could not post to this session's socket; printing the thread ids instead"))
              (print-ids! pending)))
        (if (and deadline (>= (System/currentTimeMillis) deadline))
          (do (write-duty! me session "idle") (notice!) (System/exit 3))
          (do (Thread/sleep (* 1000 interval))
              (recur (:announced next-state) mail? session (or warned? mismatch?))))))))

(defn cmd-who
  "The duty records, then the repos on call with what serve is doing about
   each (--json: the duty records alone, as status gives them)."
  [opts]
  (let [recs (duty-view)]
    (if (:json opts)
      (println (json/generate-string recs {:pretty true}))
      (let [oncall (oncall-view recs)]
        (doseq [r recs]
          (println (format "%-20s %-28s %-8s %5ds ago%s" (:repo r) (:session r) (:state r) (:age-s r)
                           (cond (false? (:session-alive r)) "  (session gone)"
                                 (and (:stale r) (= "waiting" (:state r))) "  (stale)"
                                 (and (:stale r) (:on-duty r)) "  (session alive)"
                                 :else ""))))
        (doseq [o oncall]
          (println (format "%-20s %-28s %s" (:repo o) "(on call)" (:text o))))
        (when (and (seq oncall) (not (:alive (serve-info))))
          (println (serve-line)))))))

(defn cmd-status
  "Everything at once, for UIs (a menu bar app calls it on every refresh): all
   threads with per-reader unread counts, waiting time and listener, the duty
   records, the open threads nobody is listening for, the repos on call and
   whether serve runs. A pure read: no seen marker moves, nothing is written
   or committed (not even the thread numbering that list and read catch up on)."
  [opts]
  (let [me (caller opts)
        threads (all-threads)
        readers (into #{me} (mapcat core/participants) threads)
        duty (duty-view)
        st (core/status {:now (core/now-utc) :me me :threads threads
                         :seen (into {} (map (juxt identity seen-map)) readers)
                         :duty duty :oncall (oncall-view duty) :serve (serve-info)})]
    (if (:json opts)
      (println (json/generate-string st {:pretty true}))
      (let [number-of (into {} (map (juxt :id :number)) (:threads st))
            owner-of (into {} (map (juxt :id :owner)) (:threads st))
            listening (distinct (map :repo (filter :on-duty (:duty st))))]
        (doseq [t (:threads st)]
          (println (format "#%-3d %-24s %-5s %-6s %-8s %-6s %s"
                           (:number t) (str (:last-from t) "→" (:owner t)) (:status t)
                           (if (:waiting-s t) (core/duration (:waiting-s t)) "")
                           (or (:listener t) "")
                           (if (pos? (:unread t)) (str (:unread t) " new") "")
                           (:subject t))))
        (println (str "on duty: " (if (seq listening) (str/join ", " listening) "nobody")))
        (when-let [oc (seq (:oncall st))]
          (println (str "on call: " (str/join ", " (map #(str (:repo %) (when (not= "on-call" (:state %)) (str " (" (:text %) ")"))) oc))
                        (when-not (:alive (:serve st)) " (serve isn't running)"))))
        (println (str "undelivered: " (if-let [u (seq (:undelivered st))]
                                        (str/join ", " (map #(str "#" (number-of %) " " (owner-of %)) u))
                                        "none")))))))

(defn cmd-oncall
  "Lists the repos on call (--json: oncall-view's entries); `add [NAME DIR]`
   registers one, by default the caller's repo at its main worktree (from the
   directory, whoever the caller is: the user registers repos too); `remove
   [NAME]` takes one off and keeps it off, since wait would otherwise put it
   back."
  [opts [sub & args]]
  (case sub
    "add" (let [[nm dir] (cond (second args) [(first args) (fs/normalize (fs/absolutize (second args)))]
                               (first args) (fail "oncall add takes NAME and DIR, or neither for this repo")
                               :else [(or (repo-name) (fail "not in a git repo; give NAME DIR")) (repo-root)])]
            (when-not (core/valid-name? nm) (fail "bad name:" (pr-str nm)))
            (when-not (fs/directory? dir) (fail "no such directory:" (str dir)))
            (register-oncall! nm dir)
            (println (str nm " is on call (" dir ")"))
            (when-not (:alive (serve-info)) (println (serve-line))))
    "remove" (let [nm (or (first args) (repo-name) (fail "not in a git repo; give NAME"))
                   e (get (oncall-entries) nm)]
               (when-not (core/valid-name? nm) (fail "bad name:" (pr-str nm)))
               (spit-atomic! (oncall-file nm) (pr-str (assoc (or e {:repo nm}) :off true :removed (core/now-utc))))
               (println (if (and e (not (:off e)))
                          (str nm " is no longer on call; wait won't put it back (`oncall add` does)")
                          (str nm " was not on call; wait won't put it on call either"))))
    nil (let [v (oncall-view)]
          (if (:json opts)
            (println (json/generate-string v {:pretty true}))
            (do (if (seq v)
                  (doseq [o v] (println (format "%-20s %-40s %s" (:repo o) (:dir o) (:text o))))
                  (println "nothing on call"))
                (println (serve-line)))))
    (fail "unknown oncall command" sub "(add or remove)")))

;; ---------------------------------------------------------------- serve

(def serve-idle-minutes
  "How long serve leaves a listener tab it opened idle before closing it
   (--idle-minutes). Claude Code's prompt cache lives one hour from the
   session's last request, which is when the registry's statusUpdatedAt last
   turned \"idle\": a session kept that long answers its next mail from the
   cache, while one kept longer gains nothing over a fresh session, since its
   next turn re-reads the whole context uncached."
  60)

(defn log!
  "One line on stderr with the local time in front; under launchd it goes to
   the log file."
  [& msg]
  (binding [*out* *err*]
    (println (str (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))
                  " " (str/join " " msg)))))

(defn login-shell
  "The shell a listener tab runs Claude Code from, so PATH is a normal tab's:
   $SHELL, else the account's login shell (launchd may set no SHELL), else sh."
  []
  (let [s (System/getenv "SHELL")
        user (System/getProperty "user.name")
        look (fn [& cmd] (try (:out (apply p/shell {:out :string :err :string :continue true} cmd))
                              (catch Exception _ nil)))]
    (or (when-not (str/blank? s) s)
        (if macos?
          (some->> (look "dscl" "." "-read" (str "/Users/" user) "UserShell") (re-find #"UserShell:\s*(\S+)") second)
          (some-> (look "getent" "passwd" user) str/trim (str/split #":") (get 6) not-empty))
        "/bin/sh")))

(defn- idle-ns [client]
  (let [t (get client "idle_time")] (+ (* 1000000000 (or (get t "secs") 0)) (or (get t "nanos") 0))))

(defn spawn-tab!
  "Opens a listener tab for `repo` in `dir`: in the window of the pane the
   user last focused, giving that pane its focus back, or in a new window
   when there is none. {:pane :window :gui-pid}, or {:error} saying why not."
  [repo dir]
  (cond
    (not (and dir (fs/directory? dir))) {:error (str "its directory is gone (" dir ")")}
    (nil? (wezterm-bin)) {:error "wezterm not found (AGENT_MAIL_WEZTERM names it)"}
    :else
    (let [client (first (sort-by idle-ns (wez-json "list-clients")))
          focused (get client "focused_pane_id")
          window (when focused (some #(when (= focused (get % "pane_id")) (get % "window_id")) (wez-json "list")))
          r (apply wez! "spawn" (concat (if window ["--window-id" window] ["--new-window"])
                                        ["--cwd" dir "--" (login-shell) "-lic" (core/listener-command repo)]))
          pane (when (zero? (:exit r)) (some-> (:out r) str/trim parse-long))]
      (if pane
        (do (when window (wez! "activate-pane" "--pane-id" focused))
            {:pane pane :window window :gui-pid (get client "pid")})
        {:error (str "wezterm spawn failed" (when-let [e (not-empty (str/trim (str (:err r))))] (str ": " (core/first-line e))))}))))

(defn carry-out!
  "Carries out one of core/oncall-actions, logs it, and returns serve's
   memory after it. A spawn that fails backs off; a close that fails is
   tried again next round."
  [memory [kind repo & more :as action] now oncall]
  (let [m (get memory repo {})
        backed-off (fn [[_ _ until reason :as a]]
                     (log! (str "back off " repo " until " (core/clock until) ": " reason))
                     (core/remember memory a now))]
    (case kind
      :spawn (let [dir (get-in oncall [repo :dir])
                   {:keys [pane window gui-pid error]} (spawn-tab! repo dir)]
               (if error
                 (backed-off (core/back-off repo m now error))
                 (do (log! (str "spawn " repo ": pane " pane (if window (str " in window " window) " in a new window") ", " dir))
                     (core/remember memory [:spawned repo pane gui-pid] now))))
      :close (let [[pane] more
                   r (wez! "kill-pane" "--pane-id" pane)]
               (if (zero? (:exit r))
                 (do (doseq [[f rec] (duty-files) :when (and (= repo (:repo rec)) (= pane (:pane rec)))]
                       (fs/delete-if-exists f))
                     (log! (str "close " repo ": pane " pane ", its session idle with nothing waiting"))
                     (core/remember memory action now))
                 (do (log! (str "close " repo ": pane " pane " failed: " (core/first-line (str (:err r)))))
                     memory)))
      :forget (do (log! (str "forget " repo ": pane " (:pane m) " is gone")) (core/remember memory action now))
      :reset (do (log! (str "on duty: " repo)) (core/remember memory action now))
      :back-off (backed-off action)
      :drop (do (log! (str "drop " repo ": no longer on call")) (core/remember memory action now)))))

(defn serve-round!
  "One round: gathers what core/oncall-actions decides on, carries out its
   actions and returns serve's memory after them. Reads no thread and asks
   WezTerm nothing while nothing is on call and no tab is open."
  [memory {:keys [idle-ms grace-ms]}]
  (let [oncall (oncall-registered)]
    (if (and (empty? oncall) (empty? memory))
      memory
      (let [now (System/currentTimeMillis)
            duty (duty-view)
            tabs? (some :pane (vals memory))
            listed (when tabs? (wez-json "list"))
            clients (when tabs? (wez-json "list-clients"))
            actions (core/oncall-actions
                     {:now now :oncall oncall :memory memory :duty duty
                      :pending (set (keep #(when (= "open" (:status %)) (:owner %)) (all-threads)))
                      :panes (when listed (set (keep #(get % "pane_id") listed)))
                      :gui-pid (get (first (sort-by idle-ns clients)) "pid")
                      :sessions (sessions-of duty) :idle-ms idle-ms :grace-ms grace-ms})]
        (reduce #(carry-out! %1 %2 now oncall) memory actions)))))

(defn write-serve-state! [memory interval]
  (spit-atomic! serve-state-file
                (pr-str {:pid (.pid (java.lang.ProcessHandle/current)) :heartbeat (core/now-utc)
                         :interval interval :repos memory})))

(defn claim-serve!
  "Makes this process the one serve: refuses while another is alive (see
   serve-info), takes over from a dead one. Under the store lock, so two
   started at once cannot both win."
  [interval]
  (fs/create-dirs serve-dir)
  (locked (fn []
            (let [me (.pid (java.lang.ProcessHandle/current))
                  {:keys [alive pid]} (serve-info)]
              (when (and alive (not= pid me))
                (fail (str "serve is already running (pid " pid ")")))
              (spit (str serve-pid-file) (str me "\n"))
              (write-serve-state! (or (:repos (serve-state)) {}) interval)))))

(defn release-serve!
  "Removes the pid file if it is still this process's."
  []
  (when (= (serve-pid) (.pid (java.lang.ProcessHandle/current)))
    (fs/delete-if-exists serve-pid-file)))

(defn cmd-serve
  "The on-call daemon (see core/oncall-actions): a round every --interval
   seconds (5), closing tabs idle for --idle-minutes (see serve-idle-minutes);
   --once runs one round. Its memory and heartbeat go to duty/serve/state.edn
   after every round. Exits when another serve has taken the pid file over.
   Another serve alive refuses this one, except with --standby (launchd's):
   then it waits for that one to end and takes over, rather than exiting
   for launchd to restart every few seconds."
  [opts]
  (let [interval (or (:interval opts) 5)
        idle-minutes (or (:idle-minutes opts) serve-idle-minutes)
        cfg {:idle-ms (* 60000 idle-minutes) :grace-ms core/oncall-grace-ms}
        me (.pid (java.lang.ProcessHandle/current))]
    (when (< interval 1) (fail "--interval must be at least 1"))
    (when (:standby opts)
      (loop [said? false]
        (let [{:keys [alive pid]} (serve-info)]
          (when (and alive (not= pid me))
            (when-not said? (log! (str "serve: standing by while serve pid " pid " runs")))
            (Thread/sleep (* 1000 interval))
            (recur true)))))
    (claim-serve! interval)
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable release-serve!))
    (log! (str "serve: pid " me ", a round every " interval "s, idle tabs closed after " idle-minutes "m"))
    (loop [memory (or (:repos (serve-state)) {})]
      (when-not (= me (serve-pid))
        (log! (str "serve: pid " (serve-pid) " took over; exiting"))
        (System/exit 0))
      (let [memory (try (serve-round! memory cfg)
                        (catch Exception e (log! "round failed:" (ex-message e)) memory))]
        (write-serve-state! memory interval)
        (when-not (:once opts)
          (Thread/sleep (* 1000 interval))
          (recur memory))))
    (release-serve!)))

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

  agent-mail [--as ME] @NAME text… [< body.md]
                                              new thread to NAME (subject = first line; a
                                              file or pipe on stdin follows the text as body)
  agent-mail send --to NAME --subject S [--attach FILE]… [text…] [< body.md]
  agent-mail reply ID [--to NAME] [--attach FILE]… [text…] [< body.md]
  agent-mail reply ID [--edit]                no text on a terminal: what's new opens quoted in
                                              your editor; what you write (inline or at the end)
                                              is sent when you close it
  agent-mail close ID [text…] [< note.md]     marks the thread done; the note is optional and
                                              nothing is waited for on stdin
  agent-mail forward ID --to NAME [--msg N] [--subject S] [--attach FILE]… [text…] [< note.md]
                                              new thread to NAME: your note (optional), then
                                              the whole thread, or only its message N (a
                                              position or file name), with its attachments
  (open, reply, read and close without ID offer a picker: fzf when installed)
  agent-mail list [--all] [--json]            open threads whose turn is mine
  agent-mail read ID [--json]                 the whole thread; ID is the number from `list`
                                              (7 or #7), the full id, or a unique fragment
  agent-mail open ID                          open what's new to you in your Markdown app
  agent-mail wait [--session NAME] [--timeout S] [--interval S] [--once]
                                              waits for threads whose turn is mine. In a Claude
                                              Code session it keeps running and posts each one
                                              into the session, again only on a newer message;
                                              elsewhere, with --once, or if a post fails, it
                                              prints their ids and exits. Exit 3 on timeout.
                                              --session NAME is needed only outside Claude
                                              Code; inside, wait reads the session's live name
                                              from Claude Code's session registry, and a
                                              --timeout under 21600 (the listen skill's) earns
                                              a note on stderr: that is the old hourly loop
  agent-mail who [--json]                     who is on duty, and who is on call
  agent-mail oncall [--json]                  repos on call: when mail waits on one and nobody
                                              is on duty, serve opens a listener tab for it
  agent-mail oncall add [NAME DIR]            put a repo on call; no arguments: this repo, at
                                              its main worktree. wait in a Claude Code session
                                              does this for its repo the first time
  agent-mail oncall remove [NAME]             take it off; wait won't put it back
  agent-mail serve [--interval S] [--idle-minutes M] [--once] [--standby]
                                              the on-call daemon (`just install-serve` runs it
                                              under launchd): every S (5) seconds it opens a
                                              WezTerm tab running `claude \"/agent-mail-listen\"`
                                              for an on-call repo that has mail and nobody on
                                              duty, and closes a tab it opened once nothing
                                              waits and its session has been idle M (60)
                                              minutes: the prompt cache lives an hour, so a
                                              session kept longer answers no cheaper than a
                                              fresh one. One serve at a time; --standby
                                              (launchd's) waits for a running one to end
  agent-mail status [--json]                  everything at once, for UIs
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
wait finds its session through CLAUDE_CODE_MESSAGING_SOCKET (and _TOKEN),
which Claude Code exports to the commands it runs, and the session's name in
<config>/sessions/$CLAUDE_PID.json (CLAUDE_CONFIG_DIR, else ~/.claude).
serve runs AGENT_MAIL_WEZTERM, else wezterm on PATH, else WezTerm.app's own.
Store: $AGENT_MAIL_DIR, else ~/.local/share/agent-mail.")

(defn -main [argv]
  (try
    (let [{:keys [args opts]} (core/expand-shorthand (core/parse-argv argv))
          [cmd & args] args]
      (case cmd
        "send" (cmd-send opts args)
        "reply" (cmd-reply opts args {})
        "close" (cmd-reply opts args {:close? true})
        "forward" (cmd-forward opts args)
        "read" (cmd-read opts args)
        "open" (cmd-open opts args)
        "list" (cmd-list opts)
        "wait" (cmd-wait opts)
        "who" (cmd-who opts)
        "oncall" (cmd-oncall opts args)
        "serve" (cmd-serve opts)
        "status" (cmd-status opts)
        "hook" (cmd-hook opts)
        "whoami" (println (caller opts))
        (nil "help") (println help)
        (fail "unknown command" cmd "(try `agent-mail help`)")))
    (catch clojure.lang.ExceptionInfo e
      (when-not (core/usage? e) (throw e))
      (binding [*out* *err*] (println (str "agent-mail: " (ex-message e))))
      (System/exit 1))))

(-main *command-line-args*)
