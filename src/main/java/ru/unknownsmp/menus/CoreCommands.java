package ru.unknownsmp.menus;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.unknownsmp.core.Commands;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.gui.PagedMenu;
import ru.unknownsmp.profile.Profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Главное меню, настройки игрока, профиль и динамическая справка /help. */
public final class CoreCommands implements Module {
    private final UnknownSMP plugin;

    public CoreCommands(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "menus"; }
    @Override public String title() { return "Меню и справка"; }
    @Override public String[] depends() { return new String[]{"profiles"}; }

    @Override
    public void enable() {
        var c = plugin.commands();
        c.add("menu").player().perm("usmp.player.menu").cat("Основное").usage("/menu").desc("Главное меню").exec((s, a) -> mainMenu((Player) s));
        c.add("settings").player().perm("usmp.player.menu").cat("Основное").usage("/settings").desc("Настройки игрока").exec((s, a) -> settings((Player) s, null));
        c.add("profile").player().perm("usmp.player.menu").cat("Статистика").usage("/profile [игрок]").desc("Профиль и статистика").exec((s, a) -> {
            Player p = (Player) s;
            Player t = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : p;
            if (t == null || !plugin.auth().isAuthed(t)) { Msg.error(p, "player.offline", "Игрок не в сети."); return; }
            plugin.progression().menus().profile(p, t, null);
        }).tab((s, a) -> Commands.players(a.length == 0 ? "" : a[0]));
        c.add("titles").player().perm("usmp.player.menu").cat("Статистика").usage("/titles").desc("Выбор титула").exec((s, a) -> plugin.progression().menus().titles((Player) s, null));
        c.add("achievements").player().perm("usmp.player.menu").cat("Статистика").usage("/achievements").desc("Достижения").exec((s, a) -> plugin.progression().menus().achievements((Player) s, null));
        c.add("ranks").player().perm("usmp.player.menu").cat("Статистика").usage("/ranks").desc("Ранги прогрессии").exec((s, a) -> plugin.progression().menus().ranks((Player) s, null));
        c.add("rules").perm("").cat("Помощь").usage("/rules").desc("Правила сервера").exec((s, a) -> rules(s));
        c.add("help").perm("").cat("Помощь").usage("/help [раздел]").desc("Список доступных команд").exec((s, a) -> help(s, a.length > 0 ? a[0] : null));
    }

    // ------------------------------------------------------------------ Главное меню

