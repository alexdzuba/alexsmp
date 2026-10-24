package ru.unknownsmp.world;

import org.bukkit.Material;

import java.util.Random;

/**
 * Набор архитектурных приёмов для построек деревни: крыши, окна со ставнями, двери с крыльцом, трубы, фонарные столбы,
 * садовые мелочи. Все методы работают в локальных координатах холста: x — вправо, y — вверх, z — вглубь здания
 * (фасад на z = 0, зритель снаружи при z &lt; 0).
 */
public final class Kit {
    private Kit() {}

    // ------------------------------------------------------------------ Крыши

    /** Двускатная крыша, конёк вдоль z. Ширина (x1-x0+1) должна быть нечётной. ov — свес по краям (0 или 1). */
    public static void gable(Canvas c, int x0, int x1, int z0, int z1, int y0, Material stair, Material ridge, Material gableWall, int ov) {
        for (int k = 0; ; k++) {
            int l = x0 - ov + k, r = x1 + ov - k;
            if (l > r) break;
            int y = y0 + k;
            for (int z = z0 - ov; z <= z1 + ov; z++) {
                if (l == r) c.set(l, y, z, ridge);
                else {
                    c.stair(l, y, z, stair, 1, 0, false);
                    c.stair(r, y, z, stair, -1, 0, false);
                }
            }
            for (int x = l + 1; x <= r - 1; x++) { c.set(x, y, z0, gableWall); c.set(x, y, z1, gableWall); }
            // Нижняя кромка свеса: перевёрнутые ступени под скатом.
            if (ov > 0 && k == 0) {
                for (int z = z0 - ov; z <= z1 + ov; z++) {
                    c.stair(l, y - 1, z, stair, -1, 0, true);
                    c.stair(r, y - 1, z, stair, 1, 0, true);
                }
            }
        }
    }

    /** Двускатная крыша с коньком вдоль x (глубина z0..z1 нечётная). */
    public static void gableX(Canvas c, int x0, int x1, int z0, int z1, int y0, Material stair, Material ridge, Material gableWall, int ov) {
        for (int k = 0; ; k++) {
            int t = z0 - ov + k, b = z1 + ov - k;
            if (t > b) break;
            int y = y0 + k;
            for (int x = x0 - ov; x <= x1 + ov; x++) {
                if (t == b) c.set(x, y, t, ridge);
                else {
                    c.stair(x, y, t, stair, 0, 1, false);
                    c.stair(x, y, b, stair, 0, -1, false);
                }
            }
            for (int z = t + 1; z <= b - 1; z++) { c.set(x0, y, z, gableWall); c.set(x1, y, z, gableWall); }
            if (ov > 0 && k == 0) {
                for (int x = x0 - ov; x <= x1 + ov; x++) {
                    c.stair(x, y - 1, t, stair, 0, -1, true);
                    c.stair(x, y - 1, b, stair, 0, 1, true);
                }
            }
        }
    }

    /** Шатровая крыша над прямоугольником. */
    public static void hip(Canvas c, int x0, int x1, int z0, int z1, int y0, Material stair, Material block, int ov) {
        for (int k = 0; ; k++) {
            int l = x0 - ov + k, r = x1 + ov - k, t = z0 - ov + k, b = z1 + ov - k;
            if (l > r || t > b) break;
            int y = y0 + k;
            if (r - l <= 1 || b - t <= 1) {
                for (int x = l; x <= r; x++) for (int z = t; z <= b; z++) c.set(x, y, z, block);
                break;
            }
            for (int x = l + 1; x <= r - 1; x++) { c.stair(x, y, t, stair, 0, 1, false); c.stair(x, y, b, stair, 0, -1, false); }
            for (int z = t + 1; z <= b - 1; z++) { c.stair(l, y, z, stair, 1, 0, false); c.stair(r, y, z, stair, -1, 0, false); }
            c.set(l, y, t, block);
            c.set(r, y, t, block);
            c.set(l, y, b, block);
            c.set(r, y, b, block);
        }
    }

