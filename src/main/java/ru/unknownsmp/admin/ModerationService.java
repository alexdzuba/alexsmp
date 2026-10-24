package ru.unknownsmp.admin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import ru.unknownsmp.auth.AuthService;
import ru.unknownsmp.core.ChatInput;
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
import ru.unknownsmp.gui.PagedMenu;
import ru.unknownsmp.profile.Profile;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Модерация: кик, временный/постоянный бан, мут, предупреждения, ограничение торговли, история наказаний.
 * Каждое наказание хранится в БД с автором, причиной, временем создания/окончания и историей снятия.
 */
public final class ModerationService implements Module, Listener {
    public enum Type { BAN, MUTE, WARN, KICK, TRADE }

    public record Punishment(long id, UUID target, String targetName, Type type, String reason, String author, long createdAt,
                             long expiresAt, boolean active, String removedBy, long removedAt, String removeReason) {}

    private static final Set<String> OVERRIDE = Set.of("kick", "ban", "pardon", "unban", "tempban", "mute", "unmute", "warn", "history", "help");
    private final UnknownSMP plugin;

    public ModerationService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "moderation"; }
    @Override public String title() { return "Модерация"; }
    @Override public String[] depends() { return new String[]{"database", "profiles"}; }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerCommands();
    }

    // ------------------------------------------------------------------ Проверка входа

    @EventHandler(priority = EventPriority.LOWEST)
    public void preLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        String ip = AuthService.ip(e.getAddress());
        try {
            Object[] ban = plugin.db().queryBlocking(c -> {
                try (PreparedStatement ps = Database.ps(c, "SELECT id, reason, expires_at, author FROM punishments WHERE active=1 AND ptype='BAN' AND (target=? OR (ip IS NOT NULL AND ip=?)) ORDER BY id DESC",
                        e.getUniqueId().toString(), ip); ResultSet rs = ps.executeQuery()) {
                    long now = System.currentTimeMillis();
                    while (rs.next()) {
                        long exp = rs.getLong(3);
                        if (exp > 0 && exp < now) continue;
                        return new Object[]{rs.getString(2), exp, rs.getString(4)};
                    }
                }
                return null;
            });
            if (ban != null) {
                long exp = (Long) ban[1];
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, Text.c(Msg.t("mod.banned-screen",
                        "&c&lВы заблокированы на сервере\n\n&7Причина: &f{r}\n&7Срок: &f{t}\n&7Выдал: &f{a}\n\n&8Обжалование — через администрацию",
                        "r", ban[0], "t", exp <= 0 ? "навсегда" : "до " + Text.date(exp) + " (" + Text.duration(exp - System.currentTimeMillis()) + ")", "a", ban[2])));
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Ошибка проверки банов для " + e.getName() + ": " + ex.getMessage());
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Text.c("&cПроверка доступа временно недоступна. Попробуйте позже."));
        }
    }

    /** Направляем ванильные /kick, /ban, /help и т.п. на собственные команды плагина. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void override(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        String cmd = msg.substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (OVERRIDE.contains(cmd)) {
            String target = cmd.equals("pardon") ? "unban" : cmd;
            e.setMessage("/unknownsmp:" + target + msg.substring(1 + cmd.length()));
        }
    }

    // ------------------------------------------------------------------ Наказания

    public CompletableFuture<Long> punish(CommandSender actor, UUID target, String targetName, Type type, long durationMs, String reason, boolean ipBan) {
        long now = System.currentTimeMillis();
        long exp = durationMs <= 0 ? 0 : now + durationMs;
        String author = actor instanceof Player p ? p.getName() : "Консоль";
        Player online = Bukkit.getPlayer(target);
        String ip = ipBan && online != null && online.getAddress() != null ? AuthService.ip(online.getAddress().getAddress()) : null;
        boolean active = type != Type.KICK && type != Type.WARN;
        return plugin.db().tx(c -> {
            if (type == Type.BAN || type == Type.MUTE || type == Type.TRADE) {
                Database.update(c, "UPDATE punishments SET active=0, removed_by=?, removed_at=?, remove_reason=? WHERE target=? AND ptype=? AND active=1",
                        author, now, "заменено новым наказанием", target.toString(), type.name());
            }
            return Database.insert(c, "INSERT INTO punishments(target, target_name, ptype, reason, author, created_at, expires_at, active, ip) VALUES (?,?,?,?,?,?,?,?,?)",
                    target.toString(), targetName, type.name(), reason, author, now, exp, active ? 1 : 0, ip);
        }).thenApply(id -> {
            Tasks.sync(() -> apply(actor, target, targetName, type, exp, reason, id));
            return id;
        });
    }

    private void apply(CommandSender actor, UUID target, String name, Type type, long exp, String reason, long id) {
        Player p = Bukkit.getPlayer(target);
        String time = exp <= 0 ? "навсегда" : Text.duration(exp - System.currentTimeMillis());
        plugin.logs().log(actor instanceof Player ap ? ap : null, "admin", "punish_" + type.name().toLowerCase(), null,
                name + " #" + id + " " + time + " : " + reason);
        switch (type) {
            case BAN -> {
                if (p != null) p.kick(Text.c(Msg.t("mod.banned-kick", "&cВы заблокированы.\n&7Причина: &f{r}\n&7Срок: &f{t}", "r", reason, "t", time)));
                Msg.broadcast("mod.ban-broadcast", "&c{p} заблокирован ({t}). Причина: {r}", "p", name, "t", time, "r", reason);
            }
            case KICK -> { if (p != null) p.kick(Text.c(Msg.t("mod.kicked", "&cВы были отключены.\n&7Причина: &f{r}", "r", reason))); }
            case MUTE -> {
                Profile pr = plugin.profiles().get(target);
                if (pr != null) { pr.muteUntil = exp <= 0 ? -1 : exp; pr.muteReason = reason; }
                if (p != null) Msg.error(p, "mod.muted", "Вам запрещено писать в чат ({t}). Причина: {r}", "t", time, "r", reason);
            }
            case TRADE -> {
                Profile pr = plugin.profiles().get(target);
                if (pr != null) pr.tradeBlockUntil = exp <= 0 ? -1 : exp;
                if (p != null) Msg.error(p, "mod.trade", "Вам временно ограничены торговые операции ({t}). Причина: {r}", "t", time, "r", reason);
            }
            case WARN -> {
                if (p != null) {
                    Msg.title(p, "&c&lПРЕДУПРЕЖДЕНИЕ", "&f" + reason, 5, 80, 15);
                    Msg.error(p, "mod.warned", "Вы получили предупреждение: {r}", "r", reason);
                }
                escalate(actor, target, name);
            }
        }
        Msg.ok(actor, "mod.done", "Наказание #{id} ({t}) применено к {p}.", "id", id, "t", type.name(), "p", name);
    }

    /** Эскалация по числу предупреждений (настраивается в permissions.yml → moderation.warn-escalation). */
    private void escalate(CommandSender actor, UUID target, String name) {
        var sec = plugin.configs().get("permissions.yml").getConfigurationSection("moderation.warn-escalation");
        if (sec == null) return;
        Tasks.then(plugin.db().query(c -> Database.longVal(c, "SELECT COUNT(*) FROM punishments WHERE target=? AND ptype='WARN' AND removed_at IS NULL", target.toString())), n -> {
            if (n == null) return;
            String rule = sec.getString(String.valueOf(n));
            if (rule == null) return;
            String[] r = rule.split(" ");
            try {
                Type t = Type.valueOf(r[0].toUpperCase(Locale.ROOT));
                long d = r.length > 1 ? Text.parseDuration(r[1]) : 0;
                punish(actor, target, name, t, d, "Предупреждений: " + n, false);
            } catch (Exception e) {
                plugin.getLogger().warning("Неверное правило эскалации: " + rule);
            }
        }, null);
    }

    public CompletableFuture<Integer> revoke(CommandSender actor, UUID target, Type type, String reason) {
        String author = actor instanceof Player p ? p.getName() : "Консоль";
        long now = System.currentTimeMillis();
        return plugin.db().tx(c -> Database.update(c, "UPDATE punishments SET active=0, removed_by=?, removed_at=?, remove_reason=? WHERE target=? AND ptype=? AND active=1",
                author, now, reason, target.toString(), type.name())).thenApply(n -> {
            Tasks.sync(() -> {
                Profile pr = plugin.profiles().get(target);
                if (pr != null && type == Type.MUTE) { pr.muteUntil = 0; pr.muteReason = null; }
                if (pr != null && type == Type.TRADE) pr.tradeBlockUntil = 0;
                plugin.logs().log(actor instanceof Player ap ? ap : null, "admin", "revoke_" + type.name().toLowerCase(), null, target + " : " + reason);
            });
            return n;
        });
    }

    public CompletableFuture<Integer> revokeById(CommandSender actor, long id, String reason) {
        String author = actor instanceof Player p ? p.getName() : "Консоль";
        return plugin.db().tx(c -> {
            String target = Database.strVal(c, "SELECT target FROM punishments WHERE id=?", id);
            String type = Database.strVal(c, "SELECT ptype FROM punishments WHERE id=?", id);
            int n = Database.update(c, "UPDATE punishments SET active=0, removed_by=?, removed_at=?, remove_reason=? WHERE id=?", author, System.currentTimeMillis(), reason, id);
            if (target != null && type != null) {
                Tasks.sync(() -> {
                    Profile pr = plugin.profiles().get(UUID.fromString(target));
                    if (pr != null && type.equals("MUTE")) { pr.muteUntil = 0; pr.muteReason = null; }
                    if (pr != null && type.equals("TRADE")) pr.tradeBlockUntil = 0;
                });
            }
            return n;
        });
    }

    public CompletableFuture<List<Punishment>> history(UUID target) {
        return plugin.db().query(c -> {
            List<Punishment> out = new ArrayList<>();
            try (PreparedStatement ps = Database.ps(c, "SELECT * FROM punishments WHERE target=? ORDER BY id DESC LIMIT 200", target.toString()); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
            return out;
        });
    }

    public CompletableFuture<List<Punishment>> activeBans() {
        return plugin.db().query(c -> {
            List<Punishment> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM punishments WHERE active=1 ORDER BY id DESC LIMIT 500"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
            return out;
        });
    }

    private static Punishment read(ResultSet rs) throws java.sql.SQLException {
        Type t;
        try { t = Type.valueOf(rs.getString("ptype")); } catch (Exception e) { t = Type.WARN; }
        return new Punishment(rs.getLong("id"), UUID.fromString(rs.getString("target")), rs.getString("target_name"), t, rs.getString("reason"),
                rs.getString("author"), rs.getLong("created_at"), rs.getLong("expires_at"), rs.getInt("active") == 1, rs.getString("removed_by"),
                rs.getLong("removed_at"), rs.getString("remove_reason"));
    }

    // ------------------------------------------------------------------ Команды

    private void withTarget(CommandSender s, String name, java.util.function.BiConsumer<UUID, String> fn) {
        Tasks.then(plugin.profiles().findUuid(name), u -> {
            if (u == null) { Msg.error(s, "player.unknown", "Игрок не найден."); return; }
            if (!plugin.roles().outranks(s, u)) { Msg.error(s, "mod.outranked", "Нельзя наказать игрока с равной или более высокой ролью."); return; }
            Tasks.then(plugin.profiles().findName(u), n -> fn.accept(u, n == null ? name : n), null);
        }, null);
    }

    private static String reason(String[] a, int from) {
        return a.length > from ? String.join(" ", Arrays.copyOfRange(a, from, a.length)) : "Нарушение правил";
    }

    private void registerCommands() {
        var c = plugin.commands();
        c.add("kick").perm("usmp.mod.kick").module("moderation").cat("Модерация").usage("/kick <игрок> [причина]").desc("Отключить игрока")
                .exec((s, a) -> {
                    if (a.length < 1) { Msg.error(s, "cmd.usage", "Использование: /kick <игрок> [причина]"); return; }
                    withTarget(s, a[0], (u, n) -> punish(s, u, n, Type.KICK, 0, reason(a, 1), false));
                });
        c.add("ban").perm("usmp.mod.ban").module("moderation").cat("Модерация").usage("/ban <игрок> [причина]").desc("Постоянный бан")
                .exec((s, a) -> {
                    if (a.length < 1) { Msg.error(s, "cmd.usage", "Использование: /ban <игрок> [причина]"); return; }
                    if (!plugin.roles().check(s, "usmp.admin.permban")) return;
                    withTarget(s, a[0], (u, n) -> punish(s, u, n, Type.BAN, 0, reason(a, 1), false));
                });
        c.add("tempban").perm("usmp.mod.ban").module("moderation").cat("Модерация").usage("/tempban <игрок> <срок: 1d2h> [причина]").desc("Временный бан")
                .exec((s, a) -> {
                    long d = a.length >= 2 ? Text.parseDuration(a[1]) : -1;
                    if (a.length < 2 || d <= 0) { Msg.error(s, "cmd.usage", "Использование: /tempban <игрок> <срок, напр. 1d12h> [причина]"); return; }
                    withTarget(s, a[0], (u, n) -> punish(s, u, n, Type.BAN, d, reason(a, 2), false));
                });
        c.add("unban").perm("usmp.mod.ban").module("moderation").cat("Модерация").usage("/unban <игрок> [причина]").desc("Снять бан")
                .exec((s, a) -> {
                    if (a.length < 1) return;
                    Tasks.then(plugin.profiles().findUuid(a[0]), u -> {
                        if (u == null) { Msg.error(s, "player.unknown", "Игрок не найден."); return; }
                        Tasks.then(revoke(s, u, Type.BAN, reason(a, 1)), n -> Msg.ok(s, "mod.unbanned", "Снято банов: {n}", "n", n), null);
                    }, null);
                });
        c.add("mute").perm("usmp.mod.mute").module("moderation").cat("Модерация").usage("/mute <игрок> <срок|perm> [причина]").desc("Запретить чат")
                .exec((s, a) -> {
                    if (a.length < 2) { Msg.error(s, "cmd.usage", "Использование: /mute <игрок> <срок|perm> [причина]"); return; }
                    long d = a[1].equalsIgnoreCase("perm") ? 0 : Text.parseDuration(a[1]);
                    if (d < 0) { Msg.error(s, "mod.bad-time", "Неверный срок. Пример: 30m, 2h, 1d"); return; }
                    withTarget(s, a[0], (u, n) -> punish(s, u, n, Type.MUTE, d, reason(a, 2), false));
                });
        c.add("unmute").perm("usmp.mod.mute").module("moderation").cat("Модерация").usage("/unmute <игрок>").desc("Снять мут")
                .exec((s, a) -> {
                    if (a.length < 1) return;
                    Tasks.then(plugin.profiles().findUuid(a[0]), u -> {
                        if (u == null) { Msg.error(s, "player.unknown", "Игрок не найден."); return; }
                        Tasks.then(revoke(s, u, Type.MUTE, reason(a, 1)), n -> Msg.ok(s, "mod.unmuted", "Снято мутов: {n}", "n", n), null);
                    }, null);
                });
        c.add("warn").perm("usmp.mod.warn").module("moderation").cat("Модерация").usage("/warn <игрок> <причина>").desc("Предупреждение")
                .exec((s, a) -> {
                    if (a.length < 2) { Msg.error(s, "cmd.usage", "Использование: /warn <игрок> <причина>"); return; }
                    withTarget(s, a[0], (u, n) -> punish(s, u, n, Type.WARN, 0, reason(a, 1), false));
                });
        c.add("traderestrict").perm("usmp.mod.trade").module("moderation").cat("Модерация").usage("/traderestrict <игрок> <срок|off> [причина]")
                .desc("Временно ограничить торговлю").exec((s, a) -> {
                    if (a.length < 2) { Msg.error(s, "cmd.usage", "Использование: /traderestrict <игрок> <срок|off> [причина]"); return; }
                    if (a[1].equalsIgnoreCase("off")) {
                        Tasks.then(plugin.profiles().findUuid(a[0]), u -> { if (u != null) revoke(s, u, Type.TRADE, "снято"); }, null);
                        Msg.ok(s, "mod.trade-off", "Ограничение торговли снято.");
                        return;
                    }
                    long d = Text.parseDuration(a[1]);
                    if (d <= 0) { Msg.error(s, "mod.bad-time", "Неверный срок."); return; }
                    withTarget(s, a[0], (u, n) -> punish(s, u, n, Type.TRADE, d, reason(a, 2), false));
                });
        c.add("history").perm("usmp.mod.history").module("moderation").cat("Модерация").usage("/history <игрок>").desc("История наказаний")
                .player().exec((s, a) -> {
                    if (a.length < 1) return;
                    Tasks.then(plugin.profiles().findUuid(a[0]), u -> {
                        if (u == null) { Msg.error(s, "player.unknown", "Игрок не найден."); return; }
                        historyMenu((Player) s, u, a[0], null);
                    }, null);
                });
        c.add("punish").perm("usmp.mod.kick").module("moderation").cat("Модерация").usage("/punish <игрок>").desc("Меню наказаний")
                .player().exec((s, a) -> {
                    if (a.length < 1) return;
                    withTarget(s, a[0], (u, n) -> punishMenu((Player) s, u, n, null));
                }).tab((s, a) -> Commands.players(a.length == 0 ? "" : a[0]));
    }

    // ------------------------------------------------------------------ GUI

    public void punishMenu(Player staff, UUID target, String name, Menu parent) {
        new Menu(staff, 4, "&8Наказание: " + name) {
            @Override
            protected void build() {
                fill(Items.filler());
                set(4, Items.head(target).name("&f" + name).build());
                addType(10, Material.LEATHER_BOOTS, "&eКик", Type.KICK, "usmp.mod.kick", new String[]{"0"});
                addType(11, Material.PAPER, "&6Предупреждение", Type.WARN, "usmp.mod.warn", new String[]{"0"});
                addType(12, Material.BOOK, "&eМут", Type.MUTE, "usmp.mod.mute", new String[]{"15m", "1h", "6h", "1d", "7d", "perm"});
                addType(13, Material.GOLD_NUGGET, "&6Ограничение торговли", Type.TRADE, "usmp.mod.trade", new String[]{"1h", "1d", "7d", "30d"});
                addType(14, Material.IRON_BARS, "&cВременный бан", Type.BAN, "usmp.mod.ban", new String[]{"1h", "1d", "3d", "7d", "30d"});
                addType(15, Material.BARRIER, "&4Постоянный бан", Type.BAN, "usmp.admin.permban", new String[]{"perm"});
                set(22, Items.of(Material.WRITABLE_BOOK).name("&bИстория наказаний").build(), c -> historyMenu(viewer, target, name, this));
                if (parent != null) parent(parent);
                backButton(31);
            }

            private void addType(int slot, Material m, String title, Type type, String perm, String[] durations) {
                if (!plugin.roles().has(viewer, perm)) return;
                set(slot, Items.of(m).name(title).lore("&7Нажмите, чтобы выбрать срок и причину").build(), c -> chooseDuration(viewer, target, name, type, durations, this));
            }
        }.open();
    }

    private void chooseDuration(Player staff, UUID target, String name, Type type, String[] durations, Menu parent) {
        if (durations.length == 1) { chooseReason(staff, target, name, type, durations[0].equals("perm") || durations[0].equals("0") ? 0 : Text.parseDuration(durations[0]), parent); return; }
        new Menu(staff, 3, "&8Срок: " + type.name()) {
            @Override
            protected void build() {
                fill(Items.filler());
                int slot = 10;
                for (String d : durations) {
                    long ms = d.equals("perm") ? 0 : Text.parseDuration(d);
                    set(slot++, Items.of(Material.CLOCK).name("&e" + (ms == 0 ? "Навсегда" : Text.duration(ms))).build(), c -> chooseReason(viewer, target, name, type, ms, this));
                }
                set(slot, Items.of(Material.OAK_SIGN).name("&fДругой срок").build(), c ->
                        ChatInput.ask(viewer, Msg.t("mod.ask-time", "&fСрок (напр. 2h30m):"), s -> {
                            long ms = Text.parseDuration(s);
                            if (ms <= 0) { Msg.error(viewer, "mod.bad-time", "Неверный срок."); return; }
                            chooseReason(viewer, target, name, type, ms, this);
                        }));
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    private void chooseReason(Player staff, UUID target, String name, Type type, long ms, Menu parent) {
        List<String> reasons = plugin.configs().get("permissions.yml").getStringList("moderation.reasons");
        if (reasons.isEmpty()) reasons = List.of("Читы", "Дюп / использование багов", "Оскорбления", "Спам / флуд", "Мошенничество", "Реклама");
        final List<String> rs = reasons;
        new Menu(staff, 3, "&8Причина") {
            @Override
            protected void build() {
                fill(Items.filler());
                int slot = 10;
                for (String r : rs) {
                    set(slot++, Items.of(Material.PAPER).name("&f" + r).build(), c -> confirm(r));
                    if (slot == 17) break;
                }
                set(17, Items.of(Material.OAK_SIGN).name("&fСвоя причина").build(), c ->
                        ChatInput.ask(viewer, Msg.t("mod.ask-reason", "&fПричина наказания:"), this::confirm));
                if (parent != null) parent(parent);
                backButton(22);
            }

            private void confirm(String r) {
                ConfirmMenu.open(viewer, "&8Подтвердить наказание", Items.head(target).name("&f" + name).build(),
                        List.of("&7Тип: &f" + type.name(), "&7Срок: &f" + (ms == 0 ? "навсегда" : Text.duration(ms)), "&7Причина: &f" + r),
                        () -> punish(viewer, target, name, type, ms, r, false));
            }
        }.open();
    }

    public void historyMenu(Player staff, UUID target, String name, Menu parent) {
        Tasks.then(history(target), list -> new PagedMenu(staff, "&8История: " + name) {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (Punishment p : list) {
                    boolean expired = p.expiresAt() > 0 && p.expiresAt() < System.currentTimeMillis();
                    boolean live = p.active() && !expired;
                    List<String> lore = new ArrayList<>(List.of("&7Причина: &f" + p.reason(), "&7Выдал: &f" + p.author(), "&7Дата: &f" + Text.date(p.createdAt()),
                            "&7Срок: &f" + (p.expiresAt() <= 0 ? (p.type() == Type.KICK || p.type() == Type.WARN ? "—" : "навсегда") : "до " + Text.date(p.expiresAt())),
                            live ? "&cАктивно" : "&8Неактивно"));
                    if (p.removedBy() != null) lore.add("&7Снято: &f" + p.removedBy() + " " + Text.date(p.removedAt()) + " (" + p.removeReason() + ")");
                    if (live) lore.add("&eНажмите, чтобы снять");
                    out.add(new Entry(Items.of(live ? Material.RED_CONCRETE : Material.GRAY_CONCRETE).name("&f#" + p.id() + " " + p.type().name()).lore(lore).build(), c -> {
                        if (!live) return;
                        ChatInput.ask(viewer, Msg.t("mod.ask-revoke", "&fПричина снятия:"), r ->
                                Tasks.then(revokeById(viewer, p.id(), r), n -> {
                                    Msg.ok(viewer, "mod.revoked", "Наказание #{id} снято.", "id", p.id());
                                    plugin.logs().log(viewer, "admin", "revoke", null, "#" + p.id() + " " + r);
                                }, null));
                    }));
                }
                return out;
            }
        }.parent(parent).open(), null);
    }
}