    public void mainMenu(Player p) {
        Profile pr = plugin.profiles().get(p);
        new Menu(p, 6, "&5&lALEX SMP") {
            @Override
            protected void build() {
                fill(Items.filler());
                set(4, Items.head(p).name("&f" + p.getName()).lore("&7Ранг: " + plugin.progression().rankName(pr == null ? null : pr.progRank),
                        "&7Баланс: &6" + Text.money(pr == null ? 0 : pr.balance), "&7Уровень: &b" + (pr == null ? 0 : pr.level), "", "&eПрофиль и статистика").build(),
                        c -> plugin.progression().menus().profile(viewer, viewer, this));
                boolean lobby = plugin.lobby().isLobby(viewer.getWorld());
                boolean duels = plugin.duels() != null && plugin.duels().isDuelWorld(viewer.getWorld());
                if (lobby) {
                    set(10, Items.of(Material.GRASS_BLOCK).name("&a&lИграть: SMP").lore("&7Выживание, кланы, экономика").build(), c -> { viewer.closeInventory(); plugin.lobby().enterSurvival(viewer); });
                    set(16, Items.of(Material.DIAMOND_SWORD).name("&c&lИграть: Дуэли").lore("&7Бои 1 на 1 с наборами", "&7Вещи SMP в безопасности").build(), c -> {
                        viewer.closeInventory();
                        if (plugin.modules().require(viewer, "duels")) plugin.duels().enter(viewer);
                    });
                } else if (duels) {
                    set(10, Items.of(Material.DIAMOND_SWORD).name("&cМеню дуэлей").build(), c -> plugin.duels().openMenu(viewer));
                    set(16, Items.of(Material.PURPUR_BLOCK).name("&dВ лобби").build(), c -> { viewer.closeInventory(); viewer.performCommand("lobby"); });
                } else {
                    set(10, Items.of(Material.RED_BED).name("&aДома").build(), c -> plugin.teleports().homesMenu(viewer, this));
                    set(16, Items.of(Material.PURPUR_BLOCK).name("&dВ лобби").lore("&7/lobby").build(), c -> { viewer.closeInventory(); viewer.performCommand("lobby"); });
                }
                set(19, Items.of(Material.EMERALD).name("&aМагазин").lore("&7/shop").build(), c -> plugin.shop().openShop(viewer, this));
                set(20, Items.of(Material.GOLD_INGOT).name("&6Аукцион").lore("&7/ah").build(), c -> plugin.auction().openBrowse(viewer, this));
                set(21, Items.of(Material.HOPPER).name("&eСкупка").lore("&7/sell").build(), c -> plugin.shop().openSell(viewer, this));
                set(22, Items.of(Material.WRITABLE_BOOK).name("&bОрдера").lore("&7/orders").build(), c -> plugin.orders().open(viewer, this));
                set(23, Items.of(Material.ENDER_CHEST).name("&dПочта").lore("&7/mail").build(), c -> plugin.mail().open(viewer, this));
                set(24, Items.of(Material.CYAN_BANNER).name("&dКлан").lore("&7/clan").build(), c -> plugin.clans().menus().open(viewer, this));
                set(25, Items.of(Material.MAP).name("&eЗадания").lore("&7/quests").build(), c -> plugin.quests().menus().open(viewer, this));
                set(28, Items.of(Material.NETHER_STAR).name("&5События").lore("&7/events").build(), c -> plugin.events().open(viewer, this));
                set(29, Items.of(Material.CRYING_OBSIDIAN).name("&4Измерения").lore("&7Незер, Энд, коллективное задание").build(), c -> plugin.dimensions().open(viewer, this));
                set(30, Items.of(Material.BEACON).name("&eРанги и репутация").build(), c -> plugin.progression().menus().ranks(viewer, this));
                set(31, Items.of(Material.GOLDEN_APPLE).name("&6Достижения").build(), c -> plugin.progression().menus().achievements(viewer, this));
                set(32, Items.of(Material.NAME_TAG).name("&dТитулы").build(), c -> plugin.progression().menus().titles(viewer, this));
                set(33, Items.of(Material.DIAMOND).name("&bТаблицы лидеров").lore("&7/top").build(), c -> plugin.leaderboards().open(viewer, this));
                set(34, Items.of(Material.SKELETON_SKULL).name("&cPvP-контракты").lore("&7/bounty").build(), c -> plugin.bounties().open(viewer, this));
                set(37, Items.of(Material.BLAZE_POWDER).name("&5Косметика").build(), c -> plugin.lobby().cosmetics().open(viewer, this));
                set(38, Items.of(Material.COMPARATOR).name("&7Настройки").lore("&7Уведомления, scoreboard, звуки, чат").build(), c -> settings(viewer, this));
                set(39, Items.of(Material.TRIPWIRE_HOOK).name("&7Безопасность аккаунта").lore("&7Смена пароля").build(), c -> new ru.unknownsmp.auth.PasswordMenu(viewer).parent(this).open());
                set(41, Items.of(Material.BOOK).name("&fПомощь").lore("&7Команды и правила").build(), c -> helpMenu(viewer, this));
                set(42, Items.of(Material.WRITABLE_BOOK).name("&cПожаловаться").lore("&7/report").build(), c -> viewer.performCommand("report"));
                if (plugin.roles().isOwner(viewer) || plugin.roles().has(viewer, "usmp.admin.panel"))
                    set(43, Items.of(Material.COMMAND_BLOCK).name("&4Панель владельца").build(), c -> plugin.admin().panel().open(viewer));
                else if (plugin.roles().isStaff(viewer))
                    set(43, Items.of(Material.IRON_BARS).name("&cЖалобы").build(), c -> plugin.reports().list(viewer, this, "OPEN"));
                closeButton(49);
            }
        }.open();
    }

    // ------------------------------------------------------------------ Настройки

    private static final Map<String, String[]> SETTINGS = new LinkedHashMap<>();

    static {
        SETTINGS.put("notifications", new String[]{"Уведомления о событиях", "BELL"});
        SETTINGS.put("scoreboard", new String[]{"Боковая панель (scoreboard)", "ITEM_FRAME"});
        SETTINGS.put("sounds", new String[]{"Звуки интерфейса", "NOTE_BLOCK"});
        SETTINGS.put("chat_global", new String[]{"Глобальный чат", "OAK_SIGN"});
        SETTINGS.put("chat_local", new String[]{"Локальный чат", "BIRCH_SIGN"});
        SETTINGS.put("chat_clan", new String[]{"Клановый чат", "CYAN_BANNER"});
    }

