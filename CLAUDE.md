# agent-mail

This repo is the `agent-mail` CLI, its two skills and the Mac app. The
[README](README.md) says what it is and how to install it. Questions about the
tool, sent by mail from other repos, are this repo's to answer.

## Layout

- `agent-mail.bb`: the CLI, holding commands, IO and the store. Run through
  `bin/agent-mail`, the launcher that `~/.local/bin/agent-mail` links to, so
  edits take effect at once for every agent on the machine.
- `src/agent_mail/core.clj`: pure logic (parsing, thread state, status, flow,
  forwarding, on-call decisions), unit-tested in-process.
- `test.bb`: unit tests, plus end-to-end tests that run the launcher against a
  temporary store.
- `skills/agent-mail`, `skills/agent-mail-listen`: what agents read. `just install` links them into `~/.claude/skills`.
- `mac/`: the app.
  - `AgentMailKit/`: a Swift package, AppKit-free logic with Swift Testing tests.
  - `AgentMail/`: the app target.
  - `project.yml`: XcodeGen; the Xcode project isn't committed.
  - `mac/justfile`: the app's recipes.
  - `mac/scripts/sounds.bb`: synthesizes the app's notification sounds.
- `.github/workflows/ci.yml`: `just check` on Ubuntu and macOS, plus the app
  build and tests on macos-latest.

## Commands

- `just lint`: clj-kondo over `src/`, `*.bb` and `mac/scripts`; it must report
  no warnings.
- `just test`: the CLI suite, about a minute.
- `just check`: both of the above.
- `just mac test`: the AgentMailKit tests, then the app's tests.
- `just mac build`, `just mac install`: build the app; install it into
  `~/Applications` and relaunch it.
- `AGENT_MAIL_DEBUG_HOOKS=1 AGENT_MAIL_DIR=<scratch store> just mac run`
  starts a Debug build that `bb mac/scripts/debug.bb` can drive: dump, select,
  key, snapshot and more (see its header). Use this to check UI changes without
  seeing the screen.

## Rules

- **Never touch the real store** (`~/.local/share/agent-mail`) in tests or
  experiments. Use a temporary `AGENT_MAIL_DIR`. Tests also clear the
  `CLAUDE_*` and `WEZTERM_*` variables they inherit and point
  `AGENT_MAIL_WEZTERM`, `AGENT_MAIL_CLAUDE` and `CLAUDE_CONFIG_DIR` at fakes,
  so a test run inside Claude Code or WezTerm can't post into a real session,
  start or stop a real one, or retitle a real tab. Never run the real
  `claude` from a test. They drop `GIT_DIR` and the other variables that
  point git elsewhere, which git exports to hooks and to `git rebase
  --exec`: with them, the tests' git commands commit into this repo.
- **Mac app testing:** use a scratch store, and don't send mail from the
  installed app.
- **Keep docs in step.** A change agents rely on (a command, a flag, an output
  they parse) updates the help text in `agent-mail.bb` and the skills in the
  same commit.
- **status --json is an interface for UIs.** Adding fields is compatible.
  Changing or removing one bumps `status-schema` in `core.clj`.
- **The app never writes the store itself.** It runs the CLI as `user`, and
  the store watcher brings the change back.
- **Swift:** Swift 6 strict concurrency, zero warnings. CI builds with Xcode 26,
  which is stricter than newer local Xcodes, so keep to the macOS 26 SDK.
- **Editing files:** use an editor tool or a script with assertions, never
  `sed -i` or perl. Those have mangled UTF-8 here (`→`, `📫`, `…`).
- **This repo is public:** no personal names, emails, host names or home paths
  in code, tests, docs or commit messages. `HANDOFF*.md` are private and
  ignored. Machine-specific changes, such as installing the LaunchAgent, are
  recorded in the machines repo by mail, not here.

## On duty

When this repo is on duty (`/agent-mail-listen`):

- **Questions about the tool:** answer them and close the thread.
- **Bug and friction reports** (often from `agent-survey`):
  1. Fix them with a test.
  2. Run `just check`, plus `just mac test` when `mac/` changed.
  3. Commit and push.
  4. Reply with the commit and how the behaviour changed, then close.
- **Behaviour changes** that other sessions must learn about (a new way to arm
  `wait`, a renamed flag): say so in the reply. The notice in `cmd-wait` shows
  how running listeners learn of a change.
