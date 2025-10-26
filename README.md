# PDCATimer

A standalone Paper plugin that turns lecterns into PDCA timers, so kids can practice setting goals, checking in mid-stream, and reflecting without touching chat commands.

## How It Works

1. Write a book whose first page starts with `[PDCA]` (add `[option gauge1]` or `[option gauge2]` on its own line to choose the display style).
2. Add one line per step, for example:
   ```
   [PDCA]
   [1]30m: 前半
   [2]2m: 前半振り返り
   [3]30m: 後半
   [4]5m: 今日の振り返り
   ```
   - `[]` number defines the order.
   - Durations support `s` (seconds), `m` (minutes, default), and `h` (hours).
   - The label after the colon is optional; a default like `ステップ 1` is used when omitted.
   - `[option gauge1]` (default) shows a per-step countdown; `[option gauge2]` stacks the whole schedule, colouring each segment by step.
3. Right-click a lectern with the book (or flip to page 1) and the timer starts automatically.
4. Players within the configured radius (and anyone who has been near the lectern since it started) receive:
   - A title + sound when each step begins.
   - An action-bar warning and chime before the step ends (default 5 minutes).
   - A final “振り返り” reminder and completion sound once all steps finish.
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

## License

MIT
