package ru.unknownsmp.admin;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.unknownsmp.core.ChatInput;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.SetupWizard;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.ConfirmMenu;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.gui.PagedMenu;
import ru.unknownsmp.profile.Profile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Панель владельца: игроки, журналы, откаты, экономика, события, настройки, NPC, ранги, безопасность,
 * модерация, троллинг (безвредные эффекты), мониторинг и резервные копии. Все действия журналируются.
 */
public final class OwnerPanel {
    private final UnknownSMP plugin;
    private final AdminService admin;

    OwnerPanel(UnknownSMP plugin, AdminService admin) {
        this.plugin = plugin;
        this.admin = admin;
    }

    public void open(Player p) {
        new Menu(p, 5, "&4&lПанель владельца") {
            @Override
            protected void build() {
                fill(Items.filler());
                set(10, Items.of(Material.PLAYER_HEAD).name("&eИгроки").lore("&7Онлайн: &f" + Bukkit.getOnlinePlayers().size()).build(), c -> players(viewer, this));
                set(11, Items.of(Material.WRITABLE_BOOK).name("&bЖурналы").lore("&7Последние действия по категориям").build(), c -> logs(viewer, this, null));
                set(12, Items.of(Material.CLOCK).name("&6Откаты").lore("&7/usmp rollback <игрок|*> <время> [радиус]", "&7Инспектор: /usmp inspect").build(), c -> rollbackHelp(viewer, this));
                set(13, Items.of(Material.GOLD_BLOCK).name("&6Экономика").build(), c -> economy(viewer, this));
                set(14, Items.of(Material.NETHER_STAR).name("&dСобытия и измерения").build(), c -> events(viewer, this));
                set(15, Items.of(Material.COMPARATOR).name("&7Настройки").build(), c -> settings(viewer, this));
                set(16, Items.of(Material.VILLAGER_SPAWN_EGG).name("&aNPC").lore("&7Список, телепорт, удаление").build(), c -> npcs(viewer, this));
                set(19, Items.of(Material.BEACON).name("&eРанги").lore("&7Редактор требований и наград").build(), c -> plugin.progression().menus().editor(viewer, this));
                set(34, Items.of(Material.SPYGLASS).name("&cПроверки на читы").lore("&7Дела по детекторам и жалобам,", "&7улики, телепорт, анализ Жоры").build(), c -> ru.unknownsmp.zhora.CheatMenus.show(plugin, viewer, "open", this));
                set(20, Items.of(Material.SHIELD).name("&cБезопасность").lore("&7Подозрительные действия и блокировки").build(), c -> logs(viewer, this, "suspicious"));
                set(21, Items.of(Material.IRON_BARS).name("&cМодерация").lore("&7Жалобы и активные наказания").build(), c -> moderation(viewer, this));
                set(22, Items.of(Material.FIREWORK_ROCKET).name("&dТроллинг").lore("&7Безвредные эффекты и розыгрыши").build(), c -> trollTargets(viewer, this));
                set(23, Items.of(Material.OBSERVER).name("&aМониторинг").build(), c -> monitoring(viewer, this));
                set(24, Items.of(Material.CHEST).name("&9Резервные копии").build(), c -> backups(viewer, this));
                set(25, Items.of(Material.KNOWLEDGE_BOOK).name("&fМастер настройки").lore("&7Чек-лист первого запуска").build(), c -> SetupWizard.open(plugin, viewer));
                set(31, Items.of(admin.isVanished(viewer) ? Material.ENDER_EYE : Material.ENDER_PEARL).name("&7Невидимость: " + (admin.isVanished(viewer) ? "&aвкл" : "&cвыкл")).build(),
                        c -> { admin.toggleVanish(viewer); refresh(); });
                set(28, Items.of(Material.EMERALD).name("&aПродажи и покупки").lore("&7Кто что продал/купил, за сколько и где").build(), c -> sales(viewer, this));
                set(29, Items.of(Material.RED_BED).name("&cДома игроков").lore("&7Координаты всех домов, телепорт").build(), c -> homes(viewer, this));
                set(33, Items.of(Material.RABBIT_FOOT).name("&5Удача / невезение").lore("&7Включить функцию невезения или удачи игроку").build(), c -> luckTargets(viewer, this));
                set(30, Items.of(Material.COMPASS).name("&bГде игроки сейчас").lore("&7Координаты, здоровье, режим").build(), c -> whereNow(viewer, this));
                set(32, Items.of(Material.PAPER).name("&7Файлы журналов").lore("&7plugins/UnknownSMP/logs/:", "&7market-ДАТА.log — продажи", "&7homes-ДАТА.log — дома и телепорты",
                        "&7pvp-, admin-, suspicious-, auth-, chat-, economy-").build());
                closeButton(40);
            }
        }.open();
    }

