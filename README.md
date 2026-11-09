# agent-mail

Mail between coding agents and you, on one machine. Each repo's agent has a
name in the agent directory, by default the repo's folder name, and you are
`user`. A Claude Code
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
- **The agent directory** maps each agent's name to its folder and says what
  it owns (see below). Mail goes only to `user`, to names in it, and to
  `postmaster`, serve's own role (see below).
- **Identity follows the shell.** In an agent's shell (Claude Code sets
  `CLAUDE_CODE_CHILD_SESSION`) the caller is the agent whose folder holds the
  current one. In your own shell it is `user`. `--as NAME` or `AGENT_MAIL_AS`
  override either.
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
agent-mail who                     # who is on duty, who is on call, serve's sessions, the postmaster's notices
agent-mail who --here              # the other Claude Code sessions in this folder
agent-mail agents                  # the agent directory: who owns what, and where
agent-mail help                    # everything else
```

Bodies are Markdown, given as trailing text, on stdin, or both. `--attach FILE`
copies a file into the thread.

## The agent directory

The directory is the only source of agent names. It holds one entry per
agent, `agents/<name>.md` in the store, committed like the mail. Each entry
gives the agent's folder (its real path, and the folder's device and inode)
and whether it is on call. Its text says what the agent owns and what to ask
it for, and the first line is the summary.

```sh
agent-mail agents                    # every agent: folder, on call or not, summary
agent-mail agents add [DIR]          # add a folder, on call: by default this repo, else this folder
agent-mail agents add ../web --name site "Owns the website."
agent-mail agents describe < about.md   # an agent describes itself; you describe anyone by NAME
agent-mail agents move NAME DIR      # its folder is elsewhere now
agent-mail agents remove NAME        # out of the directory; past mail keeps the name
```

An agent describes, moves and removes only its own entry; you can change any.
`/` and your home folder are refused as an agent's folder. A folder that
encloses other agents' folders is added with a note: they keep their names,
and every folder below it without an entry of its own is the new agent.

- **Who is calling.** An agent is the entry whose folder is the current
  folder or the nearest one above it, so a repo nested in an agent's folder
  is that agent unless it has an entry of its own. A worktree counts as its
  repo, and `…/app` never matches `…/app-old`. Outside any
  repo, the folder the session started in is tried too, so a shell that
  wandered into a scratchpad is still its session's agent.
- **Moved folders keep their name.** A moved or renamed folder is found by
  its device and inode, and the directory follows it.
- **New folders join with their first mail.** A folder with no entry is
  named as before the directory, after its repo or itself. It is added, on
  call, the first time it sends mail, so replies reach it, unless it is `/`,
  your home folder or a folder enclosing other agents' folders.
- **Unknown names are refused.** Mail to a name not in the directory is
  refused, with the closest names and how to add a folder. Mail already in
  the store keeps its names; while serve runs, the postmaster sends a
  thread waiting on a name the directory doesn't have back to its sender.
- **A summary even without a description.** For an agent without one,
  `agents` shows the first paragraph of the folder's CLAUDE.md or README.md,
  marked as such. A CLAUDE.md whose first paragraph is only Claude Code
  imports, such as `@README.md`, lends the first paragraph of the first
  file it imports that has one (relative to the folder, following imports
  there too). A session serve starts for it is asked to write one.
- **Trust.** `agents` marks an on-call folder that Claude Code hasn't trusted
  yet, which serve can't start sessions in.
- **From on-call registrations.** The first time the store is written or
  serve runs, the older on-call registrations (`duty/oncall/`) become
  entries. They stay where they were, and the directory decides from then on.

## Delivering mail

**On call.** `agent-mail serve` delivers the mail of the agents on call in
the directory, in their folders, one Claude Code session per thread:

```sh
just install-serve      # runs `agent-mail serve` at login (launchd), log in ~/Library/Logs/agent-mail-serve.log
agent-mail oncall add   # put this repo, or a folder outside any repo, on call (running /agent-mail-listen there does it too)
agent-mail oncall       # the agents on call, their state and serve's sessions
```

`oncall add` and `oncall remove` set the directory's on-call field; new
entries start on call.

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
- **Related threads.** Two threads can be about the same thing, such as a bug
  report and your own note on the bug. Their sessions see each other in the
  line below and agree through SendMessage which one handles both. The one
  taking over writes in the other thread, so it holds that thread from then
  on.
- **Closed with news.** A thread the other side answered and closed in one go
  is posted into its holder too, saying it is closed and needs no answer, but
  only while that session runs: serve never resumes or starts a session for a
  closed thread.
- **Who else is there.** Each message from serve ends by naming the other
  sessions working in the repo's folder (its worktrees too), with the thread
  and subject of each that serve started, so they can coordinate through
  SendMessage. `agent-mail who --here` prints the same line.
- **Stopping.** Serve stops a session it started (`claude stop`) once it has
  been idle for 5 minutes, whether or not it holds a thread; never while it
  runs a background task, waits on you or works, nor while a post into it is
  being checked. This costs nothing: the prompt cache lives on the server,
  not in the process, so the next message in its thread resumes it while the
  cache is warm and goes to a new session after that, as above. Only the
  process's memory is saved. Serve never stops sessions you started.
- **Landing.** In a git repo a background session works in a worktree of its
  own under `.claude/worktrees`, and Claude Code refuses its git commands
  aimed at the main checkout. So when one of serve's sessions goes idle or
  stops, serve brings the repo's main checkout up to date with its work.
  With an upstream, when the checkout is strictly behind `@{u}`, it runs
  `git merge --ff-only @{u}`. It never fetches: the worktree shares the
  repository, so the session's push has already moved the local
  `origin/…` ref (a push from another clone lands once something has
  fetched it). Without an upstream, as in a repo with no remote, it
  fast-forwards the checkout to the session's worktree branch, once the
  session's thread is closed and the branch is strictly ahead of the
  checkout; a session that only answered, perhaps with a question, may
  still be midway. Either way only when the agent's folder is the
  repo's main worktree, on a branch, with no changes to tracked files, and
  no other Claude Code session works in it (its folder is the checkout or
  inside it, outside `.claude/worktrees`). Untracked files are never
  touched, and nothing but a fast-forward changes anything there. Serve
  logs each landing, e.g. `land infra: main 3 commits to abc1234
  (upstream)`. When it can't land (uncommitted changes, someone working
  there, diverged history), it logs why, and `agent-mail oncall`, `who` and
  `status --json` (`cannot-land`) show it, e.g. `main checkout 2 behind
  origin/main: uncommitted changes`.
- **Removing.** Once one of serve's sessions is stopped and holds no open
  thread, serve runs `claude rm` on it, which removes the job and its
  worktree when that is safe; the transcript stays for `claude --resume`.
  It does so only when nothing of the session is unlanded: it has no
  worktree, or its worktree branch is contained in the main checkout's HEAD
  or in its upstream. A session with unlanded commits is kept, and `who`
  and `oncall` list it, e.g. `unlanded: branch worktree-x, 2 commits`;
  serve looks again after each landing for the repo and every 10 minutes.
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

**The postmaster.** Serve has a role of its own, `postmaster`: a script,
not a Claude Code session. Each round it looks at the open threads that
wait on an agent, not on you, and that nothing delivers:

- **A name not in the directory**, as in mail from before it or to an
  entry removed since. After one round of grace the postmaster writes the
  thread back to its last sender who isn't that name, or to you when no
  such sender is in the directory either. It names the closest agents and
  how to pass the thread on (`agent-mail forward N --to NAME`, or
  `agent-mail reply N --to NAME`). It is that sender's turn then, so
  serve delivers it there. A thread goes back at most once per name.
- **An agent off call**, with a thread that has waited on it for more
  than an hour. You get a notice saying that nothing delivers it until a
  session starts in the agent's folder, and that `agent-mail oncall add
  NAME` lets serve deliver it.
- **An agent on call that serve backs off from** for more than 15
  minutes, or after 3 failures in a row. The notice gives serve's reason
  and the fix where one is known: run `claude` once in a folder Claude
  Code hasn't trusted; `agent-mail agents move NAME DIR` for a folder
  that is gone; otherwise serve's log.

A notice is mail from `postmaster` to you, with the usual notification:
one thread per agent and kind, e.g. `postmaster: 2 threads wait on infra,
which isn't on call`. When its threads or the reason change, the
postmaster replies in it. Once they are delivered or closed, or the
problem is gone, it closes the thread with a short "Delivered now". It
keeps what it said in serve's state, so a restart repeats nothing. It
writes only in a notice that waits on you: once you close one or answer
it, the thread is yours, and a later change starts a new notice.
`agent-mail who` and `oncall` list the open notices, and `status --json`
has them as `postmaster`.

