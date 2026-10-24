package ru.unknownsmp.db;

import ru.unknownsmp.core.UnknownSMP;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

/**
 * Версионные миграции схемы. Порядок: проверить версию → резервная копия → миграция в транзакции →
 * проверка → запись новой версии. Миграции никогда не удаляют данные.
 */
public final class Migrations {
    private Migrations() {}

    /** Список миграций: индекс 0 = версия 1. */
    private static final List<String[]> MIGRATIONS = List.of(
            new String[]{
                    "CREATE TABLE IF NOT EXISTS accounts (name_lower TEXT PRIMARY KEY, name TEXT NOT NULL, uuid TEXT NOT NULL, hash TEXT NOT NULL, created_at BIGINT, reg_ip TEXT, last_ip TEXT, last_login BIGINT, session_ip TEXT, session_until BIGINT DEFAULT 0, failed INTEGER DEFAULT 0, locked_until BIGINT DEFAULT 0)",
                    "CREATE TABLE IF NOT EXISTS ip_attempts (ip TEXT PRIMARY KEY, failed INTEGER DEFAULT 0, locked_until BIGINT DEFAULT 0, updated_at BIGINT)",
                    "CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT, name_lower TEXT, balance BIGINT NOT NULL DEFAULT 0, reputation BIGINT DEFAULT 0, xp BIGINT DEFAULT 0, level INTEGER DEFAULT 0, prog_rank TEXT, title TEXT, staff_role TEXT DEFAULT 'player', playtime BIGINT DEFAULT 0, first_join BIGINT, last_seen BIGINT, settings TEXT, last_loc TEXT, chat_mode TEXT, cosmetic TEXT, combat_flag INTEGER DEFAULT 0)",
                    "CREATE INDEX IF NOT EXISTS idx_players_name ON players(name_lower)",
                    "CREATE INDEX IF NOT EXISTS idx_players_balance ON players(balance)",
                    "CREATE TABLE IF NOT EXISTS player_stats (uuid TEXT NOT NULL, stat TEXT NOT NULL, value BIGINT DEFAULT 0, PRIMARY KEY(uuid, stat))",
                    "CREATE INDEX IF NOT EXISTS idx_stats_stat ON player_stats(stat, value)",
                    "CREATE TABLE IF NOT EXISTS unlocks (uuid TEXT NOT NULL, kind TEXT NOT NULL, ukey TEXT NOT NULL, at BIGINT, PRIMARY KEY(uuid, kind, ukey))",
                    "CREATE TABLE IF NOT EXISTS activity (uuid TEXT NOT NULL, day INTEGER NOT NULL, seconds BIGINT DEFAULT 0, PRIMARY KEY(uuid, day))",
                    "CREATE TABLE IF NOT EXISTS homes (uuid TEXT NOT NULL, name TEXT NOT NULL, loc TEXT NOT NULL, created_at BIGINT, PRIMARY KEY(uuid, name))",
                    "CREATE TABLE IF NOT EXISTS money_log (id {ID}, at BIGINT, uuid TEXT, delta BIGINT, balance_after BIGINT, reason TEXT, ref TEXT)",
                    "CREATE INDEX IF NOT EXISTS idx_money_uuid ON money_log(uuid, at)",
                    "CREATE TABLE IF NOT EXISTS auction_lots (id {ID}, seller TEXT NOT NULL, seller_name TEXT, item {BLOB} NOT NULL, material TEXT, amount INTEGER, price BIGINT NOT NULL, created_at BIGINT)",
                    "CREATE INDEX IF NOT EXISTS idx_lots_seller ON auction_lots(seller)",
                    "CREATE TABLE IF NOT EXISTS orders (id {ID}, owner TEXT NOT NULL, owner_name TEXT, side TEXT NOT NULL, material TEXT NOT NULL, qty_total INTEGER, qty_left INTEGER, price BIGINT NOT NULL, created_at BIGINT, suspended INTEGER DEFAULT 0, note TEXT)",
                    "CREATE INDEX IF NOT EXISTS idx_orders_mat ON orders(material, side)",
                    "CREATE TABLE IF NOT EXISTS mail (id {ID}, owner TEXT NOT NULL, item {BLOB} NOT NULL, source TEXT, created_at BIGINT)",
                    "CREATE INDEX IF NOT EXISTS idx_mail_owner ON mail(owner)",
                    "CREATE TABLE IF NOT EXISTS price_state (item TEXT PRIMARY KEY, net DOUBLE PRECISION DEFAULT 0, updated_at BIGINT)",
                    "CREATE TABLE IF NOT EXISTS clans (id {ID}, tag TEXT NOT NULL, tag_lower TEXT NOT NULL UNIQUE, name TEXT, leader TEXT, bank BIGINT DEFAULT 0, home TEXT, created_at BIGINT, perms TEXT, wars_won INTEGER DEFAULT 0, wars_lost INTEGER DEFAULT 0)",
                    "CREATE TABLE IF NOT EXISTS clan_members (uuid TEXT PRIMARY KEY, clan_id BIGINT NOT NULL, role TEXT, joined_at BIGINT)",
                    "CREATE TABLE IF NOT EXISTS clan_allies (a BIGINT NOT NULL, b BIGINT NOT NULL, PRIMARY KEY(a, b))",
                    "CREATE TABLE IF NOT EXISTS clan_bank_log (id {ID}, clan_id BIGINT, uuid TEXT, delta BIGINT, at BIGINT, reason TEXT)",
                    "CREATE INDEX IF NOT EXISTS idx_clanbank ON clan_bank_log(clan_id, at)",
                    "CREATE TABLE IF NOT EXISTS wars (id {ID}, attacker BIGINT, defender BIGINT, started_at BIGINT, ends_at BIGINT, score_a INTEGER DEFAULT 0, score_d INTEGER DEFAULT 0, state TEXT, winner BIGINT DEFAULT 0, peace_from BIGINT DEFAULT 0, overtime_until BIGINT DEFAULT 0)",
                    "CREATE TABLE IF NOT EXISTS war_blocks (war_id BIGINT NOT NULL, pos TEXT NOT NULL, PRIMARY KEY(war_id, pos))",
                    "CREATE TABLE IF NOT EXISTS unique_items (uid TEXT PRIMARY KEY, type TEXT, status TEXT, created_at BIGINT, holder TEXT, note TEXT)",
                    "CREATE INDEX IF NOT EXISTS idx_unique_type ON unique_items(type, status)",
                    "CREATE TABLE IF NOT EXISTS world_state (k TEXT PRIMARY KEY, v TEXT)",
                    "CREATE TABLE IF NOT EXISTS quest_progress (uuid TEXT NOT NULL, quest TEXT NOT NULL, period TEXT NOT NULL, state TEXT, progress BIGINT DEFAULT 0, updated_at BIGINT, PRIMARY KEY(uuid, quest, period))",
                    "CREATE TABLE IF NOT EXISTS bounties (id {ID}, target TEXT, target_name TEXT, placer TEXT, placer_name TEXT, amount BIGINT, created_at BIGINT, state TEXT, claimed_by TEXT)",
                    "CREATE TABLE IF NOT EXISTS event_history (id {ID}, type TEXT, started_at BIGINT, ended_at BIGINT, success INTEGER, loc TEXT, participants INTEGER, details TEXT)",
                    "CREATE TABLE IF NOT EXISTS collective (uuid TEXT NOT NULL, item TEXT NOT NULL, amount BIGINT DEFAULT 0, PRIMARY KEY(uuid, item))",
                    "CREATE TABLE IF NOT EXISTS punishments (id {ID}, target TEXT, target_name TEXT, ptype TEXT, reason TEXT, author TEXT, created_at BIGINT, expires_at BIGINT, active INTEGER, removed_by TEXT, removed_at BIGINT, remove_reason TEXT, ip TEXT)",
                    "CREATE INDEX IF NOT EXISTS idx_pun_target ON punishments(target, active)",
                    "CREATE TABLE IF NOT EXISTS reports (id {ID}, reporter TEXT, reporter_name TEXT, target TEXT, target_name TEXT, reason TEXT, context TEXT, created_at BIGINT, state TEXT, handled_by TEXT, handled_at BIGINT)",
                    "CREATE TABLE IF NOT EXISTS block_log (id {ID}, at BIGINT, uuid TEXT, name TEXT, action TEXT, world TEXT, x INTEGER, y INTEGER, z INTEGER, old_data TEXT, new_data TEXT, extra {BLOB}, rolled_back INTEGER DEFAULT 0)",
                    "CREATE INDEX IF NOT EXISTS idx_block_pos ON block_log(world, x, z, y)",
                    "CREATE INDEX IF NOT EXISTS idx_block_uuid ON block_log(uuid, at)",
                    "CREATE INDEX IF NOT EXISTS idx_block_at ON block_log(at)",
                    "CREATE TABLE IF NOT EXISTS action_log (id {ID}, at BIGINT, uuid TEXT, name TEXT, category TEXT, action TEXT, world TEXT, x INTEGER, y INTEGER, z INTEGER, details TEXT)",
                    "CREATE INDEX IF NOT EXISTS idx_action_uuid ON action_log(uuid, at)",
                    "CREATE INDEX IF NOT EXISTS idx_action_cat ON action_log(category, at)",
                    "CREATE TABLE IF NOT EXISTS parkour (uuid TEXT NOT NULL, course TEXT NOT NULL, best_ms BIGINT, PRIMARY KEY(uuid, course))",
            },
            new String[]{
                    // v2: режим дуэлей — инвентарь SMP хранится в БД, пока игрок находится в мире дуэлей.
                    "CREATE TABLE IF NOT EXISTS duel_stash (uuid TEXT PRIMARY KEY, data {BLOB} NOT NULL, xp_level INTEGER, xp_progress DOUBLE PRECISION, health DOUBLE PRECISION, food INTEGER, created_at BIGINT)",
            },
            new String[]{
                    // v3: дневные лимиты скупки на игрока и предмет (защита от ферм и абуза).
                    "CREATE TABLE IF NOT EXISTS sell_quota (uuid TEXT NOT NULL, day INTEGER NOT NULL, item TEXT NOT NULL, coins DOUBLE PRECISION DEFAULT 0, PRIMARY KEY(uuid, day, item))",
                    "CREATE INDEX IF NOT EXISTS idx_sell_quota_day ON sell_quota(day)",
            },
            new String[]{
                    // v4: Жора — память об игроках, летопись SMP, жалобы, дела и доказательства, история общения.
                    "CREATE TABLE IF NOT EXISTS zhora_memory (id {ID}, uuid TEXT NOT NULL, kind TEXT NOT NULL, mkey TEXT, text TEXT NOT NULL, at BIGINT, source TEXT, confidence TEXT, related TEXT, active INTEGER DEFAULT 1, count INTEGER DEFAULT 1, last_used BIGINT DEFAULT 0)",
                    "CREATE INDEX IF NOT EXISTS idx_zmem_uuid ON zhora_memory(uuid, active)",
                    "CREATE TABLE IF NOT EXISTS zhora_mute (uuid TEXT PRIMARY KEY, at BIGINT, by TEXT)",
                    "CREATE TABLE IF NOT EXISTS chronicle (id {ID}, at BIGINT NOT NULL, type TEXT NOT NULL, actor TEXT, actor_name TEXT, target TEXT, target_name TEXT, world TEXT, x INTEGER, y INTEGER, z INTEGER, summary TEXT, data TEXT)",
                    "CREATE INDEX IF NOT EXISTS idx_chron_at ON chronicle(at)",
                    "CREATE INDEX IF NOT EXISTS idx_chron_type ON chronicle(type, at)",
                    "CREATE INDEX IF NOT EXISTS idx_chron_actor ON chronicle(actor, at)",
                    "CREATE INDEX IF NOT EXISTS idx_chron_target ON chronicle(target, at)",
                    "CREATE TABLE IF NOT EXISTS zhora_complaints (id {ID}, at BIGINT NOT NULL, reporter TEXT, reporter_name TEXT, target TEXT, target_name TEXT, category TEXT, message TEXT, state TEXT DEFAULT 'new', note TEXT)",
                    "CREATE INDEX IF NOT EXISTS idx_zcomp_target ON zhora_complaints(target, at)",
                    "CREATE TABLE IF NOT EXISTS zhora_cases (id {ID}, at BIGINT NOT NULL, updated_at BIGINT, target TEXT NOT NULL, target_name TEXT, category TEXT NOT NULL, priority INTEGER DEFAULT 1, state TEXT DEFAULT 'open', summary TEXT, ai_summary TEXT)",
                    "CREATE INDEX IF NOT EXISTS idx_zcase_target ON zhora_cases(target, state)",
                    "CREATE TABLE IF NOT EXISTS zhora_evidence (id {ID}, case_id BIGINT NOT NULL, at BIGINT NOT NULL, detector TEXT, severity INTEGER DEFAULT 0, details TEXT, confirmed INTEGER DEFAULT 0)",
                    "CREATE INDEX IF NOT EXISTS idx_zev_case ON zhora_evidence(case_id, at)",
                    "CREATE TABLE IF NOT EXISTS zhora_chat (id {ID}, at BIGINT NOT NULL, uuid TEXT, name TEXT, role TEXT NOT NULL, text TEXT NOT NULL)",
            },
            new String[]{
                    // v5: территории кланов, точки заработка кланов, камни заработка.
                    "CREATE TABLE IF NOT EXISTS clan_claims (id {ID}, clan_id BIGINT NOT NULL, world TEXT NOT NULL, x1 INTEGER NOT NULL, z1 INTEGER NOT NULL, x2 INTEGER NOT NULL, z2 INTEGER NOT NULL, created_at BIGINT, cost BIGINT)",
                    "CREATE INDEX IF NOT EXISTS idx_claims_clan ON clan_claims(clan_id)",
                    "CREATE TABLE IF NOT EXISTS clan_points (id TEXT PRIMARY KEY, world TEXT NOT NULL, x INTEGER, y INTEGER, z INTEGER, radius INTEGER, owner BIGINT DEFAULT 0, captured_at BIGINT DEFAULT 0)",
                    "CREATE TABLE IF NOT EXISTS money_stones (id {ID}, owner TEXT NOT NULL, tier TEXT NOT NULL, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, placed_at BIGINT, earned BIGINT DEFAULT 0)",
                    "CREATE INDEX IF NOT EXISTS idx_stones_owner ON money_stones(owner)",
            }
    );