    // ------------------------------------------------------------------ Продажи, дома, местоположение

    private void sales(Player p, Menu parent) {
        Tasks.then(plugin.logs().query(null, "market", System.currentTimeMillis() - 3 * 86_400_000L, 450), list -> new PagedMenu(p, "&8Продажи и покупки (3 дня)") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (var l : list) {
                    String what = switch (l.action()) {
                        case "sell" -> "&aПродал серверу";
                        case "shop_buy" -> "&eКупил в магазине";
                        case "ah_list" -> "&6Выставил на аукцион";
                        case "ah_buy" -> "&6Купил на аукционе";
                        case "ah_cancel" -> "&7Снял лот";
                        case "order_buy", "order_sell", "order_fill", "order_fill_direct", "order_buy_direct" -> "&bОрдер";
                        default -> "&7" + l.action();
                    };
                    out.add(new Entry(Items.of(Material.PAPER).name("&f" + l.name() + " &8— " + what)
                            .lore("&7" + Text.date(l.at()), "&f" + (l.details() == null ? "" : l.details().length() > 150 ? l.details().substring(0, 150) + "…" : l.details()),
                                    l.x() == null ? "" : "&8" + l.world() + " " + l.x() + ", " + l.y() + ", " + l.z(), l.x() == null ? "" : "&eНажмите — телепорт").build(), c -> {
                        if (l.x() == null) return;
                        var w = Bukkit.getWorld(l.world());
                        if (w != null) { viewer.closeInventory(); viewer.teleport(new Location(w, l.x() + 0.5, l.y(), l.z() + 0.5)); }
                    }));
                }
                return out;
            }
        }.parent(parent).open(), null);
    }

    private void homes(Player p, Menu parent) {
        Tasks.then(plugin.db().query(c -> {
            List<String[]> out = new ArrayList<>();
            try (var ps = c.prepareStatement("SELECT h.uuid, p.name, h.name, h.loc, h.created_at FROM homes h LEFT JOIN players p ON p.uuid = h.uuid ORDER BY p.name, h.name");
                 var rs = ps.executeQuery()) {
                while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), String.valueOf(rs.getLong(5))});
            }
            return out;
        }), list -> new PagedMenu(p, "&8Дома игроков (" + list.size() + ")") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (String[] h : list) {
                    Location l = Text.loc(h[3]);
                    out.add(new Entry(Items.head(java.util.UUID.fromString(h[0])).name("&f" + h[1] + " &7— дом &e" + h[2])
                            .lore("&7" + (l == null ? h[3] : Text.coords(l)), "&7Создан: &f" + Text.date(Long.parseLong(h[4])), "&eНажмите — телепорт").build(), c -> {
                        if (l == null) return;
                        viewer.closeInventory();
                        viewer.teleport(l);
                        plugin.logs().log(viewer, "admin", "tp_home", l, h[1] + "/" + h[2]);
                    }));
                }
                return out;
            }
        }.parent(parent).open(), null);
    }

    private void whereNow(Player p, Menu parent) {
        new PagedMenu(p, "&8Где игроки сейчас") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (Player o : Bukkit.getOnlinePlayers()) {
                    Location l = o.getLocation();
                    out.add(new Entry(Items.head(o).name("&f" + o.getName()).lore("&7" + Text.coords(l),
                            "&7Здоровье: &c" + Math.round(o.getHealth()) + " &7Голод: &f" + o.getFoodLevel(),
                            "&7Режим: &f" + o.getGameMode(), "&7Пинг: &f" + o.getPing() + " мс",
                            plugin.combat().isTagged(o) ? "&cВ бою" : "", "&eНажмите — телепорт").build(), c -> {
                        viewer.closeInventory();
                        viewer.teleport(o);
                    }));
                }
                return out;
            }
        }.parent(parent).open();
    }

    // ------------------------------------------------------------------ Игроки

    private void players(Player p, Menu parent) {
        new PagedMenu(p, "&8Игроки онлайн") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (Player o : Bukkit.getOnlinePlayers()) {
                    Profile pr = plugin.profiles().get(o);
                    out.add(new Entry(Items.head(o).name("&f" + o.getName()).lore(
                            "&7Роль: " + plugin.roles().roleOf(o).display(), "&7Баланс: &6" + (pr == null ? "?" : Text.money(pr.balance)),
                            "&7Мир: &f" + Text.coords(o.getLocation()), "&7Авторизован: " + (plugin.auth().isAuthed(o) ? "&aда" : "&cнет"),
                            "&7Пинг: &f" + o.getPing() + " мс", "", "&eНажмите для действий").build(), c -> playerActions(viewer, o, this)));
                }
                return out;
            }

            @Override
            protected void bottomBar() {
                set(47, Items.of(Material.OAK_SIGN).name("&fНайти игрока (оффлайн)").build(), c ->
                        ChatInput.ask(viewer, Msg.t("panel.ask-player", "&fНик игрока:"), n -> Tasks.then(plugin.profiles().findUuid(n), u -> {
                            if (u == null) { Msg.error(viewer, "player.unknown", "Игрок не найден."); return; }
                            plugin.moderation().historyMenu(viewer, u, n, this);
                        }, null)));
            }
        }.parent(parent).open();
    }

    private void playerActions(Player p, Player t, Menu parent) {
        new Menu(p, 4, "&8Игрок: " + t.getName()) {
            @Override
            protected void build() {
                fill(Items.filler());
                set(4, Items.head(t).name("&f" + t.getName()).build());
                set(10, Items.of(Material.ENDER_PEARL).name("&eТелепорт к игроку").build(), c -> { viewer.closeInventory(); viewer.teleport(t); log("tp", t); });
                set(11, Items.of(Material.CHEST).name("&eИнвентарь (просмотр)").build(), c -> admin.invsee(viewer, t, false));
                set(12, Items.of(Material.ENDER_CHEST).name("&eЭндер-сундук (просмотр)").build(), c -> admin.invsee(viewer, t, true));
                set(13, Items.of(Material.IRON_BARS).name("&cНаказать").build(), c -> plugin.moderation().punishMenu(viewer, t.getUniqueId(), t.getName(), this));
                set(14, Items.of(Material.BOOK).name("&bЖурнал игрока").build(), c -> { viewer.closeInventory(); viewer.performCommand("usmp lookup " + t.getName()); });
                set(15, Items.of(Material.GOLD_INGOT).name("&6Изменить баланс").lore("&7Ввод: give 1000 / take 500 / set 10000").build(), c ->
                        ChatInput.ask(viewer, Msg.t("panel.ask-money", "&fОперация и сумма (give|take|set сумма):"), s -> viewer.performCommand("usmp money " + s.split(" ")[0] + " " + t.getName() + " " + (s.contains(" ") ? s.split(" ")[1] : "0"))));
                set(16, Items.of(Material.NAME_TAG).name("&dСлужебная роль").lore("&7Текущая: " + plugin.roles().roleOf(t).display(), "&7Только владелец").build(), c -> {
                    if (!plugin.roles().isOwner(viewer)) return;
                    roles(viewer, t, this);
                });
                set(19, Items.of(Material.FIREWORK_ROCKET).name("&dТроллинг").build(), c -> troll(viewer, t, this));
                set(22, Items.of(Material.RABBIT_FOOT).name("&5Удача / невезение").lore("&7Сейчас: " + (plugin.luck() == null ? "выкл" : luckName(plugin.luck().level(t.getUniqueId())))).build(), c -> luckMenu(viewer, t, this));
                set(20, Items.of(Material.TRIPWIRE_HOOK).name("&cОтозвать сессии").lore("&7Потребуется пароль при входе").build(), c -> {
                    plugin.auth().revokeSessions(t.getName());
                    Msg.ok(viewer, "auth.revoked", "Сессии отозваны ({n}).", "n", 1);
                    log("revoke_sessions", t);
                });
                set(21, Items.of(Material.LEATHER_BOOTS).name("&eКик").build(), c ->
                        ChatInput.ask(viewer, Msg.t("mod.ask-reason", "&fПричина наказания:"), r -> plugin.moderation().punish(viewer, t.getUniqueId(), t.getName(), ModerationService.Type.KICK, 0, r, false)));
                if (parent != null) parent(parent);
                backButton(31);
            }

            private void log(String a, Player t2) { plugin.logs().log(viewer, "admin", a, t2.getLocation(), t2.getName()); }
        }.open();
    }

    private void roles(Player p, Player t, Menu parent) {
        new Menu(p, 3, "&8Роль: " + t.getName()) {
            @Override
            protected void build() {
                fill(Items.filler());
                int slot = 10;
                for (var r : plugin.roles().all().values()) {
                    boolean cur = plugin.roles().roleOf(t).id().equals(r.id());
                    set(slot++, Items.of(cur ? Material.LIME_DYE : Material.GRAY_DYE).name(r.display()).lore("&8" + r.id(), cur ? "&aТекущая" : "&eНазначить").build(), c ->
                            ConfirmMenu.open(viewer, "&8Назначить роль " + r.id() + "?", null, List.of("&7Игрок: &f" + t.getName()), () -> viewer.performCommand("usmp role " + t.getName() + " " + r.id())));
                }
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    // ------------------------------------------------------------------ Журналы

    private void logs(Player p, Menu parent, String category) {
        Tasks.then(plugin.logs().query(null, category, System.currentTimeMillis() - 7 * 86_400_000L, 300), list -> new PagedMenu(p, "&8Журнал" + (category == null ? "" : ": " + category)) {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (var l : list) {
                    out.add(new Entry(Items.of(icon(l.category())).name("&f" + l.name() + " &7" + l.category() + "/" + l.action())
                            .lore("&7" + Text.date(l.at()), "&7" + (l.details() == null ? "" : l.details().length() > 120 ? l.details().substring(0, 120) : l.details()),
                                    l.x() == null ? "" : "&8" + l.world() + " " + l.x() + ", " + l.y() + ", " + l.z(), l.x() == null ? "" : "&eНажмите — телепорт").build(), c -> {
                        if (l.x() == null) return;
                        var w = Bukkit.getWorld(l.world());
                        if (w != null) { viewer.closeInventory(); viewer.teleport(new Location(w, l.x() + 0.5, l.y(), l.z() + 0.5)); }
                    }));
                }
                return out;
            }

            @Override
            protected void bottomBar() {
                String[] cats = {"admin", "economy", "market", "suspicious", "combat", "clanbank"};
                int slot = 46;
                for (String cat : cats) {
                    if (slot == 49) slot++;
                    set(slot++, Items.of(icon(cat)).name("&e" + cat).glow(cat.equals(category)).build(), c -> logs(viewer, parent, cat));
                }
            }
        }.parent(parent).open(), null);
    }

    private static Material icon(String cat) {
        return switch (cat == null ? "" : cat) {
            case "admin" -> Material.COMMAND_BLOCK;
            case "economy", "clanbank" -> Material.GOLD_NUGGET;
            case "market" -> Material.EMERALD;
            case "suspicious" -> Material.REDSTONE;
            case "combat", "death" -> Material.IRON_SWORD;
            case "chat" -> Material.PAPER;
            case "container" -> Material.CHEST;
            default -> Material.BOOK;
        };
    }

    private void rollbackHelp(Player p, Menu parent) {
        new Menu(p, 3, "&8Откаты") {
            @Override
            protected void build() {
                fill(Items.filler());
                set(11, Items.of(Material.PLAYER_HEAD).name("&eОткат игрока").lore("&7Введите ник и период").build(), c ->
                        ChatInput.ask(viewer, Msg.t("rollback.ask", "&fНик и период (напр. Steve 2h [радиус]):"), s -> plugin.rollback().command(viewer, s.trim().split("\\s+"))));
                set(13, Items.of(Material.TNT).name("&eОткат области").lore("&7Все игроки в радиусе от вас").build(), c ->
                        ChatInput.ask(viewer, Msg.t("rollback.ask-area", "&fПериод и радиус (напр. 1h 30):"), s -> {
                            String[] a = s.trim().split("\\s+");
                            if (a.length < 2) return;
                            plugin.rollback().command(viewer, new String[]{"*", a[0], a[1]});
                        }));
                set(15, Items.of(Material.SPYGLASS).name("&eИнспектор блоков").lore("&7Вкл/выкл").build(), c -> { viewer.closeInventory(); viewer.performCommand("usmp inspect"); });
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    // ------------------------------------------------------------------ Экономика

    private void economy(Player p, Menu parent) {
        Tasks.then(plugin.db().query(c -> new long[]{
                nz(ru.unknownsmp.db.Database.longVal(c, "SELECT COALESCE(SUM(balance),0) FROM players")),
                nz(ru.unknownsmp.db.Database.longVal(c, "SELECT COALESCE(SUM(bank),0) FROM clans")),
                nz(ru.unknownsmp.db.Database.longVal(c, "SELECT COUNT(*) FROM auction_lots")),
                nz(ru.unknownsmp.db.Database.longVal(c, "SELECT COUNT(*) FROM orders")),
                nz(ru.unknownsmp.db.Database.longVal(c, "SELECT COUNT(*) FROM mail")),
                nz(ru.unknownsmp.db.Database.longVal(c, "SELECT COALESCE(SUM(delta),0) FROM money_log WHERE at > ? AND delta > 0", System.currentTimeMillis() - 86_400_000L)),
                nz(ru.unknownsmp.db.Database.longVal(c, "SELECT COALESCE(SUM(-delta),0) FROM money_log WHERE at > ? AND delta < 0", System.currentTimeMillis() - 86_400_000L))
        }), v -> new Menu(p, 3, "&8Экономика") {
            @Override
            protected void build() {
                fill(Items.filler());
                set(10, Items.of(Material.GOLD_BLOCK).name("&6Деньги игроков").lore("&f" + Text.money(v[0])).build());
                set(11, Items.of(Material.CYAN_BANNER).name("&dБанки кланов").lore("&f" + Text.money(v[1])).build());
                set(12, Items.of(Material.CHEST).name("&eРынок").lore("&7Лотов: &f" + v[2], "&7Ордеров: &f" + v[3], "&7Доставок в почте: &f" + v[4]).build(),
                        c -> plugin.auction().openBrowse(viewer, this));
                set(13, Items.of(Material.EMERALD).name("&aОборот за 24 ч").lore("&7Зачислено: &a" + Text.money(v[5]), "&7Списано: &c" + Text.money(v[6])).build());
                set(14, Items.of(Material.BOOK).name("&bЖурнал экономики").build(), c -> logs(viewer, this, "economy"));
                set(15, Items.of(Material.GOLD_INGOT).name("&6Топ богатства").build(), c -> plugin.leaderboards().board(viewer, "wealth", this));
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open(), null);
    }

    private static long nz(Long v) { return v == null ? 0 : v; }

    // ------------------------------------------------------------------ События

    private void events(Player p, Menu parent) {
        new Menu(p, 4, "&8События и измерения") {
            @Override
            protected void build() {
                fill(Items.filler());
                String[][] types = {{"meteor", "MAGMA_BLOCK", "Метеорит"}, {"cache", "CHEST", "Тайник стражей"}, {"anomaly", "END_PORTAL_FRAME", "Аномалия"},
                        {"merchant", "EMERALD", "Торговец"}, {"secrets", "LODESTONE", "Цепочка секретов"}};
                int slot = 10;
                for (String[] t : types) {
                    set(slot++, Items.of(Material.matchMaterial(t[1])).name("&dЗапустить: " + t[2]).lore("&7Через 0 мин. (без предупреждения)").build(), c -> {
                        viewer.closeInventory();
                        viewer.performCommand("usmp event start " + t[0]);
                    });
                }
                var a = plugin.events().active();
                set(16, Items.of(Material.BARRIER).name("&cОстановить событие").lore(a == null ? "&7Нет активного" : "&7" + a.title()).build(), c -> {
                    viewer.performCommand("usmp event stop");
                    refresh();
                });
                boolean n = plugin.dimensions().netherOpen(), e = plugin.dimensions().endOpen();
                set(20, Items.of(Material.NETHERRACK).name("&4Незер: " + (n ? "&aоткрыт" : "&cзакрыт")).lore("&eНажмите, чтобы переключить").build(), c ->
                        ConfirmMenu.open(viewer, "&8Переключить Незер?", null, List.of("&7Сейчас: " + (n ? "открыт" : "закрыт")), () -> viewer.performCommand("usmp nether " + (n ? "close" : "open"))));
                set(22, Items.of(Material.END_STONE).name("&5Энд: " + (e ? "&aоткрыт" : "&cзакрыт")).lore("&eНажмите, чтобы переключить").build(), c ->
                        ConfirmMenu.open(viewer, "&8Переключить Энд?", null, List.of("&7Сейчас: " + (e ? "открыт" : "закрыт")), () -> viewer.performCommand("usmp end " + (e ? "close" : "open"))));
                set(24, Items.of(Material.CRYING_OBSIDIAN).name("&dМеню измерений").build(), c -> plugin.dimensions().open(viewer, this));
                if (parent != null) parent(parent);
                backButton(31);
            }
        }.open();
    }

    // ------------------------------------------------------------------ Настройки

    private void settings(Player p, Menu parent) {
        new Menu(p, 3, "&8Настройки сервера") {
            @Override
            protected void build() {
                fill(Items.filler());
                set(10, Items.of(Material.REPEATER).name("&eПерезагрузить настройки").lore("&7Безопасная перезагрузка (без перезапуска)").build(), c -> viewer.performCommand("usmp reload"));
                boolean m = plugin.maintenance().isOn();
                set(11, Items.of(m ? Material.REDSTONE_BLOCK : Material.EMERALD_BLOCK).name("&eОбслуживание: " + (m ? "&cвкл" : "&aвыкл")).lore("&7Вкл: блок входа и торговли,", "&7отключение игроков через 60 с").build(), c -> {
                    if (m) plugin.maintenance().disable(viewer);
                    else ChatInput.ask(viewer, Msg.t("maintenance.ask", "&fПричина работ:"), r -> plugin.maintenance().enable(viewer, r, 60));
                    refresh();
                });
                set(12, Items.of(Material.RED_BED).name("&eУстановить спавн SMP здесь").build(), c -> viewer.performCommand("usmp setspawn"));
                set(13, Items.of(Material.PURPUR_BLOCK).name("&eУстановить точку лобби здесь").build(), c -> viewer.performCommand("usmp setlobby"));
                set(14, Items.of(Material.MAP).name("&eПрименить границу мира").lore("&7Из config.yml → border").build(), c -> { plugin.world().applyBorder(); Msg.ok(viewer, "panel.border", "Граница применена."); });
                set(16, Items.of(Material.PAPER).name("&7Где настройки").lore("&7plugins/UnknownSMP/*.yml", "&7Изменения применяются /usmp reload", "&7database.yml — после перезапуска").build());
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    // ------------------------------------------------------------------ NPC

    private void npcs(Player p, Menu parent) {
        new PagedMenu(p, "&8NPC") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (var n : plugin.npcs().all().values()) {
                    out.add(new Entry(Items.of(n.type().equals("MANNEQUIN") ? Material.ARMOR_STAND : Material.VILLAGER_SPAWN_EGG).name(n.name())
                            .lore("&8" + n.id(), "&7Тип: &f" + n.type() + " &7Роль: &f" + n.role(), "&7Диалог: &f" + n.dialogue(), "&7Действие: &f" + n.action(),
                                    "", "&eЛКМ — телепорт", "&eПКМ — переместить сюда", "&cShift+ПКМ — удалить").build(), c -> {
                        if (c.shift() && c.right()) {
                            ConfirmMenu.open(viewer, "&8Удалить NPC " + n.id() + "?", null, List.of(), () -> viewer.performCommand("usmp npc remove " + n.id()));
                        } else if (c.right()) {
                            viewer.performCommand("usmp npc move " + n.id());
                        } else {
                            var l = plugin.npcs().location(n);
                            if (l != null) { viewer.closeInventory(); viewer.teleport(l); }
                        }
                    }));
                }
                return out;
            }

            @Override
            protected void bottomBar() {
                set(47, Items.of(Material.EMERALD).name("&aСоздать NPC здесь").lore("&7Ввод: id ТИП [скин/профессия]").build(), c ->
                        ChatInput.ask(viewer, Msg.t("npc.ask-create", "&fid и тип (VILLAGER|MANNEQUIN|TRADER) [скин/профессия]:"), s -> viewer.performCommand("usmp npc create " + s)));
            }
        }.parent(parent).open();
    }

    // ------------------------------------------------------------------ Модерация

    private void moderation(Player p, Menu parent) {
        new Menu(p, 3, "&8Модерация") {
            @Override
            protected void build() {
                fill(Items.filler());
                set(11, Items.of(Material.WRITABLE_BOOK).name("&eЖалобы").build(), c -> plugin.reports().list(viewer, this, "OPEN"));
                set(13, Items.of(Material.IRON_BARS).name("&cАктивные наказания").build(), c -> active(viewer, this));
                set(15, Items.of(Material.REDSTONE).name("&6Подозрительные действия").build(), c -> logs(viewer, this, "suspicious"));
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    private void active(Player p, Menu parent) {
        Tasks.then(plugin.moderation().activeBans(), list -> new PagedMenu(p, "&8Активные наказания") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (var pn : list) {
                    if (pn.expiresAt() > 0 && pn.expiresAt() < System.currentTimeMillis()) continue;
                    out.add(new Entry(Items.head(pn.target()).name("&f" + pn.targetName() + " &c" + pn.type()).lore("&7" + pn.reason(), "&7Выдал: " + pn.author(),
                            "&7До: " + (pn.expiresAt() <= 0 ? "навсегда" : Text.date(pn.expiresAt())), "&eНажмите — история").build(),
                            c -> plugin.moderation().historyMenu(viewer, pn.target(), pn.targetName(), this)));
                }
                return out;
            }
        }.parent(parent).open(), null);
    }

    // ------------------------------------------------------------------ Мониторинг и копии

    private void monitoring(Player p, Menu parent) {
        new Menu(p, 3, "&8Мониторинг") {
            @Override
            protected void build() {
                fill(Items.filler());
                set(11, Items.of(Material.OBSERVER).name("&aСостояние").lore(plugin.monitor().lines()).build(), c -> refresh());
                List<String> al = new ArrayList<>();
                for (var a : plugin.monitor().alerts()) { al.add("&8" + Text.date(a.at()) + " &f" + a.text()); if (al.size() >= 15) break; }
                if (al.isEmpty()) al.add("&7Предупреждений нет");
                set(13, Items.of(Material.BELL).name("&cПредупреждения").lore(al).build());
                List<String> mods = new ArrayList<>();
                for (var e : plugin.modules().status().values()) mods.add((e.state() == ru.unknownsmp.core.ModuleManager.State.ACTIVE ? "&a✔ " : "&c✘ ") + "&7" + e.module().title());
                set(15, Items.of(Material.COMPARATOR).name("&eМодули").lore(mods).build());
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    private void backups(Player p, Menu parent) {
        new PagedMenu(p, "&8Резервные копии") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (File f : plugin.backups().list()) {
                    boolean failed = new File(f, "FAILED.txt").exists();
                    long size = 0;
                    File[] fs = f.listFiles();
                    if (fs != null) for (File x : fs) size += x.length();
                    out.add(new Entry(Items.of(failed ? Material.RED_SHULKER_BOX : Material.LIME_SHULKER_BOX).name("&f" + f.getName())
                            .lore("&7Размер: &f" + size / 1048576 + " МБ", failed ? "&cОшибка создания" : "&aСоздана", "", "&eЛКМ — проверить целостность",
                                    "&cShift+ПКМ — восстановить (при перезапуске)").build(), c -> {
                        if (c.shift() && c.right()) {
                            if (!plugin.roles().isOwner(viewer)) return;
                            restoreParts(viewer, f.getName(), this);
                        } else {
                            viewer.performCommand("usmp backup verify " + f.getName());
                        }
                    }));
                }
                return out;
            }

            @Override
            protected void bottomBar() {
                set(47, Items.of(Material.EMERALD).name("&aСоздать копию сейчас").lore(plugin.backups().isRunning() ? "&eВыполняется..." : "&7БД + настройки + миры").build(), c -> {
                    viewer.performCommand("usmp backup now");
                    viewer.closeInventory();
                });
                if (plugin.backups().restorePending())
                    set(51, Items.of(Material.BARRIER).name("&cОтменить запланированное восстановление").build(), c -> { viewer.performCommand("usmp backup cancel"); refresh(); });
            }
        }.parent(parent).open();
    }

    private void restoreParts(Player p, String name, Menu parent) {
        new Menu(p, 3, "&4Что восстановить?") {
            @Override
            protected void build() {
                fill(Items.filler());
                set(10, Items.of(Material.BOOK).name("&eТолько база данных").build(), c -> go("db"));
                set(12, Items.of(Material.GRASS_BLOCK).name("&eТолько миры").build(), c -> go("worlds"));
                set(14, Items.of(Material.PAPER).name("&eТолько настройки").build(), c -> go("configs"));
                set(16, Items.of(Material.NETHER_STAR).name("&cВсё").build(), c -> go("db,worlds,configs"));
                if (parent != null) parent(parent);
                backButton(22);
            }

            private void go(String parts) {
                ConfirmMenu.open(viewer, "&4Запланировать восстановление?", null, List.of("&7Копия: &f" + name, "&7Части: &f" + parts,
                        "&7Применится при следующем запуске.", "&7Текущие данные будут сохранены в pre-restore-*."), () -> viewer.performCommand("usmp backup restore " + name + " " + parts));
            }
        }.open();
    }

    // ------------------------------------------------------------------ Удача / невезение

    private static String luckName(int l) {
        return switch (l) {
            case -3 -> "&4Проклятие (-3)";
            case -2 -> "&cСильное невезение (-2)";
            case -1 -> "&6Невезение (-1)";
            case 0 -> "&fОбычная удача (0)";
            case 1 -> "&eУдача (+1)";
            case 2 -> "&aСильная удача (+2)";
            default -> "&2Невероятная удача (+3)";
        };
    }

    private void luckTargets(Player p, Menu parent) {
        if (plugin.luck() == null) { Msg.error(p, "luck.off", "Модуль удачи выключен."); return; }
        new PagedMenu(p, "&8Удача и невезение: игрок") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (Player o : Bukkit.getOnlinePlayers()) {
                    int l = plugin.luck().level(o.getUniqueId());
                    out.add(new Entry(Items.head(o).name("&f" + o.getName()).lore("&7Сейчас: " + luckName(l)).build(), c -> luckMenu(viewer, o, this)));
                }
                return out;
            }
        }.parent(parent).open();
    }

    public void luckMenu(Player p, Player t, Menu parent) {
        if (plugin.luck() == null || !plugin.roles().has(p, "usmp.admin.luck")) return;
        new Menu(p, 3, "&8Удача: " + t.getName()) {
            @Override
            protected void build() {
                fill(Items.filler());
                int cur = plugin.luck().level(t.getUniqueId());
                set(4, Items.head(t).name("&f" + t.getName()).lore("&7Сейчас: " + luckName(cur)).build());
                for (int l = -3; l <= 3; l++) {
                    final int lvl = l;
                    Material m = l < 0 ? Material.BLACK_DYE : l == 0 ? Material.WHITE_DYE : Material.YELLOW_DYE;
                    set(10 + (l + 3), Items.of(m).name((cur == l ? "&l▶ " : "") + luckName(l))
                            .lore(l < 0 ? "&7Руда иногда ломается впустую, реже редкие находки" : l > 0 ? "&7Чаще редкие находки" : "&7Без изменений",
                                    "&8Без смертельных последствий").build(), c -> {
                        plugin.luck().set(t.getUniqueId(), lvl);
                        plugin.logs().log(viewer, "luck", "panel_set", null, t.getName() + " = " + lvl);
                        Msg.ok(viewer, "luck.set", "Уровень удачи {p}: {l}", "p", t.getName(), "l", lvl);
                        refresh();
                    });
                }
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    // ------------------------------------------------------------------ Троллинг (безвредно)

    private void trollTargets(Player p, Menu parent) {
        new PagedMenu(p, "&8Троллинг: выберите игрока") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (!plugin.auth().isAuthed(o)) continue;
                    out.add(new Entry(Items.head(o).name("&f" + o.getName()).build(), c -> troll(viewer, o, this)));
                }
                return out;
            }
        }.parent(parent).open();
    }

    private TrollMenus trolls;

    public void troll(Player p, Player t, Menu parent) {
        if (trolls == null) trolls = new TrollMenus(plugin);
        trolls.open(p, t, parent);
    }
}
