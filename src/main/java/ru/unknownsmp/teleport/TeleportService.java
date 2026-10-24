package ru.unknownsmp.teleport;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import ru.unknownsmp.core.Commands;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.gui.ConfirmMenu;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.profile.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Дома (до 2), /tpa (120 с), /spawn (кулдаун 5 минут). Всё блокируется во время боевого таймера. */
public final class TeleportService implements Module, Listener {
    private static final Pattern HOME_NAME = Pattern.compile("^[\\p{L}0-9_-]{1,16}$");

    private record Warmup(BukkitTask task, Location start) {}
    private record Request(UUID from, long until) {}

    private final UnknownSMP plugin;
    private final Map<UUID, Warmup> warmups = new ConcurrentHashMap<>();
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();
    private int maxHomes, warmupSec, tpaSec;
    private long spawnCooldown;

    public TeleportService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "teleport"; }
    @Override public String title() { return "Дома и телепортация"; }
    @Override public String[] depends() { return new String[]{"database", "profiles", "world"}; }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerCommands();
    }

    @Override
    public void reload() {
        var c = plugin.configs().main();
        maxHomes = c.getInt("teleport.max-homes", 2);
        warmupSec = c.getInt("teleport.warmup-seconds", 15);
        tpaSec = c.getInt("teleport.tpa-timeout-seconds", 120);
        spawnCooldown = c.getLong("teleport.spawn-cooldown-seconds", 300) * 1000L;
    }

    private boolean blocked(Player p) {
        if (plugin.combat() != null && plugin.combat().isTagged(p)) {
            Msg.error(p, "combat.teleport", "Телепортация недоступна во время боя ({t}).", "t", Text.duration(plugin.combat().remaining(p)));
            return true;
        }
        if (plugin.duels() != null && plugin.duels().isDuelWorld(p.getWorld())) {
            Msg.error(p, "teleport.duels", "В режиме дуэлей это недоступно. Выход: /lobby");
            return true;
        }
        return false;
    }

    private boolean survivalWorld(World w) {
        return !plugin.lobby().isLobby(w) && !(plugin.duels() != null && plugin.duels().isDuelWorld(w));
    }

    /** Телепорт с задержкой: отменяется при движении, уроне или начале боя. */
    public void teleportWithWarmup(Player p, Location to, String label, Runnable before) {
        cancelWarmup(p, false);
        if (to == null || to.getWorld() == null) { Msg.error(p, "teleport.invalid", "Точка недоступна."); return; }
        if (warmupSec <= 0 || plugin.roles().has(p, "usmp.admin.instant-tp")) {
            if (before != null) before.run();
            p.teleportAsync(to, PlayerTeleportEvent.TeleportCause.COMMAND);
            return;
        }
        Msg.send(p, "teleport.warmup", "&7Телепортация ({l}) через &f{s}&7 сек. Не двигайтесь.", "l", label, "s", warmupSec);
        Location start = p.getLocation().clone();
        BukkitTask t = Tasks.later(warmupSec * 20L, () -> {
            warmups.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            if (plugin.combat() != null && plugin.combat().isTagged(p)) { Msg.error(p, "combat.teleport-cancel", "Телепортация отменена: вы в бою."); return; }
            if (before != null) before.run();
            p.teleportAsync(to, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
                if (ok) Tasks.sync(() -> Msg.sound(p, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.2f));
            });
        });
        warmups.put(p.getUniqueId(), new Warmup(t, start));
    }

    public void cancelWarmup(Player p, boolean notify) {
        Warmup w = warmups.remove(p.getUniqueId());
        if (w != null) {
            w.task.cancel();
            if (notify) Msg.error(p, "teleport.cancelled", "Телепортация отменена.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        Warmup w = warmups.get(e.getPlayer().getUniqueId());
        if (w == null) return;
        if (e.getTo().getWorld() != w.start.getWorld() || e.getTo().distanceSquared(w.start) > 0.36) cancelWarmup(e.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && warmups.containsKey(p.getUniqueId())) cancelWarmup(p, true);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        cancelWarmup(e.getPlayer(), false);
        requests.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ Дома

    public void setHome(Player p, String name) {
        if (blocked(p)) return;
        if (!survivalWorld(p.getWorld())) { Msg.error(p, "home.world", "Дом можно установить только в мире выживания."); return; }
        if (!HOME_NAME.matcher(name).matches()) { Msg.error(p, "home.name", "Имя дома: 1–16 букв, цифр, _ или -."); return; }
        Profile pr = plugin.profiles().get(p);
        if (pr == null) return;
        String key = name.toLowerCase();
        if (!pr.homes.containsKey(key) && pr.homes.size() >= maxHomes(p)) {
            Msg.error(p, "home.limit", "Можно иметь не более {n} домов. Удалите один или докупите слот: /homes", "n", maxHomes(p));
            return;
        }
        String loc = Text.loc(p.getLocation());
        final String u = p.getUniqueId().toString();
        final int max = maxHomes(p);
        Tasks.then(plugin.db().tx(c -> {
            Long cnt = Database.longVal(c, "SELECT COUNT(*) FROM homes WHERE uuid=? AND name<>?", u, key);
            if (cnt != null && cnt >= max) throw new Database.TxAbort("home.limit", "Можно иметь не более " + max + " домов.");
            Database.update(c, "INSERT INTO homes(uuid, name, loc, created_at) VALUES (?,?,?,?) ON CONFLICT(uuid, name) DO UPDATE SET loc = excluded.loc",
                    u, key, loc, System.currentTimeMillis());
            return null;
        }), v -> {
            pr.homes.put(key, loc);
            Msg.ok(p, "home.set", "Дом «{n}» установлен.", "n", key);
            plugin.logs().log(p, "teleport", "sethome", p.getLocation(), key);
        }, err -> Msg.error(p, err instanceof Database.TxAbort ab ? ab.key : "home.error", err instanceof Database.TxAbort ab ? ab.def : "Ошибка сохранения дома."));
    }

    public void home(Player p, String name) {
        if (blocked(p)) return;
        Profile pr = plugin.profiles().get(p);
        if (pr == null) return;
        String loc = pr.homes.get(name.toLowerCase());
        if (loc == null) { Msg.error(p, "home.none", "Дом «{n}» не найден.", "n", name); return; }
        Location l = Text.loc(loc);
        if (l == null || (plugin.dimensions() != null && !plugin.dimensions().canBeIn(l.getWorld()))) {
            Msg.error(p, "home.unavailable", "Этот дом сейчас недоступен.");
            return;
        }
        boolean fromLobby = plugin.lobby().isLobby(p.getWorld());
        teleportWithWarmup(p, l, "дом " + name, () -> {
            if (fromLobby && p.getGameMode() == org.bukkit.GameMode.ADVENTURE) p.setGameMode(org.bukkit.GameMode.SURVIVAL);
        });
    }

    /** Лимит домов игрока: базовый + докупленные слоты. */
    public int maxHomes(Player p) {
        Profile pr = plugin.profiles().get(p);
        return maxHomes + (int) (pr == null ? 0 : pr.stat("home_slots"));
    }

    /** Докупить слот дома: деньги и слот записываются одной транзакцией. */
    public void buyHomeSlot(Player p, Runnable after) {
        Profile pr = plugin.profiles().get(p);
        if (pr == null) return;
        var c = plugin.configs().main();
        long price = c.getLong("teleport.extra-home-price", 1_000_000);
        int maxExtra = c.getInt("teleport.max-extra-homes", 5);
        long have = pr.stat("home_slots");
        if (have >= maxExtra) { Msg.error(p, "home.slots-max", "Куплено максимальное число дополнительных слотов ({n}).", "n", maxExtra); return; }
        if (!plugin.economy().tradeAllowed(p)) return;
        final String u = p.getUniqueId().toString();
        final java.util.UUID uid = p.getUniqueId();
        Tasks.then(plugin.db().tx(cn -> {
            long bal = ru.unknownsmp.economy.EconomyService.debit(cn, uid, price, "home_slot", String.valueOf(have + 1));
            Database.update(cn, "INSERT INTO player_stats(uuid, stat, value) VALUES (?,?,?) ON CONFLICT(uuid, stat) DO UPDATE SET value = player_stats.value + 1",
                    u, "home_slots", 1L);
            Long v = Database.longVal(cn, "SELECT value FROM player_stats WHERE uuid=? AND stat=?", u, "home_slots");
            return new long[]{bal, v == null ? have + 1 : v};
        }), r -> {
            plugin.economy().apply(uid, r[0]);
            pr.stats.put("home_slots", r[1]);
            Msg.ok(p, "home.slot-bought", "Куплен дополнительный слот дома. Теперь домов: {n}.", "n", maxHomes(p));
            plugin.logs().log(p, "economy", "home_slot", p.getLocation(), "price=" + price + " slots=" + r[1]);
            if (after != null) after.run();
        }, err -> Msg.error(p, err instanceof Database.TxAbort ab ? ab.key : "home.error",
                err instanceof Database.TxAbort ab ? ab.def : "Покупка не выполнена. Деньги не списаны."));
    }

    public void delHome(Player p, String name) {
        Profile pr = plugin.profiles().get(p);
        String key = name.toLowerCase();
        if (pr == null || !pr.homes.containsKey(key)) { Msg.error(p, "home.none", "Дом «{n}» не найден.", "n", name); return; }
        final String u = p.getUniqueId().toString();
        Tasks.then(plugin.db().tx(c -> Database.update(c, "DELETE FROM homes WHERE uuid=? AND name=?", u, key)), n -> {
            pr.homes.remove(key);
            Msg.ok(p, "home.deleted", "Дом «{n}» удалён.", "n", key);
        }, err -> Msg.error(p, "home.error", "Ошибка удаления дома."));
    }

    public void homesMenu(Player p, Menu parent) {
        Profile pr = plugin.profiles().get(p);
        if (pr == null) return;
        new Menu(p, 4, "&8Дома") {
            @Override
            protected void build() {
                fill(Items.filler());
                int slot = 10;
                for (var e : pr.homes.entrySet()) {
                    Location l = Text.loc(e.getValue());
                    set(slot, Items.of(Material.RED_BED).name("&a" + e.getKey())
                            .lore("&7" + (l == null ? "мир недоступен" : Text.coords(l)), "", "&eЛКМ — телепорт", "&cПКМ — удалить").build(), c -> {
                        if (c.right()) {
                            ConfirmMenu.open(viewer, "&8Удалить дом?", null, List.of("&7Дом: &f" + e.getKey()), () -> delHome(viewer, e.getKey()));
                        } else {
                            viewer.closeInventory();
                            home(viewer, e.getKey());
                        }
                    });
                    slot++;
                    if (slot == 17) slot = 19;
                }
                for (int i = pr.homes.size(); i < maxHomes(viewer) && slot <= 25; i++) {
                    set(slot, Items.of(Material.LIGHT_GRAY_BED).name("&7Свободный слот дома")
                            .lore("&eНажмите, чтобы установить дом здесь").build(), c -> ru.unknownsmp.core.ChatInput.ask(viewer,
                            Msg.t("home.ask-name", "&fВведите имя нового дома:"), n -> setHome(viewer, n)));
                    slot++;
                    if (slot == 17) slot = 19;
                }
                long price = plugin.configs().main().getLong("teleport.extra-home-price", 1_000_000);
                set(31, Items.of(Material.GOLD_BLOCK).name("&6Докупить слот дома")
                        .lore("&7Цена: &6" + Text.money(price), "&7Сейчас слотов: &f" + maxHomes(viewer), "", "&eНажмите, чтобы купить").build(),
                        c -> ConfirmMenu.open(viewer, "&8Купить слот за " + Text.money(price) + "?", null, List.of("&7Ещё один дом"),
                                () -> buyHomeSlot(viewer, () -> homesMenu(viewer, parent))));
                if (parent != null) parent(parent);
                backButton(27);
            }
        }.open();
    }

    // ------------------------------------------------------------------ TPA

    private void tpa(Player p, Player t) {
        if (blocked(p)) return;
        if (t == null || !plugin.auth().isAuthed(t) || (plugin.admin() != null && plugin.admin().isVanished(t) && !plugin.roles().isStaff(p))) {
            Msg.error(p, "player.offline", "Игрок не в сети.");
            return;
        }
        if (t.equals(p)) { Msg.error(p, "tpa.self", "Нельзя отправить запрос самому себе."); return; }
        Profile tp = plugin.profiles().get(t);
        if (tp != null && tp.has("block", "tpa")) { Msg.error(p, "tpa.disabled", "Игрок не принимает запросы."); return; }
        requests.put(t.getUniqueId(), new Request(p.getUniqueId(), System.currentTimeMillis() + tpaSec * 1000L));
        Msg.ok(p, "tpa.sent", "Запрос телепортации отправлен {t}. Действует {s} сек.", "t", t.getName(), "s", tpaSec);
        Msg.send(t, "tpa.received", "&e{p}&f хочет телепортироваться к вам. &a/tpr &fили &c/tpd &7({s} сек.)", "p", p.getName(), "s", tpaSec);
        Msg.sound(t, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
    }

    private void tpaccept(Player t) {
        Request r = requests.remove(t.getUniqueId());
        if (r == null || r.until < System.currentTimeMillis()) { Msg.error(t, "tpa.none", "Нет активных запросов."); return; }
        Player p = Bukkit.getPlayer(r.from);
        if (p == null) { Msg.error(t, "tpa.gone", "Игрок вышел из сети."); return; }
        if (!survivalWorld(t.getWorld())) { Msg.error(t, "tpa.world", "Принять запрос можно только в мире выживания."); return; }
        if (plugin.combat() != null && (plugin.combat().isTagged(t))) { Msg.error(t, "combat.blocked", "Нельзя во время боя."); return; }
        if (blocked(p)) { Msg.error(t, "tpa.requester-busy", "Игрок сейчас не может телепортироваться."); return; }
        Msg.ok(t, "tpa.accepted", "Вы приняли запрос {p}.", "p", p.getName());
        boolean fromLobby = plugin.lobby().isLobby(p.getWorld());
        teleportWithWarmup(p, t.getLocation(), "к " + t.getName(), () -> {
            if (fromLobby && p.getGameMode() == org.bukkit.GameMode.ADVENTURE) p.setGameMode(org.bukkit.GameMode.SURVIVAL);
        });
        plugin.logs().log(p, "teleport", "tpa", t.getLocation(), "to " + t.getName());
    }

    private void tpdeny(Player t) {
        Request r = requests.remove(t.getUniqueId());
        if (r == null) { Msg.error(t, "tpa.none", "Нет активных запросов."); return; }
        Player p = Bukkit.getPlayer(r.from);
        if (p != null) Msg.send(p, "tpa.denied", "&c{t} отклонил ваш запрос.", "t", t.getName());
        Msg.send(t, "tpa.deny-ok", "&7Запрос отклонён.");
    }

    // ------------------------------------------------------------------ Команды

    private void registerCommands() {
        var cmds = plugin.commands();
        cmds.add("spawn").player().perm("usmp.player.spawn").module("teleport").cat("Перемещение").usage("/spawn")
                .desc("Телепорт на общий спавн (кулдаун 5 мин.)").exec((s, a) -> {
                    Player p = (Player) s;
                    if (blocked(p)) return;
                    Profile pr = plugin.profiles().get(p);
                    long last = pr == null ? 0 : pr.stat("spawn_last_ms");
                    long now = System.currentTimeMillis();
                    if (now - last < spawnCooldown && !plugin.roles().has(p, "usmp.admin.bypass-cooldown")) {
                        Msg.error(p, "spawn.cooldown", "Повторно можно через {t}.", "t", Text.duration(spawnCooldown - (now - last)));
                        return;
                    }
                    boolean fromLobby = plugin.lobby().isLobby(p.getWorld());
                    teleportWithWarmup(p, plugin.world().spawn(), "спавн", () -> {
                        if (pr != null) { pr.stats.put("spawn_last_ms", System.currentTimeMillis()); pr.dirtyStats.add("spawn_last_ms"); }
                        if (fromLobby && p.getGameMode() == org.bukkit.GameMode.ADVENTURE) p.setGameMode(org.bukkit.GameMode.SURVIVAL);
                    });
                });
        cmds.add("sethome").player().perm("usmp.player.home").module("teleport").cat("Перемещение").usage("/sethome <имя>")
                .desc("Установить дом (макс. 2)").cooldown(2000).exec((s, a) -> setHome((Player) s, a.length == 0 ? "home" : a[0])).tab((s, a) -> List.of());
        cmds.add("home").player().perm("usmp.player.home").module("teleport").cat("Перемещение").usage("/home [имя]")
                .desc("Телепорт домой или меню домов").exec((s, a) -> {
                    Player p = (Player) s;
                    Profile pr = plugin.profiles().get(p);
                    if (a.length == 0) {
                        if (pr != null && pr.homes.size() == 1) home(p, pr.homes.keySet().iterator().next());
                        else homesMenu(p, null);
                        return;
                    }
                    home(p, a[0]);
                }).tab((s, a) -> homeNames(s));
        cmds.add("delhome").player().perm("usmp.player.home").module("teleport").cat("Перемещение").usage("/delhome <имя>")
                .desc("Удалить дом").exec((s, a) -> {
                    if (a.length == 0) { homesMenu((Player) s, null); return; }
                    delHome((Player) s, a[0]);
                }).tab((s, a) -> homeNames(s));
        cmds.add("homes").player().perm("usmp.player.home").module("teleport").cat("Перемещение").usage("/homes")
                .desc("Меню домов").exec((s, a) -> homesMenu((Player) s, null));
        // /tpa и /tpr: с ником — отправить запрос, без ника — принять последний запрос.
        for (String name : new String[]{"tpa", "tpr"}) {
            cmds.add(name).player().perm("usmp.player.tpa").module("teleport").cat("Перемещение").usage("/" + name + " [игрок]")
                    .desc(name.equals("tpa") ? "Запрос телепортации к игроку (без ника — принять запрос)" : "Принять запрос телепортации (с ником — отправить запрос)")
                    .exec((s, a) -> {
                        Player p = (Player) s;
                        if (a.length == 0) { tpaccept(p); return; }
                        long now = System.currentTimeMillis();
                        Long last = lastRequest.get(p.getUniqueId());
                        if (last != null && now - last < 5000) { Msg.error(p, "tpa.too-fast", "Подождите пару секунд перед новым запросом."); return; }
                        lastRequest.put(p.getUniqueId(), now);
                        tpa(p, Bukkit.getPlayerExact(a[0]));
                    }).tab((s, a) -> Commands.players(a.length == 0 ? "" : a[0]));
        }
        cmds.add("tpaccept").player().perm("usmp.player.tpa").module("teleport").cat("Перемещение").usage("/tpaccept")
                .desc("Принять запрос телепортации").hidden().exec((s, a) -> tpaccept((Player) s));
        cmds.add("tpd").player().perm("usmp.player.tpa").module("teleport").cat("Перемещение").usage("/tpd")
                .desc("Отклонить запрос телепортации").exec((s, a) -> tpdeny((Player) s));
        cmds.add("tpdeny").player().perm("usmp.player.tpa").module("teleport").cat("Перемещение").usage("/tpdeny")
                .desc("Отклонить запрос телепортации").hidden().exec((s, a) -> tpdeny((Player) s));
        cmds.add("buyhome").player().perm("usmp.player.home").module("teleport").cat("Перемещение").usage("/buyhome")
                .desc("Докупить слот дома").exec((s, a) -> {
                    Player p = (Player) s;
                    long price = plugin.configs().main().getLong("teleport.extra-home-price", 1_000_000);
                    ConfirmMenu.open(p, "&8Купить слот за " + Text.money(price) + "?", null, List.of("&7Ещё один дом"), () -> buyHomeSlot(p, null));
                });
    }

    private List<String> homeNames(org.bukkit.command.CommandSender s) {
        if (!(s instanceof Player p)) return List.of();
        Profile pr = plugin.profiles().get(p);
        return pr == null ? List.of() : new ArrayList<>(pr.homes.keySet());
    }
}
