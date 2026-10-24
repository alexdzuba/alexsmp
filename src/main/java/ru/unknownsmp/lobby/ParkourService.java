package ru.unknownsmp.lobby;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.lobby.ParkourCourses.Course;
import ru.unknownsmp.lobby.ParkourCourses.Jump;
import ru.unknownsmp.lobby.ParkourCourses.Kind;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Три паркура лобби (лёгкий, средний, сложный). Старт и финиш — лёгкие плиты, чекпоинты — тяжёлые.
 * Раскладка берётся из {@link ParkourCourses}. Лучшее время хранится по каждому паркуру отдельно.
 */
public final class ParkourService implements Listener {
    private record Plate(Course course, Jump jump) {}

    private final UnknownSMP plugin;
    private final LobbyService lobby;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    private final Map<String, Plate> plates = new HashMap<>();

    private static final class Run {
        Course course;
        long start;
        Location checkpoint;
        int cpIndex;
    }

    ParkourService(UnknownSMP plugin, LobbyService lobby) {
        this.plugin = plugin;
        this.lobby = lobby;
        for (Course c : ParkourCourses.all()) {
            for (Jump j : c.jumps()) {
                if (j.kind() != Kind.NORMAL) plates.put(key(j.px(), j.y() + 1, j.pz()), new Plate(c, j));
            }
        }
        plugin.commands().add("parkour").player().perm("usmp.player.lobby").module("lobby").cat("Перемещение")
                .usage("/parkour [reset|leave]").desc("Паркур: вернуться на чекпоинт или выйти").exec((s, a) -> {
                    Player p = (Player) s;
                    Run r = runs.get(p.getUniqueId());
                    if (r == null) { Msg.error(p, "parkour.none", "Вы не проходите паркур. Встаньте на стартовую плиту."); return; }
                    if (a.length > 0 && a[0].equalsIgnoreCase("leave")) {
                        runs.remove(p.getUniqueId());
                        p.teleport(lobby.spawn());
                        Msg.send(p, "parkour.left", "&7Паркур прерван.");
                    } else {
                        p.teleport(r.checkpoint.clone());
                        Msg.actionBar(p, "&bВозврат на чекпоинт");
                    }
                });
        Tasks.timer(5, 5, () -> {
            for (var e : runs.entrySet()) {
                Player p = plugin.getServer().getPlayer(e.getKey());
                if (p == null) continue;
                Run r = e.getValue();
                Msg.actionBar(p, r.course.color() + "Паркур «" + r.course.title() + "»: &f" + fmt(System.currentTimeMillis() - r.start)
                        + " &7| чекпоинт " + r.cpIndex + "/" + checkpoints(r.course));
            }
        });
    }

    private static String key(int x, int y, int z) { return x + "," + y + "," + z; }

    private static int checkpoints(Course c) {
        int n = 0;
        for (Jump j : c.jumps()) if (j.kind() == Kind.CHECKPOINT) n++;
        return n;
    }

    public boolean isRunning(Player p) { return runs.containsKey(p.getUniqueId()); }

    public Location checkpoint(Player p) {
        Run r = runs.get(p.getUniqueId());
        return r == null ? lobby.spawn() : r.checkpoint.clone();
    }

    private Location spotOf(Course c, Jump j) {
        Location l = new Location(lobby.world(), j.px() + 0.5, j.y() + 1.1, j.pz() + 0.5);
        l.setYaw((float) Math.toDegrees(Math.atan2(-c.dx(), c.dz())));
        return l;
    }

