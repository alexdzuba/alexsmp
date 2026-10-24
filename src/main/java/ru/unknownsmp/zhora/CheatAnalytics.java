package ru.unknownsmp.zhora;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.profile.Profile;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Аналитика подозрительного поведения. Детекторы собирают объективную телеметрию (добыча руд, бой, поток предметов,
 * экономика) и сообщают о ПАТТЕРНАХ, а не выносят вердикты: результат — дело с доказательствами для администрации.
 * Языковая модель не принимает решений и никого не наказывает; жалобы игроков хранятся отдельно и серьёзность не повышают.
 * Новые детекторы добавляются реализацией {@link Detector} и регистрацией в {@link #register(Detector)}.
 */
public final class CheatAnalytics {
    /** Базовый интерфейс детектора. */
    public interface Detector {
        String id();

        /** Категория расследования: xray, pvp, dupe, economy, other. */
        String category();

        default void reload(YamlConfiguration cfg) {}

        /** Краткое описание текущих накопленных данных по игроку (для расследования по жалобе), либо null. */
        default String snapshot(UUID u) { return null; }

        default void start() {}

        default void stop() {}
    }

    public record Suspicion(UUID uuid, String name, String detector, String category, int severity, String details) {}

    private final UnknownSMP plugin;
    private final ZhoraService zhora;
    private final ZhoraStore store;
    private final List<Detector> detectors = new ArrayList<>();
    private final Map<String, Long> lastReport = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastNotify = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile boolean notifyStaff = true;
    private volatile int notifyMin = 3, openMin = 2;
    private volatile long notifyIntervalMs = 600_000;
    private volatile boolean ignoreStaff = true;
    private volatile long reportCooldownMs = 120_000;

    public CheatAnalytics(UnknownSMP plugin, ZhoraService zhora, ZhoraStore store) {
        this.plugin = plugin;
        this.zhora = zhora;
        this.store = store;
        register(new OreDetector());
        register(new CombatDetector());
        register(new ItemFlowDetector());
        register(new EconomyDetector());
        register(new MovementDetector());
        register(new ActionRateDetector());
    }

    public void register(Detector d) {
        detectors.add(d);
        if (d instanceof Listener l) Bukkit.getPluginManager().registerEvents(l, plugin);
    }

    public List<Detector> detectors() { return detectors; }

    public void reload(YamlConfiguration cfg) {
        enabled = cfg.getBoolean("telemetry.enabled", true);
        notifyStaff = cfg.getBoolean("investigations.notify-staff", true);
        notifyMin = cfg.getInt("investigations.notify-min-severity", 3);
        openMin = cfg.getInt("investigations.open-case-min-severity", 2);
        notifyIntervalMs = cfg.getLong("investigations.notify-interval-minutes", 10) * 60_000L;
        ignoreStaff = cfg.getBoolean("investigations.ignore-staff", true);
        riskThreshold = cfg.getDouble("investigations.risk-threshold", 10);
        reportCooldownMs = cfg.getLong("investigations.report-cooldown-seconds", 120) * 1000L;
        for (Detector d : detectors) d.reload(cfg);
    }

    public void start() {
        for (Detector d : detectors) d.start();
        if (riskTask == null) riskTask = Bukkit.getScheduler().runTaskTimer(plugin, this::riskTick, 20 * 20, 20 * 20);
    }

    public void stop() {
        for (Detector d : detectors) d.stop();
        if (riskTask != null) { riskTask.cancel(); riskTask = null; }
    }

    private boolean skip(Player p) {
        if (!enabled || p == null) return true;
        if (ignoreStaff && plugin.roles().isStaff(p)) return true;
        GameMode gm = p.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }

    private boolean realWorld(org.bukkit.World w) {
        if (plugin.lobby() != null && plugin.lobby().isLobby(w)) return false;
        return plugin.duels() == null || !plugin.duels().isDuelWorld(w);
    }

    /** Сообщить о подозрительном паттерне (вызывается детекторами). Ничего не наказывает и не пишет в публичный чат. */
    public void report(Suspicion s) {
        if (!enabled) return;
        if (!s.detector().equals("risk")) addRisk(s.uuid(), s.severity());
        String key = s.uuid() + "|" + s.detector();
        long now = System.currentTimeMillis();
        Long last = lastReport.get(key);
        if (last != null && now - last < reportCooldownMs) return;
        lastReport.put(key, now);
        plugin.logs().log(s.uuid(), s.name(), "zhora", "suspicion", s.detector() + " sev=" + s.severity() + " " + s.details());
        if (s.severity() < openMin) {
            // Слабые сигналы пишем только в журнал: дело не открываем.
            return;
        }
        store.openCase(s.uuid(), s.name(), s.category(), s.severity(), "Автоматически: " + s.detector()).thenCompose(id ->
                store.addEvidence(id, s.detector(), s.severity(), s.details(), false).thenApply(v -> id)).thenAccept(id -> {
            if (!notifyStaff || !plugin.configs().main().getBoolean("alerts.chat", false) || s.severity() < notifyMin) return;
            long n = System.currentTimeMillis();
            Long ln = lastNotify.get(id);
            if (ln != null && n - ln < notifyIntervalMs) return;
            lastNotify.put(id, n);
            Tasks.sync(() -> {
                for (Player st : Bukkit.getOnlinePlayers()) {
                    if (!plugin.roles().has(st, "usmp.mod.alerts")) continue;
                    Profile pr = plugin.profiles().get(st);
                    if (pr != null && !pr.setting("staff_alerts")) continue;
                    Msg.raw(st, "&6[Жора/проверка] &f" + s.name() + "&7: " + s.category() + ", серьёзность " + s.severity() + " &8— " + s.details()
                            + " &7(дело №" + id + ": &f/zhora case " + s.name() + "&7)");
                }
            });
        }).exceptionally(t -> {
            plugin.getLogger().warning("[Жора] Не удалось записать подозрение: " + t.getClass().getSimpleName());
            return null;
        });
    }

    /** Снимок накопленных данных всех детекторов по игроку (для расследования по жалобе). */
    public List<String> snapshot(UUID u) {
        List<String> out = new ArrayList<>();
        for (Detector d : detectors) {
            String s = d.snapshot(u);
            if (s != null && !s.isBlank()) out.add(d.id() + ": " + s);
        }
        return out;
    }

    // ================================================================== Добыча руд

    private final class OreDetector implements Detector, Listener {
        private final class State {
            int stone, valuable;
            final List<Vector> clusters = new ArrayList<>();
            long startedAt = System.currentTimeMillis();
            int lastClusters, lastValuable, lastTotal;
        }

        private final Map<UUID, State> states = new ConcurrentHashMap<>();
        private int window = 150, minValuable = 6, minClusters = 4;
        private double clusterDist = 12;
        private boolean on = true;
        private final Set<Material> valuables = EnumSet.of(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, Material.ANCIENT_DEBRIS,
                Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);
        private final Set<Material> stoneLike = EnumSet.of(Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.ANDESITE, Material.DIORITE, Material.GRANITE,
                Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE, Material.COBBLESTONE);

        @Override public String id() { return "ore"; }

        @Override public String category() { return "xray"; }

        @Override
        public void reload(YamlConfiguration c) {
            on = c.getBoolean("telemetry.ore", true);
            window = Math.max(50, c.getInt("investigations.ore.window", 150));
            minValuable = Math.max(2, c.getInt("investigations.ore.min-valuable", 6));
            minClusters = Math.max(2, c.getInt("investigations.ore.min-clusters", 4));
            clusterDist = Math.max(4, c.getDouble("investigations.ore.cluster-distance", 12));
        }

        @Override
        public String snapshot(UUID u) {
            State s = states.get(u);
            if (s == null) return "данных о добыче нет";
            return "в текущем окне разрушено камня " + s.stone + ", редких руд " + s.valuable + ", разных залежей " + s.clusters.size()
                    + "; предыдущее окно: руд " + s.lastValuable + " из " + s.lastTotal + " блоков, залежей " + s.lastClusters;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBreak(BlockBreakEvent e) {
            if (!on) return;
            Player p = e.getPlayer();
            Block b = e.getBlock();
            if (skip(p) || !realWorld(b.getWorld())) return;
            Material m = b.getType();
            boolean val = valuables.contains(m);
            if (!val && !stoneLike.contains(m)) return;
            State s = states.computeIfAbsent(p.getUniqueId(), k -> new State());
            synchronized (s) {
                if (val) {
                    s.valuable++;
                    Vector v = b.getLocation().toVector();
                    boolean near = false;
                    for (Vector c : s.clusters) if (c.distance(v) <= clusterDist) { near = true; break; }
                    if (!near) s.clusters.add(v);
                } else s.stone++;
                int total = s.stone + s.valuable;
                if (total < window) return;
                s.lastClusters = s.clusters.size();
                s.lastValuable = s.valuable;
                s.lastTotal = total;
                if (s.valuable >= minValuable && s.clusters.size() >= minClusters) {
                    double ratio = s.valuable / (double) total;
                    int sev = 2;
                    if (s.clusters.size() >= minClusters + 2) sev++;
                    if (ratio >= 0.08) sev++;
                    report(new Suspicion(p.getUniqueId(), p.getName(), id(), category(), Math.min(5, sev),
                            "в окне из " + total + " разрушенных блоков найдено " + s.valuable + " редких руд в " + s.clusters.size()
                                    + " разных залежах (" + Math.round(ratio * 1000) / 10.0 + "%); возможны удачные шахты — требуется проверка"));
                }
                s.stone = 0;
                s.valuable = 0;
                s.clusters.clear();
            }
        }

        @EventHandler
        public void quit(PlayerQuitEvent e) { states.remove(e.getPlayer().getUniqueId()); }
    }

    // ================================================================== Бой

    private final class CombatDetector implements Detector, Listener {
        private final class State {
            final ArrayDeque<Long> swings = new ArrayDeque<>();
            int highSeconds;
            long highSecStamp;
            final ArrayDeque<Long> reachHits = new ArrayDeque<>();
            double maxReach;
            int aimStreak;
            long hits, swingTotal;
            double lastReach;
        }

        private final Map<UUID, State> states = new ConcurrentHashMap<>();
        private boolean on = true;
        private double reachLimit = 3.8, aimAngle = 1.2;
        private int maxPing = 180, reachViolations = 5, cpsLimit = 22, aimHits = 12;

        @Override public String id() { return "combat"; }

        @Override public String category() { return "pvp"; }

        @Override
        public void reload(YamlConfiguration c) {
            on = c.getBoolean("telemetry.combat", true);
            reachLimit = c.getDouble("investigations.combat.reach", 3.8);
            maxPing = c.getInt("investigations.combat.max-ping-ms", 180);
            reachViolations = c.getInt("investigations.combat.reach-violations", 5);
            cpsLimit = c.getInt("investigations.combat.cps-limit", 22);
            aimHits = c.getInt("investigations.combat.aim-hits", 12);
            aimAngle = c.getDouble("investigations.combat.aim-angle-degrees", 1.2);
        }

        @Override
        public String snapshot(UUID u) {
            State s = states.get(u);
            if (s == null) return "данных о бое нет";
            return "ударов по игрокам " + s.hits + ", взмахов " + s.swingTotal + ", максимальная дальность удара " + String.format("%.2f", s.maxReach)
                    + ", серия точных наведений " + s.aimStreak;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void swing(PlayerAnimationEvent e) {
            if (!on) return;
            Player p = e.getPlayer();
            if (skip(p)) return;
            State s = states.computeIfAbsent(p.getUniqueId(), k -> new State());
            long now = System.currentTimeMillis();
            synchronized (s) {
                s.swingTotal++;
                s.swings.addLast(now);
                while (!s.swings.isEmpty() && now - s.swings.peekFirst() > 4000) s.swings.pollFirst();
                int cps = 0;
                for (long t : s.swings) if (now - t <= 1000) cps++;
                if (cps >= cpsLimit && now - s.highSecStamp > 900) {
                    s.highSecStamp = now;
                    s.highSeconds++;
                    if (s.highSeconds >= 3) {
                        s.highSeconds = 0;
                        report(new Suspicion(p.getUniqueId(), p.getName(), id(), category(), 2, "скорость кликов " + cps + " в секунду три раза подряд (лимит " + cpsLimit + "); возможен автокликер или баттерфляй-клики"));
                    }
                } else if (cps < cpsLimit / 2) s.highSeconds = 0;
                if (s.swings.size() >= 40) {
                    List<Long> a = new ArrayList<>(s.swings);
                    double mean = 0;
                    int n = a.size() - 1;
                    for (int i = 1; i < a.size(); i++) mean += a.get(i) - a.get(i - 1);
                    mean /= n;
                    double var = 0;
                    for (int i = 1; i < a.size(); i++) var += Math.pow(a.get(i) - a.get(i - 1) - mean, 2);
                    double sd = Math.sqrt(var / n);
                    if (mean < 100 && sd < 4) {
                        report(new Suspicion(p.getUniqueId(), p.getName(), id(), category(), 2, "почти идеально ровные интервалы между кликами (среднее " + Math.round(mean) + " мс, разброс " + String.format("%.1f", sd) + " мс)"));
                        s.swings.clear();
                    }
                }
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void hit(EntityDamageByEntityEvent e) {
            if (!on || !(e.getDamager() instanceof Player a) || !(e.getEntity() instanceof Player v)) return;
            if (skip(a) || a.equals(v)) return;
            State s = states.computeIfAbsent(a.getUniqueId(), k -> new State());
            Vector eye = a.getEyeLocation().toVector();
            BoundingBox bb = v.getBoundingBox();
            double dx = Math.max(Math.max(bb.getMinX() - eye.getX(), 0), eye.getX() - bb.getMaxX());
            double dy = Math.max(Math.max(bb.getMinY() - eye.getY(), 0), eye.getY() - bb.getMaxY());
            double dz = Math.max(Math.max(bb.getMinZ() - eye.getZ(), 0), eye.getZ() - bb.getMaxZ());
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            Vector to = bb.getCenter().subtract(eye);
            double angle = to.lengthSquared() < 1e-6 ? 0 : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a.getEyeLocation().getDirection().normalize().dot(to.clone().normalize())))));
            long now = System.currentTimeMillis();
            synchronized (s) {
                s.hits++;
                s.lastReach = dist;
                s.maxReach = Math.max(s.maxReach, dist);
                if (dist > reachLimit && a.getPing() < maxPing) {
                    s.reachHits.addLast(now);
                    while (!s.reachHits.isEmpty() && now - s.reachHits.peekFirst() > 60_000) s.reachHits.pollFirst();
                    if (s.reachHits.size() >= reachViolations) {
                        int sev = dist >= 4.6 ? 4 : 3;
                        report(new Suspicion(a.getUniqueId(), a.getName(), id(), category(), sev, s.reachHits.size() + " ударов за минуту с дальностью выше " + reachLimit
                                + " (последний " + String.format("%.2f", dist) + ", пинг " + a.getPing() + " мс); возможна задержка сети"));
                        s.reachHits.clear();
                    }
                }
                if (angle < aimAngle && v.getLocation().distance(a.getLocation()) > 1.5) s.aimStreak++;
                else s.aimStreak = 0;
                if (s.aimStreak >= aimHits) {
                    report(new Suspicion(a.getUniqueId(), a.getName(), id(), category(), 2, s.aimStreak + " ударов подряд с отклонением прицела менее " + aimAngle + "° (признак необычно точного наведения; не доказательство)"));
                    s.aimStreak = 0;
                }
            }
        }

        @EventHandler
        public void quit(PlayerQuitEvent e) { states.remove(e.getPlayer().getUniqueId()); }
    }

    // ================================================================== Предметы и контейнеры

    private final class ItemFlowDetector implements Detector, Listener {
        private final class State {
            long bucketStart = System.currentTimeMillis();
            int drops, pickups, opens;
            final Map<Material, Integer> pickupTypes = new java.util.EnumMap<>(Material.class);
            double lastValue = -1;
            double lastDelta;
        }

        private final Map<UUID, State> states = new ConcurrentHashMap<>();
        private boolean on = true;
        private int dropsPer10s = 80, opensPer10s = 40, surge = 300;
        private org.bukkit.scheduler.BukkitTask task;

        @Override public String id() { return "items"; }

        @Override public String category() { return "dupe"; }

        @Override
        public void reload(YamlConfiguration c) {
            on = c.getBoolean("telemetry.items", true);
            dropsPer10s = c.getInt("investigations.items.drops-per-10s", 80);
            opensPer10s = c.getInt("investigations.items.container-opens-per-10s", 40);
            surge = c.getInt("investigations.items.value-surge-per-30s", 300);
        }

        @Override
        public void start() {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::scanValues, 20 * 30, 20 * 30);
        }

        @Override
        public void stop() {
            if (task != null) task.cancel();
        }

        @Override
        public String snapshot(UUID u) {
            State s = states.get(u);
            if (s == null) return "данных о потоке предметов нет";
            return "за последнее окно: выбросов " + s.drops + ", подборов " + s.pickups + ", открытий контейнеров " + s.opens + ", последнее изменение ценностей " + Math.round(s.lastDelta);
        }

        private State bucket(Player p) {
            State s = states.computeIfAbsent(p.getUniqueId(), k -> new State());
            long now = System.currentTimeMillis();
            if (now - s.bucketStart > 10_000) {
                s.bucketStart = now;
                s.drops = 0;
                s.pickups = 0;
                s.opens = 0;
                s.pickupTypes.clear();
            }
            return s;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void drop(PlayerDropItemEvent e) {
            if (!on) return;
            Player p = e.getPlayer();
            if (skip(p) || !realWorld(p.getWorld())) return;
            State s = bucket(p);
            synchronized (s) {
                if (++s.drops == dropsPer10s) {
                    report(new Suspicion(p.getUniqueId(), p.getName(), id(), category(), 2, dropsPer10s + " выбрасываний предметов за 10 секунд"));
                }
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void pickup(EntityPickupItemEvent e) {
            if (!on || !(e.getEntity() instanceof Player p)) return;
            if (skip(p) || !realWorld(p.getWorld())) return;
            State s = bucket(p);
            Material m = e.getItem().getItemStack().getType();
            synchronized (s) {
                s.pickups++;
                int n = s.pickupTypes.merge(m, 1, Integer::sum);
                if (n == 60 && s.drops >= 30) {
                    report(new Suspicion(p.getUniqueId(), p.getName(), id(), category(), 3, "цикл «выбросить — подобрать» одного предмета (" + m.name() + ") более 60 раз за 10 секунд"));
                }
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void open(InventoryOpenEvent e) {
            if (!on || !(e.getPlayer() instanceof Player p)) return;
            if (skip(p) || !realWorld(p.getWorld())) return;
            State s = bucket(p);
            synchronized (s) {
                if (++s.opens == opensPer10s) {
                    report(new Suspicion(p.getUniqueId(), p.getName(), id(), category(), 2, opensPer10s + " открытий контейнеров за 10 секунд"));
                }
            }
        }

        private double valueOf(Player p) {
            double v = 0;
            for (ItemStack it : p.getInventory().getContents()) {
                if (it == null) continue;
                switch (it.getType()) {
                    case DIAMOND -> v += it.getAmount();
                    case DIAMOND_BLOCK -> v += 9.0 * it.getAmount();
                    case NETHERITE_INGOT -> v += 4.0 * it.getAmount();
                    case NETHERITE_BLOCK -> v += 36.0 * it.getAmount();
                    case ANCIENT_DEBRIS -> v += 1.0 * it.getAmount();
                    case EMERALD_BLOCK -> v += 2.0 * it.getAmount();
                    default -> { }
                }
            }
            return v;
        }

        private void scanValues() {
            if (!on) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (skip(p) || !realWorld(p.getWorld())) continue;
                State s = states.computeIfAbsent(p.getUniqueId(), k -> new State());
                double v = valueOf(p);
                synchronized (s) {
                    if (s.lastValue >= 0) {
                        double delta = v - s.lastValue;
                        s.lastDelta = delta;
                        if (delta >= surge) {
                            report(new Suspicion(p.getUniqueId(), p.getName(), id(), category(), delta >= surge * 2 ? 3 : 2,
                                    "прирост ценных предметов в инвентаре на " + Math.round(delta) + " (в алмазном эквиваленте) за 30 секунд; мог быть сундук, магазин или награда"));
                        }
                    }
                    s.lastValue = v;
                }
            }
        }

        @EventHandler
        public void quit(PlayerQuitEvent e) { states.remove(e.getPlayer().getUniqueId()); }
    }

    // ================================================================== Экономика

    private final class EconomyDetector implements Detector {
        private boolean on = true;
        private long windowMin = 10, threshold = 3_000_000;
        private org.bukkit.scheduler.BukkitTask task;

        @Override public String id() { return "economy"; }

        @Override public String category() { return "economy"; }

        @Override
        public void reload(YamlConfiguration c) {
            on = c.getBoolean("telemetry.economy", true);
            windowMin = Math.max(2, c.getLong("investigations.economy.window-minutes", 10));
            threshold = Math.max(10_000, c.getLong("investigations.economy.gain-threshold", 3_000_000));
            stop();
            start();
        }

        @Override
        public void start() {
            if (task != null) return;
            long ticks = Math.max(20 * 60L, windowMin * 60 * 20 / 2);
            task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::scan, ticks, ticks);
        }

        @Override
        public void stop() {
            if (task != null) task.cancel();
            task = null;
        }

        private void scan() {
            if (!on || plugin.db() == null || !plugin.db().isConnected()) return;
            long since = System.currentTimeMillis() - windowMin * 60_000L;
            plugin.db().query(c -> {
                List<Object[]> rows = new ArrayList<>();
                try (PreparedStatement ps = Database.ps(c, "SELECT uuid, SUM(delta) FROM money_log WHERE at>=? AND delta>0 GROUP BY uuid HAVING SUM(delta)>=? ORDER BY SUM(delta) DESC LIMIT 5", since, threshold);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Object[]{rs.getString(1), rs.getLong(2)});
                }
                List<Suspicion> out = new ArrayList<>();
                for (Object[] r : rows) {
                    String uuid = (String) r[0];
                    String name = Database.strVal(c, "SELECT name FROM players WHERE uuid=?", uuid);
                    StringBuilder reasons = new StringBuilder();
                    try (PreparedStatement ps = Database.ps(c, "SELECT reason, SUM(delta) FROM money_log WHERE uuid=? AND at>=? AND delta>0 GROUP BY reason ORDER BY SUM(delta) DESC LIMIT 3", uuid, since);
                         ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) reasons.append(rs.getString(1)).append("=").append(rs.getLong(2)).append(" ");
                    }
                    long sum = (Long) r[1];
                    try {
                        out.add(new Suspicion(UUID.fromString(uuid), name == null ? uuid : name, id(), category(), sum >= threshold * 3 ? 3 : 2,
                                "доход " + sum + " монет за " + windowMin + " мин; источники: " + reasons.toString().trim()));
                    } catch (IllegalArgumentException ignored) { }
                }
                return out;
            }).thenAccept(list -> list.forEach(CheatAnalytics.this::report));
        }
    }

    // ================================================================== Движение (скорость, полёт)

    private final class MovementDetector implements Detector, Listener {
        private final class St {
            int air, hover, climb, fast;
            long windowStart, ignoreUntil;
        }

        private final Map<UUID, St> states = new ConcurrentHashMap<>();
        private boolean on = true;
        private double fastLimit = 0.7;
        private int fastCount = 15, hoverTicks = 20;

        @Override public String id() { return "movement"; }

        @Override public String category() { return "other"; }

        @Override
        public void reload(YamlConfiguration c) {
            on = c.getBoolean("telemetry.movement", true);
            fastLimit = c.getDouble("investigations.movement.fast-limit", 0.7);
            fastCount = Math.max(5, c.getInt("investigations.movement.fast-count", 15));
            hoverTicks = Math.max(8, c.getInt("investigations.movement.hover-ticks", 20));
        }

        @Override
        public String snapshot(UUID u) {
            St s = states.get(u);
            return s == null ? null : "воздух " + s.air + " тиков, зависаний " + s.hover + ", быстрых шагов " + s.fast;
        }

        private St st(Player p) { return states.computeIfAbsent(p.getUniqueId(), k -> new St()); }

        private void ignore(Player p, long ms) {
            st(p).ignoreUntil = System.currentTimeMillis() + ms;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void velocity(org.bukkit.event.player.PlayerVelocityEvent e) { ignore(e.getPlayer(), 1800); }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void teleport(org.bukkit.event.player.PlayerTeleportEvent e) { ignore(e.getPlayer(), 2500); }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void riptide(org.bukkit.event.player.PlayerRiptideEvent e) { ignore(e.getPlayer(), 4000); }

        @EventHandler(priority = EventPriority.MONITOR)
        public void respawn(org.bukkit.event.player.PlayerRespawnEvent e) { ignore(e.getPlayer(), 3000); }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void damaged(org.bukkit.event.entity.EntityDamageEvent e) {
            if (e.getEntity() instanceof Player p) ignore(p, 1500);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void quit(PlayerQuitEvent e) { states.remove(e.getPlayer().getUniqueId()); }

        private boolean passable(Block b) {
            Material m = b.getType();
            if (m.isAir()) return true;
            String n = m.name();
            if (m.isSolid()) return false;
            return !(n.contains("WATER") || m == Material.LAVA || m == Material.LADDER || n.contains("VINE") || m == Material.COBWEB
                    || m == Material.SCAFFOLDING || m == Material.POWDER_SNOW || m == Material.BUBBLE_COLUMN || n.contains("CARPET") || n.contains("TRAPDOOR")
                    || n.contains("BED") || n.contains("SLAB"));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void move(org.bukkit.event.player.PlayerMoveEvent e) {
            Player p = e.getPlayer();
            if (!on || skip(p) || !realWorld(p.getWorld())) return;
            Location f = e.getFrom(), t = e.getTo();
            if (t == null || !f.getWorld().equals(t.getWorld())) return;
            St s = st(p);
            long now = System.currentTimeMillis();
            if (now < s.ignoreUntil || p.isFlying() || p.getAllowFlight() || p.isGliding() || p.isInsideVehicle() || p.isRiptiding() || p.isSwimming()
                    || p.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION) || p.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)
                    || p.hasPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST) || p.hasPotionEffect(org.bukkit.potion.PotionEffectType.DOLPHINS_GRACE)) {
                s.air = 0; s.hover = 0; s.climb = 0;
                return;
            }
            double dx = t.getX() - f.getX(), dz = t.getZ() - f.getZ(), dy = t.getY() - f.getY();
            double h = Math.sqrt(dx * dx + dz * dz);

            // ---- скорость ----
            double limit = fastLimit;
            var sp = p.getPotionEffect(org.bukkit.potion.PotionEffectType.SPEED);
            if (sp != null) limit += 0.12 * (sp.getAmplifier() + 1);
            Material under = f.clone().subtract(0, 0.4, 0).getBlock().getType();
            if (under.name().contains("ICE") || under == Material.SLIME_BLOCK) limit += 0.5;
            if (h > limit) {
                if (now - s.windowStart > 2000) { s.windowStart = now; s.fast = 0; }
                s.fast++;
                if (s.fast >= fastCount) {
                    int sev = s.fast >= fastCount * 2 ? 4 : 3;
                    report(new Suspicion(p.getUniqueId(), p.getName(), id(), category(), sev,
                            String.format("скорость %.2f блока/тик при лимите %.2f, %d быстрых шагов за 2 с, мир %s", h, limit, s.fast, p.getWorld().getName())));
                    s.fast = 0;
                    s.windowStart = now;
                }
            }

            // ---- полёт / зависание ----
            int bx = t.getBlockX(), bz = t.getBlockZ();
            int by = (int) Math.floor(t.getY() - 0.2);
            boolean support = false;
            for (int ox = -1; ox <= 1 && !support; ox++) for (int oz = -1; oz <= 1 && !support; oz++) {
                for (int oy = 0; oy <= 1; oy++) {
                    if (!passable(t.getWorld().getBlockAt(bx + ox, by - oy, bz + oz))) { support = true; break; }
                }
            }
            Material feet = t.getBlock().getType();
            if (support || feet.name().contains("WATER") || feet == Material.LADDER || feet.name().contains("VINE") || feet == Material.COBWEB || feet == Material.SCAFFOLDING) {
                s.air = 0; s.hover = 0; s.climb = 0;
                return;
            }
            s.air++;
            if (s.air > 12) {
                if (Math.abs(dy) < 0.02) s.hover++;
                else if (dy < -0.06) s.hover = 0;
                if (dy > 0.05) s.climb++; else if (dy < 0) s.climb = 0;
            }
            if (s.hover >= hoverTicks) {
                report(new Suspicion(p.getUniqueId(), p.getName(), id(), category(), 4, "завис в воздухе без опоры " + s.hover + " тиков, " + coords(t)));
                s.hover = 0;
            } else if (s.climb >= hoverTicks) {
                report(new Suspicion(p.getUniqueId(), p.getName(), id(), category(), 4, "поднимается в воздухе без опоры " + s.climb + " тиков, " + coords(t)));
                s.climb = 0;
            }
        }

        private String coords(Location l) { return l.getWorld().getName() + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ(); }
    }

    // ================================================================== Темп действий (нюкер, скаффолд, дальность)

    private final class ActionRateDetector implements Detector, Listener {
        private final Map<UUID, ArrayDeque<Long>> breaks = new ConcurrentHashMap<>(), places = new ConcurrentHashMap<>();
        private boolean on = true;
        private int breakPerSec = 16, placePerSec = 14;
        private double reach = 6.5;

        @Override public String id() { return "actions"; }

        @Override public String category() { return "other"; }

        @Override
        public void reload(YamlConfiguration c) {
            on = c.getBoolean("telemetry.actions", true);
            breakPerSec = Math.max(6, c.getInt("investigations.actions.break-per-second", 16));
            placePerSec = Math.max(6, c.getInt("investigations.actions.place-per-second", 14));
            reach = c.getDouble("investigations.actions.block-reach", 6.5);
        }

        private boolean rate(Map<UUID, ArrayDeque<Long>> m, Player p, int limit) {
            ArrayDeque<Long> q = m.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
            long now = System.currentTimeMillis();
            synchronized (q) {
                q.addLast(now);
                while (!q.isEmpty() && now - q.peekFirst() > 1000) q.pollFirst();
                if (q.size() >= limit) { q.clear(); return true; }
            }
            return false;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void brk(BlockBreakEvent e) {
            Player p = e.getPlayer();
            if (!on || skip(p) || !realWorld(p.getWorld())) return;
            if (rate(breaks, p, breakPerSec))
                report(new Suspicion(p.getUniqueId(), p.getName(), id(), category(), 4, "ломает ≥" + breakPerSec + " блоков в секунду (нюкер?), " + e.getBlock().getType()));
            double d = p.getEyeLocation().distance(e.getBlock().getLocation().add(0.5, 0.5, 0.5));
            if (d > reach)
                report(new Suspicion(p.getUniqueId(), p.getName(), id(), category(), 3, String.format("ломает блок с дистанции %.1f (лимит %.1f)", d, reach)));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void place(org.bukkit.event.block.BlockPlaceEvent e) {
            Player p = e.getPlayer();
            if (!on || skip(p) || !realWorld(p.getWorld())) return;
            if (rate(places, p, placePerSec))
                report(new Suspicion(p.getUniqueId(), p.getName(), id(), category(), 3, "ставит ≥" + placePerSec + " блоков в секунду (скаффолд?)"));
            double d = p.getEyeLocation().distance(e.getBlockPlaced().getLocation().add(0.5, 0.5, 0.5));
            if (d > reach)
                report(new Suspicion(p.getUniqueId(), p.getName(), id(), category(), 3, String.format("ставит блок с дистанции %.1f (лимит %.1f)", d, reach)));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void quit(PlayerQuitEvent e) { breaks.remove(e.getPlayer().getUniqueId()); places.remove(e.getPlayer().getUniqueId()); }
    }

    // ================================================================== Совокупный риск

    private final Map<UUID, double[]> risk = new ConcurrentHashMap<>();
    private final Map<UUID, Long> riskEscalated = new ConcurrentHashMap<>();
    private volatile double riskThreshold = 10;
    private org.bukkit.scheduler.BukkitTask riskTask;

    private void addRisk(UUID u, int severity) {
        long now = System.currentTimeMillis();
        risk.compute(u, (k, v) -> {
            double score = v == null ? 0 : v[0] * Math.pow(0.5, (now - v[1]) / 900_000.0); // период полураспада 15 минут
            return new double[]{score + severity * severity * 0.6, now};
        });
    }

    /** Текущий риск игрока (с затуханием). */
    public double riskOf(UUID u) {
        double[] v = risk.get(u);
        return v == null ? 0 : v[0] * Math.pow(0.5, (System.currentTimeMillis() - v[1]) / 900_000.0);
    }

    private void riskTick() {
        if (!enabled) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            double r = riskOf(p.getUniqueId());
            if (r < riskThreshold) continue;
            Long last = riskEscalated.get(p.getUniqueId());
            if (last != null && System.currentTimeMillis() - last < 900_000) continue;
            riskEscalated.put(p.getUniqueId(), System.currentTimeMillis());
            String details = String.format("совокупный риск %.1f (порог %.1f): много сигналов от детекторов за короткое время; сводка: %s", r, riskThreshold,
                    String.join("; ", snapshot(p.getUniqueId())));
            lastReport.remove(p.getUniqueId() + "|risk");
            report(new Suspicion(p.getUniqueId(), p.getName(), "risk", "other", 5, details));
        }
    }
}
