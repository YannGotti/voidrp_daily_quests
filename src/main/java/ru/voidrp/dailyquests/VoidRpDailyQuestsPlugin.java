package ru.voidrp.dailyquests;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import ru.voidrp.dailyquests.command.DailyQuestCommand;
import ru.voidrp.dailyquests.listener.NpcInteractListener;
import ru.voidrp.dailyquests.listener.QuestProgressListener;
import ru.voidrp.dailyquests.player.QuestStorage;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

public final class VoidRpDailyQuestsPlugin extends JavaPlugin {

    private QuestStorage storage;
    private Economy economy;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        int questsPerDay = getConfig().getInt("quests-per-day", 3);
        Path dataDir = getDataFolder().toPath().resolve("data");
        storage = new QuestStorage(dataDir, questsPerDay, getLogger());

        economy = setupEconomy();
        if (economy == null) {
            getLogger().warning("Vault economy not found — money rewards disabled.");
        }

        String newQuestsMsg     = getConfig().getString("new-quests-message",  "&6Новые ежедневные квесты доступны! Используй /dq");
        String questCompleteMsg = getConfig().getString("quest-complete-message", "&aКвест выполнен: {quest}");
        List<String> npcNames   = getConfig().getStringList("npc-names");

        // Listeners
        QuestProgressListener progressListener = new QuestProgressListener(
            storage, economy, newQuestsMsg, questCompleteMsg, getLogger());
        getServer().getPluginManager().registerEvents(progressListener, this);
        getServer().getPluginManager().registerEvents(new NpcInteractListener(storage, npcNames), this);

        // Commands
        DailyQuestCommand cmdHandler = new DailyQuestCommand(storage);
        getCommand("dailyquest").setExecutor(cmdHandler);
        getCommand("dqadmin").setExecutor(cmdHandler);

        // Load quests for already-online players (e.g. on /reload)
        for (Player p : Bukkit.getOnlinePlayers()) {
            storage.ensureToday(p.getUniqueId());
        }

        // Midnight reset scheduler — check every minute
        int resetHour = getConfig().getInt("reset-hour", 0);
        new BukkitRunnable() {
            private int lastCheckedHour = -1;

            @Override
            public void run() {
                int currentHour = LocalDateTime.now().getHour();
                if (currentHour == resetHour && lastCheckedHour != resetHour) {
                    lastCheckedHour = currentHour;
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        boolean fresh = storage.ensureToday(p.getUniqueId());
                        if (fresh) {
                            p.sendMessage(color(newQuestsMsg));
                        }
                    }
                } else if (currentHour != resetHour) {
                    lastCheckedHour = -1; // allow re-trigger next day
                }
            }
        }.runTaskTimer(this, 1200L, 1200L); // every minute (1200 ticks)

        // Auto-save every 5 minutes
        new BukkitRunnable() {
            @Override public void run() { storage.saveAll(); }
        }.runTaskTimerAsynchronously(this, 6000L, 6000L);

        getLogger().info("VoidRp Daily Quests enabled — " + questsPerDay + " quests/day per player");
    }

    @Override
    public void onDisable() {
        if (storage != null) storage.saveAll();
        getLogger().info("VoidRp Daily Quests disabled — all data saved");
    }

    private Economy setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return null;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return null;
        return rsp.getProvider();
    }

    private static String color(String s) {
        return s.replace("&", "§");
    }
}
