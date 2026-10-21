# ALV-11: WhatsApp list delivery through Atlas / Hermes

Research status: **partially verified**. The Alva repository and current
first-party Hermes documentation were inspected. The configured `atlas` SSH
alias resolves to a host, but a read-only SSH inventory failed with
`Permission denied (publickey)`. Consequently, no statement below claims that
Hermes, a particular Hermes version, its gateway, or a WhatsApp channel is
installed or configured on Atlas. This is a concrete design for ALV-12/13 plus
the exact inventory still needed before wiring delivery.

## Finding

Alva should produce a deterministic, read-only snapshot from its own database;
Hermes should deliver that text through its already-configured WhatsApp
adapter. Do not ask the Hermes agent to infer the list from page text or to
change shopping state. This is technically supported by the current Hermes
project's `no-agent` scheduled scripts: script stdout is delivered verbatim,
without LLM/provider use. Hermes also documents a `hermes send` CLI for
one-shot delivery. Which path actually works for Atlas depends on the
installed version and whether its channel is Baileys or WhatsApp Cloud; that
could not be checked over SSH.
([Hermes no-agent cron](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/cron.md#no-agent-mode-script-only-jobs),
[Hermes CLI](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/reference/cli-commands.md#hermes-send))

For a predictable local wall-clock schedule, prefer an Atlas **systemd
calendar timer**, if Atlas is a systemd host and Hermes exposes a working
standalone send path for its installed channel. Configure Atlas's local timezone
as `America/Fortaleza`, catch up at most one missed run after boot, and log each
invocation. If the actual Hermes Baileys adapter can only send through its
live gateway, use the Hermes no-agent scheduler with `--deliver whatsapp`;
keep the gateway enabled at boot and use a cadence schedule (15 or 30 minutes)
only after confirming cadence semantics. Avoid an unverified daily local-time
Hermes cron schedule: the official Hermes issue tracker currently shows an
open report for recurring jobs drifting to UTC after their first run in a
non-UTC timezone.

No schedule, WhatsApp destination, empty-list behavior, or repeat-versus-change
delivery rule is treated as a settled product decision. The proposed
15/30-minute cadence below is an option for the owner to choose, not a verified
preference.

## Verified facts

### Alva repository

- This is Rails 8.1.4, with Solid Queue configured as the production job
  adapter. The deploy config starts Solid Queue inside Puma (`SOLID_QUEUE_IN_PUMA`),
  and Puma loads its Solid Queue plugin from that variable.
- The Rails application timezone is `America/Sao_Paulo`. It matches
  `America/Fortaleza`'s current UTC-3 offset, but use the explicitly requested
  Fortaleza zone at the integration boundary rather than relying on this
  coincidence.
- There is no existing integration/API route. The current route set has the
  shopping list UI, PDF export, health, PWA, list-item mutations, and shopping
  trips.
- `ListItem.on_list` means `shopping_trip_id IS NULL`; pending rows are
  separately selectable with `.pending`. `Household` supplies the list's
  category associations. Therefore the export query must be exactly the live
  pending set (`ListItem.on_list.pending`) and must group by category. It must
  not include the purchased rows still visible on the live screen or archived
  rows from a `ShoppingTrip`.
- The app has no user login by product decision. A machine-to-machine
  integration credential is still needed for the new endpoint; it must not be
  implemented as a household login or browser session.

### Hermes upstream (public documentation/source checked 2026-10-21)

- Hermes upstream documents two distinct WhatsApp routes: `hermes whatsapp`
  pairs a Baileys personal-account bridge using a linked-device QR flow; and
  `hermes whatsapp-cloud` configures Meta's Cloud API, which needs a Business
  account and public webhook. The Baileys linked-device session stores account
  credentials under Hermes home and must be protected like a password.
  ([Baileys WhatsApp guide](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/messaging/whatsapp.md),
  [Cloud API guide](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/messaging/whatsapp-cloud.md))
- Baileys' documented modes include a dedicated bot number and personal
  self-chat. The wizard and pairing flow are interactive and should not be run
  as part of ALV-11 delivery automation.
- Hermes documents `hermes send --to <target>` and `hermes send --list`.
  Its current CLI reference says no gateway is required for WhatsApp Cloud API
  and other bot-token platforms, while adapters needing a persistent live
  process do require the gateway. The reference does not claim standalone
  support for the Baileys bridge. Therefore do not assume `hermes send` alone
  works for Atlas's channel.
  ([Hermes CLI reference](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/reference/cli-commands.md))
- Hermes' current scheduled-script mode (`no-agent`) runs a script at the
  scheduled time, sends trimmed stdout verbatim, suppresses delivery on empty
  stdout, and raises a delivery error on nonzero exit or timeout. It bypasses
  the model, provider token and fallback chain. Hermes says these scripts must
  live under `$HERMES_HOME/scripts/`; the subprocess environment is sanitized
  and secrets must be explicitly passed through by the profile config.
  ([Hermes scheduled tasks](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/cron.md#no-agent-mode-script-only-jobs))
- Hermes schedules are driven by the gateway daemon, which ticks every 60
  seconds. Current `hermes cron status` / `list` report scheduler heartbeat,
  overdue jobs, last tick, and delivery errors. A stopped gateway is therefore
  a delivery dependency for native Hermes cron.
  ([Hermes scheduled tasks](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/cron.md#how-it-works))
- A first-party Hermes issue remains open for a reproducible timezone bug in
  Hermes 0.21.0 (issue opened 2026-09-05): after a run, a local-time recurring
  schedule may persist its next wall-clock time as UTC and fire offset-hours
  late. The issue reports a 2-hour example in Europe/Warsaw. It is not evidence
  that Atlas uses 0.21.0, but it makes local wall-clock scheduling unsafe to
  assume across versions. Verify installed behavior before relying on it.
  ([Open Hermes issue #103904](https://github.com/NousResearch/hermes-agent/issues/103904))
- Official systemd documentation defines `OnCalendar=` as realtime
  wall-clock scheduling (the timezone defaults to the host's current timezone), and says
  `Persistent=true` runs a missed calendar activation once after timer restart;
  multiple missed intervals collapse to a single activation. Timers are
  subject to `AccuracySec` (one minute by default). `systemd-analyze calendar`
  validates the schedule syntax.
  ([systemd.timer](https://www.freedesktop.org/software/systemd/man/260/systemd.timer.html),
  [systemd.time](https://www.freedesktop.org/software/systemd/man/260/systemd.time.html))
- Solid Queue's recurring scheduler is an option within Rails: recurring
  tasks live in `config/recurring.yml`; its scheduler enqueues due jobs, while
  workers execute them. Current Alva already runs this supervisor with Puma.
  This couples scheduling to app availability/deployment and is less isolated
  than a host timer for a Hermes-side delivery script.
  ([Solid Queue recurring tasks](https://github.com/rails/solid_queue/blob/main/README.md#recurring-tasks))

## Proposed ALV-12/13 contract

### ALV-12: snapshot endpoint

Add a read-only endpoint such as `GET /integrations/v1/shopping-list` returning
only the live pending list, deterministically ordered and grouped by the
existing fixed category order. Suggested response:

```json
{
  "generated_at": "2026-10-21T09:30:00-03:00",
  "timezone": "America/Fortaleza",
  "fingerprint": "<sha256 of canonical payload>",
  "categories": [
    { "name": "Hortifruti", "items": ["Banana"] },
    { "name": "Outros", "items": ["Papel toalha"] }
  ],
  "item_count": 2
}
```

Return `200` with an empty `categories` array and `item_count: 0` when the
pending list is empty. Keep the read path side-effect free: no item toggles,
`ShoppingTrip`, broadcast, history mutation, or persistent “sent” marker.
Generate a stable fingerprint from the canonical category/item payload (not
from `generated_at`) so delivery code can distinguish content changes from
clock changes. `ETag` may expose the same opaque fingerprint for conditional
GETs. Avoid returning item IDs, user data, archive history, or internal model
attributes.

Protect the endpoint with a dedicated random bearer token in the app's deploy
secret environment and the Atlas caller's secret store. Scope it to this one
read endpoint; reject missing/invalid credentials with `401`, avoid token
values in logs, and use HTTPS over the private Tailscale route plus a
tailnet-only listener/firewall rule. Rails provides
`authenticate_or_request_with_http_token`; use a constant-time comparison for
the configured token. This gives the integration a non-login, server-only
access path. Do not add the token to source, URLs, browser storage, WhatsApp,
logs, or the Hermes prompt. Rate-limit failed requests and return no diagnostic
details that reveal list contents.
([Rails HTTP token helper](https://api.rubyonrails.org/v8.1.3/classes/ActionController/HttpAuthentication/Token/ControllerMethods.html),
[Rails security guide](https://guides.rubyonrails.org/security.html),
[Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve))

### ALV-13: rendering and delivery adapter

Render plain Portuguese text from the JSON snapshot with a stable title,
local timestamp and category headings, e.g. `Lista Alva · 21/10 09:30`, then
one `• item` line per pending item. Keep generation deterministic. Deliver
without invoking an LLM. On the confirmed installed interface, the implementation
can be either:

1. a Hermes no-agent script that fetches Alva and emits the rendered content,
   with Hermes cron delivering stdout to the known WhatsApp target; or
2. a systemd service script fetching Alva and invoking the confirmed Hermes
   send interface, scheduled by a systemd timer.

Do not publish or create a WhatsApp connection. Do not take the target from
untrusted payload data: configure the destination explicitly in the scheduled
job/service. Check current WhatsApp channel behavior and outbound limits in the
installed Hermes source/docs before implementing long-list splitting. Hermes'
Cloud adapter documents 4096-character chunking for long messages, but that
does not validate identical behavior for Atlas's unconfirmed Baileys version.

### Scheduling choice

| Option | Timezone and powered-off PC | Observability and trade-off |
| --- | --- | --- |
| Hermes native cron | Requires running gateway. Current docs use gateway ticks; upstream has an open local-time recurrence bug. Cadence schedules may avoid wall-clock DST/offset semantics but need verification. | `hermes cron status/list/runs` show heartbeat, overdue work and delivery outcome. No-agent avoids LLM cost. Best fit if Baileys can only deliver through the gateway. |
| systemd timer | On an Atlas Linux/systemd host, use a timezone-qualified `OnCalendar` expression. `Persistent=true` catches up one missed activation after downtime; it does not replay every missed interval. | `systemctl list-timers`, unit state and journal give host-level evidence. Requires confirmed standalone sender or a safe gateway-mediated send. Specify `AccuracySec` if tighter than the one-minute default matters. |
| Alva/Solid Queue recurring job | Rails config time zone is currently São Paulo; app deployment/restarts and queue worker health determine the schedule. | Solid Queue has durable jobs and retries but would couple the feature to Alva's app/queue and require an outbound Hermes bridge from the app. Least attractive unless app-side delivery becomes a product requirement. |

For the suggested polling cadence, **15 or 30 minutes** are both technically
reasonable only if delivery is not blindly repeated at every poll. A good
default proposal is poll on one selected cadence, send the current snapshot
when the canonical pending-list fingerprint changes, and optionally send a
separate reminder at a user-chosen wall-clock time. These are two different
behaviors; the owner must decide whether they want change notices, repeated
reminders, or both. If the request is instead a snapshot every 15/30 minutes,
confirm that frequency to avoid unsolicited WhatsApp volume. User decisions
still required: cadence, destination, behavior for empty lists, and repeat vs
change-only.

## Delivery correctness, edge cases, and operations

- **February and month end:** use a recurring interval or an explicit calendar
  rule, not logic that assumes every month has the same days. For a calendar
  schedule, validate both leap-year February and the 28/29-to-1 transition in
  the configured `America/Fortaleza` timezone. If a user chooses “last day of
  month,” define it as a calendar event or implement a daily check for
  `Date.current.end_of_month?`; do not encode day 31 and expect short months to
  match.
- **Empty list:** fetch and report the empty state. Proposed default is no
  WhatsApp message (no-agent stdout empty), but that is a product choice; an
  explicit “lista vazia” message can be selected instead.
- **PC off / late run:** a host timer with `Persistent=true` produces one
  catch-up after boot; it can send a freshly generated current snapshot. Include
  its generation time and preferably a “atualizada agora” timestamp, rather
  than replaying a stale pre-shutdown body. Hermes cron's behavior while its
  gateway is down is version-dependent; do not promise catch-up until tested
  on Atlas.
- **Idempotency:** use a persistent fingerprint of the last successfully
  confirmed content and an atomic lock/state update to prevent overlapping
  work. Mark delivered only after a positive send acknowledgement. Network
  timeout after WhatsApp accepted a message is ambiguous: a retry can duplicate
  a message, while suppressing retry can lose one. Do not promise exactly-once
  delivery; Hermes documents that a timed-out standalone send may still have
  landed. Expose the fingerprint/run timestamp and record send outcome for
  operator review.
- **Retries:** retry fetch/network `5xx` and timeout failures with bounded
  backoff (for example 3 attempts at 1, 5, and 15 minutes), but do not retry
  `401`/`403` as if transient. Retain the latest snapshot fingerprint until a
  confirmed send. Alert/log a final error without printing credentials or the
  whole list. Check how the selected Hermes adapter classifies its own failures
  before choosing the caller's retry policy to avoid multiplicative retries.
- **Message size:** one list may exceed a channel's text limit. Keep the JSON
  endpoint complete; split output only at item/category boundaries, number all
  parts, and include the same run/fingerprint in each part. A partial send is
  a partial delivery and retry may duplicate earlier parts. Confirm exact
  maximum and Hermes chunking for the installed adapter first; Cloud's 4096
  character behavior is not a Baileys guarantee.
- **Availability and monitoring:** keep Hermes gateway, WhatsApp bridge/session,
  and Atlas online. Report last successful fetch, last acknowledged delivery,
  last error, and current scheduler health. A failed snapshot call should never
  be turned into a plausible empty list. A `401`, timeout, or malformed JSON
  exits nonzero, leaves the content fingerprint unadvanced, and is visible in
  Hermes/systemd logs.

## Cost, limits, and integration risk

- Hermes no-agent and `hermes send` routes do not incur LLM inference for the
  message body. Baileys uses a linked personal or dedicated WhatsApp account;
  it avoids Meta Cloud setup but has linked-session/account-policy risk and
  needs the persistent gateway bridge. A dedicated bot number is the upstream
  documented recommendation for lower personal-account risk.
  ([Hermes Baileys guide](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/messaging/whatsapp.md#two-modes))
- WhatsApp Cloud requires Meta Business setup, public inbound webhook
  infrastructure and API credentials. Free-form scheduled sends can fail
  when no inbound message has opened the 24-hour service window; Hermes docs
  say template messages outside this window are not implemented.
  ([Hermes Cloud API limitations](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/messaging/whatsapp-cloud.md#known-limitations))
  ([Hermes Cloud API limitations](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/messaging/whatsapp-cloud.md#known-limitations))
  This is an
  unnecessary new cost/ops burden if the existing Atlas account is already
  Baileys, but actual Atlas mode is unknown.
- I did not estimate Meta Cloud fees: the active rate card and recipient
  market/category would need to be checked before choosing that route. Hermes
  no-agent avoids model inference cost either way; its reliability and platform
  costs are separate from LLM usage.
- There is no app-level user login. That makes a protected read-only endpoint,
  a tailnet/private network boundary, and a dedicated server token mandatory
  before any automatic caller is enabled. A compromised token reveals only
  pending shopping names if the endpoint stays narrowly scoped; it must not
  grant mutation routes.
- This branch must remain a research-only change. No app route, credential,
  WhatsApp pairing, Hermes schedule, service, timer, gateway restart, or message
  was created here.

## Atlas inventory still required

The only Atlas-specific evidence available is that the local SSH config
contains an exact `Host atlas` alias. The command
`ssh -o BatchMode=yes -o ConnectTimeout=8 atlas ...` failed with
`Permission denied (publickey)`. No private-key files, Hermes credentials,
WhatsApp contact information, or session data were printed or inspected. A
human with access should run a read-only inventory after SSH access works and
share only derived facts:

1. OS/distribution and whether systemd is PID 1; `date`, `timedatectl show
   --property=Timezone --property=NTPSynchronized`.
2. `command -v hermes`; `hermes --version`; Hermes CLI help for
   `whatsapp`, `whatsapp-cloud`, `send`, and `cron`.
3. Redacted status only: whether gateway/scheduler is running, channel kind
   (Baileys vs Cloud), whether a channel is configured/connected, and whether
   a destination can be selected. Do not print phone numbers or the destination
   ID into this research report.
4. Read-only service/timer inventory and current gateway service ownership;
   do not activate, restart, install, pair, or dump `.env`, config secret
   values, WhatsApp session files, or key material.
5. If these facts show Hermes version/channel and a safe sender, check the
   installed Hermes source for `hermes send`/cron support and verify a local
   dry-run that cannot transmit. A real WhatsApp message requires explicit
   later authorization.

## First-party sources

- Hermes WhatsApp Baileys docs (pairing, modes, session security, gateway):
  [NousResearch/hermes-agent — whatsapp.md](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/messaging/whatsapp.md)
- Hermes WhatsApp Cloud docs (required Meta setup, outbound window, limitations):
  [NousResearch/hermes-agent — whatsapp-cloud.md](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/messaging/whatsapp-cloud.md)
- Hermes scheduled tasks (gateway tick, no-agent scripts, delivery, credential
  passthrough, timeout/observability):
  [NousResearch/hermes-agent — cron.md](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/cron.md)
- Hermes CLI `send` command and sender limitations:
  [NousResearch/hermes-agent — cli-commands.md](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/reference/cli-commands.md)
- Hermes public scheduler implementation (the upstream `main` source checked
  for job parsing and scheduler state; this is not evidence about Atlas's
  installed build):
  [cron/jobs.py](https://github.com/NousResearch/hermes-agent/blob/main/cron/jobs.py),
  [cron/scheduler.py](https://github.com/NousResearch/hermes-agent/blob/main/cron/scheduler.py)
- Current Hermes local-time cron defect report (open as checked 2026-10-21):
  [NousResearch/hermes-agent issue #103904](https://github.com/NousResearch/hermes-agent/issues/103904)
- systemd timer wall-clock, accuracy, persistence and missed-event semantics:
  [systemd.timer, systemd 260 manual](https://www.freedesktop.org/software/systemd/man/260/systemd.timer.html)
- Solid Queue recurring scheduler and job processing:
  [rails/solid_queue README](https://github.com/rails/solid_queue/blob/main/README.md)
- Rails bearer-token controller helper:
  [ActionController HTTP token authentication (Rails 8.1)](https://api.rubyonrails.org/v8.1.3/classes/ActionController/HttpAuthentication/Token/ControllerMethods.html)
- Rails application timezone and SSL configuration:
  [Configuring Rails Applications](https://guides.rubyonrails.org/configuring.html),
  [Securing Rails Applications](https://guides.rubyonrails.org/security.html)
