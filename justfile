# agent-mail: durable, file-based mail between agents on one machine. `just` lists recipes.

[private]
default:
    @just --list

# Lint every bb script; fails on warnings
lint:
    clj-kondo --fail-level warning --lint *.bb

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
