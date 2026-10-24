package ru.unknownsmp.world;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;

/**
 * Уникальные постройки деревни. Каждая — отдельная функция со своим силуэтом, цветом крыши и деталями.
 * Локальные координаты: x вправо (0..w-1), y вверх (0 — пол), z вглубь (0 — фасад, зритель снаружи при z &lt; 0).
 */
public final class Houses {
    private Houses() {}

    public record Spec(String id, int w, int d, BiConsumer<Canvas, Random> build) {}

    public static final Map<String, Spec> ALL = new LinkedHashMap<>();

    static void reg(String id, int w, int d, BiConsumer<Canvas, Random> b) { ALL.put(id, new Spec(id, w, d, b)); }

    static {
        reg("tavern", 19, 13, Houses::tavern);
        reg("smithy", 15, 11, Houses::smithy);
        reg("cottage_round", 9, 9, Houses::cottageRound);
        reg("cottage_tower", 11, 9, Houses::cottageTower);
        reg("cottage_cherry", 11, 9, Houses::cottageCherry);
        reg("chalet", 11, 9, Houses::chalet);
        reg("bakery", 11, 9, Houses::bakery);
        reg("library", 13, 13, Houses::library);
        Houses2.init();
        Houses3.init();
    }

    // ------------------------------------------------------------------ Палитра и общие части

    /** Материалы здания. */
    public record Pal(Material plaster, Material frame, Material base, Material roofStair, Material roofBlock, Material shutter, Material door, Material floor, Material glass) {}

    static final Material[] FLOWER_POTS = {Material.POTTED_RED_TULIP, Material.POTTED_AZURE_BLUET, Material.POTTED_CORNFLOWER, Material.POTTED_ALLIUM, Material.POTTED_OXEYE_DAISY, Material.POTTED_PINK_TULIP};

    /** Фундамент и пол на всю площадь, воздух внутри. */
    static void foundation(Canvas c, int x0, int z0, int w, int d, Material found, Material floor) {
        c.fill(x0 - 1, -3, z0 - 1, x0 + w, -1, z0 + d, found);
        c.fill(x0, 0, z0, x0 + w - 1, 0, z0 + d - 1, floor);
    }

    /**
     * Стены с цоколем из камня, штукатуркой, угловыми стойками и верхней обвязкой. Окна — каждые 4 блока с окнами 1×2
     * (если windows). Внутри очищается воздух.
     */
    static void walls(Canvas c, int x0, int z0, int w, int d, int y0, int y1, Pal p, boolean baseCourse) {
        c.fill(x0, y0, z0, x0 + w - 1, y1, z0 + d - 1, Material.AIR);
        for (int y = y0; y <= y1; y++) {
            Material m = baseCourse && y == y0 ? p.base() : p.plaster();
            c.fill(x0, y, z0, x0 + w - 1, y, z0, m);
            c.fill(x0, y, z0 + d - 1, x0 + w - 1, y, z0 + d - 1, m);
            c.fill(x0, y, z0, x0, y, z0 + d - 1, m);
            c.fill(x0 + w - 1, y, z0, x0 + w - 1, y, z0 + d - 1, m);
        }
        for (int x : new int[]{x0, x0 + w - 1}) for (int z : new int[]{z0, z0 + d - 1}) c.fill(x, y0, z, x, y1, z, p.frame());
        c.fill(x0, y1, z0, x0 + w - 1, y1, z0, p.frame());
        c.fill(x0, y1, z0 + d - 1, x0 + w - 1, y1, z0 + d - 1, p.frame());
        c.fill(x0, y1, z0, x0, y1, z0 + d - 1, p.frame());
        c.fill(x0 + w - 1, y1, z0, x0 + w - 1, y1, z0 + d - 1, p.frame());
        // Промежуточные стойки.
        for (int x = x0 + 4; x < x0 + w - 2; x += 4) { c.fill(x, y0 + 1, z0, x, y1, z0, p.frame()); c.fill(x, y0 + 1, z0 + d - 1, x, y1, z0 + d - 1, p.frame()); }
        for (int z = z0 + 4; z < z0 + d - 2; z += 4) { c.fill(x0, y0 + 1, z, x0, y1, z, p.frame()); c.fill(x0 + w - 1, y0 + 1, z, x0 + w - 1, y1, z, p.frame()); }
    }

    static void sideWindows(Canvas c, int x0, int z0, int w, int d, int y, Pal p) {
        for (int z = z0 + 2; z < z0 + d - 2; z += 4) {
            c.fill(x0, y, z, x0, y + 1, z, p.glass());
            c.fill(x0 + w - 1, y, z, x0 + w - 1, y + 1, z, p.glass());
        }
        for (int x = x0 + 2; x < x0 + w - 2; x += 4) c.fill(x, y, z0 + d - 1, x, y + 1, z0 + d - 1, p.glass());
    }

