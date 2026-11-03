;; agent-mail.core: the pure half of agent-mail. Text, time, frontmatter,
;; thread state, the status view, on-call decisions, wait announcements,
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
           :owner (:to last-m) :last-from (:from last-m) :updated (:date last-m)
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
   owner, \"on-call\" when `on-call?` does (a listener tab opens for it
   shortly, see oncall-actions), else \"none\". nil for a closed thread, or one
   without messages yet."
  ([th on-duty?] (listener th on-duty? #{}))
  ([th on-duty? on-call?]
   (when (and (= "open" (:status th)) (:owner th))
     (cond (= "user" (:owner th)) "user"
           (on-duty? (:owner th)) "on-duty"
           (on-call? (:owner th)) "on-call"
           :else "none"))))

(defn undelivered
  "Ids of the threads nobody is listening for (listener \"none\"; an on-call
   owner's tab is on its way), longest waiting first, then by number. No
   threshold: that is the reader's call."
  [threads]
  (->> threads
       (filter #(= "none" (:listener %)))
       (sort-by (juxt #(- (or (:waiting-s %) 0)) :number))
       (mapv :id)))

;; Bump when a field of `status` changes meaning or goes away; adding fields
;; is compatible. UIs refuse a schema they don't know.
(def status-schema 1)

(defn status
  "Everything at once, for a UI that must not re-derive anything: every thread
   (closed ones too), newest-updated first, as a summary (flow included) with
   the caller's unread count, unread counts per participant, how long it has
   waited and who will pick it up; the duty records as given; and the
   undelivered thread ids; the on-call repos and whether serve runs.
   `seen` maps each reader (the caller and every participant) to their seen
   markers; `duty` is the duty records with :on-duty already decided;
   `oncall` is oncall-view's list and `serve` is {:alive :pid}. A repo counts
   as on call only while serve is alive and the repo is not backing off."
  [{:keys [now me threads seen duty oncall serve]}]
  (let [on-duty-repos (set (map :repo (filter :on-duty duty)))
        on-call-repos (if (:alive serve) (set (map :repo (remove #(= "backing-off" (:state %)) oncall))) #{})
        ths (->> threads
                 (map (fn [th]
                        (assoc (summary th)
                               :unread (count (unread (:msgs th) me (get-in seen [me (:id th)])))
                               :unread-by (unread-by th seen)
                               :participants (participants th)
                               :waiting-s (waiting-s th now)
                               :listener (listener th on-duty-repos on-call-repos))))
                 (sort-by :updated #(compare %2 %1))
                 vec)]
    {:schema status-schema :now now :me me :threads ths :duty (vec duty) :undelivered (undelivered ths)
     :oncall (vec oncall) :serve (or serve {:alive false :pid nil})}))

;; ---------------------------------------------------------------- on call
;; A repo on call gets a listener tab when mail waits on it and nobody is on
;; duty: `agent-mail serve` opens a WezTerm tab running Claude Code with the
;; listen skill, and closes it again once its session has idled with nothing
;; waiting. serve's memory is repo -> {:pane :gui-pid :spawned-at :failures
;; :until :reason}, times in epoch milliseconds (as Claude Code's registry has
;; them); the functions here decide, serve gathers and carries out.

(defn sh-quote
  "`s` as one POSIX shell word: single-quoted, each ' written as '\\''. The
   same text means the same in sh, bash, zsh and fish."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn listener-command
  "What a listener tab's login shell runs for `repo`: Claude Code named
   \"📫 <repo>\" (its /resume entry and tab title) with the listen skill as
   its first prompt. exec, so the tab closes when Claude Code exits."
  [repo]
  (str "exec claude --name " (sh-quote (str "📫 " repo)) " " (sh-quote "/agent-mail-listen")))

(def oncall-grace-ms
  "How long a tab serve opened has to come on duty before serve backs off:
   Claude Code starting, loading the skill and arming its wait takes seconds,
   so two minutes means something stopped it (a trust prompt, a missing
   `claude`)."
  120000)

(defn back-off-ms
  "How long serve leaves a repo alone after its `n`th failure in a row: two
   minutes, doubling, at most an hour."
  [n]
  (min 3600000 (* 120000 (bit-shift-left 1 (min 10 (max 0 (dec n)))))))

(defn back-off
  "The action backing `repo` off after one more failure; `m` is serve's
   memory of the repo, whose :failures count so far."
  [repo m now reason]
  [:back-off repo (+ now (back-off-ms (inc (or (:failures m) 0)))) reason])

(defn- pane-sessions
  "The live sessions (values of `sessions`) behind `repo`'s duty records that
   name `pane`; none without a pane."
  [repo pane duty sessions]
  (when pane
    (keep #(get sessions (:session-pid %)) (filter #(and (= repo (:repo %)) (= pane (:pane %))) duty))))

(def closable-statuses
  "The registry statuses that let serve close its tab: \"idle\", and
   \"shell\", which Claude Code reports instead of \"idle\" between turns
   while a background bash task runs. A listener's own wait always is one, so
   between turns a listener is always \"shell\", and statusUpdatedAt marks
   when its last turn ended. Serve only closes the tabs it opened, which are
   listeners, so the cost is that other background work such a session
   started and left running for the whole idle period ends with it.
   \"busy\" (a turn) and \"waiting\" (blocked on the user) never close."
  #{"idle" "shell"})

(defn- idle-since?
  "Whether registry status `s` is closable (see closable-statuses) and has
   been for `ms` by `now`."
  [s now ms]
  (and (contains? closable-statuses (:status s)) (number? (:status-updated-at s))
       (>= (- now (:status-updated-at s)) ms)))

(defn oncall-actions
  "What serve does this round, as data. `oncall` maps the registered repos to
   {:dir}; `memory` is serve's (see above); `duty` the duty records with
   :on-duty decided; `pending` the set of repos open threads wait on; `panes`
   the set of WezTerm's pane ids, nil when wezterm could not tell; `gui-pid`
   the WezTerm GUI's pid (a pane id from an earlier GUI means nothing);
   `sessions` maps a live session pid to its registry {:status
   :status-updated-at}. Only panes in `memory` are ever closed or forgotten:
   sessions the user started are never touched. Per repo, in name order:
   [:forget R]            its pane is gone (closed by the user, or an older GUI's)
   [:drop R]              no longer on call and no tab left: forget it all
   [:reset R]             it came on duty: clear the spawn and the backoff
   [:close R P]           nothing waits on R, and every session behind R's
                          duty records in pane P is idle for `idle-ms`
   [:back-off R until why] the tab did not come on duty in `grace-ms`, closed
                          first, or is open with nobody on duty
   [:spawn R]             mail waits, nobody is on duty, no tab is on its way,
                          and R is not backing off"
  [{:keys [now oncall memory duty pending panes gui-pid sessions idle-ms grace-ms]}]
  (let [grace-ms (or grace-ms oncall-grace-ms)
        on-duty (set (map :repo (filter :on-duty duty)))]
    (vec
     (mapcat
      (fn [repo]
        (let [m (get memory repo {})
              gone? (boolean (and (:pane m) panes
                                  (or (not (contains? panes (:pane m)))
                                      (and gui-pid (:gui-pid m) (not= gui-pid (:gui-pid m))))))
              pane (when-not gone? (:pane m))
              spawned (:spawned-at m)
              duty? (contains? on-duty repo)
              live (pane-sessions repo pane duty sessions)]
          (cond-> (if gone? [[:forget repo]] [])
            (and (not (contains? oncall repo)) (not pane)) (conj [:drop repo])
            (and (or (contains? oncall repo) pane) duty? (or spawned (:until m) (:failures m))) (conj [:reset repo])
            (or (contains? oncall repo) pane)
            (into (cond
                    (and pane panes (not (contains? pending repo)) (seq live) (every? #(idle-since? % now idle-ms) live))
                    [[:close repo pane]]
                    duty? []
                    (and spawned gone?) [(back-off repo m now "its tab closed before a session came on duty")]
                    (and spawned (>= (- now spawned) grace-ms)) [(back-off repo m now "no session came on duty")]
                    spawned []
                    (not (and (contains? oncall repo) (contains? pending repo))) []
                    (and (:until m) (< now (:until m))) []
                    (and pane (nil? panes)) []
                    pane [(back-off repo m now (str "its tab (pane " pane ") is open, but nobody is on duty"))]
                    :else [[:spawn repo]])))))
      (sort (distinct (concat (keys oncall) (keys memory))))))))

(defn remember
  "serve's memory after `event` at `now`: an action of oncall-actions carried
   out, or [:spawned R pane gui-pid] for a tab opened ([:spawn R] itself
   changes nothing: it ends as :spawned or :back-off). A repo left with
   nothing to remember is dropped."
  [memory [kind repo & more] now]
  (let [m (get memory repo {})
        m (case kind
            :spawned (let [[pane gui-pid] more]
                       (cond-> (-> m (assoc :pane pane :spawned-at now) (dissoc :until :reason :gui-pid))
                         gui-pid (assoc :gui-pid gui-pid)))
            (:forget :close) (dissoc m :pane :gui-pid)
            :reset (dissoc m :spawned-at :failures :until :reason)
            :back-off (let [[until reason] more]
                        (-> m (assoc :until until :reason reason :failures (inc (or (:failures m) 0)))
                            (dissoc :spawned-at)))
            :drop {}
            m)]
    (if (empty? m) (dissoc memory repo) (assoc memory repo m))))

(defn clock
  "Epoch milliseconds as the machine's local time of day, e.g. 05:40."
  [ms]
  (.format (.atZone (java.time.Instant/ofEpochMilli ms) (java.time.ZoneId/systemDefault))
           (java.time.format.DateTimeFormatter/ofPattern "HH:mm")))

(defn oncall-view
  "Each registered repo with what serve is doing about it, for `oncall`,
   `who` and `status`: {:repo :dir :state :pane :text}, plus :sessions on
   duty, :idle-s of the session in serve's tab, and :until (a UTC stamp)
   with :reason while backing off. :state is \"on-duty\", \"backing-off\",
   \"starting\" (a tab is on its way), \"tab-open\" (serve's tab, nobody on
   duty) or \"on-call\". Inputs as for oncall-actions."
  [{:keys [now oncall memory duty sessions]}]
  (vec
   (for [[repo {:keys [dir]}] (sort-by key oncall)
         :let [m (get memory repo {})
               pane (:pane m)
               names (vec (distinct (keep :session (filter #(and (= repo (:repo %)) (:on-duty %)) duty))))
               on-duty? (boolean (some #(and (= repo (:repo %)) (:on-duty %)) duty))
               s (first (pane-sessions repo pane duty sessions))
               status-s (when (number? (:status-updated-at s)) (quot (- now (:status-updated-at s)) 1000))
               idle-s (when (contains? closable-statuses (:status s)) status-s)
               until (when (and (:until m) (< now (:until m))) (:until m))
               state (cond on-duty? "on-duty" until "backing-off" (:spawned-at m) "starting"
                           pane "tab-open" :else "on-call")
               tab (str "tab open (pane " pane ")")]]
     (cond-> {:repo repo :dir dir :state state :pane pane
              :text (case state
                      "on-duty" (if pane
                                  (str tab ", on duty"
                                       (when (:status s)
                                         (str ", " (if idle-s "idle" (:status s)) (when status-s (str " " (duration status-s))))))
                                  (str "on duty (session " (str/join ", " names) ")"))
                      "backing-off" (str "backing off until " (clock until) ": " (:reason m))
                      "starting" (str "tab opening" (when pane (str " (pane " pane ")")))
                      "tab-open" (str tab ", nobody on duty")
                      "on call")}
       (seq names) (assoc :sessions names)
       idle-s (assoc :idle-s idle-s)
       until (assoc :until (fmt-utc (java.time.Instant/ofEpochMilli until)) :reason (:reason m))))))

;; ---------------------------------------------------------------- wait announcements

(defn- newest-file [th] (:file (peek (vec (:msgs th)))))

(defn announce
  "What a waiting session must be told now. `announced` maps a thread id to the
   newest message file already announced; `pending` are the threads that are
   the session's turn. Returns the threads that are new or have a newer message
   since (:fresh), and what to remember next (:announced), only for the threads
   still pending: a thread changes hands only with a new message, so nothing
   forgotten can come back unannounced."
  [announced pending]
  {:fresh (filterv #(not= (get announced (:id %)) (newest-file %)) pending)
   :announced (into {} (map (juxt :id newest-file)) pending)})

(defn announcement
  "The text `wait` posts into its own session for `threads`, which are now
   `me`'s turn: one line per thread, then where it comes from. Each line names
   the newest message's position, so a thread announced again never repeats
   the earlier text (Claude Code drops identical repeats arriving close together)."
  [me threads]
  (str/join "\n"
            (concat
             (for [t threads :let [handle (or (:number t) (:id t))]]
               (str "agent-mail: new mail for " me ": #" handle " \"" (:subject t) "\" from " (:last-from t)
                    " (" (:id t) ", message " (count (:msgs t)) "). Read it with `agent-mail read " handle
                    "` and answer before moving on."))
             ["(Posted by this session's own `agent-mail wait`, which keeps running: no need to re-arm it.)"])))

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

(def value-opts #{"to" "subject" "as" "session" "date" "id" "timeout" "interval" "msg" "idle-minutes"})
(def flag-opts #{"json" "all" "edit" "once" "standby"})
(def ^:private number-opts {:timeout "seconds" :interval "seconds" :idle-minutes "minutes"})

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
   separators become one `-`, so `2026-10 iPhone` is `2026-10-iPhone`; nil
   when nothing is left."
  [dir]
  (let [x (-> (str dir) str/trim (str/replace #"[\s/\\]+" "-"))]
    (when (valid-name? x) x)))
