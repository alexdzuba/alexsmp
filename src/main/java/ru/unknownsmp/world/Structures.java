package ru.unknownsmp.world;

import org.bukkit.Material;

import java.util.Random;

/**
 * Общие постройки: дома с крышами, окнами, дверями, табличками и меблированными интерьерами.
 * Все размеры — в локальных координатах {@link Canvas}: фасад в плоскости z=0, дверь по центру.
 */
public final class Structures {
    private Structures() {}

    /** Набор материалов здания. */
    public record Pal(Material wall, Material frame, Material trim, Material floor, Material stair, Material slab,
                      Material ridge, Material pane, Material door, Material fence) {}

    public static final Pal TIMBER = new Pal(Material.WHITE_TERRACOTTA, Material.DARK_OAK_LOG, Material.DARK_OAK_PLANKS, Material.OAK_PLANKS,
            Material.DARK_OAK_STAIRS, Material.DARK_OAK_SLAB, Material.DARK_OAK_PLANKS, Material.GLASS_PANE, Material.DARK_OAK_DOOR, Material.DARK_OAK_FENCE);
    public static final Pal SPRUCE = new Pal(Material.SPRUCE_PLANKS, Material.SPRUCE_LOG, Material.DARK_OAK_PLANKS, Material.OAK_PLANKS,
            Material.SPRUCE_STAIRS, Material.SPRUCE_SLAB, Material.SPRUCE_PLANKS, Material.GLASS_PANE, Material.SPRUCE_DOOR, Material.SPRUCE_FENCE);
    public static final Pal BRICK = new Pal(Material.BIRCH_PLANKS, Material.BIRCH_LOG, Material.OAK_PLANKS, Material.BIRCH_PLANKS,
            Material.BRICK_STAIRS, Material.BRICK_SLAB, Material.BRICKS, Material.GLASS_PANE, Material.BIRCH_DOOR, Material.BIRCH_FENCE);
    public static final Pal CHERRY = new Pal(Material.CHERRY_PLANKS, Material.CHERRY_LOG, Material.DARK_OAK_PLANKS, Material.CHERRY_PLANKS,
            Material.CHERRY_STAIRS, Material.CHERRY_SLAB, Material.CHERRY_PLANKS, Material.PINK_STAINED_GLASS_PANE, Material.CHERRY_DOOR, Material.CHERRY_FENCE);
    public static final Pal STONE = new Pal(Material.STONE_BRICKS, Material.POLISHED_ANDESITE, Material.DEEPSLATE_BRICKS, Material.STONE_BRICKS,
            Material.DEEPSLATE_TILE_STAIRS, Material.DEEPSLATE_TILE_SLAB, Material.DEEPSLATE_TILES, Material.GLASS_PANE, Material.DARK_OAK_DOOR, Material.DARK_OAK_FENCE);
    public static final Pal DARK = new Pal(Material.POLISHED_BLACKSTONE_BRICKS, Material.PURPUR_PILLAR, Material.DEEPSLATE_BRICKS, Material.DEEPSLATE_TILES,
            Material.DEEPSLATE_TILE_STAIRS, Material.DEEPSLATE_TILE_SLAB, Material.DEEPSLATE_TILES, Material.PURPLE_STAINED_GLASS_PANE, Material.DARK_OAK_DOOR, Material.DARK_OAK_FENCE);

