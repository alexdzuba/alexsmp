package ru.unknownsmp.combat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.profile.Profile;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PvP и боевой таймер (15 с). Выход из боя: потеря инвентаря, опыта и 10% баланса в пользу игрока,
 * нанёсшего больше всего урона в текущем бою. Смерть от игрока: 10% баланса убийце.
 * Каждое событие обрабатывается ровно один раз (флаг обработки боя).
 */
public final class CombatService implements Module, Listener {
    static final class Fight {
        final long id;
        long until;
        final Map<UUID, Double> damageBy = new HashMap<>();
        final Map<UUID, Long> lastHit = new HashMap<>();
        boolean processed;

        Fight(long id) { this.id = id; }
    }

    private static final AtomicLong IDS = new AtomicLong();
    private final UnknownSMP plugin;
    private final Map<UUID, Fight> fights = new ConcurrentHashMap<>();
    private int tagSeconds;
    private double deathLoss, logoutLoss;
    private boolean exemptKicked, exemptTimeout;
    private List<String> blockedCommands;

    public CombatService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "combat"; }
    @Override public String title() { return "PvP и боевой таймер"; }
    @Override public String[] depends() { return new String[]{"economy", "world"}; }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.timer(10, 10, this::tick);
        plugin.auth().onLogin(p -> {
            Profile pr = plugin.profiles().get(p);
            if (pr != null && pr.combatFlag) {
                pr.combatFlag = false;
                pr.dirty = true;
                Tasks.later(80, () -> Msg.error(p, "combat.logged-notice", "Вы вышли во время боя: потеряны вещи, опыт и часть денег."));
            }
        });
    }

    @Override
    public void reload() {
        var c = plugin.configs().get("pvp.yml");
        tagSeconds = c.getInt("combat-tag-seconds", 15);
        exemptKicked = c.getBoolean("combat-logout.exempt-kicked", true);
        exemptTimeout = c.getBoolean("combat-logout.exempt-timeout", false);
        blockedCommands = c.getStringList("blocked-commands");
        var e = plugin.configs().get("economy.yml");
        deathLoss = e.getDouble("death.loss-percent", 10);
        logoutLoss = e.getDouble("combat-logout.loss-percent", 10);
    }

    public boolean isTagged(Player p) {
        Fight f = fights.get(p.getUniqueId());
        return f != null && f.until > System.currentTimeMillis();
    }

    public long remaining(Player p) {
        Fight f = fights.get(p.getUniqueId());
        return f == null ? 0 : Math.max(0, f.until - System.currentTimeMillis());
    }

    private boolean pvpWorld(Entity e) {
        return !plugin.lobby().isLobby(e.getWorld()) && !(plugin.duels() != null && plugin.duels().isDuelWorld(e.getWorld()));
    }

    private void tag(Player p, Player attacker, double dmg) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        long now = System.currentTimeMillis();
        Fight f = fights.get(p.getUniqueId());
        if (f == null || f.until <= now || f.processed) {
            f = new Fight(IDS.incrementAndGet());
            fights.put(p.getUniqueId(), f);
            Msg.actionBar(p, Msg.t("combat.start", "&cВы в бою! Не выходите из игры {s} сек.", "s", tagSeconds));
        }
        f.until = now + tagSeconds * 1000L;
        if (attacker != null) {
            f.damageBy.merge(attacker.getUniqueId(), dmg, Double::sum);
            f.lastHit.put(attacker.getUniqueId(), now);
        }
        if (plugin.teleports() != null) plugin.teleports().cancelWarmup(p, true);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (var e : fights.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            Fight f = e.getValue();
            if (p == null) continue;
            if (f.until > now) {
                Msg.actionBar(p, Msg.t("combat.bar", "&c⚔ Бой: {t}", "t", Text.duration(f.until - now)));
            } else if (!f.processed) {
                f.processed = true;
                Msg.actionBar(p, Msg.t("combat.end", "&aВы вышли из боя."));
            }
        }
        fights.entrySet().removeIf(e -> e.getValue().until + 60_000 < now);
    }

    public static Player attackerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr) {
            ProjectileSource s = pr.getShooter();
            if (s instanceof Player p) return p;
        }
        if (damager instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        if (damager instanceof AreaEffectCloud c && c.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void pvpGuard(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = attackerOf(e.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        if (!pvpWorld(victim)) return;
        if (plugin.world().inSpawnZone(victim.getLocation()) || plugin.world().inSpawnZone(attacker.getLocation())) {
            e.setCancelled(true);
            Msg.actionBar(attacker, Msg.t("combat.spawn-zone", "&cPvP в зоне спавна запрещено"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = attackerOf(e.getDamager());
        if (attacker == null || attacker.equals(victim) || !pvpWorld(victim)) return;
        tag(victim, attacker, e.getFinalDamage());
        tag(attacker, null, 0);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent e) {
        if (!isTagged(e.getPlayer()) || plugin.roles().has(e.getPlayer(), "usmp.admin.bypass-combat")) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        if (blockedCommands.contains(cmd)) {
            e.setCancelled(true);
            Msg.error(e.getPlayer(), "combat.command", "Команда недоступна во время боя ({t}).", "t", Text.duration(remaining(e.getPlayer())));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (!isTagged(e.getPlayer())) return;
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        if (!plugin.world().inSpawnZone(e.getFrom()) && plugin.world().inSpawnZone(e.getTo())) {
            Location back = e.getFrom().clone();
            back.setYaw(e.getTo().getYaw());
            back.setPitch(e.getTo().getPitch());
            e.setTo(back);
            Msg.actionBar(e.getPlayer(), Msg.t("combat.no-spawn", "&cВо время боя нельзя войти в зону спавна"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!isTagged(p)) return;
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.UNKNOWN) return;
        boolean toSafe = plugin.world().inSpawnZone(e.getTo()) || plugin.lobby().isLobby(e.getTo().getWorld())
                || (plugin.duels() != null && plugin.duels().isDuelWorld(e.getTo().getWorld()));
        if (toSafe && !plugin.roles().has(p, "usmp.admin.bypass-combat")) {
            e.setCancelled(true);
            Msg.error(p, "combat.teleport", "Телепортация недоступна во время боя ({t}).", "t", Text.duration(remaining(p)));
        }
    }

    // ------------------------------------------------------------------ Смерть

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Fight f = fights.remove(victim.getUniqueId());
        if (f != null) f.processed = true;
        if (!pvpWorld(victim)) return;
        Player killer = victim.getKiller();
        plugin.profiles().addStat(victim, "deaths", 1);
        plugin.logs().log(victim, "death", killer == null ? "environment" : "pvp", victim.getLocation(),
                (killer == null ? String.valueOf(victim.getLastDamageCause() == null ? "?" : victim.getLastDamageCause().getCause()) : "killer=" + killer.getName())
                        + " drops=" + e.getDrops().size() + " xp=" + victim.getTotalExperience());
        if (killer == null || killer.equals(victim)) return;
        plugin.profiles().addStat(killer, "kills", 1);
        final UUID vu = victim.getUniqueId(), ku = killer.getUniqueId();
        final String vn = victim.getName(), kn = killer.getName();
        if (deathLoss > 0) {
            Tasks.then(plugin.economy().transferPercent(vu, ku, deathLoss, "pvp_death", vn + "->" + kn), amount -> {
                if (amount > 0) {
                    Player k = Bukkit.getPlayer(ku), v = Bukkit.getPlayer(vu);
                    if (k != null) Msg.send(k, "combat.kill-money", "&aВы получили {a} за убийство {v}.", "a", Text.money(amount), "v", vn);
                    if (v != null) Msg.send(v, "combat.death-money", "&cВы потеряли {a} ({p}%).", "a", Text.money(amount), "p", (int) deathLoss);
                }
            }, err -> plugin.logs().log(vu, vn, "economy", "tx_error", "pvp_death: " + err.getMessage()));
        }
        if (plugin.wars() != null) plugin.wars().onKill(killer, victim);
        if (plugin.bounties() != null) plugin.bounties().onKill(killer, victim);
        if (plugin.quests() != null) plugin.quests().onPlayerKill(killer, victim);
        if (plugin.progression() != null) plugin.progression().onPvpKill(killer, victim);
    }

    // ------------------------------------------------------------------ Выход из боя

    @EventHandler(priority = EventPriority.HIGHEST)
    public void quit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Fight f = fights.remove(p.getUniqueId());
        if (f == null || f.processed || f.until <= System.currentTimeMillis()) return;
        f.processed = true;
        if (Bukkit.isStopping()) return;
        PlayerQuitEvent.QuitReason r = e.getReason();
        if (r == PlayerQuitEvent.QuitReason.ERRONEOUS_STATE) return;
        if (r == PlayerQuitEvent.QuitReason.KICKED && exemptKicked) return;
        if (r == PlayerQuitEvent.QuitReason.TIMED_OUT && exemptTimeout) return;
        if (!pvpWorld(p)) return;
        punishLogout(p, f);
    }

    private void punishLogout(Player p, Fight f) {
        Location loc = p.getLocation();
        int count = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || it.getType().isAir()) continue;
            if (plugin.items() != null && !plugin.items().canDrop(it)) continue;
            loc.getWorld().dropItemNaturally(loc, it);
            count++;
        }
        p.getInventory().clear();
        int xp = p.getTotalExperience();
        p.setLevel(0);
        p.setExp(0);
        p.setTotalExperience(0);
        Profile pr = plugin.profiles().get(p);
        if (pr != null) { pr.combatFlag = true; pr.dirty = true; }
        UUID top = topDamager(f);
        final String name = p.getName();
        final UUID vu = p.getUniqueId();
        plugin.logs().log(p, "combat", "logout", loc, "stacks=" + count + " xp=" + xp + " receiver=" + top);
        plugin.logs().suspicious(p, "combat_logout", Text.coords(loc));
        plugin.profiles().addStat(p, "combat_logouts", 1);
        Msg.broadcast("combat.logout-broadcast", "&c{p} вышел из игры во время боя и потерял свои вещи!", "p", name);
        if (logoutLoss > 0) {
            Tasks.then(plugin.economy().transferPercent(vu, top, logoutLoss, "combat_logout", name), amount -> {
                if (top != null && amount > 0) {
                    Player k = Bukkit.getPlayer(top);
                    if (k != null) Msg.send(k, "combat.logout-money", "&a{p} вышел из боя — вы получили {a}.", "p", name, "a", Text.money(amount));
                }
            }, err -> plugin.logs().log(vu, name, "economy", "tx_error", "combat_logout: " + err.getMessage()));
        }
    }

    /** Игрок с наибольшим уроном за текущий бой; при равенстве — нанёсший удар последним. null — источника нет. */
    static UUID topDamager(Fight f) {
        UUID best = null;
        double bestDmg = 0;
        long bestTime = 0;
        for (var e : f.damageBy.entrySet()) {
            double d = e.getValue();
            long t = f.lastHit.getOrDefault(e.getKey(), 0L);
            if (d > bestDmg + 1e-9 || (Math.abs(d - bestDmg) <= 1e-9 && t > bestTime)) {
                best = e.getKey();
                bestDmg = d;
                bestTime = t;
            }
        }
        return bestDmg > 0 ? best : null;
    }
}
