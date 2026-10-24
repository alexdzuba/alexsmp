package ru.unknownsmp.zhora;

import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Хранилище Жоры: память об игроках, летопись SMP, жалобы, дела с доказательствами, история общения.
 * Все операции асинхронные (очереди базы данных плагина), SQL только с параметрами.
 */
public final class ZhoraStore {
    public record Memory(long id, String uuid, String kind, String key, String text, long at, String source, String confidence, String related, boolean active, int count) {}

    public record Chron(long id, long at, String type, String actor, String actorName, String target, String targetName, String world, Integer x, Integer y, Integer z, String summary, String data) {}

    public record Complaint(long id, long at, String reporter, String reporterName, String target, String targetName, String category, String message, String state, String note) {}

    public record Case(long id, long at, long updatedAt, String target, String targetName, String category, int priority, String state, String summary, String aiSummary) {}

    public record Evidence(long id, long caseId, long at, String detector, int severity, String details, boolean confirmed) {}

    public record Query(List<String> uuids, java.util.Set<String> types, long from, long to, String like, int limit) {}

    private final UnknownSMP plugin;
    private final ConcurrentLinkedQueue<Chron> chronQueue = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Boolean> muted = new ConcurrentHashMap<>();

    public ZhoraStore(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    private Database db() { return plugin.db(); }

    // ------------------------------------------------------------------ Старт

    public CompletableFuture<Void> loadMutes() {
        return db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM zhora_mute"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try { muted.put(UUID.fromString(rs.getString(1)), true); } catch (IllegalArgumentException ignored) { }
                }
            }
            return null;
        });
    }

    public boolean isMuted(UUID u) { return muted.containsKey(u); }

    public CompletableFuture<Void> setMuted(UUID u, boolean value, String by) {
        if (value) muted.put(u, true); else muted.remove(u);
        return db().tx(c -> {
            if (value) {
                Database.update(c, "DELETE FROM zhora_mute WHERE uuid=?", u.toString());
                Database.update(c, "INSERT INTO zhora_mute(uuid, at, by) VALUES (?,?,?)", u.toString(), System.currentTimeMillis(), by);
            } else Database.update(c, "DELETE FROM zhora_mute WHERE uuid=?", u.toString());
            return null;
        });
    }

    // ------------------------------------------------------------------ Память

    /**
     * Запомнить. Если key задан и такая запись уже есть — увеличивает счётчик и обновляет текст (не плодит дубли).
     * kind: fact (факт из игры), quote (слова игрока), assumption (предположение), joke (прошлая шутка), alias (прозвище).
     */
    public CompletableFuture<Integer> remember(UUID u, String kind, String key, String text, String source, String confidence, String related, int max) {
        long now = System.currentTimeMillis();
        String t = text.length() > 300 ? text.substring(0, 300) : text;
        return db().tx(c -> {
            int count = 1;
            boolean updated = false;
            if (key != null) {
                Long cur = Database.longVal(c, "SELECT count FROM zhora_memory WHERE uuid=? AND mkey=?", u.toString(), key);
                if (cur != null) {
                    count = (int) (cur + 1);
                    Database.update(c, "UPDATE zhora_memory SET count=?, at=?, text=?, active=1 WHERE uuid=? AND mkey=?", count, now, t, u.toString(), key);
                    updated = true;
                }
            }
            if (!updated) {
                Database.insert(c, "INSERT INTO zhora_memory(uuid, kind, mkey, text, at, source, confidence, related, active, count, last_used) VALUES (?,?,?,?,?,?,?,?,1,1,0)",
                        u.toString(), kind, key, t, now, source, confidence, related);
            }
            // Ограничение размера: оставляем самые свежие/частые записи.
            Long total = Database.longVal(c, "SELECT COUNT(*) FROM zhora_memory WHERE uuid=? AND kind<>'alias'", u.toString());
            if (total != null && total > max) {
                Database.update(c, "DELETE FROM zhora_memory WHERE id IN (SELECT id FROM zhora_memory WHERE uuid=? AND kind<>'alias' ORDER BY active ASC, kind='joke' DESC, count ASC, at ASC LIMIT ?)",
                        u.toString(), (int) (total - max));
            }
            return count;
        });
    }

    public CompletableFuture<List<Memory>> memories(UUID u, boolean onlyActive, int limit) {
        return db().query(c -> {
            List<Memory> out = new ArrayList<>();
            try (PreparedStatement ps = Database.ps(c, "SELECT id, uuid, kind, mkey, text, at, source, confidence, related, active, count FROM zhora_memory WHERE uuid=? "
                    + (onlyActive ? "AND active=1 " : "") + "ORDER BY at DESC LIMIT ?", u.toString(), limit); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Memory(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getInt(10) == 1, rs.getInt(11)));
            }
            return out;
        });
    }

    public CompletableFuture<Integer> deleteMemories(UUID u, Long id) {
        return db().tx(c -> id == null ? Database.update(c, "DELETE FROM zhora_memory WHERE uuid=?", u.toString())
                : Database.update(c, "DELETE FROM zhora_memory WHERE uuid=? AND id=?", u.toString(), id));
    }

    public CompletableFuture<Integer> setMemoryActive(UUID u, long id, boolean active) {
        return db().tx(c -> Database.update(c, "UPDATE zhora_memory SET active=? WHERE uuid=? AND id=?", active ? 1 : 0, u.toString(), id));
    }

    /** Прозвища: прозвище(lower) → uuid. */
    public CompletableFuture<Map<String, String>> aliases() {
        return db().query(c -> {
            Map<String, String> out = new java.util.HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, text FROM zhora_memory WHERE kind='alias' AND active=1"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(2).toLowerCase(java.util.Locale.ROOT), rs.getString(1));
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ Летопись

    public void chronicle(String type, UUID actor, String actorName, UUID target, String targetName, String world, Integer x, Integer y, Integer z, String summary, String data) {
        chronQueue.add(new Chron(0, System.currentTimeMillis(), type, actor == null ? null : actor.toString(), actorName, target == null ? null : target.toString(), targetName,
                world, x, y, z, summary == null ? "" : (summary.length() > 400 ? summary.substring(0, 400) : summary), data));
    }

    public void flushChronicle() {
        if (chronQueue.isEmpty()) return;
        List<Chron> batch = new ArrayList<>();
        Chron c;
        while ((c = chronQueue.poll()) != null && batch.size() < 500) batch.add(c);
        db().tx(conn -> {
            for (Chron ch : batch) {
                Database.insert(conn, "INSERT INTO chronicle(at, type, actor, actor_name, target, target_name, world, x, y, z, summary, data) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                        ch.at(), ch.type(), ch.actor(), ch.actorName(), ch.target(), ch.targetName(), ch.world(), ch.x(), ch.y(), ch.z(), ch.summary(), ch.data());
            }
            return null;
        }).exceptionally(t -> {
            chronQueue.addAll(batch);
            return null;
        });
    }

    public CompletableFuture<List<Chron>> search(Query q) {
        return db().query(c -> {
            StringBuilder sql = new StringBuilder("SELECT id, at, type, actor, actor_name, target, target_name, world, x, y, z, summary, data FROM chronicle WHERE at>=? AND at<=?");
            List<Object> args = new ArrayList<>();
            args.add(q.from());
            args.add(q.to());
            if (q.types() != null && !q.types().isEmpty()) {
                sql.append(" AND type IN (").append("?,".repeat(q.types().size()), 0, q.types().size() * 2 - 1).append(")");
                args.addAll(q.types());
            }
            if (q.uuids() != null && !q.uuids().isEmpty()) {
                String ph = "?,".repeat(q.uuids().size());
                ph = ph.substring(0, ph.length() - 1);
                sql.append(" AND (actor IN (").append(ph).append(") OR target IN (").append(ph).append("))");
                args.addAll(q.uuids());
                args.addAll(q.uuids());
            }
            if (q.like() != null && !q.like().isBlank()) {
                sql.append(" AND LOWER(summary) LIKE ?");
                args.add("%" + q.like().toLowerCase(java.util.Locale.ROOT).replace("%", "").replace("_", "") + "%");
            }
            sql.append(" ORDER BY at DESC LIMIT ?");
            args.add(Math.max(1, Math.min(50, q.limit())));
            List<Chron> out = new ArrayList<>();
            try (PreparedStatement ps = Database.ps(c, sql.toString(), args.toArray()); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Chron(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            (Integer) rs.getObject(9), (Integer) rs.getObject(10), (Integer) rs.getObject(11), rs.getString(12), rs.getString(13)));
                }
            }
            return out;
        });
    }

    /** Сколько записей типа type где игрок actor/target, начиная с момента since. */
    public CompletableFuture<Long> count(String type, UUID u, boolean asTarget, long since) {
        return db().query(c -> {
            Long v = Database.longVal(c, "SELECT COUNT(*) FROM chronicle WHERE type=? AND " + (asTarget ? "target" : "actor") + "=? AND at>=?", type, u.toString(), since);
            return v == null ? 0L : v;
        });
    }

    // ------------------------------------------------------------------ Жалобы и дела

    public CompletableFuture<Long> addComplaint(UUID reporter, String reporterName, UUID target, String targetName, String category, String message) {
        return db().tx(c -> Database.insert(c, "INSERT INTO zhora_complaints(at, reporter, reporter_name, target, target_name, category, message, state) VALUES (?,?,?,?,?,?,?, 'new')",
                System.currentTimeMillis(), reporter.toString(), reporterName, target.toString(), targetName, category, message.length() > 300 ? message.substring(0, 300) : message));
    }

    public CompletableFuture<List<Complaint>> complaints(String state, UUID target, int limit) {
        return db().query(c -> {
            List<Complaint> out = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT id, at, reporter, reporter_name, target, target_name, category, message, state, note FROM zhora_complaints WHERE 1=1");
            List<Object> args = new ArrayList<>();
            if (state != null) { sql.append(" AND state=?"); args.add(state); }
            if (target != null) { sql.append(" AND target=?"); args.add(target.toString()); }
            sql.append(" ORDER BY at DESC LIMIT ?");
            args.add(limit);
            try (PreparedStatement ps = Database.ps(c, sql.toString(), args.toArray()); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Complaint(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getString(10)));
            }
            return out;
        });
    }

    public CompletableFuture<Integer> setComplaintState(long id, String state, String note) {
        return db().tx(c -> Database.update(c, "UPDATE zhora_complaints SET state=?, note=? WHERE id=?", state, note, id));
    }

    /** Открыть дело (или вернуть существующее открытое по игроку и категории). */
    public CompletableFuture<Long> openCase(UUID target, String targetName, String category, int priority, String summary) {
        long now = System.currentTimeMillis();
        return db().tx(c -> {
            Long id = Database.longVal(c, "SELECT id FROM zhora_cases WHERE target=? AND category=? AND state='open' ORDER BY at DESC LIMIT 1", target.toString(), category);
            if (id != null) {
                Database.update(c, "UPDATE zhora_cases SET updated_at=?, priority=CASE WHEN priority>? THEN priority ELSE ? END WHERE id=?", now, priority, priority, id);
                return id;
            }
            return Database.insert(c, "INSERT INTO zhora_cases(at, updated_at, target, target_name, category, priority, state, summary) VALUES (?,?,?,?,?,?, 'open', ?)",
                    now, now, target.toString(), targetName, category, priority, summary);
        });
    }

    public CompletableFuture<Void> addEvidence(long caseId, String detector, int severity, String details, boolean confirmed) {
        long now = System.currentTimeMillis();
        return db().tx(c -> {
            Database.insert(c, "INSERT INTO zhora_evidence(case_id, at, detector, severity, details, confirmed) VALUES (?,?,?,?,?,?)",
                    caseId, now, detector, severity, details.length() > 500 ? details.substring(0, 500) : details, confirmed ? 1 : 0);
            Database.update(c, "UPDATE zhora_cases SET updated_at=?, priority=CASE WHEN priority>? THEN priority ELSE ? END WHERE id=?", now, severity, severity, caseId);
            return null;
        });
    }

    public CompletableFuture<List<Case>> cases(String state, UUID target, int limit) {
        return db().query(c -> {
            List<Case> out = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT id, at, updated_at, target, target_name, category, priority, state, summary, ai_summary FROM zhora_cases WHERE 1=1");
            List<Object> args = new ArrayList<>();
            if (state != null) { sql.append(" AND state=?"); args.add(state); }
            if (target != null) { sql.append(" AND target=?"); args.add(target.toString()); }
            sql.append(" ORDER BY priority DESC, updated_at DESC LIMIT ?");
            args.add(limit);
            try (PreparedStatement ps = Database.ps(c, sql.toString(), args.toArray()); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Case(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8),
                        rs.getString(9), rs.getString(10)));
            }
            return out;
        });
    }

    public CompletableFuture<List<Evidence>> evidence(long caseId, int limit) {
        return db().query(c -> {
            List<Evidence> out = new ArrayList<>();
            try (PreparedStatement ps = Database.ps(c, "SELECT id, case_id, at, detector, severity, details, confirmed FROM zhora_evidence WHERE case_id=? ORDER BY at DESC LIMIT ?", caseId, limit);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Evidence(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getInt(5), rs.getString(6), rs.getInt(7) == 1));
            }
            return out;
        });
    }

    public CompletableFuture<Integer> setCaseState(long id, String state) {
        return db().tx(c -> Database.update(c, "UPDATE zhora_cases SET state=?, updated_at=? WHERE id=?", state, System.currentTimeMillis(), id));
    }

    public CompletableFuture<Integer> setCaseAi(long id, String ai) {
        return db().tx(c -> Database.update(c, "UPDATE zhora_cases SET ai_summary=?, updated_at=? WHERE id=?", ai, System.currentTimeMillis(), id));
    }

    /** Сумма серьёзности подозрений детекторов (не жалоб) за последние сутки по игроку. */
    public CompletableFuture<Integer> recentSeverity(UUID target, long since) {
        return db().query(c -> {
            Long v = Database.longVal(c, "SELECT COALESCE(SUM(e.severity),0) FROM zhora_evidence e JOIN zhora_cases cs ON cs.id=e.case_id WHERE cs.target=? AND e.at>=? AND e.detector NOT LIKE 'complaint%'",
                    target.toString(), since);
            return v == null ? 0 : v.intValue();
        });
    }

    // ------------------------------------------------------------------ История общения

    public void addChat(UUID uuid, String name, String role, String text) {
        long now = System.currentTimeMillis();
        db().tx(c -> Database.insert(c, "INSERT INTO zhora_chat(at, uuid, name, role, text) VALUES (?,?,?,?,?)", now, uuid == null ? null : uuid.toString(), name, role,
                text.length() > 400 ? text.substring(0, 400) : text));
    }

    public CompletableFuture<List<String[]>> recentChat(int limit) {
        return db().query(c -> {
            List<String[]> out = new ArrayList<>();
            try (PreparedStatement ps = Database.ps(c, "SELECT role, name, text FROM zhora_chat ORDER BY id DESC LIMIT ?", limit); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
            }
            java.util.Collections.reverse(out);
            return out;
        });
    }

    // ------------------------------------------------------------------ Очистка

    public CompletableFuture<int[]> cleanup(int chronicleDays, int memoryExpireDays, int inactiveKeepDays) {
        long now = System.currentTimeMillis();
        long day = 86_400_000L;
        return db().tx(c -> {
            int a = Database.update(c, "DELETE FROM chronicle WHERE at<?", now - chronicleDays * day);
            int b = Database.update(c, "DELETE FROM zhora_memory WHERE kind<>'alias' AND at<?", now - memoryExpireDays * day);
            int d = Database.update(c, "DELETE FROM zhora_memory WHERE active=0 AND at<?", now - inactiveKeepDays * day);
            Database.update(c, "DELETE FROM zhora_chat WHERE id NOT IN (SELECT id FROM zhora_chat ORDER BY id DESC LIMIT 300)");
            Database.update(c, "DELETE FROM zhora_evidence WHERE at<?", now - 90 * day);
            Database.update(c, "DELETE FROM zhora_cases WHERE state<>'open' AND updated_at<?", now - 120 * day);
            return new int[]{a, b + d};
        });
    }
}
