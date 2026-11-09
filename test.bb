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
;; Its session id too, or every message a test writes would record the real
;; session as its writer.
(def no-session {"CLAUDE_CODE_MESSAGING_SOCKET" "" "CLAUDE_CODE_MESSAGING_TOKEN" "" "CLAUDE_PID" ""
                 "CLAUDE_CODE_SESSION_ID" ""})
;; Nor may a test reach the real WezTerm (run from a WezTerm tab, the tests
;; inherit its pane), the real claude (serve and `who --here` run it) or the
;; real Claude Code registry and transcripts: all point at nothing next to
;; the store unless a test sets its own.
(defn sandbox-env []
  {"WEZTERM_PANE" ""
   "AGENT_MAIL_WEZTERM" (str (fs/path (fs/parent *store*) "no-wezterm"))
   "AGENT_MAIL_CLAUDE" (str (fs/path (fs/parent *store*) "no-claude"))
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
    (testing "each message keeps the session that wrote it"
      (is (= ["s-1" nil] (map :session (:msgs (core/thread-state meta "id" [(msg 0 "a" "b" :session "s-1") (msg 1 "b" "a")]))))))
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
    (testing "listener on-call: serve hands it to a session; on duty wins"
      (is (= "on-call" (core/listener open-th #{} #{"b"})))
      (is (= "on-duty" (core/listener open-th #{"b"} #{"b"})))
      (is (= "user" (core/listener to-user #{} #{"user"})))
      (is (= "none" (core/listener open-th #{} #{"c"}))))
    (testing "status: on call while serve is alive and the repo is not backing off"
      (let [st (fn [state alive] (core/status {:now now :me "x" :seen {} :duty [] :threads [open-th]
                                               :oncall [{:repo "b" :state state}] :serve {:alive alive :pid 1}}))
            heard (comp :listener first :threads)]
        (is (= ["on-call" []] ((juxt heard :undelivered) (st "on-call" true))))
        (is (= ["none" ["t1"]] ((juxt heard :undelivered) (st "backing-off" true))))
        (is (= "none" (heard (st "on-call" false))) "serve isn't running")
        (is (= [[{:repo "b" :state "on-call"}] {:alive true :pid 1}] ((juxt :oncall :serve) (st "on-call" true))))))
    (testing "status: the session serve handed a thread to, as it last saw it; nothing for the others"
      (let [memory {:threads {"t1" {:holder "deadbeef"}}
                    :sessions {"deadbeef" {:name "📫 b #1" :kind "background" :state "live" :ours true :agent "b"}}}
            st (core/status {:now now :me "x" :seen {} :duty [] :threads [open-th closed-th] :memory memory})]
        (is (= [{:id "deadbeef" :name "📫 b #1" :kind "background" :state "live"} nil] (map :session (:threads st))))
        (is (not (contains? (second (:threads st)) :session)) "absent, not null")))))

;; ================================================================ unit: serve

(def t0 "The tests' now: 2026-10-31 11:00 UTC, an hour after msg's messages."
  (* 1000 (core/epoch-s "2026-10-31T11:00:00.000Z")))

(defn th
  "A thread for serve's tests: subject \"About <id>\", number `n`, `msgs`."
  [id n msgs & {:as more}]
  (merge (core/thread-state {:subject (str "About " id) :from "web" :created "2026-10-31T10:00:00.000Z" :number (str n)} id msgs)
         more))

(defn live-s
  "A running session as serve-actions gets it, in /x/infra unless said."
  [id & {:as more}]
  (merge {:id id :session-id (str id "-full") :pid 1 :cwd "/x/infra" :name (str "s-" id) :kind "background"
          :status "busy" :status-updated-at 5 :socket (str "/sock/" id)}
         more))

(deftest serve-decisions
  (let [now t0
        hour 3600000
        ask (th "t1" 1 [(msg 0 "web" "infra")])
        ;; web asked, session aaaa1111 answered for infra, web wrote again: infra's turn
        talk (th "t1" 1 [(msg 0 "web" "infra") (msg 1 "infra" "web" :session "aaaa1111-full") (msg 2 "web" "infra")])
        base {:now now :oncall {"infra" {:dir "/x/infra"}} :threads [ask] :memory (core/serve-memory {}) :on-duty #{}
              :live [] :known [] :expiry {} :grace-ms 120000 :check-ms 30000}
        acts #(core/serve-actions (merge base %))
        mem (fn [& {:as m}] (merge (core/serve-memory {}) m))
        delivered (fn [file] {"t1" {:agent "infra" :number 1 :delivered file :holder "aaaa1111"}})
        kinds #(mapv first %)
        only (fn [ks as] (filterv (comp (set ks) first) as))
        sent #(only [:post :resume :start] %)
        ours (fn [& {:as more}] (merge {:ours true :agent "infra" :thread "t1" :number 1 :name "📫 infra #1"
                                        :session-id "bbbb2222-full" :kind "background" :state "live" :started-at (- now 1000)}
                                       more))]
    (testing "a new thread: a session of its own, named for it, in the agent's folder"
      (let [[[kind agent t text info] :as all] (acts {})]
        (is (= 1 (count all)))
        (is (= [:start "infra" "t1"] [kind agent t]))
        (is (= {:agent "infra" :number 1 :file "000.md" :dir "/x/infra" :name "📫 infra #1"} info))
        (is (str/starts-with? text "agent-mail serve started this session for thread #1 for infra from web"))
        (is (str/ends-with? text "\nNo other session works in this folder."))))
    (testing "nothing to deliver: delivered already, not on call, on duty, backing off, the user's, closed"
      (is (= [] (acts {:memory (mem :threads (delivered "000.md"))})))
      (is (= [] (acts {:oncall {}})))
      (is (= [] (acts {:on-duty #{"infra"}})) "a listener's duty record (an older wait) keeps its threads")
      (is (= [] (acts {:memory (mem :agents {"infra" {:failures 1 :until (inc now) :reason "x"}})})))
      (is (= [:start] (kinds (acts {:memory (mem :agents {"infra" {:failures 1 :until now :reason "x"}})}))) "a back-off that is over")
      (is (= [] (acts {:threads [(th "t2" 2 [(msg 0 "infra" "user")])] :oncall {"user" {:dir "/x"}}})) "never the user")
      (is (= [] (acts {:threads [(th "t2" 2 [(msg 0 "web" "infra") (msg 1 "infra" "web" :status "done")])]}))))
    (testing "the holder runs: the message is posted into it"
      (let [[[kind t sid text info]] (acts {:threads [talk] :live [(live-s "aaaa1111")]})]
        (is (= [:post "t1" "aaaa1111"] [kind t sid]))
        (is (str/starts-with? text "agent-mail: new message in #1 \"About t1\" from web (t1, message 3), posted by agent-mail serve because this session wrote in this thread (not another agent)."))
        (is (= {:agent "infra" :number 1 :file "002.md" :dir "/x/infra" :socket "/sock/aaaa1111" :name "s-aaaa1111"
                :kind "background" :session-id "aaaa1111-full" :idle false :status-updated-at 5}
               info)))
      (is (true? (:idle (peek (first (acts {:threads [talk] :live [(live-s "aaaa1111" :status "shell")]})))))
          "shell is idle: the post will be checked")
      (is (= [:start] (kinds (acts {:threads [talk] :live [(live-s "aaaa1111" :socket nil)]}))) "no inbox: a new session"))
    (testing "the holder has stopped: resumed while its cache is warm, else a new session"
      (let [known [(dissoc (live-s "aaaa1111") :socket :status)]
            [[kind t sid text info]] (acts {:threads [talk] :known known :expiry {"aaaa1111-full" (+ now 60000)}})]
        (is (= [:resume "t1" "aaaa1111-full"] [kind t sid]))
        (is (str/includes? text "because this session wrote in this thread"))
        (is (= (+ now 60000) (:warm-until info))))
      (is (= [:start] (kinds (acts {:threads [talk] :known [(live-s "aaaa1111")] :expiry {"aaaa1111-full" now}}))) "cold")
      (is (= [:start] (kinds (acts {:threads [talk] :known [(live-s "aaaa1111")]}))) "no transcript to tell")
      (is (= [:start] (kinds (acts {:threads [talk] :known [(live-s "aaaa1111" :kind "interactive")] :expiry {"aaaa1111-full" (+ now 60000)}})))
          "an interactive session is not resumed")
      (is (= [:start] (kinds (acts {:threads [talk] :expiry {"aaaa1111-full" (+ now 60000)}}))) "Claude Code no longer knows it"))
    (testing "the session serve started for the thread holds it until a newer message of the agent's"
      (let [m (mem :sessions {"bbbb2222" (ours)})
            [[kind _ sid text]] (sent (acts {:threads [talk] :memory m :live [(live-s "aaaa1111") (live-s "bbbb2222")]}))]
        (is (= [:post "bbbb2222"] [kind sid]) "started after aaaa1111 last wrote")
        (is (str/includes? text "because it started this session for this thread (not another agent)")))
      (is (= [:post "aaaa1111"] ((juxt first #(nth % 2)) (first (sent (acts {:threads [talk] :memory (mem :sessions {"bbbb2222" (ours :started-at 0)})
                                                                                 :live [(live-s "aaaa1111") (live-s "bbbb2222")]})))))
          "started before aaaa1111 wrote")
      (is (= [:post "bbbb2222"] ((juxt first #(nth % 2)) (first (sent (acts {:memory (mem :sessions {"bbbb2222" (ours)}) :live [(live-s "bbbb2222")]})))))
          "nobody wrote for infra yet"))
    (testing "a session on its way: no second one; past the grace period, back off and deliver again later"
      (let [m (mem :sessions {"bbbb2222" (ours :state "starting" :since (- now 119999))})]
        (is (= [] (acts {:threads [talk] :memory m})))
        (is (= [[:back-off "infra" (+ now 120000) "session \"📫 infra #1\" did not show up in `claude agents`"] [:lost "bbbb2222" "t1"]]
               (acts {:threads [talk] :memory (assoc-in m [:sessions "bbbb2222" :since] (- now 120000))}))
            "and nothing else for infra this round"))
      (let [m (mem :sessions {"bbbb2222" (ours :state "starting")} :agents {"infra" {:failures 2 :until 0 :reason "x"}}
                   :threads {"t1" {:delivered "000.md"}})]
        (is (= [[:observe "bbbb2222" {:state "live" :status "idle" :kind "background" :name "📫 infra #1" :session-id "bbbb2222-full"}]
                [:reset "infra"]]
               (acts {:memory m :live [(live-s "bbbb2222" :status "idle" :name "📫 infra #1")]}))
            "it showed up: the back-off is over")))
    (testing "a post into an idle session that stays idle 30 s was held: another session takes the thread"
      (let [check {:session "aaaa1111" :file "002.md" :at (- now 30000) :status-updated-at 5}
            m (mem :threads (assoc-in (delivered "002.md") ["t1" :check] check))
            [deaf start :as all] (acts {:threads [talk] :memory m :live [(live-s "aaaa1111" :status "idle")]})]
        (is (= 2 (count all)))
        (is (= [:deaf "t1" "aaaa1111"] deaf))
        (is (= [:start "infra" "t1"] (subvec start 0 3)) "the message goes out again, past aaaa1111")
        (is (= [[:checked "t1"]] (acts {:threads [talk] :memory m :live [(live-s "aaaa1111" :status "idle" :status-updated-at 6)]}))
            "it took a turn since")
        (is (= [[:checked "t1"]] (acts {:threads [talk] :memory m :live [(live-s "aaaa1111")]})) "busy")
        (is (= [[:checked "t1"]] (acts {:threads [talk] :memory m :live []})) "gone: nothing to tell")
        (is (= [] (acts {:threads [talk] :memory (assoc-in m [:threads "t1" :check :at] (- now 29999)) :live [(live-s "aaaa1111" :status "idle")]}))
            "too early to tell"))
      (is (= [:start] (kinds (acts {:threads [talk] :memory (mem :threads {"t1" {:deaf #{"aaaa1111"}}}) :live [(live-s "aaaa1111")]})))
          "a session that held a post is passed over for that thread"))
    (testing "serve stops a session it started once idle, holding no open thread, its cache expired"
      (let [m (mem :sessions {"cccc3333" (ours :thread "t9" :session-id "cccc3333-full")})
            idle (live-s "cccc3333" :status "idle" :status-updated-at (- now (* 2 hour)))
            stop [:stop "cccc3333" {:id "cccc3333" :name "s-cccc3333" :agent "infra" :expired (- now hour)}]
            stops #(only [:stop] (acts %))]
        (is (= [stop] (stops {:threads [] :memory m :live [idle]})) "no transcript: statusUpdatedAt + 1 h")
        (is (= [(assoc-in stop [2 :expired] (dec now))] (stops {:threads [] :memory m :live [idle] :expiry {"cccc3333-full" (dec now)}})))
        (is (= [] (stops {:threads [] :memory m :live [idle] :expiry {"cccc3333-full" (inc now)}})) "the transcript says warm")
        (is (= [] (stops {:threads [] :memory m :live [(assoc idle :status "busy")]})))
        (is (= [] (stops {:threads [] :memory m :live [(assoc idle :status "waiting")]})) "waiting on the user")
        (is (= [stop] (stops {:threads [] :memory m :live [(assoc idle :status "shell")]})))
        (is (= [stop] (stops {:threads [] :memory m :live [(assoc idle :status nil :run-state "done")]})))
        (is (= [] (stops {:threads [] :memory (assoc-in m [:sessions "cccc3333" :ours] false) :live [idle]})) "never one serve didn't start")
        (is (= [] (stops {:threads [(th "t9" 9 [(msg 0 "web" "infra")])] :memory (assoc-in m [:threads "t9"] {:delivered "000.md"})
                          :live [idle]}))
            "an open thread waits on it")
        (is (= [] (stops {:threads [(th "t9" 9 [(msg 0 "web" "infra")])]
                          :memory (assoc-in m [:threads "t9"] {:delivered "000.md" :check {:session "cccc3333" :at now}}) :live [idle]}))
            "a post into it is being checked")
        (is (= [stop] (stops {:threads [(th "t9" 9 [(msg 0 "web" "infra") (msg 1 "infra" "user")])] :memory m :live [idle]}))
            "its thread waits on the user, not on it")))
    (testing "at most 3 starts a round, lowest thread numbers first; posts and resumes are not capped"
      (let [news (for [n [5 2 4 1 3]] (th (str "n" n) n [(msg 0 "web" "infra")]))
            all (acts {:threads (cons talk news) :live [(live-s "aaaa1111")]})]
        (is (= [[:post 1] [:start 1] [:start 2] [:start 3]] (map (fn [[kind :as a]] [kind (:number (peek a))]) all))
            "t1's post, then n1 to n3; n4 and n5 wait")
        (is (= [:start] (kinds (acts {:threads news :max-starts 1}))))
        (is (= 3 (count (acts {:threads news :memory (mem :threads {"n1" {:delivered "000.md"}})}))) "n2 to n4 now")))
    (testing "a thread closed with news for its owner goes into the holder only while that runs, unchecked"
      (let [closed (th "t1" 1 [(msg 0 "infra" "web" :session "aaaa1111-full") (msg 1 "web" "infra") (msg 2 "web" "infra" :status "done")])
            [[kind t sid text info] :as all] (acts {:threads [closed] :live [(live-s "aaaa1111" :status "idle")]})]
        (is (= 1 (count all)))
        (is (= [:post "t1" "aaaa1111"] [kind t sid]))
        (is (str/starts-with? text "agent-mail: closed, with news: #1 \"About t1\" closed by web (t1, message 3), posted by agent-mail serve"))
        (is (false? (:idle info)) "nothing to check: no other session would take it")
        (is (= [[:pass "t1" {:agent "infra" :number 1 :file "002.md" :dir "/x/infra"}]]
               (acts {:threads [closed] :known [(live-s "aaaa1111")] :expiry {"aaaa1111-full" (+ now 60000)}}))
            "its holder stopped: no resume, no start, and not tried again")
        (is (= [:pass] (kinds (acts {:threads [(th "t1" 1 [(msg 0 "web" "infra") (msg 1 "web" "infra" :status "done")])]}))) "nobody holds it")
        (is (= [] (acts {:threads [closed] :memory (mem :threads {"t1" {:delivered "002.md"}}) :live [(live-s "aaaa1111")]}))
            "posted once, and kept in mind while it is news")
        (is (= [[:forget "t1"]] (acts {:threads [closed] :memory (mem :threads {"t1" {:delivered "002.md"}}) :seen {"infra" {"t1" "002.md"}}}))
            "read: no news any more, forgotten")
        (is (= [] (acts {:threads [closed] :seen {"infra" {"t1" "002.md"}} :live [(live-s "aaaa1111")]})) "read before serve got to it")
        (is (= [[:forget "t1"]] (acts {:threads [(th "t1" 1 [(msg 0 "web" "infra") (msg 1 "infra" "web" :status "done")])]
                                       :memory (mem :threads {"t1" {:delivered "000.md"}})}))
            "closed by infra itself: news for web, which is not on call")
        (is (= [] (acts {:threads [closed] :memory (mem :agents {"infra" {:until (inc now)}}) :live [(live-s "aaaa1111")]})) "backing off")
        (is (= [[:checked "t1"]] (acts {:threads [closed] :memory (mem :threads {"t1" {:delivered "002.md" :check {:session "aaaa1111" :at 0}}})
                                        :live [(live-s "aaaa1111" :status "idle")]}))
            "a check left from before it closed is dropped")
        (is (= {:live true :known true} (core/serve-wants (merge base {:threads [closed]}))))))
    (testing "bookkeeping: closed threads forgotten, sessions seen ending, dropped once their thread is closed"
      (is (= [[:forget "t1"]] (acts {:threads [] :memory (mem :threads (delivered "000.md"))})))
      (let [m (mem :sessions {"bbbb2222" (ours)})]
        (is (= [[:observe "bbbb2222" {:state "stopped"}]] (acts {:threads [] :memory m :live [] :known nil})))
        (is (= [[:observe "bbbb2222" {:state "gone"}]] (acts {:threads [] :memory m :live [] :known []}))
            "not even `--all` lists it")
        (is (= [[:drop "bbbb2222"]] (acts {:threads [] :memory (assoc-in m [:sessions "bbbb2222" :state] "stopped") :live nil})))
        (is (= [] (acts {:threads [ask] :memory (-> m (assoc-in [:sessions "bbbb2222" :state] "stopped") (assoc :threads (delivered "000.md")))
                         :live nil}))
            "its thread is open: kept")))
    (testing "Claude Code can't list its sessions: back off with why"
      (is (= [[:back-off "infra" (+ now 120000) "cannot list Claude Code sessions: `claude agents` failed (exit 127)"]]
             (acts {:live nil :agents-error "`claude agents` failed (exit 127)"}))))
    (testing "serve-wants: ask Claude Code only with something to decide"
      (let [w #(core/serve-wants (merge base %))]
        (is (= {:live true :known true} (w {})) "something to deliver")
        (is (= {:live false :known false} (w {:threads []})))
        (is (= {:live true :known false} (w {:threads [] :memory (mem :sessions {"bbbb2222" (ours)})})) "a session of its own runs")
        (is (= {:live false :known false} (w {:threads [] :memory (mem :sessions {"bbbb2222" (ours :state "stopped")})})))
        (is (= {:live false :known false} (w {:threads [] :memory (mem :sessions {"bbbb2222" (ours :ours false)})}))
            "another's: looked at only in rounds that ask anyway")
        (is (= {:live true :known false} (w {:threads [] :memory (mem :sessions {"bbbb2222" (ours :ours false :state "starting")})})))
        (is (= {:live true :known true} (w {:threads [] :memory (mem :threads {"t1" {:check {:at (- now 30000)}}})})) "a post to check")))
    (testing "back-off: 2 min, doubling, at most 1 h"
      (is (= [120000 240000 480000 960000 1920000 3600000 3600000] (map core/back-off-ms [1 2 3 4 5 6 40])))
      (is (= [:back-off "infra" (+ now 240000) "why"] (core/back-off "infra" {:failures 1} now "why"))))
    (testing "remember: serve's memory after each action"
      (let [r #(core/remember %1 %2 now)
            info {:agent "infra" :number 1 :file "002.md" :name "s-aaaa1111" :kind "interactive" :session-id "aaaa1111-full"}]
        (is (= {:threads {"t1" {:agent "infra" :number 1 :delivered "002.md" :holder "aaaa1111"}}
                :sessions {"aaaa1111" {:agent "infra" :thread "t1" :number 1 :ours false :session-id "aaaa1111-full"
                                       :name "s-aaaa1111" :kind "interactive" :state "live"}}
                :agents {}}
               (r (mem) [:posted "t1" "aaaa1111" info])))
        (is (= {:session "aaaa1111" :file "002.md" :at now :status-updated-at 5}
               (get-in (r (mem) [:posted "t1" "aaaa1111" (assoc info :idle true :status-updated-at 5)]) [:threads "t1" :check]))
            "posted into an idle session: checked later")
        (is (= ["t9" true] ((juxt :thread :ours) (get-in (r (mem :sessions {"bbbb2222" (ours :thread "t9")}) [:posted "t1" "bbbb2222" info])
                                                         [:sessions "bbbb2222"])))
            "a session serve started for another thread stays that thread's")
        (is (= #{"aaaa1111"} (get-in (r (mem) [:post-failed "t1" "aaaa1111-full"]) [:threads "t1" :deaf])))
        (is (= {"aaaa1111" {:agent "infra" :thread "t1" :number 1 :ours false :session-id "aaaa1111-full" :name "s-aaaa1111"
                            :kind "interactive" :state "starting" :since now}}
               (:sessions (r (mem) [:resumed "t1" "aaaa1111-full" info]))))
        (let [m (r (mem) [:started "infra" "t1" "deadbeef" nil {:agent "infra" :number 1 :file "000.md" :name "📫 infra #1"}])]
          (is (= {"deadbeef" {:agent "infra" :thread "t1" :number 1 :name "📫 infra #1" :kind "background" :ours true
                              :state "starting" :since now :started-at now}}
                 (:sessions m)))
          (is (= {"t1" {:agent "infra" :number 1 :delivered "000.md" :holder "deadbeef"}} (:threads m))))
        (is (= {:state "stopped" :since now :name "x"} (get-in (r (mem :sessions {"k" {:state "live" :name "x"}}) [:stopped "k"]) [:sessions "k"])))
        (is (= {:state "live" :status "idle" :since now} (get-in (r (mem :sessions {"k" {:state "starting" :since 1}}) [:observe "k" {:state "live" :status "idle"}]) [:sessions "k"])))
        (is (= {:state "live" :status "busy" :since 1} (get-in (r (mem :sessions {"k" {:state "live" :since 1}}) [:observe "k" {:state "live" :status "busy"}]) [:sessions "k"]))
            ":since is when the state last changed")
        (is (= {"infra" {:failures 2 :until 7 :reason "why"}} (:agents (r (mem :agents {"infra" {:failures 1}}) [:back-off "infra" 7 "why"]))))
        (is (= {} (:agents (r (mem :agents {"infra" {:failures 1}}) [:reset "infra"]))))
        (let [m (mem :threads {"t1" {:delivered "002.md" :check {:session "aaaa1111"}}} :sessions {"bbbb2222" (ours :state "starting")})]
          (is (= {"t1" {:deaf #{"aaaa1111"}}} (:threads (r m [:deaf "t1" "aaaa1111-full"])))
              "not delivered any more: if no start follows this round (the cap), a later one sends it")
          (is (= {"t1" {:delivered "003.md" :agent "infra" :number 1 :check {:session "aaaa1111"}}}
                 (:threads (r m [:pass "t1" {:agent "infra" :number 1 :file "003.md"}]))))
          (is (= {"t1" {:delivered "002.md"}} (:threads (r m [:checked "t1"]))))
          (is (= [{} "gone"] ((juxt #(get-in % [:threads "t1"]) #(get-in % [:sessions "bbbb2222" :state])) (r m [:lost "bbbb2222" "t1"]))))
          (is (= {} (:threads (r m [:forget "t1"]))))
          (is (= {} (:sessions (r m [:drop "bbbb2222"]))))
          (is (= {} (:threads (r (mem) [:lost "bbbb2222" "t1"]))) "bookkeeping never brings a forgotten thread back"))
        (is (= (mem) (r (mem) [:start "infra" "t1" "text" {}])) "a start ends as :started or :back-off")))
    (testing "serve-memory: an older serve's state (its tabs under :repos) is nothing remembered"
      (is (= {:threads {} :sessions {} :agents {}} (core/serve-memory {:pid 1 :repos {"infra" {:pane 42}}})))
      (is (= {:threads {} :sessions {} :agents {}} (core/serve-memory {:threads [1] :sessions "x"}))))
    (testing "oncall-view and thread-session: each agent's state and serve's sessions, in words"
      (let [v (fn [m & {:as more}] (first (core/oncall-view (merge {:now now :oncall {"infra" {:dir "/x/infra"}} :memory m :duty []} more))))
            later (+ now 60000)
            m (mem :threads {"t1" {:holder "deadbeef"}}
                   :sessions {"deadbeef" (ours :name "📫 infra #1" :status "busy")
                              "aaaa1111" {:agent "infra" :thread "t1" :number 1 :name "infra-aa" :kind "interactive" :state "stopped" :ours false}})]
        (is (= {:repo "infra" :dir "/x/infra" :state "on-call" :pane nil :text "on call"} (v (mem))))
        (is (= "on call; serve's sessions: \"infra-aa\" (interactive, stopped), \"📫 infra #1\" (background, busy, thread #1)"
               (:text (v m)))
            "by thread number, then short id")
        (let [b (v (mem :agents {"infra" {:failures 1 :until later :reason "`claude --bg` failed (exit 1): Workspace not trusted."}}))]
          (is (= "backing-off" (:state b)))
          (is (= (str "backing off until " (core/clock later) ": `claude --bg` failed (exit 1): Workspace not trusted.") (:text b)))
          (is (= [(core/fmt-utc (java.time.Instant/ofEpochMilli later)) "`claude --bg` failed (exit 1): Workspace not trusted."]
                 ((juxt :until :reason) b))))
        (is (= "on-call" (:state (v (mem :agents {"infra" {:failures 1 :until now :reason "x"}})))) "a back-off that is over")
        (is (= ["on-duty" "on duty (session hand-1)" ["hand-1"]]
               ((juxt :state :text :sessions) (v (mem) :duty [{:repo "infra" :session "hand-1" :on-duty true}]))))
        (is (empty? (core/oncall-view {:now now :oncall {} :memory m :duty []})) "registered agents only")
        (is (= {:id "deadbeef" :name "📫 infra #1" :kind "background" :state "live"} (core/thread-session m "t1")))
        (is (nil? (core/thread-session m "t2")))
        (is (= "#1 → deadbeef \"📫 infra #1\" (background, busy)" (core/session-line (last (core/serve-sessions m)))))))))

(deftest serve-texts
  (let [t1 (th "t1" 1 [(msg 0 "web" "infra")])
        t1' (th "t1" 1 [(msg 0 "web" "infra") (msg 1 "infra" "web") (msg 2 "web" "infra")])]
    (testing "the first prompt of a session serve starts"
      (is (= (str "agent-mail serve started this session for thread #1 for infra from web, \"About t1\" (t1, message 1). "
                  "Read it with `agent-mail read 1` and act on it under this folder's own rules (CLAUDE.md). "
                  "Answer with `agent-mail reply 1`; close it with `agent-mail close 1` when the work is done. "
                  "Later messages in this thread are posted into this session. "
                  "One thread per session: don't take on other threads.\n"
                  "No other session works in this folder.")
             (core/first-prompt "infra" t1 "No other session works in this folder.")))
      (is (str/includes? (core/first-prompt "infra" (assoc t1 :last-from "user") "")
                         "from user (the user themself, writing by mail; not another agent or session), \"About t1\"")
          "mail from the user says so")
      (is (str/includes? (core/first-prompt "infra" (assoc t1 :number nil) "") "thread #t1 for infra")))
    (testing "a message posted into the session holding the thread"
      (is (= (str "agent-mail: new message in #1 \"About t1\" from web (t1, message 3), posted by agent-mail serve because "
                  "this session wrote in this thread (not another agent). Read it with `agent-mail read 1` and answer once the current step is done.\n"
                  "No other session works in this folder.")
             (core/posted-text "infra" t1' true "No other session works in this folder.")))
      (is (str/includes? (core/posted-text "infra" t1' false "") "because it started this session for this thread (not another agent)"))
      (is (str/includes? (core/posted-text "infra" (assoc t1' :last-from "user") true "")
                         "from user (the user themself, writing by mail; not another agent or session) (t1, message 3)"))
      (is (not= (core/posted-text "infra" t1 true "") (core/posted-text "infra" t1' true "")) "each post names its message")
      (is (= (str "agent-mail: closed, with news: #1 \"About t1\" closed by web (t1, message 3), posted by agent-mail serve because "
                  "this session wrote in this thread (not another agent). Read it with `agent-mail read 1`; no answer is expected. "
                  "To take it further, start a new thread or `agent-mail forward` it.\n"
                  "No other session works in this folder.")
             (core/posted-text "infra" (assoc t1' :status "done") true "No other session works in this folder."))
          "a closed thread says so: no answer expected"))
    (testing "the also-line: other sessions in the folder or below, by the name SendMessage takes, then a word on coordinating"
      (let [mine (live-s "aaaa1111" :name "📫 infra #70")
            peer (live-s "ab12cd34" :name "infra-ab12" :kind "interactive" :status "shell" :cwd "/x/infra/.claude/worktrees/w")
            elsewhere (live-s "ee55ee55" :cwd "/x/infra2")
            m {:sessions {"aaaa1111" {:ours true :number 70} "ab12cd34" {:ours false :number 3}}}]
        (is (= (str "Also working in this folder: \"📫 infra #70\" (background, busy, thread #70), \"infra-ab12\" (interactive, idle). "
                    "If your work affects theirs (the same files, a push to the branch they work on), tell them with SendMessage and let them decide.")
               (core/also-line "/x/infra" [mine peer elsewhere] m nil))
            "a thread number only for a session serve started for it; shell is idle; a sibling folder is not inside")
        (is (= (str "Also working in this folder: \"infra-ab12\" (interactive, idle). " core/coordinate-sentence)
               (core/also-line "/x/infra/" [mine peer] m "aaaa1111-full"))
            "the session told is left out")
        (is (= "No other session works in this folder." (core/also-line "/x/infra" [mine elsewhere] m "aaaa1111"))
            "nobody else: no word on coordinating")
        (is (= "No other session works in this folder." (core/also-line "/x/infra" [] {} nil)))
        (is (str/includes? (core/also-line "/x/infra" [(assoc peer :name nil)] {} nil) "\"ab12cd34\" (interactive, idle)")
            "no name: the short id")))
    (testing "cache expiry from a transcript's tail: last assistant time plus the latest cache write's lifetime"
      (let [ts "2026-10-31T10:00:00.000Z"
            at (* 1000 (core/epoch-s ts))
            a (fn [ts & {:as cc}] {:type "assistant" :timestamp ts :message {:usage {:cache_creation cc}}})]
        (is (= (+ at 3600000) (core/cache-expiry [{:type "user"} (a ts :ephemeral_1h_input_tokens 900 :ephemeral_5m_input_tokens 0)])))
        (is (= (+ at 300000) (core/cache-expiry [(a ts :ephemeral_1h_input_tokens 0 :ephemeral_5m_input_tokens 900)])))
        (is (= (+ at 3600000) (core/cache-expiry [(a ts)])) "nothing says: an hour")
        (is (= (+ at 1000 300000) (core/cache-expiry [(a ts :ephemeral_1h_input_tokens 9) (a "2026-10-31T10:00:01.000Z" :ephemeral_5m_input_tokens 9)]))
            "the latest write decides")
        (is (= (+ at 1000 3600000) (core/cache-expiry [(a ts :ephemeral_1h_input_tokens 9) (a "2026-10-31T10:00:01.000Z" :ephemeral_5m_input_tokens 0)]))
            "an entry that wrote nothing: the one before")
        (is (nil? (core/cache-expiry [{:type "user" :timestamp ts}])) "no assistant entry")
        (is (nil? (core/cache-expiry [])))))
    (testing "what claude prints"
      (is (= "52f6bcf5" (core/backgrounded-id "Starting background service…\nbackgrounded · 52f6bcf5 · 📫 infra #7\n")))
      (is (= "deadbeef" (core/backgrounded-id "backgrounded · deadbeef · x")))
      (is (nil? (core/backgrounded-id "Workspace not trusted.")))
      (is (= "`claude --bg` failed (exit 1): Workspace not trusted. Run `claude` in /x once…"
             (core/claude-failure "`claude --bg`" {:exit 1 :out "Starting background service…\nWorkspace not trusted. Run `claude` in /x once…\n" :err ""})))
      (is (= "`claude stop` failed (exit 2)" (core/claude-failure "`claude stop`" {:exit 2 :out "" :err " \n"})))
      (let [item {:id "52f6bcf5" :sessionId "52f6bcf5-aaaa" :pid 9 :cwd "/x" :name "n" :kind "background" :status "idle" :state "done"}
            want [{:id "52f6bcf5" :session-id "52f6bcf5-aaaa" :pid 9 :cwd "/x" :name "n" :kind "background" :status "idle" :run-state "done"}]]
        (is (= want (core/agents-items [item])))
        (is (= want (core/agents-items {:agents [item]})) "an object holding the list")
        (is (= "52f6bcf5" (:id (first (core/agents-items [(dissoc item :id)])))) "the short id from the sessionId")
        (is (= [] (core/agents-items "nonsense")))))))
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
  (is (= "--interval needs a whole number of seconds, got soon"
         (try (core/parse-argv ["serve" "--interval" "soon"]) nil (catch clojure.lang.ExceptionInfo e (ex-message e)))))
  (is (true? (:here (:opts (core/parse-argv ["who" "--here"])))))
  (is (= "unknown option --idle-minutes (try `agent-mail help`)"
         (try (core/parse-argv ["serve" "--idle-minutes" "90"]) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))
      "gone with the tabs it closed")
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

(deftest wait-in-a-session-hands-over-to-serve
  ;; A unix socket's path must stay under ~104 bytes: with-store's directory is
  ;; too deep for that on macOS, so the socket gets a short one of its own.
  (with-store
    (with-dir [d]
      (let [sock-dir (fs/create-temp-dir {:prefix "am"})
            sess (fake-session (fs/path sock-dir "s.sock"))
            web (str (fs/path d "web"))
            src (str (fs/path web "src"))
            me (.pid (java.lang.ProcessHandle/current))
            env {"CLAUDE_CODE_MESSAGING_SOCKET" (:path sess) "CLAUDE_PID" (str me)}
            real #(str (fs/real-path %))
            listed #(json/parse-string (out {} "oncall" "--json") true)
            records #(json/parse-string (out {} "who" "--json") true)
            duty! (fn [nm rec] (spit (str (fs/path *store* "duty" (str nm ".edn")))
                                     (pr-str (merge {:state "waiting" :heartbeat (core/now-utc) :pid 1} rec))))]
        (git-in d "init" "-q" web)
        (fs/create-dirs src)
        (try
          (testing "outside Claude Code a wait is classic and registers nothing"
            (is (= 3 (:exit (am {:dir web :env (dissoc env "CLAUDE_CODE_MESSAGING_SOCKET")} "wait" "--timeout" "0"))))
            (is (= [] (listed))))
          (let [id (out {:who "a"} "@web" "Mail for web")]
            (testing "in a Claude Code session: on call, its older waits' records gone, a notice, exit 0, nothing posted or printed"
              (duty! "web__old" {:repo "web" :session "old" :session-pid me})
              (duty! "web__other" {:repo "web" :session "other" :session-pid 4000000})
              (let [r (am {:dir src :env env} "wait" "--timeout" "21600")
                    notice (str/trim (:err r))]
                (is (zero? (:exit r)))
                (is (str/blank? (:out r)) "no thread ids: serve delivers them")
                (is (= 1 (count (str/split-lines notice))))
                (is (str/starts-with? notice "agent-mail: wait no longer runs in a Claude Code session. agent-mail serve now starts a background session for each thread"))
                (is (str/includes? notice "web is on call."))
                (is (str/includes? notice "agent-mail serve isn't running: tell the user"))
                (is (str/ends-with? notice "Don't arm wait again, and re-read /agent-mail-listen.")))
              (is (= ["web"] (map :repo (listed))))
              (is (= (real web) (real (:dir (first (listed))))) "at the main worktree")
              (is (= ["other"] (map :session (records))) "this session's record is gone, another's stays")
              (Thread/sleep 200)
              (is (empty? @(:posts sess))))
            (testing "--once is classic even in a session"
              (let [r (am {:dir web :env env} "wait" "--once" "--timeout" "5")]
                (is (zero? (:exit r)))
                (is (= [id] (str/split-lines (str/trim (:out r)))))
                (is (str/blank? (:err r)))))
            (testing "nothing at the socket's path: classic"
              (is (= [id] (str/split-lines (out {:dir web :env (assoc env "CLAUDE_CODE_MESSAGING_SOCKET" (str (fs/path sock-dir "none.sock")))}
                                                "wait" "--timeout" "5"))))))
          (testing "an --as name is not registered: a session started there would not write as it"
            (let [r (am {:who "other" :dir web :env env} "wait")]
              (is (str/includes? (:err r) "other is not on call: `agent-mail oncall add` in its repo puts it there.")))
            (is (= ["web"] (map :repo (listed)))))
          (testing "taken off call, it stays off, and the notice says so"
            (out {:dir web} "oncall" "remove")
            (is (str/includes? (err {:dir web :env env} "wait") "web was taken off call: `agent-mail oncall add` puts it back."))
            (is (= [] (listed))))
          (finally ((:close sess)) (fs/delete-tree sock-dir)))))))

(deftest messages-record-their-session
  (with-store
    (let [in-session (fn [who] {:who who :env {"CLAUDE_CODE_SESSION_ID" "5e55-1011"}})
          id (out (in-session "website") "@infra" "Hello")]
      (out (in-session "infra") "reply" id "Hi")
      (out (in-session "user") "reply" id "From the user")
      (out {:who "website"} "reply" id "From a shell without a session")
      (out (in-session "infra") "close" id "Done.")
      (testing "send, reply and close record the writer's session; never the user's, nor outside Claude Code"
        (is (= ["5e55-1011" "5e55-1011" nil nil "5e55-1011"] (map :session (:msgs (thread-json id)))))
        (is (str/includes? (slurp (str (first (sort (fs/glob (fs/path *store* "threads" id "msgs") "*__website__*.md")))))
                           "session: \"5e55-1011\"")
            "in the frontmatter"))
      (testing "forward too"
        (let [fwd (out (in-session "infra") "forward" id "--to" "docs")]
          (is (= ["5e55-1011"] (map :session (:msgs (thread-json fwd))))))))))

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

(defn fake-claude!
  "A stand-in for the claude CLI in directory `d`: each call's arguments,
   then `cwd=` its working directory and `sid=` the CLAUDE_CODE_SESSION_ID
   that reached it, go to claude.log separated by U+001F, each call ended by
   U+001E (a prompt holds newlines). `agents --json` answers with
   agents.json, `agents --json --all` with all.json; `--bg` prints the
   background service's start line and `backgrounded · <bg.id, else
   deadbeef> · <its --name>`, or, while bg.fail exists, that file with exit
   1; `stop` says what it stopped. Returns its path."
  [d]
  (script! d "claude"
           (str "d=\"$(dirname \"$0\")\"\n"
                "{ printf '%s\\037' \"$@\"; printf 'cwd=%s\\037sid=%s\\036' \"$(pwd -P)\" \"${CLAUDE_CODE_SESSION_ID:-}\"; } >> \"$d/claude.log\"\n"
                "case \"$1\" in\n"
                "  agents) case \" $* \" in *' --all '*) cat \"$d/all.json\" ;; *) cat \"$d/agents.json\" ;; esac ;;\n"
                "  --bg) if [ -e \"$d/bg.fail\" ]; then cat \"$d/bg.fail\"; exit 1; fi\n"
                "        name=''; prev=''; for a in \"$@\"; do [ \"$prev\" = --name ] && name=\"$a\"; prev=\"$a\"; done\n"
                "        echo 'Starting background service…'\n"
                "        echo \"backgrounded · $(cat \"$d/bg.id\" 2>/dev/null || echo deadbeef) · $name\" ;;\n"
                "  stop) echo \"stopped $2\" ;;\n"
                "esac")))

(defn claude-calls
  "The fake claude's calls in `d` so far, each a vector of its arguments
   followed by its cwd= and sid= entries."
  [d]
  (let [f (fs/path d "claude.log")]
    (if (fs/exists? f)
      (mapv #(str/split % #"\u001f") (remove str/blank? (str/split (slurp (str f)) #"\u001e")))
      [])))

(defn serve-file [nm] (fs/path *store* "duty" "serve" nm))
(defn serve-state [] (edn/read-string (slurp (str (serve-file "state.edn")))))

(deftest serve-delivers-a-session-per-thread
  (with-store
    (with-dir [d]
      (let [claude (fake-claude! d)
            cfg (str (fs/path d "claude-config"))
            infra (str (fs/create-dirs (fs/path d "infra")))
            infra-real (str (fs/real-path infra))
            sock-dir (fs/create-temp-dir {:prefix "am"})
            sess (fake-session (fs/path sock-dir "s.sock"))
            other (p/process ["sleep" "600"])
            other-pid (.pid ^Process (:proc other))
            me (.pid (java.lang.ProcessHandle/current))
            env {"AGENT_MAIL_CLAUDE" claude "CLAUDE_CONFIG_DIR" cfg "CLAUDE_CODE_SESSION_ID" "serve-parent"}
            serve (fn [] (let [r (am {:env env} "serve" "--once")] (is (zero? (:exit r)) (:err r)) r))
            calls #(claude-calls d)
            clear! #(fs/delete-if-exists (fs/path d "claude.log"))
            json! (fn [file x] (spit (str (fs/path d file)) (json/generate-string x)))
            memory #(core/serve-memory (serve-state))
            full "deadbeef-1111-4222-8333-444455556666"
            entry {:id "deadbeef" :sessionId full :pid me :cwd infra-real :name "📫 infra #1" :kind "background" :status "busy" :state "running"}
            stopped (assoc entry :pid nil :status nil :state "stopped")
            peer {:id "ab12cd34" :sessionId "ab12cd34-5555-4666-8777-888899990000" :pid other-pid
                  :cwd (str infra-real "/.claude/worktrees/w") :name "infra-ab12" :kind "interactive" :status "idle" :state "running"}
            transcript! (fn [sid ago ttl]
                          (let [f (fs/path cfg "projects" "-x-infra" (str sid ".jsonl"))
                                ts (core/fmt-utc (java.time.Instant/ofEpochMilli (- (System/currentTimeMillis) ago)))]
                            (fs/create-dirs (fs/parent f))
                            (spit (str f) (str (json/generate-string {:type "user" :timestamp ts}) "\n"
                                               (json/generate-string {:type "assistant" :timestamp ts
                                                                      :message {:usage {:cache_creation {ttl 900}}}}) "\n"))))
            as-session {:who "infra" :env {"CLAUDE_CODE_SESSION_ID" full}}
            posts #(deref (:posts sess))
            text-of (fn [post] (get-in (peek post) [:message :content]))
            oncall-text #(:text (first (filter (comp #{"infra"} :repo) (json/parse-string (out {:env env} "oncall" "--json") true))))
            minute 60000
            hour 3600000]
        (json! "agents.json" [])
        (json! "all.json" [])
        (fs/create-dirs (serve-file ""))
        (spit (str (serve-file "state.edn"))
              (pr-str {:pid 1 :heartbeat "2020-01-01T00:00:00.000Z" :interval 5
                       :repos {"infra" {:pane 42 :failures 3 :until 99999999999999 :reason "old"}}}))
        (out {} "oncall" "add" "infra" infra)
        (try
          (testing "an older serve's memory (its tabs) is dropped; nothing waits, so Claude Code is not asked"
            (serve)
            (is (= [] (calls)))
            (is (not (contains? (serve-state) :repos)))
            (is (= {:threads {} :sessions {} :agents {}} (memory)))
            (is (= "on call" (oncall-text)) "not backing off"))
          (let [id (out {:who "website"} "@infra" "Rotate the key")]
            (testing "a new thread: a background session of its own, in the repo, named for the thread"
              (let [r (serve)
                    [list all bg resolve :as cs] (calls)]
                (is (re-find #"(?m)^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} start \"📫 infra #1\" \(deadbeef\) for #1 in " (:err r))
                    "one timestamped log line per action")
                (is (= 4 (count cs)))
                (is (= ["agents" "--json"] (take 2 list)))
                (is (= ["agents" "--json" "--all"] (take 3 all)))
                (is (= ["--bg" "--name" "📫 infra #1"] (take 3 bg)))
                (is (str/starts-with? (nth bg 3) (str "agent-mail serve started this session for thread #1 for infra from website, \"Rotate the key\" (" id ", message 1).")))
                (is (str/ends-with? (nth bg 3) "\nNo other session works in this folder."))
                (is (= [(str "cwd=" infra-real) "sid="] (drop 4 bg)) "in the repo; the session id serve inherited is not passed on")
                (is (= ["agents" "--json"] (take 2 resolve)) "to learn the full session id"))
              (is (= {"deadbeef" {:agent "infra" :thread id :number 1 :name "📫 infra #1" :kind "background" :ours true :state "starting"}}
                     (update-vals (:sessions (memory)) #(dissoc % :since :started-at))))
              (is (= {:agent "infra" :number 1 :holder "deadbeef"} (dissoc (get-in (memory) [:threads id]) :delivered)))
              (is (= "on call; serve's sessions: \"📫 infra #1\" (background, starting, thread #1)" (oncall-text)))
              (is (= {:id "deadbeef" :name "📫 infra #1" :kind "background" :state "starting"}
                     (:session (first (:threads (status-json {:env env})))))))
            (testing "not started twice while it comes up"
              (clear!)
              (serve)
              (is (= [["agents" "--json" (str "cwd=" (fs/real-path *store*)) "sid="]] (calls)) "a look at the sessions only"))
            (testing "it shows up"
              (json! "agents.json" [entry peer])
              (registry! cfg me {:pid me :sessionId full :messagingSocketPath (:path sess) :status "busy" :statusUpdatedAt 1 :kind "bg"})
              (registry! cfg other-pid {:pid other-pid :sessionId (:sessionId peer) :status "idle" :statusUpdatedAt 1 :kind "interactive"})
              (is (str/includes? (:err (serve)) "up \"📫 infra #1\" (deadbeef)"))
              (is (= ["live" full] ((juxt :state :session-id) (get-in (memory) [:sessions "deadbeef"])))))
            (testing "a message in a thread the session wrote in is posted into it"
              (out as-session "reply" id "Which key?")
              (is (= full (:session (last (:msgs (thread-json id))))) "the reply records the session")
              (out {:who "website"} "reply" id "The staging one.")
              (clear!)
              (let [r (serve)]
                (is (str/includes? (:err r) "post #1 into \"📫 infra #1\" (deadbeef)")))
              (is (eventually 3000 #(= 1 (count (posts)))))
              (let [[post :as all] (posts)]
                (is (= 1 (count all)))
                (is (= 1 (count post)) "one line: serve has no token to send")
                (is (str/starts-with? (text-of post) (str "agent-mail: new message in #1 \"Rotate the key\" from website (" id ", message 3), posted by agent-mail serve because this session wrote in this thread (not another agent).")))
                (is (str/ends-with? (text-of post) (str "\nAlso working in this folder: \"infra-ab12\" (interactive, idle). " core/coordinate-sentence))
                    "the other session, by the name SendMessage takes"))
              (is (not-any? #(= "--bg" (first %)) (calls)))
              (clear!)
              (serve)
              (Thread/sleep 200)
              (is (= 1 (count (posts))) "posted once")
              (is (= [["agents" "--json"]] (map #(take 2 %) (calls)))))
            (testing "who: serve's sessions; who --here: the others in this folder"
              (is (re-find #"(?m)^infra\s+\(serve\)\s+#1 → deadbeef \"📫 infra #1\" \(background, busy\)$" (out {:env env} "who")))
              (is (= (str "Also working in this folder: \"infra-ab12\" (interactive, idle). " core/coordinate-sentence)
                     (out {:dir infra :env (assoc env "CLAUDE_CODE_SESSION_ID" full)} "who" "--here")))
              (is (str/starts-with? (err {:dir infra} "who" "--here") "agent-mail: `claude agents` failed (exit 127): Cannot run program")
                  "no claude: says why"))
            (testing "a thread answered and closed in one go is posted into the session that asked, once"
              (let [q (out as-session "@website" "Which port?")]
                (out {:who "website"} "reply" q "8443.")
                (out {:who "website"} "close" q)
                (clear!)
                (is (str/includes? (:err (serve)) "post #2 into \"📫 infra #1\" (deadbeef)"))
                (is (eventually 3000 #(= 2 (count (posts)))))
                (is (str/starts-with? (text-of (last (posts)))
                                      (str "agent-mail: closed, with news: #2 \"Which port?\" closed by website (" q ", message 3)")))
                (is (str/includes? (text-of (last (posts))) "no answer is expected"))
                (is (not-any? #(= "--bg" (first %)) (calls)))
                (serve)
                (Thread/sleep 200)
                (is (= 2 (count (posts))) "posted once")
                (out as-session "read" q)
                (serve)
                (is (nil? (get-in (memory) [:threads q])) "read: no news any more, forgotten")))
            (testing "the session ended: resumed while its prompt cache is warm"
              (out as-session "reply" id "Staging rotated.")
              (out {:who "website"} "reply" id "And prod?")
              (json! "agents.json" [peer])
              (json! "all.json" [stopped peer])
              (transcript! full (* 10 minute) :ephemeral_1h_input_tokens)
              (clear!)
              (let [r (serve)]
                (is (str/includes? (:err r) "ended \"📫 infra #1\" (deadbeef)"))
                (is (str/includes? (:err r) "resume \"📫 infra #1\" (deadbeef) for #1, its cache warm until ")))
              (let [[bg :as resumes] (filter #(= "--bg" (first %)) (calls))]
                (is (= 1 (count resumes)))
                (is (= ["--bg" "--resume" full] (take 3 bg)))
                (is (str/starts-with? (nth bg 3) (str "agent-mail: new message in #1 \"Rotate the key\" from website (" id ", message 5)")))
                (is (= [(str "cwd=" infra-real) "sid="] (drop 4 bg))))
              (is (= "starting" (get-in (memory) [:sessions "deadbeef" :state]))))
            (testing "ended with its cache cold: a new session for the thread"
              (json! "agents.json" [entry peer])
              (serve)
              (out as-session "reply" id "Prod rotated.")
              (out {:who "website"} "reply" id "One more: the backup key.")
              (json! "agents.json" [peer])
              (transcript! full (* 2 hour) :ephemeral_5m_input_tokens)
              (spit (str (fs/path d "bg.id")) "cafebabe")
              (clear!)
              (let [r (serve)]
                (is (str/includes? (:err r) "start \"📫 infra #1\" (cafebabe) for #1")))
              (let [[bg :as starts] (filter #(= "--bg" (first %)) (calls))]
                (is (= 1 (count starts)))
                (is (= ["--bg" "--name" "📫 infra #1"] (take 3 bg)))
                (is (str/starts-with? (nth bg 3) "agent-mail serve started this session for thread #1"))
                (is (str/ends-with? (nth bg 3) (str "\nAlso working in this folder: \"infra-ab12\" (interactive, idle). " core/coordinate-sentence))))
              (is (= [true "starting" id] ((juxt :ours :state :thread) (get-in (memory) [:sessions "cafebabe"])))))
            (testing "serve stops a session it started once idle, holding no open thread, its cache expired"
              (json! "agents.json" [{:id "cafebabe" :sessionId "cafebabe-0000-4000-8000-000000000000" :pid other-pid :cwd infra-real
                                     :name "📫 infra #1" :kind "background" :status "idle" :state "running"}])
              (registry! cfg other-pid {:pid other-pid :status "idle" :statusUpdatedAt (- (System/currentTimeMillis) (* 2 hour)) :kind "bg"})
              (serve)
              (is (empty? (filter #(= "stop" (first %)) (calls))) "its thread still waits on it")
              (out {:who "website"} "close" id "Never mind the backup key.")
              (clear!)
              (let [r (serve)]
                (is (str/includes? (:err r) "stop \"📫 infra #1\" (cafebabe): idle, holding no open thread, its cache expired at ")))
              (is (= [["stop" "cafebabe"]] (map #(take 2 %) (filter #(= "stop" (first %)) (calls)))))
              (is (= "stopped" (get-in (memory) [:sessions "cafebabe" :state])))
              (json! "agents.json" [])
              (clear!)
              (serve)
              (is (= [] (calls)) "nothing of its own runs: Claude Code is not asked")
              (is (= {} (:sessions (memory))) "their thread closed: forgotten")
              (is (= [id] (keys (:threads (memory)))) "the thread is kept while its close is news for infra")
              (out as-session "read" id)
              (serve)
              (is (= {:threads {} :sessions {} :agents {}} (memory)) "read: forgotten")))
          (testing "`claude --bg` fails (an untrusted folder): back off, and say why"
            (spit (str (fs/path d "bg.fail")) (str "Starting background service…\nWorkspace not trusted. Run `claude` in " infra-real " once…\n"))
            (let [id (out {:who "website"} "@infra" "Anyone?")
                  r (serve)
                  why (str "`claude --bg` failed (exit 1): Workspace not trusted. Run `claude` in " infra-real " once…")]
              (is (re-find #"back off infra until \d\d:\d\d: " (:err r)))
              (is (str/includes? (:err r) why))
              (is (re-matches #"backing off until \d\d:\d\d: .*" (oncall-text)))
              (is (str/ends-with? (oncall-text) why))
              (let [st (status-json {:env env})]
                (is (= [id] (:undelivered st)))
                (is (= "none" (:listener (first (:threads st))))))
              (spit (str (serve-file "pid")) (str me "\n"))
              (is (str/starts-with? (err {:who "website" :env env} "@infra" "Still?") "→ infra: nobody on duty; on call, backing off until")
                  "while serve runs")
              (fs/delete (serve-file "pid"))
              (clear!)
              (serve)
              (is (= [] (calls)) "backing off: nothing tried")))
          (testing "a listener on duty (an older wait) keeps its repo's threads"
            (let [docs (str (fs/create-dirs (fs/path d "docs")))]
              (out {} "oncall" "add" "docs" docs)
              (spit (str (fs/path *store* "duty" "docs__l.edn"))
                    (pr-str {:repo "docs" :session "l" :state "waiting" :heartbeat (core/now-utc) :pid 1}))
              (out {:who "website"} "@docs" "Docs please")
              (clear!)
              (serve)
              (is (= [] (calls)))
              (is (= "on-duty" (:listener (first (:threads (status-json {:env env}))))))))
          (is (str/blank? (store-git "status" "--porcelain")) "serve's files stay out of the store's history")
          (finally ((:close sess)) (fs/delete-tree sock-dir) (p/destroy other)))))))
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
          (is (str/includes? (:err r) "a round every 5s, sessions through ")))
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

(deftest on-call-in-status-and-delivery-notes
  (with-store
    (with-dir [d]
      (let [me (.pid (java.lang.ProcessHandle/current))
            serve! (fn [pid agents]
                     (fs/create-dirs (serve-file ""))
                     (spit (str (serve-file "pid")) (str pid "\n"))
                     (spit (str (serve-file "state.edn")) (pr-str {:pid pid :heartbeat (core/now-utc) :interval 5 :agents agents})))
            note #(str/trim (err {:who "website"} "@infra" %))]
        (out {} "oncall" "add" "infra" d)
        (testing "serve alive, infra on call, nobody on duty: serve hands it to a session"
          (serve! me {})
          (is (= "→ infra: on call; agent-mail serve hands it to a Claude Code session shortly (the one that wrote in the thread for infra, or a new one); nothing more to do"
                 (note "Rotate it")))
          (let [st (status-json {})]
            (is (= ["on-call"] (map :listener (:threads st))))
            (is (= [] (:undelivered st)) "not undelivered")
            (is (= [{:repo "infra" :dir d :state "on-call" :pane nil :text "on call"}] (:oncall st)))
            (is (= {:alive true :pid me} (:serve st))))
          (is (str/includes? (out {} "status") "\non call: infra\n"))
          (is (str/blank? (out {:who "infra"} "hook")) "a new session hears nothing of the threads serve delivers"))
        (testing "backing off: undelivered again, and the note says why"
          (serve! me {"infra" {:failures 1 :until (+ (System/currentTimeMillis) 600000) :reason "`claude --bg` failed (exit 1): Workspace not trusted."}})
          (is (re-matches #"→ infra: nobody on duty; on call, backing off until \d\d:\d\d: `claude --bg` failed \(exit 1\): Workspace not trusted\.; it will see this at its next session start"
                          (note "Still there?")))
          (let [st (status-json {})]
            (is (= ["none" "none"] (map :listener (:threads st))))
            (is (= 2 (count (:undelivered st)))))
          (is (str/starts-with? (out {:who "infra"} "hook") "agent-mail: 2 threads waiting on infra") "nor while backing off"))
        (testing "serve not running: nothing is delivered, and the note says so"
          (serve! 4000000 {})
          (is (= "→ infra: nobody on duty; on call, but `agent-mail serve` isn't running; it will see this at its next session start"
                 (note "Anyone?")))
          (is (every? #(= "none" (:listener %)) (:threads (status-json {}))))
          (is (str/includes? (out {} "status") "on call: infra (serve isn't running)"))
          (is (str/starts-with? (out {:who "infra"} "hook") "agent-mail: 3 threads waiting on infra")))
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
