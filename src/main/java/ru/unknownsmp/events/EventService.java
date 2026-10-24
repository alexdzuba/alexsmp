package ru.unknownsmp.events;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.events.types.AnomalyEvent;
import ru.unknownsmp.events.types.CacheEvent;
import ru.unknownsmp.events.types.MerchantEvent;
import ru.unknownsmp.events.types.MeteorEvent;
import ru.unknownsmp.events.types.RitualEvent;
import ru.unknownsmp.events.types.SecretChainEvent;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * События сервера: несколько раз в день в случайное время, в случайных местах основного мира.
 * Сначала атмосферное предупреждение, затем точные координаты. Неудавшееся событие повторяется через час.
 */
public final class EventService implements Module {
    private final UnknownSMP plugin;
    private final Random rnd = new Random();
    private final List<Long> schedule = new ArrayList<>();
    private GameEvent active;
    private GameEvent pending;
    private long pendingStart;
    private final List<Object[]> retries = new ArrayList<>(); // {type, time}
    private LocalDate scheduledFor;

    public EventService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "events"; }
    @Override public String title() { return "События"; }
    @Override public String[] depends() { return new String[]{"economy", "items", "state", "world"}; }

    @Override
    public void enable() {
        planDay();
        Tasks.timer(20, 20, this::tick);
        plugin.commands().add("events").player().perm("usmp.player.events").module("events").cat("События")
                .usage("/events").desc("Текущее событие и расписание").exec((s, a) -> open((Player) s, null));
    }

    @Override
    public void disable() {
        if (active != null) {
            // Остановка сервера во время события — событие отменяется без наград, мир очищается.
            try { active.cleanup(); } catch (Throwable ignored) {}
            HandlerList.unregisterAll(active);
            plugin.logs().sys("event", "aborted", active.location(), active.type() + " (остановка сервера)");
            active = null;
        }
    }

    YamlConfiguration cfg() { return plugin.configs().get("events.yml"); }

    public GameEvent active() { return active; }

    public GameEvent pending() { return pending; }

    // ------------------------------------------------------------------ Расписание

    private void planDay() {
        schedule.clear();
        LocalDate today = LocalDate.now(Text.zone());
        scheduledFor = today;
        int perDay = cfg().getInt("per-day", 4);
        LocalTime from = LocalTime.parse(cfg().getString("active-hours.from", "10:00"));
        LocalTime to = LocalTime.parse(cfg().getString("active-hours.to", "23:30"));
        long now = System.currentTimeMillis();
        long start = ZonedDateTime.of(today, from, Text.zone()).toInstant().toEpochMilli();
        long end = ZonedDateTime.of(today, to, Text.zone()).toInstant().toEpochMilli();
        if (end <= start) end = start + 3_600_000L;
        long slot = (end - start) / Math.max(1, perDay);
        for (int i = 0; i < perDay; i++) {
            long t = start + i * slot + (long) (rnd.nextDouble() * slot * 0.8);
            if (t > now + 60_000) schedule.add(t);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!LocalDate.now(Text.zone()).equals(scheduledFor)) planDay();
        if (active != null) {
            try { active.tick(); } catch (Throwable t) {
                plugin.getLogger().warning("Ошибка события " + active.type() + ": " + t);
                finish(active, false);
                return;
            }
            if (active != null && now >= active.endsAt()) finish(active, active.succeeded());
            return;
        }
        if (pending != null) {
            if (now >= pendingStart) start(pending);
            return;
        }
        int warnMin = cfg().getInt("warning-minutes", 5);
        for (Object[] r : new ArrayList<>(retries)) {
            if ((Long) r[1] - warnMin * 60_000L <= now) {
                retries.remove(r);
                prepare((String) r[0], warnMin);
                return;
            }
        }
        if (!schedule.isEmpty() && schedule.get(0) - warnMin * 60_000L <= now) {
            schedule.remove(0);
            if (countPlayers() < cfg().getInt("min-players", 2)) return;
            prepare(randomType(), warnMin);
        }
    }

    private int countPlayers() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.auth().isAuthed(p) && !plugin.afk().isAfk(p)) n++;
        return n;
    }

    private String randomType() {
        List<String> types = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        for (String t : List.of("meteor", "cache", "anomaly", "merchant", "secrets")) {
            if (!cfg().getBoolean("types." + t + ".enabled", true)) continue;
            types.add(t);
            weights.add(cfg().getInt("types." + t + ".weight", 1));
        }
        if (types.isEmpty()) return "meteor";
        int total = weights.stream().mapToInt(i -> i).sum();
        int r = rnd.nextInt(Math.max(1, total));
        for (int i = 0; i < types.size(); i++) { r -= weights.get(i); if (r < 0) return types.get(i); }
        return types.get(0);
    }

    public GameEvent createEvent(String type) {
        Map<String, Supplier<GameEvent>> f = Map.of(
                "meteor", () -> new MeteorEvent(plugin, this),
                "cache", () -> new CacheEvent(plugin, this),
                "anomaly", () -> new AnomalyEvent(plugin, this),
                "merchant", () -> new MerchantEvent(plugin, this),
                "secrets", () -> new SecretChainEvent(plugin, this));
        Supplier<GameEvent> s = f.get(type);
        return s == null ? null : s.get();
    }

    /** Подготовка: выбор места и атмосферное предупреждение. */
    public boolean prepare(String type, int warnMinutes) {
        if (active != null || pending != null) return false;
        GameEvent e = createEvent(type);
        if (e == null) return false;
        Location l = findLocation();
        if (l == null) {
            plugin.getLogger().warning("Не найдено место для события " + type);
            return false;
        }
        e.setLocation(l);
        pending = e;
        pendingStart = System.currentTimeMillis() + warnMinutes * 60_000L;
        Msg.broadcast("event.warning", "&5✦ &d{w} &7(через {m} мин.)", "w", e.warning(), "m", warnMinutes);
        for (Player p : Bukkit.getOnlinePlayers()) {
            var pr = plugin.profiles().get(p);
            if (pr != null && pr.setting("notifications")) {
                Msg.title(p, "&5✦", "&d" + e.warning(), 10, 60, 20);
                Msg.sound(p, Sound.AMBIENT_CAVE, 1f, 0.6f);
            }
        }
        return true;
    }

    private void start(GameEvent e) {
        pending = null;
        try {
            if (!e.setup()) {
                plugin.getLogger().warning("Событие " + e.type() + " не удалось подготовить в " + Text.coords(e.location()));
                return;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Ошибка подготовки события " + e.type() + ": " + t);
            e.cleanup();
            return;
        }
        e.begin();
        active = e;
        Bukkit.getPluginManager().registerEvents(e, plugin);
        Msg.broadcast("event.start", "&5&l✦ СОБЫТИЕ: &d{t}&5&l ✦ &fКоординаты: &e{c}&f. {d} &7Длится {m} мин. PvP по обычным правилам.",
                "t", e.title(), "c", Text.coords(e.location()), "d", e.description(), "m", e.minutes());
        for (Player p : Bukkit.getOnlinePlayers()) {
            var pr = plugin.profiles().get(p);
            if (pr != null && pr.setting("notifications") && plugin.auth().isAuthed(p)) {
                Msg.title(p, "&d" + e.title(), "&e" + e.location().getBlockX() + ", " + e.location().getBlockZ(), 10, 80, 20);
                Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 0.7f);
            }
        }
        plugin.logs().sys("event", "start", e.location(), e.type());
    }

    /** Запуск ритуала открытия Незера (вызывается DimensionService). */
    public boolean startRitual(Player activator, Location at, org.bukkit.inventory.ItemStack key) {
        if (active != null || pending != null) return false;
        RitualEvent r = new RitualEvent(plugin, this, activator.getUniqueId(), key);
        r.setLocation(at);
        start(r);
        return active == r;
    }

    public void forceStart(String type) {
        if (prepare(type, 0)) pendingStart = System.currentTimeMillis();
    }

    public void stopActive() {
        if (active != null) finish(active, false);
        pending = null;
    }

    // ------------------------------------------------------------------ Завершение и награды

    void finish(GameEvent e, boolean success) {
        if (e.isFinished()) return;
        e.markFinished();
        HandlerList.unregisterAll(e);
        try { e.cleanup(); } catch (Throwable t) { plugin.getLogger().warning("Ошибка очистки события: " + t); }
        if (active == e) active = null;
        double min = e.minContribution();
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(e.contribution().entrySet());
        list.removeIf(en -> en.getValue() < min);
        list.sort(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()));
        if (e instanceof RitualEvent r) r.onFinish(success, list);
        final String type = e.type();
        long now = System.currentTimeMillis();
        plugin.db().tx(c -> Database.insert(c, "INSERT INTO event_history(type, started_at, ended_at, success, loc, participants, details) VALUES (?,?,?,?,?,?,?)",
                type, e.startedAt, now, success ? 1 : 0, Text.loc(e.location()), list.size(), list.isEmpty() ? "" : "top=" + list.get(0).getKey()));
        plugin.logs().sys("event", success ? "success" : "fail", e.location(), type + " qualified=" + list.size());
        if (!success) {
            Msg.broadcast("event.failed", "&7Событие «{t}» не удалось. Попытка повторится через час.", "t", e.title());
            if (!(e instanceof RitualEvent)) retries.add(new Object[]{type, now + cfg().getLong("retry-minutes", 60) * 60_000L});
            return;
        }
        Msg.broadcast("event.success", "&a✦ Событие «{t}» завершено! Наград удостоено участников: {n}.", "t", e.title(), "n", list.size());
        reward(e, list);
    }

    private void reward(GameEvent e, List<Map.Entry<UUID, Double>> list) {
        if (list.isEmpty()) return;
        double sum = list.stream().mapToDouble(Map.Entry::getValue).sum();
        long pool = e.moneyPool();
        long repBase = cfg().getLong("reputation-per-event", 15);
        for (int i = 0; i < list.size(); i++) {
            UUID u = list.get(i).getKey();
            double share = list.get(i).getValue() / Math.max(1e-9, sum);
            long money = (long) Math.floor(pool * share);
            Player p = Bukkit.getPlayer(u);
            if (money > 0) plugin.economy().deposit(u, money, "event_reward", e.type());
            if (p == null) continue;
            Msg.ok(p, "event.reward", "Ваш вклад: {v} ({s}%). Награда: {m}.", "v", String.format("%.1f", list.get(i).getValue()),
                    "s", Math.round(share * 100), "m", Text.money(money));
            plugin.profiles().addStat(p, "events_done", 1);
            plugin.progression().addReputation(p, repBase + Math.round(repBase * share), "event");
            plugin.progression().addXp(p, 50 + Math.round(100 * share), "event");
            plugin.quests().progress(p, "EVENT", e.type(), 1);
            final int place = i;
            if (place == 0) {
                if (e instanceof RitualEvent) continue;
                plugin.items().roll("event_rare", p, e.type()).thenAccept(d -> {
                    if (d != null) Tasks.sync(() -> Msg.ok(p, "event.loot", "Особая награда: {d}", "d", d));
                });
                plugin.dimensions().maybeAwardFragment(p, e.type());
            } else if (rnd.nextDouble() < cfg().getDouble("common-loot-chance", 0.5)) {
                plugin.items().roll("event_common", p, e.type()).thenAccept(d -> {
                    if (d != null) Tasks.sync(() -> Msg.ok(p, "event.loot", "Особая награда: {d}", "d", d));
                });
            }
        }
    }

    // ------------------------------------------------------------------ Место

    /** Случайная точка на поверхности основного мира: дальше от спавна, внутри границы, не в воде/лаве. */
    public Location findLocation() {
        World w = plugin.survivalWorld();
        Location s = plugin.world().spawn();
        double border = w.getWorldBorder().getSize() / 2 - 100;
        int min = cfg().getInt("distance.min", 400);
        int max = (int) Math.min(cfg().getInt("distance.max", 6000), border);
        for (int i = 0; i < 40; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double d = min + rnd.nextDouble() * Math.max(1, max - min);
            int x = (int) (w.getWorldBorder().getCenter().getX() + Math.cos(ang) * d);
            int z = (int) (w.getWorldBorder().getCenter().getZ() + Math.sin(ang) * d);
            if (Math.abs(x - s.getX()) < min && Math.abs(z - s.getZ()) < min) continue;
            w.getChunkAt(x >> 4, z >> 4);
            Block b = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Material m = b.getType();
            if (m == Material.WATER || m == Material.LAVA || m == Material.ICE || b.isLiquid()) continue;
            if (b.getY() < w.getSeaLevel() - 2 || b.getY() > 200) continue;
            if (plugin.world().inSpawnZone(b.getLocation())) continue;
            return b.getLocation().add(0.5, 1, 0.5);
        }
        return null;
    }

    // ------------------------------------------------------------------ Меню

    /** Краткое публичное состояние событий (то же, что видит игрок в меню событий). Используется ИИ Жорой. */
    public String statusText() {
        if (active != null) return "Идёт событие «" + active.title() + "», координаты " + Text.coords(active.location()) + ", осталось " + Text.duration(Math.max(0, active.endsAt() - System.currentTimeMillis()));
        if (pending != null) return "Скоро событие: " + pending.warning() + ", начало через " + Text.duration(Math.max(0, pendingStart - System.currentTimeMillis()));
        String next = schedule.isEmpty() ? "сегодня событий больше не запланировано" : "следующее примерно в " + Text.date(schedule.get(0)).substring(11) + " (время случайное)";
        return "Сейчас событий нет; " + next;
    }

    public void open(Player p, Menu parent) {
        new Menu(p, 3, "&8События") {
            @Override
            protected void build() {
                fill(Items.filler());
                if (active != null) {
                    Double my = active.contribution().get(viewer.getUniqueId());
                    set(11, Items.of(Material.NETHER_STAR).name("&d&l" + active.title()).glow()
                            .lore("&7Координаты: &e" + Text.coords(active.location()), "&7Осталось: &f" + Text.duration(active.endsAt() - System.currentTimeMillis()),
                                    "&7" + active.description(), "", "&7Ваш вклад: &f" + (my == null ? "0" : String.format("%.1f", my)),
                                    "&7Для награды нужно: &f" + active.minContribution()).build());
                } else if (pending != null) {
                    set(11, Items.of(Material.ENDER_EYE).name("&5Скоро...").lore("&7" + pending.warning(), "&7Начало через: &f" + Text.duration(pendingStart - System.currentTimeMillis())).build());
                } else {
                    String next = schedule.isEmpty() ? "сегодня событий больше не запланировано" : "примерно в " + Text.date(schedule.get(0)).substring(11);
                    set(11, Items.of(Material.CLOCK).name("&7Сейчас событий нет").lore("&7Следующее: &f" + next, "&8Время случайное — следите за знаками").build());
                }
                set(13, Items.of(Material.BOOK).name("&eКак получить награду").lore("&7Награда зависит от личного вклада:", "&7добыча метеорита, бой со стражами,",
                        "&7поиск знаков, сделки с торговцем.", "&7Просто стоять рядом — недостаточно.", "&7AFK-игроки не получают вклад.").build());
                set(15, Items.of(Material.CRYING_OBSIDIAN).name("&5Измерения").lore("&7Незер: " + (plugin.dimensions().netherOpen() ? "&aоткрыт" : "&cзапечатан"),
                        "&7Энд: " + (plugin.dimensions().endOpen() ? "&aоткрыт" : "&cзапечатан"), "", "&eПодробнее").build(), c -> plugin.dimensions().open(viewer, this));
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }
}
