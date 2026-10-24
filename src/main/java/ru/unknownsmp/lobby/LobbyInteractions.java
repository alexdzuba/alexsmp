package ru.unknownsmp.lobby;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.progression.LeaderboardService;
import ru.unknownsmp.world.Anchors;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Мини-интерактивы лобби: сидеть на ступенях, пианино, катапульты, подарок дня (/daily),
 * голограммы таблиц лидеров (лобби и город SMP) и тайная кнопка лабиринта в городе.
 */
public final class LobbyInteractions implements Listener {
    /** Высота посадочной стойки над нижним краем блока ступени. */
    private static final double SIT_Y = 0.35;

    private record Board(String anchor, String id, String title, boolean money) {}

    private static final List<Board> BOARDS = List.of(
            new Board("board_lobby_wealth", "wealth", "&6&lТОП БОГАТСТВА", true),
            new Board("board_lobby_pvp", "pvp", "&c&lТОП PVP", false),
            new Board("board_smp", "wealth", "&6&lТОП БОГАТСТВА", true),
            new Board("board_clicks_l", "clicks", "&6&lТОП КЛИКЕРА", false),
            new Board("board_clicks_c", "clicks", "&6&lТОП КЛИКЕРА", false),
            new Board("board_tir_l", "tir", "&c&lТОП ТИРА", false),
            new Board("board_tir_c", "tir", "&c&lТОП ТИРА", false));

    private final UnknownSMP plugin;
    private final LobbyService lobby;
    private final Map<UUID, ArmorStand> seated = new ConcurrentHashMap<>();
    private final Map<UUID, Long> launchCd = new ConcurrentHashMap<>();
    private final Map<String, TextDisplay> boards = new ConcurrentHashMap<>();
    private long lastBoardText;

    LobbyInteractions(UnknownSMP plugin, LobbyService lobby) {
        this.plugin = plugin;
        this.lobby = lobby;
    }

    void start() {
        plugin.commands().add("daily").player().perm("usmp.player.lobby").module("lobby").cat("Статистика")
                .usage("/daily").desc("Подарок дня: награда растёт за каждый день подряд").exec((s, a) -> daily((Player) s));
        Tasks.timer(100, 400, this::boardTick);
        Tasks.timer(20, 10, this::ambient);
    }

    // ------------------------------------------------------------------ Клики

