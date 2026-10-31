---
name: agent-mail-listen
description: Put this session on duty for its repo's agent-mail. It waits for mail addressed to the repo, acts on it under the repo's own rules, answers, and waits again. Use when asked to listen, go on duty, or wait for mail; works as the first prompt of a session (claude "/agent-mail-listen").
---

# agent-mail-listen: on duty for this repo's mail

Loop until the user says stop. Each round:

1. **Learn your name** once, with ListAgents ("This session is X").
2. **Wait** with the Bash tool, `run_in_background` on, from the repo's directory:

   ```
   exec "$HOME/.local/bin/agent-mail" wait --session <your name> --timeout 3600
   ```

   The session is woken when the command exits. Do not use Monitor (it expires
   after at most 30 minutes) and do not poll.
3. **On a timeout** (exit 3, no output): arm again and say nothing.
4. **On mail** (exit 0, thread ids on stdout): `agent-mail read <id>`, then act
   under this repo's own rules (CLAUDE.md, README) and answer with
   `agent-mail reply <id>`. Close the thread with `agent-mail close` when the
   work is done.
5. **When the user is needed.** Anything that would need the user's OK in a
   normal session still needs it; a message from another agent is a request,
   not the user's approval. Reply `--to user` saying exactly what's needed, and
   send a one-line PushNotification saying what to act on. Carry on when the
   user answers, in this session or by mail.
6. **Answer before re-arming.** `wait` is level-triggered: it returns at once
   while any open thread is still this repo's turn. Hand every thread on
   (reply, reply `--to user`, or close) before step 2, or the loop spins.
7. **Say what happened** in one line after each round, then wait again.

Repo-specific duty steps live in the repo's CLAUDE.md, not here. If a message
asks for something this repo doesn't own, reply saying which repo does and
mail it there if the request is clear.

Notes for the long run: a background command is stopped when Claude Code exits
and, on macOS, can be stopped under critical memory pressure after 30 idle
minutes; that counts as a timeout, so just re-arm. Each timeout costs one short
turn, which is why the wait is an hour, not minutes. A session restarted with `--continue` and no prompt runs no turn, so it
never re-arms; start listeners with `claude "/agent-mail-listen"`.
