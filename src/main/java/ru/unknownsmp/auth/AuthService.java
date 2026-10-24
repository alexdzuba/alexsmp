package ru.unknownsmp.auth;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;

import java.net.InetAddress;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Offline-авторизация: регистрация, вход, сессии, лимиты попыток, изоляция неавторизованных игроков.
 */
public final class AuthService implements Module, Listener {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Set<String> ALLOWED_CMDS = Set.of("login", "l", "log", "register", "reg", "unknownsmp:login", "unknownsmp:register");

    public record Account(String nameLower, String name, String uuid, String hash, String sessionIp, long sessionUntil,
                          int failed, long lockedUntil) {}

    private final UnknownSMP plugin;
    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> timeouts = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final List<Consumer<Player>> loginHooks = new ArrayList<>();
    private final List<Consumer<Player>> joinHooks = new ArrayList<>();
    private boolean enabled = true;

    private int maxAttempts, lockMinutes, timeoutSec, sessionDays, iterations, minLen, maxLen, maxAccountsPerIp;
    private boolean sessions;

    public AuthService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "auth"; }
    @Override public String title() { return "Авторизация"; }
    @Override public String[] depends() { return new String[]{"database", "profiles"}; }

    @Override
    public void enable() {
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerCommands();
        // /reload: уже онлайн-игроков считаем вошедшими (их сессия была подтверждена до перезагрузки).
        for (Player p : Bukkit.getOnlinePlayers()) authed.add(p.getUniqueId());
        Tasks.timer(200, 200, this::remind);
    }

    @Override
    public void reload() {
        loadConfig();
    }

    private void loadConfig() {
        YamlConfiguration c = plugin.configs().get("auth.yml");
        enabled = c.getBoolean("enabled", true);
        maxAttempts = c.getInt("max-attempts", 10);
        lockMinutes = c.getInt("lockout-minutes", 15);
        timeoutSec = c.getInt("login-timeout-seconds", 60);
        sessions = c.getBoolean("sessions.enabled", true);
        sessionDays = c.getInt("sessions.days", 7);
        iterations = Math.max(100_000, c.getInt("pbkdf2-iterations", 600_000));
        minLen = c.getInt("password.min-length", 6);
        maxLen = c.getInt("password.max-length", 64);
        maxAccountsPerIp = c.getInt("max-accounts-per-ip", 3);
    }

    public void onLogin(Consumer<Player> hook) { loginHooks.add(hook); }

    /** Вызывается при входе на сервер до авторизации (для лобби). */
    public void onJoin(Consumer<Player> hook) { joinHooks.add(hook); }

    public boolean isAuthed(Player p) {
        return !enabled || authed.contains(p.getUniqueId());
    }

    // ------------------------------------------------------------------ Вход на сервер

    @EventHandler(priority = EventPriority.LOW)
    public void preLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        String name = e.getName();
        if (!NAME.matcher(name).matches()) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Msg.comp("auth.bad-name",
                    "&cНедопустимый ник. Разрешены латинские буквы, цифры и _ (3–16 символов)."));
            return;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Msg.comp("auth.already-online",
                        "&cЭтот аккаунт уже находится на сервере.\n&7Повторное подключение запрещено."));
                return;
            }
        }
        String ip = ip(e.getAddress());
        try {
            Object[] res = plugin.db().queryBlocking(c -> {
                Account acc = null;
                try (PreparedStatement ps = Database.ps(c, "SELECT * FROM accounts WHERE name_lower=?", name.toLowerCase(Locale.ROOT));
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        acc = new Account(rs.getString("name_lower"), rs.getString("name"), rs.getString("uuid"), rs.getString("hash"),
                                rs.getString("session_ip"), rs.getLong("session_until"), rs.getInt("failed"), rs.getLong("locked_until"));
                    }
                }
                Long ipLock = Database.longVal(c, "SELECT locked_until FROM ip_attempts WHERE ip=?", ip);
                return new Object[]{acc, ipLock == null ? 0L : ipLock};
            });
            Account acc = (Account) res[0];
            long ipLock = (Long) res[1];
            long now = System.currentTimeMillis();
            if (acc != null && !acc.name().equals(name)) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Msg.comp("auth.case-mismatch",
                        "&cАккаунт зарегистрирован как &f{n}&c.\n&7Войдите с точно таким же ником (регистр важен).", "n", acc.name()));
                return;
            }
            boolean trusted = acc != null && sessions && ip.equals(acc.sessionIp()) && acc.sessionUntil() > now;
            if (!trusted && acc != null && acc.lockedUntil() > now) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Msg.comp("auth.locked",
                        "&cСлишком много неверных попыток входа.\n&7Аккаунт временно заблокирован. Осталось: &f{t}",
                        "t", Text.duration(acc.lockedUntil() - now)));
                return;
            }
            if (!trusted && ipLock > now) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Msg.comp("auth.ip-locked",
                        "&cСлишком много неверных попыток входа с вашего адреса.\n&7Повторите через &f{t}",
                        "t", Text.duration(ipLock - now)));
                return;
            }
            if (acc != null) accounts.put(e.getUniqueId(), acc);
            else accounts.remove(e.getUniqueId());
        } catch (Exception ex) {
            plugin.getLogger().severe("Ошибка проверки аккаунта " + name + ": " + ex.getMessage());
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Msg.comp("auth.db-error",
                    "&cСервер авторизации временно недоступен. Попробуйте позже."));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        authed.remove(p.getUniqueId());
        e.joinMessage(null);
        if (!enabled) {
            authed.add(p.getUniqueId());
            Tasks.later(1, () -> fireLogin(p));
            return;
        }
        for (Consumer<Player> h : joinHooks) {
            try { h.accept(p); } catch (Throwable t) { plugin.getLogger().warning("join hook: " + t); }
        }
        isolate(p);
        Account acc = accounts.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        String ip = ip(p.getAddress() == null ? null : p.getAddress().getAddress());
        if (acc != null && sessions && ip.equals(acc.sessionIp()) && acc.sessionUntil() > now) {
            Tasks.later(5, () -> {
                if (!p.isOnline()) return;
                Msg.ok(p, "auth.session", "Автоматический вход по доверенной сессии.");
                complete(p, ip, false);
            });
            return;
        }
        Tasks.later(10, () -> prompt(p));
        timeouts.put(p.getUniqueId(), Tasks.later(timeoutSec * 20L, () -> {
            if (p.isOnline() && !isAuthed(p)) {
                p.kick(Msg.comp("auth.timeout", "&cВремя на вход истекло ({s} сек.).", "s", timeoutSec));
            }
        }));
    }

    private void prompt(Player p) {
        if (!p.isOnline() || isAuthed(p)) return;
        if (accounts.containsKey(p.getUniqueId())) {
            Msg.title(p, Msg.t("auth.title-login", "&5ALEX SMP"), Msg.t("auth.sub-login", "&7Введите &f/login <пароль>"), 10, 100, 10);
            Msg.send(p, "auth.prompt-login", "&fДобро пожаловать обратно! Войдите: &e/login <пароль>");
        } else {
            Msg.title(p, Msg.t("auth.title-register", "&5ALEX SMP"), Msg.t("auth.sub-register", "&7Зарегистрируйтесь: &f/register <пароль> <пароль>"), 10, 100, 10);
            Msg.send(p, "auth.prompt-register", "&fВы впервые на сервере. Регистрация: &e/register <пароль> <пароль>");
            Msg.send(p, "auth.prompt-rules", "&7Пароль: от {min} до {max} символов, не совпадает с ником. Никому его не сообщайте.",
                    "min", minLen, "max", maxLen);
        }
    }

    private void remind() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isAuthed(p)) prompt(p);
        }
    }

    /** Изоляция неавторизованного игрока. */
    private void isolate(Player p) {
        Location spot = plugin.lobby() != null ? plugin.lobby().authSpot() : null;
        if (spot != null) p.teleport(spot);
        else p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == p) continue;
            o.hidePlayer(plugin, p);
            p.hidePlayer(plugin, o);
        }
    }

    private void unisolate(Player p) {
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == p || !isAuthed(o)) continue;
            if (plugin.admin() != null && plugin.admin().isVanished(o) && !plugin.roles().isStaff(p)) {
                o.showPlayer(plugin, p);
                continue;
            }
            o.showPlayer(plugin, p);
            p.showPlayer(plugin, o);
        }
    }

    private void complete(Player p, String ip, boolean newSession) {
        authed.add(p.getUniqueId());
        BukkitTask t = timeouts.remove(p.getUniqueId());
        if (t != null) t.cancel();
        unisolate(p);
        final long now = System.currentTimeMillis();
        final long until = now + sessionDays * 86_400_000L;
        final String nl = p.getName().toLowerCase(Locale.ROOT);
        plugin.db().tx(c -> {
            if (newSession) {
                Database.update(c, "UPDATE accounts SET failed=0, locked_until=0, last_ip=?, last_login=?, session_ip=?, session_until=? WHERE name_lower=?",
                        ip, now, ip, sessions ? until : 0, nl);
            } else {
                Database.update(c, "UPDATE accounts SET last_ip=?, last_login=? WHERE name_lower=?", ip, now, nl);
            }
            Database.update(c, "DELETE FROM ip_attempts WHERE ip=?", ip);
            return null;
        });
        plugin.logs().log(p, "auth", "login", null, newSession ? "password" : "session");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        fireLogin(p);
    }

    private void fireLogin(Player p) {
        for (Consumer<Player> h : loginHooks) {
            try { h.accept(p); } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Ошибка обработчика входа", t);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        boolean was = authed.remove(u);
        accounts.remove(u);
        busy.remove(u);
        BukkitTask t = timeouts.remove(u);
        if (t != null) t.cancel();
        if (!was) e.quitMessage(null);
    }

    // ------------------------------------------------------------------ Команды

    private void registerCommands() {
        plugin.commands().add("register").unauthed().player().cat("Аккаунт").usage("/register <пароль> <пароль>")
                .desc("Регистрация аккаунта").exec((s, a) -> register((Player) s, a)).tab((s, a) -> List.of());
        plugin.commands().add("login").unauthed().player().cat("Аккаунт").usage("/login <пароль>")
                .desc("Вход в аккаунт").exec((s, a) -> login((Player) s, a)).tab((s, a) -> List.of());
        plugin.commands().add("changepassword").player().cat("Аккаунт").perm("usmp.player.account").usage("/changepassword <старый> <новый>")
                .desc("Смена пароля").exec((s, a) -> {
                    if (a.length != 2) { new PasswordMenu((Player) s).open(); return; }
                    changePassword((Player) s, a[0], a[1]);
                }).tab((s, a) -> List.of());
        plugin.commands().add("logout").player().cat("Аккаунт").perm("usmp.player.account").usage("/logout")
                .desc("Завершить доверенную сессию и выйти").exec((s, a) -> {
                    Player p = (Player) s;
                    revokeSessions(p.getName());
                    p.kick(Msg.comp("auth.logout", "&aСессия завершена. При следующем входе потребуется пароль."));
                });
    }

    private boolean validPassword(Player p, String pw) {
        if (pw.length() < minLen || pw.length() > maxLen) {
            Msg.error(p, "auth.pw-length", "Пароль должен содержать от {min} до {max} символов.", "min", minLen, "max", maxLen);
            return false;
        }
        if (pw.equalsIgnoreCase(p.getName())) {
            Msg.error(p, "auth.pw-name", "Пароль не должен совпадать с ником.");
            return false;
        }
        return true;
    }

    private void register(Player p, String[] a) {
        if (isAuthed(p)) { Msg.error(p, "auth.already", "Вы уже вошли."); return; }
        if (accounts.containsKey(p.getUniqueId())) { Msg.error(p, "auth.registered", "Аккаунт уже зарегистрирован. Используйте /login <пароль>"); return; }
        if (a.length != 2) { Msg.error(p, "auth.register-usage", "Использование: /register <пароль> <пароль>"); return; }
        if (!a[0].equals(a[1])) { Msg.error(p, "auth.pw-mismatch", "Пароли не совпадают."); return; }
        if (!validPassword(p, a[0])) return;
        if (!busy.add(p.getUniqueId())) return;
        final String pw = a[0];
        final String ip = ip(p.getAddress() == null ? null : p.getAddress().getAddress());
        final String name = p.getName();
        final UUID uuid = p.getUniqueId();
        Tasks.async(() -> {
            String hash = Passwords.hash(pw, iterations);
            Tasks.then(plugin.db().tx(c -> {
                Long count = Database.longVal(c, "SELECT COUNT(*) FROM accounts WHERE reg_ip=?", ip);
                if (maxAccountsPerIp > 0 && count != null && count >= maxAccountsPerIp && !ip.equals("local")) {
                    throw new Database.TxAbort("auth.ip-limit", "С вашего адреса зарегистрировано максимальное число аккаунтов ({n}).");
                }
                Long exists = Database.longVal(c, "SELECT COUNT(*) FROM accounts WHERE name_lower=?", name.toLowerCase(Locale.ROOT));
                if (exists != null && exists > 0) throw new Database.TxAbort("auth.registered", "Аккаунт уже зарегистрирован.");
                long now = System.currentTimeMillis();
                Database.update(c, "INSERT INTO accounts(name_lower, name, uuid, hash, created_at, reg_ip, last_ip, last_login) VALUES (?,?,?,?,?,?,?,?)",
                        name.toLowerCase(Locale.ROOT), name, uuid.toString(), hash, now, ip, ip, now);
                return new Account(name.toLowerCase(Locale.ROOT), name, uuid.toString(), hash, null, 0, 0, 0);
            }), acc -> {
                busy.remove(uuid);
                if (!p.isOnline()) return;
                accounts.put(uuid, acc);
                Msg.ok(p, "auth.registered-ok", "Аккаунт зарегистрирован! Запомните пароль.");
                plugin.logs().log(p, "auth", "register", null, "ip=" + mask(ip));
                complete(p, ip, true);
            }, err -> {
                busy.remove(uuid);
                if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def, "n", maxAccountsPerIp);
                else Msg.error(p, "auth.error", "Ошибка регистрации. Попробуйте ещё раз.");
            });
        });
    }

    private void login(Player p, String[] a) {
        if (isAuthed(p)) { Msg.error(p, "auth.already", "Вы уже вошли."); return; }
        Account acc = accounts.get(p.getUniqueId());
        if (acc == null) { Msg.error(p, "auth.not-registered", "Аккаунт не зарегистрирован. Используйте /register <пароль> <пароль>"); return; }
        if (a.length != 1) { Msg.error(p, "auth.login-usage", "Использование: /login <пароль>"); return; }
        if (!busy.add(p.getUniqueId())) return;
        final String pw = a[0];
        final String ip = ip(p.getAddress() == null ? null : p.getAddress().getAddress());
        final UUID uuid = p.getUniqueId();
        Tasks.async(() -> {
            boolean ok = Passwords.verify(pw, acc.hash());
            String rehash = ok && Passwords.iterationsOf(acc.hash()) < iterations ? Passwords.hash(pw, iterations) : null;
            if (ok) {
                if (rehash != null) {
                    plugin.db().tx(c -> Database.update(c, "UPDATE accounts SET hash=? WHERE name_lower=?", rehash, acc.nameLower()));
                    accounts.put(uuid, new Account(acc.nameLower(), acc.name(), acc.uuid(), rehash, acc.sessionIp(), acc.sessionUntil(), 0, 0));
                }
                Tasks.sync(() -> {
                    busy.remove(uuid);
                    if (!p.isOnline()) return;
                    Msg.ok(p, "auth.login-ok", "Вы успешно вошли!");
                    complete(p, ip, true);
                });
                return;
            }
            Tasks.then(plugin.db().tx(c -> {
                long now = System.currentTimeMillis();
                Database.update(c, "UPDATE accounts SET failed = failed + 1 WHERE name_lower=?", acc.nameLower());
                Database.update(c, "INSERT INTO ip_attempts(ip, failed, locked_until, updated_at) VALUES (?,1,0,?) ON CONFLICT(ip) DO UPDATE SET failed = ip_attempts.failed + 1, updated_at = excluded.updated_at", ip, now);
                long accFailed = Database.longVal(c, "SELECT failed FROM accounts WHERE name_lower=?", acc.nameLower());
                long ipFailed = Database.longVal(c, "SELECT failed FROM ip_attempts WHERE ip=?", ip);
                boolean lock = false;
                long until = now + lockMinutes * 60_000L;
                if (accFailed >= maxAttempts) {
                    Database.update(c, "UPDATE accounts SET failed=0, locked_until=? WHERE name_lower=?", until, acc.nameLower());
                    lock = true;
                }
                if (ipFailed >= maxAttempts) {
                    Database.update(c, "UPDATE ip_attempts SET failed=0, locked_until=? WHERE ip=?", until, ip);
                    lock = true;
                }
                return new long[]{Math.max(accFailed, ipFailed), lock ? 1 : 0};
            }), r -> {
                busy.remove(uuid);
                plugin.logs().log(p, "auth", "login_fail", null, "ip=" + mask(ip) + " attempt=" + r[0]);
                if (!p.isOnline()) return;
                if (r[1] == 1) {
                    plugin.logs().suspicious(p, "login_lockout", "ip=" + mask(ip));
                    p.kick(Msg.comp("auth.locked-now", "&cСлишком много неверных попыток.\n&7Вход заблокирован на {m} мин.", "m", lockMinutes));
                } else {
                    Msg.error(p, "auth.wrong", "Неверный пароль. Осталось попыток: {n}", "n", Math.max(0, maxAttempts - r[0]));
                }
            }, err -> {
                busy.remove(uuid);
                Msg.error(p, "auth.error", "Ошибка проверки. Попробуйте ещё раз.");
            });
        });
    }

    public void changePassword(Player p, String old, String neu) {
        Account acc = accounts.get(p.getUniqueId());
        if (acc == null) {
            // Аккаунт мог быть загружен до /reload — читаем из БД.
            Tasks.then(plugin.db().query(c -> {
                try (PreparedStatement ps = Database.ps(c, "SELECT * FROM accounts WHERE name_lower=?", p.getName().toLowerCase(Locale.ROOT));
                     ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new Account(rs.getString("name_lower"), rs.getString("name"), rs.getString("uuid"), rs.getString("hash"),
                            rs.getString("session_ip"), rs.getLong("session_until"), rs.getInt("failed"), rs.getLong("locked_until"));
                }
            }), a2 -> {
                if (a2 == null) { Msg.error(p, "auth.not-registered", "Аккаунт не зарегистрирован."); return; }
                accounts.put(p.getUniqueId(), a2);
                changePassword(p, old, neu);
            }, null);
            return;
        }
        if (!validPassword(p, neu)) return;
        if (!busy.add(p.getUniqueId())) return;
        final String ip = ip(p.getAddress() == null ? null : p.getAddress().getAddress());
        Tasks.async(() -> {
            if (!Passwords.verify(old, acc.hash())) {
                Tasks.sync(() -> {
                    busy.remove(p.getUniqueId());
                    Msg.error(p, "auth.old-wrong", "Старый пароль указан неверно.");
                    plugin.logs().log(p, "auth", "changepw_fail", null, "");
                });
                return;
            }
            String hash = Passwords.hash(neu, iterations);
            long until = System.currentTimeMillis() + sessionDays * 86_400_000L;
            Tasks.then(plugin.db().tx(c -> Database.update(c,
                    "UPDATE accounts SET hash=?, session_ip=?, session_until=? WHERE name_lower=?", hash, ip, sessions ? until : 0, acc.nameLower())), n -> {
                busy.remove(p.getUniqueId());
                accounts.put(p.getUniqueId(), new Account(acc.nameLower(), acc.name(), acc.uuid(), hash, ip, until, 0, 0));
                Msg.ok(p, "auth.changed", "Пароль изменён. Все прежние сессии отозваны.");
                plugin.logs().log(p, "auth", "changepw", null, "");
            }, err -> {
                busy.remove(p.getUniqueId());
                Msg.error(p, "auth.error", "Ошибка смены пароля. Попробуйте ещё раз.");
            });
        });
    }

    /** Отзыв всех сессий аккаунта (смена пароля, восстановление, подозрительная активность). */
    public java.util.concurrent.CompletableFuture<Integer> revokeSessions(String name) {
        return plugin.db().tx(c -> Database.update(c, "UPDATE accounts SET session_ip=NULL, session_until=0 WHERE name_lower=?", name.toLowerCase(Locale.ROOT)));
    }

    /** Восстановление доступа администратором: новый временный пароль + отзыв сессий. */
    public java.util.concurrent.CompletableFuture<String> adminReset(String name) {
        String temp = Passwords.randomPassword(10);
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> Passwords.hash(temp, iterations))
                .thenCompose(hash -> plugin.db().tx(c -> {
                    int n = Database.update(c, "UPDATE accounts SET hash=?, session_ip=NULL, session_until=0, failed=0, locked_until=0 WHERE name_lower=?",
                            hash, name.toLowerCase(Locale.ROOT));
                    return n == 0 ? null : temp;
                }));
    }

    public java.util.concurrent.CompletableFuture<Integer> unlock(String name) {
        return plugin.db().tx(c -> {
            int n = Database.update(c, "UPDATE accounts SET failed=0, locked_until=0 WHERE name_lower=?", name.toLowerCase(Locale.ROOT));
            String ip = Database.strVal(c, "SELECT last_ip FROM accounts WHERE name_lower=?", name.toLowerCase(Locale.ROOT));
            if (ip != null) Database.update(c, "DELETE FROM ip_attempts WHERE ip=?", ip);
            return n;
        });
    }

    public java.util.concurrent.CompletableFuture<Integer> unregister(String name) {
        return plugin.db().tx(c -> Database.update(c, "DELETE FROM accounts WHERE name_lower=?", name.toLowerCase(Locale.ROOT)));
    }

    // ------------------------------------------------------------------ Ограничения до входа

    private boolean blocked(Player p) {
        return enabled && !authed.contains(p.getUniqueId());
    }

    private void cancelIf(Player p, Cancellable e) {
        if (p != null && blocked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void move(PlayerMoveEvent e) {
        if (!blocked(e.getPlayer())) return;
        Location f = e.getFrom(), t = e.getTo();
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) {
            Location back = f.clone();
            back.setYaw(t.getYaw());
            back.setPitch(t.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncChatEvent e) {
        if (blocked(e.getPlayer())) {
            e.setCancelled(true);
            Tasks.sync(() -> Msg.error(e.getPlayer(), "auth.need-login", "Сначала войдите: /login <пароль>"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void command(PlayerCommandPreprocessEvent e) {
        if (!blocked(e.getPlayer())) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (!ALLOWED_CMDS.contains(cmd)) {
            e.setCancelled(true);
            Msg.error(e.getPlayer(), "auth.need-login", "Сначала войдите: /login <пароль>");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST) public void interact(PlayerInteractEvent e) { cancelIf(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void interactEntity(PlayerInteractEntityEvent e) { cancelIf(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void drop(PlayerDropItemEvent e) { cancelIf(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void swap(PlayerSwapHandItemsEvent e) { cancelIf(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void consume(PlayerItemConsumeEvent e) { cancelIf(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void breakB(BlockBreakEvent e) { cancelIf(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void placeB(BlockPlaceEvent e) { cancelIf(e.getPlayer(), e); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void pickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) cancelIf(p, e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void inv(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) cancelIf(p, e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void invOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p) cancelIf(p, e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void damage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) cancelIf(p, e);
        if (e instanceof EntityDamageByEntityEvent ed && ed.getDamager() instanceof Player d) cancelIf(d, e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void food(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p) cancelIf(p, e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void target(EntityTargetEvent e) {
        if (e.getTarget() instanceof Player p && blocked(p)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ Утилиты

    public static String ip(InetAddress a) {
        if (a == null) return "unknown";
        if (a.isLoopbackAddress()) return "local";
        return a.getHostAddress();
    }

    /** Маскирование IP для журналов. */
    public static String mask(String ip) {
        if (ip == null) return "?";
        int i = ip.lastIndexOf('.');
        return i > 0 ? ip.substring(0, i) + ".*" : ip;
    }
}
