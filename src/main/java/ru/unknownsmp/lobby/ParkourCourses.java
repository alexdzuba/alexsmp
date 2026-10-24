package ru.unknownsmp.lobby;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Раскладка трёх паркуров лобби. Генерация детерминирована (фиксированное зерно), поэтому строитель мира и
 * сервис паркура получают одинаковые координаты без хранения в базе.
 */
public final class ParkourCourses {
    private ParkourCourses() {}

    public enum Kind { NORMAL, START, CHECKPOINT, FINISH }

    /** Площадка: (x, y, z) — минимальный угол, size — сторона квадрата. Плита ставится в (x+size/2, y+1, z+size/2). */
    public record Jump(int x, int y, int z, int size, Kind kind, int index) {
        public int px() { return x + size / 2; }
        public int pz() { return z + size / 2; }
    }

    public record Course(String id, String title, String color, long minMs, int dx, int dz, List<Jump> jumps) {
        public Jump start() { return jumps.get(0); }
        public Jump finish() { return jumps.get(jumps.size() - 1); }
    }

    public static final int FLOOR_Y = 100;
    private static final Map<String, Course> COURSES = new HashMap<>();
    public static final List<String> IDS = List.of("easy", "medium", "hard");

    static {
        COURSES.put("easy", gen("easy", "Лёгкий", "&a", 12_000, -1, 0, -49, -2, 28, 7001L));
        COURSES.put("medium", gen("medium", "Средний", "&e", 22_000, 1, 0, 45, -2, 36, 7002L));
        COURSES.put("hard", gen("hard", "Сложный", "&c", 36_000, 0, 1, 30, 31, 40, 7003L));
    }

    public static Course get(String id) { return COURSES.get(id); }

    public static List<Course> all() { return IDS.stream().map(COURSES::get).toList(); }

    private static Course gen(String id, String title, String color, long minMs, int dx, int dz, int sx, int sz, int count, long seed) {
        Random r = new Random(seed);
        List<Jump> out = new ArrayList<>();
        int y = FLOOR_Y;
        int x = sx, z = sz;
        out.add(new Jump(x, y, z, 5, Kind.START, 0));
        int prevSize = 5;
        // Положение боковой оси относительно начала (чтобы трасса не уползала в сторону).
        int driftBase = dx != 0 ? sz : sx;
        int lateralSign = 1;
        for (int i = 1; i <= count; i++) {
            boolean last = i == count;
            int size = last ? 5 : switch (id) {
                case "easy" -> 2;
                case "medium" -> r.nextInt(3) == 0 ? 2 : 1;
                default -> 1;
            };
            int gap, lat, dy;
            switch (id) {
                case "easy" -> { gap = 1 + r.nextInt(2); lat = r.nextInt(3) - 1; dy = r.nextInt(5) == 0 ? 1 : 0; }
                case "medium" -> {
                    gap = 2 + r.nextInt(2);
                    lat = r.nextInt(5) - 2;
                    dy = r.nextInt(3) - 1;
                    if (gap == 3) { lat = Math.max(-1, Math.min(1, lat)); if (dy > 0) dy = 0; }
                    else if (dy > 0) gap = 2;
                }
                default -> {
                    gap = 3;
                    lat = r.nextInt(5) - 2;
                    dy = r.nextInt(3) - 1;
                    if (r.nextInt(8) == 0 && dy <= 0 && Math.abs(lat) <= 1) { gap = 4; lat = 0; }
                    if (dy > 0) { gap = 3; lat = Math.max(-1, Math.min(1, lat)); }
                }
            }
            if (i == 1 || last) { gap = Math.min(gap, 3); lat = 0; dy = 0; }
            // Ограничение дрейфа вбок: не дальше 3 блоков от оси.
            int cur = dx != 0 ? z : x;
            if (Math.abs(cur + lat - driftBase) > (id.equals("easy") ? 4 : 3)) lat = -Integer.signum(cur - driftBase) * Math.abs(lat);
            lat *= lateralSign;
            if (r.nextInt(4) == 0) lateralSign = -lateralSign;
            Kind k = last ? Kind.FINISH : (i % 8 == 0 ? Kind.CHECKPOINT : Kind.NORMAL);
            if (k == Kind.CHECKPOINT) size = Math.max(size, 2);
            // Шаг вдоль направления от края до края.
            if (dx > 0) x = x + prevSize + gap;
            else if (dx < 0) x = x - gap - size;
            else if (dz > 0) z = z + prevSize + gap;
            else z = z - gap - size;
            if (dx != 0) z += lat; else x += lat;
            y += dy;
            out.add(new Jump(x, y, z, size, k, i));
            prevSize = size;
        }
        return new Course(id, title, color, minMs, dx, dz, out);
    }
}
