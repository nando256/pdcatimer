package net.nando256.pdca;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class PdcaTimerPlugin extends JavaPlugin {

  private PdcaTimerManager manager;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    reloadManager();
    getLogger().info("[PDCATimer] Lectern timers enabled.");
  }

  @Override
  public void onDisable() {
    if (manager != null) {
      HandlerList.unregisterAll(manager);
      manager.shutdown();
      manager = null;
    }
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!command.getName().equalsIgnoreCase("pdcetimer")) return false;
    if (args.length == 0) {
      sender.sendMessage("/" + label + " reload");
      return true;
    }
    if (args[0].equalsIgnoreCase("reload")) {
      if (!sender.hasPermission("pdcetimer.admin")) {
        sender.sendMessage("§cYou do not have permission to do that.");
        return true;
      }
      reloadConfig();
      reloadManager();
      sender.sendMessage("§aPDCATimer configuration reloaded.");
      return true;
    }
    sender.sendMessage("/" + label + " reload");
    return true;
  }

  private void reloadManager() {
    if (manager != null) {
      HandlerList.unregisterAll(manager);
      manager.shutdown();
    }
    if (!getConfig().getBoolean("pdca.enabled", true)) {
      manager = null;
      getLogger().info("[PDCATimer] Disabled via config.");
      return;
    }
    manager = new PdcaTimerManager(this);
    getServer().getPluginManager().registerEvents(manager, this);
  }
}
