package ru.voidrp.dailyquests;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import ru.voidrp.dailyquests.command.BossQuestCommand;
import ru.voidrp.dailyquests.command.DailyQuestCommand;
import ru.voidrp.dailyquests.listener.HardQuestProgressListener;
import ru.voidrp.dailyquests.listener.NpcInteractListener;
import ru.voidrp.dailyquests.listener.QuestProgressListener;
import ru.voidrp.dailyquests.player.HardQuestStorage;
import ru.voidrp.dailyquests.player.QuestStorage;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

public final class VoidRpDailyQuestsPlugin extends JavaPlugin {

    private QuestStorage     dailyStorage;
    private HardQuestStorage hardStorage;
    private Economy economy;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Path base = getDataFolder().toPath();
        int questsPerDay = getConfig().getInt("quests-per-day", 3);
        dailyStorage = new QuestStorage(base.resolve("data"),      questsPerDay, getLogger());
        hardStorage  = new HardQuestStorage(base.resolve("hard"),               getLogger());

        economy = setupEconomy();
        if (economy == null) getLogger().warning("Vault not found — money rewards disabled.");

        String newQuestsMsg     = getConfig().getString("new-quests-message",     "&6Новые ежедневные квесты доступны! Используй /dq");
        String questCompleteMsg = getConfig().getString("quest-complete-message",  "&aКвест выполнен: {quest}");
        List<String> npcNames   = getConfig().getStringList("npc-names");

        // ── Listeners ──
        getServer().getPluginManager().registerEvents(
            new QuestProgressListener(dailyStorage, economy, newQuestsMsg, questCompleteMsg, getLogger()), this);
        getServer().getPluginManager().registerEvents(
            new HardQuestProgressListener(hardStorage, economy, getLogger()), this);
        getServer().getPluginManager().registerEvents(
            new NpcInteractListener(dailyStorage, npcNames), this);

        // ── Commands ──
        DailyQuestCommand dqCmd = new DailyQuestCommand(dailyStorage);
        getCommand("dailyquest").setExecutor(dqCmd);
        getCommand("dqadmin").setExecutor(dqCmd);

        BossQuestCommand bqCmd = new BossQuestCommand(hardStorage);
        getCommand("bossquest").setExecutor(bqCmd);
        getCommand("bqadmin").setExecutor(bqCmd);

        // ── Load quests for already-online players (e.g. on /reload) ──
        for (Player p : Bukkit.getOnlinePlayers()) {
            dailyStorage.ensureToday(p.getUniqueId());
            hardStorage.ensureCurrentPeriod(p.getUniqueId());
        }

        // ── Midnight reset check — every minute ──
        int resetHour = getConfig().getInt("reset-hour", 0);
        new BukkitRunnable() {
            private int lastCheckedHour = -1;
            @Override public void run() {
                int h = LocalDateTime.now().getHour();
                if (h == resetHour && lastCheckedHour != resetHour) {
                    lastCheckedHour = h;
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (dailyStorage.ensureToday(p.getUniqueId()))
                            p.sendMessage(color(newQuestsMsg));
                        if (hardStorage.ensureCurrentPeriod(p.getUniqueId()))
                            p.sendMessage("§4§l⚔ §cНовое Испытание Героя доступно! Используй /bq");
                    }
                } else if (h != resetHour) {
                    lastCheckedHour = -1;
                }
            }
        }.runTaskTimer(this, 1200L, 1200L);

        // ── Auto-save every 5 minutes ──
        new BukkitRunnable() {
            @Override public void run() { dailyStorage.saveAll(); hardStorage.saveAll(); }
        }.runTaskTimerAsynchronously(this, 6000L, 6000L);

        getLogger().info("VoidRp Daily Quests enabled — daily:" + questsPerDay + " + hard:1/3d per player");
    }

    @Override
    public void onDisable() {
        if (dailyStorage != null) dailyStorage.saveAll();
        if (hardStorage  != null) hardStorage.saveAll();
        getLogger().info("VoidRp Daily Quests disabled — all data saved");
    }

    private Economy setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return null;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    private static String color(String s) { return s.replace("&", "§"); }
}
