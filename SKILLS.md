# Skills

Claude Code skills available in this repository. Invoke a skill by typing
`/<skill-name>` (optionally with arguments) in the Claude Code prompt.

## Code quality & review

| Skill | What it does |
|---|---|
| `/code-review` | Reviews the current diff for correctness bugs and cleanup opportunities. Effort levels: `low`/`medium` (fewer, high-confidence findings) → `high`/`max` (broader) → `ultra` (deep multi-agent cloud review). Add `--comment` to post inline PR comments, or `--fix` to apply findings. |
| `/simplify` | Reviews changed code for reuse, simplification, efficiency, and altitude, then applies the fixes. Quality only — does not hunt for bugs. |
| `/security-review` | Runs a security review of pending changes on the current branch. |
| `/review` | Reviews a pull request. |

## Running & verifying

| Skill | What it does |
|---|---|
| `/run` | Launches and drives the app to see a change working (e.g. `./gradlew run`), or to screenshot/confirm it works in the real app. |
| `/verify` | Verifies a code change does what it should by running the app and observing behavior — confirm a fix, test a change manually, validate before pushing. |

## Automation & scheduling

| Skill | What it does |
|---|---|
| `/loop` | Runs a prompt or slash command on a recurring interval (e.g. `/loop 5m /code-review`). Omit the interval to let the model self-pace. |
| `/schedule` | Creates, updates, lists, or runs scheduled cloud agents (cron-style routines), including one-time scheduled runs. |

## Configuration & setup

| Skill | What it does |
|---|---|
| `/init` | Initializes a `CLAUDE.md` with codebase documentation. |
| `/update-config` | Configures the Claude Code harness via `settings.json` — permissions, env vars, hooks, and automated behaviors. |
| `/fewer-permission-prompts` | Scans transcripts for common read-only calls and adds an allowlist to `.claude/settings.json` to reduce permission prompts. |
| `/keybindings-help` | Customizes keyboard shortcuts in `~/.claude/keybindings.json`. |

## Reference

| Skill | What it does |
|---|---|
| `/claude-api` | Reference for the Claude API / Anthropic SDK — model ids, pricing, params, streaming, tool use, MCP, caching, token counting, migration. |