    public static int latest() { return MIGRATIONS.size(); }

    public static int currentVersion(Database db) throws Exception {
        return db.queryBlocking(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS usmp_schema (version INTEGER NOT NULL)");
            }
            Long v = Database.longVal(c, "SELECT MAX(version) FROM usmp_schema");
            return v == null ? 0 : v.intValue();
        });
    }

    /** Применяет недостающие миграции. Перед изменением существующей схемы создаётся резервная копия. */
    public static void migrate(UnknownSMP plugin, Database db) throws Exception {
        int current = currentVersion(db);
        int target = latest();
        if (current > target) {
            throw new IllegalStateException("Версия схемы БД (" + current + ") новее плагина (" + target
                    + "). Установите более новую версию плагина или восстановите резервную копию.");
        }
        if (current == target) return;
        if (current > 0) {
            plugin.getLogger().info("Обновление схемы БД " + current + " → " + target + ". Создаю резервную копию...");
            plugin.backups().backupDatabaseOnly("pre-migration-v" + current);
        }
        for (int v = current + 1; v <= target; v++) {
            final int ver = v;
            String[] stmts = MIGRATIONS.get(v - 1);
            db.txBlocking(c -> {
                try (Statement st = c.createStatement()) {
                    for (String s : stmts) st.execute(db.sql(s));
                }
                verify(c, ver);
                Database.update(c, "INSERT INTO usmp_schema(version) VALUES (?)", ver);
                return null;
            });
            plugin.getLogger().info("Миграция схемы v" + v + " применена.");
        }
    }

    private static void verify(Connection c, int ver) throws Exception {
        // Проверка: ключевые таблицы читаются.
        if (ver == 1) {
            for (String t : new String[]{"accounts", "players", "mail", "auction_lots", "clans", "block_log"}) {
                try (Statement st = c.createStatement()) {
                    st.executeQuery("SELECT COUNT(*) FROM " + t).close();
                }
            }
        }
    }

    /** Таблицы, входящие в логический дамп (для резервного копирования PostgreSQL и переноса). */
    public static final String[] TABLES = {"usmp_schema", "accounts", "ip_attempts", "players", "player_stats", "unlocks",
            "activity", "homes", "money_log", "auction_lots", "orders", "mail", "price_state", "clans", "clan_members",
            "clan_allies", "clan_bank_log", "wars", "war_blocks", "unique_items", "world_state", "quest_progress",
            "bounties", "event_history", "collective", "punishments", "reports", "block_log", "action_log", "parkour", "duel_stash", "sell_quota",
            "zhora_memory", "zhora_mute", "chronicle", "zhora_complaints", "zhora_cases", "zhora_evidence", "zhora_chat",
            "clan_claims", "clan_points", "money_stones"};
}
