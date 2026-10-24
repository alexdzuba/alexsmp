package ru.unknownsmp.zhora;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.unknownsmp.core.UnknownSMP;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Клиент внешнего ИИ-провайдера (OpenAI-совместимый Chat Completions). Все вызовы асинхронные, с таймаутами,
 * ограничением частоты, предохранителем при серии ошибок и без попадания ключа в логи/сообщения об ошибках.
 * Чтобы сменить провайдера, достаточно заменить эту реализацию — остальной код работает через {@link #chat}.
 */
public final class AiClient {
    public record Message(String role, String content) {
        public static Message system(String c) { return new Message("system", c); }
        public static Message user(String c) { return new Message("user", c); }
        public static Message assistant(String c) { return new Message("assistant", c); }
    }

    /** Ошибка обращения к ИИ с безопасным (без секретов) описанием. */
    public static final class AiException extends Exception {
        public final int status;
        public final boolean unavailable;

        public AiException(String msg, int status, boolean unavailable) {
            super(msg);
            this.status = status;
            this.unavailable = unavailable;
        }
    }

    private final UnknownSMP plugin;
    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "zhora-ai");
        t.setDaemon(true);
        return t;
    });
    private volatile HttpClient http;
    private volatile String endpoint = "";
    private volatile String key = "";
    private volatile String model = "";
    private volatile double temperature = 0.9;
    private volatile int maxTokens = 600;
    private volatile int timeoutSec = 30;
    private volatile int rpm = 40;
    private volatile int breakerFailures = 4;
    private volatile int breakerPause = 60;
    private volatile Semaphore concurrency = new Semaphore(3);
    private volatile JsonObject extraBody = new JsonObject();
    private final ArrayDeque<Long> window = new ArrayDeque<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong pausedUntil = new AtomicLong();
    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong totalFailures = new AtomicLong();
    private volatile String lastError = "";

    public AiClient(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    public void reload(YamlConfiguration cfg) {
        ConfigurationSection a = cfg.getConfigurationSection("api");
        if (a == null) a = cfg.createSection("api");
        endpoint = a.getString("endpoint", "").trim().replaceAll("/+$", "");
        String k = a.getString("key", "").trim();
        if (k.isEmpty()) {
            String env = a.getString("key-env", "ZHORA_API_KEY");
            String ev = env == null || env.isBlank() ? null : System.getenv(env);
            k = ev == null ? "" : ev.trim();
        }
        key = k;
        model = a.getString("model", "").trim();
        temperature = a.getDouble("temperature", 0.9);
        maxTokens = Math.max(64, a.getInt("max-tokens", 600));
        timeoutSec = Math.max(5, a.getInt("timeout-seconds", 30));
        rpm = Math.max(1, a.getInt("requests-per-minute", 40));
        breakerFailures = Math.max(1, a.getInt("breaker-failures", 4));
        breakerPause = Math.max(5, a.getInt("breaker-pause-seconds", 60));
        concurrency = new Semaphore(Math.max(1, Math.min(3, a.getInt("max-concurrent", 3))));
        JsonObject extra = new JsonObject();
        ConfigurationSection ex = a.getConfigurationSection("extra-body");
        if (ex != null) {
            for (String kk : ex.getKeys(false)) {
                Object v = ex.get(kk);
                if (v instanceof Number n) extra.addProperty(kk, n);
                else if (v instanceof Boolean b) extra.addProperty(kk, b);
                else if (v != null) extra.addProperty(kk, String.valueOf(v));
            }
        }
        extraBody = extra;
        HttpClient.Builder hb = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(2, a.getInt("connect-timeout-seconds", 8))))
                .executor(executor);
        if (!a.getBoolean("verify-tls", true)) {
            try {
                javax.net.ssl.TrustManager[] tm = {new javax.net.ssl.X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] c, String s) { }
                    @Override public void checkServerTrusted(X509Certificate[] c, String s) { }
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }};
                javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
                ctx.init(null, tm, new SecureRandom());
                hb.sslContext(ctx);
                plugin.getLogger().warning("[Жора] verify-tls выключен: соединение с ИИ-провайдером НЕ проверяет сертификат.");
            } catch (Exception e) {
                plugin.getLogger().warning("[Жора] Не удалось отключить проверку TLS: " + e.getClass().getSimpleName());
            }
        }
        http = hb.build();
        consecutiveFailures.set(0);
        pausedUntil.set(0);
    }

    public Executor executor() { return executor; }

    public boolean configured() { return !key.isEmpty() && !endpoint.isEmpty() && !model.isEmpty(); }

    public boolean available() { return configured() && System.currentTimeMillis() >= pausedUntil.get(); }

    public String model() { return model; }

    public String lastError() { return lastError; }

    public long calls() { return totalCalls.get(); }

    public long failures() { return totalFailures.get(); }

    public long pausedForMs() { return Math.max(0, pausedUntil.get() - System.currentTimeMillis()); }

    public void shutdown() {
        executor.shutdownNow();
    }

    private boolean takeRateSlot() {
        long now = System.currentTimeMillis();
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000) window.pollFirst();
            if (window.size() >= rpm) return false;
            window.addLast(now);
            return true;
        }
    }

    /** Отправить диалог. Результат — только итоговый текст ответа без «размышлений». */
    public CompletableFuture<String> chat(List<Message> messages, Double temp, Integer maxTok) {
        CompletableFuture<String> out = new CompletableFuture<>();
        if (!configured()) {
            out.completeExceptionally(new AiException("ИИ не настроен (api.key/endpoint/model)", 0, true));
            return out;
        }
        if (System.currentTimeMillis() < pausedUntil.get()) {
            out.completeExceptionally(new AiException("ИИ временно недоступен (пауза после ошибок)", 0, true));
            return out;
        }
        if (!takeRateSlot()) {
            out.completeExceptionally(new AiException("Превышен лимит запросов в минуту", 429, false));
            return out;
        }
        executor.execute(() -> {
            boolean got = false;
            try {
                got = concurrency.tryAcquire(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
                if (!got) throw new AiException("Очередь запросов к ИИ переполнена", 0, false);
                String text = call(messages, temp == null ? temperature : temp, maxTok == null ? maxTokens : maxTok, true);
                consecutiveFailures.set(0);
                out.complete(text);
            } catch (AiException e) {
                failed(e);
                out.completeExceptionally(e);
            } catch (Throwable t) {
                AiException e = new AiException("Сбой обращения к ИИ: " + t.getClass().getSimpleName(), 0, true);
                failed(e);
                out.completeExceptionally(e);
            } finally {
                if (got) concurrency.release();
            }
        });
        return out;
    }

    private void failed(AiException e) {
        totalFailures.incrementAndGet();
        lastError = scrub(e.getMessage());
        if (e.status == 429 || e.status == 0 && !e.unavailable) return;
        if (consecutiveFailures.incrementAndGet() >= breakerFailures) {
            pausedUntil.set(System.currentTimeMillis() + breakerPause * 1000L);
            consecutiveFailures.set(0);
            plugin.getLogger().warning("[Жора] ИИ-провайдер недоступен, пауза " + breakerPause + " с. Причина: " + lastError);
        }
    }

    private String call(List<Message> messages, double temp, int maxTok, boolean retryLonger) throws Exception {
        totalCalls.incrementAndGet();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray arr = new JsonArray();
        for (Message m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            arr.add(o);
        }
        body.add("messages", arr);
        body.addProperty("temperature", temp);
        body.addProperty("max_tokens", maxTok);
        for (var en : extraBody.entrySet()) body.add(en.getKey(), en.getValue());
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new AiException("Таймаут ответа ИИ", 0, true);
        } catch (java.io.IOException e) {
            throw new AiException("ИИ недоступен: " + scrub(e.getClass().getSimpleName() + " " + e.getMessage()), 0, true);
        }
        int st = resp.statusCode();
        if (st == 429) throw new AiException("Провайдер ограничил частоту запросов (429)", 429, false);
        if (st == 401 || st == 403) throw new AiException("Ключ API отклонён провайдером (" + st + ")", st, true);
        if (st >= 500) throw new AiException("Ошибка провайдера " + st, st, true);
        if (st != 200) throw new AiException("Провайдер вернул " + st + ": " + scrub(shorten(resp.body(), 160)), st, false);
        JsonObject root;
        try {
            root = JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new AiException("Некорректный ответ провайдера", st, false);
        }
        JsonArray choices = root.has("choices") && root.get("choices").isJsonArray() ? root.getAsJsonArray("choices") : null;
        if (choices == null || choices.isEmpty()) throw new AiException("Пустой ответ провайдера", st, false);
        JsonObject choice = choices.get(0).getAsJsonObject();
        String finish = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull() ? choice.get("finish_reason").getAsString() : "";
        String content = "";
        if (choice.has("message") && choice.get("message").isJsonObject()) {
            JsonElement c = choice.getAsJsonObject("message").get("content");
            if (c != null && !c.isJsonNull()) content = c.getAsString();
        }
        content = content.replaceAll("(?s)<think>.*?</think>", "").trim();
        if (content.isEmpty()) {
            if (retryLonger && finish.equals("length") && maxTok < 2400) return call(messages, temp, Math.min(2400, maxTok * 2), false);
            throw new AiException("ИИ не вернул текст (finish=" + finish + ")", st, false);
        }
        return content;
    }

    /** Убирает из текста секреты (ключ, заголовки авторизации). */
    public String scrub(String s) {
        if (s == null) return "";
        String r = s;
        if (!key.isEmpty()) r = r.replace(key, "***");
        return r.replaceAll("(?i)bearer\\s+[A-Za-z0-9._\\-]+", "Bearer ***");
    }

    private static String shorten(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ");
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
