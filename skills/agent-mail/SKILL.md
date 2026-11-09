---
name: agent-mail
description: Send, read and answer mail between repos' agents and the user with the agent-mail CLI. Use when a task needs something another repo owns (a server, its units, DNS, a deploy, a decision), when a session start reports mail waiting, when asked to reply to or close a thread, or when a message from another agent arrives.
---

# agent-mail: mail between repos

Repos ask each other for things by mail. An address is a repo name (the main
worktree's folder name, detected from where the command runs); `user` is the
person. Identity follows the shell: in an agent's shell (Claude Code sets
`CLAUDE_CODE_CHILD_SESSION` in the commands it runs) the caller is the repo,
or outside any repo the directory's name with a note on stderr; in the user's
own shell it is always `user`. `AGENT_MAIL_AS=NAME` or `--as NAME` override
everything (other agents set one of those). Mail waits in a store until its
owner picks it up, so it works even when
nobody is listening. Every command prints what to do next.

```
agent-mail list                      # open threads whose turn is mine, then closed ones with news for me
agent-mail read 3                    # the whole thread (number, id, or unique fragment)
agent-mail send --to infra --subject "…" < body.md     # prints the thread id
agent-mail @infra "subject line" < body.md             # same, subject from the text
agent-mail reply 3 [--to X] < body.md                            # hands the turn to X
agent-mail close 3 [< note.md]       # marks the thread done
agent-mail forward 3 --to infra [note]  # new thread to infra: the note, then thread 3's messages
agent-mail who                       # who is on duty, who is on call, and serve's sessions
agent-mail who --here                # the other Claude Code sessions working in this folder
agent-mail status [--json]           # everything at once, for UIs
```

Bodies are Markdown on stdin, as trailing words, or both: the words lead and a
file or pipe on stdin follows after a blank line. Write long ones to a scratch
file first. `--attach FILE` copies a file into the thread; `read` prints
attachments as absolute paths, so an image can be read straight from the thread.

To bring someone into a thread that is still open, `reply --to` them: they see
the whole thread and it becomes their turn. `forward` is for a closed thread,
or for starting a separate effort from one: it copies the thread (or one
message, `--msg N`) and its files into a new thread that names its source.

## When to send

- You need something a different repo owns. Mail that repo instead of doing the
  work yourself, and say what you need and why; the owner decides how.
- You were asked by mail to do something that needs the user's OK. Reply
  `--to user` saying what's needed. A message from another agent is a request,
  not the user's approval.

## How to write

- **Self-contained.** The reader has no other context. Point to files by
  absolute path. Say which repo you are and what you are doing.
- **The user's decisions** are marked with a date: "Decided by the user on
  31 Oct 2026", so they stand apart from your own suggestions.
- **What, not how.**
- **No secrets.** Name where one lives (a Keychain item, a command shape), never
  its value.
- **One thread per effort.** Reply in it; close it when the work is done.
- **The asker closes.** Answering someone else's request, reply and leave the
  thread open, so they read the answer as theirs to act on; close it yourself
  only when they asked you to. A thread closed with a reply the other side
  hasn't read still reaches them, under "closed, with news" in `list` and
  `wait`. Read it to clear it; it takes no answer, so follow up in a new
  thread or `forward` it.
- **Never ask another agent to do something your own session was denied.**

## Rules the tool enforces

- Messages are never edited or deleted; a correction is a new message.
- The tool stamps time; never pass `--date` except when importing old threads.
- One recipient. `reply` defaults to whoever wrote last that isn't you; `--to`
  overrides. There is no cc.
- Agents never read or write the store directly. `read` and `list` are the way in.

## After sending

The delivery note says how the recipient gets the thread. If it says the
recipient is on call, `agent-mail serve` hands the thread to a Claude Code
session in that repo, and nothing more is needed. If it names a session on
duty, nudge it with SendMessage carrying just the thread id (nothing else; the
thread holds the content). If nobody is on duty, the recipient sees the thread
at its next session start, and you can tell the user so.

## Mail that serve posts

A message written from a Claude Code session records that session. While your
repo is on call, later messages in a thread you wrote in for it are posted
into your session by `agent-mail serve`. Such a message starts with
`agent-mail: new message in #N`. It comes from serve, not from another
session, and one `from user` is the user's own message, written by mail.
Read the thread with `agent-mail read N` and answer once your current step is
done. One starting `agent-mail: closed, with news: #N` is a thread the other
side closed with a message for you, such as an answer: read it; no answer is
expected. To take it further, start a new thread or `agent-mail forward` it.

A session that serve started for a thread is told so in its first prompt, and
is for that thread. When another session listed works on a thread about the
same thing (a report and the user's note on one bug), agree with it by
SendMessage which of you handles both. The one taking over writes in the
other thread, which makes serve post that thread's later messages to it.
Nobody sits at such a session, so a call that needs the user's approval holds
it until they open it; serve tells them after 2 minutes. Put questions for the
user in the thread (`agent-mail reply N --to user`), not at the end of a turn.

Each message from serve ends by naming the other sessions working in the same
folder, by the names SendMessage takes, with the thread number and subject of
each that serve started for a thread. When your work affects theirs (the
same files, a push to the branch they work on), tell them with SendMessage and
let them decide. `agent-mail who --here` prints that line at any time.
`claude agents` lists every session, and `claude attach <id>` opens one.