    static void frontWindows(Canvas c, int x0, int w, int y, Pal p, int skipX, boolean box) {
        for (int x = x0 + 2; x < x0 + w - 2; x += 4) {
            if (Math.abs(x - skipX) <= 1) continue;
            Kit.window(c, x, y, 0, -1, p.glass(), p.shutter(), true, box);
        }
    }

    // ------------------------------------------------------------------ Таверна «Золотой кабан»

    private static void tavern(Canvas c, Random r) {
        int w = 19, d = 13, h1 = 4, h2 = 8;
        Pal p = new Pal(Material.WHITE_TERRACOTTA, Material.DARK_OAK_LOG, Material.STONE_BRICKS, Material.CUT_COPPER_STAIRS, Material.CUT_COPPER,
                Material.WARPED_TRAPDOOR, Material.DARK_OAK_DOOR, Material.SPRUCE_PLANKS, Material.GLASS_PANE);
        foundation(c, 0, 0, w, d, Material.COBBLESTONE, p.floor());
        walls(c, 0, 0, w, d, 1, h1, p, true);
        // Перекрытие с выносом вперёд (джеттинг) на 1 блок.
        c.fill(0, h1 + 1, 0, w - 1, h1 + 1, d - 1, Material.DARK_OAK_PLANKS);
        c.fill(-1, h1 + 1, -1, w, h1 + 1, -1, Material.DARK_OAK_PLANKS);
        for (int x = -1; x <= w; x += 2) c.stair(x, h1, -1, Material.DARK_OAK_STAIRS, 0, 1, true);
        walls(c, 0, 0, w, d, h1 + 2, h2, p, false);
        // Окна: первый этаж с ящиками цветов, второй со ставнями.
        frontWindows(c, 0, w, 2, p, 9, true);
        frontWindows(c, 0, w, h1 + 3, p, 9, false);
        sideWindows(c, 0, 0, w, d, 2, p);
        sideWindows(c, 0, 0, w, d, h1 + 3, p);
        // Вход: арка из дерева, ступени, фонари.
        c.fill(9, 1, 0, 9, 3, 0, Material.AIR);
        c.door(9, 1, 0, p.door(), 0, -1);
        for (int x : new int[]{8, 10}) c.stair(x, 1, 0, Material.DARK_OAK_STAIRS, x < 9 ? 1 : -1, 0, false);
        c.fill(8, 4, 0, 10, 4, 0, Material.DARK_OAK_LOG);
        for (int i = 1; i <= 2; i++) c.stair(9, 0, -i, Material.STONE_BRICK_STAIRS, 0, 1, false);
        for (int x : new int[]{7, 11}) { c.fill(x, 0, -3, x, 3, -3, p.frame()); }
        for (int x = 7; x <= 11; x++) { c.stair(x, 4, -3, Material.DARK_OAK_STAIRS, 0, 1, false); c.stair(x, 4, -1, Material.DARK_OAK_STAIRS, 0, -1, false); c.set(x, 4, -2, Material.DARK_OAK_SLAB); }
        c.lantern(9, 3, -2, true);
        // Балкон над входом и дверь на него.
        c.fill(7, h1 + 1, -3, 11, h1 + 1, -2, Material.DARK_OAK_PLANKS);
        for (int x = 7; x <= 11; x++) c.set(x, h1 + 2, -3, Material.DARK_OAK_FENCE);
        c.set(7, h1 + 2, -2, Material.DARK_OAK_FENCE);
        c.set(11, h1 + 2, -2, Material.DARK_OAK_FENCE);
        c.fill(9, h1 + 2, 0, 9, h1 + 3, 0, Material.AIR);
        c.door(9, h1 + 2, 0, p.door(), 0, -1);
        // Крыша: основной двускат вдоль x (глубина 13 → 7 слоёв) — делаем пониже за счёт нечётной глубины 13 и свеса.
        Kit.gableX(c, 0, w - 1, 0, d - 1, h2 + 1, p.roofStair(), p.roofBlock(), p.plaster(), 1);
        // Мансардные окна: два слуховых на фасадном скате.
        for (int x : new int[]{4, 14}) {
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) c.set(x + dx, h2 + 2 + dy, 1, p.plaster());
            c.fill(x, h2 + 2, 1, x, h2 + 3, 1, p.glass());
            for (int dx = -2; dx <= 2; dx++) { int ax = Math.abs(dx); if (ax == 2) continue; }
            c.stair(x - 1, h2 + 4, 1, p.roofStair(), 1, 0, false);
            c.stair(x + 1, h2 + 4, 1, p.roofStair(), -1, 0, false);
            c.set(x, h2 + 4, 1, p.roofBlock());
            c.stair(x - 2, h2 + 3, 1, p.roofStair(), 1, 0, true);
            c.stair(x + 2, h2 + 3, 1, p.roofStair(), -1, 0, true);
        }
        // Трубы.
        Kit.chimney(c, 3, 8, h2 + 3, h2 + 7, p.base());
        Kit.chimney(c, 16, 6, h2 + 3, h2 + 7, p.base());
        // Вывеска на кронштейне: золотой кабан.
        c.fill(17, h1 + 1, -2, 17, h1 + 2, -2, Material.DARK_OAK_FENCE);
        c.set(18, h1 + 3, -2, Material.DARK_OAK_FENCE);
        c.set(17, h1 + 3, -2, Material.DARK_OAK_FENCE);
        c.fill(16, 2, -3, 18, 3, -3, Material.DARK_OAK_PLANKS);
        c.sign(17, 3, -4, 0, -1, "", "&6Золотой", "&6кабан", "");
        c.lantern(16, 4, -3, true);
        // Улица: бочки, столики с зонтиками.
        for (int x : new int[]{0, 1, 17, 18}) Kit.barrel(c, x, 1, -2);
        c.set(0, 2, -2, Material.BARREL);
        for (int x : new int[]{2, 14}) {
            c.fill(x, 0, -5, x + 2, 0, -3, Material.SPRUCE_PLANKS);
            c.set(x + 1, 1, -4, Material.SPRUCE_FENCE);
            c.set(x + 1, 2, -4, Material.OAK_PRESSURE_PLATE);
            c.stair(x, 1, -4, Material.SPRUCE_STAIRS, -1, 0, false);
            c.stair(x + 2, 1, -4, Material.SPRUCE_STAIRS, 1, 0, false);
            c.fill(x + 1, 2, -4, x + 1, 3, -4, Material.SPRUCE_FENCE);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(x + 1 + dx, 4, -4 + dz, (dx + dz) % 2 == 0 ? Material.RED_WOOL : Material.WHITE_WOOL);
        }
        // Интерьер: стойка, полки, камин, столы, лестница.
        c.fill(3, 1, 9, 10, 1, 9, Material.DARK_OAK_PLANKS);
        c.fill(3, 2, 9, 10, 2, 9, Material.DARK_OAK_SLAB);
        for (int x = 3; x <= 10; x++) { c.dir(x, 1, 11, Material.BARREL, 0, -1); c.set(x, 2, 11, Material.BOOKSHELF); c.set(x, 3, 11, Material.BOOKSHELF); }
        c.fill(15, 1, 11, 17, 4, 11, Material.COBBLESTONE);
        c.fill(16, 1, 11, 16, 2, 11, Material.AIR);
        c.set(16, 1, 11, Material.CAMPFIRE);
        for (int x : new int[]{3, 13}) { c.set(x, 1, 3, Material.SPRUCE_FENCE); c.set(x, 2, 3, Material.OAK_PRESSURE_PLATE); c.stair(x - 1, 1, 3, Material.SPRUCE_STAIRS, -1, 0, false); c.stair(x + 1, 1, 3, Material.SPRUCE_STAIRS, 1, 0, false); }
        c.lantern(9, h1, 5, true);
        c.lantern(5, h2, 6, true);
        c.lantern(14, h2, 6, true);
        for (int i = 0; i < 4; i++) { c.stair(17, 1 + i, 3 + i, Material.DARK_OAK_STAIRS, 0, 1, false); }
        for (int z = 3; z <= 7; z++) { c.set(17, h1 + 1, z, Material.AIR); c.set(16, h1 + 1, z, Material.AIR); }
        for (int bx : new int[]{2, 5}) c.bed(bx, h1 + 2, 9, Material.RED_BED, 0, 1);
        c.dir(10, h1 + 2, 10, Material.CHEST, -1, 0);
    }

    // ------------------------------------------------------------------ Кузница

    private static void smithy(Canvas c, Random r) {
        int w = 15, d = 11, h = 5;
        Pal p = new Pal(Material.STONE_BRICKS, Material.DARK_OAK_LOG, Material.COBBLESTONE, Material.DEEPSLATE_TILE_STAIRS, Material.DEEPSLATE_TILES,
                Material.DARK_OAK_TRAPDOOR, Material.DARK_OAK_DOOR, Material.STONE_BRICKS, Material.GLASS_PANE);
        foundation(c, 0, 0, w, d, Material.COBBLESTONE, p.floor());
        walls(c, 0, 0, w, d, 1, h, p, true);
        // Открытая кузнечная часть: сносим фасад на x = 1..5, остаются столбы.
        c.fill(1, 1, 0, 4, h - 1, 0, Material.AIR);
        for (int x : new int[]{0, 5}) c.fill(x, 1, 0, x, h, 0, p.frame());
        c.fill(0, h, 0, 5, h, 0, p.frame());
        for (int x : new int[]{8, 12}) Kit.window(c, x, 2, 0, -1, p.glass(), p.shutter(), true, false);
        c.fill(10, 1, 0, 10, 3, 0, Material.AIR);
        c.door(10, 1, 0, p.door(), 0, -1);
        for (int x : new int[]{9, 11}) c.stair(x, 1, 0, Material.STONE_BRICK_STAIRS, x < 10 ? 1 : -1, 0, false);
        c.stair(10, 0, -1, Material.STONE_BRICK_STAIRS, 0, 1, false);
        c.lantern(10, 4, -1, true);
        sideWindows(c, 0, 0, w, d, 2, p);
        // Крыша: двускат вдоль z над закрытой частью и шедовая односкатная над кузней.
        Kit.gable(c, 6, 14, 0, d - 1, h + 1, p.roofStair(), p.roofBlock(), p.plaster(), 1);
        for (int z = -1; z <= d; z++) for (int k = 0; k < 3; k++) c.stair(-1 + k, h + 1 + k, z, p.roofStair(), 1, 0, false);
        for (int z = -1; z <= d; z++) { c.set(2, h + 3, z, p.roofBlock()); c.stair(3, h + 3, z, p.roofStair(), -1, 0, false); c.stair(4, h + 2, z, p.roofStair(), -1, 0, false); c.stair(5, h + 1, z, p.roofStair(), -1, 0, false); }
        // Горн с вытяжкой.
        c.fill(1, 1, d - 3, 4, 4, d - 2, p.base());
        c.fill(2, 1, d - 3, 3, 3, d - 3, Material.AIR);
        c.set(2, 1, d - 3, Material.BLAST_FURNACE);
        c.set(3, 1, d - 3, Material.LAVA_CAULDRON);
        c.fill(2, 4, d - 2, 3, h + 7, d - 2, p.base());
        c.set(2, h + 8, d - 2, Material.CAMPFIRE);
        c.set(3, h + 8, d - 2, Material.CAMPFIRE);
        c.set(1, 1, 2, Material.ANVIL);
        c.set(3, 1, 2, Material.GRINDSTONE);
        c.set(4, 1, 5, Material.WATER_CAULDRON);
        c.set(1, 1, 5, Material.SMITHING_TABLE);
        c.dir(1, 1, 7, Material.CHEST, 1, 0);
        c.set(4, 1, 7, Material.BARREL);
        c.fill(1, 3, d - 1, 4, 3, d - 1, Material.IRON_BARS);
        c.lantern(3, h - 1, 3, true);
        c.lantern(10, h - 1, 5, true);
        // Закрытая часть: стол, печь, кровать.
        c.fill(7, 1, d - 2, 13, 1, d - 2, Material.DARK_OAK_PLANKS);
        c.set(8, 1, d - 3, Material.CRAFTING_TABLE);
        c.set(12, 1, d - 3, Material.FURNACE);
        c.bed(7, 1, 2, Material.GRAY_BED, 0, 1);
        // Двор.
        for (int x = 7; x <= 9; x++) c.fill(x, 1, -2, x, 2, -2, Material.SPRUCE_LOG);
        c.fill(12, 1, -2, 14, 1, -2, Material.HAY_BLOCK);
        c.set(13, 2, -2, Material.HAY_BLOCK);
        c.set(-2, 1, -2, Material.ANVIL);
        c.sign(-1, 3, -1, 0, -1, "", "&7Кузница", "", "");
        c.set(6, 0, -3, Material.GRAVEL);
    }

    // ------------------------------------------------------------------ Круглый домик с соломенной крышей

    private static void cottageRound(Canvas c, Random r) {
        int cx = 4, cz = 4, rad = 4, h = 4;
        c.fill(-1, -3, -1, 9, -1, 9, Material.COBBLESTONE);
        for (int dx = -rad - 1; dx <= rad + 1; dx++) for (int dz = -rad - 1; dz <= rad + 1; dz++) {
            double dd = Math.hypot(dx, dz);
            int x = cx + dx, z = cz + dz;
            if (dd <= rad + 0.3) {
                c.set(x, 0, z, Material.SPRUCE_PLANKS);
                for (int y = 1; y <= h; y++) c.set(x, y, z, dd > rad - 1 ? (y == 1 ? Material.STONE_BRICKS : Material.WHITE_TERRACOTTA) : Material.AIR);
            }
        }
        for (int[] p : new int[][]{{0, -4}, {0, 4}, {4, 0}, {-4, 0}, {3, 3}, {-3, 3}, {3, -3}, {-3, -3}}) c.fill(cx + p[0], 1, cz + p[1], cx + p[0], h, cz + p[1], Material.OAK_LOG);
        for (int[] p : new int[][]{{-2, 4}, {2, 4}, {4, 2}, {-4, 2}, {-4, -2}, {4, -2}, {2, -4}, {-2, -4}}) {
            if (p[1] == -4 && Math.abs(p[0]) <= 2 && false) continue;
            c.fill(cx + p[0], 2, cz + p[1], cx + p[0], 3, cz + p[1], Material.GLASS_PANE);
        }
        c.fill(cx, 1, cz - 4, cx, 3, cz - 4, Material.AIR);
        c.door(cx, 1, cz - 4, Material.OAK_DOOR, 0, -1);
        c.stair(cx, 0, cz - 5, Material.COBBLESTONE_STAIRS, 0, 1, false);
        c.lantern(cx + 1, 4, cz - 5, true);
        c.set(cx + 1, 4, cz - 4, Material.OAK_FENCE);
        int top = Kit.cone(c, cx, cz, rad + 1, h + 1, Material.MUD_BRICK_STAIRS, Material.MUD_BRICKS);
        c.fill(cx, top, cz, cx, top + 2, cz, Material.OAK_FENCE);
        c.lantern(cx, top + 3, cz, false);
        Kit.chimney(c, cx + 2, cz + 2, 1, h + 3, Material.COBBLESTONE);
        Kit.flowerBed(c, -1, -4, 4, 3, r);
        Kit.flowerBed(c, 6, -4, 4, 3, r);
        c.set(cx - 2, 1, cz, Material.CRAFTING_TABLE);
        c.bed(cx + 1, 1, cz + 1, Material.YELLOW_BED, 0, 1);
        c.dir(cx - 2, 1, cz + 2, Material.CHEST, 1, 0);
    }

    // ------------------------------------------------------------------ Каменный дом с бирюзовой башней

    private static void cottageTower(Canvas c, Random r) {
        int w = 7, d = 9, h = 5;
        Pal p = new Pal(Material.STONE_BRICKS, Material.POLISHED_ANDESITE, Material.COBBLESTONE, Material.BRICK_STAIRS, Material.BRICKS,
                Material.SPRUCE_TRAPDOOR, Material.SPRUCE_DOOR, Material.SPRUCE_PLANKS, Material.GLASS_PANE);
        foundation(c, 0, 0, w, d, Material.COBBLESTONE, p.floor());
        walls(c, 0, 0, w, d, 1, h, p, false);
        for (int y = 1; y <= h; y++) for (int x = 0; x < w; x++) if ((x + y) % 5 == 0) { c.set(x, y, 0, Material.MOSSY_STONE_BRICKS); c.set(x, y, d - 1, Material.MOSSY_STONE_BRICKS); }
        c.fill(3, 1, 0, 3, 3, 0, Material.AIR);
        c.door(3, 1, 0, p.door(), 0, -1);
        c.stair(2, 1, 0, Material.STONE_BRICK_STAIRS, 1, 0, false);
        c.stair(4, 1, 0, Material.STONE_BRICK_STAIRS, -1, 0, false);
        c.stair(3, 0, -1, Material.STONE_BRICK_STAIRS, 0, 1, false);
        c.lantern(4, 4, -1, true);
        c.set(4, 4, 0, Material.DARK_OAK_FENCE);
        Kit.archWindow(c, 1, 2, 0, p.glass(), Material.STONE_BRICK_STAIRS);
        Kit.archWindow(c, 5, 2, 0, p.glass(), Material.STONE_BRICK_STAIRS);
        sideWindows(c, 0, 0, w, d, 2, p);
        // Круглая башня справа сзади, выше дома.
        int tx = 8, tz = 5, tr = 2, th = 10;
        for (int dx = -tr - 1; dx <= tr + 1; dx++) for (int dz = -tr - 1; dz <= tr + 1; dz++) {
            double dd = Math.hypot(dx, dz);
            if (dd > tr + 0.5) continue;
            for (int y = -3; y <= th; y++) c.set(tx + dx, y, tz + dz, dd > tr - 0.7 ? (y < 1 ? Material.COBBLESTONE : Material.STONE_BRICKS) : (y <= 0 ? Material.SPRUCE_PLANKS : Material.AIR));
        }
        for (int y = 3; y <= 7; y += 4) for (int[] q : new int[][]{{tr, 0}, {-tr, 0}, {0, tr}, {0, -tr}}) c.fill(tx + q[0], y, tz + q[1], tx + q[0], y + 1, tz + q[1], Material.GLASS_PANE);
        c.fill(tx - 1, th, tz - 1, tx + 1, th, tz + 1, Material.AIR);
        int top = Kit.cone(c, tx, tz, tr + 1, th + 1, Material.OXIDIZED_CUT_COPPER_STAIRS, Material.OXIDIZED_CUT_COPPER);
        c.fill(tx, top, tz, tx, top + 3, tz, Material.IRON_BARS);
        c.set(tx, top + 4, tz, Material.LIGHTNING_ROD);
        // Крыша дома: шатёр красной черепицы.
        Kit.hip(c, 0, w - 1, 0, d - 1, h + 1, p.roofStair(), p.roofBlock(), 1);
        Kit.chimney(c, 2, 6, h + 1, h + 5, p.base());
        for (int i = 0; i < 4; i++) c.stair(5, 1 + i, 2, Material.OAK_STAIRS, 0, 1, false);
        Kit.flowerBed(c, -2, -3, 3, 4, r);
        Kit.flowerBed(c, 6, -3, 3, 4, r);
        c.set(1, 1, 7, Material.CRAFTING_TABLE);
        c.bed(5, 1, 6, Material.RED_BED, 0, 1);
    }

    // ------------------------------------------------------------------ Вишнёвый дом

    private static void cottageCherry(Canvas c, Random r) {
        int w = 11, d = 9, h = 4;
        Pal p = new Pal(Material.WHITE_TERRACOTTA, Material.STRIPPED_CHERRY_LOG, Material.STONE_BRICKS, Material.CHERRY_STAIRS, Material.CHERRY_PLANKS,
                Material.CHERRY_TRAPDOOR, Material.CHERRY_DOOR, Material.CHERRY_PLANKS, Material.GLASS_PANE);
        foundation(c, 0, 0, w, d, Material.COBBLESTONE, p.floor());
        walls(c, 0, 0, w, d, 1, h, p, true);
        frontWindows(c, 0, w, 2, p, 5, true);
        sideWindows(c, 0, 0, w, d, 2, p);
        c.fill(5, 1, 0, 5, 3, 0, Material.AIR);
        c.door(5, 1, 0, p.door(), 0, -1);
        for (int x : new int[]{4, 6}) c.stair(x, 1, 0, Material.CHERRY_STAIRS, x < 5 ? 1 : -1, 0, false);
        for (int i = 1; i <= 2; i++) c.stair(5, 0, -i, Material.STONE_BRICK_STAIRS, 0, 1, false);
        // Навес над крыльцом на двух столбах.
        for (int x : new int[]{3, 7}) c.fill(x, 0, -3, x, 3, -3, p.frame());
        for (int x = 2; x <= 8; x++) { c.stair(x, 4, -3, p.roofStair(), 0, 1, false); c.stair(x, 5, -2, p.roofStair(), 0, 1, false); c.set(x, 4, -2, p.roofBlock()); c.set(x, 4, -1, p.roofBlock()); }
        c.lantern(5, 3, -2, true);
        Kit.gableX(c, 0, w - 1, 0, d - 1, h + 1, p.roofStair(), p.roofBlock(), p.plaster(), 1);
        Kit.gable(c, 1, 5, -1, 3, h + 1, p.roofStair(), p.roofBlock(), p.plaster(), 0);
        Kit.chimney(c, 9, 4, h + 1, h + 5, p.base());
        c.bed(2, 1, 6, Material.PINK_BED, 0, 1);
        c.set(8, 1, 7, Material.CRAFTING_TABLE);
        c.dir(9, 1, 6, Material.CHEST, -1, 0);
        Kit.flowerBed(c, -2, -5, 3, 3, r);
        Kit.flowerBed(c, 10, -5, 3, 3, r);
        for (int x = -2; x <= 12; x++) if (x != 5) c.set(x, 1, -7, Material.CHERRY_FENCE);
        c.lantern(-2, 2, -7, false);
        c.lantern(12, 2, -7, false);
    }

    // ------------------------------------------------------------------ Еловое шале

    private static void chalet(Canvas c, Random r) {
        int w = 11, d = 9, h1 = 4, h2 = 7;
        Pal p = new Pal(Material.SPRUCE_PLANKS, Material.SPRUCE_LOG, Material.STONE_BRICKS, Material.RED_NETHER_BRICK_STAIRS, Material.RED_NETHER_BRICKS,
                Material.SPRUCE_TRAPDOOR, Material.SPRUCE_DOOR, Material.SPRUCE_PLANKS, Material.GLASS_PANE);
        foundation(c, 0, 0, w, d, Material.COBBLESTONE, p.floor());
        walls(c, 0, 0, w, d, 1, h1, new Pal(Material.STONE_BRICKS, p.frame(), p.base(), p.roofStair(), p.roofBlock(), p.shutter(), p.door(), p.floor(), p.glass()), false);
        c.fill(0, h1 + 1, 0, w - 1, h1 + 1, d - 1, p.floor());
        walls(c, 0, 0, w, d, h1 + 2, h2, p, false);
        for (int x : new int[]{2, 5, 8}) { if (x != 5) Kit.window(c, x, 2, 0, -1, p.glass(), p.shutter(), true, true); }
        c.fill(5, 1, 0, 5, 3, 0, Material.AIR);
        c.door(5, 1, 0, p.door(), 0, -1);
        for (int x : new int[]{3, 7}) Kit.window(c, x, h1 + 3, 0, -1, p.glass(), p.shutter(), true, false);
        sideWindows(c, 0, 0, w, d, 2, p);
        sideWindows(c, 0, 0, w, d, h1 + 3, p);
        // Балкон на втором этаже по фасаду на двух стойках.
        c.fill(0, h1 + 1, -3, w - 1, h1 + 1, -1, p.floor());
        for (int x = 0; x < w; x++) c.set(x, h1 + 2, -3, Material.SPRUCE_FENCE);
        c.set(0, h1 + 2, -2, Material.SPRUCE_FENCE);
        c.set(w - 1, h1 + 2, -2, Material.SPRUCE_FENCE);
        for (int x : new int[]{0, 5, w - 1}) c.fill(x, 0, -3, x, h1, -3, p.frame());
        c.fill(5, h1 + 2, 0, 5, h1 + 3, 0, Material.AIR);
        c.door(5, h1 + 2, 0, p.door(), 0, -1);
        c.stair(5, 0, -4, Material.STONE_BRICK_STAIRS, 0, 1, false);
        for (int i = 0; i < 4; i++) c.stair(w + 1, 1 + i, 1 + (3 - i), Material.SPRUCE_STAIRS, 0, 1, false);
        Kit.gable(c, 0, w - 1, 0, d - 1, h2 + 1, p.roofStair(), p.roofBlock(), p.frame(), 1);
        Kit.chimney(c, 8, 6, 1, h2 + 6, p.base());
        c.set(7, 1, 6, Material.CAMPFIRE);
        Kit.lampPost(c, -2, -2, p.frame());
        Kit.lampPost(c, 12, -2, p.frame());
        for (int i = 0; i < 3; i++) Kit.barrel(c, 1 + i, 1, -5);
        c.fill(9, 1, -5, 10, 2, -5, Material.SPRUCE_LOG);
        c.bed(2, 1, 6, Material.BLUE_BED, 0, 1);
        c.bed(2, h1 + 2, 6, Material.WHITE_BED, 0, 1);
    }

    // ------------------------------------------------------------------ Пекарня с полосатым навесом

    private static void bakery(Canvas c, Random r) {
        int w = 11, d = 9, h = 4;
        Pal p = new Pal(Material.SMOOTH_SANDSTONE, Material.OAK_LOG, Material.BRICKS, Material.MUD_BRICK_STAIRS, Material.MUD_BRICKS,
                Material.OAK_TRAPDOOR, Material.OAK_DOOR, Material.OAK_PLANKS, Material.GLASS_PANE);
        foundation(c, 0, 0, w, d, Material.COBBLESTONE, p.floor());
        walls(c, 0, 0, w, d, 1, h, p, true);
        // Большая витрина вместо окон на фасаде.
        c.fill(1, 2, 0, 4, 3, 0, p.glass());
        c.fill(6, 2, 0, 9, 3, 0, Material.AIR);
        c.fill(7, 1, 0, 7, 3, 0, Material.AIR);
        c.door(7, 1, 0, p.door(), 0, -1);
        c.fill(8, 2, 0, 9, 3, 0, p.glass());
        c.fill(1, 1, -1, 4, 1, -1, Material.OAK_PLANKS);
        c.fill(1, 2, -1, 4, 2, -1, Material.OAK_SLAB);
        for (int x = 1; x <= 4; x++) c.set(x, 3, -1, Material.AIR);
        c.set(2, 3, -1, Material.CAKE);
        c.set(4, 3, -1, Material.CAKE);
        sideWindows(c, 0, 0, w, d, 2, p);
        // Полосатый навес: красно-белая шерсть, скат вперёд.
        for (int x = -1; x <= w; x++) for (int k = 0; k < 3; k++) {
            Material m = (x + 1) % 2 == 0 ? Material.RED_WOOL : Material.WHITE_WOOL;
            c.set(x, h + 0 - k * 0, -1 - k, m);
            if (k == 2) c.set(x, h - 1, -3, m);
        }
        for (int x : new int[]{-1, w}) c.fill(x, 0, -3, x, h - 2, -3, Material.OAK_FENCE);
        Kit.gableX(c, 0, w - 1, 0, d - 1, h + 1, p.roofStair(), p.roofBlock(), p.plaster(), 1);
        Kit.chimney(c, 8, 6, h + 1, h + 6, p.base());
        c.set(8, 1, 6, Material.SMOKER);
        c.set(9, 1, 6, Material.FURNACE);
        c.set(7, 1, 6, Material.CRAFTING_TABLE);
        for (int x = 1; x <= 5; x += 2) Kit.barrel(c, x, 1, 7);
        c.sign(5, 3, -4, 0, -1, "", "&6Пекарня", "", "");
        c.set(5, 3, -3, Material.AIR);
        c.fill(4, 3, -3, 6, 3, -3, Material.OAK_PLANKS);
        for (int x : new int[]{3, 7}) c.set(x, 4, -3, Material.OAK_FENCE);
        c.sign(5, 3, -4, 0, -1, "", "&6Пекарня", "", "");
        Kit.flowerBed(c, -3, -2, 2, 3, r);
        for (int i = 0; i < 2; i++) { c.set(10 + i, 0, -3, Material.HAY_BLOCK); }
        c.set(10, 1, -3, Material.HAY_BLOCK);
    }

    // ------------------------------------------------------------------ Библиотека с куполом

    private static void library(Canvas c, Random r) {
        int w = 13, d = 13, h = 6;
        Pal p = new Pal(Material.QUARTZ_BRICKS, Material.POLISHED_ANDESITE, Material.STONE_BRICKS, Material.STONE_BRICK_STAIRS, Material.STONE_BRICKS,
                Material.DARK_OAK_TRAPDOOR, Material.DARK_OAK_DOOR, Material.DARK_OAK_PLANKS, Material.GLASS_PANE);
        foundation(c, 0, 0, w, d, Material.STONE_BRICKS, p.floor());
        walls(c, 0, 0, w, d, 1, h, p, true);
        // Арочные окна вдоль фасада.
        for (int x : new int[]{2, 10}) Kit.archWindow(c, x, 2, 0, p.glass(), Material.QUARTZ_STAIRS);
        c.fill(6, 1, 0, 6, 4, 0, Material.AIR);
        c.door(6, 1, 0, p.door(), 0, -1);
        for (int x : new int[]{5, 7}) { c.stair(x, 1, 0, Material.QUARTZ_STAIRS, x < 6 ? 1 : -1, 0, false); }
        for (int i = 1; i <= 3; i++) c.stair(6, 0, -i, Material.QUARTZ_STAIRS, 0, 1, false);
        for (int x : new int[]{4, 8}) c.fill(x, 0, -2, x, 4, -2, Material.QUARTZ_PILLAR);
        for (int x = 4; x <= 8; x++) c.set(x, 5, -2, Material.QUARTZ_SLAB);
        for (int z : new int[]{3, 6, 9}) { c.fill(0, 2, z, 0, 4, z, p.glass()); c.fill(w - 1, 2, z, w - 1, 4, z, p.glass()); }
        // Центральный купол из окисленной меди.
        int cx = 6, cz = 6;
        for (int y = h + 1; y <= h + 4; y++) {
            int rr = 5 - (y - h - 1);
            for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) {
                double dd = Math.hypot(dx, dz);
                if (dd <= rr + 0.3 && dd > rr - 1.1) c.set(cx + dx, y, cz + dz, Material.OXIDIZED_CUT_COPPER);
            }
        }
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (Math.hypot(dx, dz) <= 2.2) c.set(cx + dx, h + 5, cz + dz, Material.OXIDIZED_CUT_COPPER);
        c.set(cx, h + 6, cz, Material.OXIDIZED_COPPER);
        c.fill(cx, h + 7, cz, cx, h + 9, cz, Material.LIGHTNING_ROD);
        // Плоская кровля остальной части с парапетом.
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            if (Math.hypot(x - cx, z - cz) <= 4.6) continue;
            c.set(x, h + 1, z, Material.STONE_BRICK_SLAB);
            boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
            if (edge && (x + z) % 2 == 0) c.set(x, h + 2, z, Material.STONE_BRICK_WALL);
        }
        for (int x : new int[]{0, w - 1}) for (int z : new int[]{0, d - 1}) { c.fill(x, h + 1, z, x, h + 3, z, Material.QUARTZ_PILLAR); c.set(x, h + 4, z, Material.LANTERN); }
        // Полки, столы, лестница на галерею.
        for (int x = 1; x < w - 1; x++) { c.fill(x, 1, d - 2, x, 3, d - 2, Material.BOOKSHELF); }
        for (int z = 2; z < d - 2; z++) { c.fill(1, 1, z, 1, 3, z, Material.BOOKSHELF); c.fill(w - 2, 1, z, w - 2, 3, z, Material.BOOKSHELF); }
        c.fill(4, 1, 6, 8, 1, 6, Material.DARK_OAK_PLANKS);
        c.set(6, 2, 6, Material.LECTERN);
        c.lantern(6, h, 6, true);
        c.sign(2, 4, -1, 0, -1, "", "&bБиблиотека", "", "");
        Kit.flowerBed(c, -3, -2, 2, 4, r);
        Kit.flowerBed(c, 14, -2, 2, 4, r);
    }
}