    /**
     * Дом с двускатной крышей. w×d — габариты, h — высота потолка. type — тип интерьера:
     * HOME, SHOP, SMITH, ALCHEMY, BANK, LIBRARY, LODGE, TAVERN, AUCTION, RANKS, EMPTY.
     */
    public static void house(Canvas c, int w, int d, int h, Pal p, String type, String[] sign) {
        // Фундамент и пол.
        c.fill(-1, -3, -1, w, -1, d, Material.COBBLESTONE);
        c.fill(0, 0, 0, w - 1, 0, d - 1, p.floor());
        // Стены и воздух внутри.
        c.fill(0, 1, 0, w - 1, h, d - 1, p.wall());
        c.fill(1, 1, 1, w - 2, h, d - 2, Material.AIR);
        // Потолок и балки.
        c.fill(0, h + 1, 0, w - 1, h + 1, d - 1, p.trim());
        c.fill(0, h, 0, w - 1, h, 0, p.trim());
        c.fill(0, h, d - 1, w - 1, h, d - 1, p.trim());
        c.fill(0, h, 0, 0, h, d - 1, p.trim());
        c.fill(w - 1, h, 0, w - 1, h, d - 1, p.trim());
        // Каркас: угловые и промежуточные стойки.
        for (int x : new int[]{0, w - 1}) for (int z : new int[]{0, d - 1}) c.fill(x, 1, z, x, h, z, p.frame());
        for (int x = 4; x <= w - 5; x += 4) { c.fill(x, 1, 0, x, h, 0, p.frame()); c.fill(x, 1, d - 1, x, h, d - 1, p.frame()); }
        for (int z = 4; z <= d - 5; z += 4) { c.fill(0, 1, z, 0, h, z, p.frame()); c.fill(w - 1, 1, z, w - 1, h, z, p.frame()); }
        // Окна.
        int wy2 = Math.min(3, h - 1);
        for (int x : new int[]{2, w - 3}) {
            if (x != w / 2 && !isPillar(x, w)) {
                c.fill(x, 2, 0, x, wy2, 0, p.pane());
                c.fill(x, 2, d - 1, x, wy2, d - 1, p.pane());
            }
        }
        c.fill(w / 2, 2, d - 1, w / 2, wy2, d - 1, p.pane());
        for (int z : new int[]{d / 2}) if (!isPillar(z, d)) {
            c.fill(0, 2, z, 0, wy2, z, p.pane());
            c.fill(w - 1, 2, z, w - 1, wy2, z, p.pane());
        }
        // Дверь.
        c.fill(w / 2, 1, 0, w / 2, 2, 0, Material.AIR);
        c.door(w / 2, 1, 0, p.door(), 0, -1);
        // Крыша: двускатная с навесом, фронтоны закрыты.
        for (int k = 0; ; k++) {
            int l = -1 + k, r = w - k;
            if (l > r) break;
            int y = h + 1 + k;
            for (int z = -1; z <= d; z++) {
                if (l == r) c.set(l, y, z, p.ridge());
                else {
                    c.stair(l, y, z, p.stair(), 1, 0, false);
                    c.stair(r, y, z, p.stair(), -1, 0, false);
                }
            }
            for (int x = l + 1; x <= r - 1; x++) { c.set(x, y, 0, p.wall()); c.set(x, y, d - 1, p.wall()); }
        }
        // Свет снаружи и табличка.
        for (int s : new int[]{-2, 2}) {
            int x = w / 2 + s;
            c.set(x, 1, -1, p.fence());
            c.set(x, 2, -1, p.fence());
            c.lantern(x, 3, -1, false);
        }
        if (sign != null) c.sign(w / 2, 3, -1, 0, -1, sign);
        // Светильники внутри.
        for (int x : new int[]{2, w - 3}) for (int z : new int[]{2, d - 3}) c.lantern(x, h, z, true);
        furnish(c, w, d, h, type, p);
    }

    private static boolean isPillar(int v, int size) {
        return v >= 4 && v <= size - 5 && v % 4 == 0;
    }

    // ------------------------------------------------------------------ Интерьеры

