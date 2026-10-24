package ru.unknownsmp.market;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.TransmuteRecipe;
import ru.unknownsmp.core.UnknownSMP;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Стоимость предметов. Цены задаются только «сырью» (market.yml → values), остальное выводится из рецептов игры:
 * стоимость результата = сумма стоимости ингредиентов / количество (минимум по всем рецептам), без надбавки.
 * Поэтому крафт, переплавка и автокрафтеры никогда не увеличивают стоимость — на этом нельзя заработать.
 *
 * Метки распространяются по самому дешёвому рецепту: N — требует Незера, E — Края, P — «премиум» (алмазы и выше).
 */
public final class ValueService {
    public static final int TAG_NETHER = 1, TAG_END = 2, TAG_PREMIUM = 4;

    private final UnknownSMP plugin;
    private final Map<Material, Double> values = new EnumMap<>(Material.class);
    private final Map<Material, Integer> tags = new EnumMap<>(Material.class);
    private final Set<Material> fixed = EnumSet.noneOf(Material.class);

    /** Ёмкости, которые возвращаются при крафте (ведро от молока и т.п.). */
    private static final Map<Material, Material> CONTAINER = new EnumMap<>(Material.class);

    static {
        CONTAINER.put(Material.MILK_BUCKET, Material.BUCKET);
        CONTAINER.put(Material.WATER_BUCKET, Material.BUCKET);
        CONTAINER.put(Material.LAVA_BUCKET, Material.BUCKET);
        CONTAINER.put(Material.POWDER_SNOW_BUCKET, Material.BUCKET);
        CONTAINER.put(Material.HONEY_BOTTLE, Material.GLASS_BOTTLE);
        CONTAINER.put(Material.DRAGON_BREATH, Material.GLASS_BOTTLE);
    }

