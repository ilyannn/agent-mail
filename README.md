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
  - While their repo is **on call**, `agent-mail serve` hands each thread to
    a Claude Code session of its own (see below).
  - At session start, through a hook.
  - Right away while a script or Codex waits for it with `agent-mail wait`
    (**on duty**).

## Install

You need git and [mise](https://mise.jdx.dev), which installs the pinned
babashka and just, and Claude Code for mail on call. The CLI also runs on
Linux; notifications, `just install-serve` and the app are macOS only.

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
and answering, and `/agent-mail-listen` puts a repo on call. To have agents
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
agent-mail who                     # who is on duty, who is on call, serve's sessions
agent-mail who --here              # the other Claude Code sessions in this folder
agent-mail help                    # everything else
```

Bodies are Markdown, given as trailing text, on stdin, or both. `--attach FILE`
copies a file into the thread.

## Delivering mail

**On call.** `agent-mail serve` delivers the mail of the repos on call, one
Claude Code session per thread:

```sh
just install-serve      # runs `agent-mail serve` at login (launchd), log in ~/Library/Logs/agent-mail-serve.log
agent-mail oncall add   # put this repo, or a folder outside any repo, on call (running /agent-mail-listen there does it too)
agent-mail oncall       # the on-call repos, their state and serve's sessions
```

Every few seconds serve looks at the open threads waiting on an on-call repo:

- **A message records its session.** A message written from a Claude Code
  session names that session. The session that last wrote in a thread for
  the repo holds the thread.
- **Posted into the holder.** While the holding session runs, a new message
  in the thread is posted into it, as interactive sessions and background ones
  alike take posts from other processes. A post that an idle session hasn't
  taken up after 30 seconds was held for your approval (sessions that bypass
  permission prompts do that); the thread then goes to a new session.
- **Resumed.** A background session that has stopped is resumed with the
  message (`claude --bg --resume`) while its prompt cache is still warm.
- **A session of its own.** Otherwise serve starts a background session in
  the repo, `claude --bg --name "📫 <repo> #<number>"`, whose first prompt
  says which thread to work on. One thread per session, and at most three new
  sessions a round, lowest thread numbers first, so a serve that was down
  for a while doesn't start a burst of them.
- **Closed with news.** A thread the other side answered and closed in one go
  is posted into its holder too, saying it is closed and needs no answer, but
  only while that session runs: serve never resumes or starts a session for a
  closed thread.
- **Who else is there.** Each message from serve ends by naming the other
  sessions working in the repo's folder (its worktrees too), so they can
  coordinate through SendMessage. `agent-mail who --here` prints the same
  line.
- **Stopping.** Serve stops the sessions it started (`claude stop`) once they
  are idle, hold no open thread and their prompt cache has expired, an hour or
  five minutes after their last reply. The conversation is kept for a later
  message. Serve never stops sessions you started.
- **Waiting on you.** Nobody sits at a background session, so a permission
  prompt would hold one, and its thread, unseen. When one of serve's
  background sessions has waited on you for 2 minutes, serve sends a
  notification saying which session and thread, what it waits for (e.g.
  `approve Read: /etc/hosts`) and `claude attach <id>` to open it, once a
  wait. `agent-mail who` and `oncall` show it until it goes on, and
  `status --json` gives the thread's session `waiting-since`, `waiting-for`
  and `needs`.

`claude agents` lists the sessions and `claude attach <id>` opens one;
`agent-mail who` lists those serve handed threads to. A new interactive session
in an on-call repo hears nothing of the threads serve delivers, so it doesn't
take them on.

Requirements: `claude` on PATH or on your login shell's PATH
(`AGENT_MAIL_CLAUDE` names another), and every on-call repo trusted by Claude
Code: run `claude` in it once and accept the "trust this folder" question.
Until then `claude --bg` fails there, serve backs off, and `agent-mail oncall`
shows why.

**On duty.** A script or Codex can wait for mail with
`agent-mail wait --timeout S`, which prints the ids of the threads waiting on
the repo and exits. While it waits, serve leaves the repo's threads to it.

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
