package ru.unknownsmp.zhora;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Чистые (без Bukkit) функции Жоры: обращения, разбор намерений, фильтрация вывода, запасные реплики. Покрыто тестами. */
public final class ZhoraText {
    private ZhoraText() {}

    // ------------------------------------------------------------------ Обращение

    public static Pattern mentionPattern(Collection<String> triggers) {
        List<String> parts = new ArrayList<>();
        for (String t : triggers) if (t != null && !t.isBlank()) parts.add(Pattern.quote(t.trim().toLowerCase(Locale.ROOT)));
        if (parts.isEmpty()) parts.add("жора");
        return Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + String.join("|", parts) + ")(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public static boolean mentions(Pattern p, String text) {
        return text != null && p.matcher(text).find();
    }

    /** Убирает обращение из текста (для разбора намерения). */
    public static String stripMention(Pattern p, String text) {
        return p.matcher(text == null ? "" : text).replaceAll(" ").replaceAll("\\s+", " ").replaceAll("^[ ,.:;!?-]+", "").trim();
    }

    // ------------------------------------------------------------------ Намерения

    public enum Intent { GENERAL, COMPLAINT, MEMORY_STORE, MUTE_ME, UNMUTE_ME, HISTORY }

    private static final Pattern MUTE = Pattern.compile("(?iU)(не\\s+подкалывай|отстань\\s+от\\s+меня|перестань\\s+(шутить|подкалывать)|не\\s+шути\\s+(про|над)\\s+мен[яе]|хватит\\s+подкалывать)");
    private static final Pattern UNMUTE = Pattern.compile("(?iU)(можешь\\s+подкалывать|можно\\s+подкалывать|подкалывай\\s+(меня\\s+)?снова|снова\\s+подкалывай)");
    private static final Pattern REMEMBER = Pattern.compile("(?iU)\\bзапомни\\b");
    private static final Pattern CHEAT = Pattern.compile("(?iU)(читер|\\bчит(ит|ы|ов|ует|ом)?\\b|х-?рей|x-?ray|рентген|дюп|килаур|killaura|аим(бот)?|aimbot|флай|\\bfly\\b|спидхак|хакер|эксплойт|подозрит|нечестно|автокликер|автоклик|не\\s+честно)");
    private static final Pattern SUSP_Q = Pattern.compile("(?iU)(почему|откуда)\\s+у\\s+\\S+\\s+(так\\s+)?(много|столько)|слишком\\s+много\\s+\\S+\\s+у\\s+\\S+|проверь\\s+(его|её|ее|их)");
    private static final Pattern HISTORY = Pattern.compile("(?iU)(вчера|сегодня|на\\s+прошлой\\s+неделе|на\\s+этой\\s+неделе|за\\s+неделю|за\\s+месяц|кто\\s+(убил|начал|выиграл|победил|умер|больше\\s+всех)|сколько\\s+раз|с\\s+чего\\s+началась|что\\s+(произошло|было|случилось)|летопис|последн(ий|его)\\s+(турнир|ивент|дуэл)|история|статистик)");

    public static Intent classify(String text) {
        String t = text == null ? "" : text;
        if (MUTE.matcher(t).find()) return Intent.MUTE_ME;
        if (UNMUTE.matcher(t).find()) return Intent.UNMUTE_ME;
        if (CHEAT.matcher(t).find() || SUSP_Q.matcher(t).find()) return Intent.COMPLAINT;
        if (REMEMBER.matcher(t).find()) return Intent.MEMORY_STORE;
        if (HISTORY.matcher(t).find()) return Intent.HISTORY;
        return Intent.GENERAL;
    }

