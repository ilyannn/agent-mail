#!/usr/bin/env bb
;; Tests for agent-mail: `bb test.bb` or `just test`, from any directory.
;; Unit tests load src/agent_mail/core.clj in-process; end-to-end tests call
;; the launcher (bin/agent-mail) against a temporary store, the way agents and
;; the user do. Nothing here touches the real store.
(ns test
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]])
  (:import (java.io BufferedReader InputStreamReader)
           (java.net StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels Channels ServerSocketChannel)
           (java.nio.charset StandardCharsets)))

(def repo (fs/parent (fs/absolutize *file*)))
(cp/add-classpath (str (fs/path repo "src")))
(require '[agent-mail.core :as core])

(def launcher (str (fs/path repo "bin" "agent-mail")))
(def ^:dynamic *store* nil)

;; Tests run as an agent's shell unless a test says otherwise; both variables
;; are set explicitly so the result is the same under any launcher.
(def agent-shell {"CLAUDE_CODE_CHILD_SESSION" "1" "CLAUDECODE" "1"})
(def user-shell {"CLAUDE_CODE_CHILD_SESSION" "" "CLAUDECODE" ""})
;; Run from Claude Code, the tests inherit its session socket: cleared, so a
;; wait never posts into the real session; a test sets its own fake one. Its
;; pid too, or a wait would take the real session's name from the registry.
(def no-session {"CLAUDE_CODE_MESSAGING_SOCKET" "" "CLAUDE_CODE_MESSAGING_TOKEN" "" "CLAUDE_PID" ""})
;; Nor may a test reach the real WezTerm (run from a WezTerm tab, the tests
;; inherit its pane) or the real Claude Code registry: both point at nothing
;; next to the store unless a test sets its own.
(defn sandbox-env []
  {"WEZTERM_PANE" ""
   "AGENT_MAIL_WEZTERM" (str (fs/path (fs/parent *store*) "no-wezterm"))
   "CLAUDE_CONFIG_DIR" (str (fs/path (fs/parent *store*) "no-claude-config"))})
(defn env-for [{:keys [who env]}]
  (cond-> (merge {"AGENT_MAIL_DIR" (str *store*) "AGENT_MAIL_NO_NOTIFY" "1"} no-session (sandbox-env) agent-shell env)
    who (assoc "AGENT_MAIL_AS" who)))

(defn start
  "Starts agent-mail as `who` (nil = detect from cwd) in `dir` without waiting;
   deref the result for {:exit :out :err}."
  [{:keys [dir in] :as o} & args]
  (apply p/process (cond-> {:out :string :err :string :dir (or dir (str *store*)) :extra-env (env-for o)}
                     ;; :open leaves stdin a pipe nobody writes to or closes, as an
                     ;; agent's shell does; anything else is the whole of stdin.
                     (not= in :open) (assoc :in (or in "")))
         launcher args))

(defn am
  "Runs agent-mail as `who` (nil = detect from cwd) in `dir`, returning {:exit :out :err}."
  [o & args]
  (deref (apply start o args)))

(defn out [& args] (str/trim (:out (apply am args))))
(defn err [& args] (str/trim (:err (apply am args))))
(defn ids
  "Thread ids in `who`'s inbox (the column after the #number)."
  [who]
  (->> (str/split-lines (out {:who who} "list")) (remove str/blank?) (map #(second (str/split % #"\s+")))))
(defn thread-json [id] (json/parse-string (out {} "read" id "--json") true))
(defn list-json [& args] (json/parse-string (apply out {} "list" "--json" args) true))
(defn store-git [& args]
  (str/trim (:out (apply p/shell {:out :string :err :string :continue true :dir (str *store*)} "git" args))))
(defn commits [] (parse-long (store-git "rev-list" "--count" "HEAD")))
(defn script!
  "An executable shell script in `dir` running `body` with the file as $1."
  [dir name body]
  (let [f (str (fs/path dir name))]
    (spit f (str "#!/bin/sh\n" body "\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    f))
(defn git-in [dir & args]
  (apply p/shell {:out :string :err :string :dir dir} "git" "-c" "user.name=test" "-c" "user.email=test@example.com" args))

(defmacro with-store [& body]
  `(fs/with-temp-dir [d# {}]
     (binding [*store* (fs/path d# "store")]
       (fs/create-dirs *store*)
       ~@body)))

(defmacro with-dir [[sym] & body]
  `(fs/with-temp-dir [~sym {}] (let [~sym (str ~sym)] ~@body)))

(defn usage-thrown? [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (core/usage? e))))

;; ================================================================ unit: text, ids

(deftest slug-rules
  (is (= "please-add-a-vault" (core/slug "Please add a Vault!")))
  (is (= "a-b" (core/slug "A--B")) "runs of punctuation collapse")
  (is (= "thread" (core/slug "Привет мир")) "no ASCII letters left: the fallback")
  (is (= "thread" (core/slug "")))
  (is (= "thread" (core/slug "---")))
  (is (= "h-llo-w-rld" (core/slug "héllo wörld")) "accented letters are separators, not transliterated")
  (let [s (core/slug "one two three four five six seven eight nine ten eleven twelve")]
    (is (<= (count s) 40))
    (is (not (str/ends-with? s "-")))
    (is (= "one-two-three-four-five-six-seven-eight" s) "the 40th character is a dash: cut there, no trailing dash"))
  (is (= "abcde-fghij-klmno-pqrst-uvwxy-abcde" (core/slug "abcde fghij klmno pqrst uvwxy abcde fghij klmno"))
      "cut back to the last whole word")
  (is (= 40 (count (core/slug (apply str (repeat 50 "x"))))) "a single long word is cut hard"))

(deftest new-id-clashes
  (let [d "2026-10-31T10:00:00.000Z"]
    (is (= "2026-10-31-vault" (core/new-id "Vault" d [])))
    (is (= "2026-10-31-vault-2" (core/new-id "Vault" d ["2026-10-31-vault"])))
    (is (= "2026-10-31-vault-3" (core/new-id "Vault" d ["2026-10-31-vault" "2026-10-31-vault-2"])))
    (is (= "2026-10-31-vault" (core/new-id "Vault" d ["2026-10-31-vault-2"])) "only the base counts")
    (is (= "2026-10-31-thread" (core/new-id "日本語" d [])))))

(deftest text-helpers
  (is (= "first" (core/first-line "  first\nsecond\n")))
  (is (= "" (core/first-line "   ")))
  (is (= "abcd" (core/truncate "abcd" 4)))
  (is (= "abc…" (core/truncate "abcde" 4)))
  (is (= 70 (count (core/truncate (apply str (repeat 100 "w")) 70))))
  (is (core/valid-name? "infra"))
  (is (core/valid-name? "my.repo-2"))
  (is (not (core/valid-name? "")))
  (is (not (core/valid-name? "a b")))
  (is (not (core/valid-name? "a/b")))
  (is (not (core/valid-name? nil))))

;; ================================================================ unit: time

(deftest dates
  (is (= "2026-10-31T00:00:00.000Z" (core/normalize-date "2026-10-31")))
  (is (= "2026-10-31T09:05:07.000Z" (core/normalize-date "2026-10-31T09:05:07Z")))
  (is (nil? (core/normalize-date nil)))
  (is (usage-thrown? #(core/normalize-date "yesterday")))
  (is (usage-thrown? #(core/normalize-date "2026-10-31T09:05Z")) "seconds are required")
  (testing "after keeps a thread's messages strictly increasing"
    (is (= "2026-10-31T10:00:00.000Z" (core/after "2026-10-31T10:00:00.000Z" nil)))
    (is (= "2026-10-31T10:00:00.000Z" (core/after "2026-10-31T10:00:00.000Z" "2026-10-31T09:00:00.000Z")))
    (is (= "2026-10-31T10:00:00.001Z" (core/after "2026-10-31T10:00:00.000Z" "2026-10-31T10:00:00.000Z")))
    (is (= "2026-10-31T10:00:00.001Z" (core/after "2026-01-01T00:00:00.000Z" "2026-10-31T10:00:00.000Z")) "a backfilled earlier date is pulled forward")
    (is (= "2026-11-01T00:00:00.000Z" (core/after "2026-10-31T23:59:59.999Z" "2026-10-31T23:59:59.999Z")) "rolls over the day"))
  (is (= "2026-10-31T100000.000Z" (core/stamp->name "2026-10-31T10:00:00.000Z")))
  (is (<= 0 (core/age-s (core/now-utc)) 1)))

;; ================================================================ unit: frontmatter

(deftest frontmatter-roundtrip
  (let [m {:subject "He said \"hi\" \\ back" :from "a" :to "b" :date "2026-10-31T10:00:00.000Z"
           :number 7 :attachments ["files/a.txt" "files/b c.txt"] :status nil :note "line one\nline two" :uni "Привет, 日本語 →"}
        doc (str (core/fm-str m) "\nbody\n---\nnot frontmatter\n")
        [fm body] (core/parse-doc doc)]
    (is (= "He said \"hi\" \\ back" (:subject fm)))
    (is (= "2026-10-31T10:00:00.000Z" (:date fm)) "dates stay strings")
    (is (= "7" (:number fm)) "every scalar is a string; the reader parses numbers")
    (is (= ["files/a.txt" "files/b c.txt"] (:attachments fm)))
    (is (not (contains? fm :status)) "nil values are left out")
    (is (= "line one\nline two" (:note fm)))
    (is (= "Привет, 日本語 →" (:uni fm)))
    (is (= "body\n---\nnot frontmatter" (str/trim body)) "a --- in the body is body (read-msg trims the body)"))
  (is (= [{} "plain\n"] (core/parse-doc "plain\n")))
  (is (= [{:a "1"} ""] (core/parse-doc "---\na: \"1\"\n---\n")) "empty body"))

;; ================================================================ unit: thread state

(defn msg [n from to & {:as more}]
  (merge {:file (format "%03d.md" n) :from from :to to :date (format "2026-10-31T10:00:%02d.000Z" n) :body (str "m" n)} more))

(deftest thread-state-is-the-fold-of-its-messages
  (let [meta {:subject "S" :from "a" :created "2026-10-31T10:00:00.000Z" :number "3"}]
    (testing "no messages yet"
      (let [th (core/thread-state meta "id" [])]
        (is (= 0 (:count th)))
        (is (nil? (:owner th)))
        (is (= "open" (:status th)))
        (is (= 3 (:number th)))))
    (testing "a long ping-pong: owner is the to of the last message"
      (let [msgs [(msg 0 "a" "b") (msg 1 "b" "a") (msg 2 "a" "b") (msg 3 "b" "a") (msg 4 "a" "b")]
            th (core/thread-state meta "id" msgs)]
        (is (= "b" (:owner th)))
        (is (= 5 (:count th)))
        (is (= "2026-10-31T10:00:04.000Z" (:updated th)))
        (is (= "open" (:status th)))
        (is (= "a" (core/other-party th "b")))
        (is (= "b" (core/other-party th "a")) "the last writer that isn't me")
        (is (= "a" (core/other-party th "c")) "a third party answers the last writer")))
    (testing "redirected to a third party, the next plain reply goes back to who redirected"
      (let [th (core/thread-state meta "id" [(msg 0 "a" "b") (msg 1 "b" "c")])]
        (is (= "c" (:owner th)))
        (is (= "b" (core/other-party th "c")))))
    (testing "status is the last one set"
      (is (= "done" (:status (core/thread-state meta "id" [(msg 0 "a" "b") (msg 1 "b" "a" :status "done")]))))
      (is (= "done" (:status (core/thread-state meta "id" [(msg 0 "a" "b") (msg 1 "b" "a" :status "done") (msg 2 "a" "b")])))
          "a reply after a close does not reopen"))
    (testing "a single message: the sender's follow-up goes to the recipient, the recipient's to the sender"
      (is (= "b" (core/other-party (core/thread-state meta "id" [(msg 0 "a" "b")]) "a")) "nothing from anyone else: the owner")
      (is (= "a" (core/other-party (core/thread-state meta "id" [(msg 0 "a" "b")]) "b"))))))

(deftest unread-per-reader
  (let [msgs [(msg 0 "a" "b") (msg 1 "b" "a") (msg 2 "a" "b")]]
    (is (= ["000.md" "002.md"] (map :file (core/unread msgs "b" nil))) "never seen: everything not mine")
    (is (= ["002.md"] (map :file (core/unread msgs "b" "000.md"))))
    (is (empty? (core/unread msgs "b" "002.md")))
    (is (= ["001.md"] (map :file (core/unread msgs "a" nil))) "own messages are never unread")
    (is (= 3 (count (core/unread msgs "c" nil))) "a bystander has it all unread")))

(deftest closed-news-reaches-whoever-missed-it
  ;; "a" asks "b" (meta :from "a"); wait announces only open threads, so a
  ;; reply closed before its next poll needs to be news of its own.
  (let [meta {:subject "S" :from "a" :created "2026-10-31T10:00:00.000Z" :number "1"}
        th (fn [& msgs] (core/thread-state meta "id" msgs))
        answered-and-closed (th (msg 0 "a" "b") (msg 1 "b" "a") (msg 2 "b" "a" :status "done"))]
    (is (core/closed-news? answered-and-closed "a" nil) "the reply and the close came together: news for the asker")
    (is (core/closed-news? answered-and-closed "a" "000.md"))
    (is (not (core/closed-news? answered-and-closed "a" "002.md")) "read: no longer news")
    (is (not (core/closed-news? (th (msg 0 "a" "b") (msg 1 "b" "a")) "a" nil)) "still open: that is ordinary mail")
    (is (core/closed-news? (th (msg 0 "a" "b") (msg 1 "b" "a" :status "done")) "a" nil)
        "closed by the other side, with or without a note, is news to whoever started it")
    (is (not (core/closed-news? (th (msg 0 "a" "b") (msg 1 "b" "a") (msg 2 "a" "b" :status "done")) "b" "000.md"))
        "the asker's close of an answered thread is not news to the answerer")
    (is (core/closed-news? (th (msg 0 "a" "b") (msg 1 "b" "a") (msg 2 "a" "b") (msg 3 "a" "b" :status "done")) "b" "001.md")
        "but a reply to them before it is")
    (is (not (core/closed-news? answered-and-closed "c" nil)) "a bystander is never told")))

(deftest resolve-id-rules
  (let [ids ["2026-10-30-vault" "2026-10-31-vault-2" "2026-10-31-dns"]
        number-of {"2026-10-30-vault" 1 "2026-10-31-vault-2" 2 "2026-10-31-dns" 3}
        r #(core/resolve-id % ids number-of)
        refused (fn [s] (try (r s) nil (catch clojure.lang.ExceptionInfo e (when (core/usage? e) (ex-message e)))))]
    (is (= "2026-10-30-vault" (r "2026-10-30-vault")) "an exact id wins even when it is a fragment of another")
    (is (= "2026-10-31-vault-2" (r "2")))
    (is (= "2026-10-31-vault-2" (r "#2")))
    (is (= "2026-10-31-dns" (r "dns")))
    (is (= "2026-10-31-vault-2" (r "vault-2")))
    (is (str/starts-with? (refused "vault") "ambiguous thread vault matches 2026-10-30-vault, 2026-10-31-vault-2"))
    (is (= "no thread #9" (refused "9")))
    (is (= "no thread #2026" (refused "2026")) "digits are a number, not a fragment")
    (is (= "no thread matches nope" (refused "nope")))))

;; ================================================================ unit: status

(deftest status-derivations
  (let [meta {:subject "S" :from "a" :created "2026-10-31T10:00:00.000Z" :number "1"}
        now "2026-10-31T10:01:00.000Z"
        open-th (core/thread-state meta "t1" [(msg 0 "a" "b") (msg 1 "b" "c") (msg 2 "c" "b")])
        closed-th (core/thread-state (assoc meta :number "2") "t2" [(msg 0 "a" "b") (msg 1 "b" "a" :status "done")])
        to-user (core/thread-state (assoc meta :number "3") "t3" [(msg 0 "a" "user")])
        empty-th (core/thread-state meta "t0" [])]
    (testing "participants: every from and to, distinct and sorted"
      (is (= ["a" "b" "c"] (core/participants open-th)))
      (is (= ["a" "user"] (core/participants to-user)))
      (is (= [] (core/participants empty-th))))
    (testing "flow: senders in written order, repeats collapsed, then the owner when it isn't the last sender"
      (let [fl #(core/flow (core/thread-state meta "f" %))]
        (is (= ["a" "b"] (fl [(msg 0 "a" "b")])) "a wrote: b's turn")
        (is (= ["a" "b" "a"] (fl [(msg 0 "a" "b") (msg 1 "b" "a")])) "b answered: a's turn again")
        (is (= ["a" "b" "c"] (fl [(msg 0 "a" "b") (msg 1 "b" "c")])) "b handed it on: c's turn")
        (is (= ["a" "b"] (fl [(msg 0 "a" "b") (msg 1 "a" "b")])) "a wrote twice: one a")
        (is (= ["a"] (fl [(msg 0 "a" "a")])) "the owner is the last sender: not repeated")
        (is (= ["a" "b" "c" "b"] (core/flow open-th)))
        (is (= ["a" "b"] (core/flow closed-th)) "closed: the senders only, nobody's turn is left")
        (is (= [] (core/flow empty-th)) "no messages: no flow")))
    (testing "summary: the thread without its messages, with its flow"
      (is (= (-> open-th (dissoc :msgs) (assoc :flow ["a" "b" "c" "b"])) (core/summary open-th)))
      (is (= [] (:flow (core/summary empty-th)))))
    (testing "unread-by: one count per participant, each from their own marker"
      (is (= {"a" 2 "b" 2 "c" 2} (core/unread-by open-th {})) "nobody has read anything; own messages never count")
      (is (= {"a" 2 "b" 0 "c" 1} (core/unread-by open-th {"a" {"other" "009.md"} "b" {"t1" "002.md"} "c" {"t1" "000.md"}}))
          "a marker for another thread does not count")
      (is (= ["a" "b" "c"] (keys (core/unread-by open-th {}))) "readers in name order")
      (is (= {} (core/unread-by empty-th {}))))
    (testing "waiting-s: since the last message, for open threads only"
      (is (= 58 (core/waiting-s open-th now)))
      (is (nil? (core/waiting-s closed-th now)))
      (is (nil? (core/waiting-s empty-th now)))
      (is (= 3600 (core/age-s "2026-10-31T10:00:00.000Z" "2026-10-31T11:00:00.000Z"))))
    (testing "listener: the owner's duty state, the user apart, nothing for closed threads"
      (is (= "on-duty" (core/listener open-th #{"b"})))
      (is (= "none" (core/listener open-th #{"a" "c"})) "only the owner's duty counts")
      (is (= "user" (core/listener to-user #{})))
      (is (= "user" (core/listener to-user #{"user"})))
      (is (nil? (core/listener closed-th #{"a" "b"})))
      (is (nil? (core/listener empty-th #{}))))
    (testing "undelivered: listener none only, longest waiting first, then by number"
      (is (= ["old" "mid-1" "mid-2" "new"]
             (core/undelivered [{:id "new" :number 1 :listener "none" :waiting-s 5}
                                {:id "mid-2" :number 4 :listener "none" :waiting-s 60}
                                {:id "old" :number 5 :listener "none" :waiting-s 600}
                                {:id "mid-1" :number 2 :listener "none" :waiting-s 60}
                                {:id "heard" :number 6 :listener "on-duty" :waiting-s 9000}
                                {:id "mine" :number 7 :listener "user" :waiting-s 9000}
                                {:id "closed" :number 8 :listener nil :waiting-s nil}])))
      (is (= [] (core/undelivered []))))
    (testing "status puts it together"
      (let [duty [{:repo "b" :on-duty true} {:repo "c" :on-duty false}]
            st (core/status {:now now :me "user" :threads [closed-th to-user open-th]
                             :seen {"b" {"t1" "002.md"} "user" {"t3" "000.md"}} :duty duty})
            [t1 t2 t3] (:threads st)]
        (is (= #{:schema :now :me :threads :duty :undelivered :oncall :serve} (set (keys st))))
        (is (= [now "user" duty] [(:now st) (:me st) (:duty st)]))
        (is (= [[] {:alive false :pid nil}] [(:oncall st) (:serve st)]) "nothing on call, no serve: as from a caller that predates them")
        (is (= ["t1" "t2" "t3"] (map :id (:threads st))) "every thread, closed too, newest-updated first")
        (is (not-any? :msgs (:threads st)) "summaries, no messages")
        (is (= [3 2 0] (map :unread [t1 t2 t3])) "the caller's own count: a bystander on t1 and t2, read up on t3")
        (is (= {"a" 2 "b" 0 "c" 2} (:unread-by t1)))
        (is (= ["on-duty" nil "user"] (map :listener [t1 t2 t3])))
        (is (= [58 nil 60] (map :waiting-s [t1 t2 t3])))
        (is (= [["a" "b" "c" "b"] ["a" "b"] ["a" "user"]] (map :flow [t1 t2 t3])))
        (is (= [] (:undelivered st)))
        (is (= ["t3" "t1"] (:undelivered (core/status {:now now :me "x" :seen {} :duty []
                                                     :threads [open-th (core/thread-state meta "t3" [(msg 0 "a" "d")]) closed-th]})))
            "nobody on duty: every open thread not waiting on the user, longest waiting first")))
    (testing "duration: one rounded-down unit"
      (is (= ["0s" "59s" "1m" "59m" "1h" "23h" "1d" "40d"] (map core/duration [0 59 60 3599 3600 86399 86400 (* 40 86400)]))))
    (testing "listener on-call: the owner's tab is on its way; on duty wins"
      (is (= "on-call" (core/listener open-th #{} #{"b"})))
      (is (= "on-duty" (core/listener open-th #{"b"} #{"b"})))
      (is (= "user" (core/listener to-user #{} #{"user"})))
      (is (= "none" (core/listener open-th #{} #{"c"}))))
    (testing "status: on call while serve is alive and the repo is not backing off"
      (let [st (fn [state alive] (core/status {:now now :me "x" :seen {} :duty [] :threads [open-th]
                                               :oncall [{:repo "b" :state state}] :serve {:alive alive :pid 1}}))
            heard (comp :listener first :threads)]
        (is (= ["on-call" []] ((juxt heard :undelivered) (st "on-call" true))))
        (is (= "on-call" (heard (st "starting" true))) "a tab on its way")
        (is (= ["none" ["t1"]] ((juxt heard :undelivered) (st "backing-off" true))))
        (is (= "none" (heard (st "on-call" false))) "serve isn't running")
        (is (= [[{:repo "b" :state "on-call"}] {:alive true :pid 1}] ((juxt :oncall :serve) (st "on-call" true))))))))

;; ================================================================ unit: on call

(deftest oncall-decisions
  (let [now 100000000
        hour 3600000
        base {:now now :oncall {"infra" {:dir "/x/infra"}} :memory {} :duty [] :pending #{"infra"}
              :panes #{7} :sessions {} :idle-ms hour :grace-ms 120000}
        acts #(core/oncall-actions (merge base %))
        tab {:pane 42 :gui-pid 9}
        ours (fn [& {:as more}] (merge {:repo "infra" :pane 42 :session-pid 500 :on-duty true} more))
        idle (fn [ago] {500 {:status "idle" :status-updated-at (- now ago)}})]
    (testing "spawn: mail waits, nobody is on duty, nothing spawned recently"
      (is (= [[:spawn "infra"]] (acts {})))
      (is (= [[:spawn "infra"]] (acts {:duty [{:repo "infra" :on-duty false}]})) "a stale record is nobody"))
    (testing "no spawn: nothing waits, someone is on duty (a session the user started too), not on call"
      (is (= [] (acts {:pending #{}})))
      (is (= [] (acts {:pending #{"web"}})) "mail for another repo")
      (is (= [] (acts {:duty [{:repo "infra" :on-duty true :pane 3 :session-pid 77}]})))
      (is (= [] (acts {:oncall {}}))))
    (testing "no second tab within the grace period"
      (is (= [] (acts {:memory {"infra" (assoc tab :spawned-at (- now 119999))} :panes #{7 42}}))))
    (testing "back off when the grace period passes and nobody is on duty: 2 min, doubling, at most 1 h"
      (is (= [[:back-off "infra" (+ now 120000) "no session came on duty"]]
             (acts {:memory {"infra" (assoc tab :spawned-at (- now 120000))} :panes #{42}})))
      (is (= [[:back-off "infra" (+ now 240000) "no session came on duty"]]
             (acts {:memory {"infra" (assoc tab :spawned-at (- now 200000) :failures 1)} :panes #{42}})))
      (is (= [120000 240000 480000 960000 1920000 3600000 3600000] (map core/back-off-ms [1 2 3 4 5 6 40])))
      (is (= [[:forget "infra"] [:back-off "infra" (+ now 120000) "its tab closed before a session came on duty"]]
             (acts {:memory {"infra" (assoc tab :spawned-at (- now 5000))} :panes #{7}}))
          "a tab that closes at once (no claude on PATH) backs off at once"))
    (testing "backing off: nothing until it is over, then a new tab, or another back-off while the old tab is open"
      (let [backing {:failures 1 :until (+ now 1) :reason "no session came on duty"}]
        (is (= [] (acts {:memory {"infra" backing}})))
        (is (= [[:spawn "infra"]] (acts {:memory {"infra" (assoc backing :until now)}})))
        (is (= [[:back-off "infra" (+ now 240000) "its tab (pane 42) is open, but nobody is on duty"]]
               (acts {:memory {"infra" (merge backing tab {:until now})} :panes #{42}}))
            "never a second tab for one repo")))
    (testing "on duty after a spawn or a back-off: reset"
      (is (= [[:reset "infra"]] (acts {:memory {"infra" (assoc tab :spawned-at (- now 5000))} :panes #{42} :duty [(ours)]})))
      (is (= [[:reset "infra"]] (acts {:memory {"infra" {:failures 2 :until (+ now 9)}} :duty [(ours :pane nil)]}))))
    (testing "close serve's own tab once nothing waits and its session has been idle long enough"
      (let [closing {:memory {"infra" tab} :panes #{7 42} :duty [(ours)] :pending #{}}]
        (is (= [[:close "infra" 42]] (acts (assoc closing :sessions (idle hour)))))
        (is (= [[:close "infra" 42]] (acts (assoc closing :sessions (idle (* 5 hour))))))
        (is (= [] (acts (assoc closing :sessions (idle hour) :pending #{"infra"}))) "mail waits: keep it")
        (is (= [] (acts (assoc closing :sessions (idle (dec hour))))) "idle too briefly")
        (is (= [] (acts (assoc closing :sessions {500 {:status "busy" :status-updated-at (- now (* 5 hour))}}))) "busy")
        (is (= [[:close "infra" 42]] (acts (assoc closing :sessions {500 {:status "shell" :status-updated-at (- now (* 5 hour))}})))
            "shell: idle between turns, with the listener's own wait running")
        (is (= [] (acts (assoc closing :sessions {500 {:status "shell" :status-updated-at (- now (dec hour))}})))
            "shell too briefly")
        (is (= [] (acts (assoc closing :sessions {500 {:status "waiting" :status-updated-at (- now (* 5 hour))}})))
            "waiting on the user")
        (is (= [] (acts (assoc closing :sessions {}))) "no live session behind the record: nothing to judge by")
        (is (= [] (acts (assoc closing :duty [(ours) (ours :session-pid 501)]
                               :sessions (assoc (idle hour) 501 {:status "busy" :status-updated-at 0}))))
            "every session in the pane must be idle")
        (is (= [] (acts (assoc closing :sessions (idle hour) :panes nil))) "wezterm could not list its panes")
        (is (= [[:close "infra" 42]] (acts (assoc closing :sessions (idle hour) :oncall {})))
            "a repo taken off call still gets serve's tab closed")))
    (testing "sessions the user started are never touched"
      (let [users {:pending #{} :panes #{7 42 50} :sessions (idle (* 5 hour)) :duty [(ours :pane 50)]}]
        (is (= [] (acts users)) "no tab of serve's")
        (is (= [] (acts (assoc users :memory {"infra" tab}))) "serve's tab has no session of its own, the user's is idle")
        (is (= [] (acts (assoc users :duty [(ours :pane 7)]))) "a session in the user's own pane")))
    (testing "forget a tab that is gone, or is an older WezTerm's"
      (is (= [[:forget "infra"]] (acts {:memory {"infra" tab} :pending #{}})))
      (is (= [[:forget "infra"] [:spawn "infra"]] (acts {:memory {"infra" tab}})) "and open a new one when mail waits")
      (is (= [[:forget "infra"]] (acts {:memory {"infra" tab} :panes #{42} :gui-pid 10 :pending #{}})))
      (is (= [] (acts {:memory {"infra" tab} :panes #{42} :gui-pid 9 :pending #{}})))
      (is (= [] (acts {:memory {"infra" tab} :panes nil :pending #{}})) "wezterm could not tell: keep it")
      (is (= [] (acts {:memory {"infra" tab} :panes nil})) "and open no other")
      (is (= [[:forget "infra"] [:drop "infra"]] (acts {:memory {"infra" tab} :oncall {}}))
          "taken off call, its tab gone: forget it all")
      (is (= [[:spawn "infra"] [:drop "web"]] (acts {:memory {"web" {:failures 3}}})) "repos in name order"))
    (testing "remember: serve's memory after each action"
      (let [r #(core/remember %1 %2 now)]
        (is (= {"infra" {:pane 42 :gui-pid 9 :spawned-at now :failures 1}}
               (r {"infra" {:failures 1 :until 5 :reason "x"}} [:spawned "infra" 42 9])))
        (is (= {"infra" {:failures 2 :until 7 :reason "why"}} (r {"infra" {:failures 1 :spawned-at 3}} [:back-off "infra" 7 "why"])))
        (is (= {"infra" tab} (r {"infra" (assoc tab :spawned-at 1 :failures 2 :until 3 :reason "x")} [:reset "infra"])))
        (is (= {} (r {"infra" tab} [:close "infra" 42])) "nothing left: dropped")
        (is (= {"infra" {:failures 1}} (r {"infra" (assoc tab :failures 1)} [:forget "infra"])))
        (is (= {"web" {:failures 2}} (r {"infra" {:failures 1} "web" {:failures 2}} [:drop "infra"])))
        (is (= {"infra" tab} (r {"infra" tab} [:spawn "infra"])) "a spawn ends as :spawned or :back-off")))
    (testing "oncall-view: each registered repo's state, in words"
      (let [v (fn [m & {:as more}]
                (first (core/oncall-view (merge {:now now :oncall {"infra" {:dir "/x/infra"}} :memory {"infra" m}
                                                 :duty [] :sessions {}} more))))
            later (+ now 60000)]
        (is (= {:repo "infra" :dir "/x/infra" :state "on-call" :pane nil :text "on call"} (v {})))
        (is (= ["starting" "tab opening (pane 42)"] ((juxt :state :text) (v (assoc tab :spawned-at now)))))
        (let [b (v {:failures 1 :until later :reason "no session came on duty"})]
          (is (= "backing-off" (:state b)))
          (is (= (str "backing off until " (core/clock later) ": no session came on duty") (:text b)))
          (is (= [(core/fmt-utc (java.time.Instant/ofEpochMilli later)) "no session came on duty"] ((juxt :until :reason) b))))
        (is (= "on-call" (:state (v {:failures 1 :until now :reason "x"}))) "a back-off that is over")
        (is (= ["tab-open" "tab open (pane 42), nobody on duty"] ((juxt :state :text) (v tab))))
        (is (= ["on-duty" 42 "tab open (pane 42), on duty, idle 7m" 420 ["📫 infra"]]
               ((juxt :state :pane :text :idle-s :sessions) (v tab :duty [(ours :session "📫 infra")] :sessions (idle 420000)))))
        (is (= "tab open (pane 42), on duty, busy 0s"
               (:text (v tab :duty [(ours)] :sessions {500 {:status "busy" :status-updated-at now}}))))
        (let [sh (v tab :duty [(ours)] :sessions {500 {:status "shell" :status-updated-at (- now (* 3 hour))}})]
          (is (= "tab open (pane 42), on duty, idle 3h" (:text sh)) "shell is idle between turns, and since when")
          (is (= (* 3 3600) (:idle-s sh)) "closable (see closable-statuses)"))
        (is (= "on duty (session hand-1)" (:text (v {} :duty [(ours :pane 3 :session "hand-1")]))) "a session the user started")
        (is (empty? (core/oncall-view {:now now :oncall {} :memory {"infra" tab} :duty [] :sessions {}})) "registered repos only")))
    (testing "the tab's command line, quoted for the shell"
      (is (= "exec claude --name '📫 infra' '/agent-mail-listen'" (core/listener-command "infra")))
      (is (= "'it'\\''s'" (core/sh-quote "it's")))
      (let [nasty "it's $HOME `id` \"q\" ; ok"]
        (is (= nasty (:out (p/shell {:out :string} "sh" "-c" (str "printf %s " (core/sh-quote nasty)))))
            "the shell gets the text back unchanged")))))

;; ================================================================ unit: wait announcements

(deftest wait-announcements
  (let [th (fn [id n & files] {:id id :number n :subject (str "About " id) :last-from "web"
                               :msgs (mapv #(hash-map :file %) files)})
        t1 (th "t1" 1 "001.md")
        t1' (th "t1" 1 "001.md" "002.md")
        t2 (th "t2" 2 "001.md")]
    (testing "announce: every thread at first, then only a newer message"
      (is (= {:fresh [t1 t2] :announced {"t1" "001.md" "t2" "001.md"}} (core/announce {} [t1 t2])))
      (is (= {:fresh [] :announced {"t1" "001.md" "t2" "001.md"}}
             (core/announce {"t1" "001.md" "t2" "001.md"} [t1 t2])) "nothing new: nothing to say")
      (is (= [t1'] (:fresh (core/announce {"t1" "001.md" "t2" "001.md"} [t1' t2]))) "a reply in t1 is news")
      (is (= {:fresh [] :announced {"t2" "001.md"}} (core/announce {"t1" "001.md" "t2" "001.md"} [t2]))
          "a thread handed on is forgotten")
      (is (= {:fresh [] :announced {}} (core/announce {"t1" "001.md"} []))))
    (testing "announcement: one line per thread, then where it comes from"
      (let [lines (str/split-lines (core/announcement "infra" [t1 t2]))]
        (is (= 3 (count lines)))
        (is (= "agent-mail: new mail for infra: #1 \"About t1\" from web (t1, message 1). Read it with `agent-mail read 1` and answer before moving on."
               (first lines)))
        (is (str/starts-with? (second lines) "agent-mail: new mail for infra: #2 \"About t2\""))
        (is (str/includes? (last lines) "`agent-mail wait`, which keeps running")))
      (is (str/includes? (core/announcement "infra" [t1']) "(t1, message 2)")
          "announced again, the text differs: identical repeats may be dropped")
      (is (str/includes? (core/announcement "infra" [(assoc t1 :number nil)]) "#t1 \"About t1\"")
          "a thread without a number is named by its id")
      (is (str/includes? (core/announcement "infra" [(assoc t1 :last-from "user")])
                         "from user (the user themself, writing by mail; not another agent or session) (t1, message 1)")
          "mail from the user says so: Claude Code frames every posted message as another session's"))
    (testing "a closed thread with news says so, and that it needs reading, not an answer"
      (let [line (first (str/split-lines (core/announcement "infra" [(assoc t1 :status "done")])))]
        (is (str/starts-with? line "agent-mail: closed, with news for infra: #1 \"About t1\" closed by web (t1, message 1)."))
        (is (str/includes? line "nothing to answer"))
        (is (not (str/includes? line "answer before moving on")))))))

;; ================================================================ unit: drafts

(deftest draft-extraction
  (let [marker core/reply-marker
        draft (fn [& lines] (str (str/join "\n" lines) "\n"))]
    (testing "nothing written sends nothing"
      (is (= "" (core/reply-from-draft "")))
      (is (= "" (core/reply-from-draft "\n\n")) "a draft starting with blank lines")
      (is (= "> […]\n> a\nHi" (core/reply-from-draft (draft "" "> a" "Hi" "---" marker))) "a leading blank line is harmless")
      (is (= "" (core/reply-from-draft (draft "> # S" "> body" "" "---" marker "" ""))))
      (is (= "" (core/reply-from-draft (draft "> # S" ">" "" "---" marker "   ")))))
    (testing "text under the marker is the reply, paragraphs kept, no context"
      (is (= "Para one.\n\nPara two." (core/reply-from-draft (draft "> a" "" "---" marker "" "Para one." "" "Para two." "" ""))))
      (is (= "> by hand" (core/reply-from-draft (draft "> a" "---" marker "> by hand"))) "a quote typed below the marker is kept"))
    (testing "an inline answer carries up to two quoted lines above it and marks the gap"
      (is (= "> […]\n> Second point.\n> Third point.\nYes to the third."
             (core/reply-from-draft (draft "> # Subject" "> thread: #1" ">" "> ## a → b" ">" "> First point." "> Second point." "> Third point." "Yes to the third." "" "---" marker "")))))
    (testing "no gap marker right at the top"
      (is (= "> # Subject\nHello" (core/reply-from-draft (draft "> # Subject" "Hello" "---" marker)))))
    (testing "blank quoted lines are kept inside the context but not at its top"
      (is (= "> ## a → b\n>\n> body\n>\nAnswer" (core/reply-from-draft (draft "> ## a → b" ">" "> body" ">" "Answer" "---" marker)))))
    (testing "two answers close together share their context"
      (is (= "> […]\n> b\n> c\nX\n> d\nY" (core/reply-from-draft (draft "> a" "> b" "> c" "X" "> d" "Y" "---" marker)))))
    (testing "an answer continued over a blank line stays one paragraph break"
      (is (= "> a\nX\n\nX2" (core/reply-from-draft (draft "> a" "X" "" "X2" "---" marker)))))
    (testing "bottom-posting after deleting the marker: the separator is not context"
      (is (= "My reply\nSecond line" (core/reply-from-draft (draft "> a" "> b" "" "---" "" "My reply" "Second line")))))
    (testing "inline and bottom text both go out, in order"
      (let [r (core/reply-from-draft (draft "> a" "> b" "Inline." "---" marker "" "Bottom."))]
        (is (= "> a\n> b\nInline.\nBottom." r))))
    (is (= "> a\n>\n> b" (core/quote-lines "a\n\nb")))))

;; ================================================================ unit: forwarding

(deftest forward-helpers
  (let [m1 {:from "a" :to "b" :date "2026-11-01T08:00:00Z" :file "m1.md" :body "Look at ![x](files/x.png)" :attachments ["files/x.png"]}
        m2 {:from "b" :to "a" :date "2026-11-01T09:00:00Z" :file "m2.md" :body "See [log](files/run.log \"the log\") and [x](files/x.png)" :status "done"}
        th {:id "2026-11-01-keys" :number 4 :subject "Keys" :from "a" :msgs [m1 m2]}]
    (testing "the subject gets Fwd: once"
      (is (= "Fwd: Keys" (core/forward-subject "Keys")))
      (is (= "Fwd: Keys" (core/forward-subject "Fwd: Keys"))))
    (testing "a message by position or by file name"
      (is (= m2 (core/pick-message th "2")))
      (is (= m1 (core/pick-message th "m1.md")))
      (is (usage-thrown? #(core/pick-message th "3")))
      (is (usage-thrown? #(core/pick-message th "0")))
      (is (usage-thrown? #(core/pick-message th "nope.md"))))
    (testing "the files messages use: attachments, then files their bodies link to, once each"
      (is (= ["files/x.png" "files/run.log"] (core/file-refs [m1 m2])))
      (is (= ["files/run.log" "files/x.png"] (core/file-refs [m2]))))
    (testing "the forwarded text names the thread and nests each message under a heading"
      (let [all (core/forward-text th [m1 m2])
            one (core/forward-text th [m2])]
        (is (str/starts-with? all "Forwarded thread #4 **Keys**, started by a, 2 messages (`agent-mail read 2026-11-01-keys`)\n"))
        (is (str/includes? all (str "\n### a → b · " (core/local-time (:date m1)) "\n\nLook at ![x](files/x.png)\n")))
        (is (str/includes? all (str "\n### b → a · " (core/local-time (:date m2)) " · done\n")))
        (is (str/starts-with? one "Forwarded message 2 of 2 from thread #4 **Keys** (`agent-mail read 2026-11-01-keys`)\n"))
        (is (not (str/includes? one "### a → b")))))
    (testing "the note comes first, then a rule; no note, no rule"
      (is (= "Please check\n\n---\n\nFWD" (core/forward-body "  Please check\n" "FWD")))
      (is (= "FWD" (core/forward-body nil "FWD")))
      (is (= "FWD" (core/forward-body " \n" "FWD"))))))

;; ================================================================ unit: argv

(deftest argv-parsing
  (is (= {:args ["reply" "7" "the" "text"] :opts {:attach [] :to "b" :json true}}
         (core/parse-argv ["reply" "--to" "b" "7" "the" "--json" "text"])) "options anywhere")
  (is (= {:args ["send" "--to" "x" "-h"] :opts {:attach [] :to "b"}}
         (core/parse-argv ["send" "--to" "b" "--" "--to" "x" "-h"])) "-- ends options")
  (is (= ["a" "b"] (:attach (:opts (core/parse-argv ["send" "--attach" "a" "--attach" "b"])))))
  (is (= {:args ["forward" "7"] :opts {:attach [] :to "c" :msg "2"}} (core/parse-argv ["forward" "7" "--msg" "2" "--to" "c"])))
  (is (= 5 (:timeout (:opts (core/parse-argv ["wait" "--timeout" "5"])))))
  (is (= 2 (:interval (:opts (core/parse-argv ["wait" "--interval" "2" "--"])))) "numbers are parsed even when -- ends the options")
  (is (true? (:once (:opts (core/parse-argv ["wait" "--once"])))))
  (is (= 90 (:idle-minutes (:opts (core/parse-argv ["serve" "--idle-minutes" "90"])))))
  (is (= "--idle-minutes needs a whole number of minutes, got soon"
         (try (core/parse-argv ["serve" "--idle-minutes" "soon"]) nil (catch clojure.lang.ExceptionInfo e (ex-message e)))))
  (is (= ["help" "read" "7"] (:args (core/parse-argv ["read" "7" "--help"]))) "help anywhere comes first")
  (is (= ["help"] (:args (core/parse-argv ["-h"]))))
  (is (usage-thrown? #(core/parse-argv ["wait" "--timeout" "soon"])))
  (is (usage-thrown? #(core/parse-argv ["send" "--to"])))
  (is (usage-thrown? #(core/parse-argv ["send" "--attach"])))
  (is (usage-thrown? #(core/parse-argv ["send" "--bogus"])))
  (let [expand (comp core/expand-shorthand core/parse-argv)]
    (is (= {:args ["send" "hi" "there"] :opts {:attach [] :to "infra"}} (expand ["@infra" "hi" "there"])))
    (is (= {:args ["send" "hi"] :opts {:attach [] :to "infra" :as "me"}} (expand ["--as" "me" "@infra" "hi"])) "options before @NAME")
    (is (= ["list"] (:args (expand ["list"]))))
    (is (= [] (:args (expand [])))))
  (is (= "2026-10-iPhone" (core/address-from-dir "2026-10 iPhone")))
  (is (= "a-b" (core/address-from-dir " a / b ")))
  (is (nil? (core/address-from-dir "  "))))

;; ================================================================ end to end

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
          (is (str/includes? r "waiting on: website"))))
      (testing "close marks it done and it leaves every list"
        (is (= id (out {:who "website"} "close" id)))
        (is (empty? (ids "infra")))
        (is (str/includes? (out {} "list" "--all") "done"))))))

(deftest a-reply-closed-at-once-still-reaches-the-asker
  (with-store
    (let [id (out {:who "website"} "@infra" "Which TTL?")]
      (out {:who "infra"} "reply" id "300.")
      (out {:who "infra"} "close" id)
      (testing "list, wait and the hook all show it to the asker, as closed"
        (is (= [id] (ids "website")))
        (is (str/includes? (out {:who "website"} "list") " done "))
        (is (= [id] (str/split-lines (out {:who "website"} "wait" "--once" "--timeout" "5"))))
        (is (str/includes? (out {:who "website"} "hook")
                           (str "1 closed thread with news for website, not read yet: #1 " id " \"Which TTL?\" closed by infra"))))
      (testing "nothing for the side that closed it"
        (is (empty? (ids "infra"))))
      (testing "reading it clears it"
        (out {:who "website"} "read" id)
        (is (empty? (ids "website")))
        (is (str/blank? (out {:who "website"} "hook")))))
    (testing "the asker's own close is no news to the answerer"
      (let [id (out {:who "website"} "@infra" "Another")]
        (out {:who "infra"} "reply" id "Done.")
        (out {:who "website"} "close" id "Thanks.")
        (is (empty? (ids "infra")))
        (is (empty? (ids "website")))))))

(deftest long-thread-turn-passing
  (with-store
    (let [id (out {:who "website"} "@infra" "Need a DNS record")
          turn (fn [who] (is (= [id] (ids who)) (str "turn is " who "'s"))
                 (is (empty? (ids (if (= who "infra") "website" "infra")))))]
      (turn "infra")
      (out {:who "infra"} "reply" id "Which name?")
      (turn "website")
      (out {:who "website"} "reply" id "api.example.test")
      (turn "infra")
      (out {:who "infra"} "reply" id "Added. TTL?")
      (turn "website")
      (out {:who "website"} "reply" id "300 is fine.")
      (turn "infra")
      (let [th (thread-json id)]
        (is (= 5 (:count th)))
        (is (= "infra" (:owner th)))
        (is (= ["website" "infra" "website" "infra" "website"] (map :from (:msgs th))))
        (is (= (map :file (butlast (:msgs th))) (map :in-reply-to (rest (:msgs th)))) "each reply names the message before it")
        (is (apply < (map #(.toEpochMilli (java.time.Instant/parse (:date %))) (:msgs th))) "dates strictly increase")
        (is (every? #(= (:file %) (str (core/stamp->name (:date %)) "__" (:from %) "__" (subs (:file %) (- (count (:file %)) 7)))) (:msgs th))
            "file names are stamp__from__hex.md"))
      (testing "the sender closes while it is the other side's turn"
        (out {:who "website"} "close" id "Thanks, all set.")
        (let [th (thread-json id)]
          (is (= "done" (:status th)))
          (is (= "infra" (:owner th)) "the closing note goes to infra")
          (is (= "done" (:status (last (:msgs th)))))
          (is (= "Thanks, all set." (:body (last (:msgs th))))))
        (is (= [id] (ids "infra")) "infra never read \"300 is fine.\", its answer: news, though closed")
        (is (empty? (ids "website")))
        (out {:who "infra"} "read" id)
        (is (empty? (ids "infra")))
        (is (str/includes? (out {} "read" id) "· done"))))))

(deftest redirect-to-a-third-party
  (with-store
    (let [id (out {:who "website"} "@infra" "Need a certificate")]
      (is (= id (out {:who "infra"} "reply" id "--to" "user" "Needs your OK to buy one.")))
      (is (= [id] (ids "user")))
      (is (empty? (ids "website")))
      (is (empty? (ids "infra")))
      (testing "the third party's plain reply goes back to who redirected, not the starter"
        (out {:who "user"} "reply" id "OK, buy it.")
        (is (= [id] (ids "infra")))
        (is (= "infra" (:to (last (:msgs (thread-json id)))))))
      (testing "a bystander closing answers the last writer"
        (out {:who "billing"} "close" id "Paid.")
        (let [th (thread-json id)]
          (is (= "done" (:status th)))
          (is (= "user" (:to (last (:msgs th)))))
          (is (= "billing" (:from (last (:msgs th))))))
        (is (= [id] (ids "infra")) "infra never read the user's OK: news, though closed")
        (is (every? empty? (map ids ["user" "website" "billing"])))))))

(deftest same-millisecond-messages-keep-their-order
  (with-store
    (let [d "2026-10-01T09:00:00Z"
          id (out {:who "a"} "send" "--to" "b" "--date" d "quick one")]
      (out {:who "b"} "reply" id "--date" d "first reply")
      (out {:who "a"} "reply" id "--date" d "second")
      (out {:who "b"} "reply" id "--date" "2026-09-01" "backfilled too early")
      (let [th (thread-json id)]
        (is (= ["2026-10-01T09:00:00.000Z" "2026-10-01T09:00:00.001Z" "2026-10-01T09:00:00.002Z" "2026-10-01T09:00:00.003Z"]
               (map :date (:msgs th))) "equal and earlier dates are nudged one millisecond past the last")
        (is (= ["quick one" "first reply" "second" "backfilled too early"] (map :body (:msgs th))))
        (is (= (sort (map :file (:msgs th))) (map :file (:msgs th))) "file order is message order")
        (is (str/starts-with? id "2026-10-01-") "the id carries the backfilled date"))
      (is (= [id] (ids "a")) "the last writer was b, so the turn is a's"))))

(deftest thread-ids-from-subjects
  (with-store
    (let [id1 (out {:who "a"} "@b" "Привет мир")
          id2 (out {:who "a"} "@b" "Привет мир")
          id3 (out {:who "a"} "@b" "Привет мир")
          long-id (out {:who "a"} "@b" (str/join " " (repeat 30 "word")))]
      (is (str/ends-with? id1 "-thread") "no ASCII letters: the fallback slug")
      (is (= (str id1 "-2") id2))
      (is (= (str id1 "-3") id3))
      (is (str/includes? (out {} "read" id2) "# Привет мир") "the subject itself keeps its characters")
      (is (<= (count long-id) (+ 10 1 40)))
      (is (= 4 (count (ids "b"))))
      (is (= [1 2 3 4] (map :number (list-json "--all")))))))

(deftest resolving-threads-from-the-command-line
  (with-store
    (let [v1 (out {:who "a"} "send" "--to" "b" "--subject" "Vault" "--date" "2026-10-30" "one")
          v2 (out {:who "a"} "send" "--to" "b" "--subject" "Vault" "--date" "2026-10-31" "two")]
      (is (= "2026-10-30-vault" v1))
      (is (= "2026-10-31-vault" v2) "a different day is a different base id")
      (let [r (am {} "read" "vault")]
        (is (= 1 (:exit r)))
        (is (str/includes? (:err r) "ambiguous"))
        (is (str/includes? (:err r) v1))
        (is (str/includes? (:err r) v2)))
      (is (str/includes? (out {} "read" "10-30") "\none"))
      (is (str/includes? (out {} "read" v2) "\ntwo"))
      (is (str/includes? (out {} "read" "2") "\ntwo"))
      (is (str/includes? (out {} "read" "#2") "\ntwo"))
      (is (= "agent-mail: no thread #9" (err {} "read" "9")))
      (is (= "agent-mail: no thread matches zzz" (err {} "read" "zzz"))))))

(deftest at-shorthand-and-attachments
  (with-store
    (with-dir [d]
      (let [f (str (fs/path d "shot.txt"))
            _ (spit f "pixels")
            id (out {:who "user"} "@website" "--attach" f "Look at ![this](files/shot.txt) please")]
        (is (str/ends-with? id "-look-at-this-files-shot-txt-please"))
        (is (= "pixels" (slurp (str (fs/path *store* "threads" id "files" "shot.txt")))))
        (is (str/includes? (out {} "read" id) (str "](" (fs/path *store* "threads" id "files" "shot.txt") ")")))))))

(deftest attachments-in-depth
  (with-store
    (with-dir [d]
      (let [a (str (fs/path d "a.txt")) b (str (fs/path d "b.txt"))
            other (str (fs/path d "other")) _ (fs/create-dirs other)
            a2 (str (fs/path other "a.txt"))
            _ (spit a "A") _ (spit b "B") _ (spit a2 "A prime")
            files-of (fn [id] (sort (map #(str (fs/file-name %)) (fs/list-dir (fs/path *store* "threads" id "files")))))]
        (testing "a missing attachment is refused before anything is written"
          (let [r (am {:who "a"} "send" "--to" "b" "--attach" (str (fs/path d "nope.txt")) "with a file")]
            (is (= 1 (:exit r)))
            (is (str/includes? (:err r) "attachment not found")))
          (is (empty? (fs/list-dir (fs/path *store* "threads"))) "no half thread left behind")
          (is (= 1 (commits)))
          (is (str/blank? (out {} "list" "--all"))))
        (let [id (out {:who "a"} "send" "--to" "b" "--subject" "Files" "--attach" a "--attach" b "two files")]
          (testing "several files in one message"
            (is (= ["files/a.txt" "files/b.txt"] (:attachments (first (:msgs (thread-json id))))))
            (is (= ["a.txt" "b.txt"] (files-of id)))
            (let [r (out {} "read" id)]
              (is (str/includes? r (str "attachments: " (fs/path *store* "threads" id "files" "a.txt") ", " (fs/path *store* "threads" id "files" "b.txt"))))))
          (testing "a reply attaching the same name with different content keeps both"
            (out {:who "b"} "reply" id "--attach" a2 "mine too")
            (let [atts (:attachments (last (:msgs (thread-json id))))]
              (is (= 1 (count atts)))
              (is (re-matches #"files/[0-9a-f]{6}-a\.txt" (first atts)))
              (is (= "A prime" (slurp (str (fs/path *store* "threads" id (first atts))))))
              (is (= "A" (slurp (str (fs/path *store* "threads" id "files" "a.txt")))) "the first file is untouched")))
          (testing "the same name with the same content is reused"
            (out {:who "a"} "reply" id "--attach" a "again")
            (is (= ["files/a.txt"] (:attachments (last (:msgs (thread-json id))))))
            (is (= 3 (count (files-of id)))))
          (testing "a reply with a missing attachment adds nothing"
            (let [before (thread-json id)
                  r (am {:who "b"} "reply" id "--attach" a "--attach" (str (fs/path d "gone.txt")) "half")]
              (is (= 1 (:exit r)))
              (is (= (:count before) (:count (thread-json id))))
              (is (= 3 (count (files-of id))))
              (is (str/blank? (store-git "status" "--porcelain")) "the store stays clean"))))))))

(deftest forward-a-thread
  (with-store
    (with-dir [d]
      (let [shot (str (fs/path d "shot.png")) log (str (fs/path d "run.log")) extra (str (fs/path d "extra.txt"))
            _ (spit shot "pixels") _ (spit log "lines") _ (spit extra "more")
            src (out {:who "a"} "send" "--to" "b" "--subject" "Rotate the key" "--attach" shot "Rotate it, see ![s](files/shot.png)")
            _ (out {:who "b"} "reply" src "--attach" log "Done, log attached; the shot again: ![s](files/shot.png)")
            files-of (fn [id] (sort (map #(str (fs/file-name %)) (fs/list-dir (fs/path *store* "threads" id "files")))))]
        (testing "the whole thread, with a note on the command line"
          (let [before (commits)
                r (am {:who "b"} "forward" "1" "--to" "c" "Can you double-check this?")
                id (str/trim (:out r))
                th (thread-json id)
                [m] (:msgs th)]
            (is (zero? (:exit r)))
            (is (str/includes? (:err r) "→ c:") "a delivery note, as for send")
            (is (= "Fwd: Rotate the key" (:subject th)))
            (is (= src (:forwarded-from th)))
            (is (= ["b" "c" "open"] [(:from th) (:owner th) (:status th)]))
            (is (str/starts-with? (:body m) "Can you double-check this?\n\n---\n\nForwarded thread #1 **Rotate the key**, started by a, 2 messages"))
            (is (str/includes? (:body m) "### a → b · "))
            (is (str/includes? (:body m) "### b → a · "))
            (is (= ["files/shot.png" "files/run.log"] (:attachments m)) "the files the messages use, copied")
            (is (= ["run.log" "shot.png"] (files-of id)))
            (is (= "pixels" (slurp (str (fs/path *store* "threads" id "files" "shot.png")))))
            (is (= (inc before) (commits)))
            (is (str/includes? (store-git "log" "-1" "--format=%s") "b → c: Fwd: Rotate the key"))
            (testing "read names the source by number; status and list carry it"
              (is (str/includes? (out {} "read" id) (str "forwarded from: #1 " src)))
              (is (str/includes? (out {} "read" id) (str "](" (fs/path *store* "threads" id "files" "shot.png") ")"))
                  "links into the copied files resolve")
              (is (= src (:forwarded-from (first (filter #(= id (:id %)) (:threads (json/parse-string (out {} "status" "--json") true)))))))
              (is (= src (:forwarded-from (first (filter #(= id (:id %)) (list-json "--all")))))))
            (is (= 2 (:count (thread-json src))) "the source is left as it was")))
        (testing "one message, by position, without a note: the files its body links to come along"
          (let [id (out {:who "user"} "forward" src "--to" "d" "--msg" "2")
                [m] (:msgs (thread-json id))]
            (is (str/starts-with? (:body m) "Forwarded message 2 of 2 from thread #1 **Rotate the key**"))
            (is (not (str/includes? (:body m) "### a → b")))
            (is (= ["files/run.log" "files/shot.png"] (:attachments m)))))
        (testing "by file name, with a note on stdin, a subject and an attachment of its own"
          (let [file (:file (first (:msgs (thread-json src))))
                id (out {:who "user" :in (fs/file log)} "forward" src "--to" "d" "--msg" file "--subject" "Look" "--attach" extra)
                th (thread-json id)
                [m] (:msgs th)]
            (is (= "Look" (:subject th)))
            (is (str/starts-with? (:body m) "lines\n\n---\n\nForwarded message 1 of 2"))
            (is (= ["files/shot.png" "files/extra.txt"] (:attachments m)))))
        (testing "refusals leave nothing behind"
          (let [n (count (fs/list-dir (fs/path *store* "threads")))
                refused (fn [& args] (let [r (apply am {:who "b"} args)] (and (= 1 (:exit r)) (:err r))))]
            (is (str/includes? (refused "forward" "1") "forward needs --to NAME"))
            (is (str/includes? (refused "forward" "--to" "c") "forward needs a thread"))
            (is (str/includes? (refused "forward" "1" "--to" "b") "refusing to mail yourself"))
            (is (str/includes? (refused "forward" "1" "--to" "c" "--msg" "9") "no message \"9\" in thread #1"))
            (is (str/includes? (refused "forward" "99" "--to" "c") "no thread #99"))
            (is (str/includes? (refused "forward" "1" "--to" "c" "--attach" (str (fs/path d "gone"))) "attachment not found"))
            (is (= n (count (fs/list-dir (fs/path *store* "threads")))))
            (is (str/blank? (store-git "status" "--porcelain")))))))))

(deftest bodies-from-stdin-and-argv
  (with-store
    (let [body "Rotate the key\n\nSecond paragraph with `code`.\n- a list\n"
          id (out {:who "a" :in body} "send" "--to" "b")]
      (is (str/ends-with? id "-rotate-the-key") "the subject is the first line")
      (is (= (str/trim body) (:body (first (:msgs (thread-json id))))) "the body keeps its lines"))
    (let [id (out {:who "a"} "@b" "words" "from" "argv")]
      (is (= "words from argv" (:body (first (:msgs (thread-json id)))))))
    (let [line (str/join " " (repeat 20 "long"))
          id (out {:who "a" :in (str line "\nmore\n")} "send" "--to" "b")
          th (thread-json id)]
      (is (= 70 (count (:subject th))))
      (is (str/ends-with? (:subject th) "…"))
      (is (<= (count id) 51)))
    (let [id (out {:who "a" :in "body text"} "send" "--to" "b" "--subject" "Given")]
      (is (= "Given" (:subject (thread-json id))))
      (is (str/ends-with? id "-given")))
    (testing "a reply reads its body from stdin too"
      (let [id (out {:who "a"} "@b" "q")]
        (is (= id (out {:who "b" :in "answer\nover two lines\n"} "reply" id)))
        (is (= "answer\nover two lines" (:body (last (:msgs (thread-json id))))))))
    (testing "close with no text takes a note from stdin but never waits for one"
      (let [id (out {:who "a"} "@b" "done?")
            proc (start {:who "b" :in :open} "close" id)]
        (is (.waitFor (:proc proc) 10 java.util.concurrent.TimeUnit/SECONDS)
            "an open, empty stdin is not read")
        (when (.isAlive (:proc proc)) (.destroyForcibly (:proc proc)))
        (is (= id (str/trim (:out @proc))))
        (is (= "Closed." (:body (last (:msgs (thread-json id)))))))
      (let [id (out {:who "a"} "@b" "done?")]
        (is (= id (out {:who "b" :in "All done.\n"} "close" id)))
        (is (= "All done." (:body (last (:msgs (thread-json id))))) "a pipe is the note")))
    (testing "text on the command line plus a file on stdin: the text leads, the file follows"
      (with-dir [d]
        (let [f (fs/file d "body.md")
              _ (spit f "- **What happened:** it broke\n- **Cost:** a turn\n")
              id (out {:who "a" :in f} "@b" "the-repo: it broke")
              th (thread-json id)]
          (is (= "the-repo: it broke" (:subject th)) "the text is the subject")
          (is (= "the-repo: it broke\n\n- **What happened:** it broke\n- **Cost:** a turn" (:body (first (:msgs th)))))
          (is (= "the-repo: it broke\n\nand a pipe" (:body (first (:msgs (thread-json (out {:who "a" :in "and a pipe\n"} "@b" "the-repo: it broke"))))))
              "a pipe works the same way")
          (is (= "Given" (:subject (thread-json (out {:who "a" :in f} "send" "--to" "b" "--subject" "Given" "lead"))))
              "an explicit subject stays")
          (is (= "lead\n\nmore" (:body (last (:msgs (thread-json (do (out {:who "b" :in "more"} "reply" id "lead") id))))))
              "a reply too"))))
    (testing "a lone `-` means the body is on stdin, and is not part of it"
      (let [id (out {:who "a" :in "Hello.\n"} "@b" "-")]
        (is (= "Hello." (:body (first (:msgs (thread-json id)))))))
      (let [id (out {:who "a"} "@b" "q")]
        (out {:who "b" :in "Answer.\n"} "reply" id "-")
        (is (= "Answer." (:body (last (:msgs (thread-json id))))))
        (is (= "agent-mail: empty body" (err {:who "b" :in ""} "reply" id "-")) "nothing on stdin: refused, not \"-\"")
        (out {:who "b" :in "Bye.\n"} "close" id "-")
        (is (= "Bye." (:body (last (:msgs (thread-json id))))))))))

(deftest caller-from-a-directory-with-a-space
  (with-store
    (with-dir [d]
      (let [dir (str (fs/create-dirs (fs/path d "2026-10 iPhone")))
            r (am {:dir dir} "whoami")]
        (is (= "2026-10-iPhone" (str/trim (:out r))) "the folder's name, made an address")
        (is (str/includes? (:err r) "acting as \"2026-10-iPhone\"")))
      (testing "--as before @NAME"
        (let [id (out {:dir d} "--as" "guide" "@b" "hello")]
          (is (= "guide" (:from (first (:msgs (thread-json id)))))))))))

(deftest json-shapes
  (with-store
    (let [id (out {:who "a"} "send" "--to" "b" "--subject" "Shape" "hello")]
      (out {:who "b"} "reply" id "back")
      (testing "list --json is one summary per thread, no messages"
        (let [[t :as ts] (list-json "--all")]
          (is (= 1 (count ts)))
          (is (= #{:id :number :subject :from :last-from :created :owner :status :count :updated :unread :flow} (set (keys t))))
          (is (= {:id id :number 1 :subject "Shape" :from "a" :owner "a" :status "open" :count 2} (select-keys t [:id :number :subject :from :owner :status :count])))
          (is (= ["a" "b" "a"] (:flow t)) "a wrote, b answered: a's turn")
          (is (= 2 (:unread t)) "the caller here is a bystander, so both messages are new to it")))
      (testing "read --json is the thread with its messages"
        (let [th (thread-json id)]
          (is (= id (:id th)))
          (is (= 2 (count (:msgs th))))
          (is (= #{:from :to :date :file :body} (set (keys (first (:msgs th))))))
          (is (= #{:from :to :date :file :body :in-reply-to} (set (keys (second (:msgs th))))))
          (is (= "hello" (:body (first (:msgs th)))))
          (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z" (:date (first (:msgs th)))))))
      (testing "list for a party counts what is new to them"
        (is (= 1 (:unread (first (json/parse-string (out {:who "a"} "list" "--json") true)))))))))

(deftest unread-markers-are-per-reader
  (with-store
    (with-dir [d]
      (let [viewer (script! d "viewer.sh" "exit 0")
            id (out {:who "a"} "@b" "Hello there")]
        (is (str/includes? (out {:who "b"} "list") "1 new"))
        (is (not (str/includes? (out {:who "a"} "list" "--all") "new")) "the writer has nothing new")
        (is (str/includes? (out {:who "c"} "list" "--all") "1 new") "a bystander sees it as new")
        (out {:who "b"} "read" id)
        (is (not (str/includes? (out {:who "b"} "list") "new")) "reading marks it seen")
        (is (str/includes? (out {:who "c"} "list" "--all") "1 new") "for b only")
        (out {:who "b"} "reply" id "Hi back")
        (is (str/includes? (out {:who "a"} "list") "1 new"))
        (is (str/includes? (out {:who "c"} "list" "--all") "2 new"))
        (am {:who "a" :env {"AGENT_MAIL_VIEWER" viewer}} "open" id)
        (is (not (str/includes? (out {:who "a"} "list") "new")) "open marks it seen")
        (out {:who "a"} "reply" id "And again")
        (is (not (str/includes? (out {:who "a"} "list" "--all") "new")) "my own reply leaves nothing new for me")
        (is (str/includes? (out {:who "b"} "list") "1 new") "b saw the first, not the third")
        (let [typing (script! d "editor.sh" "printf 'From the editor.\\n' >> \"$1\"")]
          (am {:who "b" :env {"AGENT_MAIL_EDITOR" typing}} "reply" id "--edit")
          (is (not (str/includes? (out {:who "b"} "list" "--all") "new")) "composing marks everything seen"))))))

(deftest wait-and-duty
  (with-store
    (testing "wait returns at once when mail is already waiting, and reports working"
      (let [id (out {:who "a"} "@b" "hi")
            r (am {:who "b"} "wait" "--session" "b-1" "--timeout" "5")]
        (is (zero? (:exit r)))
        (is (= id (str/trim (:out r))))
        (is (= "working" (:state (first (filter #(= "b-1" (:session %)) (json/parse-string (out {} "who" "--json") true))))))))
    (testing "wait times out silently with its own exit code"
      (let [r (am {:who "c"} "wait" "--session" "c-1" "--timeout" "1" "--interval" "1")]
        (is (= 3 (:exit r)))
        (is (str/blank? (:out r))))
      (is (= 3 (:exit (am {:who "c"} "wait" "--timeout" "0"))) "a zero timeout checks once"))
    (testing "who lists both"
      (let [w (out {} "who")]
        (is (str/includes? w "b-1"))
        (is (str/includes? w "c-1"))
        (is (not (str/includes? w "stale")))))
    (testing "a waiting record goes stale and delivery notes follow duty"
      (spit (str (fs/path *store* "duty" "infra__old.edn"))
            (pr-str {:repo "infra" :session "old" :state "waiting" :heartbeat "2020-01-01T00:00:00.000Z" :pid 1}))
      (is (str/includes? (out {} "who") "(stale)"))
      (is (str/includes? (err {:who "a"} "@infra" "anyone?") "infra: nobody on duty"))
      (spit (str (fs/path *store* "duty" "infra__fresh.edn"))
            (pr-str {:repo "infra" :session "fresh" :state "waiting" :heartbeat (core/now-utc) :pid 1}))
      (let [e (err {:who "a"} "@infra" "anyone now?")]
        (is (str/includes? e "infra: on duty (session fresh)"))
        (is (str/includes? e "SendMessage")))
      (testing "a listener between waits (timed out, re-arming) still counts as on duty"
        (spit (str (fs/path *store* "duty" "infra__fresh.edn"))
              (pr-str {:repo "infra" :session "fresh" :state "idle" :heartbeat (core/now-utc) :pid 1}))
        (is (str/includes? (err {:who "a"} "@infra" "between waits?") "infra: on duty (session fresh, between waits)"))
        (spit (str (fs/path *store* "duty" "infra__fresh.edn"))
              (pr-str {:repo "infra" :session "fresh" :state "idle" :heartbeat "2020-01-01T00:00:00.000Z" :pid 1}))
        (is (str/includes? (err {:who "a"} "@infra" "gone?") "infra: nobody on duty")))
      (testing "a working record past the heartbeat window counts while its session lives"
        (let [me (.pid (java.lang.ProcessHandle/current))]
          (spit (str (fs/path *store* "duty" "infra__busy.edn"))
                (pr-str {:repo "infra" :session "busy" :state "working" :heartbeat "2020-01-01T00:00:00.000Z" :pid 1 :session-pid me}))
          (is (str/includes? (err {:who "a"} "@infra" "busy?") "infra: on duty (session busy, busy)"))
          (is (str/includes? (out {} "who") "busy                         working") )
          (is (str/includes? (out {} "who") "(session alive)"))
          (spit (str (fs/path *store* "duty" "infra__busy.edn"))
                (pr-str {:repo "infra" :session "busy" :state "working" :heartbeat "2020-01-01T00:00:00.000Z" :pid 1 :session-pid 4000000}))
          (is (str/includes? (out {} "who") "(session gone)"))
          (is (str/includes? (err {:who "a"} "@infra" "dead?") "infra: nobody on duty"))
          (fs/delete (fs/path *store* "duty" "infra__busy.edn"))))
      (testing "wait records the agent session's pid from CLAUDE_PID"
        (am {:who "d" :env {"CLAUDE_PID" "4000001"}} "wait" "--session" "d-1" "--timeout" "0")
        (let [r (first (filter #(= "d-1" (:session %)) (json/parse-string (out {} "who" "--json") true)))]
          (is (= 4000001 (:session-pid r)))
          (is (false? (:session-alive r)))))
      (is (str/includes? (err {:who "a"} "@user" "for you") "→ user: shown at every session start") "no notification in tests")
      (testing "wait titles a WezTerm tab: closed mailbox while waiting, open once mail is in"
        (let [bin (fs/create-dirs (fs/path *store* "stub-bin"))
              log (str (fs/path *store* "wezterm.log"))
              stub (fs/path bin "wezterm")
              path (str bin ":" (System/getenv "PATH"))
              on-path {"AGENT_MAIL_WEZTERM" "" "PATH" path}]
          (spit (str stub) (str "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"" log "\"\n"))
          (fs/set-posix-file-permissions stub "rwxr-xr-x")
          (am {:who "e" :env (assoc on-path "WEZTERM_PANE" "7")} "wait" "--session" "e-1" "--timeout" "0")
          (is (= ["cli --no-auto-start set-tab-title 📪 e"] (str/split-lines (slurp log)))
              "a timeout leaves the closed mailbox; wezterm from PATH")
          (is (= 7 (:pane (first (filter #(= "e-1" (:session %)) (json/parse-string (out {} "who" "--json") true)))))
              "the duty record names the pane")
          (out {:who "a"} "send" "--to" "e" "--subject" "Hi" "Mail for e.")
          (am {:who "e" :env {"AGENT_MAIL_WEZTERM" (str stub) "WEZTERM_PANE" "7"}} "wait" "--session" "e-1" "--timeout" "0")
          (is (= ["cli --no-auto-start set-tab-title 📪 e" "cli --no-auto-start set-tab-title 📪 e" "cli --no-auto-start set-tab-title 📬 e"]
                 (str/split-lines (slurp log))) "AGENT_MAIL_WEZTERM names it")
          (fs/delete log)
          (am {:who "e" :env (assoc on-path "WEZTERM_PANE" "")} "wait" "--session" "e-1" "--timeout" "0")
          (is (not (fs/exists? log)) "silent outside WezTerm"))))))

(defn fake-session
  "A unix socket at `path` standing in for a Claude Code session's inbox: the
   lines of each connection, parsed as JSON, become one post in (:posts it).
   (:close it) stops listening and leaves the socket file behind, as a session
   that died would."
  [path]
  (let [server (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                 (.bind (UnixDomainSocketAddress/of (str path))))
        posts (atom [])]
    (future
      (try (loop []
             (let [ch (.accept server)]
               (future (with-open [r (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) StandardCharsets/UTF_8))]
                         (swap! posts conj (mapv #(json/parse-string % true) (line-seq r)))))
               (recur)))
           (catch java.io.IOException _ nil)))
    {:path (str path) :posts posts :close #(.close server)}))

(defn eventually
  "Polls `f` every 50 ms for up to `ms`: its first truthy value, else nil."
  [ms f]
  (loop [n (quot ms 50)]
    (or (f) (when (pos? n) (Thread/sleep 50) (recur (dec n))))))

(deftest wait-posts-into-its-session
  ;; A unix socket's path must stay under ~104 bytes: with-store's directory is
  ;; too deep for that on macOS, so the socket gets a short one of its own.
  (with-store
    (let [sock-dir (fs/create-temp-dir {:prefix "am"})
          sess (fake-session (fs/path sock-dir "s.sock"))
          env {"CLAUDE_CODE_MESSAGING_SOCKET" (:path sess) "CLAUDE_CODE_MESSAGING_TOKEN" "tok-123"}
          posts #(deref (:posts sess))
          text (fn [post] (get-in (peek post) [:message :content]))
          duty-state (fn [s] (:state (first (filter #(= s (:session %)) (json/parse-string (out {} "who" "--json") true)))))
          ids-out (fn [r] (str/split-lines (str/trim (:out r))))]
      (try
        (testing "with nothing for it: quiet until the timeout, exit 3, idle"
          (let [r (am {:who "b" :env env} "wait" "--session" "b-0" "--timeout" "1" "--interval" "1")]
            (is (= 3 (:exit r)))
            (is (str/blank? (:out r)))
            (is (empty? (posts)))
            (is (= "idle" (duty-state "b-0")))
            (testing "the old skill's shape (--session, hourly timeout) is told, at start and at the exit it re-arms on"
              (let [lines (str/split-lines (str/trim (:err r)))]
                (is (= 2 (count lines)))
                (is (every? #(str/includes? % "wait --timeout 21600") lines))
                (is (every? #(str/includes? % "/agent-mail-listen") lines))))))
        (let [id (out {:who "a"} "send" "--to" "b" "--subject" "Vault please" "Add a vault.")
              n (:number (thread-json id))
              w (start {:who "b" :env env} "wait" "--session" "b-1" "--timeout" "60" "--interval" "1")]
          (try
            (testing "mail is posted into the session, after an auth line with its token, and the wait goes on"
              (is (eventually 5000 #(= 1 (count (posts)))))
              (let [[auth msg :as post] (first (posts))]
                (is (= 2 (count post)))
                (is (= {:type "auth" :token "tok-123"} auth))
                (is (= ["user" "user"] [(:type msg) (get-in msg [:message :role])]))
                (is (str/starts-with? (text post) (str "agent-mail: new mail for b: #" n " \"Vault please\" from a (" id ", message 1)")))
                (is (str/includes? (text post) (str "Read it with `agent-mail read " n "`"))))
              (is (= "working" (duty-state "b-1")) "working while the thread is the repo's turn")
              (is (p/alive? w)))
            (testing "not announced again while nothing changes"
              (Thread/sleep 2500)
              (is (= 1 (count (posts))))
              (is (p/alive? w)))
            (testing "a newer message in the thread is announced again"
              (out {:who "a"} "reply" id "And make it fast.")
              (is (eventually 5000 #(= 2 (count (posts)))))
              (is (str/includes? (text (second (posts))) (str "#" n " \"Vault please\" from a (" id ", message 2)"))))
            (testing "handed on, the thread leaves the session waiting, unannounced"
              (out {:who "b"} "reply" id "Done.")
              (is (eventually 5000 #(= "waiting" (duty-state "b-1"))))
              (is (= 2 (count (posts))))
              (is (p/alive? w)))
            (finally (p/destroy-tree w))))
        (testing "several threads at once: one post, one line each"
          (let [x (out {:who "a"} "send" "--to" "c" "--subject" "One" "first")
                y (out {:who "a"} "send" "--to" "c" "--subject" "Two" "second")
                before (count (posts))
                r (am {:who "c" :env env} "wait" "--session" "c-1" "--timeout" "1" "--interval" "1")]
            (is (= 3 (:exit r)) "the safety net still ends it")
            (is (str/blank? (:out r)))
            (is (eventually 2000 #(= (inc before) (count (posts)))))
            (let [lines (str/split-lines (text (last (posts))))]
              (is (= 3 (count lines)) "two threads, then where it comes from")
              (is (str/includes? (first lines) (str "\"One\" from a (" x ", message 1)")))
              (is (str/includes? (second lines) (str "\"Two\" from a (" y ", message 1)"))))))
        (testing "--once prints the ids and exits even with the socket set"
          (let [before (count (posts))
                r (am {:who "c" :env env} "wait" "--once" "--session" "c-2" "--timeout" "5")]
            (is (zero? (:exit r)))
            (is (= 2 (count (ids-out r))))
            (Thread/sleep 200)
            (is (= before (count (posts))))))
        (testing "nothing at the socket's path: the classic exit"
          (let [r (am {:who "c" :env (assoc env "CLAUDE_CODE_MESSAGING_SOCKET" (str (fs/path sock-dir "none.sock")))}
                      "wait" "--session" "c-3" "--timeout" "5")]
            (is (zero? (:exit r)))
            (is (= 2 (count (ids-out r))))))
        (testing "a socket nobody listens on any more: the post fails and the ids are printed instead"
          ((:close sess))
          (let [r (am {:who "c" :env env} "wait" "--session" "c-4" "--timeout" "5")]
            (is (zero? (:exit r)))
            (is (= 2 (count (ids-out r))))
            (is (str/includes? (:err r) "could not post"))
            (is (not (str/includes? (str (:out r) (:err r)) "tok-123")) "the token is never printed")
            (is (= "working" (duty-state "c-4"))))
          (testing "the skill's own shape earns no note about the old one"
            (let [r (am {:who "c" :env env} "wait" "--timeout" "21600")]
              (is (zero? (:exit r)))
              (is (= ["agent-mail: could not post to this session's socket; printing the thread ids instead"]
                     (str/split-lines (str/trim (:err r))))))))
        (finally ((:close sess)) (fs/delete-tree sock-dir))))))

(defn registry!
  "Claude Code's session registry entry for `pid` in config dir `cfg`, written
   in one move so a wait never reads half of it. `content` is a map, written as
   JSON, or a raw string."
  [cfg pid content]
  (let [f (fs/path cfg "sessions" (str pid ".json"))
        tmp (fs/path cfg "sessions" (str "." pid ".tmp"))]
    (fs/create-dirs (fs/parent f))
    (spit (str tmp) (if (string? content) content (json/generate-string content)))
    (fs/move tmp f {:replace-existing true :atomic-move true})))

(deftest wait-takes-the-live-session-name
  (with-store
    (with-dir [cfg]
      (let [duty #(fs/path *store* "duty" (str % ".edn"))
            records (fn [repo] (filter #(= repo (:repo %)) (json/parse-string (out {} "who" "--json") true)))
            record (comp first records)
            in-session (fn [pid] {"CLAUDE_PID" (str pid) "CLAUDE_CONFIG_DIR" cfg})]
        (testing "the registry's name wins over a stale --session, said once on stderr"
          (registry! cfg 4000002 {:pid 4000002 :name "live-1" :nameSource "derived"})
          (let [r (am {:who "f" :env (in-session 4000002)} "wait" "--session" "old-1" "--timeout" "0")]
            (is (= 3 (:exit r)))
            (is (= "live-1" (:session (record "f"))))
            (is (= 4000002 (:session-pid (record "f"))))
            (is (str/includes? (:err r) "\"live-1\""))
            (is (str/includes? (:err r) "\"old-1\""))
            (is (not (fs/exists? (duty "f__old-1"))))))
        (testing "without --session the registry names it too, silently"
          (let [r (am {:who "g" :env (in-session 4000002)} "wait" "--timeout" "0")]
            (is (= "live-1" (:session (record "g"))))
            (is (str/blank? (:err r)))))
        (testing "an entry that does not parse or has no name: --session, else the pid fallback"
          (registry! cfg 4000003 "{not json")
          (registry! cfg 4000004 {:pid 4000004})
          (is (= 3 (:exit (am {:who "h" :env (in-session 4000003)} "wait" "--session" "h-1" "--timeout" "0"))))
          (is (= "h-1" (:session (record "h"))))
          (is (= 3 (:exit (am {:who "i" :env (in-session 4000004)} "wait" "--timeout" "0"))))
          (is (str/starts-with? (:session (record "i")) "pid")))
        (testing "a rename while waiting moves the record to the new name and warns only once"
          (registry! cfg 4000005 {:pid 4000005 :name "live-2"})
          (let [w (start {:who "j" :env (in-session 4000005)} "wait" "--session" "old-2" "--timeout" "4" "--interval" "1")]
            (is (eventually 5000 #(fs/exists? (duty "j__live-2"))))
            (registry! cfg 4000005 {:pid 4000005 :name "live-3"})
            (is (eventually 5000 #(fs/exists? (duty "j__live-3"))))
            (is (not (fs/exists? (duty "j__live-2"))))
            (let [r @w]
              (is (= 3 (:exit r)))
              (is (= 1 (count (str/split-lines (str/trim (:err r)))))))
            (is (= [["live-3" "idle"]] (map (juxt :session :state) (records "j"))))))
        (testing "a delivery note names an older record's session by its live name"
          (let [me (.pid (java.lang.ProcessHandle/current))]
            (registry! cfg me {:pid me :name "fresh-9"})
            (spit (str (duty "survey__stale"))
                  (pr-str {:repo "survey" :session "stale" :state "waiting" :heartbeat (core/now-utc) :pid 1 :session-pid me}))
            (is (str/includes? (err {:who "a" :env {"CLAUDE_CONFIG_DIR" cfg}} "@survey" "still there?")
                               "survey: on duty (session fresh-9)"))
            (is (str/includes? (err {:who "a" :env {"CLAUDE_CONFIG_DIR" (str (fs/path cfg "none"))}} "@survey" "and now?")
                               "survey: on duty (session stale)")
                "no registry entry: the recorded name")))))))

(defn status-json [o] (json/parse-string (out o "status" "--json") true))

(deftest status-for-uis
  (with-store
    (let [;; waits on billing, whose only duty record is stale; last written on 25 Oct
          invoice (out {:who "website"} "send" "--to" "billing" "--subject" "Invoice" "--date" "2026-10-01" "Send the invoice.")
          _ (out {:who "billing"} "reply" invoice "--date" "2026-10-02" "Which month?")
          _ (out {:who "website"} "reply" invoice "--date" "2026-10-25" "October.")
          ;; waits on website, which has no duty record at all; since 10 Oct
          typo (out {:who "user"} "send" "--to" "website" "--subject" "Fix the typo" "--date" "2026-10-10" "On the front page.")
          ;; closed
          vault (out {:who "website"} "send" "--to" "infra" "--subject" "Vault" "Please add a vault.")
          _ (out {:who "infra"} "reply" vault "Done.")
          _ (out {:who "website"} "close" vault)
          ;; waits on the user
          cert (out {:who "infra"} "send" "--to" "user" "--subject" "Need your OK" "Buy a certificate?")
          ;; waits on infra, which has a fresh record, and on docs, busy past its heartbeat with a live session
          dns (out {:who "website"} "send" "--to" "infra" "--subject" "DNS record" "api.example.test, please.")
          docs (out {:who "website"} "send" "--to" "docs" "--subject" "Docs page" "A page for the API.")
          _ (out {:who "infra"} "read" dns)
          duty! (fn [nm rec] (spit (str (fs/path *store* "duty" (str nm ".edn"))) (pr-str rec)))
          _ (duty! "billing__old" {:repo "billing" :session "old" :state "waiting" :heartbeat "2020-01-01T00:00:00.000Z" :pid 1})
          _ (duty! "docs__busy" {:repo "docs" :session "busy" :state "working" :heartbeat "2021-01-01T00:00:00.000Z" :pid 1
                                 :session-pid (.pid (java.lang.ProcessHandle/current))})
          _ (duty! "infra__fresh" {:repo "infra" :session "fresh" :state "waiting" :heartbeat (core/now-utc) :pid 1})
          listed (into {} (map (juxt :id identity)) (json/parse-string (out {:env user-shell} "list" "--all" "--json") true))
          seen-files (fn [] (into {} (for [f (fs/list-dir (fs/path *store* "seen"))]
                                       [(str (fs/file-name f)) [(slurp (str f)) (str (fs/last-modified-time f))]])))
          seen-before (seen-files)
          head-before (store-git "rev-parse" "HEAD")
          commits-before (commits)
          ;; the menu bar app runs in the user's own shell, with no --as
          st (status-json {:env user-shell})
          who-json (json/parse-string (out {} "who" "--json") true)
          th (into {} (map (juxt :id identity)) (:threads st))]
      (testing "one object: the time, the caller, every thread, the duty records, the undelivered ids, on call and serve"
        (is (= #{:schema :now :me :threads :duty :undelivered :oncall :serve} (set (keys st))))
        (is (= "user" (:me st)))
        (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z" (:now st))))
      (testing "every thread, closed ones too, newest-updated first"
        (is (= #{invoice typo vault cert dns docs} (set (keys th))))
        (is (= [docs dns cert vault invoice typo] (map :id (:threads st))))
        (is (= (sort #(compare %2 %1) (map :updated (:threads st))) (map :updated (:threads st)))))
      (testing "each thread is what list --json gives, plus the derived fields"
        (is (every? #(= #{:id :number :subject :from :last-from :created :owner :status :count :updated :unread :flow
                          :unread-by :participants :waiting-s :listener} (set (keys %))) (:threads st)))
        (is (every? #(= (get listed (:id %)) (select-keys % (keys (get listed (:id %))))) (:threads st))
            "same values as list --all --json for the same caller"))
      (testing "unread counts for the caller and for every participant"
        (is (= {:infra 1 :website 0} (:unread-by (th vault))) "website closed after infra's reply")
        (is (= 3 (:unread (th vault))) "the user is a bystander there")
        (is (= {:infra 0 :user 1} (:unread-by (th cert))))
        (is (= 1 (:unread (th cert))))
        (is (= {:infra 0 :website 0} (:unread-by (th dns))) "infra read it")
        (is (= {:billing 1 :website 0} (:unread-by (th invoice))) "replying marks what came before as seen")
        (is (= {:user 0 :website 1} (:unread-by (th typo))))
        (is (= 0 (:unread (th typo))) "the user wrote it"))
      (testing "participants, waiting time and listener"
        (is (= ["billing" "website"] (:participants (th invoice))))
        (is (= ["infra" "website"] (:participants (th vault))))
        (is (= [nil nil] ((juxt :waiting-s :listener) (th vault))) "closed: neither")
        (is (= (core/age-s "2026-10-25T00:00:00.000Z" (:now st)) (:waiting-s (th invoice))) "since its last message")
        (is (= (core/age-s "2026-10-10T00:00:00.000Z" (:now st)) (:waiting-s (th typo))))
        (is (every? #(<= 0 (:waiting-s (th %)) 300) [cert dns docs]))
        (is (= {cert "user" dns "on-duty" docs "on-duty" invoice "none" typo "none"}
               (into {} (for [id [cert dns docs invoice typo]] [id (:listener (th id))])))
            "a stale waiting record is nobody; a working one whose session lives counts"))
      (testing "flow: who the thread passed through, then whose turn it is"
        (is (= {invoice ["website" "billing" "website" "billing"]
                typo ["user" "website"]
                vault ["website" "infra" "website"]
                cert ["infra" "user"]
                dns ["website" "infra"]
                docs ["website" "docs"]}
               (into {} (for [id [invoice typo vault cert dns docs]] [id (:flow (th id))])))
            "closing sends a last message to the other party, so the closed vault ends with infra"))
      (testing "undelivered: open, not the user's, nobody on duty; longest waiting first"
        (is (= [typo invoice] (:undelivered st)) "not thread order, not number order"))
      (testing "the duty records, as who --json gives them"
        (is (= (map #(dissoc % :age-s) who-json) (map #(dissoc % :age-s) (:duty st))))
        (is (= ["billing" "docs" "infra"] (map :repo (:duty st))))
        (is (= [[true false] [true true] [false true]] (map (juxt :stale :on-duty) (:duty st))))
        (is (every? #(contains? % :age-s) (:duty st))))
      (testing "the short table without --json"
        (let [h (out {:env user-shell} "status")]
          (is (str/includes? h "#4   infra→user"))
          (is (str/includes? h "on duty: docs, infra"))
          (is (str/includes? h "undelivered: #2 website, #1 billing"))))
      (testing "a participant's view counts its own unread"
        (let [mine (into {} (map (juxt :id identity)) (:threads (status-json {:who "infra"})))]
          (is (= [0 1 0 3] (map #(:unread (mine %)) [dns vault cert invoice])) "read, not yet read, written by infra, a bystander")))
      (testing "status is a read: no seen marker moves, nothing is committed or left uncommitted"
        (is (= seen-before (seen-files)))
        (is (= head-before (store-git "rev-parse" "HEAD")))
        (is (= commits-before (commits)))
        (is (str/blank? (store-git "status" "--porcelain")))))))

;; ================================================================ end to end: on call

(defn fake-wezterm!
  "A stand-in for the wezterm CLI in directory `d`: each call's arguments go
   to argv.log as one line, separated by U+001F; `list` and `list-clients`
   answer with list.json and clients.json, `spawn` with spawn.out; while a
   file named `fail` exists every call fails. Returns its path."
  [d]
  (script! d "wezterm"
           (str "d=\"$(dirname \"$0\")\"\n"
                "printf '%s\\037' \"$@\" >> \"$d/argv.log\"; printf '\\n' >> \"$d/argv.log\"\n"
                "[ -e \"$d/fail\" ] && { echo 'no running wezterm' >&2; exit 1; }\n"
                "for a in \"$@\"; do case \"$a\" in\n"
                "  list) cat \"$d/list.json\"; exit 0 ;;\n"
                "  list-clients) cat \"$d/clients.json\"; exit 0 ;;\n"
                "  spawn) cat \"$d/spawn.out\"; exit 0 ;;\n"
                "esac; done")))

(defn wez-calls
  "The fake wezterm's calls in `d` so far, each a vector of its arguments."
  [d]
  (let [f (fs/path d "argv.log")]
    (if (fs/exists? f) (mapv #(str/split % #"\u001f") (str/split-lines (slurp (str f)))) [])))

(defn panes!
  "The fake wezterm's `list`: these pane ids, all in window 3."
  [d & ids]
  (spit (str (fs/path d "list.json"))
        (json/generate-string (for [id ids] {:window_id 3 :tab_id id :pane_id id :is_active true :title "zsh"
                                             :tab_title "" :cwd "file:///" :tty_name "/dev/ttys009" :workspace "default"}))))

(defn serve-file [nm] (fs/path *store* "duty" "serve" nm))
(defn serve-state [] (edn/read-string (slurp (str (serve-file "state.edn")))))
(defn kills [calls] (filter #(some #{"kill-pane"} %) calls))

(deftest serve-opens-and-closes-listener-tabs
  (with-store
    (with-dir [d]
      (let [wez (fake-wezterm! d)
            cfg (str (fs/path d "claude"))
            infra (str (fs/create-dirs (fs/path d "infra")))
            me (.pid (java.lang.ProcessHandle/current))
            env {"AGENT_MAIL_WEZTERM" wez "CLAUDE_CONFIG_DIR" cfg "SHELL" "/bin/zsh"}
            serve (fn [& args] (apply am {:env env} "serve" "--once" args))
            calls #(wez-calls d)
            clear! #(fs/delete-if-exists (fs/path d "argv.log"))
            memory #(get-in (serve-state) [:repos "infra"])
            remember! (fn [m] (spit (str (serve-file "state.edn")) (pr-str (assoc-in (serve-state) [:repos "infra"] m))))
            text #(:text (first (json/parse-string (out {:env env} "oncall" "--json") true)))
            ago #(- (System/currentTimeMillis) (* % 60000))
            listener! (fn [status minutes] (registry! cfg me {:pid me :name "📫 infra" :status status :statusUpdatedAt (ago minutes)}))
            on-duty! (fn [pane] (spit (str (fs/path *store* "duty" (str "infra__" pane ".edn")))
                                      (pr-str {:repo "infra" :session "📫 infra" :state "waiting" :heartbeat (core/now-utc)
                                               :pid 1 :session-pid me :pane pane})))]
        (spit (str (fs/path d "clients.json"))
              (json/generate-string [{:pid 999 :focused_pane_id 9 :idle_time {:secs 80 :nanos 0} :workspace "default"}
                                     {:pid 999 :focused_pane_id 7 :idle_time {:secs 2 :nanos 5} :workspace "default"}]))
        (panes! d 7 9)
        (spit (str (fs/path d "spawn.out")) "42\n")
        (out {} "oncall" "add" "infra" infra)
        (testing "nothing waits: no tab, and wezterm is not asked"
          (is (zero? (:exit (serve))))
          (is (= [] (calls))))
        (let [id (out {:who "website"} "@infra" "Please rotate the certificate")]
          (testing "mail waits and nobody is on duty: a tab in the window last focused, then focus goes back"
            (let [r (serve)]
              (is (zero? (:exit r)) (:err r))
              (is (re-find #"(?m)^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} spawn infra: pane 42 in window 3, " (:err r))
                  "one timestamped log line per action"))
            (is (= [["cli" "--no-auto-start" "list-clients" "--format" "json"]
                    ["cli" "--no-auto-start" "list" "--format" "json"]
                    ["cli" "--no-auto-start" "spawn" "--window-id" "3" "--cwd" infra
                     "--" "/bin/zsh" "-lic" "exec claude --name '📫 infra' '/agent-mail-listen'"]
                    ["cli" "--no-auto-start" "activate-pane" "--pane-id" "7"]]
                   (calls)))
            (is (= {:pane 42 :gui-pid 999} (dissoc (memory) :spawned-at)))
            (is (= "tab opening (pane 42)" (text))))
          (testing "within the grace period: no second tab, only a look at the panes"
            (clear!)
            (panes! d 7 9 42)
            (serve)
            (is (= [["cli" "--no-auto-start" "list" "--format" "json"] ["cli" "--no-auto-start" "list-clients" "--format" "json"]]
                   (calls))))
          (testing "nobody on duty past the grace period: back off"
            (clear!)
            (remember! (assoc (memory) :spawned-at (- (System/currentTimeMillis) 121000)))
            (let [r (serve)]
              (is (re-find #"back off infra until \d\d:\d\d: no session came on duty" (:err r))))
            (is (not-any? #(some #{"spawn"} %) (calls)))
            (is (re-matches #"backing off until \d\d:\d\d: no session came on duty" (text)))
            (is (= 1 (:failures (memory)))))
          (testing "the listener comes on duty in that tab: the back-off is cleared"
            (on-duty! 42)
            (listener! "idle" 1)
            (is (str/includes? (:err (serve)) "on duty: infra"))
            (is (= {:pane 42 :gui-pid 999} (memory)))
            (is (= "tab open (pane 42), on duty, idle 1m" (text))))
          (testing "closed once nothing waits and its session has been idle an hour"
            (clear!)
            (listener! "idle" 120)
            (serve)
            (is (empty? (kills (calls))) "mail still waits")
            (out {:who "infra"} "close" id "Rotated.")
            (listener! "busy" 120)
            (serve)
            (is (empty? (kills (calls))) "busy")
            (listener! "idle" 59)
            (serve)
            (is (empty? (kills (calls))) "idle 59 minutes: its prompt cache still holds")
            (listener! "idle" 61)
            (serve "--idle-minutes" "90")
            (is (empty? (kills (calls))) "--idle-minutes sets the limit")
            (let [r (serve)]
              (is (str/includes? (:err r) "close infra: pane 42")))
            (is (= [["cli" "--no-auto-start" "kill-pane" "--pane-id" "42"]] (kills (calls))))
            (is (not (fs/exists? (fs/path *store* "duty" "infra__42.edn"))) "its duty record goes with it")
            (is (nil? (memory)) "and serve forgets the tab")
            (is (= "on call" (text)))))
        (testing "a session the user started is never touched"
          (clear!)
          (panes! d 7 50)
          (on-duty! 50)
          (listener! "idle" 300)
          (serve)
          (is (= [] (calls)) "no tab of serve's, nothing waits: wezterm is not even asked"))
        (testing "a tab the user closed is forgotten"
          (remember! {:pane 43 :gui-pid 999})
          (is (str/includes? (:err (serve)) "forget infra: pane 43 is gone"))
          (is (nil? (memory))))
        (testing "a failing wezterm backs off, and so does a directory that is gone"
          (fs/delete (fs/path *store* "duty" "infra__50.edn"))
          (out {:who "website"} "@infra" "One more")
          (spit (str (fs/path d "fail")) "")
          (is (re-find #"back off infra until \d\d:\d\d: wezterm spawn failed: no running wezterm" (:err (serve))))
          (is (= 1 (:failures (memory))))
          (fs/delete (fs/path d "fail"))
          (let [gone (str (fs/create-dirs (fs/path d "gone")))]
            (out {} "oncall" "add" "gone" gone)
            (out {:who "website"} "@gone" "Anyone?")
            (fs/delete gone)
            (is (re-find #"back off gone until \d\d:\d\d: its directory is gone" (:err (serve))))
            (is (= 1 (count (str/split-lines (str/trim (:err (serve))))))
                "both backing off: no action, so nothing logged but the start line")
            (is (str/ends-with? (:text (first (json/parse-string (out {:env env} "oncall" "--json") true)))
                                (str "its directory is gone (" gone ")")))))
        (is (str/blank? (store-git "status" "--porcelain")) "serve's files stay out of the store's history")))))

(deftest serve-runs-once-at-a-time
  (with-store
    (let [me (.pid (java.lang.ProcessHandle/current))
          state! (fn [heartbeat] (spit (str (serve-file "state.edn")) (pr-str {:pid me :heartbeat heartbeat :interval 5 :repos {}})))]
      (fs/create-dirs (serve-file ""))
      (testing "a live serve with a fresh heartbeat: a second one refuses at once"
        (spit (str (serve-file "pid")) (str me "\n"))
        (state! (core/now-utc))
        (let [r (am {} "serve" "--once")]
          (is (= 1 (:exit r)))
          (is (= (str "agent-mail: serve is already running (pid " me ")") (str/trim (:err r)))))
        (is (= (str me) (str/trim (slurp (str (serve-file "pid"))))) "its pid file is left alone")
        (is (= {:alive true :pid me} (:serve (status-json {})))))
      (testing "--standby (launchd's) waits for the live one to end, then takes over"
        (let [w (start {} "serve" "--standby" "--once" "--interval" "1")]
          (Thread/sleep 2500)
          (is (p/alive? w) "still standing by")
          (state! "2020-01-01T00:00:00.000Z")
          (let [r (deref w 15000 nil)]
            (is (= 0 (:exit r)) (:err r))
            (is (str/includes? (:err r) (str "serve: standing by while serve pid " me " runs")))
            (is (= 1 (count (re-seq #"standing by" (:err r)))) "said once")
            (is (str/includes? (:err r) "a round every 1s"))))
        (spit (str (serve-file "pid")) (str me "\n"))
        (state! (core/now-utc)))
      (testing "a stale heartbeat, or a dead pid: taken over, and given up when done"
        (state! "2020-01-01T00:00:00.000Z")
        (is (= {:alive false :pid me} (:serve (status-json {}))) "a live pid alone is not enough")
        (let [r (am {} "serve" "--once")]
          (is (zero? (:exit r)) (:err r))
          (is (str/includes? (:err r) "a round every 5s, idle tabs closed after 60m")))
        (is (not (fs/exists? (serve-file "pid"))))
        (spit (str (serve-file "pid")) "4000000\n")
        (state! (core/now-utc))
        (is (zero? (:exit (am {} "serve" "--once"))))
        (is (not (fs/exists? (serve-file "pid"))))
        (is (<= (core/age-s (:heartbeat (serve-state))) 5) "a heartbeat every round")
        (is (= {:alive false :pid nil} (:serve (status-json {}))))))))

(deftest oncall-registration
  (with-store
    (with-dir [d]
      (let [repo (str (fs/path d "repo-x")) wt (str (fs/path d "repo-x-wt"))
            real #(str (fs/real-path %))
            listed #(json/parse-string (out {} "oncall" "--json") true)]
        (git-in d "init" "-q" repo)
        (git-in repo "commit" "-q" "--allow-empty" "-m" "i")
        (git-in repo "worktree" "add" "-q" wt "-b" "wt")
        (testing "nothing on call yet"
          (is (= [] (listed)))
          (is (str/starts-with? (out {} "oncall") "nothing on call\nserve: not running")))
        (testing "add with no arguments: the repo it runs in, at its main worktree, whoever runs it"
          (let [r (am {:dir wt :env user-shell} "oncall" "add")]
            (is (zero? (:exit r)) (:err r))
            (is (str/starts-with? (:out r) "repo-x is on call ("))
            (is (str/includes? (:out r) "serve: not running")))
          (let [[e :as all] (listed)]
            (is (= 1 (count all)))
            (is (= {:repo "repo-x" :state "on-call" :pane nil :text "on call"} (dissoc e :dir)))
            (is (= (real repo) (real (:dir e))))))
        (testing "add NAME DIR"
          (is (zero? (:exit (am {} "oncall" "add" "docs" d))))
          (is (= [["docs" d] ["repo-x" "on call"]] (map (juxt :repo #(if (= "docs" (:repo %)) (:dir %) (:text %))) (listed)))))
        (testing "refusals"
          (is (= "agent-mail: oncall add takes NAME and DIR, or neither for this repo" (err {} "oncall" "add" "docs")))
          (is (= (str "agent-mail: no such directory: " (fs/path d "nope")) (err {} "oncall" "add" "x" (str (fs/path d "nope")))))
          (is (= "agent-mail: bad name: \"a b\"" (err {} "oncall" "add" "a b" d)))
          (is (= "agent-mail: not in a git repo; give NAME DIR" (err {:dir d} "oncall" "add")))
          (is (= "agent-mail: unknown oncall command list (add or remove)" (err {} "oncall" "list"))))
        (testing "the list: repo, directory, state; then whether serve runs"
          (let [lines (str/split-lines (out {} "oncall"))]
            (is (= 3 (count lines)))
            (is (re-matches #"docs\s+\S+\s+on call" (first lines)))
            (is (str/starts-with? (last lines) "serve: not running"))))
        (testing "who shows them; their files are no duty records"
          (is (= [] (json/parse-string (out {} "who" "--json"))))
          (is (re-find #"(?m)^docs\s+\(on call\)\s+on call$" (out {} "who"))))
        (testing "remove: off, and it stays off"
          (is (= "repo-x is no longer on call; wait won't put it back (`oncall add` does)"
                 (out {:dir repo :env user-shell} "oncall" "remove")))
          (is (= ["docs"] (map :repo (listed))))
          (is (= "web was not on call; wait won't put it on call either" (out {} "oncall" "remove" "web"))))
        (testing "add puts it back"
          (out {:dir repo} "oncall" "add")
          (is (= ["docs" "repo-x"] (map :repo (listed)))))))))

(deftest wait-puts-its-repo-on-call
  (with-store
    (with-dir [d]
      (let [sock-dir (fs/create-temp-dir {:prefix "am"})
            sess (fake-session (fs/path sock-dir "s.sock"))
            web (str (fs/path d "web"))
            src (str (fs/path web "src"))
            me (.pid (java.lang.ProcessHandle/current))
            env {"CLAUDE_CODE_MESSAGING_SOCKET" (:path sess) "CLAUDE_PID" (str me) "WEZTERM_PANE" "41"}
            real #(str (fs/real-path %))
            listed #(json/parse-string (out {} "oncall" "--json") true)
            record (fn [repo] (last (filter #(= repo (:repo %)) (json/parse-string (out {} "who" "--json") true))))]
        (git-in d "init" "-q" web)
        (fs/create-dirs src)
        (try
          (testing "a wait outside Claude Code registers nothing"
            (is (= 3 (:exit (am {:dir web :env (dissoc env "CLAUDE_CODE_MESSAGING_SOCKET")} "wait" "--timeout" "0"))))
            (is (= [] (listed))))
          (testing "in a Claude Code session: its repo goes on call at the main worktree; the record has its pane and directory"
            (is (= 3 (:exit (am {:dir src :env env} "wait" "--timeout" "0"))))
            (let [[e :as all] (listed)]
              (is (= ["web"] (map :repo all)))
              (is (= (real web) (real (:dir e)))))
            (let [r (record "web")]
              (is (= [41 me] ((juxt :pane :session-pid) r)))
              (is (= (real src) (real (:dir r))))))
          (testing "an --as name is not registered: a tab started here would not come on duty as it"
            (am {:who "other" :dir web :env env} "wait" "--timeout" "0")
            (is (= ["web"] (map :repo (listed)))))
          (testing "taken off call, it stays off"
            (out {:dir web} "oncall" "remove")
            (am {:dir web :env env} "wait" "--timeout" "0")
            (is (= [] (listed))))
          (testing "a wait whose session is gone ends at once, its record with it"
            (let [r (am {:who "ghost" :env (assoc env "CLAUDE_PID" "4000000")} "wait" "--timeout" "60")]
              (is (= 3 (:exit r))))
            (is (nil? (record "ghost"))))
          (finally ((:close sess)) (fs/delete-tree sock-dir)))))))

(deftest on-call-in-status-and-delivery-notes
  (with-store
    (with-dir [d]
      (let [me (.pid (java.lang.ProcessHandle/current))
            serve! (fn [pid repos]
                     (fs/create-dirs (serve-file ""))
                     (spit (str (serve-file "pid")) (str pid "\n"))
                     (spit (str (serve-file "state.edn")) (pr-str {:pid pid :heartbeat (core/now-utc) :interval 5 :repos repos})))
            note #(str/trim (err {:who "website"} "@infra" %))]
        (out {} "oncall" "add" "infra" d)
        (testing "serve alive, infra on call, nobody on duty: a tab is on its way"
          (serve! me {})
          (is (= "→ infra: on call; a listener tab opens for it shortly" (note "Rotate it")))
          (let [st (status-json {})]
            (is (= ["on-call"] (map :listener (:threads st))))
            (is (= [] (:undelivered st)) "not undelivered")
            (is (= [{:repo "infra" :dir d :state "on-call" :pane nil :text "on call"}] (:oncall st)))
            (is (= {:alive true :pid me} (:serve st))))
          (is (str/includes? (out {} "status") "\non call: infra\n")))
        (testing "backing off: undelivered again, and the note says why"
          (serve! me {"infra" {:failures 1 :until (+ (System/currentTimeMillis) 600000) :reason "no session came on duty"}})
          (is (re-matches #"→ infra: nobody on duty; on call, backing off until \d\d:\d\d: no session came on duty; it will see this at its next session start"
                          (note "Still there?")))
          (let [st (status-json {})]
            (is (= ["none" "none"] (map :listener (:threads st))))
            (is (= 2 (count (:undelivered st))))))
        (testing "serve not running: no tab will open, and the note says so"
          (serve! 4000000 {})
          (is (= "→ infra: nobody on duty; on call, but `agent-mail serve` isn't running; it will see this at its next session start"
                 (note "Anyone?")))
          (is (every? #(= "none" (:listener %)) (:threads (status-json {}))))
          (is (str/includes? (out {} "status") "on call: infra (serve isn't running)")))
        (testing "on duty beats on call"
          (serve! me {})
          (spit (str (fs/path *store* "duty" "infra__s1.edn"))
                (pr-str {:repo "infra" :session "s1" :state "waiting" :heartbeat (core/now-utc) :pid 1}))
          (is (str/starts-with? (note "Here?") "→ infra: on duty (session s1)"))
          (is (every? #(= "on-duty" (:listener %)) (:threads (status-json {}))))
          (is (= ["on-duty" "on duty (session s1)"] ((juxt :state :text) (first (:oncall (status-json {})))))))))))

(deftest caller-identity
  (with-store
    (with-dir [d]
      (let [repo (str (fs/path d "repo-x")) wt (str (fs/path d "repo-x-wt"))]
        (git-in d "init" "-q" repo)
        (git-in repo "commit" "-q" "--allow-empty" "-m" "i")
        (git-in repo "worktree" "add" "-q" wt "-b" "wt")
        (is (= "repo-x" (out {:dir repo} "whoami")))
        (is (= "repo-x" (out {:dir wt} "whoami")) "a worktree counts as its repo")
        (is (= "user" (out {:dir d :env user-shell} "whoami")) "the user's own shell is the user")
        (is (= "user" (out {:dir repo :env user-shell} "whoami")) "also inside a repo")
        (is (= "user" (out {:dir repo :env (assoc user-shell "CLAUDECODE" "1")} "whoami"))
            "an IDE's integrated terminal sets CLAUDECODE but not CLAUDE_CODE_CHILD_SESSION: still the user")
        (is (= "repo-x" (out {:dir repo :env (assoc user-shell "CLAUDE_CODE_CHILD_SESSION" "1")} "whoami"))
            "the child-session variable alone is enough")
        (testing "an agent's shell outside any repo is the directory, never the user"
          (let [r (am {:dir d :env agent-shell} "whoami")]
            (is (= (str (fs/file-name d)) (str/trim (:out r))))
            (is (str/includes? (:err r) "not in a git repo; acting as")))
          (is (str/blank? (:err (am {:dir d :env agent-shell} "hook"))) "the hook stays silent about it")
          (is (= "user" (out {:dir d :who "user" :env agent-shell} "whoami")) "AGENT_MAIL_AS=user is explicit"))
        (is (= "other" (out {:dir repo} "whoami" "--as" "other")))
        (is (= "env-name" (out {:who "env-name" :dir repo} "whoami")) "AGENT_MAIL_AS overrides the repo")
        (is (= 1 (:exit (am {:dir repo} "whoami" "--as" "a/b"))) "names must fit a file name")))))

(deftest hook-and-user-mail
  (with-store
    (is (str/blank? (out {:who "nobody"} "hook")) "silent when nothing waits")
    (let [id (out {:who "infra"} "send" "--to" "user" "--subject" "Need your OK" "Details.")]
      (is (= [id] (ids "user")))
      (is (str/blank? (out {:who "website"} "hook")) "other repos are not told about the user's mail")
      (let [h (out {:who "user"} "hook")]
        (is (str/includes? h "waiting on user"))
        (is (str/includes? h id))))
    (testing "the hook line names every waiting thread"
      (let [a (out {:who "website"} "send" "--to" "infra" "--subject" "Alpha" "x")
            b (out {:who "user"} "send" "--to" "infra" "--subject" "Beta" "y")
            h (out {:who "infra"} "hook")]
        (is (= (format "agent-mail: 2 threads waiting on infra: #2 %s \"Alpha\" from website; #3 %s \"Beta\" from user. Read with `agent-mail read <number>`." a b) h))
        (is (zero? (:exit (am {:who "infra"} "hook"))))
        (out {:who "infra"} "close" a)
        (is (str/starts-with? (out {:who "infra"} "hook") "agent-mail: 1 thread waiting on infra: #3"))))
    (is (zero? (:exit (am {:who "infra" :env {"AGENT_MAIL_DIR" "/nonexistent/agent-mail"}} "hook"))) "never fails")))

(deftest reply-from-the-editor
  (with-store
    (with-dir [d]
      (let [typing (script! d "editor.sh" "printf 'Approved, go ahead.\\n' >> \"$1\"")
            silent (script! d "viewer.sh" "exit 0")
            id (out {:who "infra"} "@user" "Need your OK")]
        (testing "an untouched draft sends nothing and the draft is kept"
          (let [r (am {:who "user" :env {"AGENT_MAIL_EDITOR" silent}} "reply" id "--edit")]
            (is (zero? (:exit r)))
            (is (str/includes? (:err r) "nothing sent"))
            (is (= [id] (ids "user")))
            (is (fs/exists? (fs/path *store* "drafts" (str id ".md"))))))
        (testing "a failing editor sends nothing"
          (let [broken (script! d "broken.sh" "exit 1")
                r (am {:who "user" :env {"AGENT_MAIL_EDITOR" broken}} "reply" id "--edit")]
            (is (= 1 (:exit r)))
            (is (str/includes? (:err r) "editor failed"))
            (is (= 1 (:count (thread-json id))))))
        (testing "an inline answer is sent with the quoted lines around it"
          (let [inline (script! d "inline.sh" "awk '{print} /^> Need your OK$/{print \"Inline answer.\"}' \"$1\" > \"$1.new\" && mv \"$1.new\" \"$1\"")]
            (is (zero? (:exit (am {:who "user" :env {"AGENT_MAIL_EDITOR" inline}} "reply" id "--edit"))))
            (let [m (last (:msgs (thread-json id)))]
              (is (str/starts-with? (:body m) "> […]\n> ## infra → user"))
              (is (str/ends-with? (:body m) "\n>\n> Need your OK\nInline answer."))
              (is (= "infra" (:to m))))
            (is (not (fs/exists? (fs/path *store* "drafts" (str id ".md")))) "the draft is gone once sent")
            (out {:who "infra"} "reply" id "noted")))
        (testing "text below the marker becomes a reply to the other party"
          (let [r (am {:who "user" :env {"AGENT_MAIL_EDITOR" typing}} "reply" id "--edit")]
            (is (zero? (:exit r)))
            (is (= id (str/trim (:out r))))
            (is (= [id] (ids "infra")))
            (let [t (out {} "read" id)]
              (is (str/includes? t "Approved, go ahead."))
              (is (not (str/includes? t "agent-mail: the thread above")) "the marker is not part of the message"))))
        (testing "--to redirects a composed reply"
          (out {:who "infra"} "reply" id "one more")
          (is (zero? (:exit (am {:who "user" :env {"AGENT_MAIL_EDITOR" typing}} "reply" id "--edit" "--to" "website"))))
          (is (= [id] (ids "website")))
          (is (= "website" (:to (last (:msgs (thread-json id)))))))
        (testing "an editor with arguments is split on spaces"
          (out {:who "website"} "reply" id "ok")
          (let [r (am {:who "user" :env {"AGENT_MAIL_EDITOR" (str "/bin/sh " typing)}} "reply" id "--edit")]
            (is (zero? (:exit r)))
            (is (= [id] (ids "website")))))))))

(deftest open-shows-the-plain-render
  (with-store
    (with-dir [d]
      (let [seen (str (fs/path d "seen"))
            viewer (script! d "viewer.sh" (str "cp \"$1\" " seen))
            id (out {:who "infra"} "send" "--to" "user" "--subject" "Need your OK" "Details here.")]
        (is (zero? (:exit (am {:who "user" :env {"AGENT_MAIL_VIEWER" viewer}} "open" id))))
        (let [v (slurp seen)]
          (is (str/starts-with? v "# Need your OK"))
          (is (not (str/includes? v "\n> ")) "unquoted")
          (is (not (str/includes? v "agent-mail: the thread above"))))
        (is (= [id] (ids "user")) "nothing was sent")
        (is (= (str (fs/path *store* "view" (str id ".md"))) (out {:who "user" :env {"AGENT_MAIL_VIEWER" viewer}} "open" id "--json")) "--json prints the rendered file")
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
        (is (= id (out {:who "b"} "reply" "1" "ok")))
        (is (= 1 (:exit (am {:who "nobody-else"} "read" "--as" "z"))) "no open threads of mine, several in all: still asks")
        (out {:who "b"} "reply" "2" "ok too")
        (out {:who "a"} "close" "1")
        (out {:who "a"} "close" "2")
        (is (str/includes? (:err (am {:who "b"} "read")) "no open threads"))))))

(deftest options-anywhere
  (with-store
    (let [id (out {:who "a"} "send" "--to" "b" "--subject" "S" "first" "--date" "2026-10-01")]
      (is (= id (out {:who "b"} "reply" id "--to" "a" "--date" "2026-10-02" "the reply")))
      (let [m (last (:msgs (thread-json id)))]
        (is (= "the reply" (:body m)))
        (is (= "a" (:to m)))
        (is (= "2026-10-02T00:00:00.000Z" (:date m))))
      (is (= id (out {:who "a"} "reply" id "--" "--not-an-option")))
      (is (str/includes? (out {} "read" id) "\n--not-an-option"))
      (is (pos? (:exit (am {:who "a"} "reply" id "--bogus" "x"))))
      (is (str/starts-with? (out {} "read" id "--help") "agent-mail: durable mail") "help anywhere")
      (is (str/starts-with? (out {}) "agent-mail: durable mail") "no command: help"))))

(deftest refusals
  (with-store
    (let [refused (fn [& args] (let [r (apply am args)] (and (= 1 (:exit r)) (str/starts-with? (:err r) "agent-mail: ") (str/trim (:err r)))))]
      (is (= "agent-mail: refusing to mail yourself ( a )" (refused {:who "a"} "@a" "to myself")))
      (is (= "agent-mail: empty body" (refused {:who "a"} "send" "--to" "b")))
      (is (= "agent-mail: send needs --to NAME (or `agent-mail @NAME text`)" (refused {:who "a"} "send" "text")))
      (is (str/starts-with? (refused {:who "a"} "send" "--to" "b" "--date" "yesterday" "x") "agent-mail: --date must be"))
      (is (= "agent-mail: unknown command frobnicate (try `agent-mail help`)" (refused {} "frobnicate")))
      (is (= "agent-mail: unknown option --verbose (try `agent-mail help`)" (refused {} "list" "--verbose")))
      (is (= "agent-mail: --to needs a value" (refused {} "send" "--to")))
      (is (= "agent-mail: --attach needs a file" (refused {} "send" "--attach")))
      (is (= "agent-mail: --timeout needs a whole number of seconds, got soon" (refused {:who "a"} "wait" "--timeout" "soon")))
      (is (= "agent-mail: bad recipient name: \"a b\"" (refused {:who "a"} "send" "--to" "a b" "x")))
      (is (= "agent-mail: no thread matches nothing-here" (refused {} "read" "nothing-here")))
      (let [id (out {:who "a"} "@b" "real")]
        (is (= "agent-mail: refusing to mail yourself ( b )" (refused {:who "b"} "reply" id "--to" "b" "x")))
        (is (= "agent-mail: empty body" (refused {:who "b" :in ""} "reply" id)))
        (is (= "agent-mail: empty body" (refused {:who "b" :in "  \n"} "reply" id)))
        (is (= (str "agent-mail: thread exists: " id) (refused {:who "a"} "send" "--to" "b" "--id" id "x")))
        (is (= 1 (:count (thread-json id))) "none of that added a message")
        (is (= 2 (commits)) "nor a commit")))))

(deftest the-store-is-a-git-repo
  (with-store
    (out {:who "a"} "list")
    (is (= {:schema 1 :me "a" :threads [] :duty [] :undelivered [] :oncall [] :serve {:alive false :pid nil}}
           (dissoc (status-json {:who "a"}) :now)) "status of an empty store")
    (is (not (fs/exists? (fs/path *store* ".git"))) "read-only commands do not create the store")
    (let [id (out {:who "a"} "send" "--to" "b" "--subject" "Git" "one")]
      (is (= 2 (commits)) "init plus the send")
      (is (= "📨 a → b: Git" (store-git "log" "-1" "--format=%s")))
      (is (str/blank? (store-git "status" "--porcelain")))
      (out {:who "b"} "reply" id "two")
      (is (= 3 (commits)))
      (is (= "↩️ b → a: Git" (store-git "log" "-1" "--format=%s")))
      (out {} "read" id)
      (out {:who "a"} "list")
      (out {:who "a"} "hook")
      (out {:who "a"} "status")
      (out {} "status" "--json")
      (is (= 3 (commits)) "reading commits nothing")
      (is (str/blank? (store-git "status" "--porcelain")) "nor leaves anything to commit")
      (out {:who "a"} "close" id)
      (is (= 4 (commits)))
      (is (= "✅ a → b: Git" (store-git "log" "-1" "--format=%s")))
      (is (str/blank? (store-git "status" "--porcelain")))
      (is (= "duty/\ndrafts/\nview/\nseen/\n" (slurp (str (fs/path *store* ".gitignore")))))
      (is (not (str/blank? (store-git "config" "user.email"))) "commits have an author even without a global identity")
      (is (= 3 (count (str/split-lines (store-git "ls-files" (str "threads/" id "/msgs")))))
          "every message file is tracked")
      (is (not (fs/exists? (fs/path *store* ".lock"))) "the lock is released"))))

(deftest concurrent-sends
  (with-store
    (out {:who "x"} "@y" "warm up the store")
    (let [procs (doall (for [i (range 6)]
                         (start {:who (str "r" i)} "send" "--to" "hub" "--subject" (if (even? i) "Same subject" (str "Subject " i)) (str "body " i))))
          rs (mapv deref procs)]
      (is (every? #(zero? (:exit %)) rs) (pr-str (map :err rs)))
      (let [ts (list-json "--all")]
        (is (= 7 (count ts)))
        (is (= (range 1 8) (sort (map :number ts))) "thread numbers are unique and dense")
        (is (= 7 (count (set (map :id ts)))) "ids are unique, clashes suffixed")
        (is (= 3 (count (filter #(= "Same subject" (:subject %)) ts))))
        (is (= 6 (count (ids "hub"))))))))

(let [{:keys [fail error]} (t/run-tests 'test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
