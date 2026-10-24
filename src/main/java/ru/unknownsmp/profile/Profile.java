package ru.unknownsmp.profile;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш данных игрока. Баланс здесь — только зеркало значения из БД, обновляемое после успешной транзакции.
 * Прочие поля (репутация, опыт, статистика, настройки) сохраняются периодически.
 */
public final class Profile {
    public final UUID uuid;
    public String name;
    public volatile long balance;
    public long reputation;
    public long xp;
    public int level;
    public String progRank;
    public String title;
    public String staffRole = "player";
    public long playtime; // секунды
    public long firstJoin;
    public long lastSeen;
    public String lastLoc;
    public String chatMode = "global";
    public String cosmetic;
    public boolean combatFlag;
    public final Map<String, Boolean> settings = new HashMap<>();
    public final Map<String, Long> stats = new ConcurrentHashMap<>();
    public final Set<String> dirtyStats = ConcurrentHashMap.newKeySet();
    public final Set<String> unlocks = ConcurrentHashMap.newKeySet();
    public final Map<String, String> homes = new LinkedHashMap<>();
    public long muteUntil;
    public String muteReason;
    public long tradeBlockUntil;
    public boolean dirty;
    public long pendingActivity; // секунды, ещё не записанные в activity
    public final Set<String> tmp = new HashSet<>();

    public Profile(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public boolean setting(String key) {
        return settings.getOrDefault(key, true);
    }

    public void setting(String key, boolean v) {
        settings.put(key, v);
        dirty = true;
    }

    public long stat(String s) {
        return stats.getOrDefault(s, 0L);
    }

    public boolean has(String kind, String key) {
        return unlocks.contains(kind + ":" + key);
    }

    public boolean isMuted() {
        return muteUntil != 0 && (muteUntil < 0 || muteUntil > System.currentTimeMillis());
    }

    public boolean isTradeBlocked() {
        return tradeBlockUntil != 0 && (tradeBlockUntil < 0 || tradeBlockUntil > System.currentTimeMillis());
    }

    public String settingsString() {
        StringBuilder sb = new StringBuilder();
        for (var e : settings.entrySet()) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue() ? 1 : 0);
        }
        return sb.toString();
    }

    public void parseSettings(String s) {
        settings.clear();
        if (s == null || s.isBlank()) return;
        for (String part : s.split(";")) {
            String[] kv = part.split("=");
            if (kv.length == 2) settings.put(kv[0], kv[1].equals("1"));
        }
    }
}
