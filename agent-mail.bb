#!/usr/bin/env bb
;; agent-mail: durable, file-based mail between agents (and you) on one machine.
;; One write-once Markdown file per message; a thread's owner and status are
;; derived from its messages, never edited. Run `agent-mail help`.
;; Store: $AGENT_MAIL_DIR, else ~/.local/share/agent-mail (its own git repo).
(ns agent-mail
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- store

(def store (fs/path (or (System/getenv "AGENT_MAIL_DIR") (fs/xdg-data-home "agent-mail"))))
(def threads-dir (fs/path store "threads"))
(def duty-dir (fs/path store "duty"))
(def stale-s 30)

(defn fail [& msg]
  (binding [*out* *err*] (println (str "agent-mail: " (str/join " " msg))))
  (System/exit 1))

(defn git [& args]
  (apply p/shell {:dir (str store) :out :string :err :string :continue true} "git" args))

(declare ensure-numbers!)

(defn ensure-store! []
  (fs/create-dirs threads-dir)
  (fs/create-dirs duty-dir)
  (when-not (fs/exists? (fs/path store ".git"))
    (git "init" "-q")
    (spit (str (fs/path store ".gitignore")) "duty/\ndrafts/\nview/\nseen/\n")
    (git "add" "-A")
    (git "commit" "-q" "-m" "📬 init: agent-mail store"))
  (ensure-numbers!))

(defn commit! [msg]
  (loop [n 0]
    (git "add" "-A")
    (let [r (git "commit" "-q" "-m" msg)]
      (when (and (pos? (:exit r))
                 (str/includes? (str (:err r) (:out r)) "index.lock")
                 (< n 20))
        (Thread/sleep 200)
        (recur (inc n))))))

;; ---------------------------------------------------------------- time, text

(def ^:private iso (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
(defn fmt-utc [^java.time.Instant i] (.format (.atZone i java.time.ZoneOffset/UTC) iso))
(defn now-utc [] (fmt-utc (java.time.Instant/now)))
(defn epoch-s [ts] (.getEpochSecond (java.time.Instant/parse ts)))
(defn age-s [ts] (- (epoch-s (now-utc)) (epoch-s ts)))
(defn stamp->name [ts] (str/replace ts ":" ""))
(def ^:private local-fmt (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm xxx"))
(defn local-time
  "A stored UTC stamp shown in the machine's time zone, e.g. 2026-10-31 04:25 +01:00."
  [ts]
  (if ts (.format (.atZone (java.time.Instant/parse ts) (java.time.ZoneId/systemDefault)) local-fmt) ""))
(defn after
  "The later of `date` and one millisecond past `last-date`, so messages in a
   thread always sort in the order they were written."
  [date last-date]
  (if (and last-date (<= (compare date last-date) 0))
    (fmt-utc (.plusMillis (java.time.Instant/parse last-date) 1))
    date))
(defn rand-hex [] (format "%04x" (rand-int 65536)))

(defn normalize-date
  "Accepts 2026-10-31 or 2026-10-31T09:00:00Z; anything else is refused."
  [d]
  (cond (nil? d) nil
        (re-matches #"\d{4}-\d{2}-\d{2}" d) (str d "T00:00:00.000Z")
        (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z" d) (str (subs d 0 19) ".000Z")
        :else (fail "--date must be YYYY-MM-DD or YYYY-MM-DDTHH:MM:SSZ (UTC), got" d)))

(defn slug [s]
  (let [x (-> (str/lower-case s) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))]
    (cond (str/blank? x) "thread"
          (<= (count x) 40) x
          :else (let [cut (subs x 0 40)]
                  (if-let [i (str/last-index-of cut "-")] (subs cut 0 i) cut)))))

(defn first-line [s] (-> s str/trim str/split-lines first (or "")))

(defn truncate [s n] (if (> (count s) n) (str (subs s 0 (dec n)) "…") s))

;; ---------------------------------------------------------------- frontmatter

(defn- yaml-scalar [v]
  (str "\"" (-> (str v) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn fm-str
  "Frontmatter with every scalar quoted, so dates stay strings when parsed."
  [m]
  (str "---\n"
       (str/join (for [[k v] m :when (some? v)]
                   (str (name k) ": "
                        (if (sequential? v)
                          (str "[" (str/join ", " (map yaml-scalar v)) "]")
                          (yaml-scalar v))
                        "\n")))
       "---\n"))

(defn parse-doc [s]
  (if-let [[_ fm body] (re-matches #"(?s)---\n(.*?)\n---\n?(.*)" s)]
    [(into {} (yaml/parse-string fm)) body]
    [{} s]))

;; ---------------------------------------------------------------- threads

(defn thread-dir [id] (fs/path threads-dir id))
(defn thread-ids []
  (when (fs/exists? threads-dir)
    (->> (fs/list-dir threads-dir)
         (filter #(fs/exists? (fs/path % "thread.md")))
         (map #(str (fs/file-name %))) sort)))

(declare fold)

(defn resolve-id
  "Exact id, a thread number (`7` or `#7`), or a unique fragment of an id."
  [s]
  (let [ids (thread-ids)]
    (cond (some #{s} ids) s
          (re-matches #"#?\d+" s)
          (let [n (parse-long (str/replace s "#" ""))]
            (or (some #(when (= n (:number (fold %))) %) ids) (fail "no thread" (str "#" n))))
          :else (let [hits (filter #(str/includes? % s) ids)]
                  (case (count hits)
                    1 (first hits)
                    0 (fail "no thread matches" s)
                    (fail "ambiguous thread" s "matches" (str/join ", " hits)))))))

(defn read-msg [f]
  (let [[fm body] (parse-doc (slurp (str f)))]
    (assoc fm :file (str (fs/file-name f)) :body (str/trim body))))

(defn fold
  "A thread's state, derived from its messages: owner is the `to` of the last
   message, status the last `status` set (default open)."
  [id]
  (let [[meta _] (parse-doc (slurp (str (fs/path (thread-dir id) "thread.md"))))
        msgs (map read-msg (sort (fs/glob (fs/path (thread-dir id) "msgs") "*.md")))
        last-m (last msgs)]
    (assoc meta :id id :msgs (vec msgs) :count (count msgs)
           :number (some-> (:number meta) str parse-long)
           :owner (:to last-m) :updated (:date last-m)
           :status (or (some :status (reverse msgs)) "open"))))

(defn all-threads [] (map fold (thread-ids)))

;; What each participant has seen: <store>/seen/<who>.edn maps thread id to the
;; last message file read. Not committed; losing it only means re-reading.
(def seen-dir (fs/path store "seen"))
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
  (let [mark (get (seen-map who) (:id th))]
    (filter #(and (not= who (:from %)) (or (nil? mark) (pos? (compare (:file %) mark)))) (:msgs th))))
(declare open-for)

(defn pick-thread
  "No thread given: let the user choose. Candidates are the open threads whose
   turn is `me`'s, else every open thread. One candidate is taken as is;
   otherwise fzf, or a numbered prompt without it."
  [me]
  (let [cands (let [mine (open-for me)]
                (if (seq mine) mine (filter #(= "open" (:status %)) (all-threads))))
        line (fn [t] (format "#%d  %s  (%s, %d msgs%s)" (:number t) (:subject t) (:from t) (:count t)
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
                [meta _] (parse-doc (slurp f))]
          :when (nil? (:number meta))]
    (spit f (str (fm-str (assoc meta :number (next-number))) "\n"))))
(defn open-for [who] (filter #(and (= who (:owner %)) (= "open" (:status %))) (all-threads)))

(defn new-id [subject created]
  (let [base (str (subs created 0 10) "-" (slug subject))
        ids (set (thread-ids))]
    (if-not (ids base) base
            (some #(when-not (ids %) %) (map #(str base "-" %) (iterate inc 2))))))

(defn attach! [id paths]
  (let [dir (fs/path (thread-dir id) "files")]
    (fs/create-dirs dir)
    (vec (for [p paths]
           (let [p (fs/path p)
                 _ (when-not (fs/regular-file? p) (fail "attachment not found:" (str p)))
                 nm (str (fs/file-name p))
                 nm (if (and (fs/exists? (fs/path dir nm))
                             (not= (slurp (str p)) (slurp (str (fs/path dir nm)))))
                      (str (subs (str (hash (slurp (str p)))) 0 6) "-" nm)
                      nm)]
             (fs/copy p (fs/path dir nm) {:replace-existing true})
             (str "files/" nm))))))

(defn write-msg! [id {:keys [from to date in-reply-to status attachments body]}]
  (let [dir (fs/path (thread-dir id) "msgs")
        last-date (some-> (seq (sort (fs/glob dir "*.md"))) last read-msg :date)
        date (after (or date (now-utc)) last-date)
        nm (str (stamp->name date) "__" from "__" (rand-hex) ".md")
        tmp (fs/path dir (str "." nm ".tmp"))]
    (fs/create-dirs dir)
    (spit (str tmp)
          (str (fm-str (cond-> {:from from :to to :date date}
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

(defn caller [opts] (or (:as opts) (System/getenv "AGENT_MAIL_AS") (repo-name) "user"))

(defn duty-records []
  (when (fs/exists? duty-dir)
    (->> (fs/glob duty-dir "*.edn") (map #(edn/read-string (slurp (str %)))) (sort-by :heartbeat))))
(defn fresh? [rec] (< (age-s (:heartbeat rec)) stale-s))
(defn on-duty [who] (filter #(and (= who (:repo %)) (fresh? %) (= "waiting" (:state %))) (duty-records)))
(defn write-duty! [who session state]
  (fs/create-dirs duty-dir)
  (spit (str (fs/path duty-dir (str who "__" session ".edn")))
        (pr-str {:repo who :session session :state state :heartbeat (now-utc)
                 :pid (.pid (java.lang.ProcessHandle/current))})))

;; ---------------------------------------------------------------- delivery notes

(def launcher (str (fs/path (fs/parent (fs/absolutize *file*)) "bin" "agent-mail")))

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
        (println (str "→ " to ": on duty (session " (str/join ", " (map :session d))
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
        to (or (:to opts) (fail "send needs --to NAME (or `agent-mail @NAME text`)"))
        _ (when (= from to) (fail "refusing to mail yourself (" from ")"))
        body (body-from args)
        _ (when (str/blank? body) (fail "empty body"))
        subject (or (:subject opts) (truncate (first-line body) 70))
        created (or (normalize-date (:date opts)) (now-utc))
        id (or (:id opts) (new-id subject created))
        number (next-number)]
    (when (fs/exists? (thread-dir id)) (fail "thread exists:" id))
    (fs/create-dirs (thread-dir id))
    (spit (str (fs/path (thread-dir id) "thread.md"))
          (str (fm-str {:subject subject :from from :created created :number number}) "\n"))
    (let [atts (attach! id (:attach opts))]
      (write-msg! id {:from from :to to :date created :attachments atts :body body}))
    (commit! (str "📨 " from " → " to ": " subject))
    (println id)
    (delivered! from to id subject)))

(defn other-party [th me]
  (or (some #(when (not= me (:from %)) (:from %)) (reverse (:msgs th)))
      (when (not= me (:owner th)) (:owner th))))

(declare cmd-compose)

(defn cmd-reply [opts args {:keys [close?]}]
  (ensure-store!)
  (when (and (not close?) (empty? (rest args)) (or (:edit opts) (System/console)))
    (cmd-compose opts args)
    (System/exit 0))
  (let [me (caller opts)
        id (if (first args) (resolve-id (first args)) (pick-thread me))
        th (fold id)
        to (or (:to opts) (other-party th me) (fail "nobody to reply to; use --to NAME"))
        body (body-from (rest args))
        body (if (and close? (str/blank? body)) "Closed." body)
        _ (when (str/blank? body) (fail "empty body"))
        atts (attach! id (:attach opts))
        in-reply-to (:file (last (:msgs th)))]
    (write-msg! id {:from me :to to :date (normalize-date (:date opts))
                    :in-reply-to in-reply-to :status (when close? "done")
                    :attachments atts :body body})
    (commit! (str (if close? "✅ " "↩️ ") me " → " to ": " (:subject th)))
    (mark-seen! me (fold id))
    (println id)
    (when-not close? (delivered! me to id (:subject th)))))

(defn render-thread
  "The thread as one Markdown document; with `msgs`, only those messages."
  ([th] (render-thread th (:msgs th)))
  ([th msgs]
  (let [files (str (fs/path (thread-dir (:id th)) "files"))
        abs #(str/replace % "](files/" (str "](" files "/"))]
    (str "# " (:subject th) "\n"
         "thread: #" (:number th) " " (:id th) " · from: " (:from th) " · owner: " (:owner th)
         " · status: " (:status th) " · created: " (local-time (:created th)) "\n"
         (when (< (count msgs) (:count th))
           (str "showing " (count msgs) " of " (:count th) " messages; `agent-mail read " (:number th) "` for all\n"))
         (str/join
          (for [m msgs]
            (str "\n## " (:from m) " → " (:to m) " · " (local-time (:date m))
                 (when (:status m) (str " · " (:status m))) "\n"
                 (when (seq (:attachments m))
                   (str "attachments: " (str/join ", " (map #(str files "/" (subs % 6)) (:attachments m))) "\n"))
                 "\n" (abs (:body m)) "\n")))))))

(def reply-marker "<!-- agent-mail: the thread above is quoted with `>`; answer inline under any line, or write below this marker. Quoted text is kept only next to what you write. Sent when you close the editor; nothing is sent if you write nothing. -->")

(defn quote-lines [text] (str/join "\n" (map #(if (str/blank? %) ">" (str "> " %)) (str/split-lines text))))

(defn context-before
  "Indexes of the quoted lines to keep above an inline answer: back from i,
   up to two non-blank lines, never past what was already emitted."
  [lines i last-emitted]
  (loop [j (dec i), n 0, acc ()]
    (if (or (<= j last-emitted) (>= n 2) (not (str/starts-with? (nth lines j) ">")))
      (drop-while #(str/blank? (subs (nth lines %) 1)) acc)
      (recur (dec j) (if (str/blank? (subs (nth lines j) 1)) n (inc n)) (conj acc j)))))

(defn reply-from-draft
  "What the user wrote in a draft: their lines, each preceded by up to two
   quoted lines as context, with `> […]` where quoted text was skipped. Lines
   after the marker are a plain reply with no context."
  [text]
  (let [lines (vec (str/split-lines text))
        mi (or (first (keep-indexed #(when (= %2 reply-marker) %1) lines)) (count lines))
        kind (fn [i l] (cond (= i mi) :quoted
                             (> i mi) (if (str/blank? l) :blank :new)
                             (str/starts-with? l ">") :quoted
                             (str/blank? l) :blank
                             (= l "---") :quoted
                             :else :new))
        kinds (map-indexed kind lines)
        next-new? (fn [i] (= :new (some #(when (not= :blank (nth kinds %)) (nth kinds %)) (range (inc i) (count lines)))))]
    (loop [i 0, out [], last-emitted -1]
      (if (>= i (count lines))
        (str/trim (str/join "\n" out))
        (let [k (nth kinds i), l (nth lines i)]
          (case k
            :new (let [ctx (if (< i mi) (context-before lines i last-emitted) [])
                       gap? (and (seq ctx) (> (first ctx) (inc last-emitted)) (pos? (first ctx)))
                       out (cond-> out gap? (conj "> […]"))
                       out (into out (map #(nth lines %) ctx))]
                   (recur (inc i) (conj out l) i))
            :blank (recur (inc i) (if (and (= last-emitted (dec i)) (= :new (nth kinds last-emitted)) (next-new? i)) (conj out l) out) (if (and (= last-emitted (dec i)) (next-new? i)) i last-emitted))
            (recur (inc i) out last-emitted)))))))

(defn editor-cmd
  "AGENT_MAIL_EDITOR, else $VISUAL, else $EDITOR, else on macOS `open -W` (the
   default Markdown app, waiting until you quit it)."
  []
  (let [env #(let [v (System/getenv %)] (when-not (str/blank? v) v))]
    (str/split (or (env "AGENT_MAIL_EDITOR") (env "VISUAL") (env "EDITOR")
                   (when (and (fs/which "open") (str/includes? (System/getProperty "os.name") "Mac")) "open -W")
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
        f (str (fs/path dir (str id ".md")))]
    (fs/create-dirs dir)
    (spit f (render-thread th (fresh-or-last th me)))
    (mark-seen! me th)
    (p/shell {:continue true} (if-let [v (System/getenv "AGENT_MAIL_VIEWER")] v "open") f)
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
        _ (spit f (str (quote-lines (render-thread th (fresh-or-last th me))) "\n\n---\n" reply-marker "\n\n"))
        cmd (conj (editor-cmd) f)
        r (apply p/shell {:continue true} cmd)
        _ (when (pos? (:exit r)) (fail "editor failed:" (str/join " " cmd)))
        body (reply-from-draft (slurp f))]
    (if (str/blank? body)
      (binding [*out* *err*] (println "no reply written; nothing sent"))
      (let [to (or (:to opts) (other-party th me) (fail "nobody to reply to; draft kept at" f))]
        (write-msg! id {:from me :to to :in-reply-to (:file (last (:msgs th))) :body body})
        (commit! (str "↩️ " me " → " to ": " (:subject th)))
        (mark-seen! me (fold id))
        (fs/delete f)
        (println id)
        (delivered! me to id (:subject th))))))

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
                         (:number t) (:id t) (str (:from t) "→" (:owner t)) (:status t) (:count t)
                         (if (pos? (:unread t)) (format "%d new" (:unread t)) "     ")
                         (local-time (:updated t)) (:subject t)))))))

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
              (and deadline (> (System/currentTimeMillis) deadline))
              (do (write-duty! me session "idle") (System/exit 3))
              :else (do (Thread/sleep (* 1000 interval)) (recur)))))))

(defn cmd-who [opts]
  (let [recs (map #(assoc % :age-s (age-s (:heartbeat %)) :stale (not (fresh? %))) (duty-records))]
    (if (:json opts)
      (println (json/generate-string recs {:pretty true}))
      (doseq [r recs]
        (println (format "%-20s %-28s %-8s %5ds ago%s" (:repo r) (:session r) (:state r) (:age-s r)
                         (if (and (:stale r) (= "waiting" (:state r))) "  (stale)" "")))))))

(defn cmd-hook
  "SessionStart hook mode: one line listing the threads waiting on this repo;
   silent otherwise; never fails. Mail for the user is the user's business (they
   get notifications), so sessions are not told about it."
  [opts]
  (try
    (ensure-numbers!)
    (let [me (caller opts)
          line (fn [who ths]
                 (println (format "agent-mail: %d thread%s waiting on %s: %s. Read with `agent-mail read <number>`."
                                  (count ths) (if (= 1 (count ths)) "" "s") who
                                  (str/join "; " (map #(format "#%d %s \"%s\" from %s" (:number %) (:id %) (:subject %) (:from %)) ths)))))]
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

(def value-opts #{"to" "subject" "as" "session" "date" "id" "timeout" "interval"})
(def flag-opts #{"json" "all" "edit"})

(defn parse-argv
  "Options may appear anywhere: `--key value`, flags, repeated `--attach FILE`;
   `--` ends option parsing. Everything else is positional."
  [argv]
  (loop [[a & more :as xs] argv, args [], opts {:attach []}]
    (cond
      (empty? xs) {:args args :opts (cond-> opts
                                      (:timeout opts) (update :timeout parse-long)
                                      (:interval opts) (update :interval parse-long))}
      (= a "--") {:args (into args more) :opts opts}
      (= a "--attach") (if (seq more)
                         (recur (rest more) args (update opts :attach conj (first more)))
                         (fail "--attach needs a file"))
      (and (str/starts-with? a "--") (value-opts (subs a 2)))
      (if (seq more)
        (recur (rest more) args (assoc opts (keyword (subs a 2)) (first more)))
        (fail a "needs a value"))
      (and (str/starts-with? a "--") (flag-opts (subs a 2)))
      (recur more args (assoc opts (keyword (subs a 2)) true))
      (#{"-h" "--help"} a) (recur more (conj args "help") opts)
      (str/starts-with? a "--") (fail "unknown option" a "(try `agent-mail help`)")
      :else (recur more (conj args a) opts))))

(defn -main [argv]
  (let [argv (if (str/starts-with? (or (first argv) "") "@")
               (concat ["send" "--to" (subs (first argv) 1)] (rest argv))
               argv)
        {:keys [args opts]} (parse-argv argv)
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
      (fail "unknown command" cmd "(try `agent-mail help`)"))))

(-main *command-line-args*)
