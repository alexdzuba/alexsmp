package ru.unknownsmp.progression;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import ru.unknownsmp.combat.CombatService;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.profile.Profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Прогрессия: репутация, уровни (игровое время и достижения), ранги прогрессии (только косметика),
 * титулы и достижения (в т.ч. секретные). Ранги и репутация не сбрасываются автоматически.
 */
public final class ProgressionService implements Module, Listener {
    public record Rank(String id, String name, Map<String, Long> requires, long money, String cosmetic, String title) {}

    public record Achievement(String id, String name, String description, String stat, long value, long reputation,
                              String title, String cosmetic, boolean secret, String icon) {}

    private final UnknownSMP plugin;
    private final Map<String, Rank> ranks = new LinkedHashMap<>();
    private final Map<String, Achievement> achievements = new LinkedHashMap<>();
    private final Map<String, List<Achievement>> byStat = new LinkedHashMap<>();
    private final Map<String, String> titles = new LinkedHashMap<>();
    private final Map<String, Long> pvpRepCd = new ConcurrentHashMap<>();
    private ProgressionMenus menus;
    private double xpBase, xpExp, bonusPerLevel, bonusMax;
    private int maxLevel;

    public ProgressionService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "progression"; }
    @Override public String title() { return "Ранги, репутация и достижения"; }
    @Override public String[] depends() { return new String[]{"profiles"}; }

    @Override
    public void enable() {
        reload();
        menus = new ProgressionMenus(plugin, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.profiles().addHook(this::onStat);
        Tasks.timer(20 * 60, 20 * 60, () -> plugin.profiles().forEachOnline((p, pr) -> {
            if (!plugin.auth().isAuthed(p)) return;
            if (plugin.afk() != null && !plugin.afk().isAfk(p)) addXp(p, plugin.configs().get("progression.yml").getLong("levels.xp-per-minute", 2), "playtime");
            checkRank(p, pr);
        }));
        plugin.auth().onLogin(p -> Tasks.later(40, () -> {
            Profile pr = plugin.profiles().get(p);
            if (pr == null) return;
            for (var e : pr.stats.entrySet()) checkAchievements(p, pr, e.getKey(), e.getValue());
            checkRank(p, pr);
        }));
    }

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().get("progression.yml");
        xpBase = c.getDouble("levels.xp-base", 60);
        xpExp = c.getDouble("levels.xp-exponent", 1.6);
        maxLevel = c.getInt("levels.max", 100);
        bonusPerLevel = c.getDouble("levels.combat-bonus-per-level", 0.0002);
        bonusMax = Math.min(0.05, c.getDouble("levels.combat-bonus-max", 0.02));
        ranks.clear();
        ConfigurationSection rs = c.getConfigurationSection("ranks");
        if (rs != null) for (String id : rs.getKeys(false)) {
            ConfigurationSection r = rs.getConfigurationSection(id);
            Map<String, Long> req = new LinkedHashMap<>();
            ConfigurationSection q = r.getConfigurationSection("requires");
            if (q != null) for (String k : q.getKeys(false)) req.put(k, q.getLong(k));
            ranks.put(id, new Rank(id, r.getString("name", id), req, r.getLong("rewards.money", 0), r.getString("rewards.cosmetic"), r.getString("rewards.title")));
        }
        titles.clear();
        ConfigurationSection ts = c.getConfigurationSection("titles");
        if (ts != null) for (String id : ts.getKeys(false)) titles.put(id, ts.getString(id));
        achievements.clear();
        byStat.clear();
        ConfigurationSection as = c.getConfigurationSection("achievements");
        if (as != null) for (String id : as.getKeys(false)) {
            ConfigurationSection a = as.getConfigurationSection(id);
            Achievement ach = new Achievement(id, a.getString("name", id), a.getString("description", ""), a.getString("stat", "kills"),
                    a.getLong("value", 1), a.getLong("reputation", 10), a.getString("title"), a.getString("cosmetic"), a.getBoolean("secret", false),
                    a.getString("icon", "GOLD_INGOT"));
            achievements.put(id, ach);
            byStat.computeIfAbsent(ach.stat(), k -> new ArrayList<>()).add(ach);
        }
    }

    public Map<String, Rank> ranks() { return ranks; }

    public Map<String, Achievement> achievements() { return achievements; }

    public Map<String, String> titles() { return titles; }

    public ProgressionMenus menus() { return menus; }

    public String titleName(String id) {
        if (id == null) return "";
        return titles.getOrDefault(id, id);
    }

    public String rankName(String id) {
        Rank r = id == null ? null : ranks.get(id);
        if (r == null) return ranks.isEmpty() ? "&7Новичок" : ranks.values().iterator().next().name();
        return r.name();
    }

    // ------------------------------------------------------------------ Опыт и уровни

    public long xpFor(int level) {
        return (long) Math.ceil(xpBase * Math.pow(level, xpExp));
    }

    public void addXp(Player p, long amount, String reason) {
        Profile pr = plugin.profiles().get(p);
        if (pr == null || amount <= 0) return;
        pr.xp += amount;
        pr.dirty = true;
        boolean up = false;
        while (pr.level < maxLevel && pr.xp >= xpFor(pr.level + 1)) {
            pr.level++;
            up = true;
        }
        if (up) {
            Msg.title(p, "&b&lНОВЫЙ УРОВЕНЬ", "&f" + pr.level, 5, 40, 10);
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 0.9f);
            plugin.profiles().setStatMax(p, "level", pr.level);
        }
    }

    public void addReputation(Player p, long delta, String reason) {
        Profile pr = plugin.profiles().get(p);
        if (pr == null || delta == 0) return;
        pr.reputation += delta;
        pr.dirty = true;
        Msg.actionBar(p, (delta > 0 ? "&b+" : "&c") + delta + " репутации");
        plugin.profiles().setStatMax(p, "reputation_max", pr.reputation);
        if (Math.abs(delta) >= 10) plugin.logs().log(p, "progress", "reputation", null, delta + " " + reason);
    }

    public double combatBonus(Profile pr) {
        return Math.min(bonusMax, pr.level * bonusPerLevel);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void bonus(EntityDamageByEntityEvent e) {
        Player p = CombatService.attackerOf(e.getDamager());
        if (p == null) return;
        if (plugin.duels() != null && plugin.duels().isDuelWorld(p.getWorld())) return; // в дуэлях — равные условия
        Profile pr = plugin.profiles().get(p);
        if (pr == null || pr.level <= 0) return;
        e.setDamage(e.getDamage() * (1 + combatBonus(pr)));
    }

    public void onPvpKill(Player killer, Player victim) {
        String k = killer.getUniqueId() + ">" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = pvpRepCd.get(k);
        var c = plugin.configs().get("progression.yml");
        if (last == null || now - last > 3_600_000L) {
            pvpRepCd.put(k, now);
            addReputation(killer, c.getLong("reputation.pvp-kill", 2), "pvp_kill");
        }
        long loss = c.getLong("reputation.pvp-death", -1);
        if (loss != 0) addReputation(victim, loss, "pvp_death");
    }

    // ------------------------------------------------------------------ Достижения

    private void onStat(Player p, Profile pr, String stat, long value) {
        checkAchievements(p, pr, stat, value);
    }

    private void checkAchievements(Player p, Profile pr, String stat, long value) {
        List<Achievement> list = byStat.get(stat);
        if (list == null) return;
        for (Achievement a : list) {
            if (value < a.value() || pr.has("ach", a.id())) continue;
            if (!plugin.profiles().unlock(p, "ach", a.id())) continue;
            Msg.title(p, a.secret() ? "&d&lСЕКРЕТНОЕ ДОСТИЖЕНИЕ" : "&6&lДОСТИЖЕНИЕ", "&f" + a.name(), 5, 60, 15);
            Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
            Msg.send(p, "ach.unlocked", "&6Достижение: &f{n} &7— {d}", "n", a.name(), "d", a.description());
            if (a.reputation() != 0) addReputation(p, a.reputation(), "achievement");
            long xp = plugin.configs().get("progression.yml").getLong("levels.xp-per-achievement", 100);
            addXp(p, xp, "achievement");
            if (a.title() != null && plugin.profiles().unlock(p, "title", a.title())) Msg.send(p, "title.unlocked", "&dОткрыт титул: {t}", "t", titleName(a.title()));
            if (a.cosmetic() != null) plugin.profiles().unlock(p, "cosmetic", a.cosmetic());
            plugin.profiles().addStat(p, "achievements", 1);
            plugin.logs().log(p, "progress", "achievement", p.getLocation(), a.id());
        }
    }

    // ------------------------------------------------------------------ Ранги

    public long requirementValue(Player p, Profile pr, String key) {
        return switch (key) {
            case "level" -> pr.level;
            case "reputation" -> pr.reputation;
            case "playtime-hours" -> pr.playtime / 3600;
            case "achievements" -> pr.stat("achievements");
            case "kills" -> pr.stat("kills");
            case "events" -> pr.stat("events_done");
            case "quests" -> pr.stat("quests_done");
            case "wealth" -> pr.balance;
            case "secrets" -> pr.stat("secrets_found");
            case "duel-wins" -> pr.stat("duel_wins");
            default -> pr.stat(key);
        };
    }

    public boolean meets(Player p, Profile pr, Rank r) {
        for (var e : r.requires().entrySet()) if (requirementValue(p, pr, e.getKey()) < e.getValue()) return false;
        return true;
    }

    private int index(String rankId) {
        int i = 0;
        for (String id : ranks.keySet()) { if (id.equals(rankId)) return i; i++; }
        return -1;
    }

    public void checkRank(Player p, Profile pr) {
        int cur = index(pr.progRank);
        int best = cur;
        int i = 0;
        List<Rank> list = new ArrayList<>(ranks.values());
        for (Rank r : list) {
            if (i > best && meets(p, pr, r)) best = i;
            else if (i > best) break; // ранги последовательны
            i++;
        }
        if (best <= cur) return;
        for (int k = Math.max(0, cur + 1); k <= best; k++) {
            Rank r = list.get(k);
            pr.progRank = r.id();
            pr.dirty = true;
            if (r.money() > 0) plugin.economy().deposit(p.getUniqueId(), r.money(), "rank_reward", r.id());
            if (r.cosmetic() != null) plugin.profiles().unlock(p, "cosmetic", r.cosmetic());
            if (r.title() != null) plugin.profiles().unlock(p, "title", r.title());
        }
        Rank r = list.get(best);
        if (best == 0) { // начальный ранг выдаётся без объявления
            if (plugin.display() != null) plugin.display().refresh(p);
            return;
        }
        Msg.broadcast("rank.up", "&fИгрок &e{p}&f достиг ранга {r}&f!", "p", p.getName(), "r", r.name());
        Msg.title(p, "&6&lНОВЫЙ РАНГ", r.name(), 5, 60, 15);
        plugin.logs().log(p, "progress", "rank", p.getLocation(), r.id());
        if (plugin.display() != null) plugin.display().refresh(p);
    }

    /** Сохранить изменения рангов из редактора владельца. */
    public void saveRanks(Map<String, Rank> edited) {
        YamlConfiguration c = plugin.configs().get("progression.yml");
        c.set("ranks", null);
        for (Rank r : edited.values()) {
            String b = "ranks." + r.id();
            c.set(b + ".name", r.name());
            for (var e : r.requires().entrySet()) c.set(b + ".requires." + e.getKey(), e.getValue());
            if (r.money() > 0) c.set(b + ".rewards.money", r.money());
            if (r.cosmetic() != null) c.set(b + ".rewards.cosmetic", r.cosmetic());
            if (r.title() != null) c.set(b + ".rewards.title", r.title());
        }
        plugin.configs().save("progression.yml");
        reload();
    }

}