    /** @return true, если клик обработан (событие нужно отменить). */
    boolean click(Player p, Block b, Action action) {
        Material t = b.getType();
        if (t == Material.NOTE_BLOCK) {
            if (b.getBlockData() instanceof NoteBlock nb) {
                float pitch = (float) Math.pow(2.0, (nb.getNote().getId() - 12) / 12.0);
                b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_NOTE_BLOCK_HARP, 1.2f, pitch);
                b.getWorld().spawnParticle(Particle.NOTE, b.getLocation().add(0.5, 1.2, 0.5), 1, 0, 0, 0, nb.getNote().getId() / 24.0);
            }
            return true;
        }
        if (t == Material.ENDER_CHEST) {
            if (action == Action.RIGHT_CLICK_BLOCK) daily(p);
            return true;
        }
        if (action == Action.RIGHT_CLICK_BLOCK && t.name().endsWith("_STAIRS") && p.getInventory().getItemInMainHand().getType().isAir()) {
            sit(p, b);
            return true;
        }
        return false;
    }

    void sit(Player p, Block b) {
        if (seated.containsKey(p.getUniqueId()) || p.isInsideVehicle()) return;
        if (!b.getRelative(0, 1, 0).isPassable()) return;
        Location l = b.getLocation().add(0.5, SIT_Y + 0.5, 0.5);
        for (var e : b.getWorld().getNearbyEntities(l, 0.4, 0.8, 0.4)) {
            if (e instanceof ArmorStand as && as.getPersistentDataContainer().has(Keys.LOBBY_ENTITY, PersistentDataType.BYTE)) return;
        }
        l.setYaw(p.getLocation().getYaw());
        ArmorStand st = b.getWorld().spawn(l, ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setMarker(true);
            a.setGravity(false);
            a.setSilent(true);
            a.setPersistent(false);
            a.getPersistentDataContainer().set(Keys.LOBBY_ENTITY, PersistentDataType.BYTE, (byte) 1);
        });
        st.addPassenger(p);
        seated.put(p.getUniqueId(), st);
        Msg.actionBar(p, "&7Вы сидите. Нажмите &fShift&7, чтобы встать.");
    }

    @EventHandler
    public void dismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ArmorStand st = seated.remove(p.getUniqueId());
        if (st == null) return;
        Location up = st.getLocation().add(0, 0.9, 0);
        up.setYaw(p.getLocation().getYaw());
        up.setPitch(p.getLocation().getPitch());
        Tasks.sync(() -> {
            st.remove();
            if (p.isOnline()) p.teleport(up);
        });
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        ArmorStand st = seated.remove(e.getPlayer().getUniqueId());
        if (st != null) st.remove();
        launchCd.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ Катапульта

    @EventHandler
    public void plate(PlayerInteractEvent e) {
        if (e.getAction() != Action.PHYSICAL) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.STONE_PRESSURE_PLATE || !(lobby.isLobby(b.getWorld()) || (plugin.world() != null && plugin.world().inSpawnZone(b.getLocation())))) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long last = launchCd.get(p.getUniqueId());
        if (last != null && now - last < 2500) return;
        launchCd.put(p.getUniqueId(), now);
        e.setCancelled(true);
        Vector dir = p.getLocation().getDirection().setY(0).normalize().multiply(0.9);
        p.setVelocity(dir.setY(1.5));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 160, 0, false, false, false));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
        p.getWorld().spawnParticle(Particle.FIREWORK, p.getLocation(), 30, 0.3, 0.1, 0.3, 0.1);
        Msg.actionBar(p, "&bВзлёт!");
    }

    // ------------------------------------------------------------------ Подарок дня

    private void daily(Player p) {
        if (!plugin.auth().isAuthed(p)) return;
        String key = "daily." + p.getUniqueId();
        String[] cur = plugin.state().get(key, "0;0").split(";");
        long today = LocalDate.now().toEpochDay();
        long lastDay = 0;
        int streak = 0;
        try {
            lastDay = Long.parseLong(cur[0]);
            streak = Integer.parseInt(cur[1]);
        } catch (Exception ignored) { }
        if (lastDay == today) {
            Msg.error(p, "daily.done", "Сегодняшний подарок уже получен. Приходите завтра! Серия: {s}", "s", String.valueOf(streak));
            return;
        }
        streak = lastDay == today - 1 ? streak + 1 : 1;
        long base = plugin.configs().main().getLong("lobby.daily-base", 300);
        long bonus = plugin.configs().main().getLong("lobby.daily-streak-bonus", 150);
        long reward = base + bonus * Math.min(streak - 1, 6);
        plugin.state().set(key, today + ";" + streak);
        plugin.economy().deposit(p.getUniqueId(), reward, "daily", null);
        plugin.profiles().addStat(p, "daily_claims", 1);
        Msg.title(p, "&6&lПодарок дня", "&f+" + Text.money(reward) + " &7(серия: " + streak + ")", 5, 50, 15);
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 40, 0.5, 0.8, 0.5, 0.2);
    }

    // ------------------------------------------------------------------ Секрет в городе SMP

    @EventHandler(priority = EventPriority.HIGH)
    public void mazeButton(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || e.getAction() != Action.RIGHT_CLICK_BLOCK || !b.getType().name().endsWith("_BUTTON") || lobby.isLobby(b.getWorld())) return;
        lobby.checkSecret(e.getPlayer(), b);
    }

    // ------------------------------------------------------------------ Таблицы лидеров

    private void boardTick() {
        LeaderboardService lb = plugin.leaderboards();
        if (lb == null) return;
        boolean refresh = System.currentTimeMillis() - lastBoardText > 300_000;
        for (Board bd : BOARDS) {
            Location l = Anchors.get(bd.anchor());
            if (l == null || l.getWorld() == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            String k = bd.anchor();
            TextDisplay td = boards.get(k);
            boolean fresh = td == null || !td.isValid();
            if (!fresh && !refresh) continue;
            lb.get(bd.id()).thenAccept(rows -> Tasks.sync(() -> {
                StringBuilder sb = new StringBuilder(bd.title()).append("\n&8────────────");
                int i = 1;
                for (var r : rows) {
                    if (i > 7) break;
                    String col = i == 1 ? "&6" : i == 2 ? "&f" : i == 3 ? "&c" : "&7";
                    sb.append("\n").append(col).append(i).append(". &f").append(r.name()).append(" &8— &e").append(bd.money() ? Text.money(r.value()) : String.valueOf(r.value()));
                    i++;
                }
                if (rows.isEmpty()) sb.append("\n&7Пока пусто");
                TextDisplay cur = boards.get(k);
                if (cur == null || !cur.isValid()) {
                    cur = l.getWorld().spawn(l, TextDisplay.class, d -> {
                        d.setBillboard(Display.Billboard.CENTER);
                        d.setBackgroundColor(Color.fromARGB(110, 0, 10, 30));
                        d.setShadowed(true);
                        d.setPersistent(false);
                        d.getPersistentDataContainer().set(Keys.LOBBY_ENTITY, PersistentDataType.BYTE, (byte) 1);
                    });
                    boards.put(k, cur);
                }
                cur.text(Text.c(sb.toString()));
            }));
        }
        if (refresh) lastBoardText = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ Атмосфера

    private void ambient() {
        World w = lobby.world();
        if (w == null || w.getPlayers().isEmpty()) return;
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            if (Math.abs(l.getZ()) > 25) {
                double z = l.getZ() < 0 ? -38 : 38;
                Particle pt = l.getZ() < 0 ? Particle.END_ROD : Particle.FLAME;
                for (int i = 0; i < 3; i++) {
                    p.spawnParticle(pt, (Math.random() - 0.5) * 8, 101 + Math.random() * 6, z + (Math.random() - 0.5), 1, 0, 0.05, 0, 0.01);
                }
            }
        }
    }
}
