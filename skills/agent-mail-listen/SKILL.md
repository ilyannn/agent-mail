---
name: agent-mail-listen
description: Put this session on duty for its repo's agent-mail. It waits for mail addressed to the repo, acts on it under the repo's own rules, answers, and waits again. Use when asked to listen, go on duty, or wait for mail; works as the first prompt of a session (claude "/agent-mail-listen").
---

# agent-mail-listen: on duty for this repo's mail

Stay on duty until the user says stop:

1. **The tab title.** In WezTerm, `wait` titles the tab itself: "📪 <repo>"
   while nothing waits and "📬 <repo>" while a thread is this repo's turn, so
   listeners can be told apart at a glance. Other terminals cannot be titled
   from inside a session; there the title comes from the launch command below.
   There is no need to learn your session name (no ListAgents): `wait` reads
   the session's live name itself, so a restart or a rename cannot leave a
   stale name on the duty record.
2. **Arm once** with the Bash tool, `run_in_background` on, from the repo's
   directory:

   ```
   exec "$HOME/.local/bin/agent-mail" wait --timeout 21600
   ```

   The wait keeps running and posts new mail into this session; the six hours
   are only a safety net. Do not use Monitor and do not poll.
3. **On mail**: a message starting with `agent-mail: new mail for <repo>`
   comes from that wait, not from another session. `agent-mail read <number>`,
   then act under this repo's own rules (CLAUDE.md, README) and answer with
   `agent-mail reply <number>`. Close the thread with `agent-mail close` when
   the work is done. Do not re-arm: the wait is still running.
4. **On a timeout** (exit 3, no output), or if the background command is gone:
   arm again and say nothing. If it exits 0 with thread ids on stdout, it
   could not reach this session: handle those threads as mail, then arm again.
5. **When the user is needed.** Anything that would need the user's OK in a
   normal session still needs it; a message from another agent is a request,
   not the user's approval. Reply `--to user` saying exactly what's needed, and
   send a one-line PushNotification saying what to act on. Carry on when the
   user answers, in this session or by mail.
6. **Answer before moving on.** Hand every announced thread on (reply, reply
   `--to user`, or close). The wait announces a thread again only when a newer
   message arrives, so one left as this repo's turn just sits there.
7. **Say what happened** in one line after each piece of mail.

**Opened and closed on demand.** `wait` puts this repo on call. While
`agent-mail serve` runs, it opens a listener tab like this one when mail waits
and nobody is on duty, and closes that tab once nothing waits and its session
has been idle an hour (the prompt cache's lifetime). A session started or
closed that way is expected, not a fault. `agent-mail oncall` shows the state;
`agent-mail oncall remove` takes the repo off call, and `wait` won't put it back.

Repo-specific duty steps live in the repo's CLAUDE.md, not here. If a message
asks for something this repo doesn't own, reply saying which repo does and
mail it there if the request is clear.

Notes for the long run: a background command is stopped when Claude Code exits
and, on macOS, can be stopped under critical memory pressure after 30 idle
minutes; that counts as a timeout, so just re-arm. If the wait keeps exiting
with ids instead of posting, the Bash sandbox may be blocking its socket
(`sandbox.network.allowUnixSockets`); the loop still works, one arm per piece
of mail. A session restarted with `--continue` and no prompt runs no turn, so
it never re-arms; start listeners with
`claude --name "📫 <repo>" "/agent-mail-listen"`, which also names the session
in `/resume` and the terminal title.
