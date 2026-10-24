package ru.unknownsmp.economy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.unknownsmp.core.Commands;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.gui.ConfirmMenu;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.profile.Profile;

import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Экономика. Баланс хранится в БД; каждое изменение — атомарная SQL-операция с проверкой
 * достаточности средств в самом запросе и записью в money_log. Кэш обновляется только после фиксации.
 */
public final class EconomyService implements Module {
    private final UnknownSMP plugin;

    public EconomyService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "economy"; }
    @Override public String title() { return "Экономика"; }
    @Override public String[] depends() { return new String[]{"database", "profiles"}; }

    @Override
    public void enable() {
        registerCommands();
    }

    // ------------------------------------------------------------------ Операции внутри транзакции

    /** Списать сумму внутри транзакции. Бросает TxAbort при недостатке средств. Возвращает новый баланс. */
    public static long debit(Connection c, UUID u, long amount, String reason, String ref) throws Exception {
        if (amount < 0) throw new IllegalArgumentException("amount < 0");
        if (amount == 0) return balance(c, u);
        int n = Database.update(c, "UPDATE players SET balance = balance - ? WHERE uuid=? AND balance >= ?", amount, u.toString(), amount);
        if (n != 1) throw new Database.TxAbort("economy.insufficient", "Недостаточно средств.");
        long bal = balance(c, u);
        Database.update(c, "INSERT INTO money_log(at, uuid, delta, balance_after, reason, ref) VALUES (?,?,?,?,?,?)",
                System.currentTimeMillis(), u.toString(), -amount, bal, reason, ref);
        return bal;
    }

    /** Зачислить сумму внутри транзакции. Возвращает новый баланс. */
    public static long credit(Connection c, UUID u, long amount, String reason, String ref) throws Exception {
        if (amount < 0) throw new IllegalArgumentException("amount < 0");
        if (amount == 0) return balance(c, u);
        Long cur = Database.longVal(c, "SELECT balance FROM players WHERE uuid=?", u.toString());
        if (cur == null) throw new Database.TxAbort("economy.no-player", "Игрок не найден.");
        if (Long.MAX_VALUE - cur < amount) throw new Database.TxAbort("economy.overflow", "Превышен максимальный баланс.");
        Database.update(c, "UPDATE players SET balance = balance + ? WHERE uuid=?", amount, u.toString());
        long bal = cur + amount;
        Database.update(c, "INSERT INTO money_log(at, uuid, delta, balance_after, reason, ref) VALUES (?,?,?,?,?,?)",
                System.currentTimeMillis(), u.toString(), amount, bal, reason, ref);
        return bal;
    }

    public static long balance(Connection c, UUID u) throws Exception {
        Long v = Database.longVal(c, "SELECT balance FROM players WHERE uuid=?", u.toString());
        if (v == null) throw new Database.TxAbort("economy.no-player", "Игрок не найден.");
        return v;
    }

    /** Применить новые балансы к кэшу (вызывать после успешной фиксации). */
    public void apply(Map<UUID, Long> balances) {
        Tasks.sync(() -> {
            for (var e : balances.entrySet()) {
                Profile p = plugin.profiles().get(e.getKey());
                if (p != null) p.balance = e.getValue();
            }
        });
    }

    public void apply(UUID u, long bal) {
        apply(Map.of(u, bal));
    }

    // ------------------------------------------------------------------ Публичный API

    public long balance(Player p) {
        Profile pr = plugin.profiles().get(p);
        return pr == null ? 0 : pr.balance;
    }

    public CompletableFuture<Long> balanceOf(UUID u) {
        Profile pr = plugin.profiles().get(u);
        if (pr != null) return CompletableFuture.completedFuture(pr.balance);
        return plugin.db().query(c -> Database.longVal(c, "SELECT balance FROM players WHERE uuid=?", u.toString()));
    }

    public CompletableFuture<Long> deposit(UUID u, long amount, String reason, String ref) {
        CompletableFuture<Long> f = plugin.db().tx(c -> credit(c, u, amount, reason, ref));
        f.thenAccept(b -> apply(u, b));
        plugin.logs().log(u, null, "economy", "deposit", amount + " " + reason + (ref == null ? "" : " " + ref));
        return f;
    }

    public CompletableFuture<Long> withdraw(UUID u, long amount, String reason, String ref) {
        CompletableFuture<Long> f = plugin.db().tx(c -> debit(c, u, amount, reason, ref));
        f.thenAccept(b -> {
            apply(u, b);
            plugin.logs().log(u, null, "economy", "withdraw", amount + " " + reason + (ref == null ? "" : " " + ref));
        });
        return f;
    }

    public CompletableFuture<Long> set(UUID u, long amount, String reason) {
        CompletableFuture<Long> f = plugin.db().tx(c -> {
            long cur = balance(c, u);
            Database.update(c, "UPDATE players SET balance=? WHERE uuid=?", amount, u.toString());
            Database.update(c, "INSERT INTO money_log(at, uuid, delta, balance_after, reason, ref) VALUES (?,?,?,?,?,?)",
                    System.currentTimeMillis(), u.toString(), amount - cur, amount, reason, null);
            return amount;
        });
        f.thenAccept(b -> apply(u, b));
        return f;
    }

    /**
     * Перевод части баланса (процент, округление вниз) от жертвы получателю. Если получателя нет (null), деньги
     * списываются без зачисления. Возвращает фактически переданную сумму.
     */
    public CompletableFuture<Long> transferPercent(UUID from, UUID to, double percent, String reason, String ref) {
        Map<UUID, Long> res = new HashMap<>();
        CompletableFuture<Long> f = plugin.db().tx(c -> {
            res.clear();
            long bal = balance(c, from);
            long amount = (long) Math.floor(bal * percent / 100.0);
            if (amount <= 0) return 0L;
            res.put(from, debit(c, from, amount, reason + "_loss", ref));
            if (to != null) res.put(to, credit(c, to, amount, reason + "_gain", ref));
            return amount;
        });
        f.thenAccept(v -> apply(res));
        return f;
    }

    public CompletableFuture<long[]> transfer(UUID from, UUID to, long amount, String reason) {
        CompletableFuture<long[]> f = plugin.db().tx(c -> {
            long a = debit(c, from, amount, reason + "_out", to.toString());
            long b = credit(c, to, amount, reason + "_in", from.toString());
            return new long[]{a, b};
        });
        f.thenAccept(r -> apply(Map.of(from, r[0], to, r[1])));
        return f;
    }

    /** Проверка запрета торговли (временное ограничение от модерации). */
    public boolean tradeAllowed(Player p) {
        Profile pr = plugin.profiles().get(p);
        if (pr != null && pr.isTradeBlocked()) {
            Msg.error(p, "economy.trade-blocked", "Вам временно ограничены торговые операции.");
            return false;
        }
        if (plugin.maintenance() != null && plugin.maintenance().blocksCritical()) {
            Msg.error(p, "maintenance.blocked", "Идут технические работы: торговые операции временно приостановлены.");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ Команды

    private void registerCommands() {
        plugin.commands().add("balance").perm("usmp.player.balance").module("economy").cat("Экономика")
                .usage("/balance [игрок]").desc("Баланс").exec((s, a) -> {
                    if (a.length == 0) {
                        if (!(s instanceof Player p)) { Msg.error(s, "cmd.player-only", "Укажите игрока."); return; }
                        Msg.send(p, "economy.balance", "&7Ваш баланс: &6{b}", "b", Text.money(balance(p)));
                        return;
                    }
                    Tasks.then(plugin.profiles().findUuid(a[0]), u -> {
                        if (u == null) { Msg.error(s, "player.unknown", "Игрок не найден."); return; }
                        Tasks.then(balanceOf(u), b -> Msg.send(s, "economy.balance-other", "&7Баланс {p}: &6{b}", "p", a[0], "b", Text.money(b == null ? 0 : b)), null);
                    }, null);
                });
        plugin.commands().add("pay").player().perm("usmp.player.pay").module("economy").cat("Экономика").cooldown(1500)
                .usage("/pay <игрок> <сумма>").desc("Перевести деньги игроку онлайн").exec((s, a) -> pay((Player) s, a))
                .tab((s, a) -> a.length == 1 ? Commands.players(a[0]) : List.of("100", "1000", "10000"));
    }

    private void pay(Player p, String[] a) {
        if (a.length != 2) { Msg.error(p, "pay.usage", "Использование: /pay <игрок> <сумма>"); return; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) for (Player o : Bukkit.getOnlinePlayers()) if (o.getName().equalsIgnoreCase(a[0])) t = o;
        if (t == null || !plugin.auth().isAuthed(t)) { Msg.error(p, "pay.offline", "Перевод возможен только игроку в сети."); return; }
        if (t.equals(p)) { Msg.error(p, "pay.self", "Нельзя перевести деньги самому себе."); return; }
        long amount = Text.parseAmount(a[1]);
        long max = plugin.configs().get("economy.yml").getLong("pay.max", 1_000_000_000L);
        long min = plugin.configs().get("economy.yml").getLong("pay.min", 1);
        if (amount < min || amount > max) {
            Msg.error(p, "pay.amount", "Сумма должна быть от {min} до {max}.", "min", Text.money(min), "max", Text.money(max));
            return;
        }
        if (!tradeAllowed(p)) return;
        final Player target = t;
        long confirm = plugin.configs().get("economy.yml").getLong("pay.confirm-above", 100_000);
        Runnable go = () -> {
            plugin.logs().log(p, "economy", "pay_attempt", p.getLocation(), target.getName() + " " + amount);
            Tasks.then(transfer(p.getUniqueId(), target.getUniqueId(), amount, "pay"), r -> {
                Msg.ok(p, "pay.sent", "Вы перевели {a} игроку {t}. Баланс: {b}", "a", Text.money(amount), "t", target.getName(), "b", Text.money(r[0]));
                if (target.isOnline()) Msg.ok(target, "pay.received", "{p} перевёл вам {a}.", "p", p.getName(), "a", Text.money(amount));
                plugin.logs().log(p, "economy", "pay", p.getLocation(), target.getName() + " " + amount);
                plugin.quests().progress(target, "EARN", "pay", amount);
            }, err -> {
                if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
                else {
                    Msg.error(p, "economy.error", "Операция не выполнена из-за ошибки. Деньги не списаны.");
                    plugin.logs().log(p, "economy", "tx_error", null, "pay " + err.getMessage());
                }
            });
        };
        if (amount >= confirm) {
            ConfirmMenu.open(p, "&8Подтвердите перевод", Items.of(Material.GOLD_INGOT).name("&6" + Text.money(amount))
                    .lore("&7Получатель: &f" + target.getName()).build(), List.of("&7Перевести &6" + Text.money(amount), "&7игроку &f" + target.getName()), go);
        } else {
            go.run();
        }
    }
}
