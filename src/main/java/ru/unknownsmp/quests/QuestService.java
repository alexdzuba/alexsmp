package ru.unknownsmp.quests;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.profile.Profile;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Задания: ежедневные и еженедельные (выдаются автоматически), сюжетные (один раз), исследовательские,
 * боевые, событийные, дополнительные задания NPC и PvP-контракты (BountyService).
 */
public final class QuestService implements Module, Listener {
    public enum Type { DAILY, WEEKLY, STORY, EXPLORE, COMBAT, EVENT, NPC }

    public record Def(String id, Type type, String name, String description, String kind, Set<String> targets, long amount,
                      long money, long reputation, long xp, List<String> items, String title, String npc, List<String> requires,
                      boolean repeatable, long repeatCooldownHours, String icon) {}

    public static final class Progress {
        public final String quest;
        public String period;
        public String state;
        public long value;

        Progress(String quest, String period, String state, long value) {
            this.quest = quest;
            this.period = period;
            this.state = state;
            this.value = value;
        }
    }

    private final UnknownSMP plugin;
    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Progress>> progress = new ConcurrentHashMap<>();
    private final Set<String> placed = Collections.newSetFromMap(new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 200_000; }
    });
    private final Map<String, Set<UUID>> pvpVictims = new ConcurrentHashMap<>();
    private QuestMenus menus;

    public QuestService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "quests"; }
    @Override public String title() { return "Задания"; }
    @Override public String[] depends() { return new String[]{"economy", "profiles", "items"}; }

    @Override
    public void enable() {
        reload();
        menus = new QuestMenus(plugin, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.auth().onLogin(this::load);
        plugin.profiles().addHook((pl, pr, stat, v) -> {
            if (stat.equals("playtime_min")) progress(pl, "PLAYTIME", "ANY", 1);
            else if (stat.equals("duel_wins")) progress(pl, "DUEL_WIN", "ANY", 1);
        });
        for (Player p : Bukkit.getOnlinePlayers()) load(p);
        Tasks.timer(100, 100, this::periodic);
        plugin.commands().add("quests").player().perm("usmp.player.quests").module("quests").cat("Задания")
                .usage("/quests").desc("Задания").exec((s, a) -> menus.open((Player) s, null));
    }

    @Override
    public void reload() {
        defs.clear();
        ConfigurationSection qs = plugin.configs().get("quests.yml").getConfigurationSection("quests");
        if (qs == null) return;
        for (String id : qs.getKeys(false)) {
            ConfigurationSection q = qs.getConfigurationSection(id);
            Type t;
            try { t = Type.valueOf(q.getString("type", "DAILY").toUpperCase(Locale.ROOT)); } catch (Exception e) { plugin.getLogger().warning("quests.yml: неверный тип " + id); continue; }
            Set<String> targets = new LinkedHashSet<>();
            for (String s : q.getString("objective.target", "ANY").split(",")) targets.add(s.trim().toUpperCase(Locale.ROOT));
            defs.put(id, new Def(id, t, q.getString("name", id), q.getString("description", ""), q.getString("objective.kind", "BREAK_BLOCK").toUpperCase(Locale.ROOT),
                    targets, Math.max(1, q.getLong("objective.amount", 1)), q.getLong("rewards.money", 0), q.getLong("rewards.reputation", 0),
                    q.getLong("rewards.xp", 0), q.getStringList("rewards.items"), q.getString("rewards.title"), q.getString("npc"),
                    q.getStringList("requires"), q.getBoolean("repeatable", t == Type.DAILY || t == Type.WEEKLY), q.getLong("repeat-cooldown-hours", 24),
                    q.getString("icon", "PAPER")));
        }
    }

    public Map<String, Def> defs() { return defs; }

    public QuestMenus menus() { return menus; }

    // ------------------------------------------------------------------ Периоды

    public static String dailyPeriod() { return "D" + LocalDate.now(Text.zone()).toEpochDay(); }

    public static String weeklyPeriod() {
        LocalDate d = LocalDate.now(Text.zone());
        return "W" + d.get(IsoFields.WEEK_BASED_YEAR) + "-" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    private String periodFor(Def d) {
        return switch (d.type()) {
            case DAILY -> dailyPeriod();
            case WEEKLY -> weeklyPeriod();
            default -> "ONCE";
        };
    }

    // ------------------------------------------------------------------ Загрузка и выдача

    private void load(Player p) {
        final UUID u = p.getUniqueId();
        final String day = dailyPeriod(), week = weeklyPeriod();
        Tasks.then(plugin.db().query(c -> {
            Map<String, Progress> m = new LinkedHashMap<>();
            try (PreparedStatement ps = Database.ps(c, "SELECT quest, period, state, progress FROM quest_progress WHERE uuid=? AND (period=? OR period=? OR period='ONCE')",
                    u.toString(), day, week); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) m.put(rs.getString(1), new Progress(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
            }
            return m;
        }), m -> {
            progress.put(u, m);
            assignPeriodic(p);
        }, null);
    }

    /** Выдать ежедневные/еженедельные задания текущего периода, если их ещё нет. */
    private void assignPeriodic(Player p) {
        Map<String, Progress> m = progress.get(p.getUniqueId());
        if (m == null) return;
        var cfg = plugin.configs().get("quests.yml");
        assign(p, m, Type.DAILY, dailyPeriod(), cfg.getInt("daily-count", 3));
        assign(p, m, Type.WEEKLY, weeklyPeriod(), cfg.getInt("weekly-count", 2));
    }

    private void assign(Player p, Map<String, Progress> m, Type type, String period, int count) {
        // Удаляем записи прошлых периодов из кэша.
        m.values().removeIf(pr -> { Def d = defs.get(pr.quest); return d != null && d.type() == type && !pr.period.equals(period); });
        long have = m.values().stream().filter(pr -> { Def d = defs.get(pr.quest); return d != null && d.type() == type; }).count();
        if (have >= count) return;
        List<Def> pool = new ArrayList<>();
        for (Def d : defs.values()) if (d.type() == type && !m.containsKey(d.id())) pool.add(d);
        Collections.shuffle(pool);
        boolean any = false;
        for (int i = 0; i < count - have && i < pool.size(); i++) {
            Def d = pool.get(i);
            Progress pr = new Progress(d.id(), period, "ACTIVE", 0);
            m.put(d.id(), pr);
            save(p.getUniqueId(), pr);
            any = true;
        }
        if (any) Msg.send(p, type == Type.DAILY ? "quests.daily-new" : "quests.weekly-new",
                type == Type.DAILY ? "&eНовые ежедневные задания! &f/quests" : "&eНовые еженедельные задания! &f/quests");
    }

    private void save(UUID u, Progress pr) {
        final String q = pr.quest, per = pr.period, st = pr.state;
        final long v = pr.value;
        plugin.db().tx(c -> Database.update(c,
                "INSERT INTO quest_progress(uuid, quest, period, state, progress, updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(uuid, quest, period) DO UPDATE SET state=excluded.state, progress=excluded.progress, updated_at=excluded.updated_at",
                u.toString(), q, per, st, v, System.currentTimeMillis()));
    }

    /** Активное задание ближе всего к завершению для показа на панели: {название, прогресс-строка}. */
    public String[] tracked(Player p) {
        Map<String, Progress> m = progress.get(p.getUniqueId());
        if (m == null) return null;
        Def best = null;
        Progress bp = null;
        double bestFrac = -1;
        for (Progress pr : m.values()) {
            if (!pr.state.equals("ACTIVE")) continue;
            Def d = defs.get(pr.quest);
            if (d == null) continue;
            double frac = pr.value / (double) d.amount();
            if (frac > bestFrac && frac < 1.0) { bestFrac = frac; best = d; bp = pr; }
        }
        if (best == null) return null;
        return new String[]{best.name(), bp.value + "/" + best.amount() + " " + Text.bar(bestFrac, 10, "&a", "&8")};
    }

    public Map<String, Progress> of(Player p) {
        return progress.getOrDefault(p.getUniqueId(), Map.of());
    }

    /** Можно ли взять задание (сюжетное/NPC/исследовательское и т.п.). */
    public String canAccept(Player p, Def d) {
        Map<String, Progress> m = progress.get(p.getUniqueId());
        if (m == null) return "Данные заданий ещё загружаются.";
        Progress pr = m.get(d.id());
        if (pr != null && pr.state.equals("ACTIVE")) return "Задание уже взято.";
        if (pr != null && pr.state.equals("DONE")) {
            if (!d.repeatable()) return "Задание уже выполнено.";
            Profile prof = plugin.profiles().get(p);
            long last = prof == null ? 0 : prof.stat("quest_last_" + d.id());
            long cd = d.repeatCooldownHours() * 3_600_000L;
            if (System.currentTimeMillis() - last < cd) return "Повторить можно через " + Text.duration(cd - (System.currentTimeMillis() - last)) + ".";
        }
        for (String r : d.requires()) {
            Progress rp = m.get(r);
            if (rp == null || !rp.state.equals("DONE")) {
                Def rd = defs.get(r);
                return "Сначала выполните: " + (rd == null ? r : rd.name());
            }
        }
        return null;
    }

    public boolean accept(Player p, String id) {
        Def d = defs.get(id);
        if (d == null || d.type() == Type.DAILY || d.type() == Type.WEEKLY) return false;
        String why = canAccept(p, d);
        if (why != null) { Msg.error(p, "quests.cant-accept", "{w}", "w", why); return false; }
        Progress pr = new Progress(d.id(), "ONCE", "ACTIVE", 0);
        progress.get(p.getUniqueId()).put(d.id(), pr);
        save(p.getUniqueId(), pr);
        Msg.ok(p, "quests.accepted", "Задание принято: {n}", "n", d.name());
        if (d.kind().equals("TALK")) progress(p, "TALK", d.npc() == null ? "ANY" : d.npc(), 1);
        return true;
    }

    // ------------------------------------------------------------------ Прогресс

    /** Учесть действие. kind — тип цели, target — материал/моб/биом и т.п. */
    public void progress(Player p, String kind, String target, long amount) {
        if (p == null || amount <= 0) return;
        Map<String, Progress> m = progress.get(p.getUniqueId());
        if (m == null) return;
        String t = target == null ? "ANY" : target.toUpperCase(Locale.ROOT);
        for (Progress pr : m.values()) {
            if (!pr.state.equals("ACTIVE")) continue;
            Def d = defs.get(pr.quest);
            if (d == null || !d.kind().equals(kind)) continue;
            if (!d.targets().contains("ANY") && !d.targets().contains(t)) continue;
            if (kind.equals("REACH_DISTANCE")) {
                if (amount < d.amount()) continue;
                pr.value = d.amount();
            } else {
                pr.value = Math.min(d.amount(), pr.value + amount);
            }
            if (pr.value >= d.amount()) complete(p, d, pr);
            else {
                save(p.getUniqueId(), pr);
                if (pr.value * 4 / d.amount() != (pr.value - amount) * 4 / d.amount())
                    Msg.actionBar(p, "&e" + d.name() + ": &f" + pr.value + "/" + d.amount());
            }
        }
    }

    private void complete(Player p, Def d, Progress pr) {
        pr.state = "DONE";
        pr.value = d.amount();
        save(p.getUniqueId(), pr);
        Profile prof = plugin.profiles().get(p);
        if (prof != null) {
            prof.stats.put("quest_last_" + d.id(), System.currentTimeMillis());
            prof.dirtyStats.add("quest_last_" + d.id());
        }
        plugin.profiles().addStat(p, "quests_done", 1);
        if (d.type() == Type.STORY) plugin.profiles().addStat(p, "story_done", 1);
        Msg.title(p, "&a✔ Задание выполнено", "&f" + d.name(), 5, 50, 15);
        Msg.sound(p, org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
        List<String> got = new ArrayList<>();
        if (d.money() > 0) { plugin.economy().deposit(p.getUniqueId(), d.money(), "quest", d.id()); got.add(Text.money(d.money())); }
        if (d.reputation() != 0) { plugin.progression().addReputation(p, d.reputation(), "quest"); got.add(d.reputation() + " репутации"); }
        if (d.xp() > 0) { plugin.progression().addXp(p, d.xp(), "quest"); got.add(d.xp() + " опыта уровня"); }
        for (String spec : d.items()) {
            Tasks.then(plugin.items().resolve(spec, p.getName(), "quest:" + d.id()), it -> {
                if (it != null) plugin.mail().giveOrMail(p, it, "reward");
            }, null);
            got.add(spec.replace("unique:", "★").replace("potion:", "зелье "));
        }
        if (d.title() != null && plugin.profiles().unlock(p, "title", d.title())) got.add("титул «" + Text.strip(plugin.progression().titleName(d.title())) + "»");
        if (!got.isEmpty()) Msg.send(p, "quests.rewards", "&7Награда: &f{r}", "r", String.join(", ", got));
        plugin.logs().log(p, "progress", "quest_done", p.getLocation(), d.id());
    }

    private void periodic() {
        String day = dailyPeriod();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Progress> m = progress.get(p.getUniqueId());
            if (m == null || !plugin.auth().isAuthed(p)) continue;
            // Смена суток/недели во время игры.
            boolean stale = m.values().stream().anyMatch(pr -> { Def d = defs.get(pr.quest); return d != null && d.type() == Type.DAILY && !pr.period.equals(day); });
            long dailyCount = m.values().stream().filter(pr -> { Def d = defs.get(pr.quest); return d != null && d.type() == Type.DAILY; }).count();
            if (stale || dailyCount == 0) assignPeriodic(p);
            if (plugin.lobby().isLobby(p.getWorld()) || (plugin.duels() != null && plugin.duels().isDuelWorld(p.getWorld()))) continue;
            progress(p, "VISIT_BIOME", p.getLocation().getBlock().getBiome().getKey().getKey(), 1);
            var s = plugin.world().spawn();
            if (s.getWorld() == p.getWorld()) {
                long dist = (long) Math.sqrt(Math.pow(p.getX() - s.getX(), 2) + Math.pow(p.getZ() - s.getZ(), 2));
                progress(p, "REACH_DISTANCE", "ANY", dist);
                plugin.profiles().setStatMax(p, "max_distance", dist);
            }
            if (p.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER) plugin.profiles().setStatMax(p, "visited_nether", 1);
            if (p.getWorld().getEnvironment() == org.bukkit.World.Environment.THE_END) plugin.profiles().setStatMax(p, "visited_end", 1);
        }
    }

    /** Сдать предметы NPC (цель DELIVER). */
    public boolean deliver(Player p, String questId) {
        Def d = defs.get(questId);
        Progress pr = of(p).get(questId);
        if (d == null || pr == null || !pr.state.equals("ACTIVE") || !d.kind().equals("DELIVER")) return false;
        long need = d.amount() - pr.value;
        long have = 0;
        Material m = Material.matchMaterial(d.targets().iterator().next());
        if (m == null) return false;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && it.getType() == m && ru.unknownsmp.market.ShopService.isPlain(it)) have += it.getAmount();
        long take = Math.min(need, have);
        if (take <= 0) { Msg.error(p, "quests.deliver-none", "У вас нет нужных предметов: {m}.", "m", ru.unknownsmp.gui.Items.materialName(m)); return false; }
        long left = take;
        ItemStack[] cont = p.getInventory().getStorageContents();
        for (int i = 0; i < cont.length && left > 0; i++) {
            if (cont[i] == null || cont[i].getType() != m || !ru.unknownsmp.market.ShopService.isPlain(cont[i])) continue;
            int t = (int) Math.min(left, cont[i].getAmount());
            cont[i].setAmount(cont[i].getAmount() - t);
            if (cont[i].getAmount() <= 0) cont[i] = null;
            left -= t;
        }
        p.getInventory().setStorageContents(cont);
        p.saveData();
        progress(p, "DELIVER", m.name(), take);
        return true;
    }

    // ------------------------------------------------------------------ Слушатели

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawnTag(CreatureSpawnEvent e) {
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER || e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                || e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER) {
            e.getEntity().getPersistentDataContainer().set(Keys.of("spawner"), PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void kill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null || e.getEntity() instanceof Player) return;
        boolean spawner = e.getEntity().getPersistentDataContainer().has(Keys.of("spawner"), PersistentDataType.BYTE);
        plugin.profiles().addStat(k, "mobs_killed", 1);
        if (spawner) return; // фермы спавнеров не засчитываются в задания
        progress(k, "KILL_MOB", e.getEntityType().name(), 1);
    }

    private static String key(Block b) { return b.getWorld().getName() + b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        synchronized (placed) { placed.add(key(e.getBlock())); }
        progress(e.getPlayer(), "PLACE_BLOCK", e.getBlock().getType().name(), 1);
        plugin.profiles().addStat(e.getPlayer(), "blocks_placed", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void brk(BlockBreakEvent e) {
        boolean wasPlaced;
        synchronized (placed) { wasPlaced = placed.remove(key(e.getBlock())); }
        plugin.profiles().addStat(e.getPlayer(), "blocks_broken", 1);
        if (e.getBlock().getBlockData() instanceof org.bukkit.block.data.Ageable crop) {
            // Урожай засчитывается только созревшим (посадка игроком не мешает).
            if (crop.getAge() < crop.getMaximumAge()) return;
        } else if (wasPlaced) {
            return; // поставленные игроками блоки не засчитываются
        }
        progress(e.getPlayer(), "BREAK_BLOCK", e.getBlock().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        plugin.profiles().addStat(e.getPlayer(), "fish_caught", 1);
        String t = e.getCaught() instanceof org.bukkit.entity.Item it ? it.getItemStack().getType().name() : "ANY";
        progress(e.getPlayer(), "FISH", t, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void craft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack r = e.getRecipe().getResult();
        int amount = r.getAmount();
        if (e.isShiftClick()) {
            int min = Integer.MAX_VALUE;
            for (ItemStack it : e.getInventory().getMatrix()) if (it != null && !it.getType().isAir()) min = Math.min(min, it.getAmount());
            if (min != Integer.MAX_VALUE) amount *= min;
        }
        progress(p, "CRAFT", r.getType().name(), amount);
    }

    /** PvP-убийство для заданий: засчитываются только разные жертвы за сутки. */
    public void onPlayerKill(Player killer, Player victim) {
        String k = killer.getUniqueId() + ":" + dailyPeriod();
        Set<UUID> s = pvpVictims.computeIfAbsent(k, x -> new HashSet<>());
        if (!s.add(victim.getUniqueId())) return;
        progress(killer, "KILL_PLAYER", "ANY", 1);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        progress.remove(e.getPlayer().getUniqueId());
    }

    public List<String> targetsText(Def d) {
        return Arrays.asList(d.targets().toArray(new String[0]));
    }
}
