# Tempo Branch Tracker

IntelliJ plugin that tracks **active IDE time per Jira ticket**, based on the checked-out Git branch, and logs it to **Tempo (Jira Cloud)**.

`feature/ART01DEV10-4466-this-is-a-test` logs time on `ART01DEV10-4466`. The time is rounded **up to the next quarter hour** per ticket per day.

## What it does

- **Tracks active time only.** The clock runs while you use IntelliJ. It stops after *N* minutes with no keyboard or mouse input (5 by default), and optionally as soon as IntelliJ loses focus.
- **Branch → ticket.** It uses a configurable regex, with an optional allow-list of project keys. For branches without a key (`develop`, `fix/login`), use *Tools → Tempo Tracker → Assign Jira Ticket to Current Branch…*. Time on such branches is kept as "(no ticket)", and you can type a ticket into that row later.
- **Switching branches.** Time is credited to the old branch at the moment you switch.
- **Review window.** The *Tempo Tracker* tool window (right side) shows a table per day, with these columns:
  - **Log:** include or skip the entry.
  - **Ticket:** editable until submitted. Typing an existing ticket merges the two rows.
  - **Summary:** the issue summary from Jira.
  - **Tracked:** raw active time.
  - **To log:** rounded time. You can overwrite it (`1h 30m`, `45m`, `1.5h`, `1:30`); clear it to go back to the rounded value. ✎ marks a manual value.
  - **Description:** uses the template by default (`{key}`, `{summary}`).
  - **Status:** Not logged / Changed / ✓ Logged / errors.

  The header shows what's tracked, what will be logged, and what's already in Tempo for that day, from any source.
- **Submitting updates instead of duplicating.** Every entry remembers its Tempo worklog id. If you resubmit after more work, or after editing an entry, the existing worklog is updated. If you untick an entry that was already logged, its worklog is removed.
- **Two modes** (Settings → Tools → Tempo Tracker):
  - *Review and submit myself.* At the daily time (17:00 by default) you get a reminder if something isn't logged yet.
  - *Submit automatically.* At the daily time, today and the last 7 days are synced. Leftovers from previous days are also synced when the IDE starts.
- **Worklog layouts.** Set in Settings → Tools → Tempo Tracker → Worklogs:
  - *First activity:* one worklog per ticket, starting when you first worked on it.
  - *Back to back:* one worklog per ticket, chained from your first activity.
  - *Real timeline:* every quarter hour goes to the ticket you spent most of it on, and neighbouring quarters of the same ticket form one worklog. So 10:00–11:03 on ABC-01, then support until 12:00, then ABC-01 from 13:00 to 15:00 gives ABC-01 10:00–11:00 and 13:00–15:00, plus support 11:00–12:00. Other rules:
    - Detours shorter than half a quarter hour are absorbed.
    - Idle gaps up to 15 minutes within the same ticket are filled.
    - Manually added time and edited durations go into the first free slots.
- **Rituals.** Recurring meetings such as a standup, retro or planning, set up in Settings → Tools → Tempo Tracker → Rituals. Each has a name, ticket, days (`Mon,Wed-Fri`, `ma,wo,do,vr`), start time and duration.
  - They're added as fixed-time rows only on days you've worked in IntelliJ, so days off stay empty.
  - IDE time isn't counted while a ritual is running.
  - With back-to-back on, dev time flows around rituals. A ticket that would overlap one is split into two worklogs, e.g. 08:30–09:30 and 09:45–10:15.
  - If a meeting is cancelled, untick its row.
- **Status bar.** Shows `ART01DEV10-4466 · 1h 05m`, plus `(idle)` or `(paused)` when applicable. Click it to open the tool window. You can pause tracking from the toolbar or the Tools menu.

Tokens are stored in the IDE password safe (macOS Keychain). Tracked time is stored in `tempo-tracker-time.xml` in the IDE config folder. It isn't synced between machines, and entries older than 90 days are pruned.

## Setup

1. **Jira API token:** https://id.atlassian.com/manage-profile/security/api-tokens
2. **Tempo API token:** in Jira, go to *Apps → Tempo → Settings → API Integration → New Token*. It needs at least the scope to view and manage worklogs.
3. In IntelliJ, go to *Settings → Tools → Tempo Tracker*. Fill in your site URL (`https://<company>.atlassian.net`), email and both tokens, then click **Test Connection**.

## Build and run

Requires IntelliJ IDEA **2026.2** or later (the plugin is built with Kotlin 2.4, which ships with 2026.2). The first build downloads Gradle, a JDK 21 if needed, and the IntelliJ SDK, which is about 1–2 GB.

```bash
./gradlew runIde        # starts a sandbox IntelliJ with the plugin installed
./gradlew test          # unit tests for branch parsing, rounding, duration parsing
./gradlew buildPlugin   # → build/distributions/tempo-tracker-0.1.0.zip
```

To install the plugin into your own IntelliJ, go to *Settings → Plugins → ⚙ → Install Plugin from Disk…* and pick the zip.

If you're on an older IntelliJ, change `intellijIdea("2026.2.2")` and `sinceBuild` in `build.gradle.kts` to your version, and use the Kotlin version bundled with it (2025.3 → Kotlin 2.2.20, 2026.1 → 2.3.20).

## Code map

| Package | What |
|---|---|
| `core/ActivityTracker` | 30-second ticks, idle and focus detection; credits time to the ticket checked out at the start of each interval |
| `core/BranchResolver`, `TicketParser` | branch → ticket |
| `core/TimeStore`, `TimeEntry` | persisted entries, branch assignments |
| `core/LogPolicy` | rounding, minimum, description template, what needs to change in Tempo |
| `core/SubmitService` | create/update/delete worklogs, daily reminder and auto-submit |
| `api/` | Jira Cloud REST v3 (issue key → id, your accountId), Tempo REST v4 |
| `ui/` | tool window, table, status bar widget |
| `settings/` | settings page, password-safe secrets |

## Ideas for next steps

- Show an editable start time per entry, or split an entry over the day.
- Pull existing Tempo worklogs into the table, so you can see time logged from elsewhere per ticket.
- Configure Tempo work attributes (e.g. account or billable) per project.
- Sync time across machines through the worklogs themselves instead of local XML.
