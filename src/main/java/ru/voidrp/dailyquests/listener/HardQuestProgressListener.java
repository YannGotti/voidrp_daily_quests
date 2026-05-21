package ru.voidrp.dailyquests.listener;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.BlockBreakEvent;
import ru.voidrp.dailyquests.gui.HardQuestGui;
import ru.voidrp.dailyquests.player.HardQuestStorage;
import ru.voidrp.dailyquests.player.PlayerQuestState;
import ru.voidrp.dailyquests.quest.ActiveQuest;
import ru.voidrp.dailyquests.quest.QuestType;

import java.util.logging.Logger;

public final class HardQuestProgressListener implements Listener {

    private final HardQuestStorage storage;
    private final Economy economy;
    private final Logger log;

    private static final String NEW_QUEST_MSG =
        "§4§l⚔ §cНовое Испытание Героя доступно! §4§l⚔ §7Используй /bq";

    public HardQuestProgressListener(HardQuestStorage storage, Economy economy, Logger log) {
        this.storage  = storage;
        this.economy  = economy;
        this.log      = log;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        boolean fresh = storage.ensureCurrentPeriod(e.getPlayer().getUniqueId());
        if (fresh) e.getPlayer().sendMessage(NEW_QUEST_MSG);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        storage.evict(e.getPlayer().getUniqueId());
    }

    // ── Kill ──────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        String key = e.getEntity().getType().getKey().toString();
        track(killer, QuestType.KILL, key, 1);
    }

    // ── Collect ───────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent e) {
        String mat = e.getItem().getItemStack().getType().name();
        track(e.getPlayer(), QuestType.COLLECT, mat, e.getItem().getItemStack().getAmount());
    }

    // ── Mine ──────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        track(e.getPlayer(), QuestType.MINE, e.getBlock().getType().name(), 1);
    }

    // ── Craft ─────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        ItemStack result = e.getRecipe().getResult();
        if (result.getType().isAir()) return;

        // Shift-click crafts full stack
        int amount = e.isShiftClick()
            ? result.getAmount() * 4 // approximate; exact requires recipe matrix counting
            : result.getAmount();

        track(player, QuestType.CRAFT, result.getType().name(), amount);
    }

    // ── Fish ──────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (e.getCaught() == null) return;
        String mat = "ANY";
        if (e.getCaught() instanceof org.bukkit.entity.Item item) {
            mat = item.getItemStack().getType().name();
        }
        PlayerQuestState state = storage.get(e.getPlayer().getUniqueId());
        for (ActiveQuest q : state.quests) {
            if (q.type != QuestType.FISH) continue;
            if (!q.isCompleted() && (q.target.equals("ANY") || q.target.equals(mat))) {
                if (q.addProgress(1) > 0 && q.isCompleted()) notifyComplete(e.getPlayer(), q);
            }
        }
        storage.save(e.getPlayer().getUniqueId());
    }

    // ── Breed ─────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (!(e.getBreeder() instanceof Player player)) return;
        track(player, QuestType.BREED, e.getEntity().getType().getKey().toString(), 1);
    }

    // ── GUI: claim reward ─────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!HardQuestGui.TITLE.equals(e.getView().getTitle())) return;
        e.setCancelled(true);

        if (e.getRawSlot() != HardQuestGui.CLAIM_SLOT) return;

        PlayerQuestState state = storage.get(player.getUniqueId());
        if (state.quests.isEmpty()) return;
        ActiveQuest q = state.quests.get(0);

        if (!q.isClaimable()) return;
        q.rewardClaimed = true;
        storage.save(player.getUniqueId());

        if (economy != null) economy.depositPlayer(player, q.moneyReward);
        player.giveExp(q.expReward);
        player.sendMessage("§4§l⚔ §aИспытание завершено! §6+" + (int) q.moneyReward + " монет §7+ §b" + q.expReward + " опыта");

        player.openInventory(HardQuestGui.build(state.quests));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void track(Player player, QuestType type, String target, int amount) {
        PlayerQuestState state = storage.get(player.getUniqueId());
        boolean dirty = false;
        for (ActiveQuest q : state.quests) {
            if (q.type != type || q.isCompleted()) continue;
            if (!q.target.equalsIgnoreCase(target)) continue;
            int added = q.addProgress(amount);
            if (added > 0) {
                dirty = true;
                if (q.isCompleted()) notifyComplete(player, q);
            }
        }
        if (dirty) storage.save(player.getUniqueId());
    }

    private static void notifyComplete(Player player, ActiveQuest q) {
        player.sendMessage("§4§l⚔ §aИспытание выполнено: §f" + q.displayName + " §a| Забери награду (/bq)");
    }
}
