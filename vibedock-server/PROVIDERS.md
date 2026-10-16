# Data source types

VibeDock shows remaining usage per data source (one provider account each, see *Data sources* in the administration). Which providers are supported depends on whether their usage can be read on a server without a desktop app and without borrowing another program's OAuth client.

| Type | Status | How VibeDock reads it | Shown as |
| --- | --- | --- | --- |
| Codex | supported | Browser sign-in through the Codex CLI in the container; usage endpoint of ChatGPT | 5-hour and weekly window (other plan windows: up to a day short, longer weekly) |
| Claude | supported | Browser sign-in through Claude Code in the container; OAuth usage endpoint | 5-hour and weekly window |
| Cursor | supported for teams | Cursor Admin API (`POST https://api.cursor.com/teams/spend`, Basic auth with an Admin API key) | share of the member's monthly limit, reset at the end of the billing cycle |
| Gemini | supported (Gemini CLI sign-in, Google AI Pro/Ultra or free) | Sign-in through the official Gemini CLI in the container; daily quota of Gemini Code Assist | the model with the least left today, reset at its daily reset |
| Antigravity | possible, not built | official Antigravity CLI (`agy`) sign-in; see below | – |
| Cursor (individual plan) | possible, not built | official Cursor CLI sign-in; see below | – |
| Grok | supported (SuperGrok, X Premium+) | Device-code sign-in through the official Grok Build CLI in the container; credit usage from the Grok Build billing endpoint | share of the weekly (or monthly) credit pool, reset at the end of the period |

## Cursor

Cursor offers no usage interface for an individual subscription. The [Admin API](https://docs.cursor.com/account/teams/admin-api) is for teams: only a team admin can create an Admin API key (cursor.com/dashboard → Settings → Cursor Admin API Keys). VibeDock asks `/teams/spend` for the member entered as *Member e-mail* and uses `totalPercentUsed` where Cursor returns it (tiered self-serve teams); otherwise it divides the member's spend (`overallSpendCents`, or `includedSpendCents` + `spendCents`) by `hardLimitOverrideDollars` or `monthlyLimitDollars`. Without any limit there is no share to show; the log then names the spend. Several of these fields are not in the official documentation yet ([forum](https://forum.cursor.com/t/admin-api-docs-missing-includedspendcents-and-new-teams-usage-metrics/162742)). The billing month starts at `subscriptionCycleStart`.

## Grok

xAI's terminal agent [Grok Build](https://github.com/xai-org/grok-build/blob/main/crates/codegen/xai-grok-pager/docs/user-guide/02-authentication.md) (open source, npm package `@xai-official/grok`, pinned in the `Dockerfile`) signs in with the subscription. *Sign in with Grok* runs `grok login --device-auth` with `GROK_HOME` set to the source's own folder; the page shows the link to `accounts.x.ai` and the code. The CLI stores the sign-in in `auth.json` (bearer token in `key`, with `expires_at`). VibeDock never renews tokens itself: about 15 minutes before expiry it runs `grok models` (lists models, uses no credits), and the CLI renews the sign-in.

Usage comes from `GET https://cli-chat-proxy.grok.com/v1/billing?format=credits` with the header `X-XAI-Token-Auth: xai-grok-cli`, the endpoint Grok Build itself uses; it is not officially documented and may change ([example of another reader](https://github.com/pihme/grok-budget-mcp)). VibeDock shows `config.creditUsagePercent` (missing means nothing used yet) with the reset at `currentPeriod.end` (or `billingPeriodEnd`).

## Gemini

The official [Gemini CLI](https://github.com/google-gemini/gemini-cli) (npm package `@google/gemini-cli`, pinned in the `Dockerfile`) signs in with the Google account. Each source has its own folder as `GEMINI_CLI_HOME`, with settings that select *Login with Google* and file storage of the sign-in. *Sign in with Gemini* starts the CLI in a terminal (`script`) with `NO_BROWSER=true`; the page shows Google's consent link, Google then shows a code on `codeassist.google.com`, which is pasted back into the administration. The CLI stores the sign-in in `.gemini/oauth_creds.json`; VibeDock stops the CLI once that file holds a valid token.

Google access tokens last an hour. VibeDock never renews them itself and never uses the CLI's OAuth client: about four minutes before the expiry it runs `gemini --list-sessions`, which signs in (the CLI's auth library renews the token and saves it) and lists saved chats without sending a prompt, so it spends no quota.

Usage is read like the CLI does it: `POST https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist` names the Code Assist project of the account, `…:retrieveUserQuota` returns one bucket per model with `remainingFraction` and `resetTime`. Neither call spends quota; both are internal and not documented. VibeDock shows the model with the least left as the daily window (`Day`). Accounts that need their own Google Cloud project (some Workspace accounts) are not supported yet.

## Possible, not built yet

Both work the same way as Gemini and Grok: the official CLI signs in inside the container and keeps the token fresh, VibeDock only reads usage. They are not built for now because each adds a large CLI to the Docker image and neither has been tested with a real account. Findings as of October 2026:

**Antigravity** (Google's agent, CLI `agy`). Google publishes a Linux binary per version with a SHA-512 in a manifest (`https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_amd64.json`, e.g. 1.3.5: about 63 MB download, 215 MB unpacked), so it can be pinned like the other clients. Without a keyring it stores secrets in files under `~/.config/antigravity/`. Started without arguments in a terminal (`script`), it offers *Google OAuth*, prints the consent link (also as an OSC 8 hyperlink, so line wrapping does not matter) and asks for the code that `antigravity.google/oauth-callback` shows, the same paste flow as Gemini; this was checked up to Google with a wrong code. `agy models` needs a valid sign-in and spends nothing, so it can renew the token like `grok models`. Usage would come from the same internal Code Assist endpoints (`loadCodeAssist`, `retrieveUserQuota`, and `fetchAvailableModels` with `quotaInfo.remainingFraction` for the Claude and GPT pools; [example reader](https://github.com/tingyi365/agy-quota)). Open: the file in which `agy` keeps its Google token and whether `agy models` itself prints the remaining share; both need one real sign-in.

**Cursor, individual plans.** The official Cursor CLI (`cursor-agent`, `https://cursor.com/install`, versioned packages under `downloads.cursor.com/lab/<version>/linux/x64/agent-cli-package.tar.gz`) signs in through a browser link without a code to paste (`cursor.com/loginDeepControl`, the CLI polls until it completes), like Codex. On Linux it stores the sign-in in `$XDG_CONFIG_HOME/cursor/auth.json`. The usage of an individual plan comes from the dashboard endpoint `GET https://cursor.com/api/usage-summary` with the cookie `WorkosCursorSessionToken=<user id>::<access token>` (`individualUsage.plan.totalPercentUsed`, `billingCycleEnd`; not documented, [notes](https://github.com/noamsto/tmux-og/issues/778)). The CLI package bundles its own Node.js and is about 560 MB unpacked, which is the main reason it is not built. Teams keep using the Admin API above.

Requirements for these and other new types: a documented or at least stable way to read the remaining share and its reset time, credentials that the official client renews on its own (or a long-lived key), and no third-party OAuth client inside VibeDock.
