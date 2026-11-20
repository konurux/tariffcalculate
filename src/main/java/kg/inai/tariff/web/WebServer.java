package kg.inai.tariff.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.sun.net.httpserver.HttpExchange;
import kg.inai.tariff.llm.Explainer;
import com.sun.net.httpserver.HttpServer;
import kg.inai.tariff.core.MissingCurrentTariffException;
import kg.inai.tariff.core.TariffService;
import kg.inai.tariff.io.Json;
import kg.inai.tariff.io.UsageCsvParser;
import kg.inai.tariff.model.Tariff;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Веб-форма (бонус) на встроенном JDK HttpServer — без внешних зависимостей.
 * <pre>
 *   GET  /                      страница с формой и графиком
 *   GET  /api/tariffs           список тарифов (для выпадающего списка)
 *   GET  /api/samples           примеры usage_*.csv из папки данных
 *   POST /api/recommend?current=T2&amp;name=usage_3.csv   тело — текст CSV → JSON
 *   GET  /api/recommend?sample=usage_3.csv[&amp;current=T2] расчёт по примеру
 *   POST /api/explain           тело — JSON recommend() → объяснение простым языком (LLM или шаблон)
 * </pre>
 */
public final class WebServer {

    private static final int MAX_UPLOAD = 20 * 1024 * 1024;

    private final TariffService service;
    private final Path dataDir;
    private final Explainer explainer;

    public WebServer(TariffService service, Path dataDir, Explainer explainer) {
        this.service = service;
        this.dataDir = dataDir;
        this.explainer = explainer;
    }

    public HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return server;
    }

    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            switch (path) {
                case "/", "/index.html" -> sendResource(ex, "/web/index.html", "text/html; charset=utf-8");
                case "/api/tariffs" -> sendJson(ex, 200, tariffs());
                case "/api/samples" -> sendJson(ex, 200, samples());
                case "/api/recommend" -> recommend(ex, q);
                case "/api/validate" -> validate(ex);
                case "/api/explain" -> explain(ex);
                default -> sendJson(ex, 404, Map.of("error", "not found: " + path));
            }
        } catch (MissingCurrentTariffException e) {
            trySend(ex, 400, Map.of("error", e.getMessage(), "code", "current_tariff_missing"));
        } catch (IllegalArgumentException e) {
            trySend(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            trySend(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        } finally {
            ex.close();
        }
    }

    private static void trySend(HttpExchange ex, int status, Map<String, ?> body) {
        try {
            sendJson(ex, status, body);
        } catch (IOException ignored) {
        }
    }

    /** POST /api/validate, тело — текст CSV: проверка структуры тем же парсером, без расчёта тарифов. */
    private void validate(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            throw new IllegalArgumentException("Отправьте CSV методом POST");
        }
        String csv = readBody(ex.getRequestBody());
        if (csv.isBlank()) {
            throw new IllegalArgumentException("Файл пустой");
        }
        UsageCsvParser.Result parsed = UsageCsvParser.parse(csv);
        if (parsed.records().isEmpty() && parsed.skipped() > 0) {
            throw new IllegalArgumentException("Ни одной корректной строки: все " + parsed.skipped() + " строк с ошибками");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("records", parsed.records().size());
        out.put("skipped_rows", parsed.skipped());
        sendJson(ex, 200, out);
    }

    private void recommend(HttpExchange ex, Map<String, String> q) throws IOException {
        String current = blankToNull(q.get("current"));
        Map<String, Object> result;
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String csv = readBody(ex.getRequestBody());
            String name = q.getOrDefault("name", "upload.csv");
            result = service.recommendCsv(name, csv, current);
        } else {
            String sample = q.get("sample");
            if (sample == null || !sample.matches("usage_[\\w-]+\\.csv")) {
                throw new IllegalArgumentException("Укажите sample=usage_<id>.csv или отправьте CSV методом POST");
            }
            Path f = dataDir.resolve(sample);
            if (!Files.exists(f)) {
                throw new IllegalArgumentException("Нет файла " + sample);
            }
            result = service.recommend(f, current);
        }
        sendJson(ex, 200, result);
    }

    /** POST /api/explain, тело — JSON-результат recommend(). */
    private void explain(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            throw new IllegalArgumentException("Отправьте результат recommend() методом POST");
        }
        Map<String, Object> result = Json.MAPPER.readerFor(Map.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(readBody(ex.getRequestBody()));
        sendJson(ex, 200, explainer.explain(result));
    }

    private List<Map<String, Object>> tariffs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tariff t : service.tariffs()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("name", t.name());
            m.put("fee", t.fee());
            m.put("minutes", t.minutes());
            m.put("gb", t.gb());
            m.put("sms", t.sms());
            m.put("night_unlimited", t.nightUnlimited());
            out.add(m);
        }
        return out;
    }

    private List<String> samples() throws IOException {
        if (dataDir == null || !Files.isDirectory(dataDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dataDir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("usage_.*\\.csv"))
                    .sorted((a, b) -> naturalKey(a).compareTo(naturalKey(b)))
                    .toList();
        }
    }

    private static String naturalKey(String name) {
        String id = name.replaceAll("^usage_|\\.csv$", "");
        return id.matches("\\d+") ? String.format("%010d", Long.parseLong(id)) : "~" + id;
    }

    private static String readBody(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_UPLOAD + 1);
        if (data.length > MAX_UPLOAD) {
            throw new IllegalArgumentException("Файл больше 20 МБ");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.MAPPER.writeValueAsBytes(body));
    }

    private static void sendResource(HttpExchange ex, String resource, String type) throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                sendJson(ex, 404, Map.of("error", "missing resource " + resource));
                return;
            }
            send(ex, 200, type, in.readAllBytes());
        }
    }

    /**
     * Единая отправка ответа.
     * <ul>
     *   <li>Тело запроса дочитывается, даже если ответ — ошибка: иначе соединение рвётся
     *       и браузер показывает сетевую ошибку вместо нашего сообщения.</li>
     *   <li>{@code Connection: close} — браузер не переиспользует соединение, которое сервер
     *       мог уже закрыть по таймауту простоя (Safari не повторяет такие POST и пишет «Load failed»).</li>
     *   <li>HEAD — только заголовки, без тела.</li>
     * </ul>
     */
    private static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        drainIfHasBody(ex);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("Connection", "close");
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Дочитывает тело только если оно объявлено (Content-Length &gt; 0 или chunked).
     * У GET/HEAD тела нет — попытка читать там блокирует поток до таймаута клиента.
     */
    private static void drainIfHasBody(HttpExchange ex) {
        String len = ex.getRequestHeaders().getFirst("Content-Length");
        String te = ex.getRequestHeaders().getFirst("Transfer-Encoding");
        boolean hasBody = (len != null && !len.isBlank() && !"0".equals(len.trim()))
                || (te != null && te.toLowerCase(java.util.Locale.ROOT).contains("chunked"));
        if (!hasBody) {
            return;
        }
        try (InputStream in = ex.getRequestBody()) {
            in.readNBytes(MAX_UPLOAD + 1);
        } catch (IOException ignored) {
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
