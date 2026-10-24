package ru.unknownsmp.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.unknownsmp.core.UnknownSMP;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Доступ к SQL. База — единственный источник истины.
 * Все записи выполняются последовательно в одном потоке записи внутри транзакций,
 * чтение — в отдельном пуле. Основной поток сервера никогда не ждёт сеть БД,
 * кроме запуска плагина и асинхронной предзагрузки при входе игрока.
 */
public final class Database {
    public enum Dialect { SQLITE, POSTGRESQL }

    private final UnknownSMP plugin;
    private HikariDataSource ds;
    private Dialect dialect;
    private File sqliteFile;
    private ExecutorService writer;
    private ExecutorService readers;

    private final AtomicLong ops = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();
    private final AtomicLong maxNanos = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger queued = new AtomicInteger();
    private volatile String lastError;

    public Database(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    public void connect(YamlConfiguration cfg) throws Exception {
        String type = cfg.getString("type", "sqlite").toLowerCase();
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("UnknownSMP");
        if (type.equals("postgresql")) {
            dialect = Dialect.POSTGRESQL;
            hc.setDriverClassName("org.postgresql.Driver");
            String host = cfg.getString("postgresql.host", "localhost");
            int port = cfg.getInt("postgresql.port", 5432);
            String db = cfg.getString("postgresql.database", "unknownsmp");
            hc.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + db
                    + (cfg.getBoolean("postgresql.ssl", false) ? "?sslmode=require" : ""));
            hc.setUsername(cfg.getString("postgresql.user", "unknownsmp"));
            hc.setPassword(cfg.getString("postgresql.password", ""));
            hc.setMaximumPoolSize(Math.max(3, cfg.getInt("postgresql.pool-size", 8)));
        } else {
            dialect = Dialect.SQLITE;
            hc.setDriverClassName("org.sqlite.JDBC");
            sqliteFile = new File(plugin.getDataFolder(), cfg.getString("sqlite.file", "data.db"));
            hc.setJdbcUrl("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
            hc.setMaximumPoolSize(4);
            hc.setConnectionInitSql("PRAGMA busy_timeout=10000");
        }
        hc.setConnectionTimeout(cfg.getLong("connection-timeout-ms", 10000));
        hc.setAutoCommit(true);
        ds = new HikariDataSource(hc);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            if (dialect == Dialect.SQLITE) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=FULL");
                st.execute("PRAGMA foreign_keys=ON");
            } else {
                st.execute("SELECT 1");
            }
        }
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "USMP-DB-Writer");
            t.setDaemon(false);
            return t;
        });
        readers = Executors.newFixedThreadPool(dialect == Dialect.SQLITE ? 2 : 4, r -> {
            Thread t = new Thread(r, "USMP-DB-Reader");
            t.setDaemon(true);
            return t;
        });
    }

    public Dialect dialect() { return dialect; }

    public File sqliteFile() { return sqliteFile; }

    /** Подстановка диалектных токенов в SQL. */
    public String sql(String s) {
        if (dialect == Dialect.POSTGRESQL) {
            return s.replace("{ID}", "BIGSERIAL PRIMARY KEY").replace("{BLOB}", "BYTEA");
        }
        return s.replace("{ID}", "INTEGER PRIMARY KEY AUTOINCREMENT").replace("{BLOB}", "BLOB");
    }

    /** Транзакция записи (последовательно, атомарно). */
    public <T> CompletableFuture<T> tx(SqlFn<T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        if (writer == null || writer.isShutdown()) {
            f.completeExceptionally(new SQLException("База данных недоступна"));
            return f;
        }
        queued.incrementAndGet();
        writer.execute(() -> {
            queued.decrementAndGet();
            try {
                f.complete(runTx(fn));
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /** Чтение (без гарантии порядка относительно записей, если вызвано одновременно). */
    public <T> CompletableFuture<T> query(SqlFn<T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        if (readers == null || readers.isShutdown()) {
            f.completeExceptionally(new SQLException("База данных недоступна"));
            return f;
        }
        readers.execute(() -> {
            try {
                f.complete(run(fn));
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /** Блокирующая транзакция. Нельзя вызывать из основного потока во время игры. */
    public <T> T txBlocking(SqlFn<T> fn) throws Exception {
        if (Bukkit.isPrimaryThread() && plugin.isFullyStarted()) {
            throw new IllegalStateException("Блокирующая запись из основного потока запрещена");
        }
        if (Thread.currentThread().getName().equals("USMP-DB-Writer")) return runTx(fn);
        try {
            return tx(fn).get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    public <T> T queryBlocking(SqlFn<T> fn) throws Exception {
        return run(fn);
    }

    private <T> T runTx(SqlFn<T> fn) throws Exception {
        long start = System.nanoTime();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                T r = fn.apply(c);
                c.commit();
                success(start);
                return r;
            } catch (Throwable t) {
                try { c.rollback(); } catch (SQLException ignored) {}
                fail(t);
                throw t;
            } finally {
                try { c.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            fail(e);
            throw e;
        }
    }

    private <T> T run(SqlFn<T> fn) throws Exception {
        long start = System.nanoTime();
        try (Connection c = ds.getConnection()) {
            T r = fn.apply(c);
            success(start);
            return r;
        } catch (Exception e) {
            fail(e);
            throw e;
        }
    }

    private void success(long start) {
        long d = System.nanoTime() - start;
        ops.incrementAndGet();
        totalNanos.addAndGet(d);
        maxNanos.accumulateAndGet(d, Math::max);
        consecutiveFailures.set(0);
    }

    private void fail(Throwable t) {
        if (t instanceof TxAbort) return; // логический отказ, не сбой БД
        failures.incrementAndGet();
        lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
        int n = consecutiveFailures.incrementAndGet();
        if (n == 5 && plugin.monitor() != null) plugin.monitor().alert("Пять подряд ошибок базы данных: " + lastError);
    }

    public void close() {
        if (writer != null) {
            writer.shutdown();
            try {
                if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                    plugin.getLogger().severe("Поток записи БД не завершился за 30 секунд!");
                }
            } catch (InterruptedException ignored) {}
        }
        if (readers != null) readers.shutdownNow();
        if (ds != null) ds.close();
    }

    public boolean isConnected() { return ds != null && !ds.isClosed(); }

    // ---- Статистика для мониторинга ----
    public long ops() { return ops.get(); }
    public double avgMs() { long o = ops.get(); return o == 0 ? 0 : totalNanos.get() / 1e6 / o; }
    public double maxMs() { return maxNanos.get() / 1e6; }
    public long failures() { return failures.get(); }
    public int queued() { return queued.get(); }
    public String lastError() { return lastError; }
    public void resetMax() { maxNanos.set(0); }

    // ---- Помощники ----
    public static PreparedStatement ps(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        bind(ps, args);
        return ps;
    }

    public static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof byte[] b) ps.setBytes(i + 1, b);
            else if (a instanceof Boolean b) ps.setInt(i + 1, b ? 1 : 0);
            else if (a instanceof java.util.UUID u) ps.setString(i + 1, u.toString());
            else ps.setObject(i + 1, a);
        }
    }

    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = ps(c, sql, args)) {
            return ps.executeUpdate();
        }
    }

    public static long insert(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1;
    }

    public static Long longVal(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = ps(c, sql, args); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
            return null;
        }
    }

    public static String strVal(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = ps(c, sql, args); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** Логический отказ транзакции (недостаточно средств и т.п.) — откат без записи сбоя. */
    public static final class TxAbort extends RuntimeException {
        public final String key;
        public final String def;

        public TxAbort(String key, String def) {
            super(def, null, false, false);
            this.key = key;
            this.def = def;
        }
    }
}