Mail to `postmaster`, such as your answer to a notice, goes to the agent
`agent-mail`, the one that owns the tool: serve delivers it there while
that agent is on call. Anyone may send to `postmaster`; nobody writes as
it but serve, so `--as postmaster` is refused.

Requirements: `claude` on PATH or on your login shell's PATH
(`AGENT_MAIL_CLAUDE` names another), and every on-call repo trusted by Claude
Code: run `claude` in it once and accept the "trust this folder" question.
Until then `claude --bg` fails there, serve backs off, and `agent-mail oncall`
shows why. A git repo nested inside a trusted folder needs its own `claude`
run: trust reaches plain folders below a trusted one, not repos.
`agent-mail agents` marks such folders beforehand.

**On duty.** A script or Codex can wait for mail with
`agent-mail wait --timeout S`, which prints the ids of the threads waiting on
the repo and exits. While it waits, serve leaves the repo's threads to it.

## Mac app

`mac/` holds a native app (AppKit, macOS 26). It has a menu bar item with the
unread count and a three-pane window:

- Inbox, Undelivered, All Mail, and Participants with Sent, Received and
  Waiting filters. The Inbox and the menu bar count the unread messages
  addressed to you (each thread's `unread-to-me` in `status --json`), not what
  agents sent each other in the threads waiting on you.
- Participants are the agent directory, plus names that only appear in
  mail: a dot says whether one is on call, the tooltip what it owns, and a
  warning what needs you (a folder serve can't use, a checkout it can't
  land in, unlanded sessions; its mailbox lists them too). Its menu puts
  it on or off call; File › Add Folder… adds one.
- Threads rendered as Markdown, with the session serve handed each to; one
  waiting on you says what for, with `claude attach ID` to copy.
- Reply, Forward and new mail written inline; To offers the directory's
  names and says when one isn't in it.
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
