package ru.unknownsmp.clans;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.unknownsmp.core.Commands;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.economy.EconomyService;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Кланы: платное создание, до 5 участников, настраиваемые роли, банк (вносят все, снимает лидер),
 * точка появления, альянсы без ограничений. Территориальной защиты нет.
 */
public final class ClanService implements Module {
    private static final Pattern TAG = Pattern.compile("^[A-Za-zА-Яа-яЁё0-9]{2,5}$");

    private final UnknownSMP plugin;
    private final Map<Long, Clan> clans = new ConcurrentHashMap<>();
    private final Map<UUID, Long> memberOf = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> invites = new ConcurrentHashMap<>(); // target -> {clanId, until}
    private final Map<Long, long[]> allyRequests = new ConcurrentHashMap<>(); // targetClan -> {fromClan, until}
    private final java.util.Set<Long> busy = ConcurrentHashMap.newKeySet();
    private ClanMenus menus;

    public ClanService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "clans"; }
    @Override public String title() { return "Кланы"; }
    @Override public String[] depends() { return new String[]{"economy", "profiles"}; }

    @Override
    public void enable() throws Exception {
        loadAll();
        menus = new ClanMenus(plugin, this);
        registerCommands();
    }

    private void loadAll() throws Exception {
        plugin.db().queryBlocking(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM clans"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Clan cl = new Clan(rs.getLong("id"));
                    cl.tag = rs.getString("tag");
                    cl.name = rs.getString("name");
                    cl.leader = rs.getString("leader") == null ? null : UUID.fromString(rs.getString("leader"));
                    cl.bank = rs.getLong("bank");
                    cl.home = rs.getString("home");
                    cl.createdAt = rs.getLong("created_at");
                    cl.parsePerms(rs.getString("perms"));
                    cl.warsWon = rs.getInt("wars_won");
                    cl.warsLost = rs.getInt("wars_lost");
                    clans.put(cl.id, cl);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT m.uuid, m.clan_id, m.role, m.joined_at, p.name FROM clan_members m LEFT JOIN players p ON p.uuid = m.uuid");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Clan cl = clans.get(rs.getLong(2));
                    if (cl == null) continue;
                    UUID u = UUID.fromString(rs.getString(1));
                    Clan.Role r;
                    try { r = Clan.Role.valueOf(rs.getString(3)); } catch (Exception e) { r = Clan.Role.MEMBER; }
                    cl.members.put(u, new Clan.Member(u, rs.getString(5), r, rs.getLong(4)));
                    memberOf.put(u, cl.id);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT a, b FROM clan_allies"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Clan a = clans.get(rs.getLong(1)), b = clans.get(rs.getLong(2));
                    if (a != null && b != null) { a.allies.add(b.id); b.allies.add(a.id); }
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ Доступ

    public Clan clanOf(UUID u) {
        Long id = memberOf.get(u);
        return id == null ? null : clans.get(id);
    }

    public Clan clanOf(Player p) { return clanOf(p.getUniqueId()); }

    public Clan byTag(String tag) {
        for (Clan c : clans.values()) if (c.tag.equalsIgnoreCase(tag)) return c;
        return null;
    }

    public Clan byId(long id) { return clans.get(id); }

    public Collection<Clan> all() { return clans.values(); }

    public int maxMembers() { return plugin.configs().get("clans.yml").getInt("max-members", 5); }

    public long createCost() { return plugin.configs().get("clans.yml").getLong("create-cost", 50_000); }

    public boolean allied(Clan a, Clan b) { return a != null && b != null && a.allies.contains(b.id); }

    public ClanMenus menus() { return menus; }

    // ------------------------------------------------------------------ Создание и состав

    public void create(Player p, String tag, String name) {
        if (clanOf(p) != null) { Msg.error(p, "clan.already", "Вы уже состоите в клане."); return; }
        if (!TAG.matcher(tag).matches()) { Msg.error(p, "clan.tag", "Тег: 2–5 букв или цифр."); return; }
        if (name == null || name.isBlank() || name.length() > 24) { Msg.error(p, "clan.name", "Название: 1–24 символа."); return; }
        if (byTag(tag) != null) { Msg.error(p, "clan.tag-taken", "Тег уже занят."); return; }
        if (!plugin.economy().tradeAllowed(p)) return;
        long cost = createCost();
        final UUID u = p.getUniqueId();
        final String clean = Text.strip(name);
        Tasks.then(plugin.db().tx(c -> {
            Long exists = Database.longVal(c, "SELECT COUNT(*) FROM clan_members WHERE uuid=?", u.toString());
            if (exists != null && exists > 0) throw new Database.TxAbort("clan.already", "Вы уже состоите в клане.");
            long bal = EconomyService.debit(c, u, cost, "clan_create", tag);
            Clan tmp = new Clan(0);
            long id = Database.insert(c, "INSERT INTO clans(tag, tag_lower, name, leader, bank, created_at, perms) VALUES (?,?,?,?,0,?,?)",
                    tag, tag.toLowerCase(Locale.ROOT), clean, u.toString(), System.currentTimeMillis(), tmp.permsString());
            Database.update(c, "INSERT INTO clan_members(uuid, clan_id, role, joined_at) VALUES (?,?,?,?)", u.toString(), id, "LEADER", System.currentTimeMillis());
            return new long[]{id, bal};
        }), r -> {
            plugin.economy().apply(u, r[1]);
            Clan cl = new Clan(r[0]);
            cl.tag = tag;
            cl.name = clean;
            cl.leader = u;
            cl.createdAt = System.currentTimeMillis();
            cl.members.put(u, new Clan.Member(u, p.getName(), Clan.Role.LEADER, cl.createdAt));
            clans.put(cl.id, cl);
            memberOf.put(u, cl.id);
            Msg.broadcast("clan.created", "&fИгрок &e{p}&f основал клан &d[{t}] {n}&f!", "p", p.getName(), "t", tag, "n", clean);
            plugin.logs().log(p, "clan", "create", p.getLocation(), tag + " cost=" + cost);
            plugin.display().refresh(p);
        }, err -> {
            if (err instanceof Database.TxAbort ab) {
                if (ab.key.equals("economy.insufficient")) Msg.error(p, "clan.cost", "Создание клана стоит {c}.", "c", Text.money(cost));
                else Msg.error(p, ab.key, ab.def);
            } else if (String.valueOf(err.getMessage()).toLowerCase().contains("unique")) Msg.error(p, "clan.tag-taken", "Тег уже занят.");
            else Msg.error(p, "clan.error", "Ошибка создания клана. Деньги не списаны.");
        });
    }

    public void invite(Player p, Player t) {
        Clan cl = clanOf(p);
        if (cl == null) { Msg.error(p, "clan.none", "Вы не состоите в клане."); return; }
        if (!cl.can(p.getUniqueId(), Clan.Perm.INVITE)) { Msg.error(p, "clan.no-perm", "Недостаточно прав в клане."); return; }
        if (t == null || !plugin.auth().isAuthed(t)) { Msg.error(p, "player.offline", "Игрок не в сети."); return; }
        if (clanOf(t) != null) { Msg.error(p, "clan.target-in-clan", "Игрок уже в клане."); return; }
        if (cl.members.size() >= maxMembers()) { Msg.error(p, "clan.full", "В клане максимум {n} участников.", "n", maxMembers()); return; }
        invites.put(t.getUniqueId(), new long[]{cl.id, System.currentTimeMillis() + 120_000});
        Msg.ok(p, "clan.invited", "Приглашение отправлено {t}.", "t", t.getName());
        Msg.send(t, "clan.invite", "&e{p}&f приглашает вас в клан &d[{tag}] {n}&f. &a/clan accept &7(2 мин.)", "p", p.getName(), "tag", cl.tag, "n", cl.name);
    }

    public void accept(Player p) {
        long[] inv = invites.remove(p.getUniqueId());
        if (inv == null || inv[1] < System.currentTimeMillis()) { Msg.error(p, "clan.no-invite", "Нет активных приглашений."); return; }
        Clan cl = clans.get(inv[0]);
        if (cl == null) { Msg.error(p, "clan.gone", "Клан больше не существует."); return; }
        if (clanOf(p) != null) { Msg.error(p, "clan.already", "Вы уже состоите в клане."); return; }
        final UUID u = p.getUniqueId();
        final int max = maxMembers();
        Tasks.then(plugin.db().tx(c -> {
            Long cnt = Database.longVal(c, "SELECT COUNT(*) FROM clan_members WHERE clan_id=?", cl.id);
            if (cnt != null && cnt >= max) throw new Database.TxAbort("clan.full", "Клан заполнен.");
            Long in = Database.longVal(c, "SELECT COUNT(*) FROM clan_members WHERE uuid=?", u.toString());
            if (in != null && in > 0) throw new Database.TxAbort("clan.already", "Вы уже состоите в клане.");
            Database.update(c, "INSERT INTO clan_members(uuid, clan_id, role, joined_at) VALUES (?,?,?,?)", u.toString(), cl.id, "MEMBER", System.currentTimeMillis());
            return null;
        }), v -> {
            cl.members.put(u, new Clan.Member(u, p.getName(), Clan.Role.MEMBER, System.currentTimeMillis()));
            memberOf.put(u, cl.id);
            broadcastClan(cl, "clan.joined", "&a{p} вступил в клан.", "p", p.getName());
            plugin.logs().log(p, "clan", "join", p.getLocation(), cl.tag);
            plugin.display().refresh(p);
        }, err -> Msg.error(p, err instanceof Database.TxAbort ab ? ab.key : "clan.error", err instanceof Database.TxAbort ab ? ab.def : "Ошибка вступления."));
    }

    public void kick(Player p, UUID target) {
        Clan cl = clanOf(p);
        if (cl == null || !cl.members.containsKey(target)) { Msg.error(p, "clan.not-member", "Игрок не состоит в вашем клане."); return; }
        if (!cl.can(p.getUniqueId(), Clan.Perm.KICK)) { Msg.error(p, "clan.no-perm", "Недостаточно прав в клане."); return; }
        Clan.Role tr = cl.role(target);
        if (tr == Clan.Role.LEADER || (tr == Clan.Role.OFFICER && cl.role(p.getUniqueId()) != Clan.Role.LEADER) || target.equals(p.getUniqueId())) {
            Msg.error(p, "clan.cant-kick", "Нельзя исключить этого участника.");
            return;
        }
        final String tn = cl.members.get(target).name();
        removeMember(cl, target, () -> {
            broadcastClan(cl, "clan.kicked", "&c{t} исключён из клана.", "t", tn);
            Player t = Bukkit.getPlayer(target);
            if (t != null) { Msg.error(t, "clan.you-kicked", "Вас исключили из клана."); plugin.display().refresh(t); }
            plugin.logs().log(p, "clan", "kick", p.getLocation(), cl.tag + " " + target);
        });
    }

    private final Map<UUID, String> names = new HashMap<>();

    private String nameOf(UUID u) {
        Player p = Bukkit.getPlayer(u);
        return p != null ? p.getName() : names.getOrDefault(u, u.toString().substring(0, 8));
    }

    private void removeMember(Clan cl, UUID target, Runnable after) {
        Clan.Member m = cl.members.get(target);
        if (m != null) names.put(target, m.name());
        Tasks.then(plugin.db().tx(c -> Database.update(c, "DELETE FROM clan_members WHERE uuid=? AND clan_id=?", target.toString(), cl.id)), n -> {
            cl.members.remove(target);
            memberOf.remove(target);
            if (after != null) after.run();
        }, err -> plugin.getLogger().warning("Ошибка удаления участника клана: " + err.getMessage()));
    }

    public void leave(Player p) {
        Clan cl = clanOf(p);
        if (cl == null) { Msg.error(p, "clan.none", "Вы не состоите в клане."); return; }
        if (cl.leader.equals(p.getUniqueId())) {
            if (cl.members.size() == 1) {
                disband(p, cl);
                return;
            }
            // Лидер уходит без назначения преемника — выбор по активности и вкладу.
            autoSuccessor(cl, p.getUniqueId(), succ -> {
                setLeader(cl, succ, () -> removeMember(cl, p.getUniqueId(), () -> {
                    Msg.ok(p, "clan.left", "Вы покинули клан.");
                    broadcastClan(cl, "clan.new-leader", "&6Новый лидер клана: {n} (выбран по активности и вкладу).", "n", nameOf(succ));
                    plugin.display().refresh(p);
                }));
            });
            return;
        }
        removeMember(cl, p.getUniqueId(), () -> {
            Msg.ok(p, "clan.left", "Вы покинули клан.");
            broadcastClan(cl, "clan.member-left", "&7{p} покинул клан.", "p", p.getName());
            plugin.logs().log(p, "clan", "leave", p.getLocation(), cl.tag);
            plugin.display().refresh(p);
        });
    }

    /** Формула преемника: часы игры за N дней × w1 + сумма взносов в банк за N дней / 1000 × w2. */
    private void autoSuccessor(Clan cl, UUID exclude, java.util.function.Consumer<UUID> then) {
        var cfg = plugin.configs().get("clans.yml");
        int days = cfg.getInt("succession.days", 14);
        double wTime = cfg.getDouble("succession.weight-hours", 1.0);
        double wBank = cfg.getDouble("succession.weight-per-1000-deposited", 1.0);
        long since = System.currentTimeMillis() - days * 86_400_000L;
        long sinceDay = LocalDate.now(Text.zone()).toEpochDay() - days;
        List<UUID> cands = new ArrayList<>();
        for (UUID u : cl.members.keySet()) if (!u.equals(exclude)) cands.add(u);
        Tasks.then(plugin.db().query(c -> {
            UUID best = null;
            double bestScore = -1;
            for (UUID u : cands) {
                Long secs = Database.longVal(c, "SELECT COALESCE(SUM(seconds),0) FROM activity WHERE uuid=? AND day >= ?", u.toString(), sinceDay);
                Long dep = Database.longVal(c, "SELECT COALESCE(SUM(delta),0) FROM clan_bank_log WHERE clan_id=? AND uuid=? AND delta > 0 AND at >= ?", cl.id, u.toString(), since);
                double score = (secs == null ? 0 : secs) / 3600.0 * wTime + (dep == null ? 0 : dep) / 1000.0 * wBank;
                // При равенстве — более ранний участник (стаж).
                if (score > bestScore || (score == bestScore && best != null && cl.members.get(u).joinedAt() < cl.members.get(best).joinedAt())) {
                    best = u;
                    bestScore = score;
                }
            }
            return best;
        }), best -> { if (best != null) then.accept(best); }, null);
    }

    public void setLeader(Clan cl, UUID newLeader, Runnable after) {
        UUID old = cl.leader;
        Tasks.then(plugin.db().tx(c -> {
            Database.update(c, "UPDATE clans SET leader=? WHERE id=?", newLeader.toString(), cl.id);
            Database.update(c, "UPDATE clan_members SET role='OFFICER' WHERE uuid=? AND clan_id=?", old.toString(), cl.id);
            Database.update(c, "UPDATE clan_members SET role='LEADER' WHERE uuid=? AND clan_id=?", newLeader.toString(), cl.id);
            return null;
        }), v -> {
            cl.leader = newLeader;
            Clan.Member om = cl.members.get(old);
            if (om != null) cl.members.put(old, new Clan.Member(old, om.name(), Clan.Role.OFFICER, om.joinedAt()));
            Clan.Member nm = cl.members.get(newLeader);
            if (nm != null) cl.members.put(newLeader, new Clan.Member(newLeader, nm.name(), Clan.Role.LEADER, nm.joinedAt()));
            plugin.logs().log(old, null, "clan", "leader_change", cl.tag + " -> " + newLeader);
            if (after != null) after.run();
        }, err -> plugin.getLogger().warning("Ошибка смены лидера: " + err.getMessage()));
    }

    public void setRole(Player p, UUID target, Clan.Role role) {
        Clan cl = clanOf(p);
        if (cl == null || !cl.leader.equals(p.getUniqueId())) { Msg.error(p, "clan.leader-only", "Только лидер."); return; }
        if (!cl.members.containsKey(target) || target.equals(p.getUniqueId()) || role == Clan.Role.LEADER) return;
        Tasks.then(plugin.db().tx(c -> Database.update(c, "UPDATE clan_members SET role=? WHERE uuid=? AND clan_id=?", role.name(), target.toString(), cl.id)), n -> {
            Clan.Member m = cl.members.get(target);
            cl.members.put(target, new Clan.Member(target, m.name(), role, m.joinedAt()));
            broadcastClan(cl, "clan.role-changed", "&e{t} теперь {r}&e.", "t", m.name(), "r", role.display);
        }, null);
    }

    public void togglePerm(Player p, Clan.Role role, Clan.Perm perm) {
        Clan cl = clanOf(p);
        if (cl == null || !cl.leader.equals(p.getUniqueId()) || role == Clan.Role.LEADER) return;
        var set = cl.perms.computeIfAbsent(role, r -> java.util.EnumSet.noneOf(Clan.Perm.class));
        if (!set.remove(perm)) set.add(perm);
        final String s = cl.permsString();
        plugin.db().tx(c -> Database.update(c, "UPDATE clans SET perms=? WHERE id=?", s, cl.id));
    }

    public void disband(Player p, Clan cl) {
        if (!cl.leader.equals(p.getUniqueId()) && !plugin.roles().has(p, "usmp.admin.clans")) { Msg.error(p, "clan.leader-only", "Только лидер."); return; }
        if (!busy.add(cl.id)) return;
        final UUID leader = cl.leader;
        Tasks.then(plugin.db().tx(c -> {
            Long bank = Database.longVal(c, "SELECT bank FROM clans WHERE id=?", cl.id);
            long bal = -1;
            if (bank != null && bank > 0) {
                bal = EconomyService.credit(c, leader, bank, "clan_disband", cl.tag);
                Database.update(c, "INSERT INTO clan_bank_log(clan_id, uuid, delta, at, reason) VALUES (?,?,?,?,?)", cl.id, leader.toString(), -bank, System.currentTimeMillis(), "disband");
            }
            Database.update(c, "DELETE FROM clan_members WHERE clan_id=?", cl.id);
            Database.update(c, "DELETE FROM clan_allies WHERE a=? OR b=?", cl.id, cl.id);
            Database.update(c, "DELETE FROM clans WHERE id=?", cl.id);
            return new long[]{bank == null ? 0 : bank, bal};
        }), r -> {
            busy.remove(cl.id);
            if (r[1] >= 0) plugin.economy().apply(leader, r[1]);
            if (plugin.wars() != null) plugin.wars().onDisband(cl);
            for (UUID u : cl.members.keySet()) memberOf.remove(u);
            for (Long a : cl.allies) { Clan o = clans.get(a); if (o != null) o.allies.remove(cl.id); }
            clans.remove(cl.id);
            Msg.broadcast("clan.disbanded", "&7Клан &d[{t}]&7 распущен.", "t", cl.tag);
            plugin.logs().log(p, "clan", "disband", p.getLocation(), cl.tag + " bank_to_leader=" + r[0]);
            for (UUID u : cl.members.keySet()) { Player m = Bukkit.getPlayer(u); if (m != null) plugin.display().refresh(m); }
        }, err -> { busy.remove(cl.id); Msg.error(p, "clan.error", "Ошибка роспуска клана."); });
    }

    // ------------------------------------------------------------------ Банк

    public void deposit(Player p, long amount) {
        Clan cl = clanOf(p);
        if (cl == null) { Msg.error(p, "clan.none", "Вы не состоите в клане."); return; }
        if (amount < 1) { Msg.error(p, "clan.amount", "Неверная сумма."); return; }
        if (!plugin.economy().tradeAllowed(p)) return;
        final UUID u = p.getUniqueId();
        Tasks.then(plugin.db().tx(c -> {
            long bal = EconomyService.debit(c, u, amount, "clan_deposit", cl.tag);
            Database.update(c, "UPDATE clans SET bank = bank + ? WHERE id=?", amount, cl.id);
            Database.update(c, "INSERT INTO clan_bank_log(clan_id, uuid, delta, at, reason) VALUES (?,?,?,?,?)", cl.id, u.toString(), amount, System.currentTimeMillis(), "deposit");
            Long bank = Database.longVal(c, "SELECT bank FROM clans WHERE id=?", cl.id);
            return new long[]{bal, bank == null ? 0 : bank};
        }), r -> {
            plugin.economy().apply(u, r[0]);
            cl.bank = r[1];
            broadcastClan(cl, "clan.deposit", "&a{p} внёс в банк клана {a}. Банк: {b}", "p", p.getName(), "a", Text.money(amount), "b", Text.money(r[1]));
            plugin.logs().log(p, "clanbank", "deposit", p.getLocation(), cl.tag + " " + amount);
        }, err -> Msg.error(p, err instanceof Database.TxAbort ab ? ab.key : "clan.error", err instanceof Database.TxAbort ab ? ab.def : "Ошибка операции. Деньги не списаны."));
    }

    public void withdraw(Player p, long amount) {
        Clan cl = clanOf(p);
        if (cl == null || !cl.leader.equals(p.getUniqueId())) { Msg.error(p, "clan.leader-only", "Снимать средства может только лидер."); return; }
        if (amount < 1) { Msg.error(p, "clan.amount", "Неверная сумма."); return; }
        if (!plugin.economy().tradeAllowed(p)) return;
        final UUID u = p.getUniqueId();
        Tasks.then(plugin.db().tx(c -> {
            int n = Database.update(c, "UPDATE clans SET bank = bank - ? WHERE id=? AND bank >= ? AND leader=?", amount, cl.id, amount, u.toString());
            if (n != 1) throw new Database.TxAbort("clan.bank-low", "В банке клана недостаточно средств.");
            long bal = EconomyService.credit(c, u, amount, "clan_withdraw", cl.tag);
            Database.update(c, "INSERT INTO clan_bank_log(clan_id, uuid, delta, at, reason) VALUES (?,?,?,?,?)", cl.id, u.toString(), -amount, System.currentTimeMillis(), "withdraw");
            Long bank = Database.longVal(c, "SELECT bank FROM clans WHERE id=?", cl.id);
            return new long[]{bal, bank == null ? 0 : bank};
        }), r -> {
            plugin.economy().apply(u, r[0]);
            cl.bank = r[1];
            broadcastClan(cl, "clan.withdraw", "&e{p} снял из банка клана {a}. Банк: {b}", "p", p.getName(), "a", Text.money(amount), "b", Text.money(r[1]));
            plugin.logs().log(p, "clanbank", "withdraw", p.getLocation(), cl.tag + " " + amount);
        }, err -> Msg.error(p, err instanceof Database.TxAbort ab ? ab.key : "clan.error", err instanceof Database.TxAbort ab ? ab.def : "Ошибка операции."));
    }

    // ------------------------------------------------------------------ Точка клана

    public void setHome(Player p) {
        Clan cl = clanOf(p);
        if (cl == null || !cl.can(p.getUniqueId(), Clan.Perm.HOME_SET)) { Msg.error(p, "clan.no-perm", "Недостаточно прав в клане."); return; }
        if (plugin.lobby().isLobby(p.getWorld()) || (plugin.duels() != null && plugin.duels().isDuelWorld(p.getWorld()))) {
            Msg.error(p, "home.world", "Точку клана можно установить только в мире выживания.");
            return;
        }
        String loc = Text.loc(p.getLocation());
        Tasks.then(plugin.db().tx(c -> Database.update(c, "UPDATE clans SET home=? WHERE id=?", loc, cl.id)), n -> {
            cl.home = loc;
            broadcastClan(cl, "clan.home-set", "&aТочка появления клана установлена: {c}", "c", Text.coords(p.getLocation()));
        }, null);
    }

    public void home(Player p) {
        Clan cl = clanOf(p);
        if (cl == null) { Msg.error(p, "clan.none", "Вы не состоите в клане."); return; }
        Location l = Text.loc(cl.home);
        if (l == null) { Msg.error(p, "clan.no-home", "У клана нет точки появления."); return; }
        if (plugin.combat().isTagged(p)) { Msg.error(p, "combat.teleport", "Телепортация недоступна во время боя ({t}).", "t", Text.duration(plugin.combat().remaining(p))); return; }
        if (plugin.duels() != null && plugin.duels().isDuelWorld(p.getWorld())) { Msg.error(p, "teleport.duels", "В режиме дуэлей это недоступно."); return; }
        boolean fromLobby = plugin.lobby().isLobby(p.getWorld());
        plugin.teleports().teleportWithWarmup(p, l, "точка клана", () -> {
            if (fromLobby && p.getGameMode() == org.bukkit.GameMode.ADVENTURE) p.setGameMode(org.bukkit.GameMode.SURVIVAL);
        });
    }

    // ------------------------------------------------------------------ Альянсы

    public void requestAlly(Player p, Clan target) {
        Clan cl = clanOf(p);
        if (cl == null || !cl.can(p.getUniqueId(), Clan.Perm.ALLY)) { Msg.error(p, "clan.no-perm", "Недостаточно прав в клане."); return; }
        if (target == null || target == cl) { Msg.error(p, "clan.unknown", "Клан не найден."); return; }
        if (cl.allies.contains(target.id)) { Msg.error(p, "clan.already-ally", "Вы уже союзники."); return; }
        if (plugin.wars().activeBetween(cl, target) != null) { Msg.error(p, "clan.at-war", "Нельзя заключить союз во время войны."); return; }
        long[] req = allyRequests.get(cl.id);
        if (req != null && req[0] == target.id && req[1] > System.currentTimeMillis()) {
            // Встречный запрос — союз заключён.
            allyRequests.remove(cl.id);
            long a = Math.min(cl.id, target.id), b = Math.max(cl.id, target.id);
            Tasks.then(plugin.db().tx(c -> Database.update(c, "INSERT INTO clan_allies(a, b) VALUES (?,?) ON CONFLICT DO NOTHING", a, b)), n -> {
                cl.allies.add(target.id);
                target.allies.add(cl.id);
                Msg.broadcast("clan.allied", "&aКланы &d[{a}]&a и &d[{b}]&a заключили союз.", "a", cl.tag, "b", target.tag);
            }, null);
            return;
        }
        allyRequests.put(target.id, new long[]{cl.id, System.currentTimeMillis() + 300_000});
        Msg.ok(p, "clan.ally-sent", "Предложение союза отправлено клану [{t}].", "t", target.tag);
        broadcastClan(target, "clan.ally-request", "&eКлан [{t}] предлагает союз. Принять: &a/clan ally {t}", "t", cl.tag);
    }

    public void breakAlly(Player p, Clan target) {
        Clan cl = clanOf(p);
        if (cl == null || !cl.can(p.getUniqueId(), Clan.Perm.ALLY) || target == null || !cl.allies.contains(target.id)) {
            Msg.error(p, "clan.no-perm", "Недостаточно прав или союза нет.");
            return;
        }
        long a = Math.min(cl.id, target.id), b = Math.max(cl.id, target.id);
        Tasks.then(plugin.db().tx(c -> Database.update(c, "DELETE FROM clan_allies WHERE a=? AND b=?", a, b)), n -> {
            cl.allies.remove(target.id);
            target.allies.remove(cl.id);
            Msg.broadcast("clan.ally-broken", "&cСоюз кланов [{a}] и [{b}] расторгнут.", "a", cl.tag, "b", target.tag);
        }, null);
    }

    // ------------------------------------------------------------------ Сообщения

    public void broadcastClan(Clan cl, String key, String def, Object... kv) {
        for (UUID u : cl.members.keySet()) {
            Player m = Bukkit.getPlayer(u);
            if (m != null) Msg.send(m, key, "&d[Клан] &r" + def, kv);
        }
    }

    public void clanChat(Player p, String msg) {
        Clan cl = clanOf(p);
        if (cl == null) { Msg.error(p, "clan.none", "Вы не состоите в клане."); return; }
        var prof = plugin.profiles().get(p);
        if (prof != null && prof.isMuted()) { Msg.error(p, "chat.muted", "Вы не можете писать в чат: {r}", "r", prof.muteReason); return; }
        String line = Msg.t("clan.chat-format", "&d[Клан] &f{p}&7: &f{m}", "p", p.getName(), "m", msg.replace("&", ""));
        for (UUID u : cl.members.keySet()) {
            Player m = Bukkit.getPlayer(u);
            var mp = m == null ? null : plugin.profiles().get(m);
            if (m != null && (mp == null || mp.setting("chat_clan"))) Msg.raw(m, line);
        }
        plugin.logs().log(p, "chat", "clan", p.getLocation(), msg);
    }

    // ------------------------------------------------------------------ Команды

    private void registerCommands() {
        plugin.commands().add("clan").player().perm("usmp.player.clan").module("clans").cat("Кланы")
                .usage("/clan [create|invite|accept|leave|kick|deposit|withdraw|home|sethome|ally|war|info|disband]").desc("Кланы (GUI)")
                .exec((s, a) -> command((Player) s, a)).tab((s, a) -> {
                    if (a.length == 1) return List.of("create", "invite", "accept", "leave", "kick", "deposit", "withdraw", "home", "sethome", "ally", "unally", "war", "peace", "info", "leader", "disband");
                    if (a.length == 2 && List.of("invite", "kick", "leader").contains(a[0].toLowerCase())) return Commands.players(a[1]);
                    if (a.length == 2 && List.of("ally", "unally", "war", "peace", "info").contains(a[0].toLowerCase())) {
                        List<String> t = new ArrayList<>();
                        for (Clan c : clans.values()) t.add(c.tag);
                        return t;
                    }
                    return List.of();
                });
        plugin.commands().add("cc").player().perm("usmp.player.clan").module("clans").cat("Кланы").usage("/cc <сообщение>")
                .desc("Клановый чат").exec((s, a) -> {
                    if (a.length == 0) { Msg.error(s, "cc.usage", "Использование: /cc <сообщение>"); return; }
                    clanChat((Player) s, String.join(" ", a));
                }).tab((s, a) -> List.of());
    }

    private void command(Player p, String[] a) {
        if (a.length == 0) { menus.open(p, null); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (a.length < 3) { Msg.error(p, "clan.create-usage", "Использование: /clan create <тег> <название> (стоимость {c})", "c", Text.money(createCost())); return; }
                create(p, a[1], String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)));
            }
            case "invite" -> { if (a.length < 2) return; invite(p, Bukkit.getPlayerExact(a[1])); }
            case "accept" -> accept(p);
            case "leave" -> menus.confirmLeave(p);
            case "kick" -> {
                if (a.length < 2) return;
                Clan cl = clanOf(p);
                if (cl == null) return;
                for (Clan.Member m : cl.members.values()) if (m.name() != null && m.name().equalsIgnoreCase(a[1])) { kick(p, m.uuid()); return; }
                Msg.error(p, "clan.not-member", "Игрок не состоит в вашем клане.");
            }
            case "leader" -> {
                if (a.length < 2) return;
                Clan cl = clanOf(p);
                if (cl == null || !cl.leader.equals(p.getUniqueId())) { Msg.error(p, "clan.leader-only", "Только лидер."); return; }
                for (Clan.Member m : cl.members.values()) if (m.name() != null && m.name().equalsIgnoreCase(a[1]) && !m.uuid().equals(p.getUniqueId())) {
                    setLeader(cl, m.uuid(), () -> broadcastClan(cl, "clan.new-leader-manual", "&6{n} назначен лидером клана.", "n", m.name()));
                    return;
                }
                Msg.error(p, "clan.not-member", "Игрок не состоит в вашем клане.");
            }
            case "deposit" -> { if (a.length < 2) return; deposit(p, Text.parseAmount(a[1])); }
            case "withdraw" -> { if (a.length < 2) return; withdraw(p, Text.parseAmount(a[1])); }
            case "home" -> home(p);
            case "sethome" -> setHome(p);
            case "ally" -> { if (a.length < 2) return; requestAlly(p, byTag(a[1])); }
            case "unally" -> { if (a.length < 2) return; breakAlly(p, byTag(a[1])); }
            case "war" -> { if (a.length < 2) { menus.wars(p, null); return; } plugin.wars().declare(p, byTag(a[1])); }
            case "peace" -> { if (a.length < 2) return; plugin.wars().proposePeace(p, byTag(a[1])); }
            case "info" -> {
                Clan cl = a.length > 1 ? byTag(a[1]) : clanOf(p);
                if (cl == null) { Msg.error(p, "clan.unknown", "Клан не найден."); return; }
                menus.info(p, cl, null);
            }
            case "disband" -> {
                Clan cl = clanOf(p);
                if (cl == null) return;
                menus.confirmDisband(p, cl);
            }
            default -> menus.open(p, null);
        }
    }
}
