# PDCATimer

A standalone Paper plugin that turns lecterns into PDCA timers, so kids can practice setting goals, checking in mid-stream, and reflecting without touching chat commands.

## How It Works

1. Write a book whose first page starts with `[PDCA]` (add `[option gauge1]` or `[option gauge2]` on its own line to choose the display style).
2. Add one line per step, for example:
   ```
   [PDCA]
   [1]30m: First Half
   [2]2m: First Half Review
   [3]30m: Second Half
   [4]5m: Today's reflection
   ```
   - `[]` number defines the order.
   - Durations support `s` (seconds), `m` (minutes, default), and `h` (hours).
   - The label after the colon is optional; a default like `Step 1` is used when omitted.
   - `[option gauge1]` (default) shows a per-step countdown; `[option gauge2]` stacks the whole schedule, colouring each segment by step.
   - You can also restrict who sees the timer with `[audience ...]` / `[user ...]`:
     * `[user nearby]` (default) — viewers within the configured radius.
     * `[user all]` — everyone online, regardless of distance.
     * `[user none]` — hide the timer completely.
     * `[user Alice Bob]` — only players whose names are listed (case-insensitive).
3. Right-click a lectern with the book (or flip to page 1) and the timer starts automatically.
4. Players within the configured radius (and anyone who has been near the lectern since it started) receive:
   - A title + sound when each step begins.
   - An action-bar warning and chime before the step ends (default 5 minutes).
   - A final “Review” reminder and completion sound once all steps finish.
5. Remove the book or break the lectern to cancel the schedule.

## Configuration (`config.yml`)

```yaml
pdca:
  enabled: true          # Toggle the feature entirely.
  radius: 10.0           # Blocks around the lectern to notify.
  warn_before: 5m        # How long before a step ends to warn players.
  title:
    fade_in_ticks: 10
    stay_ticks: 60
    fade_out_ticks: 10
  sounds:
    start: ENTITY_EXPERIENCE_ORB_PICKUP
    warn: BLOCK_NOTE_BLOCK_BELL
    end: UI_TOAST_CHALLENGE_COMPLETE
```

Run `/pdcetimer reload` (requires `pdcetimer.admin`, default OP) to re-read the configuration.

## Building

This project uses Gradle. To compile the plugin JAR:

```bash
./gradlew build
# Output: build/libs/PDCATimer-<version>.jar
```

_(You may need network access so Gradle can download the Paper API and plugins.)_

## Hangar Publishing

- GitHub Actions workflow `.github/workflows/publish.yml` builds the plugin and uploads it to Hangar whenever `main` is updated or a `v*` tag is pushed.  
- Add a repository secret named `HANGAR_API_TOKEN` that contains a Hangar API key with publish rights for this project.  
- The workflow auto-derives the channel (`Release` for tags, `Snapshot` otherwise) and version (tags strip the `v` prefix, non-tag builds use `0.0.0-<sha>-SNAPSHOT`).  
- You can run the same publish logic locally with: `./gradlew -Phangar.channel=Snapshot -Pversion.override=1.2.3 publishPluginPublicationToHangar`.  
- Supported Paper versions for Hangar listings are configured via `paperVersion` in `gradle.properties` (comma-separated if you need multiple).
- If your Hangar project slug isn’t `pdcetimer`, set `hangar.slug=<your-slug>` in `gradle.properties` or define the `HANGAR_PROJECT_SLUG` repo variable/secret so the publish task targets the correct project.

## License

MIT