    /** Категория жалобы: xray, pvp, dupe, economy, other. */
    public static String complaintCategory(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (Pattern.compile("(х-?рей|x-?ray|рентген|алмаз|руд|шахт|древн|незерит|изумруд)").matcher(t).find()) return "xray";
        if (Pattern.compile("(дюп|дублир|размнож|быстро\\s+перемещает|предмет)").matcher(t).find()) return "dupe";
        if (Pattern.compile("(аим|aim|килаур|killaura|reach|бьёт|бьет|файт|пвп|pvp|автоклик|кликер|хитбокс)").matcher(t).find()) return "pvp";
        if (Pattern.compile("(деньги|монет|баланс|заработ|магазин|скуп|продаёт|продает)").matcher(t).find()) return "economy";
        return "other";
    }

    // ------------------------------------------------------------------ Время и тип запроса к летописи

    public record Range(long from, long to, String label) {}

    public static Range parseRange(String text, long now, ZoneId zone) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long startToday = today.atStartOfDay(zone).toInstant().toEpochMilli();
        long day = 86_400_000L;
        if (t.contains("вчера")) return new Range(startToday - day, startToday, "вчера");
        if (t.contains("прошлой неделе") || t.contains("прошлую неделю")) return new Range(now - 14 * day, now - 7 * day, "на прошлой неделе");
        if (t.contains("за месяц") || t.contains("в этом месяце")) return new Range(now - 30 * day, now, "за месяц");
        if (t.contains("за неделю") || t.contains("на этой неделе")) return new Range(now - 7 * day, now, "за неделю");
        if (t.contains("сегодня")) return new Range(startToday, now, "сегодня");
        return new Range(now - 7 * day, now, "за последнюю неделю");
    }

    /** Типы записей летописи, которые подразумевает вопрос (пусто — любые). */
    public static Set<String> typeHints(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        if (Pattern.compile("(убил|убийств|умер|смерт|погиб|умирал)").matcher(t).find()) { out.add("death"); out.add("kill"); }
        if (Pattern.compile("(дуэл|файт|1\\s*на\\s*1)").matcher(t).find()) out.add("duel");
        if (Pattern.compile("(войн|воюет|воевал)").matcher(t).find()) { out.add("war"); out.add("clan"); }
        if (Pattern.compile("(клан|союз)").matcher(t).find()) out.add("clan");
        if (Pattern.compile("(ивент|событи|турнир|метеорит|аномал)").matcher(t).find()) out.add("event");
        if (Pattern.compile("(торг|сделк|магазин|продал|купил|заработ|аукцион)").matcher(t).find()) out.add("trade");
        if (Pattern.compile("(босс|дракон|варден|иссушитель|визер)").matcher(t).find()) out.add("boss");
        return out;
    }

    // ------------------------------------------------------------------ Вывод

    /** Делает ответ модели безопасным для чата: без цветовых кодов, переносов, команд, ссылок-приманок и сверхдлинных строк. */
    public static String sanitize(String ai, int maxChars) {
        if (ai == null) return "";
        String s = ai.replace('\r', ' ').replace('\n', ' ').replace("§", "").replace("&", "");
        s = s.replaceAll("(?i)https?://\\S+", "[ссылка]");
        s = s.replaceAll("(?s)```.*?```", " ").replace("`", "").replace("*", "").replace("_", " ").replace("#", "");
        s = s.replaceAll("\\s+", " ").trim();
        while (s.startsWith("/") || s.startsWith("!") || s.startsWith("@")) s = s.substring(1).trim();
        s = s.replaceAll("(?i)@(everyone|here|all)", "");
        s = s.replaceAll("^[\"«“]+|[\"»”]+$", "").trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("жора:")) s = s.substring(5).trim();
        if (s.length() > maxChars) {
            int cut = Math.max(s.lastIndexOf(". ", maxChars), Math.max(s.lastIndexOf("! ", maxChars), s.lastIndexOf("? ", maxChars)));
            s = cut > maxChars / 2 ? s.substring(0, cut + 1) : s.substring(0, maxChars - 1).trim() + "…";
        }
        return s;
    }

    /** Нейтрализует управляющие конструкции в тексте игрока, прежде чем показывать его модели. */
    public static String quoteUserText(String s, int max) {
        if (s == null) return "";
        String r = s.replace('\n', ' ').replace('\r', ' ').replace("«", "\"").replace("»", "\"").replace("[", "(").replace("]", ")").trim();
        return r.length() > max ? r.substring(0, max) + "…" : r;
    }

    public static String normalizeAnswer(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    // ------------------------------------------------------------------ Стиль юмора

    public static String humorStyle(String level) {
        return switch (level == null ? "normal" : level.toLowerCase(Locale.ROOT)) {
            case "calm" -> "Спокойный лёгкий юмор: мягкая ирония, максимум дружеская шутка, без резких слов.";
            case "toxic" -> "Токсичный игровой юмор: колкие подколы, сарказм, преувеличения — как у друга, который слишком любит троллить. Без перехода на личность.";
            case "max" -> "Максимально дерзкий игровой стиль: язвительно, жёстко и смешно по игровым ошибкам и самоуверенности. Допустим сленг, но без мата в адрес людей, без угроз и без оскорблений по личным признакам.";
            default -> "Обычный игровой юмор: заметный сарказм и подколы, но по-дружески.";
        };
    }

    // ------------------------------------------------------------------ Запасные реплики (если ИИ недоступен)

    private static final Map<String, String[]> FALLBACK = Map.ofEntries(
            Map.entry("death_lava", new String[]{"{p} решил проверить, насколько лава тёплая. Вещи, говорят, тоже согрелись.", "Лава снова приняла {p} в свои тёплые объятия. Рекомендую не повторять.", "{p} и лава — любовь без взаимности: лава берёт вещи, {p} берёт новый респавн."}),
            Map.entry("death_void", new String[]{"{p} узнал, что у мира есть дно. Только оно оказалось очень далеко.", "{p} ушёл в пустоту. Говорят, там тихо и без налогов."}),
            Map.entry("death_fall", new String[]{"{p} проверил гравитацию. Она работает.", "{p}, высота — это не рекомендация, это закон."}),
            Map.entry("death_mob", new String[]{"{p} проиграл {k}. Это даже не босс.", "{k} сегодня в форме, а у {p} — нет."}),
            Map.entry("death_pvp", new String[]{"{k} забрал у {p} вещи и немного достоинства.", "{p} не проиграл файт, он перевёл вещи на {k} безвозмездно.", "{k} сегодня в роли налоговой для {p}."}),
            Map.entry("death_explosion", new String[]{"{p} встретил бабах. Бабах победил.", "{p}, шипение — это был не звук нетерпения."}),
            Map.entry("death_drown", new String[]{"{p} забыл, что жабры в моде не выдают.", "{p} решил, что воздух — это опция."}),
            Map.entry("death_fire", new String[]{"{p} слишком хорошо прожарился. Medium rare не получилось.", "{p}, огонь — это не мебель."}),
            Map.entry("death_starve", new String[]{"{p} забыл поесть. Сервер напоминает: хлеб существует.", "{p} умер от голода, хотя еды в мире хватало."}),
            Map.entry("death_other", new String[]{"{p} снова отправился на респавн. Регулярная активность.", "{p}, сервер уже начал вести для тебя отдельную статистику."}),
            Map.entry("valuable_loss", new String[]{"{p} потерял ценные вещи так красиво, что хочется поаплодировать.", "Инвентарь {p} сменил владельца, и это не было сделкой."}),
            Map.entry("totem", new String[]{"Тотем сработал у {p}, а вот план на жизнь — нет.", "{p} потратил тотем. Хороший был тотем."}),
            Map.entry("duel_win", new String[]{"{p} выиграл дуэль у {k}. Реванш будет, но не сегодня.", "{k} проиграл {p} и теперь изучает потолок арены."}),
            Map.entry("boss", new String[]{"{p} победил босса. Босс пока не комментирует.", "{p} убрал босса. Респект, а теперь вещи не потеряй."}),
            Map.entry("advancement", new String[]{"{p} получил достижение. Следующий шаг — не потерять его вместе с инвентарём.", "{p} растёт. Медленно, но красиво."}),
            Map.entry("event_start", new String[]{"Ивент начался. Все бегут, половина потеряет вещи.", "Началось. Готовьте инвентарь и нервы."}),
            Map.entry("event_end", new String[]{"Ивент закончился. Летопись зафиксировала героев и тех, кто просто бегал.", "Всё. Расходимся, считаем потери."}),
            Map.entry("clan", new String[]{"Новый клан в летописи. Посмотрим, сколько он проживёт до первого конфликта.", "Клан основан. Дипломатия — с завтрашнего дня."}),
            Map.entry("war", new String[]{"Война началась. Летопись уже нашла чистый лист.", "Объявлена война. Чат, держите попкорн."}),
            Map.entry("trade", new String[]{"Крупная сделка. Кто-то явно знает, как работает экономика.", "Деньги пошли в оборот. Следим."}),
            Map.entry("economy", new String[]{"На рынке заметное движение. Следим за деньгами.", "Экономика шевельнулась. Я записал."}),
            Map.entry("first-join", new String[]{"Новенький зашёл. Выдержите ли вы этот сервер — посмотрим.", "Свежий игрок. Пока ещё с целым инвентарём."}),
            Map.entry("api_down", new String[]{"Я сегодня без мозгов — связь с ИИ барахлит. Спроси чуть позже.", "Голова временно не отвечает. Повтори через минуту."}),
            Map.entry("busy", new String[]{"Не так быстро, я не робот. Ну, почти.", "Подожди секунду, я думаю. Иногда."}));

    public static String fallback(String type, Map<String, String> vars, Random rnd) {
        String[] arr = FALLBACK.getOrDefault(type, FALLBACK.get("death_other"));
        String s = arr[rnd.nextInt(arr.length)];
        for (var e : vars.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue() == null ? "?" : e.getValue());
        return s;
    }

    /** Классификация причины смерти по названию урона. */
    public static String deathType(String cause, boolean byPlayer) {
        if (byPlayer) return "death_pvp";
        String c = cause == null ? "" : cause.toUpperCase(Locale.ROOT);
        return switch (c) {
            case "LAVA", "HOT_FLOOR" -> "death_lava";
            case "VOID" -> "death_void";
            case "FALL", "FLY_INTO_WALL" -> "death_fall";
            case "ENTITY_ATTACK", "ENTITY_SWEEP_ATTACK", "PROJECTILE", "THORNS", "SONIC_BOOM" -> "death_mob";
            case "BLOCK_EXPLOSION", "ENTITY_EXPLOSION" -> "death_explosion";
            case "DROWNING" -> "death_drown";
            case "FIRE", "FIRE_TICK", "MELTING" -> "death_fire";
            case "STARVATION" -> "death_starve";
            default -> "death_other";
        };
    }

    public static List<String> extractNames(String text, Collection<String> knownLower) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = Pattern.compile("[\\p{L}\\p{N}_]{3,16}", Pattern.UNICODE_CHARACTER_CLASS).matcher(text);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (knownLower.contains(w) && !out.contains(w)) out.add(w);
        }
        return out;
    }

    /** Грубое склонение: «Селфача», «Селфачу», «Селфач» → пробуем найти ник по основе (минимум 4 символа). */
    public static String matchStem(String word, Collection<String> knownLower) {
        String w = word.toLowerCase(Locale.ROOT);
        if (knownLower.contains(w)) return w;
        for (int cut = 1; cut <= 3 && w.length() - cut >= 4; cut++) {
            String stem = w.substring(0, w.length() - cut);
            if (knownLower.contains(stem)) return stem;
        }
        return null;
    }
}
