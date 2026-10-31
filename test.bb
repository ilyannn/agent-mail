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
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

(def repo (fs/parent (fs/absolutize *file*)))
(cp/add-classpath (str (fs/path repo "src")))
(require '[agent-mail.core :as core])

(def launcher (str (fs/path repo "bin" "agent-mail")))
(def ^:dynamic *store* nil)

;; Tests run as an agent's shell (CLAUDECODE set) unless a test says otherwise.
(defn env-for [{:keys [who env]}]
  (cond-> (merge {"AGENT_MAIL_DIR" (str *store*) "AGENT_MAIL_NO_NOTIFY" "1" "CLAUDECODE" "1"} env)
    who (assoc "AGENT_MAIL_AS" who)))

(defn start
  "Starts agent-mail as `who` (nil = detect from cwd) in `dir` without waiting;
   deref the result for {:exit :out :err}."
  [{:keys [dir in] :as o} & args]
  (apply p/process {:out :string :err :string :in (or in "") :dir (or dir (str *store*)) :extra-env (env-for o)}
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

;; ================================================================ unit: argv

(deftest argv-parsing
  (is (= {:args ["reply" "7" "the" "text"] :opts {:attach [] :to "b" :json true}}
         (core/parse-argv ["reply" "--to" "b" "7" "the" "--json" "text"])) "options anywhere")
  (is (= {:args ["send" "--to" "x" "-h"] :opts {:attach [] :to "b"}}
         (core/parse-argv ["send" "--to" "b" "--" "--to" "x" "-h"])) "-- ends options")
  (is (= ["a" "b"] (:attach (:opts (core/parse-argv ["send" "--attach" "a" "--attach" "b"])))))
  (is (= 5 (:timeout (:opts (core/parse-argv ["wait" "--timeout" "5"])))))
  (is (= 2 (:interval (:opts (core/parse-argv ["wait" "--interval" "2" "--"])))) "numbers are parsed even when -- ends the options")
  (is (= ["help" "read" "7"] (:args (core/parse-argv ["read" "7" "--help"]))) "help anywhere comes first")
  (is (= ["help"] (:args (core/parse-argv ["-h"]))))
  (is (usage-thrown? #(core/parse-argv ["wait" "--timeout" "soon"])))
  (is (usage-thrown? #(core/parse-argv ["send" "--to"])))
  (is (usage-thrown? #(core/parse-argv ["send" "--attach"])))
  (is (usage-thrown? #(core/parse-argv ["send" "--bogus"])))
  (is (= ["send" "--to" "infra" "hi" "there"] (core/expand-shorthand ["@infra" "hi" "there"])))
  (is (= ["list"] (core/expand-shorthand ["list"])))
  (is (= [] (core/expand-shorthand []))))

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
        (is (empty? (ids "infra")))
        (is (empty? (ids "website")))
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
        (is (every? empty? (map ids ["user" "infra" "website" "billing"])))))))

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
        (is (= "answer\nover two lines" (:body (last (:msgs (thread-json id))))))))))

(deftest json-shapes
  (with-store
    (let [id (out {:who "a"} "send" "--to" "b" "--subject" "Shape" "hello")]
      (out {:who "b"} "reply" id "back")
      (testing "list --json is one summary per thread, no messages"
        (let [[t :as ts] (list-json "--all")]
          (is (= 1 (count ts)))
          (is (= #{:id :number :subject :from :last-from :created :owner :status :count :updated :unread} (set (keys t))))
          (is (= {:id id :number 1 :subject "Shape" :from "a" :owner "a" :status "open" :count 2} (select-keys t [:id :number :subject :from :owner :status :count])))
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
      (is (str/includes? (err {:who "a"} "@user" "for you") "→ user: shown at every session start") "no notification in tests"))))

(deftest caller-identity
  (with-store
    (with-dir [d]
      (let [repo (str (fs/path d "repo-x")) wt (str (fs/path d "repo-x-wt"))]
        (git-in d "init" "-q" repo)
        (git-in repo "commit" "-q" "--allow-empty" "-m" "i")
        (git-in repo "worktree" "add" "-q" wt "-b" "wt")
        (is (= "repo-x" (out {:dir repo} "whoami")))
        (is (= "repo-x" (out {:dir wt} "whoami")) "a worktree counts as its repo")
        (is (= "user" (out {:dir d :env {"CLAUDECODE" ""}} "whoami")) "the user's own shell is the user")
        (is (= "user" (out {:dir repo :env {"CLAUDECODE" ""}} "whoami")) "also inside a repo")
        (testing "an agent's shell outside any repo is the directory, never the user"
          (let [r (am {:dir d :env {"CLAUDECODE" "1"}} "whoami")]
            (is (= (str (fs/file-name d)) (str/trim (:out r))))
            (is (str/includes? (:err r) "not in a git repo; acting as")))
          (is (str/blank? (:err (am {:dir d :env {"CLAUDECODE" "1"}} "hook"))) "the hook stays silent about it")
          (is (= "user" (out {:dir d :who "user" :env {"CLAUDECODE" "1"}} "whoami")) "AGENT_MAIL_AS=user is explicit"))
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
      (is (= 3 (commits)) "reading commits nothing")
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
