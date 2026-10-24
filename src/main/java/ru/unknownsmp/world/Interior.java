package ru.unknownsmp.world;

import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Обстановка комнат после постройки дома. Работает по готовым блокам: находит свободные клетки пола внутри крытых помещений
 * (на любом этаже), обходит двери, лестницы и проёмы и расставляет мебель вдоль стен по теме здания.
 * Существующие блоки не трогает — только заполняет воздух.
 */
public final class Interior {
    private Interior() {}

    private record Cell(int x, int y, int z, int wx, int wz) {}

    private static boolean air(Block b) {
        return b.getType().isAir();
    }

    private static boolean full(Block b) {
        Material m = b.getType();
        return m.isOccluding() || m.name().endsWith("_PANE") || m.name().endsWith("GLASS");
    }

    /** Свободна ли клетка для мебели и стоит ли она на полу в закрытой комнате. */
    private static boolean floorCell(Canvas c, int x, int y, int z) {
        if (!air(c.at(x, y, z)) || !air(c.at(x, y + 1, z))) return false;
        Material below = c.at(x, y - 1, z).getType();
        if (!(below.isOccluding() || below.name().endsWith("_CARPET"))) return false;
        boolean roof = false;
        for (int k = 2; k <= 4; k++) if (full(c.at(x, y + k, z))) { roof = true; break; }
        if (!roof) return false;
        int walls = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            for (int k = 1; k <= 12; k++) {
                if (full(c.at(x + d[0] * k, y, z + d[1] * k)) || full(c.at(x + d[0] * k, y + 1, z + d[1] * k))) { walls++; break; }
            }
        }
        return walls == 4;
    }

    private static boolean nearPassage(Canvas c, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 1; dy++) {
            String n = c.at(x + dx, y + dy, z + dz).getType().name();
            if (n.contains("DOOR") || n.contains("STAIRS") || n.equals("LADDER") || n.contains("FENCE_GATE")) return true;
        }
        return false;
    }

    public static void furnish(Canvas c, String id, int w, int d, Random rnd) {
        if (id.startsWith("stall") || id.equals("stable") || id.equals("markethall")) return;
        String theme = themeOf(id);
        for (int y = 1; y <= 12; y++) {
            List<Cell> wallCells = new ArrayList<>();
            List<Cell> free = new ArrayList<>();
            boolean[][] taken = new boolean[w + 2][d + 2];
            for (int x = 1; x < w - 1; x++) for (int z = 1; z < d - 1; z++) {
                if (!floorCell(c, x, y, z) || nearPassage(c, x, y, z)) continue;
                free.add(new Cell(x, y, z, 0, 0));
                int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] dd : dirs) {
                    if (full(c.at(x + dd[0], y, z + dd[1])) && !c.at(x + dd[0], y, z + dd[1]).getType().name().contains("DOOR")) {
                        wallCells.add(new Cell(x, y, z, dd[0], dd[1]));
                        break;
                    }
                }
            }
            if (free.size() < 6) continue;
            Collections.shuffle(wallCells, rnd);
            Collections.shuffle(free, rnd);
            fill(c, theme, y, wallCells, free, taken, rnd);
            ceilingLights(c, y, free, rnd);
        }
    }

    private static String themeOf(String id) {
        return switch (id) {
            case "tavern" -> "tavern";
            case "smithy" -> "smithy";
            case "library" -> "library";
            case "bank" -> "bank";
            case "alchemist" -> "alchemist";
            case "bakery" -> "bakery";
            case "townhall" -> "hall";
            case "guardhouse" -> "guard";
            case "windmill", "watchtower" -> "storage";
            default -> "home";
        };
    }

    private static boolean ok(boolean[][] taken, Cell cell) {
        return !taken[cell.x()][cell.z()];
    }

    private static void mark(boolean[][] taken, int x, int z) {
        if (x >= 0 && z >= 0 && x < taken.length && z < taken[0].length) taken[x][z] = true;
    }

    private static void fill(Canvas c, String theme, int y, List<Cell> wall, List<Cell> free, boolean[][] taken, Random rnd) {
        int placedBeds = 0, placed = 0;
        int limit = Math.max(4, Math.min(16, free.size() / 3));
        Material wood = switch (theme) { case "tavern", "guard" -> Material.SPRUCE_PLANKS; default -> Material.OAK_PLANKS; };
        for (Cell cell : wall) {
            if (placed >= limit) break;
            if (!ok(taken, cell)) continue;
            int x = cell.x(), z = cell.z(), fx = -cell.wx(), fz = -cell.wz(); // лицом в комнату
            Material item = pick(theme, rnd, placedBeds);
            if (item == Material.RED_BED) {
                // Кровать вдоль стены: изголовье у стены, ноги — в комнату.
                int hx = x + fx, hz = z + fz;
                if (hx < 0 || hz < 0 || hx >= taken.length || hz >= taken[0].length) continue;
                Cell far = new Cell(hx, y, hz, 0, 0);
                if (!ok(taken, far) || !floorCell(c, hx, y, hz) || nearPassage(c, hx, y, hz)) continue;
                Material[] beds = {Material.RED_BED, Material.BLUE_BED, Material.GREEN_BED, Material.YELLOW_BED, Material.WHITE_BED};
                c.bed(hx, y, hz, beds[rnd.nextInt(beds.length)], -fx, -fz);
                mark(taken, x, z);
                mark(taken, hx, hz);
                placedBeds++;
                placed++;
                continue;
            }
            switch (item) {
                case BOOKSHELF -> { c.set(x, y, z, Material.BOOKSHELF); c.set(x, y + 1, z, Material.BOOKSHELF); }
                case BARREL, CHEST, FURNACE, SMOKER, BLAST_FURNACE, SMITHING_TABLE, FLETCHING_TABLE, CARTOGRAPHY_TABLE -> {
                    c.dir(x, y, z, item, fx, fz);
                    if (item == Material.BARREL && rnd.nextInt(3) == 0) c.dir(x, y + 1, z, Material.BARREL, 0, -1);
                    else if (rnd.nextInt(3) == 0) Kit.flowerPot(c, x, y + 1, z, rnd);
                }
                case ANVIL -> c.dir(x, y, z, Material.ANVIL, fz != 0 ? 1 : 0, fx != 0 ? 1 : 0);
                case LECTERN -> c.dir(x, y, z, Material.LECTERN, fx, fz);
                case CRAFTING_TABLE, GRINDSTONE, CAULDRON, BREWING_STAND, COMPOSTER, LOOM -> {
                    if (item == Material.GRINDSTONE) c.dir(x, y, z, item, fx, fz); else c.set(x, y, z, item);
                }
                case HAY_BLOCK -> c.set(x, y, z, Material.HAY_BLOCK);
                default -> { continue; }
            }
            mark(taken, x, z);
            placed++;
        }
        // Стол со стульями (или длинный стол) в середине больших комнат.
        int tables = 0;
        for (Cell cell : free) {
            if (tables >= (theme.equals("tavern") ? 4 : theme.equals("hall") ? 2 : 1)) break;
            int x = cell.x(), z = cell.z();
            if (x < 2 || z < 2 || x >= taken.length - 3 || z >= taken[0].length - 3) continue;
            boolean clear = true;
            for (int dx = -1; dx <= 1 && clear; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (taken[x + dx][z + dz] || !floorCell(c, x + dx, y, z + dz) || nearPassage(c, x + dx, y, z + dz)) { clear = false; break; }
            }
            if (!clear) continue;
            c.set(x, y, z, Material.OAK_FENCE);
            c.set(x, y + 1, z, rnd.nextBoolean() ? Material.OAK_PRESSURE_PLATE : Material.SPRUCE_PRESSURE_PLATE);
            if (rnd.nextInt(3) == 0) c.set(x, y + 1, z, Material.FLOWER_POT);
            Material seat = theme.equals("tavern") || theme.equals("guard") ? Material.SPRUCE_STAIRS : Material.OAK_STAIRS;
            int[][] seats = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] s : seats) {
                if (rnd.nextInt(4) == 0) continue;
                c.stair(x + s[0], y, z + s[1], seat, -s[0], -s[1], false);
            }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) mark(taken, x + dx, z + dz);
            tables++;
        }
        // Ковёр посреди комнаты.
        if (!theme.equals("storage") && !theme.equals("smithy")) {
            Material[] rugs = {Material.RED_CARPET, Material.BROWN_CARPET, Material.GREEN_CARPET, Material.BLUE_CARPET, Material.WHITE_CARPET};
            Material rug = rugs[rnd.nextInt(rugs.length)];
            int rugged = 0;
            for (Cell cell : free) {
                if (rugged >= 9) break;
                if (taken[cell.x()][cell.z()]) continue;
                if (!air(c.at(cell.x(), y, cell.z()))) continue;
                c.set(cell.x(), y, cell.z(), rug);
                rugged++;
            }
        }
    }

    private static Material pick(String theme, Random rnd, int beds) {
        Material[] pool = switch (theme) {
            case "tavern" -> new Material[]{Material.BARREL, Material.BARREL, Material.CHEST, Material.BOOKSHELF, Material.FURNACE, Material.BARREL};
            case "smithy" -> new Material[]{Material.ANVIL, Material.BLAST_FURNACE, Material.FURNACE, Material.SMITHING_TABLE, Material.GRINDSTONE, Material.CHEST, Material.CAULDRON, Material.BARREL};
            case "library" -> new Material[]{Material.BOOKSHELF, Material.BOOKSHELF, Material.BOOKSHELF, Material.LECTERN, Material.CARTOGRAPHY_TABLE, Material.BOOKSHELF};
            case "bank" -> new Material[]{Material.CHEST, Material.BARREL, Material.CHEST, Material.BOOKSHELF, Material.CHEST};
            case "alchemist" -> new Material[]{Material.BREWING_STAND, Material.CAULDRON, Material.BOOKSHELF, Material.BARREL, Material.BREWING_STAND, Material.CHEST};
            case "bakery" -> new Material[]{Material.SMOKER, Material.FURNACE, Material.BARREL, Material.HAY_BLOCK, Material.COMPOSTER, Material.CHEST};
            case "hall" -> new Material[]{Material.BOOKSHELF, Material.LECTERN, Material.CARTOGRAPHY_TABLE, Material.CHEST, Material.BOOKSHELF};
            case "guard" -> new Material[]{Material.RED_BED, Material.CHEST, Material.FLETCHING_TABLE, Material.SMITHING_TABLE, Material.BARREL, Material.RED_BED};
            case "storage" -> new Material[]{Material.BARREL, Material.CHEST, Material.BARREL, Material.HAY_BLOCK, Material.BARREL};
            default -> new Material[]{Material.RED_BED, Material.BOOKSHELF, Material.CHEST, Material.CRAFTING_TABLE, Material.FURNACE, Material.BARREL, Material.LOOM, Material.RED_BED};
        };
        Material m = pool[rnd.nextInt(pool.length)];
        if (m == Material.RED_BED && beds >= 2) return Material.CHEST;
        return m;
    }

    /** Подвесные фонари по комнате, если потолок достаточно высокий. */
    private static void ceilingLights(Canvas c, int y, List<Cell> free, Random rnd) {
        int n = 0;
        for (Cell cell : free) {
            if (n >= 3) break;
            int x = cell.x(), z = cell.z();
            for (int h = 3; h <= 5; h++) {
                if (full(c.at(x, y + h, z)) && air(c.at(x, y + h - 1, z))) {
                    if (rnd.nextInt(3) == 0 && !nearPassage(c, x, y, z)) {
                        if (h > 3) c.set(x, y + h - 1, z, Material.IRON_CHAIN);
                        c.lantern(x, y + (h > 3 ? h - 2 : h - 1), z, true);
                        n++;
                    }
                    break;
                }
            }
        }
    }
}
