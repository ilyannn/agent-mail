;; agent-mail.core: the pure half of agent-mail. Text, time, frontmatter,
;; thread state, the status view, serve's decisions and the texts it posts,
;; draft extraction and argv parsing; nothing
;; here touches the store, the environment or a process. Loaded by
;; agent-mail.bb (and test.bb) from the repo's src/ directory; refusals are
;; thrown as ::usage ex-infos and turned into exit codes by the script.
(ns agent-mail.core
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]))

(defn usage-error
  "A refusal the script reports on stderr and exits 1 with."
  [& msg]
  (ex-info (str/join " " msg) {:type ::usage}))

(defn usage? [e] (= ::usage (:type (ex-data e))))

;; ---------------------------------------------------------------- time, text

(def ^:private iso (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
(defn fmt-utc [^java.time.Instant i] (.format (.atZone i java.time.ZoneOffset/UTC) iso))
(defn now-utc [] (fmt-utc (java.time.Instant/now)))
(defn epoch-s [ts] (.getEpochSecond (java.time.Instant/parse ts)))
(defn age-s
  "Whole seconds from stamp `ts` to stamp `now` (default: the current time)."
  ([ts] (age-s ts (now-utc)))
  ([ts now] (- (epoch-s now) (epoch-s ts))))
(defn duration
  "Seconds as one rounded-down unit: 45s, 12m, 3h, 2d."
  [s]
  (cond (< s 60) (str s "s")
        (< s 3600) (str (quot s 60) "m")
        (< s 86400) (str (quot s 3600) "h")
        :else (str (quot s 86400) "d")))
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

(defn normalize-date
  "Accepts 2026-10-31 or 2026-10-31T09:00:00Z; anything else is refused."
  [d]
  (cond (nil? d) nil
        (re-matches #"\d{4}-\d{2}-\d{2}" d) (str d "T00:00:00.000Z")
        (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z" d) (str (subs d 0 19) ".000Z")
        :else (throw (usage-error "--date must be YYYY-MM-DD or YYYY-MM-DDTHH:MM:SSZ (UTC), got" d))))

(defn slug
  "Lower-case ASCII words joined by `-`, at most 40 characters cut at a word
   boundary; `thread` when nothing is left."
  [s]
  (let [x (-> (str/lower-case s) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))]
    (cond (str/blank? x) "thread"
          (<= (count x) 40) x
          :else (let [cut (subs x 0 40)]
                  (if-let [i (str/last-index-of cut "-")] (subs cut 0 i) cut)))))

(defn first-line [s] (-> s str/trim str/split-lines first (or "")))

(defn truncate [s n] (if (> (count s) n) (str (subs s 0 (dec n)) "…") s))

(defn new-id
  "`<date>-<slug>`, with `-2`, `-3`… appended while the id is taken."
  [subject created taken]
  (let [base (str (subs created 0 10) "-" (slug subject))
        taken (set taken)]
    (if-not (taken base) base
            (some #(when-not (taken %) %) (map #(str base "-" %) (iterate inc 2))))))

(defn valid-name?
  "A participant name: non-empty, no whitespace or path separators, so it can
   sit in a message file name."
  [s]
  (boolean (and (string? s) (re-matches #"[^\s/\\]+" s))))

;; ---------------------------------------------------------------- frontmatter

(defn- yaml-scalar [v]
  (str "\"" (-> (str v) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"") (str/replace "\n" "\\n")) "\""))

(defn fm-str
  "Frontmatter with every scalar quoted, so dates stay strings when parsed;
   booleans are written bare (true, false). nil values are left out."
  [m]
  (str "---\n"
       (str/join (for [[k v] m :when (some? v)]
                   (str (name k) ": "
                        (cond (sequential? v) (str "[" (str/join ", " (map yaml-scalar v)) "]")
                              (boolean? v) (str v)
                              :else (yaml-scalar v))
                        "\n")))
       "---\n"))

(defn parse-doc
  "[frontmatter-map body]; a document without frontmatter is all body."
  [s]
  (if-let [[_ fm body] (re-matches #"(?s)---\n(.*?)\n---\n?(.*)" s)]
    [(into {} (yaml/parse-string fm)) body]
    [{} s]))

;; ---------------------------------------------------------------- thread state

(defn thread-state
  "A thread's state, derived from its messages (in file order): owner is the
   `to` of the last message, status the last `status` set (default open).
   The messages are kept as read, :session (the Claude Code session that
   wrote one) included."
  [meta id msgs]
  (let [msgs (vec msgs) last-m (peek msgs)]
    (assoc meta :id id :msgs msgs :count (count msgs)
           :number (some-> (:number meta) str parse-long)
           :owner (:to last-m) :last-from (:from last-m) :updated (:date last-m)
           :status (or (some :status (rseq msgs)) "open"))))

(defn unread
  "Messages that `who` has not seen (`mark` is the last file read) and did not write."
  [msgs who mark]
  (filter #(and (not= who (:from %)) (or (nil? mark) (pos? (compare (:file %) mark)))) msgs))

(defn closed-news?
  "A closed thread with news `who` would otherwise never hear of (`mark` is the
   last file they read): unread, addressed to them, a reply, or the close of a
   thread they started. `wait` announces a thread only while it is open and
   `who`'s turn, so a reply closed before its next poll went unannounced. The
   asker's own close of an answered thread is not news to the answerer."
  [th who mark]
  (and (= "done" (:status th))
       (boolean (some #(and (= who (:to %)) (or (nil? (:status %)) (= who (:from th))))
                      (unread (:msgs th) who mark)))))

(defn other-party
  "Whom a reply from `me` goes to: the last writer that isn't me, else the owner."
  [th me]
  (or (some #(when (not= me (:from %)) (:from %)) (rseq (:msgs th)))
      (when (not= me (:owner th)) (:owner th))))

(defn resolve-id
  "Exact id, a thread number (`7` or `#7`), or a unique fragment of an id.
   `number-of` gives a thread's number from its id."
  [s ids number-of]
  (cond (some #{s} ids) s
        (re-matches #"#?\d+" s)
        (let [n (parse-long (str/replace s "#" ""))]
          (or (some #(when (= n (number-of %)) %) ids) (throw (usage-error "no thread" (str "#" n)))))
        :else (let [hits (filter #(str/includes? % s) ids)]
                (case (count hits)
                  1 (first hits)
                  0 (throw (usage-error "no thread matches" s))
                  (throw (usage-error "ambiguous thread" s "matches" (str/join ", " hits)))))))

(defn render-thread
  "The thread as one Markdown document; `files` is the absolute path of its
   attachment directory, so `files/x` links resolve from anywhere. With `msgs`,
   only those messages."
  ([th files] (render-thread th files (:msgs th)))
  ([th files msgs]
   (let [abs #(str/replace % "](files/" (str "](" files "/"))]
     (str "# " (:subject th) "\n"
          "thread: #" (:number th) " " (:id th) " · started by: " (:from th) " · waiting on: " (:owner th)
          " · status: " (:status th) " · created: " (local-time (:created th))
          (when-let [f (:forwarded-from th)]
            (str " · forwarded from: " (when-let [n (:forwarded-from-number th)] (str "#" n " ")) f))
          "\n"
          (when (< (count msgs) (:count th))
            (str "showing " (count msgs) " of " (:count th) " messages; `agent-mail read " (:number th) "` for all\n"))
          (str/join
           (for [m msgs]
             (str "\n## " (:from m) " → " (:to m) " · " (local-time (:date m))
                  (when (:status m) (str " · " (:status m))) "\n"
                  (when (seq (:attachments m))
                    (str "attachments: " (str/join ", " (map #(str files "/" (subs % 6)) (:attachments m))) "\n"))
                  "\n" (abs (:body m)) "\n")))))))

;; ---------------------------------------------------------------- forwarding

(defn forward-subject
  "A forward's subject: the source's with \"Fwd: \" in front, once."
  [subject]
  (if (str/starts-with? (str subject) "Fwd: ") subject (str "Fwd: " subject)))

(defn pick-message
  "The message `which` names in `th`: its 1-based position (`2`) or its file
   name, as `read --json` gives it. Throws a usage error when there is none."
  [th which]
  (let [msgs (vec (:msgs th))
        n (when (re-matches #"\d+" (str which)) (parse-long which))]
    (or (if n (get msgs (dec n)) (some #(when (= which (:file %)) %) msgs))
        (throw (usage-error "no message" (pr-str which) "in thread" (str "#" (:number th))
                            (str "(it has " (count msgs) "; give a position or a file name)"))))))

(defn file-refs
  "The thread files `msgs` use, in order and once each: their attachments,
   then files their bodies link to (`](files/x)`)."
  [msgs]
  (vec (distinct (concat (mapcat :attachments msgs)
                         (for [m msgs, [_ ref] (re-seq #"\]\((files/[^)\s\"]+)" (str (:body m)))] ref)))))

(defn forward-text
  "The forwarded part of a forward's body: a line naming the source thread
   (and how to read it), then each of `msgs` under a `### from → to · time`
   heading, which nests under the message's own heading in `read`. The
   bodies are kept as written: their files are copied under the same names."
  [th msgs]
  (let [all? (= (count msgs) (count (:msgs th)))
        pos (when-not all? (inc (.indexOf ^java.util.List (vec (:msgs th)) (first msgs))))]
    (str (if all?
           (str "Forwarded thread #" (:number th) " **" (:subject th) "**, started by " (:from th)
                ", " (count msgs) (if (= 1 (count msgs)) " message" " messages"))
           (str "Forwarded message " pos " of " (count (:msgs th)) " from thread #" (:number th)
                " **" (:subject th) "**"))
         " (`agent-mail read " (:id th) "`)\n"
         (str/join
          (for [m msgs]
            (str "\n### " (:from m) " → " (:to m) " · " (local-time (:date m))
                 (when (:status m) (str " · " (:status m))) "\n\n"
                 (str/trim (str (:body m))) "\n"))))))

(defn forward-body
  "The whole body of a forward: the note, a rule, then the forwarded text;
   without a note, the forwarded text alone."
  [note forwarded]
  (if (str/blank? note) forwarded (str (str/trim note) "\n\n---\n\n" forwarded)))

;; ---------------------------------------------------------------- the agent directory
;; The only source of agent names: one entry per agent, <store>/agents/<name>.md,
;; mapping the name to its folder, so moving or renaming the folder keeps
;; the name. Frontmatter: name, dir (the real path), file-id (the folder's
;; device and inode, which follow a move), oncall, kind (claude) and added;
;; the body says what the agent owns and what to ask it for, its first line
;; being the summary.

(def postmaster
  "The postmaster's name: a role of serve's (see postmaster-actions), not
   an agent. Mail may go to it, like to `user`; only serve writes as it."
  "postmaster")

(def postmaster-agent
  "The agent serve hands postmaster's mail to: the one that owns the tool."
  "agent-mail")

(defn delivery-owner
  "Whose sessions get the mail waiting on `owner`: postmaster-agent's for
   postmaster's, else `owner`'s own."
  [owner]
  (if (= postmaster owner) postmaster-agent owner))

(defn agent-name?
  "A name the directory takes: a participant name (valid-name?) other than
   `user` and `postmaster`, starting with neither `.` nor `-`, so its entry
   file is not hidden and the name never reads as an option."
  [s]
  (boolean (and (valid-name? s) (not (contains? #{"user" postmaster} s)) (re-matches #"[^.\-].*" s))))

(defn name-from-dir
  "The name `agents add` gives a folder by default: the folder's name with
   runs of whitespace made `-` (as address-from-dir), keeping only letters,
   digits, `.`, `_` and `-`, without leading dots or dashes. nil when that
   leaves no name the directory takes (agent-name?)."
  [folder-name]
  (let [x (-> (str folder-name) str/trim (str/replace #"[\s/\\]+" "-")
              (str/replace #"[^\p{L}\p{N}._-]" "") (str/replace #"^[.-]+" ""))]
    (when (agent-name? x) x)))

(defn path-segments
  "A path's segments: `/a//b/` is [\"a\" \"b\"]."
  [p]
  (vec (remove str/blank? (str/split (str p) #"/"))))

(defn same-path?
  "Whether paths `a` and `b` name the same place, segment by segment: a
   trailing or doubled slash aside, `/x/app` is never `/x/app-old`."
  [a b]
  (boolean (and a b (= (path-segments a) (path-segments b)))))

(defn- below-or-at? [segs dir-segs] (= dir-segs (take (count dir-segs) segs)))

(defn folder-chain
  "Absolute folder `start` and each folder above it, nearest first, up to
   `/`. Inside git too: a repo nested in an agent's folder belongs to that
   agent unless it has an entry of its own."
  [start]
  (let [segs (path-segments start)]
    (vec (for [n (range (count segs) -1 -1)]
           (str "/" (str/join "/" (take n segs)))))))

(defn worktree-path
  "Folder `path` inside linked worktree `top` as the same place in the main
   worktree `main`, so a worktree counts as its repo; `path` itself when
   `top` is the main worktree or `path` is not inside it."
  [path top main]
  (let [p (path-segments path) t (path-segments top) m (path-segments main)]
    (if (and (not= t m) (below-or-at? p t))
      (str "/" (str/join "/" (concat m (drop (count t) p))))
      path)))

(defn trusted?
  "Whether Claude Code trusts the first folder of `chain` (folder-chain's):
   its own trust, else that of the nearest folder above it that has any,
   as trust reaches plain folders below a trusted one. A git repo's root
   (`repo-root?`) is not reached from above: without its own trust, it and
   what lies below it are untrusted. `accepted?` tells whether a folder's
   trust dialog was accepted."
  [chain accepted? repo-root?]
  (loop [[f & more] chain]
    (cond (nil? f) false
          (accepted? f) true
          (repo-root? f) false
          :else (recur more))))

(defn- preferred
  "The entry to take when several share a folder: one on call, then by name."
  [entries]
  (first (sort-by (juxt (comp not :oncall) :name) entries)))

(defn find-entry
  "The directory entry of the folders in `chain` (folder-chain's, nearest
   first), as [entry folder how]: the nearest folder that is an entry's dir,
   compared segment by segment (how :path); else the nearest whose file id
   is an entry's (:moved: that folder was moved there). `file-id-of` gives a
   folder's file id, nil when unknown. nil when nothing matches."
  [entries chain file-id-of]
  (or (some (fn [f] (when-let [es (seq (filter #(same-path? (:dir %) f) entries))]
                      [(preferred es) f :path]))
            chain)
      (when (some :file-id entries)
        (some (fn [f] (when-let [id (file-id-of f)]
                        (when-let [es (seq (filter #(= id (:file-id %)) entries))]
                          [(preferred es) f :moved])))
              chain))))

(defn edit-distance
  "Levenshtein distance between strings `a` and `b`."
  [a b]
  (let [b (vec b)]
    (peek (reduce (fn [prev [i x]]
                    (reduce (fn [row [j y]]
                              (conj row (min (inc (peek row)) (inc (prev (inc j))) (+ (prev j) (if (= x y) 0 1)))))
                            [(inc i)] (map-indexed vector b)))
                  (vec (range (inc (count b))))
                  (map-indexed vector a)))))

(defn closest-names
  "Up to three of `names` closest to `s`, a name that is not among them:
   those that contain it, or that it contains when three characters or
   longer, first; then those within a few edits (a third of its length, at
   least two) that keep something of the name; case aside, nearest first,
   then by name."
  [s names]
  (let [l (str/lower-case (str s))]
    (->> (for [n (distinct names)
               :let [m (str/lower-case n)
                     d (edit-distance l m)
                     sub? (or (str/includes? m l) (and (<= 3 (count m)) (str/includes? l m)))]
               :when (and (not= n s) (or sub? (and (<= d (max 2 (quot (count l) 3))) (< d (count m)))))]
           [(if sub? 0 1) d n])
         sort (take 3) (mapv peek))))

(defn word-list
  "Names in a sentence: `a`, `a or b`, `a, b or c` (with `word` \"or\")."
  [xs word]
  (if (< (count xs) 2) (str/join xs) (str (str/join ", " (butlast xs)) " " word " " (last xs))))

(defn- or-list [xs] (word-list xs "or"))

(defn broad-folder?
  "Whether `folder` is too broad to be an agent's: `/`, or the home folder
   `home`. Every folder below an agent's that has no entry of its own is
   that agent."
  [folder home]
  (boolean (or (empty? (path-segments folder)) (same-path? folder home))))

(defn enclosed-agents
  "The names of the `entries` other than `nm` whose folders lie below
   `folder`, sorted: they keep their names when `folder` becomes `nm`'s."
  [entries folder nm]
  (let [f (path-segments folder)]
    (vec (sort (for [e entries
                     :let [s (path-segments (:dir e))]
                     :when (and (:dir e) (not= nm (:name e)) (> (count s) (count f)) (below-or-at? s f))]
                 (:name e))))))

(defn unknown-agent
  "Why `nm` is refused as a recipient or an agent: it is not in the
   directory; up to three close names from `names`, and how to add a folder."
  [nm names]
  (let [close (closest-names nm names)]
    (str "no agent named " nm " in the directory"
         (if (seq close) (str "; did you mean " (or-list close) "?") ";")
         " `agent-mail agents` lists them, and `agent-mail agents add DIR` adds a folder")))

(defn description-summary
  "An agent's summary: the first line of its description, without heading
   marks; nil without one."
  [description]
  (some-> description str/trim not-empty first-line (str/replace #"^#+\s*" "") str/trim not-empty))

(def borrowed-chars "How much of a borrowed paragraph a summary keeps." 300)

(defn- first-paragraph
  "The lines of the first paragraph of a CLAUDE.md or README.md, trimmed:
   after any frontmatter, skipping headings, badges, images, HTML and
   code. nil when there is none."
  [text]
  (let [t (str/replace (str text) #"\r\n?" "\n")
        t (if-let [[_ rest] (re-matches #"(?s)---\n.*?\n---\n(.*)" t)] rest t)
        skip? #(or (str/blank? %) (re-find #"^(#|!\[|\[!\[|<)" %))]
    (some (fn [para]
            (let [lines (->> (str/split-lines para) (map str/trim) (drop-while skip?))]
              (when (and (seq lines) (not (re-find #"^(```|~~~|\|)" (first lines))))
                (vec (remove str/blank? lines)))))
          (str/split t #"\n\s*\n"))))

(defn borrowed-paragraph
  "The first paragraph of a CLAUDE.md or README.md, as one line, for an
   agent without a description: after any frontmatter, skipping headings,
   badges, images, HTML and code; cut at borrowed-chars. nil when there is
   none."
  [text]
  (some-> (first-paragraph text) (->> (str/join " ")) (truncate borrowed-chars)))

(defn import-paths
  "The files that the first paragraph of a CLAUDE.md (as
   borrowed-paragraph finds it) imports, when it is nothing but Claude
   Code `@path` import lines, e.g. `@README.md`; nil otherwise."
  [text]
  (let [lines (first-paragraph text)]
    (when (and (seq lines) (every? #(re-matches #"@\S+" %) lines))
      (mapv #(subs % 1) lines))))

(defn import-target
  "Import `path`, written in file `from` (both relative to the agent's
   folder), relative to the folder: next to `from`, as Claude Code reads
   it, with `.` and `..` resolved. nil for an absolute or home path,
   which lies outside the folder."
  [from path]
  (when-not (re-find #"^[/~]" path)
    (let [segs (reduce (fn [acc s] (cond (= "." s) acc
                                         (and (= ".." s) (seq acc) (not= ".." (peek acc))) (pop acc)
                                         :else (conj acc s)))
                       [] (concat (butlast (path-segments from)) (path-segments path)))]
      (when (seq segs) (str/join "/" segs)))))

(def import-depth
  "How many imports deep a borrowed summary follows, as Claude Code does."
  5)

(defn borrowed-summary
  "[paragraph file] for an agent without a description: the first
   paragraph (borrowed-paragraph) of its folder's CLAUDE.md, else of its
   README.md; nil when neither has one. A CLAUDE.md whose first paragraph
   is only `@path` imports (import-paths) borrows from the first file it
   imports that has a paragraph, following imports there too, up to
   import-depth: `file` is then that file. `read` gives a file's text by
   its path relative to the folder, nil when there is none."
  [read]
  (letfn [(from [path depth]
            (when-let [text (read path)]
              (if-let [imports (import-paths text)]
                (when (pos? depth) (some #(some-> (import-target path %) (from (dec depth))) imports))
                (some-> (borrowed-paragraph text) (vector path)))))]
    (or (from "CLAUDE.md" import-depth)
        (some-> (read "README.md") borrowed-paragraph (vector "README.md")))))

(defn entry-from-registration
  "The entry an on-call registration ({:repo :dir :added}, with :off after
   `oncall remove`) becomes when the directory first takes over: on call
   unless it was taken off, kind claude, added when it was registered (else
   `now`). Its folder is as registered; the caller resolves it."
  [reg now]
  {:name (:repo reg) :dir (some-> (:dir reg) str) :oncall (not (:off reg)) :kind "claude"
   :added (or (:added reg) (:removed reg) now)})

(defn entry-doc
  "An entry's file: its frontmatter, then its description."
  [e]
  (str (fm-str (array-map :name (:name e) :dir (:dir e) :file-id (:file-id e) :oncall (boolean (:oncall e))
                          :kind (or (:kind e) "claude") :added (:added e)))
       (when-not (str/blank? (:description e)) (str "\n" (str/trim (:description e)) "\n"))))

(defn parse-entry
  "Entry `nm` (its file's name) from its file's text: {:name :dir :file-id
   :oncall :kind :added :description}, read defensively."
  [nm text]
  (let [[fm body] (parse-doc text)]
    {:name nm :dir (some-> (:dir fm) str) :file-id (some-> (:file-id fm) str)
     :oncall (contains? #{true "true"} (:oncall fm)) :kind (str (or (:kind fm) "claude"))
     :added (some-> (:added fm) str) :description (not-empty (str/trim body))}))

(defn agent-view
  "Entry `e` as `agents` and `status` show it: {:name :dir :oncall :kind
   :summary :borrowed :trusted :dir-exists}. The summary is the
   description's first line, else the paragraph `borrowed` ([text file])
   gives, with :borrowed naming that file; :trusted is whether Claude Code
   trusts the folder, nil when unknown."
  [e {:keys [exists trusted borrowed]}]
  (let [own (description-summary (:description e))]
    {:name (:name e) :dir (:dir e) :oncall (boolean (:oncall e)) :kind (:kind e)
     :summary (or own (first borrowed)) :borrowed (when-not own (second borrowed))
     :trusted trusted :dir-exists (boolean exists)}))

;; ---------------------------------------------------------------- status

(defn flow
  "Who the thread has passed through: the senders of its messages in written
   order, consecutive repeats collapsed, then, while the thread is open, the
   owner when it differs from the last sender. A wrote to B, B's turn: [A B];
   B answered: [A B A]; B handed on to C: [A B C]; closed by B: [A B], since
   nobody's turn is left. [] for a thread without messages."
  [th]
  (let [senders (vec (dedupe (keep :from (:msgs th))))
        owner (:owner th)]
    (cond-> senders (and (= "open" (:status th)) owner (not= owner (peek senders))) (conj owner))))

(defn summary
  "A thread without its messages, with its flow (list --json, status --json)."
  [th]
  (-> th (dissoc :msgs) (assoc :flow (flow th))))

(defn participants
  "Every name the thread's messages come from or go to, sorted."
  [th]
  (vec (sort (distinct (keep identity (mapcat (juxt :from :to) (:msgs th)))))))

(defn unread-by
  "Unread count for each participant of `th`; `seen` maps a reader to their
   seen markers (thread id -> last message file read)."
  [th seen]
  (into (sorted-map)
        (for [p (participants th)]
          [p (count (unread (:msgs th) p (get-in seen [p (:id th)])))])))

(defn waiting-s
  "Seconds an open thread has waited on its owner since its last message, as
   of stamp `now`; nil once it is closed."
  [th now]
  (when (and (= "open" (:status th)) (:updated th))
    (age-s (:updated th) now)))

(defn listener
  "Who picks up an open thread: \"user\" when it waits on the user, \"on-duty\"
   when `on-duty?` (e.g. the set of repos with an on-duty record) holds for its
   owner, \"on-call\" when `on-call?` does (serve hands it to a Claude Code
   session, see serve-actions), else \"none\"; postmaster's mail counts as
   postmaster-agent's (delivery-owner). nil for a closed thread, or one
   without messages yet."
  ([th on-duty?] (listener th on-duty? #{}))
  ([th on-duty? on-call?]
   (when (and (= "open" (:status th)) (:owner th))
     (let [o (delivery-owner (:owner th))]
       (cond (= "user" (:owner th)) "user"
             (on-duty? o) "on-duty"
             (on-call? o) "on-call"
             :else "none")))))

(defn undelivered
  "Ids of the threads nobody is listening for (listener \"none\": not on
   call, serve not running, or backing off), longest waiting first, then by
   number. No threshold: that is the reader's call."
  [threads]
  (->> threads
       (filter #(= "none" (:listener %)))
       (sort-by (juxt #(- (or (:waiting-s %) 0)) :number))
       (mapv :id)))

;; Bump when a field of `status` changes meaning or goes away; adding fields
;; is compatible. UIs refuse a schema they don't know.
(def status-schema 1)

(declare thread-session postmaster-view)

(defn status
  "Everything at once, for a UI that must not re-derive anything: every thread
   (closed ones too), newest-updated first, as a summary (flow included) with
   the caller's unread count, how many of those are addressed to the caller
   (:unread-to-me, what an inbox counts: not what agents sent each other in
   a thread that now waits on the caller), unread counts per participant,
   how long it has waited and who will pick it up; the duty records as
   given; and the undelivered thread ids; the on-call repos and whether
   serve runs; the agent directory; for a thread serve delivered, the
   session holding it (thread-session); and the postmaster's open notices
   (postmaster-view).
   `seen` maps each reader (the caller and every participant) to their seen
   markers; `duty` is the duty records with :on-duty already decided;
   `oncall` is oncall-view's list, `serve` is {:alive :pid}, `memory`
   serve's and `agents` agent-view's for each agent. A repo counts as on
   call only while serve is alive and the repo
   is not backing off."
  [{:keys [now me threads seen duty oncall serve memory agents]}]
  (let [on-duty-repos (set (map :repo (filter :on-duty duty)))
        on-call-repos (if (:alive serve) (set (map :repo (remove #(= "backing-off" (:state %)) oncall))) #{})
        ths (->> threads
                 (map (fn [th]
                        (let [mine (unread (:msgs th) me (get-in seen [me (:id th)]))]
                          (cond-> (assoc (summary th)
                                         :unread (count mine)
                                         :unread-to-me (count (filter #(= me (:to %)) mine))
                                         :unread-by (unread-by th seen)
                                         :participants (participants th)
                                         :waiting-s (waiting-s th now)
                                         :listener (listener th on-duty-repos on-call-repos))
                            (thread-session memory (:id th)) (assoc :session (thread-session memory (:id th)))))))
                 (sort-by :updated #(compare %2 %1))
                 vec)]
    {:schema status-schema :now now :me me :threads ths :duty (vec duty) :undelivered (undelivered ths)
     :oncall (vec oncall) :serve (or serve {:alive false :pid nil}) :agents (vec agents)
     :postmaster (postmaster-view memory (into {} (map (juxt :id identity)) threads))}))

;; ---------------------------------------------------------------- serve: a session per thread
;; `agent-mail serve` hands the mail of on-call agents to Claude Code
;; sessions itself, one session per thread. An open thread whose turn is an
;; on-call agent's goes to the session holding it: the one that last wrote
;; in it as that agent (a message records its writer's session), or the one
;; serve started for it since. A running holder gets the message posted into
;; its inbox; a stopped background one whose prompt cache is still warm is
;; resumed with it; otherwise serve starts a background session for the
;; thread. It stops the sessions it started once they have been idle for
;; idle-stop-ms, held thread or not: the prompt cache lives on the server,
;; so a stopped session resumes as cheaply as a running one takes a post,
;; and only the process's memory is saved. Its memory, times in epoch
;; milliseconds as Claude Code's registry has them:
;;   {:threads  {id {:agent :number :delivered (newest message file handed on)
;;                   :holder (short id) :deaf #{short ids that held a post}
;;                   :check {:session :file :at :status-updated-at}}}
;;    :sessions {short-id {:session-id :agent :thread :number :name :kind
;;                         :ours (serve started it) :state :status :since
;;                         :started-at :cwd
;;                         :waiting-since :waiting-for :needs :alerted
;;                         (while it waits on the user, see needs-input-ms)
;;                         :unlanded {:branch :commits :at} (finished,
;;                         but kept: see rm-plan)}}
;;    :agents   {agent {:failures :until :reason :since (the first failure
;;                                                       in this row)}}
;;    :land     {agent {:blocked (why its main checkout can't be brought
;;                                up to date, see land-plan)}}
;;    :postmaster {:pending :notices} (see postmaster-actions)}
;; :state is "starting" (started or resumed, not listed by `claude agents`
;; yet), "live", "stopped" or "gone" (not even `claude agents --all` lists
;; it). The functions here decide; serve gathers and carries out.

(def idle-statuses
  "The registry statuses of a session between turns: \"idle\", and
   \"shell\", which Claude Code reports instead while a background bash task
   runs. \"busy\" (a turn) and \"waiting\" (blocked on the user) are not."
  #{"idle" "shell"})

(def start-grace-ms
  "How long a session serve started or resumed has to show up in `claude
   agents` before serve backs off: `claude --bg` returns once the background
   service has it, so two minutes means something stopped it."
  120000)

(def post-check-ms
  "How long after a post into an idle session serve looks whether it was
   taken up: Claude Code holds a post from another process for the user to
   approve in a session that bypasses permission prompts, and the session
   stays idle with an unchanged statusUpdatedAt."
  30000)

(def needs-input-ms
  "How long a background session in serve's memory has to wait on the user
   (registry status \"waiting\": a permission prompt or another dialog)
   before serve tells the user, once per wait. Nobody watches a background
   session, so it would otherwise hold its thread unseen."
  120000)

(def starts-per-round
  "How many sessions serve starts in one round at most, for the lowest
   thread numbers; the others go out in later rounds. A serve that was down
   for a while would otherwise start a burst of sessions at once. Posts and
   resumes are not capped."
  3)

(def default-ttl-ms
  "A prompt cache's lifetime when nothing says otherwise: one hour."
  3600000)

(def idle-stop-ms
  "How long a session serve started has to be idle (registry status
   \"idle\" since its statusUpdatedAt) before serve stops it, whether or
   not it holds a thread. The prompt cache lives on the server, not in
   the process: a stopped session is resumed while the cache is warm, as
   routing does anyway, and replaced by a new one once it is cold. Only
   the process's memory is saved. \"shell\" (a background task runs),
   \"waiting\" (a prompt) and \"busy\" never count as idle here."
  300000)

(defn- resting?
  "Whether running session `e` is idle as idle-stop-ms means it: registry
   status \"idle\", or no status and `claude agents` calling it done."
  [e]
  (or (= "idle" (:status e)) (and (nil? (:status e)) (= "done" (:run-state e)))))

(defn back-off-ms
  "How long serve leaves an agent alone after its `n`th failure in a row: two
   minutes, doubling, at most an hour."
  [n]
  (min 3600000 (* 120000 (bit-shift-left 1 (min 10 (max 0 (dec n)))))))

(defn back-off
  "The action backing `agent` off after one more failure; `m` is serve's
   memory of the agent, whose :failures count so far."
  [agent m now reason]
  [:back-off agent (+ now (back-off-ms (inc (or (:failures m) 0)))) reason])

(defn clock
  "Epoch milliseconds as the machine's local time of day, e.g. 05:40."
  [ms]
  (.format (.atZone (java.time.Instant/ofEpochMilli ms) (java.time.ZoneId/systemDefault))
           (java.time.format.DateTimeFormatter/ofPattern "HH:mm")))

(defn- ms-of [ts] (try (.toEpochMilli (java.time.Instant/parse ts)) (catch Exception _ nil)))

(defn short-id
  "The 8-character id Claude Code shows for a session (`claude agents`,
   `claude attach`): the start of its sessionId. nil for nil."
  [sid]
  (when (string? sid) (subs sid 0 (min 8 (count sid)))))

(defn find-session
  "The entry of `sessions` (as agents-items gives them) for session `sid`, a
   full sessionId or a short id."
  [sessions sid]
  (when-let [k (short-id sid)] (some #(when (= k (:id %)) %) sessions)))

(defn background? [s] (contains? #{"background" "bg"} (:kind s)))

(defn inside?
  "Whether directory `path` is `dir` or below it."
  [path dir]
  (boolean (and path dir (let [d (str/replace dir #"/+$" "")]
                           (or (= path d) (str/starts-with? path (str d "/")))))))

(defn thread-session-name
  "The name serve gives the session it starts for thread number `n` of
   `agent`: its /resume entry and `claude agents` row."
  [agent n]
  (str "📫 " agent " #" n))

;; ---- what Claude Code says

(defn agents-items
  "The sessions in `claude agents --json`, parsed with keyword keys: an
   array, or an object holding one. Each as {:id :session-id :pid :cwd :name
   :kind :status :run-state :waiting-for}, :id being the short id,
   :run-state the list's own `state` (done, stopped, blocked, …) and
   :waiting-for what a \"waiting\" session waits on (\"permission prompt\",
   \"dialog open\", …)."
  [parsed]
  (let [xs (cond (sequential? parsed) parsed
                 (map? parsed) (or (some #(when (sequential? %) %) ((juxt :agents :sessions :items) parsed))
                                   (some #(when (sequential? %) %) (vals parsed)))
                 :else [])]
    (vec (for [x xs :when (map? x)
               :let [sid (:sessionId x) id (short-id (or sid (:id x)))]
               :when id]
           {:id id :session-id sid :pid (:pid x) :cwd (:cwd x) :name (:name x) :kind (:kind x)
            :status (:status x) :run-state (:state x) :waiting-for (:waitingFor x)}))))

(defn backgrounded-id
  "The short id in what `claude --bg` prints, `backgrounded · 52f6bcf5 ·
   name` (after a `Starting background service…` line when it started one);
   nil when there is none."
  [out]
  (second (re-find #"(?m)^\s*backgrounded\s*·\s*([0-9a-f]{8})" (str out))))

(defn claude-failure
  "Why a run of claude failed, in one line for serve's log and back-off:
   `what` failed, its exit code, and the first line it printed that is not
   the background service starting, e.g. `Workspace not trusted. Run
   `claude` in <dir> once…`."
  [what {:keys [exit out err]}]
  (let [line (->> (str/split-lines (str err "\n" out)) (map str/trim) (remove str/blank?)
                  (remove #(str/starts-with? % "Starting background service")) first)]
    (str what " failed (exit " exit ")" (when line (str ": " line)))))

(defn cache-expiry
  "When a session's prompt cache expires, in epoch ms, from its transcript's
   entries (parsed with keyword keys, in file order; the tail is enough):
   the last assistant entry's timestamp plus the lifetime of the cache the
   latest assistant entry that wrote any wrote, 1 h for
   ephemeral_1h_input_tokens and 5 min for ephemeral_5m_input_tokens, 1 h
   when none says. nil without an assistant entry."
  [entries]
  (let [as (filter #(= "assistant" (:type %)) entries)
        ttl (or (some (fn [e]
                        (let [cc (get-in e [:message :usage :cache_creation])]
                          (cond (pos? (or (:ephemeral_1h_input_tokens cc) 0)) 3600000
                                (pos? (or (:ephemeral_5m_input_tokens cc) 0)) 300000)))
                      (reverse as))
                default-ttl-ms)]
    (some-> (some :timestamp (reverse as)) ms-of (+ ttl))))

;; ---- texts

(defn- activity
  "What a session is doing, in a word: busy, idle or waiting while it runs
   (\"shell\" is idle), else its state as serve last saw it."
  [{:keys [state status run-state]}]
  (if (contains? #{nil "live"} state)
    (cond (contains? idle-statuses status) "idle"
          (some? status) status
          :else (or run-state "running"))
    state))

(defn needs-you
  "What a session in serve's memory that waits on the user (it has
   :waiting-since) waits for and how to open it, e.g. needs you since 02:41
   (approve Read: /etc/hosts; `claude attach 166f77e1`). nil otherwise."
  [s]
  (when-let [since (:waiting-since s)]
    (str "needs you since " (clock since) " ("
         (or (some #(when-not (str/blank? %) %) [(:needs s) (:waiting-for s)]) "input")
         "; `claude attach " (:id s) "`)")))

(defn needs-input-note
  "What serve tells the user of a session waiting on them, from a
   [:needs-input S info] action's info: thread, session, since when, what
   for, and how to open it."
  [{:keys [id name number since waiting-for needs]}]
  (str "#" number " " (pr-str (or name id)) " (" id ") has waited on you since " (clock since) ": "
       (or (some #(when-not (str/blank? %) %) [needs waiting-for]) "input")
       ". Open it with `claude attach " id "`."))

(def subject-chars
  "How much of a thread's subject the also-line quotes."
  60)

(declare unlanded-phrase)

(defn session-phrase
  "A session as serve names it to others: \"name\" (kind, activity), with
   the thread for a session serve started for one, and its subject when
   :subject has it, e.g. \"📫 infra #70\" (background, busy, thread #70
   \"Rotate the staging key\"), then, for one of serve's waiting on the
   user, what it needs (needs-you), and for one finished but kept, what of
   it is unlanded (see rm-plan). The name is `claude agents`' name field,
   the one SendMessage reaches the session by; the short id without one."
  [s]
  (str "\"" (if (str/blank? (:name s)) (:id s) (:name s)) "\" ("
       (str/join ", " (remove nil? [(:kind s) (activity s)
                                    (when (and (:ours s) (:number s))
                                      (str "thread #" (:number s)
                                           (when-not (str/blank? (:subject s))
                                             (str " \"" (truncate (:subject s) subject-chars) "\""))))]))
       ")"
       (when-let [n (needs-you s)] (str " " n))
       (when-let [u (:unlanded s)] (str " " (unlanded-phrase u)))))

(def coordinate-sentence
  "What the also-line asks of a session when others work in its folder:
   sessions coordinate with each other directly."
  "If your work affects theirs (the same files, a push to the branch they work on), tell them with SendMessage and let them decide.")

(defn also-line
  "The also-line: the running sessions (`live`) whose folder is `dir` or
   below it (worktrees under .claude/worktrees too), other than session
   `exclude`, each by the name SendMessage takes and, when serve started
   it for a thread (`memory`), with that thread's number and its subject
   from `subjects` (thread id -> subject), so sessions on threads about
   the same thing can find each other; then, when there are any, a
   sentence asking to tell them of work that affects theirs."
  ([dir live memory exclude] (also-line dir live memory exclude {}))
  ([dir live memory exclude subjects]
   (let [ours (into {} (for [[k s] (:sessions memory) :when (:ours s)] [k s]))
         others (for [s live
                      :when (and (inside? (:cwd s) dir) (not= (:id s) (short-id exclude)))
                      :let [o (ours (:id s))]]
                  (cond-> s o (assoc :ours true :number (:number o) :subject (get subjects (:thread o)))))]
     (if (seq others)
       (str "Also working in this folder: " (str/join ", " (map session-phrase others)) ". " coordinate-sentence)
       "No other session works in this folder."))))

(defn- from-who
  "A message's writer as a session is told of it. Claude Code presents
   anything posted into a session as another session's, so one from `user`
   says that it is the user's own."
  [from]
  (str from (when (= from "user") " (the user themself, writing by mail; not another agent or session)")))

(def related-sentence
  "What the first prompt says about other threads: the session is for its
   own, and two sessions on threads about the same thing (a report and the
   user's observation of one bug) agree between themselves who handles
   both. Writing in a thread makes the writer its holder (see holder)."
  (str "This session is for this thread. If a session listed below works on a thread about the same thing, "
       "agree with it by SendMessage which of you handles both. The one taking over writes in the other thread, "
       "which makes serve post that thread's later messages to it."))

(defn describe-sentence
  "What the first prompt asks of a session whose agent has no description
   in the directory yet."
  [agent]
  (str agent " has no description in the agent directory yet: write one with `agent-mail agents describe " agent
       " < about.md`, saying what this folder owns and what to ask it for."))

(defn addressed-sentence
  "What a session hears of thread `th` when it waits on postmaster (its
   :addressed, see as-delivered): serve hands postmaster's mail to
   `agent`, which answers as itself. nil otherwise."
  [th agent]
  (when (= postmaster (:addressed th))
    (str "It waits on postmaster, agent-mail serve's own role, whose mail goes to " agent
         ": answer as " agent ". ")))

(defn first-prompt
  "The first prompt of the session serve starts for thread `th`, now
   `agent`'s turn: what started it and for which thread (and that it is
   postmaster's mail, see addressed-sentence), what to do, that
   later messages come into it and how it relates to the sessions of other
   threads (related-sentence); with `describe?`, a request to describe the
   agent (describe-sentence); then the also-line `also`."
  ([agent th also] (first-prompt agent th also {}))
  ([agent th also {:keys [describe?]}]
   (let [n (or (:number th) (:id th))]
     (str "agent-mail serve started this session for thread #" n " for " agent " from " (from-who (:last-from th))
          ", \"" (:subject th) "\" (" (:id th) ", message " (count (:msgs th)) "). "
          (addressed-sentence th agent)
          "Read it with `agent-mail read " n "` and act on it under this folder's own rules (CLAUDE.md). "
          "Answer with `agent-mail reply " n "`; close it with `agent-mail close " n "` when the work is done. "
          "Later messages in this thread are posted into this session. "
          related-sentence
          (when describe? (str " " (describe-sentence agent)))
          "\n"
          also))))

(defn posted-text
  "What serve posts into the session holding thread `th` for `agent` (or
   resumes it with) for its newest message, and why there: `wrote?`, the
   session wrote in the thread, else serve started it for the thread; then
   the also-line `also`. A closed thread (news for the session, see
   closed-news?) says so, and that no answer is expected. It names the
   message's position, so a later post never repeats an earlier one word for
   word (Claude Code drops identical posts arriving close together).
   Postmaster's mail says so (addressed-sentence)."
  [agent th wrote? also]
  (let [n (or (:number th) (:id th))
        closed? (= "done" (:status th))]
    (str "agent-mail: " (if closed? "closed, with news: #" "new message in #") n " \"" (:subject th) "\" "
         (if closed? "closed by " "from ") (from-who (:last-from th))
         " (" (:id th) ", message " (count (:msgs th)) "), posted by agent-mail serve because "
         (if wrote? "this session wrote in this thread" "it started this session for this thread")
         " (not another agent). " (addressed-sentence th agent) "Read it with `agent-mail read " n "`"
         (if closed?
           "; no answer is expected. To take it further, start a new thread or `agent-mail forward` it.\n"
           " and answer once the current step is done.\n")
         also)))

;; ---- landing
;; In a git repo a background session works in a worktree of its own under
;; <repo>/.claude/worktrees, and Claude Code's worktree guard refuses any
;; git command from there that targets the main checkout. What a session
;; pushes would leave the main checkout behind, with nothing to bring it
;; up to date; so serve fast-forwards it (see land-plan), and removes a
;; finished session once nothing of its is unlanded (see rm-plan).

(defn commits-phrase "`1 commit`, `3 commits`." [n] (str n (if (= 1 n) " commit" " commits")))

(defn parse-worktrees
  "`git worktree list --porcelain` as [{:path :head :branch}], the main
   worktree first; :branch is the short name, nil for a detached HEAD."
  [out]
  (vec (for [block (str/split (str out) #"\n\s*\n")
             :let [kv (into {} (for [l (str/split-lines block)
                                     :let [[k v] (str/split l #" " 2)]
                                     :when (not (str/blank? k))]
                                 [k (or v true)]))
                   branch (get kv "branch")]
             :when (string? (get kv "worktree"))]
         {:path (get kv "worktree") :head (let [h (get kv "HEAD")] (when (string? h) h))
          :branch (when (string? branch) (str/replace branch #"^refs/heads/" ""))})))

(defn worktree-of
  "The linked worktree of `worktrees` (parse-worktrees', the main one
   first) that folder `cwd` is in, the deepest; nil when none, as for the
   main worktree itself."
  [worktrees cwd]
  (->> (rest worktrees) (filter #(inside? cwd (:path %))) (sort-by #(count (:path %))) last))

(defn working-in
  "The names of the running sessions (`live`, as agents-items gives them)
   that work in folder `dir`'s checkout itself: their folder is `dir` or
   below it, but not under its .claude/worktrees, where Claude Code puts a
   session's own worktree. Someone works there, so serve leaves the
   checkout alone."
  [dir live]
  (let [wts (str (str/replace (str dir) #"/+$" "") "/.claude/worktrees")]
    (vec (for [s live :when (and (inside? (:cwd s) dir) (not (inside? (:cwd s) wts)))]
           (if (str/blank? (:name s)) (:id s) (:name s))))))

(defn land-plan
  "What serve does with an agent's main checkout once one of its sessions
   went idle or stopped ([:land], see serve-actions), from what git and
   Claude Code say of it:
     :main?       the agent's folder is its repo's main worktree
     :branch      the branch checked out there; nil when detached
     :dirty?      tracked files have changes (untracked ones never count)
     :working     names of the sessions working in it (working-in)
     :upstream    its branch's upstream, e.g. origin/main; nil without
     :behind :ahead  commits HEAD lacks and has against the upstream
     :candidate   without an upstream, the session's worktree branch:
                  {:branch :behind :ahead (HEAD against it) :closed (its
                   thread is closed, and it holds no other open thread)}
   With an upstream: a fast-forward to it when HEAD is strictly behind.
   The upstream is the local remote-tracking ref, never fetched: a
   session pushes from its worktree, which shares the repository, so its
   push has already moved that ref. Without one, the session's worktree
   branch counts, once its thread is closed (a session that asks something
   midway must not have unfinished commits landed) and HEAD is its
   ancestor. Either only on a branch, with clean tracked files and nobody
   working there. Returns {:ff target :commits n :via label} for `git
   merge --ff-only target`, {:blocked text} when there is something to
   land but serve can't, e.g. `main checkout 2 behind origin/main:
   uncommitted changes`, or nil when there is nothing to land."
  [{:keys [main? branch dirty? working upstream behind ahead candidate]}]
  (let [blocked (fn [n label ahead]
                  (let [ahead (or ahead 0)
                        why (cond-> []
                              (pos? ahead) (conj "diverged")
                              dirty? (conj "uncommitted changes")
                              (seq working) (conj (str (word-list (map pr-str working) "and")
                                                       (if (= 1 (count working)) " works" " work") " there")))]
                    (when (seq why)
                      {:blocked (str "main checkout " n " behind " label (when (pos? ahead) (str " and " ahead " ahead"))
                                     ": " (str/join ", " why))})))]
    (cond
      (not (and main? branch)) nil
      upstream (when (pos? (or behind 0))
                 (or (blocked behind upstream ahead) {:ff "@{u}" :commits behind :via "upstream"}))
      :else (let [{b :branch n :behind a :ahead :keys [closed]} candidate]
              (when (and b closed (pos? (or n 0)))
                (or (blocked n b a) {:ff (str "refs/heads/" b) :commits n :via (str "branch " b)}))))))

(def unlanded-recheck-ms
  "How often serve looks again at a finished session it kept for its
   unlanded commits (see rm-plan); it also looks after every landing of
   its agent's."
  600000)

(defn rm-plan
  "Whether serve removes a finished session of its own (stopped, holding
   no open thread) with `claude rm`, which removes the job and its
   worktree when that is safe and keeps the transcript for `claude
   --resume`; from what git says of the session's worktree:
     :worktree     the linked worktree its folder is in, {:branch :head};
                   nil when there is none
     :commits      how many commits of its branch the main checkout's
                   HEAD lacks
     :in-head?     its branch is contained in that HEAD
     :in-upstream? or in HEAD's upstream (@{u})
     :error        why git could not tell
   Nothing unlanded, so removed: no worktree, or a branch contained in
   either. Returns {:rm why} or {:keep {:branch :commits}}, :branch a
   detached HEAD's commit when there is no branch; {:keep {:error why}}
   when git could not tell."
  [{:keys [worktree commits in-head? in-upstream? error]}]
  (cond error {:keep {:error error}}
        (nil? worktree) {:rm "no worktree"}
        in-head? {:rm (str "branch " (:branch worktree) " landed")}
        in-upstream? {:rm (str "branch " (:branch worktree) " pushed")}
        :else {:keep {:branch (or (:branch worktree) (some-> (:head worktree) (subs 0 (min 7 (count (:head worktree))))))
                      :commits commits}}))

(defn unlanded-phrase
  "What a kept session's :unlanded says, e.g. unlanded: branch
   worktree-x, 2 commits."
  [{:keys [branch commits error]}]
  (if error
    (str "unlanded, perhaps: " error)
    (str "unlanded: branch " branch (when commits (str ", " (commits-phrase commits))))))

;; ---- deciding

(defn serve-memory
  "serve's memory from its state file: the maps it knows, each {} when
   missing or not a map, so an older serve's state (tabs, under :repos)
   reads as nothing remembered."
  [st]
  (into {} (for [k [:threads :sessions :agents :land :postmaster]] [k (let [v (get st k)] (if (map? v) v {}))])))

(defn- newest-file [th] (:file (peek (vec (:msgs th)))))

(defn as-delivered
  "Thread `th` as serve delivers it: one waiting on postmaster is
   postmaster-agent's turn (delivery-owner), with :addressed postmaster,
   so its sessions are told whose mail it is (addressed-sentence)."
  [th]
  (if (= postmaster (:owner th)) (assoc th :owner postmaster-agent :addressed postmaster) th))

(defn holder
  "Who holds thread `th` for `agent`, {:sid :wrote?}: the session of the
   newest message `agent` wrote in it (:wrote? true), or the session serve
   started for `agent` and the thread when that started later (a session
   that has not written yet), leaving out the short ids in `deaf`. nil when
   there is neither."
  [th agent memory deaf]
  (let [deaf (set deaf)
        w (some #(when (and (= agent (:from %)) (:session %) (not (deaf (short-id (:session %)))))
                   {:sid (:session %) :wrote? true :at (or (ms-of (:date %)) 0)})
                (rseq (vec (:msgs th))))
        a (some->> (:sessions memory)
                   (filter (fn [[k s]] (and (:ours s) (= agent (:agent s)) (= (:id th) (:thread s)) (not (deaf k)))))
                   (sort-by (comp #(or % 0) :started-at val))
                   last
                   ((fn [[k s]] {:sid (or (:session-id s) k) :wrote? false :at (or (:started-at s) 0)})))]
    (some-> (if (and w a) (if (> (:at a) (:at w)) a w) (or w a)) (dissoc :at))))

(defn- backing-off? [memory agent now]
  (let [u (get-in memory [:agents agent :until])] (boolean (and u (< now u)))))

(defn- news?
  "Whether closed thread `th` has news for `agent` (see closed-news?), by
   the seen markers in `seen` (reader -> thread id -> last file read); for
   postmaster's mail (as-delivered), news addressed to postmaster, by
   `agent`'s markers."
  [th agent seen]
  (and (= "done" (:status th)) (closed-news? th (or (:addressed th) agent) (get-in seen [agent (:id th)]))))

(defn- deliverable
  "The threads with something for serve to deliver: open, or closed with
   news (see news?), whose turn is an on-call agent's (not `user`), with no
   listener on duty for it and not backing off, and a newest message serve
   has not handed on (or is to hand on again: ids in `redo`). By number."
  [{:keys [now oncall threads memory on-duty seen]} redo]
  (let [on-duty (set on-duty)]
    (->> threads
         (filter (fn [th]
                   (let [a (:owner th)]
                     (and a (not= "user" a) (contains? oncall a) (not (on-duty a))
                          (not (backing-off? memory a now)) (seq (:msgs th))
                          (or (= "open" (:status th)) (news? th a seen))
                          (or (contains? redo (:id th))
                              (not= (newest-file th) (get-in memory [:threads (:id th) :delivered])))))))
         (sort-by :number))))

(defn- due-checks [memory now check-ms]
  (for [[t m] (sort-by key (:threads memory))
        :let [c (:check m)]
        :when (and c (>= (- now (or (:at c) 0)) check-ms))]
    [t c]))

(defn- removable?
  "Whether session `s` of serve's memory is one serve removes once it is
   finished (see rm-plan): its own, stopped, its folder known and its
   agent on call (in `oncall`), so its worktree can be found."
  [s oncall]
  (boolean (and (:ours s) (= "stopped" (:state s)) (:cwd s) (get-in oncall [(:agent s) :dir]))))

(defn- finished-ids
  "The short ids of the sessions in serve's memory that are done with
   their threads: their own is closed (not among `threads` open), and they
   hold no other open thread for their agent, whoever's turn it is (see
   holder)."
  [threads memory]
  (let [open (set (keep #(when (= "open" (:status %)) (:id %)) threads))
        agents (distinct (keep :agent (vals (:sessions memory))))
        holding (set (for [th threads, a agents
                           :when (open (:id th))
                           :let [h (holder th a memory (get-in memory [:threads (:id th) :deaf]))]
                           :when h]
                       (short-id (:sid h))))]
    (set (for [[k s] (:sessions memory) :when (and (not (open (:thread s))) (not (holding k)))] k))))

(defn- retirable
  "The sessions of serve's memory, [k s], that serve looks at this round
   to remove them (see rm-plan): removable?, done with their threads
   (finished-ids), and either not looked at yet, or kept for unlanded
   commits and due again: their agent's checkout is landed this round (an
   agent in `landing`), or unlanded-recheck-ms have passed."
  [{:keys [now oncall threads memory]} landing]
  (let [finished (finished-ids threads memory)]
    (for [[k s] (sort-by key (:sessions memory))
          :when (and (removable? s oncall) (finished k)
                     (let [u (:unlanded s)]
                       (or (nil? u) (contains? landing (:agent s)) (>= (- now (or (:at u) 0)) unlanded-recheck-ms))))]
      [k s])))

(defn serve-wants
  "Which session lists a round needs, so serve asks Claude Code (some 100 ms
   a call) only when it has something to decide: :live for something to
   deliver, a post to check, a session of its own starting or running, or
   one finished to look at for removal; :known (stopped sessions too) only
   to choose between resuming and starting. Takes serve-actions' input."
  [{:keys [now memory check-ms] :as in}]
  (let [in (update in :threads #(mapv as-delivered %))
        todo (boolean (or (seq (deliverable in #{})) (seq (due-checks memory now (or check-ms post-check-ms)))))]
    {:live (boolean (or todo (some #(or (= "starting" (:state %)) (and (:ours %) (= "live" (:state %))))
                                   (vals (:sessions memory)))
                        (seq (retirable in #{}))))
     :known todo}))

(defn- observed
  "What serve keeps of a running session's entry, nils left out: what it
   waits on only while it waits (status \"waiting\"); its folder, which
   tells its worktree once it has stopped (see land-plan)."
  [e]
  (let [waiting? (= "waiting" (:status e))]
    (into {} (remove (comp nil? val)) {:state "live" :status (:status e) :kind (:kind e) :name (:name e)
                                       :session-id (:session-id e) :cwd (:cwd e)
                                       :waiting-for (when waiting? (:waiting-for e))
                                       :needs (when waiting? (:needs e))})))

(defn- needs-input
  "[:needs-input S info] when session `s` of serve's memory (short id `k`),
   running as `e`, has waited on the user since serve first saw it wait
   (:waiting-since, which remember keeps for background sessions only), for
   `needs-input-ms` at least, and the user has not been told of this wait
   yet."
  [k s e now]
  (when (and (background? s) (= "waiting" (:status e)) (= "waiting" (:status s))
             (:waiting-since s) (not (:alerted s))
             (>= (- now (:waiting-since s)) needs-input-ms))
    [:needs-input k (into {} (remove (comp nil? val))
                          {:id (:id e) :name (or (:name e) (:name s)) :agent (:agent s) :thread (:thread s)
                           :number (:number s) :since (:waiting-since s)
                           :waiting-for (or (:waiting-for e) (:waiting-for s)) :needs (or (:needs e) (:needs s))})]))

(defn- route
  "The delivery of thread `th`'s newest message: into its holder, by
   resuming it, or to a new session (see serve-actions); nil while the
   session serve started or resumed for it is still on its way. A closed
   thread's news goes only into a running holder, unchecked; without one it
   is passed over ([:pass T info]): no session is resumed or started for a
   closed thread."
  [{:keys [now oncall memory live known expiry subjects]} th deaf]
  (let [agent (:owner th)
        t (:id th)
        closed? (= "done" (:status th))
        dir (get-in oncall [agent :dir])
        info {:agent agent :number (:number th) :file (newest-file th) :dir dir}
        {:keys [sid wrote?] :as h} (holder th agent memory deaf)
        s (when h (get-in memory [:sessions (short-id sid)]))
        e (when h (find-session live sid))
        k (when (and h (not e)) (find-session known sid))
        warm (when (and k (background? k)) (expiry (or (:session-id k) sid)))
        also #(also-line dir live memory % subjects)]
    (cond
      (and closed? e (:socket e))
      [:post t (:id e) (posted-text agent th wrote? (also sid))
       (merge info {:socket (:socket e) :name (:name e) :kind (:kind e) :session-id (:session-id e) :idle false})]
      closed? [:pass t info]
      (and s (= "starting" (:state s)) (not e)) nil
      (and e (:socket e))
      [:post t (:id e) (posted-text agent th wrote? (also sid))
       (merge info {:socket (:socket e) :name (:name e) :kind (:kind e) :session-id (:session-id e)
                    :idle (contains? idle-statuses (:status e)) :status-updated-at (:status-updated-at e)})]
      (and warm (< now warm))
      [:resume t (or (:session-id k) sid) (posted-text agent th wrote? (also sid))
       (merge info {:name (:name k) :kind (:kind k) :warm-until warm})]
      :else
      [:start agent t (first-prompt agent th (also nil) {:describe? (false? (get-in oncall [agent :described]))})
       (assoc info :name (thread-session-name agent (or (:number th) t)))])))

(defn serve-actions
  "What serve does this round, as data. `oncall` maps the agents on call to
   {:dir :described}, :described false when the directory has no
   description of the agent (the first prompt asks for one); `threads` are
   every thread; `memory` is serve's (see above);
   `on-duty` the agents a listener's duty record covers, whose threads serve
   leaves to it (a wait still running code from before 9 Nov 2026, or a
   classic one); `live` the running sessions as agents-items gives them,
   with what their registry entry adds (:socket :status
   :status-updated-at), nil when not asked or Claude Code could not tell
   (`agents-error` says why); `known` every session Claude Code keeps
   (`--all`), nil when not asked; `expiry` a session id's cache expiry from
   its transcript (see cache-expiry), nil when unknown; `seen` the on-call
   agents' seen markers (reader -> thread id -> last file read), for closed
   threads with news; `max-starts` the cap on starts (starts-per-round). In
   this order:
   [:forget T]              thread T is gone, or closed without news for an
                            owner on call (one with news is kept until read)
   [:observe S changes]     session S as `claude agents` now shows it
   [:reset A]               a session for A showed up: its back-off is over
   [:back-off A until why]  a session serve started or resumed for A did not
                            show up in `grace-ms` (then [:lost S T]: T is to
                            go out again), or the sessions could not be listed
   [:drop S]                S is down and its thread closed: forget it (one
                            of serve's own that stopped is looked at to be
                            removed instead, see :retire)
   [:checked T]             the post into T's holder was taken up (or it ended,
                            or T has closed)
   [:deaf T S]              it was not: S stayed idle `check-ms` after it, so
                            T goes to another session this round
   [:post T S text info]    post into S, T's running holder; for a closed
                            thread with news too, then unchecked
   [:pass T info]           a closed thread's news has no running holder:
                            marked handed on, nothing resumed or started
   [:resume T S text info]  `claude --bg --resume S text`: the holder is a
                            stopped background session with a warm cache
   [:start A T text info]   `claude --bg --name …` in A's folder, for the
                            `max-starts` lowest thread numbers this round;
                            the others go out in later rounds
   [:stop S info]           `claude stop`: a session serve started, idle
                            for idle-stop-ms by the registry's
                            statusUpdatedAt (:idle-since), held thread or
                            not; never while it runs a background task
                            (shell), waits on the user or is busy, nor with
                            a post into it checked or made this round
   [:land A info]           bring A's main checkout up to date (land-plan):
                            one of serve's sessions for A went idle (from
                            busy, waiting or starting), ended, is being
                            stopped this round, or is finished and looked
                            at for the first time (see :retire). info:
                            {:dir :sessions [{:id :name :cwd :closed}]
                            :working}, :working the sessions working in
                            the checkout (working-in)
   [:retire S info]         S, serve's own, is stopped and holds no open
                            thread: `claude rm` it unless its worktree
                            branch has unlanded commits (rm-plan), after
                            any landing this round. Looked at again while
                            kept, see retirable. info: {:id :name :agent
                            :dir :cwd}
   [:needs-input S info]    tell the user: background session S has waited
                            on them (a permission prompt) `needs-input-ms`
                            (see needs-input); serve's own are looked at
                            every round, others when a round lists anyway
   Sessions serve did not start are never stopped. A thread waiting on
   postmaster is postmaster-agent's to deliver (as-delivered)."
  [{:keys [now oncall threads memory live known expiry agents-error grace-ms check-ms max-starts seen] :as in}]
  (let [grace-ms (or grace-ms start-grace-ms)
        check-ms (or check-ms post-check-ms)
        max-starts (or max-starts starts-per-round)
        expiry (or expiry (constantly nil))
        threads (mapv as-delivered threads)
        in (assoc in :threads threads :expiry expiry :subjects (into {} (map (juxt :id :subject)) threads))
        open (into {} (comp (filter #(= "open" (:status %))) (map (juxt :id identity))) threads)
        news (set (keep #(when (and (contains? oncall (:owner %)) (news? % (:owner %) seen)) (:id %)) threads))
        sessions (sort-by key (:sessions memory))
        agent-m #(get-in memory [:agents %])
        forget (for [t (sort (keys (:threads memory))) :when (not (or (open t) (news t)))] [:forget t])
        observe (when live
                  (mapcat
                   (fn [[k s]]
                     (let [e (find-session live k)]
                       (cond
                         e (let [o (observed e)]
                             (concat (when-not (every? (fn [[x v]] (= v (get s x))) o) [[:observe k o]])
                                     (when (and (= "starting" (:state s)) (agent-m (:agent s))) [[:reset (:agent s)]])))
                         (= "starting" (:state s))
                         (when (>= (- now (or (:since s) 0)) grace-ms)
                           [(back-off (:agent s) (agent-m (:agent s)) now
                                      (str "session " (pr-str (or (:name s) k)) " did not show up in `claude agents`"))
                            [:lost k (:thread s)]])
                         :else
                         (let [st (cond (and known (find-session known k)) "stopped"
                                        known "gone"
                                        (= "live" (:state s)) "stopped"
                                        :else (:state s))]
                           (when (not= st (:state s)) [[:observe k {:state st}]])))))
                   sessions))
        drops (for [[k s] sessions
                    :when (and (contains? #{"stopped" "gone"} (:state s)) (not (open (:thread s)))
                               (not (find-session live k)) (not (removable? s oncall)))]
                [:drop k])
        backing (into #{} (keep #(when (= :back-off (first %)) (second %))) observe)
        checks (when live
                 (for [[t c] (due-checks memory now check-ms)]
                   (let [e (find-session live (:session c))]
                     (if (and (open t) e (contains? idle-statuses (:status e)) (= (:status-updated-at c) (:status-updated-at e)))
                       [:deaf t (:session c)]
                       [:checked t]))))
        redo (into {} (for [[kind t s] checks :when (= :deaf kind)] [t s]))
        cands (remove #(backing (:owner %)) (deliverable in (set (keys redo))))
        deliveries (cond
                     (empty? cands) []
                     (nil? live) (for [a (distinct (map :owner cands))]
                                   (back-off a (agent-m a) now (str "cannot list Claude Code sessions: "
                                                                    (or agents-error "`claude agents` said nothing"))))
                     :else (->> cands
                                (keep #(route in % (cond-> (set (get-in memory [:threads (:id %) :deaf]))
                                                     (redo (:id %)) (conj (short-id (redo (:id %)))))))
                                (reduce (fn [[acc n] a]
                                          (cond (not= :start (first a)) [(conj acc a) n]
                                                (< n max-starts) [(conj acc a) (inc n)]
                                                :else [acc n]))
                                        [[] 0])
                                first))
        checking (set (for [[t m] (:threads memory) :when (and (:check m) (open t))] (short-id (get-in m [:check :session]))))
        targeted (set (keep (fn [[kind _ s]] (when (#{:post :resume} kind) (short-id s))) deliveries))
        stops (when live
                (for [[k s] sessions
                      :let [e (find-session live k)
                            since (:status-updated-at e)]
                      :when (and (:ours s) e (resting? e) (number? since) (>= (- now since) idle-stop-ms)
                                 (not (checking k)) (not (targeted k)))]
                  [:stop k {:id (:id e) :name (or (:name e) (:name s)) :agent (:agent s) :idle-since since}]))
        unseen (when live
                 (for [[k s] (retirable in #{}) :when (and (nil? (:unlanded s)) (not (find-session live k)))] k))
        ;; serve's own sessions that went idle, ended, are being stopped or
        ;; are finished: what they pushed is to land in their agent's main
        ;; checkout
        settled (distinct
                 (concat unseen
                         (for [[k s] sessions
                               :let [e (find-session live k)]
                               :when (and (:ours s) e (contains? idle-statuses (:status e))
                                          (not (contains? idle-statuses (:status s))))]
                           k)
                         (for [[kind k changes] observe
                               :when (and (= :observe kind) (contains? #{"stopped" "gone"} (:state changes))
                                          (get-in memory [:sessions k :ours]) (= "live" (get-in memory [:sessions k :state])))]
                           k)
                         (map second stops)))
        finished (finished-ids threads memory)
        lands (for [[agent ks] (sort-by key (group-by #(get-in memory [:sessions % :agent]) settled))
                    :let [dir (get-in oncall [agent :dir])]
                    :when dir]
                [:land agent {:dir dir
                              :sessions (vec (for [k (sort ks)
                                                   :let [s (get-in memory [:sessions k]) e (find-session live k)]]
                                               {:id k :name (or (:name e) (:name s)) :cwd (or (:cwd e) (:cwd s))
                                                :closed (boolean (finished k))}))
                              :working (working-in dir live)}])
        retires (when live
                  (for [[k s] (retirable in (set (map second lands)))
                        :when (not (find-session live k))]
                    [:retire k {:id k :name (:name s) :agent (:agent s) :dir (get-in oncall [(:agent s) :dir]) :cwd (:cwd s)}]))
        alerts (when live
                 (keep (fn [[k s]] (when-let [e (find-session live k)] (needs-input k s e now))) sessions))]
    (vec (concat forget observe drops checks deliveries stops lands retires alerts))))

(defn remember
  "serve's memory after `action` at `now`: one of serve-actions' bookkeeping
   actions ([:pass T info] and [:needs-input S info], once the user is told,
   included), or what carrying out a delivery or a
   stop gave: [:posted T S
   info], [:post-failed T S] (S counts as deaf for T), [:resumed T S info],
   [:started A T short-id session-id info] (session-id nil until `claude
   agents` lists it), [:stopped S], [:land-checked A blocked] (blocked:
   land-plan's text, nil when nothing keeps A's main checkout behind),
   [:removed S] (`claude rm` ran: forgotten), [:unlanded S {:branch
   :commits}] (kept, see rm-plan). [:post], [:resume], [:start], [:stop],
   [:land] and [:retire] themselves change nothing: they end as one of
   those or a :back-off. The postmaster's (see postmaster-actions):
   [:pm-wait T owner], [:pm-unwait T], [:notice-forget K] as they come;
   [:bounced T] once its bounce is written, [:noticed K info] once its
   notice thread is (info with its :thread and :number),
   [:notice-updated K info] and [:notice-closed K] once those are, or
   could not be, written."
  [memory [kind & args] now]
  (let [known-thread (fn [m t f & xs] (if (get-in m [:threads t]) (apply update-in m [:threads t] f xs) m))
        known-session (fn [m k f & xs] (if (get-in m [:sessions k]) (apply update-in m [:sessions k] f xs) m))
        delivered (fn [m t sid {:keys [agent number file]}]
                    (update-in m [:threads t] #(-> % (assoc :agent agent :number number :delivered file :holder (short-id sid))
                                                   (dissoc :check))))
        strip (fn [m] (into {} (remove (comp nil? val)) m))]
    (case kind
      :forget (update memory :threads dissoc (first args))
      (:drop :removed) (update memory :sessions dissoc (first args))
      :unlanded (let [[k info] args] (known-session memory k assoc :unlanded (assoc info :at now)))
      :reset (update memory :agents dissoc (first args))
      :back-off (let [[a until reason] args]
                  (update-in memory [:agents a] #(assoc % :until until :reason reason :failures (inc (or (:failures %) 0))
                                                        :since (or (:since %) now))))
      :observe (let [[k changes] args
                     ;; only a background session: nobody may be at it, and
                     ;; `claude attach` opens it
                     waiting? #(and (background? %) (= "live" (:state %)) (= "waiting" (:status %)))]
                 (known-session memory k
                                (fn [s] (let [s' (cond-> (merge s changes)
                                                   (and (:state changes) (not= (:state changes) (:state s))) (assoc :since now)
                                                   ;; running again: looked at afresh once it is finished
                                                   (= "live" (:state changes)) (dissoc :unlanded))]
                                          (cond (not (waiting? s')) (dissoc s' :waiting-since :waiting-for :needs :alerted)
                                                (waiting? s) (cond-> s' (not (:waiting-for changes)) (dissoc :waiting-for)
                                                                     (not (:needs changes)) (dissoc :needs))
                                                :else (assoc s' :waiting-since now))))))
      :needs-input (known-session memory (first args) assoc :alerted now)
      :lost (let [[k t] args]
              (-> memory (known-session k assoc :state "gone" :since now)
                  (known-thread t dissoc :delivered :check)))
      :deaf (let [[t sid] args]
              ;; :delivered goes too: should no start follow this round (the
              ;; cap, a failure), the message goes out again in a later one
              (known-thread memory t #(-> % (update :deaf (fnil conj #{}) (short-id sid)) (dissoc :check :delivered))))
      :checked (known-thread memory (first args) dissoc :check)
      :pass (let [[t {:keys [agent number file]}] args]
              (update-in memory [:threads t] assoc :agent agent :number number :delivered file))
      :post-failed (let [[t sid] args] (update-in memory [:threads t :deaf] (fnil conj #{}) (short-id sid)))
      :posted (let [[t sid {:keys [agent number file idle status-updated-at name kind session-id] :as info}] args
                    k (short-id sid)]
                (-> (delivered memory t sid info)
                    (cond-> idle (assoc-in [:threads t :check] {:session k :file file :at now :status-updated-at status-updated-at}))
                    (update-in [:sessions k] #(merge {:agent agent :thread t :number number :ours false} %
                                                     (strip {:session-id session-id :name name :kind kind :state "live"})))))
      :resumed (let [[t sid {:keys [agent number name kind] :as info}] args
                     k (short-id sid)]
                 (-> (delivered memory t sid info)
                     (update-in [:sessions k] #(merge {:agent agent :thread t :number number :ours false} %
                                                      (strip {:session-id sid :name name :kind kind :state "starting" :since now})))))
      :started (let [[a t k sid {:keys [number name] :as info}] args]
                 (-> (delivered memory t k info)
                     (assoc-in [:sessions k] (strip {:session-id sid :agent a :thread t :number number :name name
                                                     :kind "background" :ours true :state "starting" :since now
                                                     :started-at now}))))
      :stopped (known-session memory (first args) assoc :state "stopped" :since now)
      :land-checked (let [[a blocked] args]
                      (if blocked (assoc-in memory [:land a] {:blocked blocked}) (update memory :land dissoc a)))
      :pm-wait (let [[t owner] args] (assoc-in memory [:postmaster :pending t] owner))
      (:pm-unwait :bounced) (update-in memory [:postmaster :pending] #(dissoc (or % {}) (first args)))
      :noticed (let [[k info] args]
                 (assoc-in memory [:postmaster :notices k] (select-keys info [:agent :kind :thread :number :threads :reason])))
      :notice-updated (let [[k info] args]
                        (if (get-in memory [:postmaster :notices k])
                          (update-in memory [:postmaster :notices k] merge (select-keys info [:threads :reason]))
                          memory))
      (:notice-closed :notice-forget) (update-in memory [:postmaster :notices] #(dissoc (or % {}) (first args)))
      memory)))

;; ---- views

(defn thread-session
  "The session holding thread `id` as serve last saw it, for status:
   {:id :name :kind :state}, :id being the short id `claude attach` takes;
   while it waits on the user also :waiting-since (a UTC stamp),
   :waiting-for and :needs (see needs-input-ms). nil when serve has
   delivered nothing of it."
  [memory id]
  (when-let [k (get-in memory [:threads id :holder])]
    (when-let [s (get-in memory [:sessions k])]
      (into {} (remove (comp nil? val))
            {:id k :name (:name s) :kind (:kind s) :state (:state s)
             :waiting-since (some-> (:waiting-since s) java.time.Instant/ofEpochMilli fmt-utc)
             :waiting-for (when (:waiting-since s) (:waiting-for s))
             :needs (when (:waiting-since s) (:needs s))}))))

(defn serve-sessions
  "The sessions in serve's memory, each with its short id as :id, by agent
   and thread number (`who`, `oncall`)."
  [memory]
  (sort-by (juxt :agent #(or (:number %) 0) :id) (for [[k s] (:sessions memory)] (assoc s :id k))))

(defn session-line
  "One of serve's sessions as `who` lists it: thread, short id, name and
   what it is doing."
  [s]
  (str "#" (:number s) " → " (:id s) " " (session-phrase (dissoc s :ours))))

(defn oncall-view
  "Each registered agent with what serve is doing about it, for `oncall`,
   `who` and `status`: {:repo :dir :state :pane :text}, plus :sessions on
   duty, :until (a UTC stamp) with :reason while backing off,
   :cannot-land while its main checkout stays behind what a session of
   serve's pushed (land-plan's text), and :unlanded, serve's finished
   sessions kept for their unlanded commits ([{:id :name :branch
   :commits}], see rm-plan). :state is \"on-duty\" (a listener's
   duty record covers it, so serve leaves its threads alone),
   \"backing-off\" or \"on-call\"; :pane is always nil (serve opened
   WezTerm tabs until 9 Nov 2026). The text says why it can't land, then
   ends with the sessions serve holds for it. `memory` is serve's; `duty`
   the duty records with :on-duty decided."
  [{:keys [now oncall memory duty]}]
  (vec
   (for [[repo {:keys [dir]}] (sort-by key oncall)
         :let [m (get-in memory [:agents repo])
               names (vec (distinct (keep :session (filter #(and (= repo (:repo %)) (:on-duty %)) duty))))
               until (when (and (:until m) (< now (:until m))) (:until m))
               state (cond (seq names) "on-duty" until "backing-off" :else "on-call")
               blocked (get-in memory [:land repo :blocked])
               held (filter #(= repo (:agent %)) (serve-sessions memory))
               tail (when (seq held) (str "; serve's sessions: " (str/join ", " (map session-phrase held))))]]
     (cond-> {:repo repo :dir dir :state state :pane nil
              :text (str (case state
                           "on-duty" (str "on duty (session " (str/join ", " names) ")")
                           "backing-off" (str "backing off until " (clock until) ": " (:reason m))
                           "on call")
                         (when blocked (str "; can't land: " blocked))
                         tail)}
       (seq names) (assoc :sessions names)
       until (assoc :until (fmt-utc (java.time.Instant/ofEpochMilli until)) :reason (:reason m))
       blocked (assoc :cannot-land blocked)
       (some :unlanded held) (assoc :unlanded (vec (for [s held :when (:unlanded s)]
                                                     (into {} (remove (comp nil? val))
                                                           {:id (:id s) :name (:name s) :branch (get-in s [:unlanded :branch])
                                                            :commits (get-in s [:unlanded :commits])}))))))))

;; ---------------------------------------------------------------- the postmaster
;; A role inside serve: a script, not a session, and indifferent to what
;; kind of agent receives the mail. Each round, after serve's own actions,
;; it looks at the open threads that are not the user's turn and that
;; nothing delivers (nor a listener on duty):
;;   - Owner not in the directory (legacy mail, or an entry removed since):
;;     after a round of grace, the postmaster writes the thread back to its
;;     last sender who isn't the owner (bounce-target), saying so, once per
;;     owner name: the bounce records the name as `bounced: NAME`.
;;   - Owner off call, the thread waiting offcall-notice-ms: kind off-call.
;;   - Owner on call, but serve keeps backing off from it, for
;;     backoff-notice-ms or backoff-notice-failures failures in a row:
;;     kind backing-off.
;; The user hears of the last two by a notice: one thread per agent and
;; kind, from postmaster, which it updates in place and closes itself. It
;; writes in a notice thread only while that waits on the user: one the
;; user closed (dismissed), or answered (the answer goes to postmaster, so
;; to postmaster-agent), is the user's, and a later change starts a new
;; one. Its memory, serve's :postmaster:
;;   {:pending {thread-id owner}  owner not in the directory last round
;;    :notices {[agent kind] {:agent :kind :thread :number
;;                            :threads (ids, by number) :reason}}}
;; :notices is what each notice said last, and where, so a restart says
;; nothing it said before.

(def offcall-notice-ms
  "How long a thread waits on an agent that is off call before the
   postmaster tells the user: an hour."
  3600000)

(def backoff-notice-ms
  "How long serve backs off from an on-call agent, from the first failure
   in the row, before the postmaster tells the user: 15 minutes, unless
   backoff-notice-failures come first."
  900000)

(def backoff-notice-failures
  "After how many failures in a row the postmaster tells the user that
   serve backs off from an agent, unless backoff-notice-ms came first."
  3)

(def serve-log
  "Where serve logs under launchd (`just install-serve`), on macOS."
  "~/Library/Logs/agent-mail-serve.log")

(defn bounced-for?
  "Whether the postmaster bounced thread `th` for owner `owner` before: its
   bounce records the name (`bounced: NAME`)."
  [th owner]
  (boolean (some #(= owner (:bounced %)) (:msgs th))))

(defn bounce-target
  "Whom the postmaster writes thread `th` back to, its owner not being in
   the directory: the last sender that is neither the owner nor the
   postmaster, when that is the user or one of `names`; else the user."
  [th names]
  (let [skip (hash-set (:owner th) postmaster)
        s (some #(when-not (skip (:from %)) (:from %)) (rseq (vec (:msgs th))))]
    (if (and s (or (= "user" s) (contains? (set names) s))) s "user")))

(defn bounce-text
  "What the postmaster writes, sending thread number `number` back: its
   owner `owner` (whose mail goes to `agent`, delivery-owner's) is not in
   the directory; up to three close names `close` (closest-names); how to
   pass it on."
  [{:keys [owner agent number close]}]
  (str "There is no agent " agent " in the agent directory"
       (when (not= owner agent) (str " (" owner "'s mail goes to " agent ")"))
       ", so nothing delivers this thread to it: the postmaster sends it back to you. "
       (if (seq close) (str "The closest names are " (or-list close) ". ") "")
       "To pass it on, `agent-mail forward " number " --to NAME` starts a new thread carrying this one, "
       "or `agent-mail reply " number " --to NAME` hands this one over; `agent-mail agents` lists who owns what. "
       "If nobody needs it any more, `agent-mail close " number "`."))

(defn- numbers-phrase [numbers] (word-list (map #(str "#" %) numbers) "and"))

(defn- wait-word [n] (if (= 1 n) " waits on " " wait on "))

(defn notice-summary
  "What a notice is about, by the count of its `threads`, e.g. `2 threads
   wait on infra, which isn't on call`."
  [{:keys [agent kind threads]}]
  (let [n (count threads)]
    (str n (if (= 1 n) " thread" " threads") (wait-word n) agent
         (if (= "off-call" kind) ", which isn't on call" ", where serve can't start sessions"))))

(defn notice-subject "A notice thread's subject." [n] (str "postmaster: " (notice-summary n)))

(defn notice-phrase
  "An open notice (postmaster-view's) as `who` and `oncall` show it, e.g.
   postmaster's notice #12: 2 threads wait on infra, which isn't on call."
  [n]
  (str "postmaster's notice #" (:number n) ": " (notice-summary n)))

(defn backoff-fix
  "What would end serve's back-off from an agent: :gone (its folder is),
   :trust (Claude Code hasn't trusted it) or :log (serve's log is all
   there is to go on). From `facts` about its folder, {:exists :trusted},
   each nil when unknown; else from the back-off's `reason`."
  [reason {:keys [exists trusted]}]
  (cond (false? exists) :gone
        (false? trusted) :trust
        (re-find #"(?i)\bnot trusted\b|\buntrusted\b" (str reason)) :trust
        (str/includes? (str reason) "directory is gone") :gone
        :else :log))

(defn fix-text
  "The fix backoff-fix names, for `agent` and its folder `dir`."
  [agent dir fix]
  (let [dir (or dir (str agent "'s folder"))]
    (case fix
      :trust (str "Claude Code hasn't trusted " dir " yet: run `claude` there once and accept the question.")
      :gone (str dir " is gone: if " agent "'s folder moved, `agent-mail agents move " agent " DIR` says where.")
      (str "serve's log says why: " serve-log " on macOS."))))

(def notice-trailer
  "How a notice ends: who writes it, and where a reply goes."
  (str "The postmaster, agent-mail serve's own role, writes here when this changes, and closes this thread once "
       "it no longer holds. A reply goes to " postmaster-agent ", the agent that owns the tool."))

(defn- item-line [now th]
  (str "- #" (:number th) " \"" (:subject th) "\" from " (:last-from th) ", waiting "
       (duration (quot (max 0 (- now (or (ms-of (:updated th)) now))) 1000))))

(defn notice-text
  "A notice's body at `now` (epoch ms): what keeps `items` (the threads it
   lists) from `agent`, the threads, and what to do, ending with
   notice-trailer. For kind backing-off: how often serve failed since when
   (`failures`, `since`), the back-off's `reason` and the fix (`fix`, see
   backoff-fix)."
  [{:keys [agent kind items dir reason failures since fix]} now]
  (let [n (count items)
        these (if (= 1 n) "this thread" (str "these " n " threads"))]
    (str (if (= "off-call" kind)
           (str agent " isn't on call, so nothing delivers " these " until a session starts in its folder, "
                (or dir "which the directory doesn't give") ":")
           (str "serve can't start sessions for " agent ", which is on call, so " these (if (= 1 n) " waits:" " wait:")))
         "\n\n" (str/join "\n" (map #(item-line now %) items)) "\n\n"
         (if (= "off-call" kind)
           (str "`agent-mail oncall add " agent "` lets serve deliver " (if (= 1 n) "it" "them") ".")
           (str "serve has failed " failures (if (= 1 failures) " time" " times") " in a row"
                (when since (str " since " (clock since))) ": " reason "\n\n"
                "To fix it: " (fix-text agent dir fix)))
         "\n\n" notice-trailer)))

(defn notice-update-text
  "A notice's update: what changed since it last said `before` (its
   :threads and :reason), `number-of` giving a thread's number by id, then
   the notice as it stands (notice-text of `info`)."
  [info before number-of now]
  (let [old (set (:threads before))
        new (set (map :id (:items info)))
        added (remove old (map :id (:items info)))
        gone (remove new (:threads before))
        nums #(numbers-phrase (map number-of %))]
    (str "Changed: "
         (str/join "; " (remove nil? [(when (seq added) (str (nums added) (wait-word (count added)) (:agent info) " too"))
                                      (when (seq gone) (str (nums gone) " no longer" (wait-word (count gone)) (:agent info)))
                                      (when (not= (:reason info) (:reason before)) "serve gives another reason")]))
         ".\n\n" (notice-text info now))))

(defn notice-close-text
  "What the postmaster writes closing the notice about `agent`, its
   threads `numbers` no longer kept from it for the notice's reason: those
   of them that still wait on it undelivered (`remaining`) are on their
   way when it is on call (`oncall?`) now, else they wait for another
   reason."
  [agent numbers remaining oncall?]
  (cond (empty? remaining) (str "Delivered now: " (numbers-phrase numbers) " no longer" (wait-word (count numbers)) agent ".")
        oncall? (str "Delivered now: " agent " is on call, and serve hands " (numbers-phrase remaining) " to its sessions.")
        :else (str (numbers-phrase remaining) " still" (wait-word (count remaining)) agent ", but no longer for the reason above.")))

(defn- troubled?
  "Whether serve's memory of an agent, `m`, calls for a notice at `now`: a
   row of failures that began backoff-notice-ms ago, or is
   backoff-notice-failures long."
  [m now]
  (let [n (or (:failures m) 0)]
    (and (pos? n)
         (or (>= n backoff-notice-failures) (boolean (and (:since m) (> (- now (:since m)) backoff-notice-ms)))))))

(defn postmaster-actions
  "What the postmaster does this round, as data. `threads` are every thread
   as stored (postmaster's own owner, not as-delivered); `names` the agent
   directory's names and `dirs` their folders; `oncall` the agents serve
   delivers for, `on-duty` those a listener covers (as for serve-actions);
   `memory` serve's after its own actions this round; `facts` maps an agent
   serve backs off from to what is known of its folder, {:exists :trusted}
   (see backoff-fix). Threads that wait on the user, or on an agent on
   duty, are left alone. In this order:
   [:pm-unwait T]           T's owner was not in the directory last round,
                            and that no longer holds
   [:pm-wait T owner]       it is not, seen for the first time: a round of
                            grace
   [:bounce T info]         still not: postmaster writes T back to info's
                            :to (bounce-target) with :text, recording
                            :owner as `bounced`, while T's newest message
                            is still :file. Once per owner name
   [:notice K info]         a new notice thread to the user for K, [agent
                            kind]: info has :subject and :text, and
                            :agent :kind :threads :reason to remember
   [:notice-update K info]  a reply in K's notice thread (:thread, its
                            :number), which waits on the user: its threads
                            or reason changed
   [:notice-close K info]   K no longer holds: its notice thread (:thread
                            :number), which waits on the user, is closed
                            with :text
   [:notice-forget K]       K no longer holds, and its notice thread is the
                            user's: forgotten without a word"
  [{:keys [now threads names dirs oncall on-duty memory facts]}]
  (let [names (set names)
        on-duty (set on-duty)
        pending (or (get-in memory [:postmaster :pending]) {})
        notices (or (get-in memory [:postmaster :notices]) {})
        by-id (into {} (map (juxt :id identity)) threads)
        number-of #(or (:number (by-id %)) %)
        waiting (->> threads
                     (keep (fn [th]
                             (let [o (:owner th) a (delivery-owner o)]
                               (when (and (= "open" (:status th)) o (not= "user" o) (seq (:msgs th)) (not (on-duty a)))
                                 [a th]))))
                     (sort-by (comp :number second)))
        ;; owners not in the directory
        unknown (for [[a th] waiting :when (and (not (names a)) (not (bounced-for? th (:owner th))))] [a th])
        unknown-ids (into {} (for [[_ th] unknown] [(:id th) (:owner th)]))
        unwaits (for [t (sort (keys pending)) :when (not (contains? unknown-ids t))] [:pm-unwait t])
        waits (for [[_ th] unknown :when (not= (:owner th) (pending (:id th)))] [:pm-wait (:id th) (:owner th)])
        bounces (for [[a th] unknown :when (= (:owner th) (pending (:id th)))]
                  [:bounce (:id th) {:owner (:owner th) :agent a :to (bounce-target th names) :number (:number th)
                                     :file (newest-file th)
                                     :text (bounce-text {:owner (:owner th) :agent a :number (:number th)
                                                         :close (closest-names a (sort names))})}])
        ;; agents in the directory: off call, or backed off from
        undelivered? (fn [th]
                       (and (not= (newest-file th) (get-in memory [:threads (:id th) :delivered]))
                            ;; a session serve started or resumed for it is coming up
                            (not= "starting" (get-in memory [:sessions (get-in memory [:threads (:id th) :holder]) :state]))))
        kind-of (fn [a th]
                  (cond (not (names a)) nil
                        (not (contains? oncall a))
                        (when (> (- now (or (ms-of (:updated th)) now)) offcall-notice-ms) "off-call")
                        (and (troubled? (get-in memory [:agents a]) now) (undelivered? th)) "backing-off"))
        stuck (reduce (fn [acc [a th]] (if-let [k (kind-of a th)] (update acc [a k] (fnil conj []) th) acc)) {} waiting)
        described (fn [[a kind] ths]
                    (let [m (get-in memory [:agents a])
                          reason (when (= "backing-off" kind) (:reason m))]
                      {:agent a :kind kind :threads (mapv :id ths) :reason reason
                       :items ths :dir (get dirs a) :failures (:failures m) :since (:since m)
                       :fix (when reason (backoff-fix reason (get facts a)))}))
        remembered #(select-keys % [:agent :kind :threads :reason])
        notice (fn [d] (assoc (remembered d) :subject (notice-subject d) :text (notice-text d now)))
        notes (for [k (sort (distinct (concat (keys stuck) (keys notices))))
                    :let [ths (get stuck k)
                          m (get notices k)
                          nt (by-id (:thread m))
                          writable? (boolean (and nt (= "open" (:status nt)) (= "user" (:owner nt))))
                          d (when ths (described k ths))
                          where {:thread (:thread m) :number (:number m)}
                          act (cond
                                (and d (not m)) [:notice k (notice d)]
                                (and d (= (:threads d) (vec (:threads m))) (= (:reason d) (:reason m))) nil
                                (and d writable?) [:notice-update k (merge (remembered d) where
                                                                           {:text (notice-update-text d m number-of now)})]
                                d [:notice k (notice d)]
                                writable? (let [a (first k)
                                                remaining (filter #(let [th (by-id %)]
                                                                     (and th (= "open" (:status th)) (= a (delivery-owner (:owner th)))
                                                                          (undelivered? th)))
                                                                  (:threads m))]
                                            [:notice-close k (assoc where :text (notice-close-text a (map number-of (:threads m))
                                                                                                   (map number-of remaining)
                                                                                                   (contains? oncall a)))])
                                :else [:notice-forget k])]
                    :when act]
                act)]
    (vec (concat unwaits waits bounces notes))))

(defn postmaster-view
  "The postmaster's open notices, for status, `who` and `oncall`: [{:agent
   :kind :threads :notice :number :reason}] by agent and kind; :notice is
   the notice thread's id, :threads the ids of the threads it lists, as it
   last said; :reason, a back-off's. A notice whose thread is closed or
   gone is left out. `thread-of` gives a thread by id (a map does)."
  [memory thread-of]
  (vec (for [[_ n] (sort-by key (get-in memory [:postmaster :notices]))
             :let [th (thread-of (:thread n))]
             :when (and th (= "open" (:status th)))]
         (into {} (remove (comp nil? val))
               {:agent (:agent n) :kind (:kind n) :threads (vec (:threads n)) :notice (:thread n) :number (:number n)
                :reason (:reason n)}))))

;; ---------------------------------------------------------------- drafts

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
            ;; A blank right after what was emitted, with more of the user's text
            ;; to come, is a paragraph break; runs of blanks collapse to one.
            :blank (let [follows? (and (pos? i) (= last-emitted (dec i)) (next-new? i))]
                     (recur (inc i)
                            (if (and follows? (= :new (nth kinds last-emitted))) (conj out l) out)
                            (if follows? i last-emitted)))
            (recur (inc i) out last-emitted)))))))

;; ---------------------------------------------------------------- argv

(def value-opts #{"to" "subject" "as" "session" "date" "id" "timeout" "interval" "msg" "name"})
(def flag-opts #{"json" "all" "edit" "once" "standby" "here"})
(def ^:private number-opts {:timeout "seconds" :interval "seconds"})

(defn- finish-opts [opts]
  (reduce (fn [o [k unit]]
            (if-let [v (get o k)]
              (assoc o k (or (parse-long v) (throw (usage-error (str "--" (name k)) "needs a whole number of" (str unit ",") "got" v))))
              o))
          opts number-opts))

(defn parse-argv
  "Options may appear anywhere: `--key value`, flags, repeated `--attach FILE`;
   `--` ends option parsing; `-h`/`--help` anywhere asks for help. Everything
   else is positional. Returns {:args [...] :opts {...}}."
  [argv]
  (loop [[a & more :as xs] argv, args [], opts {:attach []}]
    (cond
      (empty? xs) {:args args :opts (finish-opts opts)}
      (= a "--") {:args (into args more) :opts (finish-opts opts)}
      (= a "--attach") (if (seq more)
                         (recur (rest more) args (update opts :attach conj (first more)))
                         (throw (usage-error "--attach needs a file")))
      (and (str/starts-with? a "--") (value-opts (subs a 2)))
      (if (seq more)
        (recur (rest more) args (assoc opts (keyword (subs a 2)) (first more)))
        (throw (usage-error a "needs a value")))
      (and (str/starts-with? a "--") (flag-opts (subs a 2)))
      (recur more args (assoc opts (keyword (subs a 2)) true))
      (#{"-h" "--help"} a) (recur more (into ["help"] args) opts)
      (str/starts-with? a "--") (throw (usage-error "unknown option" a "(try `agent-mail help`)"))
      :else (recur more (conj args a) opts))))

(defn expand-shorthand
  "`@NAME text…` is `send --to NAME text…`. Works on parsed argv, so options
   such as `--as NAME` may come before the @NAME."
  [{:keys [args opts] :as parsed}]
  (if (str/starts-with? (or (first args) "") "@")
    {:args (into ["send"] (rest args)) :opts (assoc opts :to (subs (first args) 1))}
    parsed))

(defn address-from-dir
  "An address derived from a directory's name: runs of whitespace and path
   separators become one `-`, so `2026-10 Bike` is `2026-10-Bike`; nil
   when nothing is left."
  [dir]
  (let [x (-> (str dir) str/trim (str/replace #"[\s/\\]+" "-"))]
    (when (valid-name? x) x)))
