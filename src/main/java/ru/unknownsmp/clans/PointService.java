package ru.unknownsmp.clans;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.gui.PagedMenu;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Точки заработка кланов. Администратор ставит точки на карте; клан захватывает точку, если его участники
 * стоят в её радиусе без соперников заданное время. Захваченная точка регулярно платит в банк клана,
 * пока её не отобьют.
 */
public final class PointService implements Module {
    public static final class Point {
        final String id;
        final String world;
        final int x, y, z, radius;
        volatile long owner;
        volatile long capturedAt;
        long progressClan;
        int progress;
        BossBar bar;

        Point(String id, String world, int x, int y, int z, int radius, long owner, long capturedAt) {
            this.id = id; this.world = world; this.x = x; this.y = y; this.z = z; this.radius = radius;
            this.owner = owner; this.capturedAt = capturedAt;
        }

        Location loc() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x + 0.5, y, z + 0.5);
        }
    }

    private final UnknownSMP plugin;
    private final Map<String, Point> points = new ConcurrentHashMap<>();
    private boolean enabled = true, requireOnline = true;
    private int captureSeconds = 60, defaultRadius = 8;
    private long income = 5000, incomeMinutes = 10;
    private long nextPayout;

    public PointService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "clanpoints"; }
    @Override public String title() { return "Точки заработка кланов"; }
    @Override public String[] depends() { return new String[]{"clans"}; }

    @Override
    public void enable() throws Exception {
        reload();
        plugin.db().queryBlocking(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, world, x, y, z, radius, owner, captured_at FROM clan_points"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Point p = new Point(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getLong(7), rs.getLong(8));
                    points.put(p.id, p);
                }
            }
            return null;
        });
        nextPayout = System.currentTimeMillis() + incomeMinutes * 60_000L;
        Tasks.timer(40, 20, this::tick);
    }

    @Override
    public void disable() {
        for (Point p : points.values()) if (p.bar != null) p.bar.removeAll();
    }

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().get("clans.yml");
        enabled = c.getBoolean("points.enabled", true);
        captureSeconds = Math.max(5, c.getInt("points.capture-seconds", 60));
        defaultRadius = Math.max(2, c.getInt("points.radius", 8));
        income = Math.max(0, c.getLong("points.income", 5000));
        incomeMinutes = Math.max(1, c.getLong("points.income-minutes", 10));
        requireOnline = c.getBoolean("points.require-online-member", true);
    }

    // ------------------------------------------------------------------ Захват

    private boolean counts(Player p) {
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) return false;
        if (plugin.afk() != null && plugin.afk().isAfk(p)) return false;
        return plugin.auth() == null || plugin.auth().isAuthed(p);
    }

    private void tick() {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        for (Point pt : points.values()) {
            Location c = pt.loc();
            if (c == null || !c.isChunkLoaded()) continue;
            if (now % 2000 < 1000) c.getWorld().spawnParticle(Particle.END_ROD, c.clone().add(0, 1.5, 0), 6, 0.3, 0.6, 0.3, 0.01);
            Map<Long, Integer> present = new HashMap<>();
            List<Player> inside = new ArrayList<>();
            double r2 = (double) pt.radius * pt.radius;
            for (Player p : c.getWorld().getPlayers()) {
                Location l = p.getLocation();
                double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ();
                if (dx * dx + dz * dz > r2 || Math.abs(l.getY() - c.getY()) > pt.radius) continue;
                inside.add(p);
                if (!counts(p)) continue;
                Clan cl = plugin.clans().clanOf(p);
                if (cl != null) present.merge(cl.id, 1, Integer::sum);
            }
            updateBar(pt, inside);
            if (present.isEmpty()) {
                // Никого — прогресс медленно спадает.
                if (pt.progress > 0) pt.progress = Math.max(0, pt.progress - 1);
                continue;
            }
            if (present.size() > 1) continue; // спорная точка — прогресс замирает
            long clan = present.keySet().iterator().next();
            if (clan == pt.owner) { pt.progress = 0; pt.progressClan = 0; continue; }
            if (pt.progressClan != clan) { pt.progressClan = clan; pt.progress = 0; }
            pt.progress++;
            if (pt.progress == 1) {
                Clan att = plugin.clans().byId(clan);
                Clan own = plugin.clans().byId(pt.owner);
                if (own != null && att != null) plugin.clans().broadcastClan(own, "points.under-attack", "&cКлан [{t}] захватывает вашу точку «{p}»!", "t", att.tag, "p", pt.id);
            }
            if (pt.progress >= captureSeconds) capture(pt, clan, inside);
        }
        if (now >= nextPayout) {
            nextPayout = now + incomeMinutes * 60_000L;
            payout();
        }
    }

    private void updateBar(Point pt, List<Player> inside) {
        if (pt.bar == null) pt.bar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SEGMENTED_10);
        Clan own = plugin.clans().byId(pt.owner);
        Clan att = plugin.clans().byId(pt.progressClan);
        String title = "&6Точка «" + pt.id + "» &7— владелец: " + (own == null ? "&8никто" : "&d[" + own.tag + "]");
        if (pt.progress > 0 && att != null) title += " &8| &eзахват [" + att.tag + "] " + (pt.progress * 100 / captureSeconds) + "%";
        pt.bar.setTitle(Text.legacySection(title));
        pt.bar.setProgress(Math.max(0, Math.min(1, pt.progress / (double) captureSeconds)));
        for (Player p : new ArrayList<>(pt.bar.getPlayers())) if (!inside.contains(p)) pt.bar.removePlayer(p);
        for (Player p : inside) pt.bar.addPlayer(p);
    }

    private void capture(Point pt, long clan, List<Player> inside) {
        long prevOwner = pt.owner;
        pt.owner = clan;
        pt.capturedAt = System.currentTimeMillis();
        pt.progress = 0;
        pt.progressClan = 0;
        plugin.db().tx(c -> Database.update(c, "UPDATE clan_points SET owner=?, captured_at=? WHERE id=?", clan, pt.capturedAt, pt.id));
        Clan cl = plugin.clans().byId(clan);
        Clan prev = plugin.clans().byId(prevOwner);
        Msg.broadcast("points.captured", "&6✦ Клан &d[{t}]&6 захватил точку заработка «{p}»{f}!", "t", cl == null ? "?" : cl.tag, "p", pt.id,
                "f", prev == null ? "" : " у клана [" + prev.tag + "]");
        for (Player p : inside) Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        if (cl != null) plugin.logs().sys("clan", "point_capture", pt.loc(), cl.tag + " " + pt.id);
    }

    private void payout() {
        if (income <= 0) return;
        for (Point pt : points.values()) {
            if (pt.owner == 0) continue;
            Clan cl = plugin.clans().byId(pt.owner);
            if (cl == null) continue;
            if (requireOnline) {
                boolean any = false;
                for (UUID u : cl.members.keySet()) if (Bukkit.getPlayer(u) != null) { any = true; break; }
                if (!any) continue;
            }
            Tasks.then(plugin.db().tx(c -> {
                Database.update(c, "UPDATE clans SET bank = bank + ? WHERE id=?", income, cl.id);
                Database.update(c, "INSERT INTO clan_bank_log(clan_id, uuid, delta, at, reason) VALUES (?,?,?,?,?)", cl.id, "clan_point", income, System.currentTimeMillis(), "point:" + pt.id);
                Long bank = Database.longVal(c, "SELECT bank FROM clans WHERE id=?", cl.id);
                return bank == null ? 0L : bank;
            }), bank -> {
                cl.bank = bank;
                plugin.clans().broadcastClan(cl, "points.income", "&6+{a} &7в банк клана от точки «{p}». Банк: &6{b}", "a", Text.money(income), "p", pt.id, "b", Text.money(bank));
            }, null);
        }
    }

    public void onDisband(Clan cl) {
        for (Point pt : points.values()) if (pt.owner == cl.id) {
            pt.owner = 0;
            plugin.db().tx(c -> Database.update(c, "UPDATE clan_points SET owner=0 WHERE id=?", pt.id));
        }
    }

    // ------------------------------------------------------------------ Меню игроков

    public void menu(Player p, Menu parent) {
        new PagedMenu(p, "&8Точки заработка кланов") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                Clan mine = plugin.clans().clanOf(viewer);
                for (Point pt : points.values()) {
                    Clan own = plugin.clans().byId(pt.owner);
                    boolean ours = mine != null && own != null && mine.id == own.id;
                    out.add(new Entry(Items.of(ours ? Material.LIME_BANNER : own == null ? Material.WHITE_BANNER : Material.RED_BANNER)
                            .name("&6Точка «" + pt.id + "»").lore("&7Координаты: &f" + pt.x + ", " + pt.y + ", " + pt.z + " &7(" + pt.world + ")",
                                    "&7Владелец: " + (own == null ? "&8никто" : "&d[" + own.tag + "] " + own.name),
                                    "&7Доход: &6" + Text.money(income) + " &7каждые " + incomeMinutes + " мин в банк клана",
                                    "&7Захват: " + captureSeconds + " сек в радиусе " + pt.radius + " блоков без соперников").build(), null));
                }
                if (out.isEmpty()) out.add(new Entry(Items.of(Material.BARRIER).name("&7Точек пока нет").lore("&7Администрация ещё не поставила точки").build(), null));
                return out;
            }
        }.parent(parent).open();
    }

    // ------------------------------------------------------------------ Админ: /usmp clanpoint

    public void admin(CommandSender s, String[] r) {
        if (r.length == 0 || r[0].equalsIgnoreCase("list")) {
            Msg.raw(s, "&8--- &6Точки заработка &8---");
            for (Point pt : points.values()) {
                Clan own = plugin.clans().byId(pt.owner);
                Msg.raw(s, "&e" + pt.id + " &f" + pt.world + " " + pt.x + "," + pt.y + "," + pt.z + " r=" + pt.radius + " &7владелец: " + (own == null ? "—" : own.tag));
            }
            Msg.raw(s, "&7/usmp clanpoint add <id> [радиус] — точка там, где вы стоите; /usmp clanpoint remove <id>; /usmp clanpoint reset <id>");
            return;
        }
        switch (r[0].toLowerCase(java.util.Locale.ROOT)) {
            case "add" -> {
                if (!(s instanceof Player p) || r.length < 2) { Msg.error(s, "points.add-usage", "Использование (в игре): /usmp clanpoint add <id> [радиус]"); return; }
                String id = r[1].toLowerCase(java.util.Locale.ROOT);
                if (!id.matches("[a-z0-9_а-яё-]{1,24}")) { Msg.error(s, "points.bad-id", "Название: латиница/кириллица, цифры, до 24 символов."); return; }
                int radius = defaultRadius;
                if (r.length > 2) try { radius = Math.max(2, Math.min(64, Integer.parseInt(r[2]))); } catch (NumberFormatException ignored) {}
                Location l = p.getLocation();
                Point pt = new Point(id, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), radius, 0, 0);
                Point old = points.put(id, pt);
                if (old != null && old.bar != null) old.bar.removeAll();
                final int rad = radius;
                plugin.db().tx(c -> {
                    Database.update(c, "DELETE FROM clan_points WHERE id=?", id);
                    return Database.update(c, "INSERT INTO clan_points(id, world, x, y, z, radius, owner, captured_at) VALUES (?,?,?,?,?,?,0,0)",
                            id, pt.world, pt.x, pt.y, pt.z, rad);
                });
                Msg.ok(s, "points.added", "Точка «{id}» создана (радиус {r}).", "id", id, "r", radius);
            }
            case "remove" -> {
                if (r.length < 2) return;
                Point pt = points.remove(r[1].toLowerCase(java.util.Locale.ROOT));
                if (pt == null) { Msg.error(s, "points.unknown", "Нет такой точки."); return; }
                if (pt.bar != null) pt.bar.removeAll();
                plugin.db().tx(c -> Database.update(c, "DELETE FROM clan_points WHERE id=?", pt.id));
                Msg.ok(s, "points.removed", "Точка удалена.");
            }
            case "reset" -> {
                if (r.length < 2) return;
                Point pt = points.get(r[1].toLowerCase(java.util.Locale.ROOT));
                if (pt == null) { Msg.error(s, "points.unknown", "Нет такой точки."); return; }
                pt.owner = 0; pt.progress = 0; pt.progressClan = 0;
                plugin.db().tx(c -> Database.update(c, "UPDATE clan_points SET owner=0 WHERE id=?", pt.id));
                Msg.ok(s, "points.reset", "Точка сброшена — ничья.");
            }
            default -> Msg.error(s, "points.usage", "Использование: /usmp clanpoint add|remove|reset|list");
        }
    }

    public List<String> ids() { return new ArrayList<>(points.keySet()); }
}
