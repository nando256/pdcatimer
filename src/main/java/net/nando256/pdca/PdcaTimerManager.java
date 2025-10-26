package net.nando256.pdca;

import io.papermc.paper.event.player.PlayerLecternPageChangeEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Lectern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.scheduler.BukkitScheduler;
import org.jetbrains.annotations.Nullable;

final class PdcaTimerManager implements Listener {

  private static final Pattern STEP_PATTERN = Pattern.compile("^\\[(\\d+)]\\s*(.*)$");
  private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)([hms])?$");
  private static final Pattern OPTION_PATTERN =
      Pattern.compile("^\\[option\\s+([^\\]]+)]$", Pattern.CASE_INSENSITIVE);

  private static final BarColor[] STEP_BAR_COLORS = {
    BarColor.RED,
    BarColor.BLUE,
    BarColor.GREEN,
    BarColor.YELLOW,
    BarColor.PURPLE,
    BarColor.WHITE,
    BarColor.PINK
  };

  private static final int PROGRESS_SLOTS = 180;
  private static final String[] STEP_COLOR_CODES = {
    "§c", "§9", "§a", "§e", "§5", "§f", "§b"
  };

  private final PdcaTimerPlugin plugin;
  private final Map<LecternKey, Session> sessions = new HashMap<>();

  private final double notifyRadius;
  private final Duration warnBefore;
  private final int titleFadeInTicks;
  private final int titleStayTicks;
  private final int titleFadeOutTicks;
  private final Sound startSound;
  private final Sound warnSound;
  private final Sound endSound;

  PdcaTimerManager(PdcaTimerPlugin plugin) {
    this.plugin = plugin;

    ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("pdca");
    if (cfg == null) {
      cfg = plugin.getConfig().createSection("pdca");
    }

    notifyRadius = cfg.getDouble("radius", 10.0);
    warnBefore = parseDuration(cfg.getString("warn_before"), Duration.ofMinutes(5));

    ConfigurationSection title = cfg.getConfigurationSection("title");
    titleFadeInTicks = title != null ? title.getInt("fade_in_ticks", 10) : 10;
    titleStayTicks = title != null ? title.getInt("stay_ticks", 60) : 60;
    titleFadeOutTicks = title != null ? title.getInt("fade_out_ticks", 10) : 10;

    ConfigurationSection sounds = cfg.getConfigurationSection("sounds");
    startSound =
        parseSound(sounds != null ? sounds.getString("start") : null, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
    warnSound =
        parseSound(sounds != null ? sounds.getString("warn") : null, Sound.BLOCK_NOTE_BLOCK_BELL);
    endSound =
        parseSound(sounds != null ? sounds.getString("end") : null, Sound.UI_TOAST_CHALLENGE_COMPLETE);
  }

  @SuppressWarnings("removal")
  private Sound parseSound(@Nullable String raw, Sound fallback) {
    if (raw == null || raw.isBlank()) return fallback;
    try {
      return Sound.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      plugin
          .getLogger()
          .warning("[PDCATimer] Invalid sound '" + raw + "'. Falling back to " + fallback);
      return fallback;
    }
  }

  void shutdown() {
    for (Session session : new ArrayList<>(sessions.values())) {
      session.cancel(false);
    }
    sessions.clear();
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void onRightClickLectern(PlayerInteractEvent event) {
    Block clicked = event.getClickedBlock();
    if (clicked == null || clicked.getType() != Material.LECTERN) return;
    plugin
        .getServer()
        .getScheduler()
        .runTask(plugin, () -> tryStartFromLectern(clicked, event.getPlayer()));
  }

  @EventHandler(ignoreCancelled = true)
  public void onPageChange(PlayerLecternPageChangeEvent event) {
    tryStartFromLectern(event.getLectern().getBlock(), event.getPlayer());
  }

  @EventHandler(ignoreCancelled = true)
  public void onLecternBreak(BlockBreakEvent event) {
    if (event.getBlock().getType() != Material.LECTERN) return;
    cancelSession(LecternKey.fromBlock(event.getBlock()), true);
  }

  private void tryStartFromLectern(Block block, Player starter) {
    BlockState state = block.getState();
    if (!(state instanceof Lectern lectern)) return;

    ItemStack bookStack = lectern.getInventory().getItem(0);
    if (bookStack == null) return;
    Material type = bookStack.getType();
    if (type != Material.WRITTEN_BOOK && type != Material.WRITABLE_BOOK) return;
    if (!(bookStack.getItemMeta() instanceof BookMeta meta)) return;

    String firstPage = "";
    if (meta.getPageCount() > 0) {
      try {
        firstPage = meta.getPage(1);
      } catch (IndexOutOfBoundsException ignored) {
        firstPage = "";
      }
    }
    String normalized = firstPage == null ? "" : firstPage.strip();
    if (!normalized.toUpperCase(Locale.ROOT).startsWith("[PDCA]")) return;

    ParsedBook parsed = parseBook(firstPage);
    if (parsed.steps().isEmpty()) {
      starter.sendMessage("§c[PDCA] No steps were found on page 1 of the book.");
      return;
    }

    LecternKey key = LecternKey.fromBlock(block);
    if (sessions.containsKey(key)) {
      starter.sendMessage("§e[PDCA] A PDCA timer is already running for this lectern.");
      return;
    }

    Location center =
        block.getLocation().toCenterLocation().add(0, 0.25, 0); // Slightly above the lectern

    Session session =
        new Session(
            key,
            center,
            parsed.steps(),
            parsed.totalDuration(),
            parsed.displayMode(),
            parsed.audienceMode(),
            parsed.audienceNames(),
            starter.getUniqueId(),
            starter.getName());
    sessions.put(key, session);
    session.start();
    starter.sendMessage("§a[PDCA] Started PDCA timer (steps: " + parsed.steps().size() + ")");
  }

  private void cancelSession(LecternKey key, boolean removed) {
    Session session = sessions.remove(key);
    if (session != null) {
      session.cancel(removed);
    }
  }

  private ParsedBook parseBook(String firstPage) {
    String[] rawLines = firstPage.replace("\r", "").split("\n");
    List<String> stepLines = new ArrayList<>();
    DisplayMode displayMode = DisplayMode.OVERALL_ONLY;
    AudienceMode audienceMode = AudienceMode.NEARBY;
    Set<String> audienceNames = new HashSet<>();

    for (String raw : rawLines) {
      String line = raw.trim();
      if (line.isEmpty()) continue;
      Matcher optionMatcher = OPTION_PATTERN.matcher(line);
      if (!optionMatcher.matches()) {
        stepLines.add(line);
        continue;
      }

      String option = optionMatcher.group(1).toLowerCase(Locale.ROOT);
      if (option.contains("display")) {
        String value = option.replace("display", "").replace('=', ' ').trim();
        String[] tokens = value.isEmpty() ? new String[0] : value.split("\\s+");
        boolean hasOverall = false;
        boolean hasStep = false;
        boolean hasNone = false;
        boolean hasBoth = false;
        for (String token : tokens) {
          if (token.isBlank()) continue;
          if (token.contains("overall")) hasOverall = true;
          if (token.contains("step")) hasStep = true;
          if (token.equals("none") || token.equals("hide") || token.equals("off")) hasNone = true;
          if (token.equals("both") || token.equals("all")) hasBoth = true;
        }
        if (hasNone) {
          displayMode = DisplayMode.NONE;
        } else if (hasBoth || (hasOverall && hasStep)) {
          displayMode = DisplayMode.BOTH;
        } else if (hasOverall) {
          displayMode = DisplayMode.OVERALL_ONLY;
        } else if (hasStep) {
          displayMode = DisplayMode.PER_STEP_ONLY;
        }
        continue;
      }

      if (option.contains("audience") || option.contains("user")) {
        String value = option
            .replace("audience", "")
            .replace("user", "")
            .replace('=', ' ')
            .replace(',', ' ')
            .trim();
        String[] tokens = value.isEmpty() ? new String[0] : value.split("\\s+");
        boolean anyToken = false;
        for (String token : tokens) {
          if (token.isBlank()) continue;
          anyToken = true;
          switch (token) {
            case "nearby", "radius" -> audienceMode = AudienceMode.NEARBY;
            case "all", "everyone", "global" -> audienceMode = AudienceMode.ALL;
            case "none", "off", "hide" -> audienceMode = AudienceMode.NONE;
            case "list", "users", "players" -> audienceMode = AudienceMode.LIST;
            default -> {
              audienceNames.add(token.toLowerCase(Locale.ROOT));
              audienceMode = AudienceMode.LIST;
            }
          }
        }
        if (!anyToken) audienceMode = AudienceMode.NEARBY;
        continue;
      }

      stepLines.add(line);
    }

    if (audienceMode == AudienceMode.LIST && audienceNames.isEmpty()) {
      audienceMode = AudienceMode.NEARBY;
    }

    List<PdcaStep> steps = parseSteps(stepLines);
    Duration total = Duration.ZERO;
    for (PdcaStep step : steps) {
      total = total.plus(step.duration());
    }

    return new ParsedBook(
        steps,
        total,
        displayMode,
        audienceMode,
        Collections.unmodifiableSet(audienceNames));
  }

  private List<PdcaStep> parseSteps(List<String> lines) {
    Map<Integer, PdcaStep> map = new LinkedHashMap<>();
    for (String raw : lines) {
      String line = raw.trim();
      if (line.isEmpty()) continue;
      if (line.equalsIgnoreCase("[PDCA]")) continue;

      Matcher matcher = STEP_PATTERN.matcher(line);
      if (!matcher.matches()) continue;

      int id;
      try {
        id = Integer.parseInt(matcher.group(1));
      } catch (NumberFormatException ex) {
        continue;
      }

      String remainder = matcher.group(2).trim();
      String durationToken;
      String label;
      int colon = remainder.indexOf(':');
      if (colon >= 0) {
        durationToken = remainder.substring(0, colon).trim();
        label = remainder.substring(colon + 1).trim();
      } else {
        durationToken = remainder;
        label = "";
      }
      if (durationToken.isEmpty()) continue;
      durationToken = durationToken.replace(" ", "");
      Duration duration = parseDuration(durationToken);
      if (duration == null || duration.isZero() || duration.isNegative()) continue;
      if (label.isEmpty()) label = "Step " + id;
      map.put(id, new PdcaStep(id, duration, label));
    }

    List<PdcaStep> steps = new ArrayList<>(map.values());
    steps.sort(Comparator.comparingInt(PdcaStep::id));
    return steps;
  }

  private Duration parseDuration(String token, Duration fallback) {
    Duration parsed = parseDuration(token);
    return parsed != null ? parsed : fallback;
  }

  @Nullable
  private Duration parseDuration(String token) {
    if (token == null) return null;
    String trimmed = token.trim().toLowerCase(Locale.ROOT);
    Matcher matcher = DURATION_PATTERN.matcher(trimmed);
    if (!matcher.matches()) return null;

    long value = Long.parseLong(matcher.group(1));
    String suffix = matcher.group(2);
    if (suffix == null || suffix.equals("m")) {
      return Duration.ofMinutes(value);
    }
    return switch (suffix) {
      case "s" -> Duration.ofSeconds(value);
      case "h" -> Duration.ofHours(value);
      default -> null;
    };
  }

  private List<Player> audience(Location center) {
    if (notifyRadius <= 0) return Collections.emptyList();
    double radiusSq = notifyRadius * notifyRadius;
    List<Player> players = new ArrayList<>();
    for (Player player : Objects.requireNonNull(center.getWorld()).getPlayers()) {
      if (player.getLocation().distanceSquared(center) <= radiusSq) {
        players.add(player);
      }
    }
    return players;
  }

  private boolean hasPdcaBook(LecternKey key) {
    Block block = key.block();
    if (block == null || block.getType() != Material.LECTERN) return false;
    Lectern lectern = (Lectern) block.getState();
    ItemStack item = lectern.getInventory().getItem(0);
    if (item == null) return false;
    Material type = item.getType();
    if (type != Material.WRITTEN_BOOK && type != Material.WRITABLE_BOOK) return false;
    if (!(item.getItemMeta() instanceof BookMeta meta)) return false;
    if (meta.getPageCount() == 0) return false;
    String first = meta.getPage(1);
    return first != null && first.strip().toUpperCase(Locale.ROOT).startsWith("[PDCA]");
  }

  private final class Session implements Runnable {

    private final LecternKey key;
    private final Location center;
    private final List<PdcaStep> steps;
    private final Duration totalDuration;
    private final long totalDurationMillis;
    private final DisplayMode displayMode;
    private final AudienceMode audienceMode;
    private final Set<String> audienceNames;
    private final long[] stepDurationsMillis;
    private final long[] stepStartOffsetsMillis;
    private final int[] stepSlotWidths;
    private final UUID starter;
    private final String starterName;

    private int index = 0;
    private PdcaStep currentStep;
    private long sessionStartMillis;
    private long stepStartMillis;

    private BossBar overallBar;
    private BossBar countdownBar;
    private final Set<UUID> viewers = new HashSet<>();

    private org.bukkit.scheduler.BukkitTask stepTask;
    private org.bukkit.scheduler.BukkitTask warnTask;
    private org.bukkit.scheduler.BukkitTask progressTask;

    Session(
        LecternKey key,
        Location center,
        List<PdcaStep> steps,
        Duration totalDuration,
        DisplayMode displayMode,
        AudienceMode audienceMode,
        Set<String> audienceNames,
        UUID starter,
        String starterName) {
      this.key = key;
      this.center = center;
      this.steps = steps;
      this.totalDuration = totalDuration;
      this.totalDurationMillis = Math.max(1L, totalDuration.toMillis());
      this.stepDurationsMillis = new long[steps.size()];
      this.stepStartOffsetsMillis = new long[steps.size()];
      long offset = 0L;
      for (int i = 0; i < steps.size(); i++) {
        stepStartOffsetsMillis[i] = offset;
        long millis = Math.max(1L, steps.get(i).duration().toMillis());
        stepDurationsMillis[i] = millis;
        offset += millis;
      }
      this.stepSlotWidths = new int[steps.size()];
      int slotsRemaining = PROGRESS_SLOTS;
      for (int i = 0; i < steps.size(); i++) {
        long durationMillis = stepDurationsMillis[i];
        int minRemaining = Math.max(0, (steps.size() - i - 1));
        int width;
        if (totalDurationMillis == 0) {
          width = Math.max(1, slotsRemaining / (steps.size() - i));
        } else if (i == steps.size() - 1) {
          width = Math.max(1, slotsRemaining);
        } else {
          double ratio = (double) durationMillis / (double) totalDurationMillis;
          width = Math.max(1, (int) Math.round(ratio * PROGRESS_SLOTS));
          if (width > slotsRemaining - minRemaining) {
            width = slotsRemaining - minRemaining;
          }
          width = Math.max(1, width);
        }
        stepSlotWidths[i] = width;
        slotsRemaining -= width;
      }
      if (steps.size() > 0 && slotsRemaining != 0) {
        stepSlotWidths[steps.size() - 1] += slotsRemaining;
      }
      this.displayMode = displayMode;
      this.audienceMode = audienceMode;
      this.audienceNames = audienceNames;
      this.starter = starter;
      this.starterName = starterName;
    }

    void start() {
      if (!hasPdcaBook(key)) {
        cancel(true);
        return;
      }
      sessionStartMillis = System.currentTimeMillis();

      if (showOverall()) {
        overallBar = Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID);
        overallBar.setProgress(0.0);
      } else {
        overallBar = null;
      }

      if (showPerStep()) {
        countdownBar =
            Bukkit.createBossBar(
                "PDCA",
                colorForIndex(0),
                BarStyle.SEGMENTED_20);
        countdownBar.setProgress(1.0);
      } else {
        countdownBar = null;
      }

      Player starterPlayer = Bukkit.getPlayer(starter);
      if (starterPlayer != null) addViewer(starterPlayer);

      run();

      progressTask =
          plugin
              .getServer()
              .getScheduler()
              .runTaskTimer(plugin, this::updateBossBarProgress, 0L, 10L);
    }

    @Override
    public void run() {
      if (!hasPdcaBook(key)) {
        cancel(true);
        return;
      }
      if (index >= steps.size()) {
        finish();
        return;
      }
      currentStep = steps.get(index);
      stepStartMillis = System.currentTimeMillis();

      if (countdownBar != null) {
        countdownBar.setColor(colorForIndex(index));
        countdownBar.setTitle(currentStep.label() + " remaining " + formatDuration(currentStep.duration()));
      }
      syncBossBarPlayers();

      for (Player player : messagePlayers()) {
        String label = currentStep.label();
        String mainTitle = label.contains(":" ) ? label.substring(label.indexOf(":" ) + 1).trim() : "";
        String subTitle = label.contains(":" ) ? label.substring(0, label.indexOf(":" )).trim() : label;
        player.sendTitle(mainTitle, subTitle, titleFadeInTicks, titleStayTicks, titleFadeOutTicks);
        player.playSound(center, startSound, 1f, 1f);
      }

      warnTask = scheduleWarn(currentStep);
      stepTask =
          scheduleLater(
              () -> {
                index++;
                run();
              },
              currentStep.duration());
    }

    void finish() {
      cancelTasks();
      sessions.remove(key);
      if (!hasPdcaBook(key)) {
        messageStarter("§c[PDCA] Timer cancelled because the book was removed.");
        return;
      }
      for (Player player : messagePlayers()) {
        player.sendActionBar("§aTime for today's reflection!");
        player.playSound(center, endSound, 1f, 1f);
        player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f);
      }
      messageStarter("§a[PDCA] Timer completed.");
    }

    void cancel(boolean removed) {
      cancelTasks();
      sessions.remove(key);
      if (removed) {
        messageStarter("§c[PDCA] Timer cancelled because the book was removed.");
      } else {
        messageStarter("§c[PDCA] Timer stopped.");
      }
    }

    private org.bukkit.scheduler.BukkitTask scheduleWarn(PdcaStep step) {
      if (warnBefore.isZero() || warnBefore.isNegative()) return null;
      if (step.duration().compareTo(warnBefore) <= 0) return null;
      Duration delay = step.duration().minus(warnBefore);
      return scheduleLater(
          () -> {
            if (!hasPdcaBook(key)) {
              cancel(true);
              return;
            }
            String warning = step.label() + " has " + formatDuration(warnBefore) + " remaining";
            for (Player player : messagePlayers()) {
              player.sendActionBar(warning);
              player.playSound(center, warnSound, 1f, 1f);
            }
          },
          delay);
    }

    private org.bukkit.scheduler.BukkitTask scheduleLater(Runnable run, Duration duration) {
      long ticks = Math.max(1L, (duration.toMillis() + 49L) / 50L);
      BukkitScheduler scheduler = plugin.getServer().getScheduler();
      return scheduler.runTaskLater(plugin, run, ticks);
    }

    private void updateBossBarProgress() {
      syncBossBarPlayers();

      long now = System.currentTimeMillis();
      long elapsed = now - sessionStartMillis;

      if (showOverall()) broadcastOverallProgress(elapsed);

      if (showPerStep() && countdownBar != null && currentStep != null) {
        long stepElapsed = now - stepStartMillis;
        long durationMillis = Math.max(1L, currentStep.duration().toMillis());
        double remaining = Math.max(0.0, 1.0 - (double) stepElapsed / durationMillis);
        countdownBar.setProgress(Math.max(0.0, Math.min(1.0, remaining)));
        Duration remainingDuration = clampToZero(currentStep.duration().minusMillis(stepElapsed));
        countdownBar.setTitle(currentStep.label() + " remaining " + formatDuration(remainingDuration));
      }
    }

    private void broadcastOverallProgress(long elapsedMillis) {
      if (totalDurationMillis <= 0 || overallBar == null) return;
      long clamped = Math.max(0L, Math.min(totalDurationMillis, elapsedMillis));
      StringBuilder bar = new StringBuilder("§7[");
      for (int i = 0; i < steps.size(); i++) {
        int width = stepSlotWidths[i];
        long stepStart = stepStartOffsetsMillis[i];
        long stepDuration = stepDurationsMillis[i];
        double stepProgress;
        if (clamped <= stepStart) {
          stepProgress = 0.0;
        } else if (clamped >= stepStart + stepDuration) {
          stepProgress = 1.0;
        } else {
          stepProgress = (double) (clamped - stepStart) / (double) stepDuration;
        }
        int filled = Math.min(width, Math.max(0, (int) Math.round(stepProgress * width)));
        String color = STEP_COLOR_CODES[i % STEP_COLOR_CODES.length];
        for (int slot = 0; slot < width; slot++) {
          boolean boundary = slot == 0;
          if (slot < filled || boundary) {
            bar.append(color).append('|');
          } else {
            bar.append("§7|");
          }
        }
        bar.append("§7|");
      }
      bar.append("§7]");
      Duration remaining = clampToZero(totalDuration.minusMillis(clamped));
      double progress = totalDurationMillis == 0 ? 0.0 : (double) clamped / (double) totalDurationMillis;
      if (overallBar != null) {
        overallBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
        overallBar.setTitle(bar + formatDuration(remaining));
      }
    }

    private void syncBossBarPlayers() {
      List<Player> current = audiencePlayers();
      Set<UUID> active = new HashSet<>();
      for (Player player : current) {
        active.add(player.getUniqueId());
        addViewer(player);
      }

      viewers.removeIf(
          uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
              removeViewer(player);
              return true;
            }

            AudienceMode mode = audienceMode;
            boolean allowed = switch (mode) {
              case ALL -> true;
              case LIST -> audienceNames.contains(player.getName().toLowerCase(Locale.ROOT));
              case NONE -> false;
              case NEARBY -> true;
            };

            if (!allowed) {
              removeViewer(player);
              return true;
            }

            // For LIST/NONE we already handled above; for NEARBY we keep viewers even if they moved away.
            // However, when mode == NEARBY, we may want to drop players who never entered radius again when reconfigured.
            if ((mode == AudienceMode.ALL || mode == AudienceMode.LIST) && !active.contains(uuid)) {
              removeViewer(player);
              return true;
            }

            ensureViewer(player);
            return false;
          });
    }

    private List<Player> audiencePlayers() {
      return switch (audienceMode) {
        case ALL -> new ArrayList<>(plugin.getServer().getOnlinePlayers());
        case LIST -> {
          List<Player> players = new ArrayList<>();
          for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (audienceNames.contains(player.getName().toLowerCase(Locale.ROOT))) {
              players.add(player);
            }
          }
          yield players;
        }
        case NONE -> Collections.emptyList();
        default -> audience(center);
      };
    }

    private List<Player> messagePlayers() {
      List<Player> players = new ArrayList<>();
      for (UUID uuid : viewers) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline() && isPlayerAllowed(player)) {
          players.add(player);
        }
      }
      return players;
    }

    private List<Player> countdownPlayers() {
      if (!showPerStep()) return Collections.emptyList();
      List<Player> players = new ArrayList<>();
      for (UUID uuid : viewers) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline() && isPlayerAllowed(player)) {
          players.add(player);
        }
      }
      return players;
    }

    private void cancelTasks() {
      if (stepTask != null) {
        stepTask.cancel();
        stepTask = null;
      }
      if (warnTask != null) {
        warnTask.cancel();
        warnTask = null;
      }
      if (progressTask != null) {
        progressTask.cancel();
        progressTask = null;
      }
      if (overallBar != null) {
        overallBar.removeAll();
        overallBar = null;
      }
      if (countdownBar != null) {
        countdownBar.removeAll();
        countdownBar = null;
      }
      viewers.clear();
    }

    private void messageStarter(String message) {
      Optional.ofNullable(Bukkit.getPlayer(starter))
          .ifPresentOrElse(
              player -> player.sendMessage(message),
              () -> plugin.getLogger().info(message + " (" + starterName + ")"));
    }

    private void addViewer(Player player) {
      if (player == null || !isPlayerAllowed(player)) return;
      if (viewers.add(player.getUniqueId())) {
        if (overallBar != null) overallBar.addPlayer(player);
        if (countdownBar != null) countdownBar.addPlayer(player);
      } else {
        ensureViewer(player);
      }
    }

    private void ensureViewer(Player player) {
      if (player == null || !isPlayerAllowed(player)) return;
      if (overallBar != null && !overallBar.getPlayers().contains(player)) {
        overallBar.addPlayer(player);
      }
      if (countdownBar != null && !countdownBar.getPlayers().contains(player)) {
        countdownBar.addPlayer(player);
      }
    }

    private void removeViewer(@Nullable Player player) {
      if (player == null) return;
      if (overallBar != null) overallBar.removePlayer(player);
      if (countdownBar != null) countdownBar.removePlayer(player);
    }

    private boolean isPlayerAllowed(Player player) {
      if (player == null) return false;
      return switch (audienceMode) {
        case ALL -> true;
        case LIST -> audienceNames.contains(player.getName().toLowerCase(Locale.ROOT));
        case NONE -> false;
        case NEARBY -> true;
      };
    }

    private boolean showOverall() {
      return displayMode == DisplayMode.BOTH || displayMode == DisplayMode.OVERALL_ONLY;
    }

    private boolean showPerStep() {
      return displayMode == DisplayMode.BOTH || displayMode == DisplayMode.PER_STEP_ONLY;
    }

    private BarColor colorForIndex(int idx) {
      return STEP_BAR_COLORS[idx % STEP_BAR_COLORS.length];
    }

    private int stepIndexForFraction(double fraction) {
      double targetMillis = fraction * totalDurationMillis;
      for (int i = 0; i < stepStartOffsetsMillis.length; i++) {
        long start = stepStartOffsetsMillis[i];
        long end = start + stepDurationsMillis[i];
        if (targetMillis <= end || i == stepStartOffsetsMillis.length - 1) {
          return i;
        }
      }
      return Math.max(0, stepStartOffsetsMillis.length - 1);
    }
  }

  private record PdcaStep(int id, Duration duration, String label) {}

  private record ParsedBook(
      List<PdcaStep> steps,
      Duration totalDuration,
      DisplayMode displayMode,
      AudienceMode audienceMode,
      Set<String> audienceNames) {}

  private enum DisplayMode {
    BOTH,
    OVERALL_ONLY,
    PER_STEP_ONLY,
    NONE
  }

  private enum AudienceMode {
    NEARBY,
    ALL,
    LIST,
    NONE
  }

  private record LecternKey(String world, int x, int y, int z) {
    static LecternKey fromBlock(Block block) {
      return new LecternKey(
          block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    @Nullable
    Block block() {
      org.bukkit.World w = Bukkit.getWorld(world);
      if (w == null) return null;
      return w.getBlockAt(x, y, z);
    }
  }

  private static Duration clampToZero(Duration duration) {
    return duration.isNegative() ? Duration.ZERO : duration;
  }

  private static String formatDuration(Duration duration) {
    duration = clampToZero(duration);
    long seconds = duration.getSeconds();
    long hours = seconds / 3600;
    long minutes = (seconds % 3600) / 60;
    long secs = seconds % 60;
    if (hours > 0) {
      return String.format("%d:%02d:%02d", hours, minutes, secs);
    }
    return String.format("%d:%02d", minutes, secs);
  }
}
