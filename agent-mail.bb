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

(def git-redirects
  "The variables that point git at another repository than the folder it
   runs in. Git exports them to hooks and to `git rebase --exec`, so a
   command run from one would otherwise commit the store's mail there."
  #{"GIT_DIR" "GIT_WORK_TREE" "GIT_INDEX_FILE" "GIT_COMMON_DIR" "GIT_OBJECT_DIRECTORY"
    "GIT_ALTERNATE_OBJECT_DIRECTORIES" "GIT_NAMESPACE" "GIT_PREFIX"})

(defn git-env
  "This process's environment without git-redirects."
  []
  (into {} (remove (comp git-redirects key)) (System/getenv)))

(defn git [& args]
  (apply p/shell {:dir (str store) :env (git-env) :out :string :err :string :continue true} "git" args))

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

(declare ensure-numbers! migrate-directory!)

(defn real-dir
  "Directory `d` with symbolic links resolved, as Claude Code records a
   session's folder and the agent directory keeps it; `d` itself when it
   does not resolve."
  [d]
  (try (str (fs/real-path d)) (catch Exception _ (str d))))

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
  (migrate-directory!)
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
(defn news-for
  "Closed threads with news for `who` (see core/closed-news?): a reply closed
   straight after it was sent is never `who`'s turn, so open-for misses it."
  [who]
  (let [seen (seen-map who)]
    (filter #(core/closed-news? % who (get seen (:id %))) (all-threads))))
(defn mail-for "What waits on `who`: open threads whose turn it is, then closed ones with news."
  [who] (concat (open-for who) (news-for who)))

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

(defn writer-session
  "The Claude Code session writing a message from `from`: its
   CLAUDE_CODE_SESSION_ID, which Claude Code exports to the commands it
   runs, so the thread's later messages can be posted into it. nil for the
   user and outside Claude Code."
  [from]
  (let [s (System/getenv "CLAUDE_CODE_SESSION_ID")]
    (when (and (not= "user" from) (not (str/blank? s))) (str/trim s))))

(defn write-msg!
  "Writes one message file into thread `id`, in one move, and returns its
   name. A message written from a Claude Code session records it as
   `session` (see writer-session)."
  [id {:keys [from to date in-reply-to status attachments body]}]
  (let [dir (fs/path (thread-dir id) "msgs")
        last-date (some-> (seq (msg-files id)) last read-msg :date)
        date (core/after (or date (core/now-utc)) last-date)
        nm (str (core/stamp->name date) "__" from "__" (format "%04x" (rand-int 65536)) ".md")
        tmp (fs/path dir (str "." nm ".tmp"))
        session (writer-session from)]
    (fs/create-dirs dir)
    (spit (str tmp)
          (str (core/fm-str (cond-> {:from from :to to :date date}
                              in-reply-to (assoc :in-reply-to in-reply-to)
                              status (assoc :status status)
                              (seq attachments) (assoc :attachments attachments)
                              session (assoc :session session)))
               "\n" (str/trim body) "\n"))
    (fs/move tmp (fs/path dir nm))
    nm))

;; ---------------------------------------------------------------- duty

(defn agent-session?
  "True inside a coding agent's shell. Claude Code (2.1.172+) sets
   CLAUDE_CODE_CHILD_SESSION in the processes it launches itself (Bash, hooks,
   status line) and nowhere else. The older CLAUDECODE is deliberately not
   used: IDE extensions set it in their integrated terminals too, where it is
   the user typing."
  []
  (not (str/blank? (System/getenv "CLAUDE_CODE_CHILD_SESSION"))))

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

(defn claude-config
  "Claude Code's config directory: CLAUDE_CONFIG_DIR, else ~/.claude."
  []
  (let [d (System/getenv "CLAUDE_CONFIG_DIR")]
    (if (str/blank? d) (fs/path (fs/home) ".claude") (fs/path d))))

(defn registry-entry
  "Claude Code's session registry entry for process id `pid`, a map with
   string keys: <config>/sessions/<pid>.json (see claude-config). nil when
   there is no such file or it does not parse; never throws."
  [pid]
  (try
    (let [f (fs/path (claude-config) "sessions" (str pid ".json"))
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
   from a dead one; the directory it runs in; and its WezTerm pane. Written
   in one move: serve must never read half a record and take the repo for
   off duty, and deliver its threads under the listener's feet."
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

;; ---------------------------------------------------------------- the agent directory
;; One entry per agent, <store>/agents/<name>.md (see core), committed like
;; the mail: the only source of agent names, each mapped to its folder.

(def agents-dir (fs/path store "agents"))
(defn agent-file [nm] (fs/path agents-dir (str nm ".md")))

(def oncall-dir
  "Where the on-call registrations lived before the directory, one
   <repo>.edn each ({:repo :dir}, :off after `oncall remove`): the
   directory takes them over once (migrate-directory!) and leaves them in
   place, still readable."
  (fs/path duty-dir "oncall"))

(defn note
  "One line on stderr, after `agent-mail: `."
  [& msg]
  (binding [*out* *err*] (println (str "agent-mail: " (str/join msg)))))

(defn file-id
  "Folder `dir`'s device and inode, e.g. 16777234:44201492, which stay the
   same when the folder is moved or renamed; nil when it does not exist."
  [dir]
  (try (let [p (fs/path dir) no-opts (make-array java.nio.file.LinkOption 0)]
         (when (fs/directory? p)
           (str (java.nio.file.Files/getAttribute p "unix:dev" no-opts) ":"
                (java.nio.file.Files/getAttribute p "unix:ino" no-opts))))
       (catch Exception _ nil)))

(defn legacy-registrations
  "The on-call registrations (see oncall-dir), removed ones included."
  []
  (when (fs/exists? oncall-dir)
    (keep #(let [e (read-edn %)] (when (string? (:repo e)) e)) (fs/glob oncall-dir "*.edn"))))

(defn migrated-entries
  "The entries the on-call registrations become (core/entry-from-registration),
   their folders resolved; names the directory doesn't take are left out."
  []
  (let [now (core/now-utc)]
    (vec (for [r (sort-by :repo (legacy-registrations))
               :when (core/agent-name? (:repo r))
               :let [e (core/entry-from-registration r now)]]
           (cond-> e (:dir e) (assoc :dir (real-dir (:dir e)) :file-id (file-id (:dir e))))))))

(defn read-entry [f]
  (try (core/parse-entry (str/replace (str (fs/file-name f)) #"\.md$" "") (slurp (str f)))
       (catch Exception _ nil)))

(defn directory
  "Every agent, name -> entry (core/parse-entry), by name. Before the store
   has its agents folder, the on-call registrations as migrate-directory!
   will write them, so a read (status) never has to write."
  []
  (into (sorted-map) (map (juxt :name identity))
        (if (fs/directory? agents-dir)
          (keep read-entry (fs/glob agents-dir "*.md"))
          (migrated-entries))))

(defn migrate-directory!
  "Gives the store its agents folder the first time: the on-call
   registrations become entries, written aside and moved in at once, so no
   reader sees half a directory, and committed."
  []
  (when-not (fs/directory? agents-dir)
    (locked
     (fn []
       (when-not (fs/directory? agents-dir)
         (let [es (migrated-entries)
               tmp (fs/path store (str ".agents." (.pid (java.lang.ProcessHandle/current)) ".tmp"))]
           (fs/delete-tree tmp)
           (fs/create-dirs tmp)
           (doseq [e es] (spit (str (fs/path tmp (str (:name e) ".md"))) (core/entry-doc e)))
           (fs/move tmp agents-dir {:atomic-move true})
           (when (seq es)
             (commit! (str "📇 agents: the directory takes over " (count es) " on-call registration"
                           (when (< 1 (count es)) "s"))))))))))

(defn directory!
  "Changes the directory under the store lock. `f` gets it as it stands
   (name -> entry) and returns nil, or [commit-message changes], changes
   mapping names to the entries to write, nil deleting one; a refusal it
   throws changes nothing. Returns the changes."
  [f]
  (ensure-store!)
  (locked
   (fn []
     (when-let [[msg changes] (f (directory))]
       (doseq [[nm e] changes]
         (if e (spit-atomic! (agent-file nm) (core/entry-doc e)) (fs/delete-if-exists (agent-file nm))))
       (when (seq changes) (commit! msg))
       changes))))

(defn new-entry
  "A new entry for agent `nm` in folder `dir`."
  [nm dir oncall description]
  {:name nm :dir (str dir) :file-id (file-id dir) :oncall oncall :kind "claude" :added (core/now-utc)
   :description description})

(defn entry-at
  "The entry of `d` (name -> entry) whose folder is `folder`, other than
   `but`; nil when none."
  [d folder but]
  (some #(when (and (not= but (:name %)) (core/same-path? (:dir %) folder)) %) (vals d)))

(defn home-dir "The user's home folder, its real path." [] (real-dir (fs/home)))

(defn refuse-broad!
  "Refuses `folder` as an agent's when it is `/` or the home folder (see
   core/broad-folder?)."
  [folder]
  (when (core/broad-folder? folder (home-dir))
    (fail (str "refusing " folder " for an agent: " (if (core/same-path? folder "/") "/" "the home folder")
               " is too broad, as every folder below it without an entry of its own would be that agent"))))

(defn enclosure-note
  "The stderr line for `folder`, now agent `nm`'s, when it encloses folders
   other entries of `d` have; nil when it encloses none."
  [d folder nm]
  (when-let [ns (seq (core/enclosed-agents (vals d) folder nm))]
    (str folder " encloses the folders of " (core/word-list ns "and") "; they keep their names,"
         " and every folder below it without an entry of its own is now " nm "'s")))

(defn own-or-user!
  "Refuses a change by agent `me` to another agent's entry: an agent
   `verbs` only itself; `user` may `verb` any agent."
  [me [verbs verb] nm]
  (when-not (or (= "user" me) (= nm me))
    (fail (str "an agent " verbs " only itself: you are " me ", not " nm " (the user may " verb " any agent)"))))

(defn oncall-agents
  "The agents serve delivers for: those on call in the directory, kind
   claude, name -> {:dir :described}."
  ([] (oncall-agents (directory)))
  ([d] (into (sorted-map) (for [[nm e] d :when (and (:oncall e) (= "claude" (:kind e)))]
                            [nm {:dir (:dir e) :described (boolean (:description e))}]))))

;; ---- what Claude Code says of a folder

(defn claude-json-file
  "Claude Code's settings file: .claude.json in CLAUDE_CONFIG_DIR, else in
   the home folder."
  []
  (let [d (System/getenv "CLAUDE_CONFIG_DIR")]
    (fs/path (if (str/blank? d) (fs/home) d) ".claude.json")))

(defn trust-of
  "A function telling whether Claude Code trusts a folder, which `claude
   --bg` needs, from the projects entries' hasTrustDialogAccepted in
   .claude.json (claude-json-file): the folder's own, else a folder above
   it, short of a git repo's root (see core/trusted?). nil when that can't
   be read; never throws."
  []
  (let [projects (try (let [f (claude-json-file)]
                        (when (fs/regular-file? f) (get (json/parse-string (slurp (str f))) "projects")))
                      (catch Exception _ nil))
        accepted? (fn [d] (let [e (get projects d)] (true? (and (map? e) (get e "hasTrustDialogAccepted")))))]
    (fn [dir]
      (when (and (map? projects) dir)
        (or (accepted? (str dir))
            (core/trusted? (core/folder-chain (real-dir dir)) accepted? #(fs/exists? (fs/path % ".git"))))))))

(defn trust-line
  "Why serve can't start sessions for on-call entry `e`, when Claude Code
   hasn't trusted its folder (`trust`, see trust-of); nil when it has, or
   when that is unknown."
  [e trust]
  (when (and (:oncall e) (:dir e) (fs/directory? (:dir e)) (false? (trust (:dir e))))
    (str "Claude Code hasn't trusted " (:dir e) " yet, so serve can't start sessions there:"
         " run `claude` in it once and accept the question")))

(defn borrowed-summary
  "[paragraph file] for folder `dir` without a description (see
   core/borrowed-summary): the first paragraph of its CLAUDE.md, or of the
   file that one imports, else of its README.md; nil when none has one."
  [dir]
  (core/borrowed-summary (fn [rel] (let [p (fs/path dir rel)]
                                     (try (when (fs/regular-file? p) (slurp (str p))) (catch Exception _ nil))))))

(defn agents-view
  "Each agent as `agents` and `status` show it (core/agent-view), by name;
   with `full?` its description, file id and when it was added too."
  ([] (agents-view (directory) false))
  ([d full?]
   (let [trust (trust-of)]
     (vec (for [[_ e] d
                :let [exists (boolean (and (:dir e) (fs/directory? (:dir e))))
                      v (core/agent-view e {:exists exists
                                            :trusted (when exists (trust (:dir e)))
                                            :borrowed (when (and exists (not (core/description-summary (:description e))))
                                                        (borrowed-summary (:dir e)))})]]
            (cond-> v full? (assoc :description (:description e) :file-id (:file-id e) :added (:added e))))))))

;; ---------------------------------------------------------------- identity

(defn git-roots
  "For folder `dir`, inside git: {:top :main}, the real paths of its
   worktree and of the main worktree (a linked worktree's main is the
   parent of the common git dir; a submodule is its own); nil outside git."
  [dir]
  (let [r (try (p/shell {:dir (str dir) :env (git-env) :out :string :err :string :continue true}
                        "git" "rev-parse" "--path-format=absolute" "--show-toplevel" "--git-dir" "--git-common-dir")
               (catch Exception _ nil))]
    (when (and r (zero? (:exit r)))
      (let [[top git-dir common] (map str/trim (str/split-lines (:out r)))]
        (when (and (not (str/blank? top)) common)
          {:top (real-dir top) :main (real-dir (if (= git-dir common) top (str (fs/parent common))))})))))

(defn repo-root
  "The real path of the main working tree of the repo the command runs in,
   so a worktree still counts as its repo. nil outside any repo."
  []
  (:main (git-roots ".")))

(defn here
  "The folder the command runs in, as the on-call shorthands take it: the
   repo's main worktree (see repo-root), or outside any repo the directory
   itself with :plain true. :name is the address derived from the folder's
   name, as an agent there writes as before the directory knows it; nil
   when none can be."
  []
  (if-let [root (repo-root)]
    {:name (core/address-from-dir (fs/file-name root)) :dir root}
    (let [cwd (real-dir (fs/absolutize "."))]
      {:name (core/address-from-dir (fs/file-name cwd)) :dir cwd :plain true})))

(defn folder-place
  "Where folder `dir` stands for the directory: {:folder :git :main}.
   :folder is its real path, moved from a linked worktree to the same place
   in the main worktree; :main is that main worktree's root inside git."
  [dir]
  (let [d (real-dir (fs/absolutize dir))]
    (if-let [{:keys [top main]} (git-roots d)]
      {:folder (core/worktree-path d top main) :git true :main main}
      {:folder d :git false})))

(defn find-agent
  "[entry folder how] for place `pl` (folder-place) among `entries`: the
   nearest of its folder and those above it up to `/` (see
   core/find-entry); nil when none matches."
  [entries pl]
  (core/find-entry entries (core/folder-chain (:folder pl)) file-id))

(defn session-start-folder
  "The folder the Claude Code session running this command started in: its
   registry entry's cwd (see registry-entry); nil when unknown."
  []
  (when-let [pid (claude-pid)]
    (let [c (get (registry-entry pid) "cwd")]
      (when (and (string? c) (fs/directory? c)) (real-dir c)))))

(defn keep-current!
  "Brings entry `e`, found for folder `f` (`how` find-entry's), up to date:
   a folder found by its file id was moved there, so the entry's folder
   follows, said on stderr unless `quiet`; a folder found by its path gets
   its file id when that is missing or stale (the folder was made anew).
   Nothing when nothing changed; never fails."
  [e f how quiet]
  (try
    (let [id (file-id f)]
      (case how
        :moved (when (and id (not= id (file-id (:dir e))))
                 (when (directory! (fn [d] (when-let [cur (d (:name e))]
                                             (when (= id (:file-id cur))
                                               [(str "📇 agents: " (:name e) " moved to " f)
                                                {(:name e) (assoc cur :dir f)}]))))
                   (when-not quiet
                     (note "the folder of " (:name e) " moved from " (:dir e) " to " f "; the agent directory now says so"))))
        :path (when (and id (not= id (:file-id e)))
                (directory! (fn [d] (when-let [cur (d (:name e))]
                                      (when (core/same-path? (:dir cur) f)
                                        [(str "📇 agents: " (:name e) "'s folder id") {(:name e) (assoc cur :file-id id)}])))))
        nil))
    (catch Exception _ nil)))

(defn identify
  "The agent of folder `dir` (an agent's shell runs there): {:name :how
   :folder}. The directory decides (find-agent): the nearest entry from the
   folder up, so a repo nested in an agent's folder is that agent unless it
   has an entry of its own; :how :directory, the entry kept current
   (keep-current!, not when `pure`). With no entry, outside git, the folder
   the session started in is tried the same way: a shell that wandered
   into a scratchpad is still its session's agent. Otherwise :how
   :derived, the name derived from the folder as before the directory (the
   repo's main worktree's name, else the folder's own), with :folder what
   auto-add! adds, and a note on stderr outside git unless `quiet`."
  [dir {:keys [quiet pure]}]
  (let [entries (vals (directory))
        pl (folder-place dir)
        start (when-not (:git pl) (session-start-folder))
        spl (when (and start (not (core/same-path? start (:folder pl)))) (folder-place start))
        [e f how] (or (find-agent entries pl) (when spl (find-agent entries spl)))]
    (if e
      (do (when-not pure (keep-current! e f how quiet))
          {:name (:name e) :how :directory :folder f})
      (let [{:keys [folder git main]} (or spl pl)
            home (if git main folder)
            nm (core/address-from-dir (fs/file-name home))]
        (when-not quiet
          (cond spl (note "this folder is in no repo; acting as " (pr-str nm) ", after " home
                          ", where this session started (AGENT_MAIL_AS=NAME or --as NAME to choose)")
                (not git) (note "not in a git repo; acting as " (pr-str nm) " (AGENT_MAIL_AS=NAME or --as NAME to choose)")))
        {:name nm :how :derived :folder home}))))

(defn whoami
  "Who runs the command, {:name :how …}: --as or AGENT_MAIL_AS if set
   (:explicit); otherwise the user in the user's own shell (:user), and in
   an agent's shell (see agent-session?) its folder's agent (identify). An
   agent never becomes the user by accident, and the user never becomes a
   repo. Refuses a name that can't be one."
  [opts]
  (let [explicit (or (:as opts) (System/getenv "AGENT_MAIL_AS"))
        w (cond explicit {:name explicit :how :explicit}
                (not (agent-session?)) {:name "user" :how :user}
                :else (identify "." opts))]
    (when-not (core/valid-name? (:name w)) (fail "bad name for the caller:" (pr-str (:name w))))
    w))

(defn caller "Who runs the command, by name (see whoami)." [opts] (:name (whoami opts)))

(defn auto-add!
  "Puts the folder of `w` (whoami's), an agent the directory doesn't know
   yet, into the directory, on call, so mail to it can be delivered: one
   line on stderr says so unless `quiet`. A name the directory has for
   another folder is left as it is, and so is a folder too broad to add
   unasked: `/`, the home folder, or one enclosing other agents' folders.
   stderr says why. Returns the entry added, if any."
  [{:keys [name how folder]} quiet]
  (when (and (= :derived how) folder (core/agent-name? name) (fs/directory? folder))
    (let [why (atom nil)
          added (get (directory! (fn [d]
                                   (let [o (or (d name) (entry-at d folder nil))
                                         inside (core/enclosed-agents (vals d) folder name)
                                         no (cond
                                              o (str "the agent directory has " (:name o) " for " (:dir o) ", not " folder
                                                     "; `agent-mail agents add --name NAME` gives this folder a name of its own")
                                              (core/broad-folder? folder (home-dir))
                                              (str "did not add " folder " to the agent directory: it is too broad")
                                              (seq inside)
                                              (str "did not add " folder " to the agent directory: it encloses the folders of "
                                                   (core/word-list inside "and") "; `agent-mail agents add` adds it if that is meant"))]
                                     (if no
                                       (do (reset! why no) nil)
                                       [(str "📇 agents: add " name ", on call, on its first mail")
                                        {name (new-entry name folder true nil)}]))))
                     name)]
      (when-not quiet
        (if added
          (note "added " name " to the agent directory, on call, for " folder
                ", so replies reach it; `agent-mail agents describe` says what it owns")
          (when-let [w @why] (note w))))
      added)))

(defn known!
  "Refuses `to` as a recipient unless it is `user` or in the directory."
  [to]
  (let [d (directory)]
    (when-not (or (= "user" to) (contains? d to))
      (fail (core/unknown-agent to (cons "user" (keys d)))))))

(defn recipient [opts]
  (let [to (or (:to opts) (fail "send needs --to NAME (or `agent-mail @NAME text`)"))]
    (when-not (core/valid-name? to) (fail "bad recipient name:" (pr-str to)))
    to))

(defn auto-register!
  "A wait in a Claude Code session puts its folder on call the first time,
   so serve delivers its threads (see auto-add!). An agent taken off with
   `oncall remove` stays off."
  [w]
  (auto-add! w true))

;; ---------------------------------------------------------------- on call

(def serve-dir (fs/path duty-dir "serve"))
(def serve-pid-file (fs/path serve-dir "pid"))
(def serve-state-file
  "serve's heartbeat, interval and memory of the threads it delivered and
   the sessions it holds them in (see core)."
  (fs/path serve-dir "state.edn"))

(defn serve-state [] (or (read-edn serve-state-file) {}))
(defn serve-pid [] (try (some-> (slurp (str serve-pid-file)) str/trim parse-long) (catch Exception _ nil)))
(defn serve-memory
  "serve's memory as its state file has it (see core/serve-memory)."
  []
  (core/serve-memory (serve-state)))

(defn serve-info
  "Whether serve runs, {:alive :pid}: its pid file names a live process and
   its heartbeat is fresh, within three rounds and at least a minute (a
   reused pid alone is not enough)."
  []
  (let [pid (serve-pid)
        st (serve-state)
        fresh? (and (:heartbeat st) (< (core/age-s (:heartbeat st)) (max 60 (* 3 (or (:interval st) 5)))))]
    {:alive (boolean (and fresh? (pid-alive? pid))) :pid pid}))

(defn oncall-view
  "The on-call repos with what serve is doing about each (core/oncall-view)."
  ([] (oncall-view (duty-view)))
  ([duty]
   (core/oncall-view {:now (System/currentTimeMillis) :oncall (oncall-agents)
                      :memory (serve-memory) :duty duty})))

(defn serve-line
  "One line on whether serve runs, for `oncall` and `who`."
  []
  (let [{:keys [alive pid]} (serve-info)]
    (if alive
      (str "serve: running (pid " pid ")")
      "serve: not running, so nothing on call is delivered; start `agent-mail serve` (`just install-serve` in the agent-mail repo keeps it running)")))

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
   --no-auto-start a CLI that finds no GUI starts a headless mux server.
   Exit 127 when there is no wezterm; never throws."
  [& args]
  (if-let [bin (wezterm-bin)]
    (try (apply p/shell {:out :string :err :string :in "" :continue true} bin "cli" "--no-auto-start" (map str args))
         (catch Exception e {:exit 127 :out "" :err (str (ex-message e))}))
    {:exit 127 :out "" :err "wezterm not found"}))

;; ---------------------------------------------------------------- Claude Code sessions

(def macos? (str/includes? (System/getProperty "os.name") "Mac"))

(defn login-shell
  "The account's shell, whose login setup puts claude on PATH: $SHELL, else
   the account's login shell (launchd may set no SHELL), else sh."
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

(def ^:private claude-found (atom nil))

(defn claude-bin
  "The claude CLI: AGENT_MAIL_CLAUDE if set, else claude on PATH, else what
   the login shell finds (launchd's PATH lacks it), looked up once. nil when
   there is none."
  []
  (:bin (or @claude-found
            (reset! claude-found
                    {:bin (let [e (System/getenv "AGENT_MAIL_CLAUDE")]
                            (cond (not (str/blank? e)) e
                                  (fs/which "claude") (str (fs/which "claude"))
                                  :else (let [out (try (:out (p/shell {:out :string :err :string :in "" :continue true}
                                                                      (login-shell) "-lic" "command -v claude"))
                                                       (catch Exception _ nil))
                                              path (some->> out str/split-lines (map str/trim)
                                                            (filter #(str/starts-with? % "/")) last)]
                                          (when (and path (fs/executable? path)) path))))}))))

(defn claude-env
  "The environment serve runs claude in: its own without CLAUDE_* and
   CLAUDECODE, so a claude started from a serve that runs in a Claude Code
   shell never takes itself for that session's child; CLAUDE_CONFIG_DIR
   stays, so claude and serve read the same registry."
  []
  (into {} (remove (fn [[k _]] (and (or (str/starts-with? k "CLAUDE_") (= k "CLAUDECODE")) (not= k "CLAUDE_CONFIG_DIR"))))
        (System/getenv)))

(defn run-claude!
  "Runs claude ARGS… in `dir`, {:exit :out :err}: output goes through files,
   so a background service that inherits it cannot keep serve waiting; past
   `timeout-ms` it is killed (exit 124). Exit 127 when there is no claude;
   never throws."
  [{:keys [dir timeout-ms] :or {timeout-ms 60000}} & args]
  (if-let [bin (claude-bin)]
    (let [out (fs/create-temp-file {:prefix "agent-mail-claude"})
          err (fs/create-temp-file {:prefix "agent-mail-claude"})]
      (try
        (let [proc (apply p/process {:dir (some-> dir str) :env (claude-env) :in ""
                                     :out :write :out-file (fs/file out) :err :write :err-file (fs/file err)}
                          bin (map str args))
              r (deref proc timeout-ms nil)]
          (when-not r (p/destroy proc))
          {:exit (if r (:exit r) 124) :out (slurp (str out))
           :err (str (slurp (str err)) (when-not r (str "\ntimed out after " (quot timeout-ms 1000) "s")))})
        (catch Exception e {:exit 127 :out "" :err (str (ex-message e))})
        (finally (fs/delete-if-exists out) (fs/delete-if-exists err))))
    {:exit 127 :out "" :err "claude not found (AGENT_MAIL_CLAUDE names it)"}))

(defn claude-agents
  "Claude Code's sessions, `claude agents --json` (`all?`: `--all`, stopped
   ones too), as core/agents-items gives them: {:sessions}, or {:error why}."
  [all?]
  (let [r (apply run-claude! {:timeout-ms 30000} (cond-> ["agents" "--json"] all? (conj "--all")))]
    (if (zero? (:exit r))
      (try {:sessions (core/agents-items (json/parse-string (:out r) true))}
           (catch Exception _ {:error (str "`claude agents --json` printed no JSON: " (core/first-line (str (:out r))))}))
      {:error (core/claude-failure "`claude agents`" r)})))

(defn job-needs
  "What background job `job-id` waits on the user for, as its state file
   (<config>/jobs/<id>/state.json) says, e.g. \"approve Read: /etc/hosts\";
   nil when it says nothing; never throws."
  [job-id]
  (try
    (when (and (string? job-id) (re-matches #"[0-9A-Za-z-]+" job-id))
      (let [f (fs/path (claude-config) "jobs" job-id "state.json")
            needs (when (fs/regular-file? f) (get (json/parse-string (slurp (str f))) "needs"))]
        (when (and (string? needs) (not (str/blank? needs))) needs)))
    (catch Exception _ nil)))

(defn live-sessions
  "The sessions of `items` whose process runs, each with what Claude Code's
   registry adds: its inbox (:socket), and its status with statusUpdatedAt,
   which tell \"shell\" and \"waiting\" apart and since when. A waiting one
   also has what it waits on (:waiting-for, e.g. \"permission prompt\") and,
   for a background job, what it needs (:needs, see job-needs)."
  [items]
  (vec (for [s items
             :let [pid (:pid s)]
             :when (and (number? pid) (pid-alive? (long pid)))
             :let [e (registry-entry pid)
                   s (cond-> (update s :cwd #(some-> % real-dir))
                       e (assoc :socket (get e "messagingSocketPath")
                                :status (or (get e "status") (:status s))
                                :status-updated-at (get e "statusUpdatedAt")))]]
         (if (= "waiting" (:status s))
           (let [needs (job-needs (get e "jobId"))]
             (cond-> (assoc s :waiting-for (or (get e "waitingFor") (:waiting-for s)))
               needs (assoc :needs needs)))
           (dissoc s :waiting-for)))))

(defn transcript-file
  "Session `sid`'s transcript, <config>/projects/*/<sid>.jsonl; nil when
   there is none."
  [sid]
  (let [dir (fs/path (claude-config) "projects")]
    (when (and (string? sid) (re-matches #"[0-9A-Za-z-]+" sid) (fs/directory? dir))
      (some #(let [f (fs/path % (str sid ".jsonl"))] (when (fs/regular-file? f) f)) (fs/list-dir dir)))))

(defn- tail-entries
  "The assistant entries in the last `n` bytes of transcript `f`, parsed; a
   line cut at the start, or one that does not parse, is skipped."
  [f n]
  (with-open [raf (java.io.RandomAccessFile. (str f) "r")]
    (let [len (.length raf)
          start (max 0 (- len n))
          buf (byte-array (- len start))]
      (.seek raf start)
      (.readFully raf buf)
      (let [lines (str/split-lines (String. buf StandardCharsets/UTF_8))]
        (keep #(when (str/includes? % "\"assistant\"") (try (json/parse-string % true) (catch Exception _ nil)))
              (if (pos? start) (rest lines) lines))))))

(defn transcript-expiry
  "When session `sid`'s prompt cache expires (core/cache-expiry), from the
   tail of its transcript, which reaches megabytes: the last 256 KB, else
   the last 4 MB. nil when that tells nothing; never throws."
  [sid]
  (try
    (when-let [f (transcript-file sid)]
      (or (core/cache-expiry (tail-entries f 262144))
          (when (> (fs/size f) 262144) (core/cache-expiry (tail-entries f 4194304)))))
    (catch Exception _ nil)))
;; ---------------------------------------------------------------- delivery notes

(def launcher (str (fs/path repo-dir "bin" "agent-mail")))

(defn notify!
  "A macOS notification. With terminal-notifier installed and allowed, one
   per `group` (a newer one replaces it) and a click runs `execute` when
   given; a plain osascript banner is the fallback. Returns :clickable,
   :banner-not-allowed, :banner or nil (AGENT_MAIL_NO_NOTIFY, or neither
   tool)."
  [{:keys [title subtitle message group execute]}]
  (when-not (System/getenv "AGENT_MAIL_NO_NOTIFY")
    (let [tn (when (fs/which "terminal-notifier")
               (apply p/shell {:continue true :out :string :err :string}
                      "terminal-notifier" "-title" title "-subtitle" subtitle "-message" message "-group" group
                      (when execute ["-execute" execute])))]
      (cond
        (and tn (zero? (:exit tn))) :clickable
        (fs/which "osascript")
        (let [q #(str "\"" (str/replace (str/replace % "\\" "\\\\") "\"" "\\\"") "\"")]
          (p/shell {:continue true :out :string :err :string}
                   "osascript" "-e"
                   (str "display notification " (q message) " with title " (q title) " subtitle " (q subtitle)))
          (if tn :banner-not-allowed :banner))))))

(defn notify-user!
  "macOS notification for mail to the user. With terminal-notifier installed and
   allowed, a click opens the thread for reading and replying; a plain osascript
   banner is the fallback. Returns :clickable, :banner or nil (see notify!)."
  [from id subject]
  (notify! {:title (str "agent-mail: from " from) :subtitle id :message subject
            :group (str "agent-mail/" id) :execute (str launcher " open " id)}))

(defn oncall-note
  "The delivery note for `to`, which nobody is on duty for, when it is on
   call: serve hands the thread to a Claude Code session while it runs and
   the repo is not backing off; otherwise why not. nil when `to` is not on
   call."
  [to]
  (when-let [oc (first (filter #(= to (:repo %)) (oncall-view)))]
    (cond
      (not (:alive (serve-info)))
      (str "→ " to ": nobody on duty; on call, but `agent-mail serve` isn't running; it will see this at its next session start")
      (= "backing-off" (:state oc))
      (str "→ " to ": nobody on duty; on call, backing off until "
           (core/clock (.toEpochMilli (java.time.Instant/parse (:until oc)))) ": " (:reason oc)
           "; it will see this at its next session start")
      :else (str "→ " to ": on call; agent-mail serve hands it to a Claude Code session shortly"
                 " (the one that wrote in the thread for " to ", or a new one); nothing more to do"))))

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

(defn cmd-send
  "A new thread to --to, which must be `user` or in the directory. A sender
   the directory doesn't know yet is added to it (auto-add!)."
  [opts args]
  (ensure-store!)
  (let [w (whoami opts)
        from (:name w)
        to (recipient opts)
        _ (when (= from to) (fail "refusing to mail yourself (" from ")"))
        _ (known! to)
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
         (delivered! from to id subject))))
    (auto-add! w false)))

(declare cmd-compose)

(defn reply-to
  "The recipient of a reply from `me` to `th`: --to, which must be `user`
   or in the directory, else whoever wrote last that isn't me (mail from
   before the directory may name anyone). Never me."
  [opts th me]
  (let [to (or (:to opts) (core/other-party th me) (fail "nobody to reply to; use --to NAME"))]
    (when-not (core/valid-name? to) (fail "bad recipient name:" (pr-str to)))
    (when (= to me) (fail "refusing to mail yourself (" me ")"))
    (when (:to opts) (known! to))
    to))

(defn cmd-reply [opts args {:keys [close?]}]
  (ensure-store!)
  (when (and (not close?) (empty? (rest args)) (or (:edit opts) (System/console)))
    (cmd-compose opts args)
    (System/exit 0))
  (let [w (whoami opts)
        me (:name w)
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
         (when-not close? (delivered! me to id (:subject th))))))
    (when-not close? (auto-add! w false))))

(defn cmd-forward
  "A new thread to --to carrying thread ID, or only its message --msg (a
   position or a file name), after the caller's note. The note is optional:
   the trailing text, then stdin as for send, but nothing is waited for on a
   terminal. The files the forwarded messages use are copied under their own
   names, so the links in them keep working, and listed on the new message
   with any --attach; thread.md records `forwarded-from`."
  [opts args]
  (ensure-store!)
  (let [w (whoami opts)
        from (:name w)
        to (or (:to opts) (fail "forward needs --to NAME"))
        _ (when-not (core/valid-name? to) (fail "bad recipient name:" (pr-str to)))
        _ (when (= from to) (fail "refusing to mail yourself (" from ")"))
        _ (known! to)
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
         (delivered! from to id subject))))
    (auto-add! w false)))

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
        _ (when-let [to (:to opts)] (known! to))
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
        ths (if (:all opts) (all-threads) (mail-for me))
        ths (map #(assoc % :unread (count (unread % me))) (sort-by :updated ths))]
    (if (:json opts)
      (println (json/generate-string (map core/summary ths) {:pretty true}))
      (doseq [t ths]
        (println (format "#%-3d %-34s %-14s %-5s %2d msgs %s  %s  %s"
                         (:number t) (:id t) (str (:last-from t) "→" (:owner t)) (:status t) (:count t)
                         (if (pos? (:unread t)) (format "%d new" (:unread t)) "     ")
                         (core/local-time (:updated t)) (:subject t)))))))

(defn tab-title!
  "Titles the terminal tab when the command runs in a WezTerm pane, so a
   classic listener's tab shows its repo and whether mail is in: an explicit
   tab title sticks over the title Claude Code keeps rewriting. Silent
   elsewhere or on failure."
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
  "Posts `text` into the Claude Code session behind unix socket `path` as a
   user message: an idle session starts a turn with it, a busy one reads it
   between tool calls. No auth line: serve is no session's child, and the
   token is optional on macOS and Linux. True once written; false on any
   failure."
  [path text]
  (try
    (with-open [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
      (.connect ch (UnixDomainSocketAddress/of ^String path))
      (let [buf (ByteBuffer/wrap (.getBytes (str (json/generate-string {:type "user" :message {:role "user" :content text}}) "\n")
                                            StandardCharsets/UTF_8))]
        (while (.hasRemaining buf) (.write ch buf))
        true))
    (catch Exception _ false)))

(defn serve-notice
  "What wait says in a Claude Code session, where it no longer runs since
   9 Nov 2026: serve delivers there now, so a listener armed by the old
   listen skill learns to stop arming it, and whether `me` is on call."
  [me]
  (let [e (get (directory) me)]
    (str "agent-mail: wait no longer runs in a Claude Code session. agent-mail serve now starts a background "
         "session for each thread and posts later messages into the session that wrote in the thread. "
         (cond (nil? e) (str me " is not on call: `agent-mail oncall add` in its repo puts it there. ")
               (not (:oncall e)) (str me " was taken off call: `agent-mail oncall add` puts it back. ")
               :else (str me " is on call. "))
         (when-not (:alive (serve-info)) "agent-mail serve isn't running: tell the user (`just install-serve` in the agent-mail repo). ")
         "Don't arm wait again, and re-read /agent-mail-listen.")))

(defn cmd-wait
  "Waits for threads that are `me`'s turn, prints their ids and exits 0;
   exit 3 on --timeout, duty \"idle\". For Codex and scripts. The duty
   record names the session by its live name, read again every round (see
   session-name), so neither a restart nor a rename leaves a name nobody
   answers to; --session names it only where there is no registry. In a
   Claude Code session (its socket set, no --once) serve delivers instead:
   wait puts the repo on call the first time (see auto-register!), removes
   the duty records this session's older waits left, says so on stderr and
   exits 0 with nothing on stdout."
  [opts]
  (ensure-store!)
  (let [w (whoami opts)
        me (:name w)
        given (:session opts)
        fallback (str "pid" (.pid (java.lang.ProcessHandle/current)))
        interval (or (:interval opts) 3)
        deadline (when-let [t (:timeout opts)] (+ (System/currentTimeMillis) (* 1000 t)))]
    (when (and (not (:once opts)) (session-socket))
      (auto-register! w)
      (doseq [[f rec] (duty-files)
              :when (and (= me (:repo rec)) (claude-pid) (= (claude-pid) (:session-pid rec)))]
        (fs/delete-if-exists f))
      (binding [*out* *err*] (println (serve-notice me)))
      (System/exit 0))
    (tab-title! (str "📪 " me))
    (loop [prev-session nil, warned? false]
      (let [live (session-name)
            session (or live given fallback)
            mismatch? (boolean (and live given (not= live given)))
            pending (mail-for me)]
        (when (and mismatch? (not warned?))
          (binding [*out* *err*]
            (println (str "agent-mail: this session is named " (pr-str live) ", not " (pr-str given)
                          "; using the live name"))))
        (when (and prev-session (not= prev-session session))
          (fs/delete-if-exists (duty-file me prev-session)))
        (write-duty! me session (if (seq pending) "working" "waiting"))
        (when (seq pending)
          (tab-title! (str "📬 " me))
          (run! println (map :id pending))
          (System/exit 0))
        (if (and deadline (>= (System/currentTimeMillis) deadline))
          (do (write-duty! me session "idle") (System/exit 3))
          (do (Thread/sleep (* 1000 interval))
              (recur session (or warned? mismatch?))))))))

(defn who-here
  "The also-line for the caller's folder (its repo's main worktree, else
   the current directory): the other Claude Code sessions working there,
   as serve names them to the sessions it posts to, with the subject of
   the thread serve started each for."
  []
  (let [dir (real-dir (or (some-> (repo-root) str) (str (fs/absolutize "."))))
        {:keys [sessions error]} (claude-agents false)]
    (when error (fail error))
    (println (core/also-line dir (live-sessions sessions) (serve-memory) (System/getenv "CLAUDE_CODE_SESSION_ID")
                             (into {} (map (juxt :id :subject)) (all-threads))))))

(defn cmd-who
  "The duty records, the repos on call with what serve is doing about each,
   then the sessions serve delivered threads to, as it last saw them (--json:
   the duty records alone, as status gives them). --here: the other Claude
   Code sessions in this folder (see who-here)."
  [opts]
  (if (:here opts)
    (who-here)
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
          (doseq [s (core/serve-sessions (serve-memory))]
            (println (format "%-20s %-28s %s" (:agent s) "(serve)" (core/session-line s))))
          (when (and (seq oncall) (not (:alive (serve-info))))
            (println (serve-line))))))))
(defn cmd-status
  "Everything at once, for UIs (a menu bar app calls it on every refresh): all
   threads with per-reader unread counts (and the caller's unread addressed
   to it, :unread-to-me), waiting time, listener and the
   session serve delivered each to, the duty records, the open threads nobody
   is listening for, the repos on call and whether serve runs, and the agent
   directory. A pure read: no seen marker moves, nothing is written or
   committed (not even the thread numbering that list and read catch up on,
   nor a moved folder in the directory), and Claude Code is not asked:
   sessions are as serve last saw them."
  [opts]
  (let [me (caller (assoc opts :pure true))
        threads (all-threads)
        readers (into #{me} (mapcat core/participants) threads)
        duty (duty-view)
        st (core/status {:now (core/now-utc) :me me :threads threads
                         :seen (into {} (map (juxt identity seen-map)) readers)
                         :duty duty :oncall (oncall-view duty) :serve (serve-info) :memory (serve-memory)
                         :agents (agents-view)})]
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

(defn folder-agent
  "The directory entry of the folder the command runs in, or of a folder
   above it (see find-agent); nil when none."
  []
  (first (find-agent (vals (directory)) (folder-place "."))))

(defn put-agent!
  "Puts agent `nm` into the directory at folder `folder`, on call or not: a
   new entry, or `nm`'s own moved there, which only `nm` itself or the user
   may do (the caller, from `opts`). Refuses a folder another agent has and
   one too broad (refuse-broad!); says on stderr which agents' folders the
   folder encloses. Returns the entry."
  [opts nm folder oncall]
  (refuse-broad! folder)
  (when-let [e ((directory) nm)]
    (when-not (core/same-path? (:dir e) folder)
      (own-or-user! (caller (assoc opts :quiet true)) ["moves" "move"] nm)))
  (let [said (atom nil)
        e (get (directory! (fn [d]
                             (when-let [o (entry-at d folder nm)]
                               (fail (str folder " is already in the agent directory, as " (:name o))))
                             (let [e (d nm)
                                   moved? (and e (not (core/same-path? (:dir e) folder)))]
                               (when (or moved? (not e)) (reset! said (enclosure-note d folder nm)))
                               [(str "📇 agents: " (if e nm (str "add " nm)) (if oncall ", on call" ", off call")
                                     (when moved? (str ", at " folder)))
                                {nm (if e
                                      (assoc e :dir (str folder) :file-id (file-id folder) :oncall oncall)
                                      (new-entry nm folder oncall nil))}])))
               nm)]
    (when-let [s @said] (note s))
    e))

(defn set-oncall!
  "Sets whether agent `nm` is on call: [its entry before, after]. Refuses a
   name the directory doesn't have."
  [nm on?]
  (let [before (atom nil)
        changes (directory! (fn [d]
                              (let [e (or (d nm) (fail (core/unknown-agent nm (keys d))))]
                                (reset! before e)
                                (when (not= on? (:oncall e))
                                  [(str "📇 agents: " nm (if on? ", on call" ", off call")) {nm (assoc e :oncall on?)}]))))]
    [@before (get changes nm @before)]))

(defn here-oncall
  "[name dir] that `oncall add` and `remove` take with no NAME for a folder
   the directory doesn't have yet: the folder (see here), whoever the caller
   is, since the user puts repos on call too. Outside any repo a note on
   stderr says so, as caller's does. `sub` and `params` name the command
   that chooses instead. Refuses a name the directory has for another
   folder that still exists."
  [sub params]
  (let [{:keys [name dir plain]} (here)]
    (when-not name
      (fail (str "this folder's name makes no address (" dir "); give " params)))
    (when-not (core/agent-name? name) (fail "bad name:" (pr-str name) (str "; give " params)))
    (when-let [o (get (directory) name)]
      (when (and (:dir o) (fs/directory? (:dir o)))
        (fail (str "the agent directory has " name " for " (:dir o) "; `agent-mail oncall " sub " " params
                   "` chooses, `agent-mail agents move " name " DIR` moves it"))))
    (when plain
      (note "not in a git repo; taking this folder as " (pr-str name) " (`agent-mail oncall " sub " " params "` to choose)"))
    [name dir]))

(defn oncall-said
  "What `oncall add` prints after the name and folder: why serve can't
   start sessions there, and whether serve runs."
  [e]
  (when-let [l (trust-line e (trust-of))] (println l))
  (when-not (:alive (serve-info)) (println (serve-line))))

(defn cmd-oncall
  "Lists the agents on call (--json: oncall-view's entries). `add` and
   `remove` set the directory's oncall field: `add [NAME [DIR]]` puts NAME
   on call, at DIR when given (adding it, or moving it there, which only
   NAME itself or the user may do, see put-agent!); no arguments, the
   folder it runs in (its entry, else a new one, see here-oncall). `remove
   [NAME]` takes one off and keeps it off, since wait would otherwise put it
   back."
  [opts [sub & args]]
  (case sub
    "add" (let [[nm dir] args
                e (cond
                    dir (let [d (fs/normalize (fs/absolutize dir))]
                          (when-not (core/agent-name? nm) (fail "bad name:" (pr-str nm)))
                          (when-not (fs/directory? d) (fail "no such directory:" (str d)))
                          (put-agent! opts nm (:folder (folder-place d)) true))
                    nm (second (set-oncall! nm true))
                    :else (if-let [e (folder-agent)]
                            (second (set-oncall! (:name e) true))
                            (let [[nm dir] (here-oncall "add" "NAME DIR")] (put-agent! opts nm dir true))))]
            (println (str (:name e) " is on call (" (:dir e) ")"))
            (oncall-said e))
    "remove" (let [nm (first args)
                   [before after] (cond nm (set-oncall! nm false)
                                        :else (if-let [e (folder-agent)]
                                                (set-oncall! (:name e) false)
                                                (let [[nm dir] (here-oncall "remove" "NAME")]
                                                  [nil (put-agent! opts nm dir false)])))]
               (println (if (:oncall before)
                          (str (:name after) " is no longer on call; wait won't put it back (`oncall add` does)")
                          (str (:name after) " was not on call; wait won't put it on call either"))))
    nil (let [v (oncall-view)]
          (if (:json opts)
            (println (json/generate-string v {:pretty true}))
            (do (if (seq v)
                  (doseq [o v] (println (format "%-20s %-40s %s" (:repo o) (:dir o) (:text o))))
                  (println "nothing on call"))
                (println (serve-line)))))
    (fail "unknown oncall command" sub "(add or remove)")))

(defn optional-text
  "Text that may or may not be given: the words, then what stands ready on
   stdin after a blank line (as for send), never waited for; nil when
   there is none."
  [words]
  (let [text (str/join " " words)
        more (stdin-content)]
    (not-empty (str/trim (if (str/blank? more) text (str text "\n\n" (str/trim more)))))))

(defn agents-list
  "`agents`: every agent with its folder, whether it is on call and its
   summary (a borrowed one says where from); a folder that is gone, or one
   Claude Code hasn't trusted while on call, is marked."
  [opts]
  (let [v (agents-view (directory) (:json opts))]
    (if (:json opts)
      (println (json/generate-string v {:pretty true}))
      (if (empty? v)
        (println "the agent directory is empty; `agent-mail agents add [DIR]` adds a folder")
        (doseq [a v]
          (println (format "%-20s %-8s %s%s" (:name a) (if (:oncall a) "on call" "off call") (:dir a)
                           (cond (not (:dir-exists a)) "  (folder gone)"
                                 (and (:oncall a) (false? (:trusted a))) "  (not trusted by Claude Code: run `claude` there once)"
                                 :else "")))
          (println (str "  " (or (:summary a) "(no description)")
                        (when-let [b (:borrowed a)] (str " (from " b ")")))))))))

(defn agents-add
  "`agents add [DIR] [--name NAME] [description…]`: DIR when the first
   argument names a folder (an existing directory, or a path with a `/`),
   else the caller's repo at its main worktree, else the current folder.
   NAME defaults to the folder's (core/name-from-dir). On call. Refuses `/`
   and the home folder (refuse-broad!), and says on stderr which agents'
   folders the new one encloses."
  [opts args]
  (let [a (first args)
        dir? (and a (or (str/includes? a "/") (#{"." ".."} a) (fs/directory? a)))
        [dir words] (if dir? [a (rest args)] [nil args])
        folder (if dir
                 (let [d (fs/normalize (fs/absolutize dir))]
                   (when-not (fs/directory? d) (fail "no such directory:" (str d)))
                   (:folder (folder-place d)))
                 (:dir (here)))
        nm (or (:name opts) (core/name-from-dir (fs/file-name folder))
               (fail (str "no name can be made from " folder "; give --name NAME")))
        _ (when-not (core/agent-name? nm)
            (fail "bad name:" (pr-str nm) "(letters, digits, `.`, `_` and `-`; not `user`)"))
        _ (refuse-broad! folder)
        desc (optional-text words)
        said (atom nil)
        e (get (directory! (fn [d]
                             (when-let [o (entry-at d folder nil)]
                               (fail (str folder " is already in the agent directory, as " (:name o))))
                             (when-let [o (d nm)]
                               (fail (str nm " is already in the agent directory, for " (:dir o)
                                          "; `agent-mail agents move " nm " DIR` moves it")))
                             (reset! said (enclosure-note d folder nm))
                             [(str "📇 agents: add " nm) {nm (new-entry nm folder true desc)}]))
               nm)]
    (when-let [s @said] (note s))
    (println (str nm " is in the agent directory, on call (" folder ")"))
    (when-not desc
      (println (str "Say what it owns and what to ask it for: `agent-mail agents describe " nm " < about.md`")))
    (oncall-said e)))

(defn agents-describe
  "`agents describe [NAME] [text…] [< about.md]`: an agent describes only
   itself; the user describes any agent, by name."
  [opts [nm & words]]
  (let [me (caller opts)
        nm (or nm (if (= "user" me) (fail "agents describe takes the agent's NAME") me))
        _ (own-or-user! me ["describes" "describe"] nm)
        desc (or (optional-text words)
                 (when (System/console)
                   (note "reading the description from stdin, end with Ctrl-D")
                   (not-empty (str/trim (slurp *in*))))
                 (fail (str "empty description: give it on stdin, `agent-mail agents describe " nm " < about.md`")))]
    (directory! (fn [d]
                  (let [e (or (d nm) (fail (core/unknown-agent nm (keys d))))]
                    [(str "📇 agents: describe " nm) {nm (assoc e :description desc)}])))
    (println (str nm ": " (core/description-summary desc)))))

(defn cmd-agents
  "The agent directory: list it (--json for UIs), add a folder, describe,
   move or remove an agent. An agent describes, moves and removes only its
   own entry; the user any. A move refuses `/` and the home folder and says
   which agents' folders the new one encloses, as add does. Past mail keeps
   a removed agent's name."
  [opts [sub & args]]
  (case sub
    nil (agents-list opts)
    "add" (agents-add opts args)
    "describe" (agents-describe opts args)
    "move" (let [[nm dir] args
                 _ (when-not (and nm dir) (fail "agents move takes NAME and DIR"))
                 _ (own-or-user! (caller opts) ["moves" "move"] nm)
                 d (fs/normalize (fs/absolutize dir))
                 _ (when-not (fs/directory? d) (fail "no such directory:" (str d)))
                 folder (:folder (folder-place d))
                 _ (refuse-broad! folder)
                 said (atom nil)
                 e (get (directory! (fn [dm]
                                      (let [e (or (dm nm) (fail (core/unknown-agent nm (keys dm))))]
                                        (when-let [o (entry-at dm folder nm)]
                                          (fail (str folder " is already in the agent directory, as " (:name o))))
                                        (reset! said (enclosure-note dm folder nm))
                                        [(str "📇 agents: move " nm " to " folder)
                                         {nm (assoc e :dir folder :file-id (file-id folder))}])))
                        nm)]
             (when-let [s @said] (note s))
             (println (str nm " is now at " folder))
             (when-let [l (trust-line e (trust-of))] (println l)))
    "remove" (let [nm (or (first args) (fail "agents remove takes NAME"))
                   _ (own-or-user! (caller opts) ["removes" "remove"] nm)]
               (directory! (fn [d]
                             (when-not (d nm) (fail (core/unknown-agent nm (keys d))))
                             [(str "📇 agents: remove " nm) {nm nil}]))
               (println (str nm " is no longer in the agent directory; its past mail keeps the name")))
    (fail "unknown agents command" sub "(add, describe, move or remove)")))

;; ---------------------------------------------------------------- serve

(defn log!
  "One line on stderr with the local time in front; under launchd it goes to
   the log file."
  [& msg]
  (binding [*out* *err*]
    (println (str (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))
                  " " (str/join " " msg)))))

(defn- named
  "A session in serve's log: its name in quotes and its short id."
  [nm sid]
  (str (pr-str (or nm (core/short-id sid))) " (" (core/short-id sid) ")"))

(defn- gone-dir [dir] {:exit 1 :out "" :err (str "its directory is gone (" dir ")")})

;; ---- landing: what serve's sessions pushed reaches the main checkout (see core)

(defn git-at
  "Runs git ARGS… in folder `dir`, {:exit :out :err}: without
   git-redirects (git-env), never prompting, and killed past `timeout-ms`
   (exit 124). Never throws."
  [dir timeout-ms & args]
  (try
    (let [proc (apply p/process {:dir (str dir) :env (assoc (git-env) "GIT_TERMINAL_PROMPT" "0") :in ""
                                 :out :string :err :string}
                      "git" (map str args))
          r (deref proc timeout-ms nil)]
      (if r
        {:exit (:exit r) :out (str (:out r)) :err (str (:err r))}
        (do (p/destroy proc) {:exit 124 :out "" :err (str "timed out after " (quot timeout-ms 1000) "s")})))
    (catch Exception e {:exit 127 :out "" :err (str (ex-message e))})))

(defn- git-out
  "What git ARGS… print in `dir`, trimmed; nil when it fails or prints
   nothing."
  [dir & args]
  (let [r (apply git-at dir 30000 args)]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn- git-count [dir range] (some-> (git-out dir "rev-list" "--count" range) parse-long))

(defn- git-why
  "The first line git printed on failing, else its exit code."
  [r]
  (or (not-empty (core/first-line (str (:err r) "\n" (:out r)))) (str "exit " (:exit r))))

(defn land-facts
  "What git says of agent folder `dir` for core/land-plan, all but
   :working and the candidate's :closed: whether it is its repo's main
   worktree, its branch and upstream, its tracked files' changes, how far
   HEAD is behind and ahead of the upstream as the local remote-tracking
   ref has it (never fetched: a session's push from its worktree moved
   it); without an upstream, the same against the worktree branch of the
   session whose folder is `cwd`. Only reads, and nothing over the
   network."
  [dir cwd]
  (let [{:keys [top main]} (git-roots dir)]
    (if-not (and top (core/same-path? top dir) (core/same-path? main dir))
      {:main? false}
      (let [branch (git-out dir "symbolic-ref" "-q" "--short" "HEAD")
            upstream (when branch (git-out dir "rev-parse" "--abbrev-ref" "--symbolic-full-name" "@{u}"))
            status (git-at dir 30000 "--no-optional-locks" "status" "--porcelain" "--untracked-files=no")
            wt (when (and branch (not upstream) cwd)
                 (core/worktree-of (map #(update % :path real-dir)
                                        (core/parse-worktrees (git-out dir "worktree" "list" "--porcelain")))
                                   cwd))
            ref (some->> (:branch wt) (str "refs/heads/"))]
        (cond-> {:main? true :branch branch :upstream upstream
                 :dirty? (or (pos? (:exit status)) (not (str/blank? (:out status))))}
          upstream (assoc :behind (git-count dir "HEAD..@{u}") :ahead (git-count dir "@{u}..HEAD"))
          ref (assoc :candidate {:branch (:branch wt) :behind (git-count dir (str "HEAD.." ref))
                                 :ahead (git-count dir (str ref "..HEAD"))}))))))

(defn land!
  "Carries out [:land A info] (core/serve-actions): fast-forwards agent
   `agent`'s main checkout `dir` as core/land-plan says, once with an
   upstream, else for each of `sessions` in turn, logging each
   fast-forward, e.g. `land infra: main 3 commits to abc1234 (upstream)`.
   Nothing but `git merge --ff-only` changes anything there, nothing is
   fetched, and untracked files are never touched. Returns why it can't
   land (the last reason), nil when nothing keeps the checkout behind;
   never throws."
  [agent dir sessions working]
  (try
    (let [step (fn [f]
                 (let [plan (core/land-plan (assoc f :working working))]
                   (if-let [target (:ff plan)]
                     (let [r (git-at dir 60000 "merge" "--ff-only" "-q" target)]
                       (if (zero? (:exit r))
                         (do (log! (str "land " agent ": " (:branch f) " " (core/commits-phrase (:commits plan)) " to "
                                        (git-out dir "rev-parse" "--short" "HEAD") " (" (:via plan) ")"))
                             nil)
                         (str "main checkout " (:commits plan) " behind " (or (:upstream f) (get-in f [:candidate :branch]))
                              ": `git merge --ff-only` failed: " (git-why r))))
                     (:blocked plan))))
          f (land-facts dir nil)]
      (cond (not (:main? f)) nil
            (:upstream f) (step f)
            :else (reduce (fn [blocked s]
                            (or (step (update (land-facts dir (:cwd s)) :candidate
                                              #(some-> % (assoc :closed (:closed s)))))
                                blocked))
                          nil sessions)))
    (catch Exception e (str "landing failed: " (ex-message e)))))

(defn retire-facts
  "What git says, for core/rm-plan, of the worktree of the session whose
   folder was `cwd`, in agent folder `dir`'s repo: the linked worktree it
   was in (none outside git or in the main worktree), how many commits of
   its branch (a detached one's commit) HEAD lacks, and whether HEAD or
   @{u} contains it; {:error why} when git can't list the worktrees. Only
   reads."
  [dir cwd]
  (if-not (git-roots dir)
    {:worktree nil}
    (let [listed (git-at dir 30000 "worktree" "list" "--porcelain")
          wt (when (zero? (:exit listed))
               (core/worktree-of (map #(update % :path real-dir) (core/parse-worktrees (:out listed))) cwd))
          ref (when wt (if (:branch wt) (str "refs/heads/" (:branch wt)) (:head wt)))
          in? (fn [base] (zero? (:exit (git-at dir 30000 "merge-base" "--is-ancestor" ref base))))]
      (cond (pos? (:exit listed)) {:error (str "`git worktree list` failed: " (git-why listed))}
            (not ref) {:worktree nil}
            :else {:worktree (select-keys wt [:branch :head])
                   :commits (git-count dir (str "HEAD.." ref))
                   :in-head? (in? "HEAD")
                   :in-upstream? (boolean (and (git-out dir "rev-parse" "--abbrev-ref" "--symbolic-full-name" "@{u}")
                                               (in? "@{u}")))}))))

(defn carry-out!
  "Carries out one of core/serve-actions, logs it, and returns serve's
   memory after it. A start or resume that fails backs its agent off with
   what claude said (an untrusted folder, a missing claude); a post that
   fails leaves the thread to go out again next round, past that session;
   a stop that fails is tried again next round. A landing that can't be
   done is logged when its reason changes, and remembered for `oncall`."
  [memory [kind & args :as action] now]
  (let [remember #(core/remember memory % now)
        agent-m #(get-in memory [:agents %])
        backed-off (fn [[_ a until reason :as act]]
                     (log! (str "back off " a " until " (core/clock until) ": " reason))
                     (core/remember memory act now))]
    (case kind
      :post (let [[t sid text {:keys [socket number name idle] :as info}] args]
              (if (post-to-session! socket text)
                (do (log! (str "post #" number " into " (named name sid)
                               (when idle (str ", idle: looking again in " (quot core/post-check-ms 1000) "s"))))
                    (remember [:posted t sid info]))
                (do (log! (str "post #" number " into " (named name sid) " failed: nothing took it; another session next round"))
                    (remember [:post-failed t sid]))))
      :resume (let [[t sid text {:keys [agent dir number name warm-until] :as info}] args
                    r (if (and dir (fs/directory? dir)) (run-claude! {:dir dir} "--bg" "--resume" sid text) (gone-dir dir))]
                (if (zero? (:exit r))
                  (do (log! (str "resume " (named name sid) " for #" number ", its cache warm until " (core/clock warm-until)))
                      (remember [:resumed t sid info]))
                  (backed-off (core/back-off agent (agent-m agent) now (core/claude-failure "`claude --bg --resume`" r)))))
      :start (let [[agent t text {:keys [dir number name] :as info}] args
                   r (if (and dir (fs/directory? dir)) (run-claude! {:dir dir} "--bg" "--name" name text) (gone-dir dir))
                   id (when (zero? (:exit r)) (core/backgrounded-id (:out r)))]
               (if id
                 (let [sid (some #(when (= id (:id %)) (:session-id %)) (:sessions (claude-agents false)))]
                   (log! (str "start " (named name id) " for #" number " in " dir))
                   (remember [:started agent t id sid info]))
                 (backed-off (core/back-off agent (agent-m agent) now
                                            (if (zero? (:exit r))
                                              (str "`claude --bg` printed no session id: " (core/first-line (str (:out r))))
                                              (core/claude-failure "`claude --bg`" r))))))
      :stop (let [[sid {:keys [id name idle-since]}] args
                  r (run-claude! {} "stop" (or id sid))]
              (if (zero? (:exit r))
                (do (log! (str "stop " (named name sid) ": idle since " (core/clock idle-since)))
                    (remember [:stopped sid]))
                (do (log! (str "stop " (named name sid) ": " (core/claude-failure "`claude stop`" r)))
                    memory)))
      :land (let [[agent {:keys [dir sessions working]}] args
                  blocked (land! agent dir sessions working)]
              (when (and blocked (not= blocked (get-in memory [:land agent :blocked])))
                (log! (str "can't land " agent ": " blocked)))
              (remember [:land-checked agent blocked]))
      :retire (let [[sid {:keys [id name dir cwd]}] args
                    plan (core/rm-plan (try (retire-facts dir cwd) (catch Exception e {:error (ex-message e)})))]
                (if-let [why (:rm plan)]
                  (let [r (run-claude! {} "rm" (or id sid))]
                    (log! (str "rm " (named name sid) ": stopped, holding no open thread, " why
                               (when-not (zero? (:exit r)) (str "; " (core/claude-failure "`claude rm`" r) ", so only forgotten"))))
                    (remember [:removed sid]))
                  (let [u (:keep plan)]
                    (when (not= u (dissoc (get-in memory [:sessions sid :unlanded]) :at))
                      (log! (str "keep " (named name sid) ": stopped, holding no open thread, " (core/unlanded-phrase u))))
                    (remember [:unlanded sid u]))))
      :back-off (backed-off action)
      :observe (let [[sid changes] args
                     s (get-in memory [:sessions sid])]
                 (when (and (:state changes) (not= (:state changes) (:state s)))
                   (log! (str (case (:state changes) "live" "up" "stopped" "ended" (:state changes)) " "
                              (named (or (:name changes) (:name s)) sid))))
                 (remember action))
      :deaf (let [[t sid] args]
              (log! (str "#" (get-in memory [:threads t :number]) ": " (named (get-in memory [:sessions (core/short-id sid) :name]) sid)
                         " stayed idle " (quot core/post-check-ms 1000) "s after the post (held for the user to approve?); another session takes the thread"))
              (remember action))
      :needs-input (let [[_ {:keys [id name number since waiting-for needs] :as info}] args
                         told (notify! {:title (str "agent-mail: " (or name id) " needs you")
                                        :subtitle (str "#" number ", waiting since " (core/clock since))
                                        :message (str (or needs waiting-for "input") ". Open it with: claude attach " id)
                                        :group (str "agent-mail/needs/" id)})]
                     (log! (str (core/needs-input-note info)
                                (if told " Told the user with a notification." " No notification sent; `agent-mail who` shows it.")))
                     (remember action))
      :pass (let [[_ {:keys [agent number]}] args]
              (log! (str "#" number " closed with news for " agent ": no session of its runs to post it into;"
                         " `agent-mail list` shows it there"))
              (remember action))
      :reset (do (log! (str "on track again: " (first args))) (remember action))
      (:forget :drop :checked :lost) (remember action)
      (do (log! "unknown action" (pr-str kind)) memory))))

(defn serve-round!
  "One round: gathers what core/serve-actions decides on, carries out its
   actions and returns serve's memory after them. The agents on call and
   their folders come from the directory (migrated first, once). Reads no
   thread while nothing is on call and nothing is remembered, and asks
   Claude Code for its sessions only when core/serve-wants says so."
  [memory {:keys [grace-ms check-ms]}]
  (when (fs/exists? (fs/path store ".git")) (migrate-directory!))
  (let [oncall (oncall-agents)]
    (if (and (empty? oncall) (every? empty? (vals (dissoc memory :land))))
      memory
      (let [now (System/currentTimeMillis)
            in {:now now :memory memory :grace-ms grace-ms :check-ms check-ms :max-starts core/starts-per-round
                :oncall (update-vals oncall #(update % :dir real-dir))
                :threads (all-threads)
                :seen (into {} (for [a (keys oncall)] [a (try (seen-map a) (catch Exception _ {}))]))
                :on-duty (set (map :repo (filter :on-duty (duty-view))))}
            wants (core/serve-wants in)
            live (when (:live wants) (claude-agents false))
            known (when (and (:known wants) (:sessions live)) (claude-agents true))
            actions (core/serve-actions
                     (assoc in
                            :live (some-> (:sessions live) live-sessions)
                            :known (:sessions known)
                            :agents-error (or (:error live) (:error known))
                            :expiry (memoize transcript-expiry)))]
        (reduce #(carry-out! %1 %2 now) memory actions)))))

(defn write-serve-state! [memory interval]
  (spit-atomic! serve-state-file
                (pr-str (merge {:pid (.pid (java.lang.ProcessHandle/current)) :heartbeat (core/now-utc) :interval interval}
                               memory))))

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
              (write-serve-state! (serve-memory) interval)))))

(defn release-serve!
  "Removes the pid file if it is still this process's."
  []
  (when (= (serve-pid) (.pid (java.lang.ProcessHandle/current)))
    (fs/delete-if-exists serve-pid-file)))

(defn cmd-serve
  "The on-call daemon (see core/serve-actions): a round every --interval
   seconds (5); --once runs one round. Its memory and heartbeat go to
   duty/serve/state.edn after every round; what an older serve kept there
   (its WezTerm tabs) is dropped. Exits when another serve has taken the pid
   file over. Another serve alive refuses this one, except with --standby
   (launchd's): then it waits for that one to end and takes over, rather
   than exiting for launchd to restart every few seconds."
  [opts]
  (let [interval (or (:interval opts) 5)
        cfg {:grace-ms core/start-grace-ms :check-ms core/post-check-ms}
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
    (log! (str "serve: pid " me ", a round every " interval "s, sessions through "
               (or (claude-bin) "claude, which is not found (AGENT_MAIL_CLAUDE names it)")))
    (loop [memory (serve-memory)]
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
   get notifications), so sessions are not told about it. Nor about a repo's
   open threads while serve delivers them (serve runs, the repo is on call
   and not backing off): a new interactive session must not take on a thread
   a session of serve's holds. Closed threads with news are always listed:
   serve posts them only into a holder that runs, and starts nothing for
   them."
  [opts]
  (try
    (ensure-numbers!)
    (let [me (caller (assoc opts :quiet true))
          line (fn [who ths]
                 (println (format "agent-mail: %d thread%s waiting on %s: %s. Read with `agent-mail read <number>`."
                                  (count ths) (if (= 1 (count ths)) "" "s") who
                                  (str/join "; " (map #(format "#%d %s \"%s\" from %s" (:number %) (:id %) (:subject %) (:last-from %)) ths)))))
          served? (and (:alive (serve-info)) (some #(and (= me (:repo %)) (= "on-call" (:state %))) (oncall-view)))]
      (when-let [m (and (not served?) (seq (open-for me)))] (line me m))
      (when-let [m (seq (news-for me))]
        (println (format "agent-mail: %d closed thread%s with news for %s, not read yet: %s. Read with `agent-mail read <number>`; nothing to answer."
                         (count m) (if (= 1 (count m)) "" "s") me
                         (str/join "; " (map #(format "#%d %s \"%s\" closed by %s" (:number %) (:id %) (:subject %) (:last-from %)) m))))))
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
  agent-mail list [--all] [--json]            open threads whose turn is mine, then closed
                                              ones with news for me: a reply or a close I
                                              haven't read (read it and it drops off)
  agent-mail read ID [--json]                 the whole thread; ID is the number from `list`
                                              (7 or #7), the full id, or a unique fragment
  agent-mail open ID                          open what's new to you in your Markdown app
  agent-mail wait [--session NAME] [--timeout S] [--interval S] [--once]
                                              waits for what `list` shows, prints their ids
                                              and exits; exit 3 on timeout. For Codex and
                                              scripts: in a Claude Code session (without
                                              --once) it only puts the repo on call and says
                                              on stderr that serve delivers there. --session
                                              NAME is needed only outside Claude Code
  agent-mail who [--json]                     who is on duty, the repos on call, and the
                                              sessions serve handed threads to (one waiting
                                              on you says what for and `claude attach ID`;
                                              one kept says what of it is unlanded)
  agent-mail who --here                       the other Claude Code sessions working in this
                                              folder, by the names SendMessage takes
  agent-mail agents [--json]                  the agent directory: each agent's folder, whether
                                              it is on call, and its summary (else the first
                                              paragraph of the folder's CLAUDE.md, or of the
                                              file it imports when it only has `@path` lines,
                                              or of README.md)
  agent-mail agents add [DIR] [--name NAME] [description…]
                                              add a folder, on call: by default this repo at
                                              its main worktree, else this folder; NAME
                                              defaults to the folder's name (letters, digits,
                                              `.`, `_` and `-`). Not `/` or your home folder;
                                              a folder enclosing other agents' is added, with
                                              a note: folders below it without an entry of
                                              their own are now this agent
  agent-mail agents describe [NAME] [< about.md]
                                              what the agent owns and what to ask it for, the
                                              first line its summary. An agent describes only
                                              itself; the user describes anyone
  agent-mail agents move NAME DIR             the agent's folder is now DIR (as for add)
  agent-mail agents remove NAME               out of the directory; past mail keeps the name.
                                              An agent moves or removes only itself; the user
                                              any agent (so for `oncall add NAME DIR` moving
                                              one)
  agent-mail oncall [--json]                  agents on call: serve hands each of their threads
                                              to a Claude Code session; also why serve can't
                                              land what one pushed in the main checkout
  agent-mail oncall add [NAME [DIR]]          put an agent on call: NAME (at DIR: added, or
                                              moved there); no arguments: this folder's agent,
                                              else this repo at its main worktree, or outside
                                              any repo this folder, as its name with whitespace
                                              made `-`. /agent-mail-listen does this
  agent-mail oncall remove [NAME]             take it off (no NAME: this folder's, as add);
                                              wait won't put it back
  agent-mail serve [--interval S] [--once] [--standby]
                                              the on-call daemon (`just install-serve` runs it
                                              under launchd). Every S (5) seconds, each open
                                              thread waiting on an on-call repo goes to one
                                              Claude Code session: posted into the session that
                                              last wrote in it for the repo while that runs;
                                              resumed with `claude --bg --resume` while its
                                              prompt cache is warm; else a new background
                                              session, `claude --bg --name \"📫 REPO #N\"` in the
                                              repo, one per thread (the folder must be trusted:
                                              run `claude` there once), at most 3 a round. A
                                              thread closed with news for the repo is posted
                                              only into a running holder, and says it is
                                              closed. Sessions it started are
                                              stopped once idle for 5 min, holding a thread or
                                              not (never while busy, running a background task
                                              or waiting on you): a later message resumes them
                                              while their cache is warm. When one of them goes
                                              idle or stops, the repo's main checkout is
                                              fast-forwarded to what it pushed, `git merge
                                              --ff-only @{u}` (never fetched: its push moved
                                              @{u}); without an upstream, to its worktree
                                              branch once its thread is closed. Only on a branch, with clean
                                              tracked files and no other session working
                                              there; `oncall` and `who` say why not
                                              (status --json: cannot-land). A stopped one
                                              holding no open thread is removed (`claude rm`;
                                              the transcript stays) unless its worktree branch
                                              has commits neither HEAD nor @{u} has: `who` and
                                              `oncall` list it as unlanded. A background session of its
                                              that waits on you (a permission prompt) for 2 min
                                              is reported once: a notification, serve's log,
                                              and `who`/`oncall` while it waits. `claude agents`
                                              lists them, `claude attach ID` opens one. One serve
                                              at a time; --standby (launchd's) waits for a
                                              running one to end
  agent-mail status [--json]                  everything at once, for UIs. --json gives each
                                              thread's unread (new to me) and unread-to-me
                                              (those of them addressed to me: what an inbox
                                              counts), unread-by, flow, waiting-s, listener
  agent-mail hook                             SessionStart hook output
  agent-mail whoami                           who the caller is

Viewer for open: AGENT_MAIL_VIEWER, else the macOS default app for .md.
Editor for reply: AGENT_MAIL_EDITOR, else $VISUAL, else $EDITOR, else `open -W`
(your Markdown app, until you quit it). E.g. AGENT_MAIL_EDITOR='zed --wait'.
A notification click runs `agent-mail open ID` (needs terminal-notifier).
Who am I: in your own shell, `user`. In an agent's shell, the agent whose
folder in the directory (<store>/agents) is this folder or the nearest above
it, so a repo nested in an agent's folder is that agent unless it has an
entry of its own; a worktree counts as its repo, a moved folder is found by
its file id (and the directory follows it), and outside git the folder the
session started in is tried too. Else the repo's name (main worktree), or
outside any repo the folder's; that folder joins the directory, on call,
with its first mail. --as NAME or AGENT_MAIL_AS
overrides. Mail goes only to `user` and to names in the directory.
Bodies are Markdown; attachments are
copied into the thread and `read` prints their absolute paths. --date backfills
a timestamp when importing old threads; otherwise the tool stamps time.
A message written in a Claude Code session records it (CLAUDE_CODE_SESSION_ID),
so serve posts the thread's later messages into that session.
serve runs AGENT_MAIL_CLAUDE, else claude on PATH, else the one the login
shell finds, and reads Claude Code's registry (sessions/PID.json) and
transcripts (projects/*/ID.jsonl) under CLAUDE_CONFIG_DIR, else ~/.claude.
A classic wait titles its WezTerm tab (AGENT_MAIL_WEZTERM, else wezterm on PATH).
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
        "agents" (cmd-agents opts args)
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
