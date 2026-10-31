;; agent-mail.core: the pure half of agent-mail. Text, time, frontmatter,
;; thread state, draft extraction and argv parsing; nothing here touches the
;; store, the environment or a process. Loaded by agent-mail.bb (and test.bb)
;; from the repo's src/ directory; refusals are thrown as ::usage ex-infos and
;; turned into exit codes by the script.
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
  "Frontmatter with every scalar quoted, so dates stay strings when parsed.
   nil values are left out."
  [m]
  (str "---\n"
       (str/join (for [[k v] m :when (some? v)]
                   (str (name k) ": "
                        (if (sequential? v)
                          (str "[" (str/join ", " (map yaml-scalar v)) "]")
                          (yaml-scalar v))
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
   `to` of the last message, status the last `status` set (default open)."
  [meta id msgs]
  (let [msgs (vec msgs) last-m (peek msgs)]
    (assoc meta :id id :msgs msgs :count (count msgs)
           :number (some-> (:number meta) str parse-long)
           :owner (:to last-m) :updated (:date last-m)
           :status (or (some :status (rseq msgs)) "open"))))

(defn unread
  "Messages that `who` has not seen (`mark` is the last file read) and did not write."
  [msgs who mark]
  (filter #(and (not= who (:from %)) (or (nil? mark) (pos? (compare (:file %) mark)))) msgs))

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

(def value-opts #{"to" "subject" "as" "session" "date" "id" "timeout" "interval"})
(def flag-opts #{"json" "all" "edit"})
(def ^:private number-opts #{:timeout :interval})

(defn- finish-opts [opts]
  (reduce (fn [o k]
            (if-let [v (get o k)]
              (assoc o k (or (parse-long v) (throw (usage-error (str "--" (name k)) "needs a whole number of seconds, got" v))))
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
  "`@NAME text…` is `send --to NAME text…`."
  [argv]
  (if (str/starts-with? (or (first argv) "") "@")
    (concat ["send" "--to" (subs (first argv) 1)] (rest argv))
    argv))