    private static void furnish(Canvas c, int w, int d, int h, String type, Pal p) {
        int mid = w / 2;
        switch (type) {
            case "HOME" -> {
                c.bed(1, 1, d - 3, Material.RED_BED, 0, 1);
                c.bed(2, 1, d - 3, Material.BLUE_BED, 0, 1);
                c.fill(1, 1, 1, 3, 1, 2, Material.AIR);
                c.fill(3, 1, 1, w - 2, 1, 1, Material.AIR);
                table(c, w - 4, 2, p);
                c.dir(w - 2, 1, d - 2, Material.CHEST, -1, 0);
                c.dir(w - 2, 1, d - 3, Material.FURNACE, -1, 0);
                c.set(w - 2, 1, d - 4, Material.CRAFTING_TABLE);
                c.fill(4, 1, d - 2, 5, 2, d - 2, Material.BOOKSHELF);
                c.fill(mid - 1, 1, 2, mid + 1, 1, 4, Material.RED_CARPET);
                c.set(1, 1, 1, Material.POTTED_FERN);
                c.set(w - 2, 1, 1, Material.POTTED_AZURE_BLUET);
            }
            case "SHOP", "AUCTION" -> {
                counter(c, 2, w - 3, 3, p);
                c.fill(mid, 1, 1, mid, 1, 2, Material.RED_CARPET);
                for (int x = 2; x <= w - 3; x++) { c.set(x, 1, d - 2, Material.BOOKSHELF); c.set(x, 2, d - 2, Material.BOOKSHELF); }
                c.dir(1, 1, d - 2, Material.BARREL, 0, -1);
                c.dir(w - 2, 1, d - 2, Material.BARREL, 0, -1);
                c.dir(1, 1, 2, Material.CHEST, 1, 0);
                c.dir(w - 2, 1, 2, Material.CHEST, -1, 0);
                c.set(2, 2, 3, type.equals("AUCTION") ? Material.GOLD_BLOCK : Material.FLOWER_POT);
                c.set(w - 3, 2, 3, type.equals("AUCTION") ? Material.GOLD_BLOCK : Material.POTTED_POPPY);
                c.set(1, 1, 1, Material.POTTED_AZALEA_BUSH);
            }
            case "SMITH" -> {
                counter(c, 2, w - 3, 3, p);
                c.dir(1, 1, d - 2, Material.BLAST_FURNACE, 0, -1);
                c.dir(2, 1, d - 2, Material.FURNACE, 0, -1);
                c.dir(w - 3, 1, d - 2, Material.GRINDSTONE, 0, -1);
                c.set(w - 2, 1, d - 2, Material.SMITHING_TABLE);
                c.dir(w - 2, 1, d - 4, Material.ANVIL, 1, 0);
                c.set(3, 1, d - 2, Material.LAVA_CAULDRON);
                c.set(4, 1, d - 2, Material.WATER_CAULDRON);
                c.fill(1, 1, 2, 1, 2, 2, Material.IRON_BARS);
                c.fill(w - 2, 1, 1, w - 2, 1, 2, Material.COBBLESTONE_WALL);
                c.set(w - 2, 2, 1, Material.LANTERN);
                c.fill(mid, 1, 1, mid, 1, 2, Material.GRAY_CARPET);
            }
            case "ALCHEMY" -> {
                counter(c, 2, w - 3, 3, p);
                c.set(2, 2, 3, Material.BREWING_STAND);
                c.set(w - 3, 2, 3, Material.BREWING_STAND);
                c.set(mid, 2, 3, Material.CAULDRON);
                for (int x = 1; x <= w - 2; x += 2) c.set(x, 1, d - 2, Material.BARREL);
                for (int x = 2; x <= w - 3; x += 2) { c.set(x, 1, d - 2, Material.BOOKSHELF); c.set(x, 2, d - 2, Material.BOOKSHELF); }
                c.set(1, 1, 1, Material.POTTED_CRIMSON_FUNGUS);
                c.set(w - 2, 1, 1, Material.POTTED_WARPED_FUNGUS);
                c.fill(mid, 1, 1, mid, 1, 2, Material.PURPLE_CARPET);
                c.set(w - 2, 3, d - 2, Material.AMETHYST_CLUSTER);
            }
            case "BANK" -> {
                counter(c, 2, w - 3, 3, p);
                for (int x = 2; x <= w - 3; x++) c.set(x, 2, 3, Material.IRON_BARS);
                c.set(mid, 2, 3, Material.AIR);
                for (int x = 2; x <= w - 3; x += 2) c.set(x, 1, d - 2, Material.GOLD_BLOCK);
                c.dir(1, 1, d - 2, Material.ENDER_CHEST, 0, -1);
                c.dir(w - 2, 1, d - 2, Material.ENDER_CHEST, 0, -1);
                c.dir(1, 1, 2, Material.BARREL, 1, 0);
                c.dir(w - 2, 1, 2, Material.BARREL, -1, 0);
                c.fill(mid, 1, 1, mid, 1, 2, Material.YELLOW_CARPET);
            }
            case "LIBRARY" -> {
                for (int z = 2; z <= d - 3; z++) { c.fill(1, 1, z, 1, 3, z, Material.BOOKSHELF); c.fill(w - 2, 1, z, w - 2, 3, z, Material.BOOKSHELF); }
                for (int x = 2; x <= w - 3; x++) c.fill(x, 1, d - 2, x, 3, d - 2, Material.BOOKSHELF);
                c.fill(mid - 1, 1, d - 2, mid + 1, 3, d - 2, Material.CHISELED_BOOKSHELF);
                c.set(mid, 1, 3, Material.ENCHANTING_TABLE);
                c.dir(mid - 2, 1, 4, Material.LECTERN, 0, -1);
                c.dir(mid + 2, 1, 4, Material.LECTERN, 0, -1);
                c.fill(mid - 1, 1, 1, mid + 1, 1, 2, Material.BLUE_CARPET);
                c.stair(3, 1, 2, p.stair(), 1, 0, false);
                c.stair(w - 4, 1, 2, p.stair(), -1, 0, false);
                c.set(1, 1, 1, Material.POTTED_AZALEA_BUSH);
            }
            case "LODGE" -> {
                c.bed(1, 1, d - 3, Material.RED_BED, 0, 1);
                c.set(w - 2, 1, d - 2, Material.CAMPFIRE);
                c.fill(w - 2, 2, d - 2, w - 2, 3, d - 2, Material.AIR);
                c.fill(2, 1, 1, w - 3, 1, 1, Material.AIR);
                c.fill(mid - 1, 1, 2, mid + 1, 1, 4, Material.BROWN_CARPET);
                c.fill(mid - 1, 1, 3, mid, 1, 4, Material.WHITE_CARPET);
                counter(c, 2, w - 3, 3, p);
                c.dir(2, 1, d - 2, Material.BARREL, 0, -1);
                c.set(3, 1, d - 2, Material.FLETCHING_TABLE);
                c.set(4, 1, d - 2, Material.CARTOGRAPHY_TABLE);
                c.set(w - 3, 2, 3, Material.SKELETON_SKULL);
                c.set(2, 2, 3, Material.ZOMBIE_HEAD);
                c.fill(1, 1, 2, 1, 2, 2, p.fence());
            }
            case "TAVERN" -> {
                // Барная стойка Г-образная, столы и бочки.
                c.fill(2, 1, d - 4, w - 3, 1, d - 4, p.trim());
                c.fill(w - 3, 1, d - 4, w - 3, 1, 2, p.trim());
                for (int x = 2; x <= w - 4; x += 2) c.set(x, 1, d - 2, Material.BARREL);
                for (int x = 3; x <= w - 5; x += 2) c.fill(x, 1, d - 2, x, 2, d - 2, Material.BOOKSHELF);
                c.dir(2, 2, d - 2, Material.BARREL, 0, -1);
                table(c, 3, 2, p);
                table(c, 3, 6, p);
                c.set(w - 2, 1, 2, Material.CAMPFIRE);
                c.fill(mid - 1, 1, 1, mid - 1, 1, 2, Material.RED_CARPET);
                for (int z = 2; z <= d - 5; z += 3) { c.stair(w - 2, 1, z, p.stair(), -1, 0, false); }
                c.set(1, 1, 1, Material.POTTED_DARK_OAK_SAPLING);
            }
            case "RANKS" -> {
                counter(c, 2, w - 3, 3, p);
                c.fill(mid - 1, 1, 1, mid + 1, 1, 2, Material.RED_CARPET);
                for (int x = 2; x <= w - 3; x += 3) { c.fill(x, 1, d - 2, x, 3, d - 2, Material.GOLD_BLOCK); c.set(x, 4 > h ? h : 4, d - 2, Material.BEACON); }
                c.dir(2, 1, 2, Material.LECTERN, 1, 0);
                c.dir(w - 3, 1, 2, Material.LECTERN, -1, 0);
                c.set(1, 1, 1, Material.POTTED_CHERRY_SAPLING);
            }
            default -> { /* пустое помещение */ }
        }
    }

