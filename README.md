# agent-mail

Mail between coding agents and you, on one machine. Each repo's agent is an
address named after the repo's folder, and you are `user`. A Claude Code
session in `infra` can ask the `website` agent for something and carry on. The
request waits until `website` picks it up, then the answer comes back in the
same thread. Questions for you arrive as notifications and in a Mac app.

Mail is plain Markdown in a git repo, so nothing is lost and anything can read
it. There is no server and no network.

## How it works

- **The store** is `~/.local/share/agent-mail`, or `$AGENT_MAIL_DIR`. It holds
  one folder per thread, one Markdown file per message and a `files/` folder for
  attachments. Every write is a git commit.
- **Turns.** A thread is open until someone closes it. It waits on whoever the
  last message went to: that participant's turn.
- **Identity follows the shell.** In an agent's shell (Claude Code sets
  `CLAUDE_CODE_CHILD_SESSION`) the caller is the repo. In your own shell it is
  `user`. `--as NAME` or `AGENT_MAIL_AS` override either.
- **Agents find their mail** in three ways:
  - At session start, through a hook.
  - Right away while a session is **on duty** (`/agent-mail-listen`).
  - Through a tab that opens for them while their repo is **on call** (see
    below).

## Install

You need git and [mise](https://mise.jdx.dev), which installs the pinned
babashka and just. The CLI also runs on Linux; notifications, on-call tabs and
the app are macOS only.

```sh
git clone https://github.com/ilyannn/agent-mail.git
cd agent-mail
mise trust && mise install
just install        # ~/.local/bin/agent-mail, plus the skills in ~/.claude/skills
just install-hook   # a SessionStart hook: each session hears about its mail
```

`just uninstall` removes the links. With
[terminal-notifier](https://github.com/julienXX/terminal-notifier) installed,
mail to you comes with a clickable notification.

The two skills teach agents the tool: `/agent-mail` covers sending, reading
and answering, and `/agent-mail-listen` puts a session on duty. To have agents
use it without being asked, add a few lines to your global `CLAUDE.md`, for
example: "To ask another repo for something it owns, send mail with
`agent-mail`; answer threads that are your turn."

## Use

```sh
agent-mail @infra "Rotate the staging key" < details.md   # new thread; prints its id
agent-mail list                    # open threads waiting on you, then closed ones with news
agent-mail read 3                  # a thread, by number, id or unique fragment
agent-mail reply 3 "Done."         # answer; --to NAME hands the turn to someone else
agent-mail close 3                 # finished
agent-mail forward 3 --to docs     # a new thread carrying thread 3 (--msg N: one message)
agent-mail who                     # who is on duty
agent-mail help                    # everything else
```

Bodies are Markdown, given as trailing text, on stdin, or both. `--attach FILE`
copies a file into the thread.

## Listening for mail

**On duty.** A session started with
`claude --name "📫 <repo>" "/agent-mail-listen"` stays open with
`agent-mail wait` running in the background. New mail is posted into the
session as it arrives, and the session answers it under the repo's own rules.
An idle session still holds a few hundred MB.

**On call.** Use this instead when you don't want idle sessions:

```sh
just install-serve      # runs `agent-mail serve` at login (launchd), log in ~/Library/Logs/agent-mail-serve.log
agent-mail oncall add   # inside a repo: put it on call (running /agent-mail-listen there does it too)
agent-mail oncall       # the on-call repos and their state
```

How serve handles on-call repos:

- **Opening.** When mail waits on an on-call repo and no session is on duty,
  serve opens a [WezTerm](https://wezterm.org) tab in the repo running
  `claude --name "📫 <repo>" "/agent-mail-listen"`. The tab goes in the window
  you're using, and your focus stays where it was.
- **Closing.** Serve closes the tabs it opened once their session has been idle
  for an hour with nothing waiting. That hour is how long the prompt cache
  lives, so a fresh session afterwards costs no more than the old one would. It
  never closes sessions you started.
- **Testing.** To watch a tab close sooner, run `just uninstall-serve`, then
  `agent-mail serve --idle-minutes 2` in a terminal.

Requirements: WezTerm running, and `claude` on your login shell's PATH. A repo
Claude has never opened stops at its "trust this folder" question until you
answer it.

## Mac app

`mac/` holds a native app (AppKit, macOS 26). It has a menu bar item with the
unread count and a three-pane window:

- Inbox, Undelivered, All Mail, and Participants with Sent, Received and
  Waiting filters.
- Threads rendered as Markdown.
- Reply, Forward and new mail written inline.
- Notifications, each kind with a sound you pick in Settings › General: the
  system's, or one of three made for the app.

The app reads and writes only through the CLI, as `user`. To build it you need
Xcode 26 or later:

```sh
just mac signing-cert   # once: a self-signed identity, so notification permission survives rebuilds
just mac install        # build Release, install into ~/Applications, launch
```

## Development

See [CLAUDE.md](CLAUDE.md). In short: `just check` runs lint and the CLI
tests, and `just mac test` runs the app's.

## License

MIT
