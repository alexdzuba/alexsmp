package ru.unknownsmp.duels;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Stairs;

/**
 * Карта дуэлей «Древний колизей»: круглая арена с узорным полом, укрытиями-руинами,
 * колоннами с фонарями и амфитеатром для зрителей. Плюс хаб дуэлей.
 */
final class ArenaBuilder {
    private ArenaBuilder() {}

    static final int FLOOR_R = 20;
    static final int Y = 100;

    static void arena(World w, int cx, int cz) {
        for (int dx = -30; dx <= 30; dx++) for (int dz = -30; dz <= 30; dz++) {
            double d = Math.sqrt(dx * dx + dz * dz);
            int x = cx + dx, z = cz + dz;
            if (d <= FLOOR_R + 0.5) {
                set(w, x, Y - 1, z, Material.STONE);
                set(w, x, Y, z, floor(dx, dz, d));
            } else if (d <= 22.5) {
                // Стена арены (3 блока) с зубцами.
                for (int y = Y - 1; y <= Y + 3; y++) set(w, x, y, z, y == Y + 3 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS);
                if ((dx + dz) % 2 == 0) set(w, x, Y + 4, z, Material.STONE_BRICK_WALL);
            } else if (d <= 28.5) {
                // Амфитеатр: ряды поднимаются наружу.
                int row = (int) Math.floor(d - 22.5);
                int top = Y + 3 + row;
                for (int y = Y - 1; y < top; y++) set(w, x, y, z, row % 2 == 0 ? Material.STONE_BRICKS : Material.CRACKED_STONE_BRICKS);
                Block b = w.getBlockAt(x, top, z);
                b.setType(row == 5 ? Material.POLISHED_DEEPSLATE : Material.STONE_BRICK_STAIRS, false);
                if (b.getBlockData() instanceof Stairs s) {
                    s.setFacing(away(dx, dz));
                    s.setHalf(Bisected.Half.BOTTOM);
                    b.setBlockData(s, false);
                }
            } else if (d <= 29.5) {
                for (int y = Y - 1; y <= Y + 10; y++) set(w, x, y, z, y >= Y + 9 ? Material.POLISHED_DEEPSLATE : Material.DEEPSLATE_BRICKS);
                if ((dx * 7 + dz * 3) % 4 == 0) set(w, x, Y + 11, z, Material.DEEPSLATE_BRICK_WALL);
            }
        }
        // Колонны с фонарями по кругу (каждые 30°).
        for (int a = 0; a < 360; a += 30) {
            double r = Math.toRadians(a);
            int x = cx + (int) Math.round(Math.cos(r) * 21.5), z = cz + (int) Math.round(Math.sin(r) * 21.5);
            for (int y = Y; y <= Y + 7; y++) set(w, x, y, z, y == Y + 7 ? Material.CHISELED_STONE_BRICKS : Material.QUARTZ_PILLAR);
            Block l = w.getBlockAt(x, Y + 8, z);
            l.setType(Material.LANTERN, false);
            if (l.getBlockData() instanceof Lantern ln) { ln.setHanging(false); l.setBlockData(ln, false); }
            // Светильник в полу перед колонной.
            int fx = cx + (int) Math.round(Math.cos(r) * 14), fz = cz + (int) Math.round(Math.sin(r) * 14);
            set(w, fx, Y, fz, Material.SEA_LANTERN);
        }
        // Укрытия-руины (45° диагонали) и низкие стенки на оси Z.
        int[] heights = {3, 2, 4, 1};
        for (int k = 0; k < 4; k++) {
            double r = Math.toRadians(45 + 90 * k);
            int x = cx + (int) Math.round(Math.cos(r) * 10), z = cz + (int) Math.round(Math.sin(r) * 10);
            for (int ox = 0; ox <= 1; ox++) for (int oz = 0; oz <= 1; oz++) {
                int h = Math.max(1, heights[k] - (ox + oz) % 2);
                for (int y = Y + 1; y <= Y + h; y++) set(w, x + ox, y, z + oz, (y + ox) % 2 == 0 ? Material.MOSSY_STONE_BRICKS : Material.CRACKED_STONE_BRICKS);
            }
        }
        for (int side : new int[]{-7, 7}) {
            for (int o = -2; o <= 2; o++) {
                set(w, cx + o, Y + 1, cz + side, Material.MOSSY_COBBLESTONE_WALL);
                if (Math.abs(o) <= 1) set(w, cx + o, Y + 2, cz + side, Material.MOSSY_COBBLESTONE_WALL);
            }
        }
        // Точки появления: красная и синяя.
        for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
            set(w, cx - 15 + ox, Y, cz + oz, ox == 0 && oz == 0 ? Material.REDSTONE_LAMP : Material.RED_TERRACOTTA);
            set(w, cx + 15 + ox, Y, cz + oz, ox == 0 && oz == 0 ? Material.REDSTONE_LAMP : Material.BLUE_TERRACOTTA);
        }
        set(w, cx - 15, Y - 1, cz, Material.REDSTONE_BLOCK);
        set(w, cx + 15, Y - 1, cz, Material.REDSTONE_BLOCK);
        for (int ox : new int[]{-15, 15}) {
            Block b = w.getBlockAt(cx + ox, Y, cz);
            if (b.getBlockData() instanceof Lightable l) { l.setLit(true); b.setBlockData(l, false); }
        }
    }

    private static Material floor(int dx, int dz, double d) {
        if (d < 2.5) return Material.CHISELED_STONE_BRICKS;
        if (Math.abs(d - 4) < 0.6) return Material.POLISHED_DEEPSLATE;
        if (Math.abs(d - 9) < 0.5 || Math.abs(d - 18) < 0.5) return Material.POLISHED_ANDESITE;
        if (dx == 0 || dz == 0 || Math.abs(dx) == Math.abs(dz)) return Material.STONE_BRICKS;
        int h = Math.floorMod(dx * 92837111 ^ dz * 689287499, 12);
        return switch (h) {
            case 0 -> Material.MOSSY_STONE_BRICKS;
            case 1 -> Material.CRACKED_STONE_BRICKS;
            case 2 -> Material.ANDESITE;
            case 3 -> Material.TUFF_BRICKS;
            default -> Material.SMOOTH_STONE;
        };
    }

    private static BlockFace away(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return dx > 0 ? BlockFace.EAST : BlockFace.WEST;
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** Хаб дуэлей: площадка с красными акцентами. */
    static void hub(World w) {
        for (int dx = -14; dx <= 14; dx++) for (int dz = -14; dz <= 14; dz++) {
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > 14.5) continue;
            Material m;
            if (d > 13.5) m = Material.RED_NETHER_BRICKS;
            else if (d < 2.5) m = Material.CRYING_OBSIDIAN;
            else if (dx == 0 || dz == 0) m = Material.NETHER_BRICKS;
            else if (Math.abs(d - 7) < 0.5) m = Material.RED_NETHER_BRICKS;
            else m = Material.POLISHED_BLACKSTONE_BRICKS;
            set(w, dx, Y, dz, m);
            set(w, dx, Y - 1, dz, Material.BLACKSTONE);
            if (d > 13.5 && (dx + dz) % 2 == 0) set(w, dx, Y + 1, dz, Material.RED_NETHER_BRICK_WALL);
        }
        for (int[] p : new int[][]{{8, 8}, {-8, 8}, {8, -8}, {-8, -8}}) {
            for (int y = Y + 1; y <= Y + 3; y++) set(w, p[0], y, p[1], Material.POLISHED_BLACKSTONE_WALL);
            set(w, p[0], Y + 4, p[1], Material.SHROOMLIGHT);
        }
    }

    private static void set(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }
}
