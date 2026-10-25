package ru.unknownsmp.admin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.db.Migrations;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Резервное копирование: БД (SQLite — VACUUM INTO, PostgreSQL — логический дамп), конфигурация и миры.
 * Автоматически каждые N часов, вручную — командой/GUI. Ротация, проверка целостности, отложенное восстановление.
 */
public final class BackupService implements Module {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private final UnknownSMP plugin;
    private final AtomicBoolean running = new AtomicBoolean();

    public BackupService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "backup"; }
    @Override public String title() { return "Резервное копирование"; }
    @Override public String[] depends() { return new String[]{"database"}; }

    @Override
    public void enable() {
        // Раньше первая копия делалась только через interval-hours после запуска — при частых перезапусках её не было никогда.
        // Теперь через 3 минуты после запуска и дальше каждые 10 минут проверяем, сколько прошло с последней удачной копии.
        Tasks.timer(20L * 60 * 3, 20L * 60 * 10, this::autoCheck);
        File dir = dir();
        dir.mkdirs();
        if (!new File(cfg().getString("backup.directory", "backups")).isAbsolute()) {
            plugin.getLogger().warning("[Резервные копии] Копии хранятся на том же диске, что и сервер (" + dir.getPath()
                    + "). Это не защищает от отказа диска — регулярно выгружайте папку backups во внешнее хранилище.");
        }
    }

    private void autoCheck() {
        if (running.get()) return;
        long hours = Math.max(1, cfg().getLong("backup.interval-hours", 6));
        if (System.currentTimeMillis() - newestOkTime() >= hours * 3_600_000L) create("auto", null);
    }

    /** Время последней удачной копии (0 — ни одной). */
    public long newestOkTime() {
        long best = 0;
        for (File f : list()) {
            File mf = new File(f, "manifest.properties");
            if (mf.exists() && !new File(f, "FAILED.txt").exists()) best = Math.max(best, mf.lastModified());
        }
        return best;
    }

    private static long folderSize(Path p) {
        try (var st = Files.walk(p)) {
            return st.filter(Files::isRegularFile).mapToLong(x -> { try { return Files.size(x); } catch (IOException e) { return 0; } }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Нужно ~2 размера миров (копия + архив). Если места мало — удаляем самые старые копии, но одну удачную оставляем. */
    private void ensureSpace(List<World> worlds, File target) throws IOException {
        long need = 0;
        for (World w : worlds) need += folderSize(w.getWorldFolder().toPath());
        need = need * 2 + 50L * 1048576;
        List<File> all = list();
        all.remove(target);
        for (int i = all.size() - 1; i >= 1 && dir().getUsableSpace() < need; i--) {
            deleteDir(all.get(i).toPath());
            plugin.getLogger().warning("[Резервные копии] Мало места — удалена старая копия " + all.get(i).getName());
        }
        long free = dir().getUsableSpace();
        if (free < need) {
            throw new IOException("мало места на диске: нужно ~" + need / 1048576 + " МБ, свободно " + free / 1048576
                    + " МБ. Уменьшите backup.keep, исключите лишние миры (backup.worlds) или освободите место на хостинге");
        }
    }

    private YamlConfiguration cfg() {
        return plugin.configs().get("logging.yml");
    }

    public File dir() {
        String d = cfg().getString("backup.directory", "backups");
        File f = new File(d);
        if (!f.isAbsolute()) f = new File(plugin.getDataFolder(), d);
        return f;
    }

    public boolean isRunning() { return running.get(); }

    // ------------------------------------------------------------------ Создание

    /** Полная резервная копия (асинхронно). */
    public void create(String label, CommandSender who) {
        if (!running.compareAndSet(false, true)) {
            if (who != null) Msg.error(who, "backup.running", "Резервное копирование уже выполняется.");
            return;
        }
        final String name = LocalDateTime.now(Text.zone()).format(TS) + "_" + label;
        final File target = new File(dir(), name);
        target.mkdirs();
        if (who != null) Msg.send(who, "backup.started", "&7Резервное копирование &f{n}&7 запущено...", "n", name);
        plugin.logs().sys("admin", "backup_start", null, name + (who == null ? "" : " by " + who.getName()));
        boolean worlds = cfg().getBoolean("backup.include-worlds", true);

        // 1. БД — в потоке записи (согласованный снимок относительно всех транзакций).
        CompletableFuture<Void> dbStep = plugin.db().tx(c -> {
            dumpDatabase(c, target);
            return null;
        });
        dbStep.whenComplete((v, err) -> {
            if (err != null) {
                finish(target, false, "ошибка БД: " + err.getMessage(), who);
                return;
            }
            Tasks.sync(() -> {
                // 2. Конфигурация и миры. Сохраняем миры в основном потоке, затем копируем асинхронно.
                List<World> list = new ArrayList<>();
                if (worlds) {
                    List<String> only = cfg().getStringList("backup.worlds");
                    for (World w : Bukkit.getWorlds()) if (only.isEmpty() || only.contains(w.getName())) list.add(w);
                    for (World w : list) {
                        w.save();
                        w.setAutoSave(false);
                    }
                }
                Tasks.async(() -> {
                    String error = null;
                    File staging = new File(target, "staging");
                    try {
                        zipConfigs(new File(target, "config.zip"));
                        if (!list.isEmpty()) ensureSpace(list, target);
                        if (!list.isEmpty()) {
                            for (World w : list) copyDir(w.getWorldFolder().toPath(), new File(staging, w.getName()).toPath());
                        }
                    } catch (Exception ex) {
                        error = ex.getMessage();
                    } finally {
                        Tasks.sync(() -> { for (World w : list) w.setAutoSave(true); });
                    }
                    try {
                        if (error == null && !list.isEmpty()) {
                            zipDir(staging.toPath(), new File(target, "worlds.zip"));
                        }
                    } catch (Exception ex) {
                        error = ex.getMessage();
                    } finally {
                        deleteDir(staging.toPath());
                    }
                    if (error == null) {
                        try {
                            writeManifest(target, label);
                            if (cfg().getBoolean("backup.verify-after-create", true)) {
                                String v2 = verify(target);
                                if (v2 != null) error = "проверка не пройдена: " + v2;
                            }
                        } catch (Exception ex) {
                            error = ex.getMessage();
                        }
                    }
                    finish(target, error == null, error == null ? "успешно (" + name + ", " + size(target) + ")" : error, who);
                });
            });
        });
    }

    private void finish(File target, boolean ok, String info, CommandSender who) {
        running.set(false);
        if (!ok) {
            // Недоделанные файлы только занимают место — оставляем лишь пояснение.
            File[] partial = target.listFiles();
            if (partial != null) for (File f : partial) if (f.isFile()) f.delete();
            try { Files.writeString(new File(target, "FAILED.txt").toPath(), info, StandardCharsets.UTF_8); } catch (IOException ignored) {}
            plugin.getLogger().warning("[Резервные копии] НЕ УДАЛОСЬ: " + info);
            Tasks.sync(() -> {
                for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers())
                    if (p != who && plugin.roles().has(p, "usmp.admin.backup")) Msg.error(p, "backup.failed", "Резервное копирование не удалось: {i}", "i", info);
            });
        } else {
            plugin.getLogger().info("[Резервные копии] Готово: " + info);
        }
        plugin.monitor().backupResult(ok, info);
        plugin.logs().sys("admin", ok ? "backup_ok" : "backup_fail", null, target.getName() + ": " + info);
        if (ok) rotate();
        Tasks.sync(() -> {
            if (who != null) {
                if (ok) Msg.ok(who, "backup.done", "Резервная копия создана: {i}", "i", info);
                else Msg.error(who, "backup.failed", "Резервное копирование не удалось: {i}", "i", info);
            }
        });
    }

    /** Только БД, синхронно (перед миграцией схемы при запуске). */
    public void backupDatabaseOnly(String label) throws Exception {
        String name = LocalDateTime.now(Text.zone()).format(TS) + "_" + label;
        File target = new File(dir(), name);
        target.mkdirs();
        plugin.db().txBlocking(c -> {
            dumpDatabase(c, target);
            return null;
        });
        writeManifest(target, label);
        String v = verify(target);
        if (v != null) throw new IllegalStateException("Резервная копия перед миграцией не прошла проверку: " + v);
        plugin.getLogger().info("Резервная копия перед миграцией: " + target.getPath());
    }

    private void dumpDatabase(Connection c, File target) throws Exception {
        if (plugin.db().dialect() == Database.Dialect.SQLITE) {
            File out = new File(target, "database.db");
            // VACUUM нельзя выполнять внутри транзакции. Мы в потоке записи, поэтому снимок согласован.
            boolean auto = c.getAutoCommit();
            if (!auto) { c.commit(); c.setAutoCommit(true); }
            try (PreparedStatement ps = c.prepareStatement("VACUUM INTO ?")) {
                ps.setString(1, out.getAbsolutePath());
                ps.execute();
            } finally {
                if (!auto) c.setAutoCommit(false);
            }
        } else {
            exportDump(c, new File(target, "database.dump.gz"));
        }
    }

    // ------------------------------------------------------------------ Логический дамп (переносимый между SQLite и PostgreSQL)

    public static void exportDump(Connection c, File out) throws Exception {
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(out)), StandardCharsets.UTF_8))) {
            w.write("#USMPDUMP 1\n");
            for (String t : Migrations.TABLES) {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + t)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    StringBuilder cols = new StringBuilder();
                    for (int i = 1; i <= n; i++) { if (i > 1) cols.append('\t'); cols.append(md.getColumnName(i).toLowerCase()); }
                    w.write("#TABLE " + t + "\n#COLS " + cols + "\n");
                    while (rs.next()) {
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= n; i++) {
                            if (i > 1) row.append('\t');
                            Object o = rs.getObject(i);
                            if (o == null) row.append('N');
                            else if (o instanceof byte[] b) row.append('B').append(Base64.getEncoder().encodeToString(b));
                            else if (o instanceof Integer || o instanceof Long || o instanceof Short) row.append('L').append(o);
                            else if (o instanceof Number num) {
                                int type = md.getColumnType(i);
                                if (type == Types.DOUBLE || type == Types.FLOAT || type == Types.REAL || type == Types.NUMERIC || type == Types.DECIMAL)
                                    row.append('D').append(num.doubleValue());
                                else row.append('L').append(num.longValue());
                            } else row.append('S').append(Base64.getEncoder().encodeToString(o.toString().getBytes(StandardCharsets.UTF_8)));
                        }
                        w.write(row.toString());
                        w.write('\n');
                    }
                } catch (java.sql.SQLException e) {
                    // Таблица может отсутствовать в старой схеме — пропускаем.
                    w.write("#SKIP " + t + "\n");
                }
            }
            w.write("#END\n");
        }
    }

    /** Импорт дампа: таблицы очищаются и заполняются из дампа (в одной транзакции). */
    public static long importDump(InputStream in, Connection c) throws Exception {
        long rows = 0;
        boolean ended = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
            String header = r.readLine();
            if (header == null || !header.startsWith("#USMPDUMP")) throw new IOException("Неверный формат дампа");
            String table = null;
            String[] cols = null;
            PreparedStatement ps = null;
            int batch = 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#TABLE ")) {
                    if (ps != null) { ps.executeBatch(); ps.close(); ps = null; }
                    table = line.substring(7).trim();
                    if (!Arrays.asList(Migrations.TABLES).contains(table)) throw new IOException("Неизвестная таблица в дампе: " + table);
                    try (Statement st = c.createStatement()) { st.executeUpdate("DELETE FROM " + table); }
                    continue;
                }
                if (line.startsWith("#COLS ")) {
                    cols = line.substring(6).split("\t");
                    for (String col : cols) if (!col.matches("[a-z_]+")) throw new IOException("Недопустимое имя столбца: " + col);
                    String q = "?,".repeat(cols.length);
                    ps = c.prepareStatement("INSERT INTO " + table + " (" + String.join(",", cols) + ") VALUES (" + q.substring(0, q.length() - 1) + ")");
                    continue;
                }
                if (line.startsWith("#SKIP")) continue;
                if (line.startsWith("#END")) { ended = true; break; }
                if (ps == null || cols == null) throw new IOException("Повреждённый дамп: строка без таблицы");
                String[] vals = line.split("\t", -1);
                if (vals.length != cols.length) throw new IOException("Повреждённый дамп: неверное число столбцов в " + table);
                for (int i = 0; i < vals.length; i++) {
                    String v = vals[i];
                    char t = v.isEmpty() ? 'N' : v.charAt(0);
                    String body = v.length() > 1 ? v.substring(1) : "";
                    switch (t) {
                        case 'N' -> ps.setObject(i + 1, null);
                        case 'L' -> ps.setLong(i + 1, Long.parseLong(body));
                        case 'D' -> ps.setDouble(i + 1, Double.parseDouble(body));
                        case 'B' -> ps.setBytes(i + 1, Base64.getDecoder().decode(body));
                        default -> ps.setString(i + 1, new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8));
                    }
                }
                ps.addBatch();
                rows++;
                if (++batch >= 1000) { ps.executeBatch(); batch = 0; }
            }
            if (ps != null) { ps.executeBatch(); ps.close(); }
        }
        if (!ended) throw new IOException("Дамп обрезан (нет маркера #END)");
        return rows;
    }

    // ------------------------------------------------------------------ Файлы

    private void zipConfigs(File out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            File[] files = plugin.getDataFolder().listFiles((d, n) -> n.endsWith(".yml"));
            if (files == null) return;
            for (File f : files) {
                zos.putNextEntry(new ZipEntry(f.getName()));
                Files.copy(f.toPath(), zos);
                zos.closeEntry();
            }
        }
    }

    private static void copyDir(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(d).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                if (f.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                Files.copy(f, dst.resolve(src.relativize(f).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void zipDir(Path root, File out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    zos.putNextEntry(new ZipEntry(root.relativize(f).toString().replace('\\', '/')));
                    Files.copy(f, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    static void deleteDir(Path p) {
        if (!Files.exists(p)) return;
        try {
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Files.delete(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private void writeManifest(File target, String label) throws Exception {
        Properties p = new Properties();
        p.setProperty("created", String.valueOf(System.currentTimeMillis()));
        p.setProperty("label", label);
        p.setProperty("minecraft", Bukkit.getMinecraftVersion());
        p.setProperty("plugin", plugin.getPluginMeta().getVersion());
        p.setProperty("dialect", String.valueOf(plugin.db().dialect()));
        p.setProperty("schema", String.valueOf(Migrations.latest()));
        File[] files = target.listFiles();
        if (files != null) for (File f : files) {
            if (f.isFile() && !f.getName().equals("manifest.properties")) p.setProperty("sha256." + f.getName(), sha256(f));
        }
        try (var out = new FileOutputStream(new File(target, "manifest.properties"))) {
            p.store(out, "ALEX SMP backup");
        }
    }

    // ------------------------------------------------------------------ Проверка

    /** Проверка целостности и пробное восстановление во временный файл. null — всё в порядке. */
    public String verify(File target) {
        try {
            File mf = new File(target, "manifest.properties");
            if (!mf.exists()) return "нет manifest.properties";
            Properties p = new Properties();
            try (var in = new FileInputStream(mf)) { p.load(in); }
            for (String k : p.stringPropertyNames()) {
                if (!k.startsWith("sha256.")) continue;
                File f = new File(target, k.substring(7));
                if (!f.exists()) return "отсутствует файл " + f.getName();
                if (!sha256(f).equals(p.getProperty(k))) return "контрольная сумма не совпадает: " + f.getName();
            }
            File db = new File(target, "database.db");
            if (db.exists()) {
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
                     Statement st = c.createStatement()) {
                    try (ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                        if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) return "SQLite integrity_check не пройден";
                    }
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM players")) { rs.next(); }
                }
            }
            File dump = new File(target, "database.dump.gz");
            if (dump.exists()) {
                // Пробное восстановление в память (SQLite in-memory со схемой) — проверяет читаемость всего дампа.
                try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
                    try (Statement st = c.createStatement()) {
                        st.execute("CREATE TABLE usmp_schema (version INTEGER NOT NULL)");
                    }
                    createSchemaSqlite(c);
                    c.setAutoCommit(false);
                    try (InputStream in = new FileInputStream(dump)) { importDump(in, c); }
                    c.rollback();
                }
            }
            for (String z : new String[]{"worlds.zip", "config.zip"}) {
                File zf = new File(target, z);
                if (!zf.exists()) continue;
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zf))) {
                    byte[] buf = new byte[1 << 16];
                    ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        while (zis.read(buf) > 0) { /* проверка CRC при чтении */ }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void createSchemaSqlite(Connection c) throws Exception {
        java.lang.reflect.Field f = Migrations.class.getDeclaredField("MIGRATIONS");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String[]> list = (List<String[]>) f.get(null);
        try (Statement st = c.createStatement()) {
            for (String[] stmts : list) for (String s : stmts)
                st.execute(s.replace("{ID}", "INTEGER PRIMARY KEY AUTOINCREMENT").replace("{BLOB}", "BLOB"));
        }
    }

    public CompletableFuture<String> verifyAsync(String name) {
        return CompletableFuture.supplyAsync(() -> {
            File t = new File(dir(), name);
            if (!t.isDirectory()) return "копия не найдена";
            return verify(t);
        });
    }

    // ------------------------------------------------------------------ Ротация и список

    public List<File> list() {
        File[] fs = dir().listFiles(File::isDirectory);
        List<File> out = new ArrayList<>();
        if (fs != null) for (File f : fs) if (!f.getName().startsWith("pre-restore")) out.add(f);
        out.sort((a, b) -> b.getName().compareTo(a.getName()));
        return out;
    }

    private void rotate() {
        int keep = Math.max(2, cfg().getInt("backup.keep", 12));
        List<File> all = list();
        for (int i = keep; i < all.size(); i++) {
            deleteDir(all.get(i).toPath());
            plugin.getLogger().info("Ротация резервных копий: удалена " + all.get(i).getName());
        }
    }

    private static String size(File dir) {
        long total = 0;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) total += f.length();
        return total / 1048576 + " МБ";
    }

    // ------------------------------------------------------------------ Восстановление

    /** Запланировать восстановление при следующем запуске сервера. parts: db, worlds, configs. */
    public void scheduleRestore(String name, List<String> parts) throws IOException {
        File t = new File(dir(), name);
        if (!t.isDirectory()) throw new IOException("Копия не найдена: " + name);
        String v = verify(t);
        if (v != null) throw new IOException("Копия не прошла проверку: " + v);
        Properties p = new Properties();
        p.setProperty("backup", t.getAbsolutePath());
        p.setProperty("parts", String.join(",", parts));
        p.setProperty("scheduled", String.valueOf(System.currentTimeMillis()));
        try (var out = new FileOutputStream(new File(plugin.getDataFolder(), "restore.pending"))) {
            p.store(out, "Restore will be applied on next server start");
        }
    }

    public void cancelRestore() {
        new File(plugin.getDataFolder(), "restore.pending").delete();
    }

    public boolean restorePending() {
        return new File(plugin.getDataFolder(), "restore.pending").exists();
    }

    /**
     * Применяется в onLoad (до загрузки миров). Текущие данные не удаляются, а перемещаются в pre-restore-*.
     * Импорт в PostgreSQL выполняется позже, после подключения к БД.
     */
    public static void applyPendingRestore(UnknownSMP pl) throws Exception {
        File marker = new File(pl.getDataFolder(), "restore.pending");
        if (!marker.exists()) return;
        Properties p = new Properties();
        try (var in = new FileInputStream(marker)) { p.load(in); }
        File backup = new File(p.getProperty("backup", ""));
        List<String> parts = Arrays.asList(p.getProperty("parts", "db").split(","));
        if (!backup.isDirectory()) {
            pl.getLogger().severe("Восстановление отменено: папка копии не найдена " + backup);
            marker.renameTo(new File(pl.getDataFolder(), "restore.failed"));
            return;
        }
        File safety = new File(backup.getParentFile(), "pre-restore-" + LocalDateTime.now().format(TS));
        safety.mkdirs();
        pl.getLogger().warning("=== ВОССТАНОВЛЕНИЕ из " + backup.getName() + " (" + parts + "). Текущие данные сохраняются в " + safety.getName() + " ===");
        File worldContainer = Bukkit.getWorldContainer();
        if (parts.contains("worlds")) {
            File wz = new File(backup, "worlds.zip");
            if (wz.exists()) {
                List<String> tops = new ArrayList<>();
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(wz))) {
                    ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        String top = e.getName().split("/")[0];
                        if (!tops.contains(top)) tops.add(top);
                    }
                }
                for (String w : tops) {
                    File cur = new File(worldContainer, w);
                    if (cur.exists()) Files.move(cur.toPath(), new File(safety, w).toPath());
                }
                unzip(wz, worldContainer);
                pl.getLogger().warning("Миры восстановлены: " + tops);
            }
        }
        if (parts.contains("configs")) {
            File cz = new File(backup, "config.zip");
            if (cz.exists()) {
                File cfgSafety = new File(safety, "configs");
                cfgSafety.mkdirs();
                File[] ymls = pl.getDataFolder().listFiles((d, n) -> n.endsWith(".yml"));
                if (ymls != null) for (File y : ymls) Files.copy(y.toPath(), new File(cfgSafety, y.getName()).toPath());
                unzip(cz, pl.getDataFolder());
            }
        }
        if (parts.contains("db")) {
            YamlConfiguration dbc = YamlConfiguration.loadConfiguration(new File(pl.getDataFolder(), "database.yml"));
            boolean sqlite = !dbc.getString("type", "sqlite").equalsIgnoreCase("postgresql");
            File dbFile = new File(backup, "database.db");
            if (sqlite && dbFile.exists()) {
                File cur = new File(pl.getDataFolder(), dbc.getString("sqlite.file", "data.db"));
                for (String suf : new String[]{"", "-wal", "-shm"}) {
                    File f = new File(cur.getPath() + suf);
                    if (f.exists()) Files.move(f.toPath(), new File(safety, f.getName()).toPath());
                }
                Files.copy(dbFile.toPath(), cur.toPath());
                pl.getLogger().warning("База SQLite восстановлена из копии.");
            } else {
                // Импорт после подключения (PostgreSQL, или SQLite из дампа).
                Properties imp = new Properties();
                imp.setProperty("backup", backup.getAbsolutePath());
                imp.setProperty("safety", safety.getAbsolutePath());
                try (var out = new FileOutputStream(new File(pl.getDataFolder(), "db-import.pending"))) { imp.store(out, null); }
            }
        }
        marker.renameTo(new File(safety, "restore.applied"));
    }

    /** Импорт БД из копии после подключения (вызывается при запуске до миграций). */
    public static void applyPendingImport(UnknownSMP pl, Database db) throws Exception {
        File marker = new File(pl.getDataFolder(), "db-import.pending");
        if (!marker.exists()) return;
        Properties p = new Properties();
        try (var in = new FileInputStream(marker)) { p.load(in); }
        File backup = new File(p.getProperty("backup"));
        File safety = new File(p.getProperty("safety"));
        // Страховочный дамп текущей БД.
        db.txBlocking(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS usmp_schema (version INTEGER NOT NULL)");
            }
            if (Migrations.currentVersion(db) > 0) exportDump(c, new File(safety, "current-db.dump.gz"));
            return null;
        });
        Migrations.migrate(pl, db); // гарантирует наличие таблиц для импорта
        File dump = new File(backup, "database.dump.gz");
        File sqlite = new File(backup, "database.db");
        File tmp = null;
        if (!dump.exists() && sqlite.exists()) {
            tmp = File.createTempFile("usmp-import", ".dump.gz");
            try (Connection src = DriverManager.getConnection("jdbc:sqlite:" + sqlite.getAbsolutePath())) {
                exportDump(src, tmp);
            }
            dump = tmp;
        }
        final File source = dump;
        long rows = db.txBlocking(c -> {
            try (InputStream in = new FileInputStream(source)) {
                return importDump(in, c);
            }
        });
        if (tmp != null) tmp.delete();
        if (db.dialect() == Database.Dialect.POSTGRESQL) resetSequences(db);
        marker.renameTo(new File(safety, "db-import.applied"));
        pl.getLogger().warning("Импорт БД из копии завершён: " + rows + " строк.");
    }

    private static void resetSequences(Database db) throws Exception {
        db.txBlocking(c -> {
            for (String t : Migrations.TABLES) {
                try (Statement st = c.createStatement()) {
                    st.execute("SELECT setval(pg_get_serial_sequence('" + t + "', 'id'), COALESCE((SELECT MAX(id) FROM " + t + "), 0) + 1, false)");
                } catch (Exception ignored) {
                    // таблица без столбца id
                }
            }
            return null;
        });
    }

    private static void unzip(File zip, File dest) throws IOException {
        Path root = dest.toPath().toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) throw new IOException("Недопустимый путь в архиве: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
