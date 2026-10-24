package ru.unknownsmp.quests;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.unknownsmp.core.Commands;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.economy.EconomyService;
import ru.unknownsmp.gui.ConfirmMenu;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.gui.PagedMenu;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PvP-контракты: игрок назначает награду за голову другого игрока (деньги списываются сразу и хранятся в контракте).
 * Награду получает убийца цели (не заказчик и не соклановец цели). Неисполненный контракт через N дней возвращается заказчику.
 */
public final class BountyService implements Module {
    public record Bounty(long id, UUID target, String targetName, UUID placer, String placerName, long amount, long at) {}

    private final UnknownSMP plugin;

    public BountyService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "bounties"; }
    @Override public String title() { return "PvP-контракты"; }
    @Override public String[] depends() { return new String[]{"economy"}; }

    @Override
    public void enable() {
        Tasks.timer(20 * 600, 20 * 3600, this::expire);
        plugin.commands().add("bounty").player().perm("usmp.player.bounty").module("bounties").cat("PvP").cooldown(3000)
                .usage("/bounty [игрок сумма]").desc("Награда за голову игрока").exec((s, a) -> {
                    Player p = (Player) s;
                    if (a.length < 2) { open(p, null); return; }
                    place(p, a[0], Text.parseAmount(a[1]));
                }).tab((s, a) -> a.length == 1 ? Commands.players(a[0]) : List.of());
    }

    private long min() { return plugin.configs().get("pvp.yml").getLong("bounty.min", 1000); }

    public void place(Player p, String targetName, long amount) {
        if (amount < min()) { Msg.error(p, "bounty.min", "Минимальная награда: {m}.", "m", Text.money(min())); return; }
        if (!plugin.economy().tradeAllowed(p)) return;
        Tasks.then(plugin.profiles().findUuid(targetName), target -> {
            if (target == null) { Msg.error(p, "player.unknown", "Игрок не найден."); return; }
            if (target.equals(p.getUniqueId())) { Msg.error(p, "bounty.self", "Нельзя назначить награду за себя."); return; }
            final UUID u = p.getUniqueId();
            Tasks.then(plugin.db().tx(c -> {
                long bal = EconomyService.debit(c, u, amount, "bounty_place", targetName);
                String tn = Database.strVal(c, "SELECT name FROM players WHERE uuid=?", target.toString());
                Database.insert(c, "INSERT INTO bounties(target, target_name, placer, placer_name, amount, created_at, state) VALUES (?,?,?,?,?,?,'ACTIVE')",
                        target.toString(), tn == null ? targetName : tn, u.toString(), p.getName(), amount, System.currentTimeMillis());
                return bal;
            }), bal -> {
                plugin.economy().apply(u, bal);
                Msg.broadcast("bounty.placed", "&c☠ За голову &f{t}&c назначена награда &6{a}&c!", "t", targetName, "a", Text.money(amount));
                plugin.logs().log(p, "economy", "bounty_place", p.getLocation(), targetName + " " + amount);
            }, err -> Msg.error(p, err instanceof Database.TxAbort ab ? ab.key : "bounty.error", err instanceof Database.TxAbort ab ? ab.def : "Ошибка. Деньги не списаны."));
        }, null);
    }

    /** Заказчик серверных наград (деньги из «казны», при истечении никому не возвращаются). */
    public static final UUID SERVER = new UUID(0L, 0L);

    /** Награда от сервера: /usmp bounty <игрок> <сумма>. Можно назначить и за администратора. */
    public void placeServer(org.bukkit.command.CommandSender s, String targetName, long amount) {
        if (amount < 1) { Msg.error(s, "admin.amount", "Неверная сумма."); return; }
        Tasks.then(plugin.profiles().findUuid(targetName), target -> {
            if (target == null) { Msg.error(s, "player.unknown", "Игрок не найден."); return; }
            Tasks.then(plugin.db().tx(c -> {
                String tn = Database.strVal(c, "SELECT name FROM players WHERE uuid=?", target.toString());
                Database.insert(c, "INSERT INTO bounties(target, target_name, placer, placer_name, amount, created_at, state) VALUES (?,?,?,?,?,?,'ACTIVE')",
                        target.toString(), tn == null ? targetName : tn, SERVER.toString(), "Сервер", amount, System.currentTimeMillis());
                return tn == null ? targetName : tn;
            }), name -> {
                Msg.broadcast("bounty.placed-server", "&c☠ Сервер назначил награду &6{a}&c за голову &f{t}&c! Убейте его в PvP, чтобы забрать.", "t", name, "a", Text.money(amount));
                plugin.logs().log(s instanceof Player p ? p : null, "admin", "bounty_server", null, name + " " + amount);
            }, err -> Msg.error(s, "bounty.error", "Ошибка: {e}", "e", err.getMessage()));
        }, null);
    }

    public void onKill(Player killer, Player victim) {
        var kc = plugin.clans().clanOf(killer);
        var vc = plugin.clans().clanOf(victim);
        if (kc != null && kc == vc) return;
        final UUID k = killer.getUniqueId(), v = victim.getUniqueId();
        Tasks.then(plugin.db().tx(c -> {
            long total = 0;
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = Database.ps(c, "SELECT id, amount, placer FROM bounties WHERE target=? AND state='ACTIVE'", v.toString()); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getString(3).equals(k.toString())) continue; // заказчик не может получить свою же награду
                    ids.add(rs.getLong(1));
                    total += rs.getLong(2);
                }
            }
            if (ids.isEmpty()) return new long[]{0, -1};
            for (long id : ids) Database.update(c, "UPDATE bounties SET state='CLAIMED', claimed_by=? WHERE id=? AND state='ACTIVE'", k.toString(), id);
            long bal = EconomyService.credit(c, k, total, "bounty_claim", victim.getName());
            return new long[]{total, bal};
        }), r -> {
            if (r[0] <= 0) return;
            plugin.economy().apply(k, r[1]);
            Msg.broadcast("bounty.claimed", "&c☠ {k} получил награду &6{a}&c за голову {v}!", "k", killer.getName(), "a", Text.money(r[0]), "v", victim.getName());
            plugin.profiles().addStat(killer, "bounties_claimed", 1);
            plugin.logs().log(killer, "economy", "bounty_claim", killer.getLocation(), victim.getName() + " " + r[0]);
        }, null);
    }

    private void expire() {
        long days = plugin.configs().get("pvp.yml").getLong("bounty.expire-days", 7);
        long before = System.currentTimeMillis() - days * 86_400_000L;
        Tasks.then(plugin.db().tx(c -> {
            Map<UUID, Long> refunds = new LinkedHashMap<>();
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = Database.ps(c, "SELECT id, placer, amount FROM bounties WHERE state='ACTIVE' AND created_at < ?", before); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                    UUID placer = UUID.fromString(rs.getString(2));
                    if (!placer.equals(SERVER)) refunds.merge(placer, rs.getLong(3), Long::sum);
                }
            }
            for (long id : ids) Database.update(c, "UPDATE bounties SET state='EXPIRED' WHERE id=?", id);
            Map<UUID, Long> bals = new LinkedHashMap<>();
            for (var e : refunds.entrySet()) bals.put(e.getKey(), EconomyService.credit(c, e.getKey(), e.getValue(), "bounty_refund", null));
            return bals;
        }), bals -> plugin.economy().apply(bals), null);
    }

    public void open(Player p, Menu parent) {
        Tasks.then(plugin.db().query(c -> {
            List<Bounty> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, target, target_name, placer, placer_name, amount, created_at FROM bounties WHERE state='ACTIVE' ORDER BY amount DESC LIMIT 200");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Bounty(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), UUID.fromString(rs.getString(4)),
                        rs.getString(5), rs.getLong(6), rs.getLong(7)));
            }
            return out;
        }), list -> new PagedMenu(p, "&8PvP-контракты") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (Bounty b : list) {
                    boolean online = Bukkit.getPlayer(b.target()) != null;
                    out.add(new Entry(Items.head(b.target()).name("&c" + b.targetName()).lore("&7Награда: &6" + Text.money(b.amount()),
                            "&7Заказчик: &f" + b.placerName(), "&7С: &f" + Text.date(b.at()), online ? "&aЦель в сети" : "&8Цель не в сети").build(), null));
                }
                return out;
            }

            @Override
            protected void bottomBar() {
                set(47, Items.of(org.bukkit.Material.IRON_SWORD).name("&cНазначить награду").lore("&7/bounty <игрок> <сумма>", "&7Минимум: " + Text.money(min())).build(), c ->
                        ru.unknownsmp.core.ChatInput.ask(viewer, Msg.t("bounty.ask-target", "&fНик цели:"), t ->
                                ru.unknownsmp.core.ChatInput.ask(viewer, Msg.t("bounty.ask-amount", "&fСумма награды:"), a ->
                                        ConfirmMenu.open(viewer, "&8Назначить награду?", null, List.of("&7Цель: &f" + t, "&7Сумма: &6" + a), () -> place(viewer, t, Text.parseAmount(a))))));
            }
        }.parent(parent).open(), null);
    }
}