    public void settings(Player p, Menu parent) {
        Profile pr = plugin.profiles().get(p);
        if (pr == null) return;
        new Menu(p, 4, "&8Настройки") {
            @Override
            protected void build() {
                fill(Items.filler());
                int slot = 10;
                for (var e : SETTINGS.entrySet()) {
                    boolean on = pr.setting(e.getKey());
                    Material m = Material.matchMaterial(e.getValue()[1]);
                    set(slot, Items.of(m).name("&f" + e.getValue()[0]).build());
                    set(slot + 9, Items.of(on ? Material.LIME_DYE : Material.GRAY_DYE).name(on ? "&aВключено" : "&cВыключено").lore("&eНажмите, чтобы переключить").build(), c -> {
                        pr.setting(e.getKey(), !on);
                        refresh();
                    });
                    slot++;
                }
                String mode = pr.chatMode == null ? "global" : pr.chatMode;
                set(16, Items.of(Material.PAPER).name("&eРежим чата: &f" + ru.unknownsmp.chat.ChatService.modeName(mode)).lore("&7Нажмите, чтобы сменить",
                        "&7Префикс &f!&7 — сообщение в глобальный", "&7Префикс &f@&7 — в клановый").build(), c -> {
                    pr.chatMode = switch (mode) { case "global" -> "local"; case "local" -> "clan"; default -> "global"; };
                    pr.dirty = true;
                    refresh();
                });
                boolean tpa = !pr.has("block", "tpa");
                set(25, Items.of(tpa ? Material.ENDER_PEARL : Material.BARRIER).name("&fЗапросы /tpa: " + (tpa ? "&aпринимать" : "&cотклонять")).build(), c -> {
                    if (tpa) plugin.profiles().unlock(viewer, "block", "tpa");
                    else plugin.profiles().lock(viewer, "block", "tpa");
                    refresh();
                });
                if (plugin.roles().isStaff(viewer)) {
                    boolean al = pr.setting("staff_alerts");
                    set(26, Items.of(al ? Material.REDSTONE_TORCH : Material.LEVER).name("&cОповещения персонала: " + (al ? "&aвкл" : "&cвыкл")).build(), c -> {
                        pr.setting("staff_alerts", !al);
                        refresh();
                    });
                }
                if (parent != null) parent(parent);
                backButton(31);
            }
        }.open();
    }

    // ------------------------------------------------------------------ Справка

    private void help(CommandSender s, String cat) {
        Map<String, List<Commands.Cmd>> byCat = new LinkedHashMap<>();
        for (Commands.Cmd c : plugin.commands().all()) {
            if (c.hiddenCmd() || !plugin.roles().has(s, c.perm())) continue;
            if (c.module() != null && !plugin.modules().isActive(c.module())) continue;
            byCat.computeIfAbsent(c.category(), k -> new ArrayList<>()).add(c);
        }
        if (s instanceof Player p && cat == null) { helpMenu(p, null); return; }
        Msg.raw(s, "&5&lALEX SMP — команды");
        for (var e : byCat.entrySet()) {
            if (cat != null && !e.getKey().equalsIgnoreCase(cat)) continue;
            Msg.raw(s, "&d" + e.getKey() + ":");
            for (Commands.Cmd c : e.getValue()) Msg.raw(s, "  &e" + c.usage() + " &7— " + c.desc());
        }
    }

    public void helpMenu(Player p, Menu parent) {
        Map<String, List<Commands.Cmd>> byCat = new LinkedHashMap<>();
        for (Commands.Cmd c : plugin.commands().all()) {
            if (c.hiddenCmd() || !plugin.roles().has(p, c.perm())) continue;
            if (c.module() != null && !plugin.modules().isActive(c.module())) continue;
            byCat.computeIfAbsent(c.category(), k -> new ArrayList<>()).add(c);
        }
        new PagedMenu(p, "&8Помощь — команды") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (var e : byCat.entrySet()) {
                    List<String> lore = new ArrayList<>();
                    for (Commands.Cmd c : e.getValue()) lore.add("&e" + c.usage() + " &7— " + c.desc());
                    out.add(new Entry(Items.of(icon(e.getKey())).name("&d" + e.getKey()).lore(lore).build(), null));
                }
                out.add(new Entry(Items.of(Material.BOOK).name("&fПравила сервера").lore(rulesLines()).build(), null));
                return out;
            }
        }.parent(parent).open();
    }

    private static Material icon(String cat) {
        return switch (cat) {
            case "Аккаунт" -> Material.TRIPWIRE_HOOK;
            case "Перемещение" -> Material.ENDER_PEARL;
            case "Экономика" -> Material.GOLD_INGOT;
            case "Кланы" -> Material.CYAN_BANNER;
            case "PvP" -> Material.IRON_SWORD;
            case "Дуэли" -> Material.DIAMOND_SWORD;
            case "Задания" -> Material.MAP;
            case "События" -> Material.NETHER_STAR;
            case "Статистика" -> Material.BOOK;
            case "Чат" -> Material.PAPER;
            case "Модерация", "Администрирование" -> Material.COMMAND_BLOCK;
            default -> Material.COMPASS;
        };
    }

    private List<String> rulesLines() {
        List<String> r = plugin.configs().main().getStringList("rules");
        if (r.isEmpty()) r = List.of("PvP разрешено везде, кроме зоны спавна.", "Приватов нет: грабить и ломать можно.", "Выход из боя = потеря вещей, опыта и 10% денег.",
                "Читы, дюпы, использование багов запрещены.", "Не оскорбляйте игроков, не спамьте.", "Сделки — через рынок: так безопаснее.");
        List<String> out = new ArrayList<>();
        int i = 1;
        for (String s : r) out.add("&f" + (i++) + ". &7" + s);
        return out;
    }

    private void rules(CommandSender s) {
        Msg.raw(s, "&5&lПравила ALEX SMP");
        for (String l : rulesLines()) Msg.raw(s, l);
    }
}