    @EventHandler
    public void plate(PlayerInteractEvent e) {
        if (e.getAction() != Action.PHYSICAL) return;
        Block b = e.getClickedBlock();
        if (b == null || !lobby.isLobby(b.getWorld())) return;
        Plate pl = plates.get(key(b.getX(), b.getY(), b.getZ()));
        if (pl == null) return;
        Player p = e.getPlayer();
        Run r = runs.get(p.getUniqueId());
        switch (pl.jump().kind()) {
            case START -> {
                if (r != null && r.course == pl.course() && System.currentTimeMillis() - r.start < 1500) return;
                Run nr = new Run();
                nr.course = pl.course();
                nr.start = System.currentTimeMillis();
                nr.checkpoint = spotOf(pl.course(), pl.jump());
                runs.put(p.getUniqueId(), nr);
                Msg.title(p, pl.course().color() + "&l" + pl.course().title(), "&7Падение — возврат на чекпоинт", 3, 25, 10);
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.5f);
            }
            case CHECKPOINT -> {
                if (r == null || r.course != pl.course() || r.cpIndex >= pl.jump().index() / 8) return;
                r.checkpoint = spotOf(pl.course(), pl.jump());
                r.cpIndex = pl.jump().index() / 8;
                Msg.actionBar(p, "&bЧекпоинт " + r.cpIndex + " сохранён");
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.6f);
            }
            case FINISH -> {
                if (r != null && r.course == pl.course()) finish(p, r);
            }
            default -> { }
        }
    }

    private void finish(Player p, Run r) {
        runs.remove(p.getUniqueId());
        Course c = r.course;
        long time = System.currentTimeMillis() - r.start;
        if (time < c.minMs()) {
            plugin.logs().suspicious(p, "parkour_time", c.id() + " " + fmt(time));
            Msg.error(p, "parkour.invalid", "Результат не засчитан: слишком быстро.");
            return;
        }
        Msg.title(p, "&a&lФИНИШ!", c.color() + c.title() + " &7— &f" + fmt(time), 5, 50, 15);
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.3f);
        boolean first = plugin.profiles().get(p) != null && plugin.profiles().get(p).stat("parkour_" + c.id()) == 0;
        plugin.profiles().addStat(p, "parkour_done", 1);
        plugin.profiles().addStat(p, "parkour_" + c.id(), 1);
        final String u = p.getUniqueId().toString();
        Tasks.then(plugin.db().tx(conn -> {
            Long best = Database.longVal(conn, "SELECT best_ms FROM parkour WHERE uuid=? AND course=?", u, c.id());
            if (best == null) Database.update(conn, "INSERT INTO parkour(uuid, course, best_ms) VALUES (?,?,?)", u, c.id(), time);
            else if (time < best) Database.update(conn, "UPDATE parkour SET best_ms=? WHERE uuid=? AND course=?", time, u, c.id());
            return best;
        }), best -> {
            if (best == null || time < best) Msg.ok(p, "parkour.record", "Новый личный рекорд: {t}", "t", fmt(time));
            else Msg.send(p, "parkour.result", "&7Время: &f{t}&7. Рекорд: &f{b}", "t", fmt(time), "b", fmt(best));
        }, null);
        if (first) {
            long reward = plugin.configs().main().getLong("lobby.parkour-rewards." + c.id(), plugin.configs().main().getLong("lobby.parkour-first-reward", 500));
            if (reward > 0) {
                plugin.economy().deposit(p.getUniqueId(), reward, "parkour_first", null);
                Msg.send(p, "parkour.reward", "&6+{r} монет &7за первое прохождение.", "r", String.valueOf(reward));
            }
        }
        Tasks.later(60, () -> { if (p.isOnline() && lobby.isLobby(p.getWorld())) p.teleport(lobby.spawn()); });
    }

    @EventHandler
    public void move(PlayerMoveEvent e) {
        if (!e.hasChangedPosition()) return;
        Run r = runs.get(e.getPlayer().getUniqueId());
        if (r == null) return;
        if (e.getTo().getY() < r.checkpoint.getY() - 12) {
            e.setTo(r.checkpoint.clone());
            Msg.actionBar(e.getPlayer(), "&cУпали — возврат на чекпоинт");
            Msg.sound(e.getPlayer(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.4f, 1.3f);
        }
    }

    @EventHandler
    public void tp(PlayerTeleportEvent e) {
        if (!runs.containsKey(e.getPlayer().getUniqueId())) return;
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || e.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND
                || (e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN && !lobby.isLobby(e.getTo().getWorld()))) {
            runs.remove(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        runs.remove(e.getPlayer().getUniqueId());
    }

    public static String fmt(long ms) {
        return String.format("%d:%02d.%03d", ms / 60000, (ms / 1000) % 60, ms % 1000);
    }
}