    private static void counter(Canvas c, int x1, int x2, int z, Pal p) {
        c.fill(x1, 1, z, x2, 1, z, p.trim());
        c.set(x1, 2, z, Material.LANTERN);
        c.set(x2, 2, z, Material.LANTERN);
    }

    /** Стол 2×1 с настоящей столешницей, свечой и двумя стульями по бокам. */
    private static void table(Canvas c, int x, int z, Pal p) {
        c.slab(x, 1, z, p.slab(), true);
        c.slab(x + 1, 1, z, p.slab(), true);
        c.set(x, 2, z, Material.CANDLE);
        c.stair(x - 1, 1, z, p.stair(), -1, 0, false);
        c.stair(x + 2, 1, z, p.stair(), 1, 0, false);
    }

    // ------------------------------------------------------------------ Деревья

    /** Декоративное дерево (листва не опадает). kind: oak, birch, spruce, cherry. */
    public static void tree(Canvas c, int x, int z, String kind, Random r) {
        Material log, leaf;
        int h;
        switch (kind) {
            case "birch" -> { log = Material.BIRCH_LOG; leaf = Material.BIRCH_LEAVES; h = 6 + r.nextInt(3); }
            case "spruce" -> { log = Material.SPRUCE_LOG; leaf = Material.SPRUCE_LEAVES; h = 8 + r.nextInt(4); }
            case "cherry" -> { log = Material.CHERRY_LOG; leaf = Material.CHERRY_LEAVES; h = 5 + r.nextInt(2); }
            default -> { log = Material.OAK_LOG; leaf = Material.OAK_LEAVES; h = 5 + r.nextInt(3); }
        }
        if (kind.equals("spruce")) {
            for (int y = 1; y <= h; y++) c.set(x, y, z, log);
            for (int y = 3; y <= h + 1; y++) {
                int rad = Math.max(0, Math.min(3, (h + 1 - y + 1) / 2));
                for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > rad + (rad > 1 ? 1 : 0)) continue;
                    if (dx == 0 && dz == 0 && y <= h) continue;
                    c.leaves(x + dx, y, z + dz, leaf);
                }
            }
            return;
        }
        for (int y = 1; y <= h; y++) c.set(x, y, z, log);
        int top = h;
        int rad = kind.equals("cherry") ? 3 : 2;
        for (int dy = -2; dy <= 1; dy++) {
            int rr = dy == 1 ? rad - 1 : rad;
            for (int dx = -rr - 1; dx <= rr + 1; dx++) for (int dz = -rr - 1; dz <= rr + 1; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz + dy * dy * 1.4);
                if (dist > rr + 0.6 || (dist > rr - 0.2 && r.nextInt(3) == 0)) continue;
                if (dx == 0 && dz == 0 && dy <= 0) continue;
                c.leaves(x + dx, top + dy + 1, z + dz, leaf);
            }
        }
    }
}
