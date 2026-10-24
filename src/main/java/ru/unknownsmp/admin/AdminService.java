package ru.unknownsmp.admin;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.SetupWizard;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Администрирование: команда /usmp, невидимость персонала, инспектор блоков, просмотр инвентарей. */
public final class AdminService implements Module, Listener {
    private final UnknownSMP plugin;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final Set<UUID> inspecting = ConcurrentHashMap.newKeySet();
    private OwnerPanel panel;

    public AdminService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "admin"; }
    @Override public String title() { return "Администрирование"; }

    @Override
    public void enable() {
        panel = new OwnerPanel(plugin, this);
        Tasks.later(100, () -> structures().autoPlace());
        Tasks.timer(100, 40, this::spoofSpectators);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.commands().add("unknownsmp").perm("usmp.mod.base").cat("Администрирование").usage("/usmp <раздел> ...")
                .desc("Администрирование сервера (без аргументов — панель)").exec(this::command).tab(this::tab);
    }

    public OwnerPanel panel() { return panel; }

    public boolean isVanished(Player p) { return vanished.contains(p.getUniqueId()); }

    public void toggleVanish(Player p) {
        if (vanished.remove(p.getUniqueId())) {
            // Вышел из ванища: снова виден всем, звучит как обычный вход.
            for (Player o : Bukkit.getOnlinePlayers()) if (plugin.auth().isAuthed(o)) o.showPlayer(plugin, p);
            p.setInvisible(false);
            p.setSilent(false);
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p && plugin.auth().isAuthed(o))
                Msg.plain(o, "vanish.fake-join", "&e{p} присоединился к игре", "p", p.getName());
            Msg.ok(p, "admin.vanish-off", "Вы снова видимы.");
        } else {
            vanished.add(p.getUniqueId());
            // Прячем от всех (и из таба — hidePlayer убирает из списка у получателя), кроме самого игрока.
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p) o.hidePlayer(plugin, p);
            p.setInvisible(true);
            p.setSilent(true);
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p && plugin.auth().isAuthed(o))
                Msg.plain(o, "vanish.fake-leave", "&e{p} покинул игру", "p", p.getName());
            Msg.ok(p, "admin.vanish-on", "Вы в скрытом режиме: вас нет в табе, выглядит как выход.");
        }
        plugin.logs().log(p, "admin", "vanish", p.getLocation(), String.valueOf(isVanished(p)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        for (UUID u : vanished) {
            if (u.equals(p.getUniqueId())) continue;
            Player v = Bukkit.getPlayer(u);
            if (v != null) p.hidePlayer(plugin, v);
        }
    }

    // ------------------------------------------------------------------ Наблюдатель в табе выглядит как обычный игрок

    private boolean spectatingStaff(Player p) {
        return plugin.configs().main().getBoolean("tab.hide-spectator", true) && p.getGameMode() == GameMode.SPECTATOR && !isVanished(p) && plugin.roles().has(p, "usmp.mod.vanish");
    }

    /** Разослать всем остальным подмену режима для наблюдающего персонала. */
    private void spoofSpectators() {
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (!spectatingStaff(s)) continue;
            for (Player v : Bukkit.getOnlinePlayers()) if (v != s) TabSpoof.send(v, s);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void gameMode(org.bukkit.event.player.PlayerGameModeChangeEvent e) {
        Tasks.later(2, this::spoofSpectators);
        Tasks.later(20, this::spoofSpectators);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void joinSpoof(PlayerJoinEvent e) {
        Tasks.later(30, this::spoofSpectators);
    }

    /** Фейерверки и куры из раздела троллинга безвредны. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void trollDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (e.getDamager().getPersistentDataContainer().has(ru.unknownsmp.core.Keys.TROLL_ENTITY, org.bukkit.persistence.PersistentDataType.BYTE)
                || e.getEntity().getPersistentDataContainer().has(ru.unknownsmp.core.Keys.TROLL_ENTITY, org.bukkit.persistence.PersistentDataType.BYTE)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        vanished.remove(e.getPlayer().getUniqueId());
        inspecting.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ Инспектор

    @EventHandler(priority = EventPriority.LOWEST)
    public void inspect(PlayerInteractEvent e) {
        if (!inspecting.contains(e.getPlayer().getUniqueId()) || e.getClickedBlock() == null) return;
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        var loc = e.getAction() == Action.RIGHT_CLICK_BLOCK ? e.getClickedBlock().getRelative(e.getBlockFace()).getLocation() : e.getClickedBlock().getLocation();
        Tasks.then(plugin.logs().blockHistory(loc, 10), list -> {
            Msg.raw(p, "&8--- &7История блока " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + " &8---");
            if (list.isEmpty()) Msg.raw(p, "&7Записей нет.");
            for (var en : list) {
                Msg.raw(p, "&8" + Text.date(en.at()) + " &f" + en.name() + " &7" + en.action().toLowerCase() + " &f"
                        + (en.action().equals("PLACE") ? en.newData() : en.oldData()).replace("minecraft:", "") + (en.rolledBack() ? " &8(откачено)" : ""));
            }
        }, null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void inspectBreak(BlockBreakEvent e) {
        if (inspecting.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ Просмотр инвентаря (только чтение)

    public void invsee(Player staff, Player target, boolean ender) {
        ItemStack[] items = ender ? target.getEnderChest().getContents() : target.getInventory().getContents();
        new Menu(staff, ender ? 3 : 5, "&8" + (ender ? "Эндер-сундук " : "Инвентарь ") + target.getName()) {
            @Override
            protected void build() {
                for (int i = 0; i < items.length && i < size(); i++) if (items[i] != null) set(i, Items.display(items[i], List.of("&8(только просмотр)")));
            }
        }.open();
        plugin.logs().log(staff, "admin", ender ? "endersee" : "invsee", staff.getLocation(), target.getName());
    }

    // ------------------------------------------------------------------ Команда /usmp

    private boolean need(CommandSender s, String perm) {
        return plugin.roles().check(s, perm);
    }

    private void command(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (s instanceof Player p) {
                if (plugin.roles().isOwner(p) || plugin.roles().has(p, "usmp.admin.panel")) panel.open(p);
                else help(s);
            } else help(s);
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        String[] r = Arrays.copyOfRange(a, 1, a.length);
        switch (sub) {
            case "help" -> help(s);
            case "reload" -> {
                if (!need(s, "usmp.admin.reload")) return;
                plugin.configs().loadAll();
                plugin.modules().reloadAll(s);
                for (String pr : plugin.configs().problems()) Msg.error(s, "reload.problem", "{p}", "p", pr);
                Msg.ok(s, "reload.done", "Настройки перезагружены (безопасные изменения). Изменения database.yml требуют перезапуска.");
                plugin.logs().log(s instanceof Player p ? p : null, "admin", "reload", null, "");
            }
            case "status" -> {
                if (!need(s, "usmp.admin.monitor")) return;
                for (String l : plugin.monitor().lines()) Msg.raw(s, l);
            }
            case "panel" -> { if (s instanceof Player p && need(s, "usmp.admin.panel")) panel.open(p); }
            case "setup" -> { if (s instanceof Player p && plugin.roles().isOwner(p)) SetupWizard.open(plugin, p); else SetupWizard.consoleReport(plugin); }
            case "setspawn" -> {
                if (!(s instanceof Player p) || !need(s, "usmp.admin.world")) return;
                plugin.world().setSpawn(p.getLocation());
                Msg.ok(p, "admin.spawn-set", "Спавн выживания установлен: {c}", "c", Text.coords(p.getLocation()));
                plugin.logs().log(p, "admin", "setspawn", p.getLocation(), "");
            }
            case "setlobby" -> {
                if (!(s instanceof Player p) || !need(s, "usmp.admin.world")) return;
                if (!plugin.lobby().isLobby(p.getWorld())) { Msg.error(p, "admin.not-lobby", "Встаньте в мире лобби."); return; }
                plugin.lobby().setSpawn(p.getLocation());
                Msg.ok(p, "admin.lobby-set", "Точка лобби установлена.");
            }
            case "maintenance" -> {
                if (!need(s, "usmp.admin.maintenance")) return;
                if (r.length > 0 && r[0].equalsIgnoreCase("off")) { plugin.maintenance().disable(s); return; }
                int sec = r.length > 1 ? parseInt(r[1], 0) : 0;
                String why = r.length > 2 ? String.join(" ", Arrays.copyOfRange(r, 2, r.length)) : "";
                plugin.maintenance().enable(s, why, sec);
            }
            case "backup" -> backup(s, r);
            case "role" -> {
                if (!plugin.roles().isOwner(s)) { Msg.error(s, "perm.owner", "Только владелец или консоль."); return; }
                if (r.length < 2 || !plugin.roles().all().containsKey(r[1])) { Msg.error(s, "admin.role-usage", "Использование: /usmp role <игрок> <{r}>", "r", String.join("|", plugin.roles().ids())); return; }
                Tasks.then(plugin.profiles().findUuid(r[0]), u -> {
                    if (u == null) { Msg.error(s, "player.unknown", "Игрок не найден (должен хотя бы раз зайти на сервер)."); return; }
                    Tasks.then(plugin.roles().setRole(u, r[1]), n -> {
                        Msg.ok(s, "admin.role-set", "Роль {p}: {r}", "p", r[0], "r", r[1]);
                        plugin.logs().log(s instanceof Player p ? p : null, "admin", "role", null, r[0] + " -> " + r[1]);
                        Player t = Bukkit.getPlayer(u);
                        if (t != null) plugin.display().refresh(t);
                    }, null);
                }, null);
            }
            case "money" -> money(s, r);
            case "item" -> item(s, r);
            case "npc" -> npc(s, r);
            case "event" -> event(s, r);
            case "nether", "end" -> {
                if (!need(s, "usmp.admin.events") || r.length < 1) return;
                boolean open = r[0].equalsIgnoreCase("open");
                if (sub.equals("nether")) plugin.dimensions().forceNether(open); else plugin.dimensions().forceEnd(open);
                Msg.ok(s, "admin.dim", "{d}: {s}", "d", sub, "s", open ? "открыт" : "закрыт");
                plugin.logs().log(s instanceof Player p ? p : null, "admin", sub + "_" + (open ? "open" : "close"), null, "");
            }
            case "rollback" -> { if (s instanceof Player p && need(s, "usmp.admin.rollback")) plugin.rollback().command(p, r); }
            case "inspect" -> {
                if (!(s instanceof Player p) || !need(s, "usmp.mod.inspect")) return;
                if (!inspecting.remove(p.getUniqueId())) { inspecting.add(p.getUniqueId()); Msg.ok(p, "admin.inspect-on", "Инспектор включён: нажмите на блок."); }
                else Msg.ok(p, "admin.inspect-off", "Инспектор выключен.");
            }
            case "checks", "проверки" -> { if (s instanceof Player p && need(s, "usmp.mod.alerts")) ru.unknownsmp.zhora.CheatMenus.show(plugin, p, "open", null); }
            case "vanish", "v" -> { if (s instanceof Player p && need(s, "usmp.mod.vanish")) toggleVanish(p); }
            case "tp" -> {
                if (!(s instanceof Player p) || !need(s, "usmp.mod.tp") || r.length < 1) return;
                Player t = Bukkit.getPlayerExact(r[0]);
                if (t == null) { Msg.error(p, "player.offline", "Игрок не в сети."); return; }
                p.teleport(t);
                plugin.logs().log(p, "admin", "tp", t.getLocation(), t.getName());
            }
            case "invsee", "endersee" -> {
                if (!(s instanceof Player p) || !need(s, "usmp.mod.invsee") || r.length < 1) return;
                Player t = Bukkit.getPlayerExact(r[0]);
                if (t == null) { Msg.error(p, "player.offline", "Игрок не в сети."); return; }
                invsee(p, t, sub.equals("endersee"));
            }
            case "structure", "schem" -> structure(s, r);
            case "fun" -> funBuild(s, r);
            case "sign" -> signBuild(s);
            case "dump" -> dump(s, r);
            case "dev" -> dev(s, r);
            case "auth" -> auth(s, r);
            case "lookup" -> lookup(s, r);
            case "broadcast", "bc" -> {
                if (!need(s, "usmp.admin.broadcast") || r.length == 0) return;
                Msg.broadcast("admin.broadcast", "&c&l[Объявление] &f{m}", "m", String.join(" ", r));
            }
            case "gm" -> {
                if (!(s instanceof Player p) || !need(s, "usmp.admin.gamemode") || r.length < 1) return;
                GameMode gm = switch (r[0]) { case "0", "s" -> GameMode.SURVIVAL; case "1", "c" -> GameMode.CREATIVE; case "2", "a" -> GameMode.ADVENTURE; default -> GameMode.SPECTATOR; };
                p.setGameMode(gm);
                plugin.logs().log(p, "admin", "gamemode", p.getLocation(), gm.name());
            }
            default -> help(s);
        }
    }

    // ------------------------------------------------------------------ Готовые постройки и игры

    private ru.unknownsmp.world.StructureTools structures;

    public ru.unknownsmp.world.StructureTools structures() {
        if (structures == null) structures = new ru.unknownsmp.world.StructureTools(plugin);
        return structures;
    }

    private void structure(CommandSender s, String[] r) {
        if (!need(s, "usmp.admin.world")) return;
        var tools = structures();
        String act = r.length > 0 ? r[0].toLowerCase(Locale.ROOT) : "list";
        switch (act) {
            case "list" -> {
                Msg.raw(s, "&7Папка: &fplugins/UnknownSMP/structures");
                var files = tools.files();
                Msg.raw(s, files.isEmpty() ? "&7Файлов .nbt нет." : "&f" + String.join(", ", files));
            }
            case "pos1", "pos2" -> {
                if (!(s instanceof Player p)) return;
                tools.select(p, act.equals("pos1") ? 0 : 1, p.getLocation());
                Msg.ok(p, "struct.pos", "Точка {n} задана: {c}", "n", act.substring(3), "c", Text.coords(p.getLocation()));
            }
            case "save" -> {
                if (!(s instanceof Player p) || r.length < 2) { Msg.error(s, "struct.usage", "/usmp structure save <имя> (сначала pos1 и pos2)"); return; }
                var sel = tools.selection(p);
                if (sel == null || sel[0] == null || sel[1] == null) { Msg.error(p, "struct.sel", "Сначала задайте pos1 и pos2."); return; }
                ru.unknownsmp.world.StructureTools.send(p, tools.save(r[1], sel[0], sel[1], false));
                plugin.logs().log(p, "admin", "structure_save", p.getLocation(), r[1]);
            }
            case "place" -> {
                if (!(s instanceof Player p) || r.length < 2) { Msg.error(s, "struct.usage", "/usmp structure place <файл> [0|90|180|270] [center]"); return; }
                int rot = 0;
                boolean center = false;
                for (int i = 2; i < r.length; i++) {
                    if (r[i].equalsIgnoreCase("center")) center = true;
                    else rot = parseInt(r[i], 0);
                }
                Msg.send(p, "struct.placing", "&7Ставлю постройку, подождите...");
                tools.place(r[1], p.getLocation().getBlock().getLocation(), rot, "NONE", false, center).thenAccept(res -> Tasks.sync(() -> ru.unknownsmp.world.StructureTools.send(p, res)));
                plugin.logs().log(p, "admin", "structure_place", p.getLocation(), r[1]);
            }
            default -> Msg.raw(s, "&7/usmp structure list | place <файл> [0|90|180|270] [center] | pos1 | pos2 | save <имя>");
        }
    }

    /** Служебная команда разработчика: выгрузка области блоков в JSON для предпросмотра. /usmp dump x1 y1 z1 x2 y2 z2 имя */
    private void dump(CommandSender s, String[] r) {
        if (!need(s, "usmp.admin.world") || r.length < 7 || !(s instanceof Player p)) return;
        try {
            int x1 = Integer.parseInt(r[0]), y1 = Integer.parseInt(r[1]), z1 = Integer.parseInt(r[2]);
            int x2 = Integer.parseInt(r[3]), y2 = Integer.parseInt(r[4]), z2 = Integer.parseInt(r[5]);
            int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
            java.util.Map<String, Integer> pal = new java.util.LinkedHashMap<>();
            StringBuilder data = new StringBuilder();
            var w = p.getWorld();
            for (int x = ax; x <= bx; x++) for (int y = ay; y <= by; y++) for (int z = az; z <= bz; z++) {
                String bd = w.getBlockAt(x, y, z).getBlockData().getAsString().replace("minecraft:", "");
                int idx = pal.computeIfAbsent(bd, k -> pal.size());
                data.append(idx).append(',');
            }
            StringBuilder sb = new StringBuilder("{\"o\":[" + ax + "," + ay + "," + az + "],\"s\":[" + (bx - ax + 1) + "," + (by - ay + 1) + "," + (bz - az + 1) + "],\"p\":[");
            boolean first = true;
            for (String k : pal.keySet()) { if (!first) sb.append(','); first = false; sb.append('"').append(k.replace("\"", "'")).append('"'); }
            sb.append("],\"d\":[").append(data, 0, Math.max(0, data.length() - 1)).append("]}");
            File dir = new File(plugin.getDataFolder(), "dump");
            dir.mkdirs();
            java.nio.file.Files.writeString(new File(dir, r[6].replaceAll("[^a-zA-Z0-9_-]", "") + ".json").toPath(), sb.toString());
            Msg.ok(p, "dump.ok", "Выгружено блоков: {n}", "n", (long) (bx - ax + 1) * (by - ay + 1) * (bz - az + 1));
        } catch (Exception e) {
            Msg.error(s, "dump.err", "Ошибка: {e}", "e", e.toString());
        }
    }

    /** Служебная команда разработчика: /usmp dev plat <r> | <постройка> | list. */
    private void dev(CommandSender s, String[] r) {
        if (!(s instanceof Player p) || !need(s, "usmp.admin.world")) return;
        if (r.length < 1) { Msg.raw(s, "&7/usmp dev plat <r> | list | <постройка>"); return; }
        var w = p.getWorld();
        int px = p.getLocation().getBlockX(), py = p.getLocation().getBlockY() - 1, pz = p.getLocation().getBlockZ();
        if (r[0].equalsIgnoreCase("list")) { Msg.raw(s, "&f" + String.join(", ", ru.unknownsmp.world.Houses.ALL.keySet())); return; }
        if (r[0].equalsIgnoreCase("plat")) {
            int rad = r.length > 1 ? parseInt(r[1], 40) : 40;
            for (int x = px - rad; x <= px + rad; x++) for (int z = pz - rad; z <= pz + rad; z++) {
                for (int y = py + 1; y <= py + 45; y++) w.getBlockAt(x, y, z).setType(Material.AIR, false);
                w.getBlockAt(x, py, z).setType(Material.GRASS_BLOCK, false);
                for (int y = py - 1; y >= py - 4; y--) w.getBlockAt(x, y, z).setType(Material.DIRT, false);
            }
            Msg.ok(p, "dev.plat", "Платформа готова.");
            return;
        }
        if (r[0].equalsIgnoreCase("village")) {
            Msg.send(p, "dev.village", "&7Строю деревню вокруг вас (в пустом мире), это займёт несколько секунд...");
            ru.unknownsmp.world.Village.buildAsync(w, px, pz, py, ru.unknownsmp.world.Anchors::put, sp -> Msg.ok(p, "dev.village.done", "Деревня построена."));
            return;
        }
        if (r[0].equalsIgnoreCase("holes")) { devHoles(p, px, py, pz); return; }
        if (r[0].equalsIgnoreCase("econ")) { for (String line : plugin.shop().audit()) plugin.getLogger().info("ECON " + line); Msg.ok(p, "dev.econ", "Готово, смотрите консоль (ECON)."); return; }
        var spec = ru.unknownsmp.world.Houses.ALL.get(r[0].toLowerCase(Locale.ROOT));
        if (spec == null) { Msg.error(p, "dev.unknown", "Нет такой постройки (/usmp dev list)."); return; }
        org.bukkit.util.Vector d = p.getLocation().getDirection().setY(0);
        int fx, fz;
        if (Math.abs(d.getX()) > Math.abs(d.getZ())) { fx = d.getX() > 0 ? 1 : -1; fz = 0; } else { fx = 0; fz = d.getZ() > 0 ? 1 : -1; }
        int bx = px + fx * 8, bz = pz + fz * 8;
        // Расчистка участка под постройку и свежий газон.
        int rx0 = bx - spec.w() / 2 - 12, rx1 = bx + spec.w() / 2 + 12, rz0 = pz + (fz > 0 ? -4 : fz < 0 ? -spec.d() - 20 : -20), rz1 = pz + (fz > 0 ? spec.d() + 24 : fz < 0 ? 4 : 20);
        if (fz == 0) { rx0 = px - 4; rx1 = px + spec.d() + 24; if (fx < 0) { rx0 = px - spec.d() - 24; rx1 = px + 4; } rz0 = bz - spec.w() / 2 - 12; rz1 = bz + spec.w() / 2 + 12; }
        for (int x = rx0; x <= rx1; x++) for (int z = rz0; z <= rz1; z++) {
            for (int y = py + 1; y <= py + 45; y++) w.getBlockAt(x, y, z).setType(Material.AIR, false);
            w.getBlockAt(x, py, z).setType(Material.GRASS_BLOCK, false);
            for (int y = py - 1; y >= py - 4; y--) w.getBlockAt(x, y, z).setType(Material.DIRT, false);
        }
        var c = ru.unknownsmp.world.Canvas.front(w, bx, py, bz, fx, fz, spec.w());
        spec.build().accept(c, new java.util.Random(42));
        ru.unknownsmp.world.Interior.furnish(c, spec.id(), spec.w(), spec.d(), new java.util.Random(7));
        ru.unknownsmp.world.Canvas.flushConnections();
        Msg.ok(p, "dev.built", "Построено: {n} ({w}x{d}) перед вами.", "n", spec.id(), "w", spec.w(), "d", spec.d());
    }

    /** Строит каждую постройку отдельно и ищет дыры в стенах по периметру (воздух на высоте 1..3 и «окна» в стене над/под блоками). */
    private void devHoles(Player p, int px, int py, int pz) {
        var w = p.getWorld();
        int i = 0;
        for (var spec : ru.unknownsmp.world.Houses.ALL.values()) {
            int bx = px + (i % 8) * 36, bz = pz + (i / 8) * 36;
            i++;
            for (int x = bx - 20; x <= bx + 20; x++) for (int z = bz - 6; z <= bz + 30; z++) {
                for (int y = py + 1; y <= py + 45; y++) w.getBlockAt(x, y, z).setType(Material.AIR, false);
                w.getBlockAt(x, py, z).setType(Material.GRASS_BLOCK, false);
            }
            var c = ru.unknownsmp.world.Canvas.front(w, bx, py, bz, 0, 1, spec.w());
            spec.build().accept(c, new java.util.Random(42));
            ru.unknownsmp.world.Interior.furnish(c, spec.id(), spec.w(), spec.d(), new java.util.Random(7));
            ru.unknownsmp.world.Canvas.flushConnections();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int lx = 0; lx < spec.w(); lx++) for (int lz = 0; lz < spec.d(); lz++) {
                if (lx != 0 && lz != 0 && lx != spec.w() - 1 && lz != spec.d() - 1) continue;
                for (int ly = 1; ly <= 3; ly++) {
                    Material m = c.at(lx, ly, lz).getType();
                    if (m.isAir() || (!m.isSolid() && !m.name().contains("PANE") && !m.name().contains("DOOR") && !m.name().contains("BARS") && !m.name().contains("FENCE") && !m.name().contains("WALL") && !m.name().contains("LANTERN") && !m.name().contains("TORCH") && !m.name().contains("SIGN") && !m.name().contains("BANNER") && !m.name().contains("CARPET") && !m.name().contains("VINE") && !m.name().contains("LADDER") && !m.name().contains("POT") && !m.name().contains("BUTTON") && !m.name().contains("CANDLE") && !m.name().contains("HEAD") && !m.name().contains("TRAPDOOR"))) {
                        n++;
                        if (n <= 12) sb.append('(').append(lx).append(',').append(ly).append(',').append(lz).append(')');
                    }
                }
            }
            plugin.getLogger().info("HOLES " + spec.id() + " " + spec.w() + "x" + spec.d() + " perimeterAir=" + n + " " + sb);
        }
        Msg.ok(p, "dev.holes", "Готово, смотрите консоль (HOLES).");
    }

    /** Парящая вывеска с названием сервера: встаньте там, где должен быть низ по центру, и смотрите в сторону зрителей. */
    private void signBuild(CommandSender s) {
        if (!(s instanceof Player p) || !need(s, "usmp.admin.world")) return;
        var cfg = plugin.configs().main();
        String title = cfg.getString("branding.sign", "ALEX").toUpperCase(Locale.ROOT);
        String sub = cfg.getString("branding.sign-sub", "SMP").toUpperCase(Locale.ROOT);
        if (title.length() > 9) title = title.substring(0, 9);
        if (sub.length() > 6) sub = sub.substring(0, 6);
        org.bukkit.util.Vector d = p.getLocation().getDirection().setY(0);
        int fx, fz;
        if (Math.abs(d.getX()) > Math.abs(d.getZ())) { fx = d.getX() > 0 ? -1 : 1; fz = 0; } else { fx = 0; fz = d.getZ() > 0 ? -1 : 1; }
        var loc = p.getLocation().getBlock().getLocation();
        var c = ru.unknownsmp.world.Canvas.facing(p.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), fx, fz);
        int h = ru.unknownsmp.world.BigSign.build(c, title, sub);
        ru.unknownsmp.world.Canvas.flushConnections();
        Msg.ok(p, "sign.built", "Вывеска построена (высота {h}). Если не туда смотрит — уберите командой отката и повторите, повернувшись иначе.", "h", h);
        plugin.logs().log(p, "admin", "sign_build", p.getLocation(), title + " " + sub);
    }

    private void funBuild(CommandSender s, String[] r) {
        if (!(s instanceof Player p) || !need(s, "usmp.admin.world")) return;
        if (r.length < 1) {
            Msg.raw(p, "&7/usmp fun <disco|tramp|clicker|wheel|snow|tir|fw|launch|well|targets> — строит игру на месте, где вы стоите");
            return;
        }
        var loc = p.getLocation().getBlock().getLocation();
        boolean lobbyWorld = plugin.lobby() != null && plugin.lobby().isLobby(p.getWorld());
        var g = new ru.unknownsmp.world.Canvas(p.getWorld(), 0, loc.getBlockY() - 1, 0, 1, 0, 0, 1);
        var f = new ru.unknownsmp.world.Fun(p.getWorld(), g, ru.unknownsmp.world.Anchors::put, new java.util.Random(), lobbyWorld ? "_l" : "_c");
        int x = loc.getBlockX(), z = loc.getBlockZ();
        switch (r[0].toLowerCase(Locale.ROOT)) {
            case "disco" -> f.disco(x, z);
            case "tramp" -> f.trampolines(x, z, 15, 9);
            case "clicker" -> f.clicker(x, z);
            case "wheel" -> f.wheel(x, z, true);
            case "snow" -> f.snowPile(x, z, true);
            case "tir" -> f.tirLectern(x, z);
            case "targets" -> f.targets(x, x + 16, z, 3);
            case "fw" -> f.fireworkPedestal(x, z);
            case "launch" -> f.launchPad(x, z);
            case "well" -> f.wishingWell(x, z);
            default -> { Msg.error(p, "fun.unknown", "Неизвестная игра."); return; }
        }
        ru.unknownsmp.world.Canvas.flushConnections();
        Msg.ok(p, "fun.built", "Построено: {t}. Подписи появятся через несколько секунд.", "t", r[0]);
        plugin.logs().log(p, "admin", "fun_build", p.getLocation(), r[0]);
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private void help(CommandSender s) {
        Msg.raw(s, "&5&lALEX SMP — администрирование");
        String[][] lines = {
                {"usmp.admin.panel", "/usmp — панель владельца"},
                {"usmp.admin.reload", "/usmp reload — безопасная перезагрузка настроек"},
                {"usmp.admin.monitor", "/usmp status — мониторинг"},
                {"usmp.admin.world", "/usmp setspawn | setlobby"},
                {"usmp.admin.world", "/usmp structure list|place|pos1|pos2|save — готовые постройки (.nbt)"},
                {"usmp.admin.world", "/usmp fun <disco|tramp|clicker|wheel|snow|tir|fw|launch|well> — игры на карте"},
                {"usmp.admin.maintenance", "/usmp maintenance on [сек] [причина] | off"},
                {"usmp.admin.backup", "/usmp backup now | list | verify <имя> | restore <имя> [db,worlds,configs] | cancel"},
                {"*", "/usmp role <игрок> <роль> — только владелец/консоль"},
                {"usmp.admin.economy", "/usmp money give|take|set <игрок> <сумма>"},
                {"usmp.admin.items", "/usmp item give|potion <игрок> <id> | list"},
                {"usmp.admin.npc", "/usmp npc create <id> <VILLAGER|MANNEQUIN|TRADER> [скин/профессия] | remove | move | list | name | dialogue | action | role"},
                {"usmp.admin.events", "/usmp event start <meteor|cache|anomaly|merchant|secrets> | stop; /usmp nether|end open|close"},
                {"usmp.admin.rollback", "/usmp rollback <игрок|*> <время> [радиус]"},
                {"usmp.mod.inspect", "/usmp inspect — история блоков"},
                {"usmp.mod.vanish", "/usmp vanish"},
                {"usmp.mod.tp", "/usmp tp <игрок>"},
                {"usmp.mod.invsee", "/usmp invsee|endersee <игрок>"},
                {"usmp.admin.auth", "/usmp auth reset|unlock|unregister|revoke <ник>"},
                {"usmp.mod.logs", "/usmp lookup <игрок> [категория] [время]"},
                {"usmp.admin.broadcast", "/usmp bc <текст>"},
        };
        for (String[] l : lines) if (l[0].equals("*") ? plugin.roles().isOwner(s) : plugin.roles().has(s, l[0])) Msg.raw(s, "&e" + l[1]);
    }

    private void backup(CommandSender s, String[] r) {
        if (!need(s, "usmp.admin.backup")) return;
        String op = r.length == 0 ? "list" : r[0].toLowerCase(Locale.ROOT);
        switch (op) {
            case "now" -> plugin.backups().create("manual", s);
            case "list" -> {
                List<File> list = plugin.backups().list();
                Msg.raw(s, "&7Резервные копии (" + list.size() + ") в &f" + plugin.backups().dir().getPath());
                for (int i = 0; i < Math.min(15, list.size()); i++) {
                    File f = list.get(i);
                    boolean failed = new File(f, "FAILED.txt").exists();
                    Msg.raw(s, (failed ? "&c✘ " : "&a✔ ") + "&f" + f.getName());
                }
            }
            case "verify" -> {
                if (r.length < 2) return;
                Msg.send(s, "backup.verifying", "&7Проверка {n}...", "n", r[1]);
                Tasks.then(plugin.backups().verifyAsync(r[1]), res -> {
                    if (res == null) Msg.ok(s, "backup.verified", "Копия {n} цела, пробное восстановление прошло успешно.", "n", r[1]);
                    else Msg.error(s, "backup.verify-fail", "Копия {n} НЕ прошла проверку: {e}", "n", r[1], "e", res);
                }, null);
            }
            case "restore" -> {
                if (!plugin.roles().isOwner(s)) { Msg.error(s, "perm.owner", "Только владелец или консоль."); return; }
                if (r.length < 2) return;
                List<String> parts = r.length > 2 ? Arrays.asList(r[2].split(",")) : List.of("db");
                try {
                    plugin.backups().scheduleRestore(r[1], parts);
                    Msg.ok(s, "backup.restore-scheduled", "Восстановление {n} ({p}) будет применено при следующем запуске сервера. Перезапустите сервер. Отмена: /usmp backup cancel",
                            "n", r[1], "p", String.join(",", parts));
                    plugin.logs().log(s instanceof Player p ? p : null, "admin", "restore_scheduled", null, r[1] + " " + parts);
                } catch (Exception e) {
                    Msg.error(s, "backup.restore-fail", "Нельзя запланировать восстановление: {e}", "e", e.getMessage());
                }
            }
            case "cancel" -> {
                plugin.backups().cancelRestore();
                Msg.ok(s, "backup.restore-cancel", "Запланированное восстановление отменено.");
            }
            default -> help(s);
        }
    }

    private void money(CommandSender s, String[] r) {
        if (!need(s, "usmp.admin.economy")) return;
        if (r.length < 3) { Msg.error(s, "admin.money-usage", "Использование: /usmp money give|take|set <игрок> <сумма>"); return; }
        long amount = r[2].equals("0") ? 0 : Text.parseAmount(r[2]);
        if (amount < 0) { Msg.error(s, "admin.amount", "Неверная сумма."); return; }
        Tasks.then(plugin.profiles().findUuid(r[1]), u -> {
            if (u == null) { Msg.error(s, "player.unknown", "Игрок не найден."); return; }
            var f = switch (r[0].toLowerCase(Locale.ROOT)) {
                case "give" -> plugin.economy().deposit(u, amount, "admin_give", s.getName());
                case "take" -> plugin.economy().withdraw(u, amount, "admin_take", s.getName());
                case "set" -> plugin.economy().set(u, amount, "admin_set:" + s.getName());
                default -> null;
            };
            if (f == null) return;
            Tasks.then(f, bal -> {
                Msg.ok(s, "admin.money-done", "Готово. Баланс {p}: {b}", "p", r[1], "b", Text.money(bal));
                plugin.logs().log(s instanceof Player p ? p : null, "admin", "money_" + r[0], null, r[1] + " " + amount + " -> " + bal);
            }, err -> Msg.error(s, "admin.money-fail", "Ошибка: {e}", "e", err.getMessage()));
        }, null);
    }

    private void item(CommandSender s, String[] r) {
        if (!need(s, "usmp.admin.items")) return;
        if (r.length >= 1 && r[0].equalsIgnoreCase("list")) {
            Msg.raw(s, "&7Уникальные: &f" + String.join(", ", plugin.items().defs().keySet()));
            Msg.raw(s, "&7Зелья: &f" + String.join(", ", plugin.items().potions().keySet()));
            return;
        }
        if (r.length < 3) { Msg.error(s, "admin.item-usage", "Использование: /usmp item give|potion <игрок> <id>"); return; }
        Player t = Bukkit.getPlayerExact(r[1]);
        if (t == null) { Msg.error(s, "player.offline", "Игрок не в сети."); return; }
        if (r[0].equalsIgnoreCase("potion")) {
            ItemStack it = plugin.items().potion(r[2]);
            if (it == null) { Msg.error(s, "admin.item-unknown", "Нет такого зелья."); return; }
            plugin.mail().giveOrMail(t, it, "admin");
            plugin.logs().log(s instanceof Player p ? p : null, "admin", "give_potion", null, t.getName() + " " + r[2]);
            Msg.ok(s, "admin.item-given", "Выдано.");
            return;
        }
        Tasks.then(plugin.items().create(r[2], t.getName(), "admin:" + s.getName()), it -> {
            plugin.mail().giveOrMail(t, it, "admin");
            plugin.logs().log(s instanceof Player p ? p : null, "admin", "give_unique", null, t.getName() + " " + r[2] + " uid=" + plugin.items().uidOf(it));
            Msg.ok(s, "admin.item-given", "Выдано.");
        }, err -> Msg.error(s, "admin.item-fail", "Ошибка: {e}", "e", err.getMessage()));
    }

    private void npc(CommandSender s, String[] r) {
        if (!need(s, "usmp.admin.npc")) return;
        if (r.length == 0 || r[0].equalsIgnoreCase("list")) {
            for (var n : plugin.npcs().all().values()) Msg.raw(s, "&e" + n.id() + " &7" + n.type() + " " + n.role() + " &f" + n.name() + " &8" + n.locSpec());
            return;
        }
        String op = r[0].toLowerCase(Locale.ROOT);
        if (r.length < 2) { help(s); return; }
        String id = r[1];
        switch (op) {
            case "create" -> {
                if (!(s instanceof Player p)) return;
                if (plugin.npcs().all().containsKey(id)) { Msg.error(s, "npc.exists", "NPC уже существует."); return; }
                plugin.npcs().create(p, id, r.length > 2 ? r[2] : "VILLAGER", r.length > 3 ? r[3] : null);
                Msg.ok(s, "npc.created", "NPC {id} создан. Настройте: /usmp npc name|dialogue|action|role {id} ...", "id", id);
            }
            case "remove" -> Msg.send(s, "npc.result", plugin.npcs().remove(id) ? "&aУдалён." : "&cНе найден.");
            case "move" -> { if (s instanceof Player p) Msg.send(s, "npc.result", plugin.npcs().set(id, "location", Text.loc(p.getLocation())) ? "&aПеремещён." : "&cНе найден."); }
            case "name" -> Msg.send(s, "npc.result", r.length > 2 && plugin.npcs().set(id, "name", String.join(" ", Arrays.copyOfRange(r, 2, r.length))) ? "&aГотово." : "&cОшибка.");
            case "dialogue" -> Msg.send(s, "npc.result", r.length > 2 && plugin.npcs().set(id, "dialogue", r[2]) ? "&aГотово." : "&cОшибка.");
            case "action" -> Msg.send(s, "npc.result", r.length > 2 && plugin.npcs().set(id, "action", r[2].equals("none") ? null : r[2]) ? "&aГотово." : "&cОшибка.");
            case "role" -> Msg.send(s, "npc.result", r.length > 2 && plugin.npcs().set(id, "role", r[2].toUpperCase(Locale.ROOT)) ? "&aГотово." : "&cОшибка.");
            case "skin" -> Msg.send(s, "npc.result", r.length > 2 && plugin.npcs().set(id, "appearance.skin", r[2]) ? "&aГотово." : "&cОшибка.");
            default -> help(s);
        }
        plugin.logs().log(s instanceof Player p ? p : null, "admin", "npc_" + op, null, String.join(" ", r));
    }

    private void event(CommandSender s, String[] r) {
        if (!need(s, "usmp.admin.events")) return;
        if (r.length == 0) { help(s); return; }
        switch (r[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (r.length < 2) return;
                if (plugin.events().active() != null) { Msg.error(s, "event.busy", "Уже идёт событие."); return; }
                plugin.events().forceStart(r[1]);
                Msg.ok(s, "event.forced", "Событие {t} запускается.", "t", r[1]);
            }
            case "stop" -> { plugin.events().stopActive(); Msg.ok(s, "event.stopped", "Событие остановлено."); }
            default -> help(s);
        }
        plugin.logs().log(s instanceof Player p ? p : null, "admin", "event_" + r[0], null, String.join(" ", r));
    }

    private void auth(CommandSender s, String[] r) {
        if (!need(s, "usmp.admin.auth") || r.length < 2) { help(s); return; }
        String name = r[1];
        switch (r[0].toLowerCase(Locale.ROOT)) {
            case "reset" -> Tasks.then(plugin.auth().adminReset(name), temp -> {
                if (temp == null) { Msg.error(s, "auth.no-account", "Аккаунт не найден."); return; }
                Msg.ok(s, "auth.reset-done", "Временный пароль для {n}: &f{t}&a. Передайте его владельцу лично после проверки. Сессии отозваны.", "n", name, "t", temp);
                plugin.logs().log(s instanceof Player p ? p : null, "admin", "auth_reset", null, name);
                Player t = Bukkit.getPlayerExact(name);
                if (t != null) t.kick(Text.c("&eПароль аккаунта сброшен администратором."));
            }, null);
            case "unlock" -> Tasks.then(plugin.auth().unlock(name), n -> Msg.ok(s, "auth.unlocked", "Блокировка входа снята ({n}).", "n", n), null);
            case "unregister" -> {
                if (!plugin.roles().isOwner(s)) { Msg.error(s, "perm.owner", "Только владелец."); return; }
                Tasks.then(plugin.auth().unregister(name), n -> Msg.ok(s, "auth.unregistered", "Регистрация удалена ({n}). Данные игрока сохранены.", "n", n), null);
                plugin.logs().log(s instanceof Player p ? p : null, "admin", "auth_unregister", null, name);
            }
            case "revoke" -> Tasks.then(plugin.auth().revokeSessions(name), n -> Msg.ok(s, "auth.revoked", "Сессии отозваны ({n}).", "n", n), null);
            default -> help(s);
        }
    }

    private void lookup(CommandSender s, String[] r) {
        if (!need(s, "usmp.mod.logs") || r.length < 1) { help(s); return; }
        String cat = r.length > 1 && !r[1].equals("*") ? r[1] : null;
        long since = r.length > 2 ? Text.parseDuration(r[2]) : 86_400_000L;
        if (since <= 0) since = 86_400_000L;
        final long sinceAt = System.currentTimeMillis() - since;
        Tasks.then(plugin.profiles().findUuid(r[0]), u -> {
            if (u == null) { Msg.error(s, "player.unknown", "Игрок не найден."); return; }
            Tasks.then(plugin.logs().query(u.toString(), cat, sinceAt, 20), list -> {
                Msg.raw(s, "&8--- &7Журнал " + r[0] + (cat == null ? "" : " / " + cat) + " &8---");
                for (var l : list) Msg.raw(s, "&8" + Text.date(l.at()) + " &e" + l.category() + "/" + l.action() + " &f" + (l.details() == null ? "" : l.details())
                        + (l.x() == null ? "" : " &8@" + l.world() + " " + l.x() + "," + l.y() + "," + l.z()));
                if (list.isEmpty()) Msg.raw(s, "&7Записей нет.");
            }, null);
        }, null);
    }

    private List<String> tab(CommandSender s, String[] a) {
        if (a.length == 1) return new ArrayList<>(List.of("help", "reload", "status", "panel", "setup", "setspawn", "setlobby", "maintenance", "backup", "role", "money",
                "item", "npc", "event", "nether", "end", "rollback", "inspect", "vanish", "tp", "invsee", "endersee", "auth", "lookup", "broadcast", "gm", "structure", "fun", "sign", "dump", "dev"));
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) return switch (sub) {
            case "backup" -> List.of("now", "list", "verify", "restore", "cancel");
            case "money" -> List.of("give", "take", "set");
            case "item" -> List.of("give", "potion", "list");
            case "npc" -> List.of("create", "remove", "move", "list", "name", "dialogue", "action", "role", "skin");
            case "event" -> List.of("start", "stop");
            case "nether", "end" -> List.of("open", "close");
            case "maintenance" -> List.of("on", "off");
            case "auth" -> List.of("reset", "unlock", "unregister", "revoke");
            case "gm" -> List.of("0", "1", "2", "3");
            default -> ru.unknownsmp.core.Commands.players(a[1]);
        };
        if (a.length == 3) return switch (sub) {
            case "role" -> plugin.roles().ids();
            case "item" -> new ArrayList<>(a[1].equalsIgnoreCase("potion") ? List.of() : List.of());
            case "event" -> List.of("meteor", "cache", "anomaly", "merchant", "secrets");
            case "backup" -> {
                List<String> l = new ArrayList<>();
                for (File f : plugin.backups().list()) l.add(f.getName());
                yield l;
            }
            case "npc" -> new ArrayList<>(plugin.npcs().all().keySet());
            default -> ru.unknownsmp.core.Commands.players(a[2]);
        };
        if (a.length == 4 && sub.equals("item")) return new ArrayList<>(a[1].equalsIgnoreCase("potion") ? plugin.items().potions().keySet() : plugin.items().defs().keySet());
        if (a.length == 4 && sub.equals("npc") && a[1].equalsIgnoreCase("create")) return List.of("VILLAGER", "MANNEQUIN", "TRADER");
        return List.of();
    }
}
