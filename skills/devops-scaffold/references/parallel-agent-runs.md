# Parallel & Long-Running Agent Runs

Reference notes for running one or many coding agents against a Spring Boot project without
them stepping on each other or on your working tree. Not generated into a project — this is workflow
guidance you (or a meta-skill) reach for when a job is big enough to run in the background or fan out
across branches.

---

## Worktree pooling (fast, isolated agents)

The problem: a fresh agent on a cold clone re-downloads the whole `.m2` and rebuilds from scratch —
minutes of wasted setup before it does any real work. Running several at once, they collide on the
same working tree and branch.

The pattern (from git worktrees, the way `treehouse` automates it):

- **One worktree per agent.** `git worktree add ../wt-<name> <base>` gives each agent an isolated
  checkout. No branch collisions, no dirty-tree conflicts.
- **Pool and reuse, don't discard.** Keep a small set of worktrees around with their `target/` and a
  warm `.m2` intact. A returning agent starts warm instead of re-resolving dependencies. Point Maven
  at a shared local repo (`-Dmaven.repo.local=~/.m2/repository`) so the cache is shared, not copied.
- **Detached HEAD off the latest default branch** sidesteps branch-name collisions entirely; name the
  branch only when there's something worth pushing.
- **Track who's using what.** Before reusing a worktree, check no process is live in it — a stale lock
  file or a `git worktree list` scan is enough for a personal setup.

For Spring projects the win is concrete: warm `.m2` + preserved `target/` turns a ~3 min cold
`./mvnw verify` into seconds, so parallel agents are actually worth spawning.

---

## Guardrails for a long autonomous run

When you point a coding agent at a big objective and walk away, bound it so it can't run away.
Borrowed from overnight-orchestrator practice:

- **Token budget.** Cap total spend so a stuck loop can't burn the account (`--max-tokens`-style hard
  stop, checked mid-iteration).
- **Stop condition.** End on a natural-language goal ("stop when all tests pass and coverage ≥ 80%"),
  not just a fixed iteration count.
- **Commit per iteration.** One git commit per successful step, so you can cherry-pick the good work
  and `git reset --hard` a bad iteration cleanly. Never let an hour of work sit uncommitted.
- **Persistent notes.** Have the agent append progress to a scratch `notes.md` each pass, so context
  carries across iterations without re-deriving it — and so a resumed run picks up where it stopped.
- **Backoff on hard errors, retry on soft ones.** An agent-reported "I couldn't do X" retries
  immediately; an environment/tooling crash backs off exponentially before retrying.

Pair this with a ship gate as the final decision: a long run produces branches, then a review skill
or human decides which are actually PR-ready.
