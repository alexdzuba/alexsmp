package ru.unknownsmp.clans;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Войны кланов: до 7 дней, очки за убийства (больше) и подтверждённое разрушение блоков противника (меньше).
 * Завершение — по соглашению или по времени; при равенстве — дополнительное время до первого очка.
 */
public final class WarService implements Module, Listener {
    public static final class War {
        public final long id;
        public final long attacker, defender;
        public final long startedAt;
        public long endsAt;
        public int scoreA, scoreD;
        public String state; // ACTIVE, OVERTIME, ENDED
        public long peaceFrom;
        public long overtimeUntil;

        War(long id, long attacker, long defender, long startedAt, long endsAt) {
            this.id = id;
            this.attacker = attacker;
            this.defender = defender;
            this.startedAt = startedAt;
            this.endsAt = endsAt;
        }

        public boolean involves(long clan) { return attacker == clan || defender == clan; }

        public long enemyOf(long clan) { return attacker == clan ? defender : attacker; }
    }

    private final UnknownSMP plugin;
    private final Map<Long, War> wars = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> killLog = new ConcurrentHashMap<>();
    private final Map<UUID, List<Long>> blockLog = new ConcurrentHashMap<>();

    public WarService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "wars"; }
    @Override public String title() { return "Войны кланов"; }
    @Override public String[] depends() { return new String[]{"clans"}; }

    @Override
    public void enable() throws Exception {
        plugin.db().queryBlocking(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM wars WHERE state <> 'ENDED'"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    War w = new War(rs.getLong("id"), rs.getLong("attacker"), rs.getLong("defender"), rs.getLong("started_at"), rs.getLong("ends_at"));
                    w.scoreA = rs.getInt("score_a");
                    w.scoreD = rs.getInt("score_d");
                    w.state = rs.getString("state");
                    w.peaceFrom = rs.getLong("peace_from");
                    w.overtimeUntil = rs.getLong("overtime_until");
                    wars.put(w.id, w);
                }
            }
            return null;
        });
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.timer(20 * 30, 20 * 30, this::tick);
    }

    private org.bukkit.configuration.file.YamlConfiguration cfg() { return plugin.configs().get("clans.yml"); }

    public List<War> active() { return new ArrayList<>(wars.values()); }

    public War activeBetween(Clan a, Clan b) {
        if (a == null || b == null) return null;
        for (War w : wars.values()) if (w.involves(a.id) && w.involves(b.id)) return w;
        return null;
    }

    public List<War> warsOf(Clan c) {
        List<War> out = new ArrayList<>();
        if (c == null) return out;
        for (War w : wars.values()) if (w.involves(c.id)) out.add(w);
        return out;
    }

    // ------------------------------------------------------------------ Объявление и мир

    public void declare(Player p, Clan target) {
        Clan cl = plugin.clans().clanOf(p);
        if (cl == null || !cl.can(p.getUniqueId(), Clan.Perm.WAR)) { Msg.error(p, "clan.no-perm", "Недостаточно прав в клане."); return; }
        if (target == null || target == cl) { Msg.error(p, "clan.unknown", "Клан не найден."); return; }
        if (plugin.clans().allied(cl, target)) { Msg.error(p, "war.allied", "Нельзя объявить войну союзнику. Сначала расторгните союз."); return; }
        if (activeBetween(cl, target) != null) { Msg.error(p, "war.exists", "Война с этим кланом уже идёт."); return; }
        long now = System.currentTimeMillis();
        long ends = now + cfg().getLong("war.duration-hours", 168) * 3_600_000L;
        Tasks.then(plugin.db().tx(c -> Database.insert(c, "INSERT INTO wars(attacker, defender, started_at, ends_at, score_a, score_d, state) VALUES (?,?,?,?,0,0,'ACTIVE')",
                cl.id, target.id, now, ends)), id -> {
            War w = new War(id, cl.id, target.id, now, ends);
            w.state = "ACTIVE";
            wars.put(id, w);
            Msg.broadcast("war.declared", "&c&lВОЙНА! &fКлан &d[{a}]&f объявил войну клану &d[{b}]&f. Длительность: до {d}.",
                    "a", cl.tag, "b", target.tag, "d", Text.duration(ends - now));
            plugin.logs().log(p, "clan", "war_declare", p.getLocation(), cl.tag + " -> " + target.tag);
        }, err -> Msg.error(p, "war.error", "Ошибка объявления войны."));
    }

    public void proposePeace(Player p, Clan target) {
        Clan cl = plugin.clans().clanOf(p);
        if (cl == null || !cl.can(p.getUniqueId(), Clan.Perm.WAR)) { Msg.error(p, "clan.no-perm", "Недостаточно прав в клане."); return; }
        War w = activeBetween(cl, target);
        if (w == null) { Msg.error(p, "war.none", "Войны с этим кланом нет."); return; }
        if (w.peaceFrom != 0 && w.peaceFrom != cl.id) {
            end(w, 0, "peace");
            return;
        }
        w.peaceFrom = cl.id;
        plugin.db().tx(c -> Database.update(c, "UPDATE wars SET peace_from=? WHERE id=?", cl.id, w.id));
        Msg.ok(p, "war.peace-sent", "Предложение мира отправлено клану [{t}].", "t", target.tag);
        plugin.clans().broadcastClan(target, "war.peace-offer", "&eКлан [{t}] предлагает мир. Принять: &a/clan peace {t}", "t", cl.tag);
    }

    // ------------------------------------------------------------------ Очки

    private void addScore(War w, long clan, int pts, String reason) {
        if (pts <= 0 || "ENDED".equals(w.state)) return;
        if (w.attacker == clan) w.scoreA += pts;
        else w.scoreD += pts;
        final int a = w.scoreA, d = w.scoreD;
        plugin.db().tx(c -> Database.update(c, "UPDATE wars SET score_a=?, score_d=? WHERE id=?", a, d, w.id));
        if ("OVERTIME".equals(w.state)) {
            end(w, clan, "overtime");
        }
    }

    private boolean rateLimited(List<Long> times, int max, long windowMs) {
        long now = System.currentTimeMillis();
        times.removeIf(t -> t < now - windowMs);
        if (times.size() >= max) return true;
        times.add(now);
        return false;
    }

    public void onKill(Player killer, Player victim) {
        Clan kc = plugin.clans().clanOf(killer), vc = plugin.clans().clanOf(victim);
        War w = activeBetween(kc, vc);
        if (w == null) return;
        int maxPerHour = cfg().getInt("war.max-kills-same-victim-per-hour", 3);
        List<Long> t = killLog.computeIfAbsent(killer.getUniqueId() + ">" + victim.getUniqueId(), k -> new ArrayList<>());
        if (rateLimited(t, maxPerHour, 3_600_000L)) {
            Msg.actionBar(killer, "&7Повторные убийства этого игрока не дают очков войны");
            return;
        }
        int pts = cfg().getInt("war.points-kill", 10);
        addScore(w, kc.id, pts, "kill");
        Msg.send(killer, "war.kill-points", "&c+{p} очков войны &7(убийство {v})", "p", pts, "v", victim.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void brk(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Clan cl = plugin.clans().clanOf(p);
        if (cl == null) return;
        List<War> mine = warsOf(cl);
        if (mine.isEmpty()) return;
        Block b = e.getBlock();
        String world = b.getWorld().getName();
        int x = b.getX(), y = b.getY(), z = b.getZ();
        long minAge = cfg().getLong("war.block-min-age-minutes", 10) * 60_000L;
        int pts = cfg().getInt("war.points-block", 1);
        int perHour = cfg().getInt("war.max-block-points-per-hour", 60);
        long now = System.currentTimeMillis();
        String pos = world + ":" + x + ":" + y + ":" + z;
        plugin.db().tx(c -> {
            String placer = null;
            long at = 0;
            try (PreparedStatement ps = Database.ps(c, "SELECT uuid, at FROM block_log WHERE world=? AND x=? AND z=? AND y=? AND action='PLACE' ORDER BY id DESC LIMIT 1", world, x, z, y);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { placer = rs.getString(1); at = rs.getLong(2); }
            }
            if (placer == null || now - at < minAge) return null;
            Clan pc = plugin.clans().clanOf(UUID.fromString(placer));
            if (pc == null) return null;
            for (War w : mine) {
                if (w.enemyOf(cl.id) != pc.id) continue;
                Long seen = Database.longVal(c, "SELECT COUNT(*) FROM war_blocks WHERE war_id=? AND pos=?", w.id, pos);
                if (seen != null && seen > 0) return null;
                Database.update(c, "INSERT INTO war_blocks(war_id, pos) VALUES (?,?)", w.id, pos);
                return w;
            }
            return null;
        }).thenAccept(w -> {
            if (w == null) return;
            Tasks.sync(() -> {
                List<Long> t = blockLog.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
                if (rateLimited(t, perHour, 3_600_000L)) return;
                addScore(w, cl.id, pts, "block");
                if (p.isOnline()) Msg.actionBar(p, "&c+" + pts + " очко войны &7(разрушение постройки врага)");
            });
        });
    }

    // ------------------------------------------------------------------ Завершение

    private void tick() {
        long now = System.currentTimeMillis();
        for (War w : new ArrayList<>(wars.values())) {
            if ("ACTIVE".equals(w.state) && now >= w.endsAt) {
                if (w.scoreA == w.scoreD) {
                    w.state = "OVERTIME";
                    w.overtimeUntil = now + cfg().getLong("war.overtime-max-hours", 24) * 3_600_000L;
                    plugin.db().tx(c -> Database.update(c, "UPDATE wars SET state='OVERTIME', overtime_until=? WHERE id=?", w.overtimeUntil, w.id));
                    Clan a = plugin.clans().byId(w.attacker), d = plugin.clans().byId(w.defender);
                    Msg.broadcast("war.overtime", "&6Война [{a}] — [{b}] закончилась вничью ({s}:{s}). Дополнительное время до первого очка!",
                            "a", a == null ? "?" : a.tag, "b", d == null ? "?" : d.tag, "s", w.scoreA);
                } else {
                    end(w, w.scoreA > w.scoreD ? w.attacker : w.defender, "time");
                }
            } else if ("OVERTIME".equals(w.state) && now >= w.overtimeUntil) {
                end(w, 0, "draw");
            }
        }
    }

    /** winner = 0 — ничья/мир. */
    public void end(War w, long winner, String reason) {
        if ("ENDED".equals(w.state)) return;
        w.state = "ENDED";
        wars.remove(w.id);
        long loser = winner == 0 ? 0 : w.enemyOf(winner);
        plugin.db().tx(c -> {
            Database.update(c, "UPDATE wars SET state='ENDED', winner=?, ends_at=? WHERE id=?", winner, System.currentTimeMillis(), w.id);
            if (winner != 0) {
                Database.update(c, "UPDATE clans SET wars_won = wars_won + 1 WHERE id=?", winner);
                Database.update(c, "UPDATE clans SET wars_lost = wars_lost + 1 WHERE id=?", loser);
            }
            return null;
        });
        Clan a = plugin.clans().byId(w.attacker), d = plugin.clans().byId(w.defender);
        String ta = a == null ? "?" : a.tag, td = d == null ? "?" : d.tag;
        if (winner == 0) {
            Msg.broadcast("war.ended-draw", reason.equals("peace") ? "&aКланы [{a}] и [{b}] заключили мир ({sa}:{sd})." : "&eВойна [{a}] — [{b}] завершилась вничью ({sa}:{sd}).",
                    "a", ta, "b", td, "sa", w.scoreA, "sd", w.scoreD);
        } else {
            Clan wc = plugin.clans().byId(winner), lc = plugin.clans().byId(loser);
            if (wc != null) wc.warsWon++;
            if (lc != null) lc.warsLost++;
            Msg.broadcast("war.ended", "&6&lКлан [{w}] победил в войне против [{l}] &7({sa}:{sd})", "w", wc == null ? "?" : wc.tag,
                    "l", lc == null ? "?" : lc.tag, "sa", w.scoreA, "sd", w.scoreD);
            if (wc != null && plugin.progression() != null) {
                long rep = cfg().getLong("war.winner-reputation", 50);
                for (UUID u : wc.members.keySet()) {
                    Player m = Bukkit.getPlayer(u);
                    if (m != null) {
                        plugin.progression().addReputation(m, rep, "war_win");
                        plugin.profiles().addStat(m, "wars_won", 1);
                    }
                }
            }
        }
        plugin.logs().sys("clan", "war_end", null, ta + " vs " + td + " " + w.scoreA + ":" + w.scoreD + " winner=" + winner + " reason=" + reason);
    }

    public void onDisband(Clan c) {
        for (War w : warsOf(c)) end(w, w.enemyOf(c.id), "disband");
    }
}