    /** Коническая крыша башни с центром (cx, cz). r0 — радиус нижнего яруса. Возвращает высоту верхушки. */
    public static int cone(Canvas c, int cx, int cz, int r0, int y0, Material stair, Material block) {
        int y = y0;
        for (int rr = r0; rr >= 0; rr--, y++) {
            for (int dx = -rr - 1; dx <= rr + 1; dx++) for (int dz = -rr - 1; dz <= rr + 1; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > rr + 0.45 || d <= rr - 0.8 && rr > 0) continue;
                if (rr == 0) { c.set(cx, y, cz, block); continue; }
                int ax = Math.abs(dx), az = Math.abs(dz);
                if (ax >= az) c.stair(cx + dx, y, cz + dz, stair, dx > 0 ? -1 : 1, 0, false);
                else c.stair(cx + dx, y, cz + dz, stair, 0, dz > 0 ? -1 : 1, false);
            }
            // Заполнение потолка яруса.
            if (rr > 0) for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) if (Math.hypot(dx, dz) <= rr - 0.8) c.set(cx + dx, y, cz + dz, block);
        }
        return y;
    }

    // ------------------------------------------------------------------ Окна, двери

    /** Окно 1×2 на стене в плоскости z со ставнями и цветочным ящиком. out — направление наружу (dz: -1 для фасада, +1 для задней стены). */
    public static void window(Canvas c, int x, int y, int z, int out, Material glass, Material shutter, boolean shutters, boolean box) {
        c.set(x, y, z, glass);
        c.set(x, y + 1, z, glass);
        if (shutters) {
            for (int s : new int[]{-1, 1}) {
                c.trapdoor(x + s, y, z + out, shutter, 0, out, false, true);
                c.trapdoor(x + s, y + 1, z + out, shutter, 0, out, false, true);
            }
        }
        if (box) {
            c.slab(x, y - 1, z + out, Material.DARK_OAK_SLAB, true);
            c.leaves(x, y, z + out, Material.FLOWERING_AZALEA_LEAVES);
        }
    }

    /** Арочное окно 2×3: рама из ступенек сверху. */
    public static void archWindow(Canvas c, int x, int y, int z, Material glass, Material frameStair) {
        c.set(x, y, z, glass);
        c.set(x, y + 1, z, glass);
        c.set(x, y + 2, z, Material.AIR);
        c.stair(x, y + 2, z, frameStair, 0, 0, true);
        c.stair(x - 1, y + 2, z, frameStair, 1, 0, true);
        c.stair(x + 1, y + 2, z, frameStair, -1, 0, true);
        c.set(x - 1, y, z, glass);
        c.set(x - 1, y + 1, z, glass);
        c.set(x + 1, y, z, glass);
        c.set(x + 1, y + 1, z, glass);
    }

    /** Дверь с наличником, фонарём и ступенями. out — наружу (-1 фасад). */
    public static void door(Canvas c, int x, int z, int out, Material door, Material frame, Material step) {
        c.fill(x, 1, z, x, 2, z, Material.AIR);
        c.door(x, 1, z, door, 0, out);
        c.stair(x - 1, 1, z, frame, 1, 0, false);
        c.stair(x + 1, 1, z, frame, -1, 0, false);
        c.set(x, 3, z, frame);
        c.stair(x, 0, z + out, step, 0, -out, false);
        c.lantern(x + 2, 3, z + out, true);
    }

    // ------------------------------------------------------------------ Детали

    public static void chimney(Canvas c, int x, int z, int yFrom, int yTo, Material block) {
        for (int y = yFrom; y <= yTo; y++) c.set(x, y, z, block);
        c.set(x, yTo + 1, z, Material.CAMPFIRE);
        c.set(x - 1, yTo, z, Material.AIR);
    }

    public static void lampPost(Canvas c, int x, int z, Material post) {
        c.set(x, 0, z, Material.STONE_BRICKS);
        c.fill(x, 1, z, x, 3, z, post);
        c.set(x, 4, z, Material.OAK_FENCE);
        c.lantern(x, 3, z - 1, true);
        c.set(x, 4, z - 1, Material.OAK_FENCE);
    }

    public static void barrel(Canvas c, int x, int y, int z) {
        c.dir(x, y, z, Material.BARREL, 0, -1);
    }

    public static void flowerPot(Canvas c, int x, int y, int z, Random rnd) {
        Material[] pots = {Material.POTTED_RED_TULIP, Material.POTTED_AZURE_BLUET, Material.POTTED_CORNFLOWER, Material.POTTED_ALLIUM, Material.POTTED_OXEYE_DAISY, Material.POTTED_PINK_TULIP};
        c.set(x, y, z, pots[rnd.nextInt(pots.length)]);
    }

    /** Грядка-клумба с бордюром. */
    public static void flowerBed(Canvas c, int x0, int z0, int w, int d, Random rnd) {
        Material[] fl = {Material.POPPY, Material.DANDELION, Material.CORNFLOWER, Material.ALLIUM, Material.PINK_TULIP, Material.AZURE_BLUET, Material.RED_TULIP, Material.OXEYE_DAISY};
        for (int x = x0; x < x0 + w; x++) for (int z = z0; z < z0 + d; z++) {
            boolean edge = x == x0 || x == x0 + w - 1 || z == z0 || z == z0 + d - 1;
            if (edge) { c.set(x, 0, z, Material.STONE_BRICK_SLAB); }
            else { c.set(x, 0, z, Material.PODZOL); c.set(x, 1, z, fl[rnd.nextInt(fl.length)]); }
        }
    }

    /** Свисающая гирлянда фонарей между двумя столбами вдоль оси x на высоте y. */
    public static void garland(Canvas c, int xFrom, int xTo, int z, int y) {
        for (int x = xFrom; x <= xTo; x++) {
            c.set(x, y, z, Material.OAK_FENCE);
            if ((x - xFrom) % 3 == 1) c.lantern(x, y - 1, z, true);
        }
    }
}
