# agent-mail: durable, file-based mail between agents on one machine. `just` lists recipes.

[private]
default:
    @just --list

# The Mac app's recipes, from mac/justfile: `just mac build`, `just mac test`, …
mod mac

# Lint src/ and every bb script (the Mac app's too); fails on warnings
lint:
    clj-kondo --fail-level warning --lint src *.bb mac/scripts

# Run the test suite against a temporary store
test:
    bb test.bb

# Lint, then test
check: lint test

# Link the launcher into ~/.local/bin and the skills into ~/.claude/skills; refuses to replace anything it didn't create
install:
    #!/usr/bin/env bb
    (require '[babashka.fs :as fs])
    (doseq [[src target] [[(fs/absolutize "bin/agent-mail") (fs/path (fs/home) ".local" "bin" "agent-mail")]
                          [(fs/absolutize "skills/agent-mail") (fs/path (fs/home) ".claude" "skills" "agent-mail")]
                          [(fs/absolutize "skills/agent-mail-listen") (fs/path (fs/home) ".claude" "skills" "agent-mail-listen")]]]
      (cond
        (and (fs/sym-link? target) (= (str (fs/read-link target)) (str src)))
        (println "already installed:" (str target) "->" (str src))
        (fs/exists? target)
        (do (println "refusing to replace" (str target) "- not created by agent-mail") (System/exit 1))
        :else
        (do (fs/create-dirs (fs/parent target))
            (fs/create-sym-link target src)
            (println "installed:" (str target) "->" (str src)))))
    (println "SessionStart hook: `just install-hook` (prints what it adds to ~/.claude/settings.json)")

# Add the SessionStart hook to ~/.claude/settings.json next to existing hooks (idempotent; other settings untouched)
install-hook:
    #!/usr/bin/env bb
    (require '[babashka.fs :as fs] '[cheshire.core :as json])
    (let [f (str (fs/path (fs/home) ".claude" "settings.json"))
          d (if (fs/exists? f) (json/parse-string (slurp f)) {})
          cmd "$HOME/.local/bin/agent-mail hook"
          hook {"matcher" "startup|resume|clear|compact"
                "hooks" [{"type" "command" "command" cmd "timeout" 10}]}
          present? (some #(= cmd (get-in % ["hooks" 0 "command"])) (get-in d ["hooks" "SessionStart"]))]
      (if present?
        (println "already configured in" f)
        (do (println "adding to hooks.SessionStart in" f)
            (println (json/generate-string hook {:pretty true}))
            (spit f (str (json/generate-string (update-in d ["hooks" "SessionStart"] (fnil conj []) hook) {:pretty true}) "\n"))
            (println "added"))))

# Run `agent-mail serve` (on-call listener tabs) under launchd, logging to ~/Library/Logs; replaces a loaded one
install-serve:
    #!/usr/bin/env bb
    (require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str])
    (when-not (str/includes? (System/getProperty "os.name") "Mac") (println "launchd is macOS only") (System/exit 1))
    (let [env #(not-empty (System/getenv %))
          home (or (env "HOME") (str (fs/home)))
          label "dev.agent-mail.serve"
          plist (str (fs/path home "Library" "LaunchAgents" (str label ".plist")))
          log (str (fs/path home "Library" "Logs" "agent-mail-serve.log"))
          bin (str (fs/path home ".local" "bin" "agent-mail"))
          mise (or (env "MISE_DATA_DIR") (str (fs/path (or (env "XDG_DATA_HOME") (fs/path home ".local" "share")) "mise")))
          vars (cond-> {"PATH" (str/join ":" [(str (fs/path home ".local" "bin")) "/opt/homebrew/bin" "/usr/local/bin"
                                              (str (fs/path mise "shims")) "/usr/bin" "/bin" "/usr/sbin" "/sbin"])}
                 (env "SHELL") (assoc "SHELL" (env "SHELL"))
                 (env "AGENT_MAIL_DIR") (assoc "AGENT_MAIL_DIR" (env "AGENT_MAIL_DIR")))
          esc #(-> % (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;"))
          s #(str "<string>" (esc %) "</string>")
          domain (str "gui/" (str/trim (:out (p/shell {:out :string} "id" "-u"))))
          target (str domain "/" label)
          loaded? #(zero? (:exit (p/shell {:out :string :err :string :continue true} "launchctl" "print" target)))]
      (when-not (fs/exists? bin) (println "no" bin "yet: run `just install` first") (System/exit 1))
      (fs/create-dirs (fs/parent plist))
      (fs/create-dirs (fs/parent log))
      (spit plist (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                       "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                       "<plist version=\"1.0\">\n<dict>\n"
                       "  <key>Label</key>" (s label) "\n"
                       "  <key>ProgramArguments</key><array>" (s bin) (s "serve") "</array>\n"
                       "  <key>EnvironmentVariables</key><dict>"
                       (str/join (for [[k v] (sort vars)] (str "<key>" k "</key>" (s v)))) "</dict>\n"
                       "  <key>WorkingDirectory</key>" (s home) "\n"
                       "  <key>RunAtLoad</key><true/>\n"
                       "  <key>KeepAlive</key><true/>\n"
                       "  <key>StandardOutPath</key>" (s log) "\n"
                       "  <key>StandardErrorPath</key>" (s log) "\n"
                       "</dict>\n</plist>\n"))
      (p/shell {:out :string} "plutil" "-lint" plist)
      (println "wrote" plist (str "(PATH=" (vars "PATH") (when (vars "AGENT_MAIL_DIR") (str ", AGENT_MAIL_DIR=" (vars "AGENT_MAIL_DIR"))) ")"))
      (when (loaded?)
        (println "booting out the loaded" label)
        (p/shell "launchctl" "bootout" target))
      ;; bootout finishes asynchronously; bootstrap fails until it has
      (loop [n 0]
        (let [r (p/shell {:out :string :err :string :continue true} "launchctl" "bootstrap" domain plist)]
          (cond (zero? (:exit r)) (println (str "started " label "; log: " log))
                (< n 40) (do (Thread/sleep 250) (recur (inc n)))
                :else (do (println "launchctl bootstrap failed:" (str/trim (str (:err r) (:out r)))) (System/exit 1))))))

# Stop the launchd agent that runs `agent-mail serve` and remove its plist (the log stays)
uninstall-serve:
    #!/usr/bin/env bb
    (require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str])
    (let [home (or (not-empty (System/getenv "HOME")) (str (fs/home)))
          label "dev.agent-mail.serve"
          plist (fs/path home "Library" "LaunchAgents" (str label ".plist"))
          target (str "gui/" (str/trim (:out (p/shell {:out :string} "id" "-u"))) "/" label)]
      (if (zero? (:exit (p/shell {:out :string :err :string :continue true} "launchctl" "print" target)))
        (do (p/shell "launchctl" "bootout" target) (println "stopped" label))
        (println label "is not loaded"))
      (if (fs/exists? plist)
        (do (fs/delete plist) (println "removed" (str plist)))
        (println "no" (str plist))))

# Remove the ~/.local/bin and ~/.claude/skills symlinks if they point here
uninstall:
    #!/usr/bin/env bb
    (require '[babashka.fs :as fs])
    (doseq [[src target] [[(fs/absolutize "bin/agent-mail") (fs/path (fs/home) ".local" "bin" "agent-mail")]
                          [(fs/absolutize "skills/agent-mail") (fs/path (fs/home) ".claude" "skills" "agent-mail")]
                          [(fs/absolutize "skills/agent-mail-listen") (fs/path (fs/home) ".claude" "skills" "agent-mail-listen")]]]
      (if (and (fs/sym-link? target) (= (str (fs/read-link target)) (str src)))
        (do (fs/delete target) (println "removed" (str target)))
        (println "nothing to remove at" (str target))))