    public ValueService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    public synchronized void compute() {
        values.clear();
        tags.clear();
        fixed.clear();
        YamlConfiguration c = plugin.configs().get("market.yml");
        // Масштаб цен и минимальная цена скупки для «сырья»: цена любой скупки сырья не ниже min-sell-price.
        double scale = Math.max(0.0001, c.getDouble("pricing.value-scale", 1.0));
        double sellFr = Math.max(0.01, Math.min(0.95, c.getDouble("pricing.sell-fraction", 0.45)));
        double minRaw = Math.max(0, c.getDouble("pricing.min-sell-price", 0)) / sellFr;
        ConfigurationSection vs = c.getConfigurationSection("values");
        if (vs != null) for (String k : vs.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m == null) { plugin.getLogger().warning("market.yml → values: неизвестный предмет " + k); continue; }
            double v = Math.max(vs.getDouble(k) * scale, minRaw);
            if (v > 0) values.put(m, v);
        }
        mark(c.getStringList("tags.nether"), TAG_NETHER);
        mark(c.getStringList("tags.end"), TAG_END);
        mark(c.getStringList("tags.premium"), TAG_PREMIUM);
        // Фиксированные цены (особые предметы): не пересчитываются рецептами.
        ConfigurationSection fx = c.getConfigurationSection("fixed-values");
        if (fx != null) for (String k : fx.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m == null) continue;
            double v = fx.getDouble(k) * scale;
            if (v > 0) { values.put(m, v); fixed.add(m); }
        }

        List<Recipe> recipes = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            try { recipes.add(it.next()); } catch (Throwable ignored) {}
        }
        double fuel = values.getOrDefault(Material.COAL, 4.0) / 8.0;
        for (int pass = 0; pass < 25; pass++) {
            boolean changed = false;
            changed |= fluids();
            for (Recipe r : recipes) {
                try {
                    changed |= apply(r, fuel);
                } catch (Throwable ignored) {
                    // Рецепт нестандартного вида — пропускаем.
                }
            }
            if (!changed) break;
        }
        plugin.getLogger().info("Экономика: рассчитана стоимость " + values.size() + " предметов (из " + recipes.size() + " рецептов).");
    }

    private void mark(List<String> list, int tag) {
        for (String s : list) {
            Material m = Material.matchMaterial(s);
            if (m != null) tags.merge(m, tag, (a, b) -> a | b);
        }
    }

    /** Вёдра и бутылки с жидкостью = тара + немного. */
    private boolean fluids() {
        boolean ch = false;
        Double bucket = values.get(Material.BUCKET);
        if (bucket != null) {
            ch |= offer(Material.WATER_BUCKET, bucket + 0.2, 0);
            ch |= offer(Material.LAVA_BUCKET, bucket + 2, 0);
            ch |= offer(Material.MILK_BUCKET, bucket + 1, 0);
            ch |= offer(Material.POWDER_SNOW_BUCKET, bucket + 1, 0);
        }
        return ch;
    }

    private boolean offer(Material m, double v, int tag) {
        if (fixed.contains(m) || !Double.isFinite(v) || v <= 0) return false;
        Double cur = values.get(m);
        if (cur == null || v < cur - 1e-9) {
            values.put(m, v);
            // Метки берутся от самого дешёвого пути получения.
            tags.put(m, tag);
            return true;
        }
        return false;
    }

    /** Стоимость варианта ингредиента: самый дешёвый из допустимых предметов. */
    private double[] choice(RecipeChoice ch) {
        if (ch == null) return new double[]{0, 0};
        List<Material> mats = new ArrayList<>();
        if (ch instanceof RecipeChoice.MaterialChoice mc) mats.addAll(mc.getChoices());
        else if (ch instanceof RecipeChoice.ExactChoice ec) for (ItemStack is : ec.getChoices()) mats.add(is.getType());
        else return null;
        double best = Double.MAX_VALUE;
        int bestTag = 0;
        for (Material m : mats) {
            Double v = values.get(m);
            if (v == null) continue;
            Material cont = CONTAINER.get(m);
            double val = v - (cont != null ? values.getOrDefault(cont, 0.0) : 0);
            if (val < 0) val = 0;
            if (val < best) { best = val; bestTag = tags.getOrDefault(m, 0); }
        }
        return best == Double.MAX_VALUE ? null : new double[]{best, bestTag};
    }

    private boolean apply(Recipe r, double fuel) {
        ItemStack res = r.getResult();
        if (res == null || res.getType().isAir() || res.getAmount() <= 0) return false;
        double cost = 0;
        int tag = 0;
        List<RecipeChoice> ins = new ArrayList<>();
        if (r instanceof ShapedRecipe sr) {
            Map<Character, RecipeChoice> map = sr.getChoiceMap();
            for (String row : sr.getShape()) for (char ch : row.toCharArray()) {
                RecipeChoice rc = map.get(ch);
                if (rc != null) ins.add(rc);
            }
        } else if (r instanceof ShapelessRecipe sl) {
            ins.addAll(sl.getChoiceList());
        } else if (r instanceof CookingRecipe<?> cr) {
            ins.add(cr.getInputChoice());
            cost += fuel;
        } else if (r instanceof StonecuttingRecipe st) {
            ins.add(st.getInputChoice());
        } else if (r instanceof SmithingTransformRecipe sm) {
            ins.add(sm.getTemplate());
            ins.add(sm.getBase());
            ins.add(sm.getAddition());
        } else if (r instanceof TransmuteRecipe tr) {
            ins.add(tr.getInput());
            ins.add(tr.getMaterial());
        } else {
            return false;
        }
        if (ins.isEmpty()) return false;
        for (RecipeChoice rc : ins) {
            if (rc == null) continue;
            double[] v = choice(rc);
            if (v == null) return false; // ингредиент пока без цены
            cost += v[0];
            tag |= (int) v[1];
        }
        // Уголь сам по себе может получаться из рецепта переплавки — не допускаем нулевой стоимости.
        if (cost <= 0) return false;
        return offer(res.getType(), cost / res.getAmount(), tag);
    }

    /** Проверка экономики: рецепты, где результат «дороже» ингредиентов, и цепочки «купить → скрафтить → продать» с прибылью. */
    public synchronized List<String> audit(java.util.function.ToLongFunction<Material> buy, java.util.function.ToDoubleFunction<Material> sell) {
        List<String> out = new ArrayList<>();
        double fuel = values.getOrDefault(Material.COAL, 4.0) / 8.0;
        Iterator<Recipe> it = Bukkit.recipeIterator();
        int n = 0;
        while (it.hasNext()) {
            Recipe r;
            try { r = it.next(); } catch (Throwable t) { continue; }
            ItemStack res = r.getResult();
            if (res == null || res.getType().isAir() || res.getAmount() <= 0) continue;
            double cost = 0;
            double buyCost = 0, sellIn = 0;
            boolean sellable = true;
            boolean buyable = true;
            List<RecipeChoice> ins = new ArrayList<>();
            if (r instanceof ShapedRecipe sr) {
                Map<Character, RecipeChoice> map = sr.getChoiceMap();
                for (String row : sr.getShape()) for (char ch : row.toCharArray()) { RecipeChoice rc = map.get(ch); if (rc != null) ins.add(rc); }
            } else if (r instanceof ShapelessRecipe sl) ins.addAll(sl.getChoiceList());
            else if (r instanceof CookingRecipe<?> cr) { ins.add(cr.getInputChoice()); cost += fuel; }
            else if (r instanceof StonecuttingRecipe st) ins.add(st.getInputChoice());
            else continue;
            boolean ok = true;
            for (RecipeChoice rc : ins) {
                if (rc == null) continue;
                double[] v = choice(rc);
                if (v == null) { ok = false; break; }
                cost += v[0];
                // Минимальная цена покупки среди вариантов ингредиента.
                long bestBuy = Long.MAX_VALUE;
                List<Material> mats = new ArrayList<>();
                if (rc instanceof RecipeChoice.MaterialChoice mc) mats.addAll(mc.getChoices());
                else if (rc instanceof RecipeChoice.ExactChoice ec) for (ItemStack is : ec.getChoices()) mats.add(is.getType());
                double minSell = Double.MAX_VALUE;
                for (Material m : mats) { long b = buy.applyAsLong(m); if (b > 0 && b < bestBuy) bestBuy = b; double sv = sell.applyAsDouble(m); if (sv > 0 && sv < minSell) minSell = sv; }
                if (minSell == Double.MAX_VALUE) sellable = false; else sellIn += minSell;
                if (bestBuy == Long.MAX_VALUE) buyable = false; else buyCost += bestBuy;
            }
            if (!ok || cost <= 0) continue;
            n++;
            Material o = res.getType();
            Double vo = values.get(o);
            if (vo != null && !fixed.contains(o) && vo * res.getAmount() > cost * 1.0001)
                out.add(String.format("VALUE %s x%d: стоимость %.2f > ингредиенты %.2f", o, res.getAmount(), vo * res.getAmount(), cost));
            if (sellable) {
                double so = sell.applyAsDouble(res.getType()) * res.getAmount();
                if (so > sellIn + 0.001) out.add(String.format("SELLCHAIN продать ингредиенты за %.1f, а результат %s x%d за %.0f (+%.1f)", sellIn, res.getType(), res.getAmount(), so, so - sellIn));
            }
            if (buyable) {
                double s = sell.applyAsDouble(o) * res.getAmount();
                if (s > buyCost) out.add(String.format("ARB купить ингредиенты за %.0f, продать %s x%d за %.0f (+%.0f)", buyCost, o, res.getAmount(), s, s - buyCost));
            }
        }
        out.add("Проверено рецептов: " + n);
        for (Material m : fixed) out.add("FIXED " + m + " = " + values.get(m));
        return out;
    }

    public Double value(Material m) {
        return values.get(m);
    }

    public int tags(Material m) {
        return tags.getOrDefault(m, 0);
    }

    public Map<Material, Double> all() {
        return values;
    }
}
