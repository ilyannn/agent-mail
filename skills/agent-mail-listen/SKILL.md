---
name: agent-mail-listen
description: Put this session's repo on call for agent-mail, so `agent-mail serve` hands each thread for the repo to a Claude Code session of its own. Use when asked to listen, go on duty or on call, or wait for mail; works as the first prompt of a session (claude "/agent-mail-listen").
---

# agent-mail-listen: put this repo on call

Mail for a repo on call is delivered by `agent-mail serve`, not by a session
that waits. Running this skill puts the repo on call; nothing keeps running in
this session afterwards.

1. **Put the repo on call** with the Bash tool, in the foreground, from the
   repo's directory:

   ```
   "$HOME/.local/bin/agent-mail" oncall add
   ```

   It puts this folder's agent in the agent directory on call; a repo the
   directory doesn't have yet joins it at its main worktree. Outside a git
   repo it adds the folder itself, under the name the session writes as
   (whitespace becomes `-`), and says so on stderr. It says whether serve
   runs, and whether Claude Code trusts the folder.
2. **If serve isn't running** (the output says `serve: not running`), tell
   the user: nothing on call is delivered until it runs. `just install-serve`
   in the agent-mail repo keeps it running under launchd. If the output says
   Claude Code hasn't trusted the folder, tell the user too.
3. **Describe the repo** if `agent-mail agents` shows no description for it
   (`(no description)`, or a paragraph borrowed from a file, marked like
   `(from CLAUDE.md)` or `(from README.md)`): write what
   it owns and what to ask it for to a scratch file, first line the summary,
   and run `agent-mail agents describe < that-file`.
4. **Say in one line** that the repo is on call, and stop. Do not run
   `agent-mail wait`: in a Claude Code session it only prints a notice now.

## How serve delivers

- Each open thread whose turn is this repo's goes to one Claude Code session.
  Later messages in a thread are posted into the session that last wrote in it
  for the repo, while that session runs. A background session that has
  stopped is resumed with the message while its prompt cache is warm.
  Otherwise serve starts a background session in the repo for the thread,
  named `📫 <repo> #<number>`, one thread per session, at most three a round.
- A message posted by serve starts with `agent-mail: new message in #N`. It
  comes from serve, not from another session. Read the thread and answer once
  the current step is done. One starting `agent-mail: closed, with news: #N`
  is a thread closed with a message for this session: read it; no answer is
  expected. Serve posts those only into a session that runs.
- Each message from serve ends by naming the other sessions working in the
  folder, by the names SendMessage takes, with the thread and subject of each
  that serve started. Tell them with SendMessage when your work affects
  theirs. `agent-mail who --here` prints that line at any time.
- Two threads about the same thing get two sessions at first. Those sessions
  agree by SendMessage which of them handles both; the one taking over writes
  in the other thread, and serve posts that thread's later messages to it.
- `claude agents` lists the sessions, and `claude attach <id>` opens one.
  `agent-mail who` lists the ones serve handed threads to.
- `claude --bg` only runs in a folder Claude Code trusts: run `claude` in the
  repo once and accept the question. Until then serve backs off, and
  `agent-mail oncall` shows why; `agent-mail agents` marks the folder. A
  plain folder inside a trusted one is trusted too, but a git repo nested
  inside one is not: run `claude` in the repo itself.
- Nobody sits at a background session. A permission prompt holds it until the
  user opens it with `claude attach <id>`. Once one of serve's sessions has
  waited 2 minutes, serve tells the user with a notification, and `agent-mail
  who` and `agent-mail oncall` show what it waits for while it waits.
- Serve starts sessions in the folder the agent directory gives. A session
  it starts while the directory has no description of the repo is asked to
  write one.
- Serve stops a session it started once it has been idle for 5 minutes,
  even while it holds a thread; never while it runs a background task, waits
  on the user or works. The conversation is kept, and a later message
  resumes it while its prompt cache is warm, or goes to a new session. A
  session started or stopped this way is expected, not a fault.
- In a git repo a background session works in a worktree under
  `.claude/worktrees`, and Claude Code keeps it out of the main checkout.
  When one of serve's sessions goes idle or stops, serve fast-forwards the
  repo's main checkout: to its upstream as the session's push left it
  (serve never fetches), or, in a repo without one, to the session's
  worktree branch once its thread is closed. Only on a branch, with no changes to tracked files
  and no other session working in the main checkout; otherwise `agent-mail
  oncall` says why, e.g. `main checkout 2 behind origin/main: uncommitted
  changes`. Untracked files are never touched.
- Once a stopped session of serve's holds no open thread, serve removes it
  with `claude rm` (the transcript stays for `claude --resume`), unless its
  worktree branch has commits that are in neither the main checkout nor its
  upstream. `agent-mail who` lists such a session as unlanded, e.g.
  `unlanded: branch worktree-x, 2 commits`.
- While a new interactive session starts in the repo, the SessionStart hook
  says nothing of the threads serve delivers, so it doesn't take them on.
- Serve's postmaster, a role and not a session, tells the user by a notice
  from `postmaster` when a thread has waited on the repo more than an hour
  while it is off call, or while serve backs off from it (an untrusted or
  missing folder, `claude` failing) for 15 minutes or 3 failures in a row,
  with the fix. It updates the notice as that changes and closes it once
  the threads are delivered. A thread waiting on a name the directory
  doesn't have goes back to its sender.
- Mail to `postmaster`, such as the user's answer to a notice, goes to
  the agent `agent-mail`; a session serve starts for it is told so.

`agent-mail oncall` shows the state; `agent-mail oncall remove` takes the repo
off call (it stays in the directory), and `wait` won't put it back.

Repo-specific steps for handling mail live in the repo's CLAUDE.md, not here.
If a message asks for something this repo doesn't own, reply saying which repo
does and mail it there if the request is clear.
