package ru.unknownsmp.admin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.unknownsmp.core.ChatInput;
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
import java.util.List;
import java.util.UUID;

/** Жалобы: /report → выбор игрока и причины; к жалобе прикладываются журналы и контекст. */
public final class ReportService implements Module {
    public record Report(long id, UUID reporter, String reporterName, UUID target, String targetName, String reason, String context,
                         long at, String state, String handledBy) {}

    private final UnknownSMP plugin;

    public ReportService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "reports"; }
    @Override public String title() { return "Жалобы"; }
    @Override public String[] depends() { return new String[]{"logs"}; }

    @Override
    public void enable() {
        plugin.commands().add("report").player().perm("usmp.player.report").module("reports").cat("Помощь").cooldown(60_000)
                .usage("/report").desc("Пожаловаться на игрока").exec((s, a) -> choosePlayer((Player) s));
        plugin.commands().add("reports").player().perm("usmp.mod.reports").module("reports").cat("Модерация")
                .usage("/reports").desc("Просмотр жалоб").exec((s, a) -> list((Player) s, null, "OPEN"));
    }

    private void choosePlayer(Player p) {
        new PagedMenu(p, "&8На кого жалоба?") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (o.equals(viewer) || !plugin.auth().isAuthed(o)) continue;
                    if (plugin.admin() != null && plugin.admin().isVanished(o)) continue;
                    out.add(new Entry(Items.head(o).name("&f" + o.getName()).lore("&eВыбрать").build(), c -> chooseReason(viewer, o.getUniqueId(), o.getName())));
                }
                return out;
            }

            @Override
            protected void bottomBar() {
                set(47, Items.of(Material.OAK_SIGN).name("&fИгрок не в сети").lore("&7Ввести ник вручную").build(), c ->
                        ChatInput.ask(viewer, Msg.t("report.ask-name", "&fНик игрока:"), n -> Tasks.then(plugin.profiles().findUuid(n), u -> {
                            if (u == null) { Msg.error(viewer, "player.unknown", "Игрок не найден."); return; }
                            chooseReason(viewer, u, n);
                        }, null)));
            }
        }.open();
    }

    private void chooseReason(Player p, UUID target, String name) {
        List<String> reasons = plugin.configs().get("permissions.yml").getStringList("reports.reasons");
        if (reasons.isEmpty()) reasons = List.of("Читы", "Дюп / баг", "Оскорбления", "Спам", "Мошенничество при сделке", "Другое");
        final List<String> rs = reasons;
        new Menu(p, 3, "&8Причина жалобы") {
            @Override
            protected void build() {
                fill(Items.filler());
                int slot = 10;
                for (String r : rs) {
                    set(slot++, Items.of(Material.PAPER).name("&f" + r).build(), c -> {
                        if (r.equals("Другое")) ChatInput.ask(viewer, Msg.t("report.ask-reason", "&fОпишите нарушение:"), t -> submit(viewer, target, name, "Другое: " + t));
                        else submit(viewer, target, name, r);
                    });
                    if (slot == 17) break;
                }
                closeButton(22);
            }
        }.open();
    }

    private void submit(Player p, UUID target, String name, String reason) {
        p.closeInventory();
        long since = System.currentTimeMillis() - 30 * 60_000L;
        Tasks.then(plugin.logs().query(target.toString(), null, since, 25), logs -> {
            StringBuilder ctx = new StringBuilder();
            Player t = Bukkit.getPlayer(target);
            ctx.append("Позиция жалующегося: ").append(Text.coords(p.getLocation())).append('\n');
            if (t != null) ctx.append("Позиция цели: ").append(Text.coords(t.getLocation())).append('\n');
            for (var l : logs) ctx.append(Text.date(l.at())).append(' ').append(l.category()).append('/').append(l.action()).append(' ')
                    .append(l.details() == null ? "" : l.details().length() > 80 ? l.details().substring(0, 80) : l.details()).append('\n');
            final String context = ctx.toString();
            final UUID r = p.getUniqueId();
            Tasks.then(plugin.db().tx(c -> {
                Long open = Database.longVal(c, "SELECT COUNT(*) FROM reports WHERE reporter=? AND state='OPEN'", r.toString());
                if (open != null && open >= 5) throw new Database.TxAbort("report.limit", "У вас уже 5 открытых жалоб.");
                return Database.insert(c, "INSERT INTO reports(reporter, reporter_name, target, target_name, reason, context, created_at, state) VALUES (?,?,?,?,?,?,?,'OPEN')",
                        r.toString(), p.getName(), target.toString(), name, reason, context, System.currentTimeMillis());
            }), id -> {
                Msg.ok(p, "report.sent", "Жалоба #{id} отправлена. Спасибо!", "id", id);
                plugin.logs().log(p, "report", "create", p.getLocation(), "#" + id + " " + name + ": " + reason);
                for (Player st : Bukkit.getOnlinePlayers()) {
                    if (plugin.roles().has(st, "usmp.mod.reports"))
                        Msg.send(st, "report.staff", "&6[Жалоба #{id}] &f{r} → {t}: &7{why} &8(/reports)", "id", id, "r", p.getName(), "t", name, "why", reason);
                }
            }, err -> Msg.error(p, err instanceof Database.TxAbort ab ? ab.key : "report.error", err instanceof Database.TxAbort ab ? ab.def : "Не удалось отправить жалобу."));
        }, null);
    }

    public void list(Player staff, Menu parent, String state) {
        Tasks.then(plugin.db().query(c -> {
            List<Report> out = new ArrayList<>();
            try (PreparedStatement ps = Database.ps(c, "SELECT * FROM reports WHERE state=? ORDER BY id DESC LIMIT 200", state); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Report(rs.getLong("id"), UUID.fromString(rs.getString("reporter")), rs.getString("reporter_name"),
                        UUID.fromString(rs.getString("target")), rs.getString("target_name"), rs.getString("reason"), rs.getString("context"),
                        rs.getLong("created_at"), rs.getString("state"), rs.getString("handled_by")));
            }
            return out;
        }), list -> new PagedMenu(staff, state.equals("OPEN") ? "&8Открытые жалобы" : "&8Закрытые жалобы") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (Report r : list) {
                    List<String> lore = new ArrayList<>(List.of("&7От: &f" + r.reporterName(), "&7Причина: &f" + r.reason(), "&7Дата: &f" + Text.date(r.at()), "", "&7Контекст:"));
                    String[] ctx = r.context() == null ? new String[0] : r.context().split("\n");
                    for (int i = 0; i < Math.min(12, ctx.length); i++) lore.add("&8" + ctx[i]);
                    if (r.handledBy() != null) lore.add("&7Рассмотрел: &f" + r.handledBy());
                    if (state.equals("OPEN")) lore.addAll(List.of("", "&eЛКМ &7— наказать", "&aПКМ &7— закрыть (обоснована)", "&cShift+ПКМ &7— отклонить", "&bShift+ЛКМ &7— телепорт к цели"));
                    out.add(new Entry(Items.head(r.target()).name("&c#" + r.id() + " " + r.targetName()).lore(lore).build(), c -> {
                        if (!state.equals("OPEN")) return;
                        if (c.shift() && c.left()) {
                            Player t = Bukkit.getPlayer(r.target());
                            if (t != null) { viewer.closeInventory(); viewer.teleport(t); }
                            else Msg.error(viewer, "player.offline", "Игрок не в сети.");
                        } else if (c.left()) {
                            plugin.moderation().punishMenu(viewer, r.target(), r.targetName(), this);
                        } else {
                            close(viewer, r.id(), c.shift() ? "REJECTED" : "RESOLVED");
                            list(viewer, parent, state);
                        }
                    }));
                }
                return out;
            }

            @Override
            protected void bottomBar() {
                set(47, Items.of(Material.BOOK).name(state.equals("OPEN") ? "&7Показать закрытые" : "&eПоказать открытые").build(),
                        c -> list(viewer, parent, state.equals("OPEN") ? "RESOLVED" : "OPEN"));
            }
        }.parent(parent).open(), null);
    }

    private void close(Player staff, long id, String state) {
        plugin.db().tx(c -> Database.update(c, "UPDATE reports SET state=?, handled_by=?, handled_at=? WHERE id=?", state, staff.getName(), System.currentTimeMillis(), id));
        plugin.logs().log(staff, "admin", "report_" + state.toLowerCase(), null, "#" + id);
        Msg.ok(staff, "report.closed", "Жалоба #{id} закрыта.", "id", id);
    }
}
