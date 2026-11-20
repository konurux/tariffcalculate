package kg.inai.tariff.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import kg.inai.tariff.core.Config;
import kg.inai.tariff.core.TariffService;
import kg.inai.tariff.io.Json;
import kg.inai.tariff.llm.Explainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * HTTP-интеграция веб-формы и пакетной загрузки: настоящий сервер на свободном порту,
 * запросы так же, как их шлёт страница.
 */
class WebServerTest {

    private static final Path DATA = Path.of("src/test/resources/inai");
    private static final String HEADER = "date,type,amount,duration_sec,roaming\n";
    private static HttpServer server;
    private static HttpClient http;
    private static String base;

    @BeforeAll
    static void start() throws Exception {
        TariffService service = TariffService.fromDir(DATA, Config.defaults());
        server = new WebServer(service, DATA, Explainer.fromEnv()).start(0);
        base = "http://localhost:" + server.getAddress().getPort();
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private record Resp(int status, String body) {
        JsonNode json() throws Exception {
            return Json.MAPPER.readTree(body);
        }
    }

    private static Resp get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(r.statusCode(), r.body());
    }

    private static Resp post(String path, String body) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(r.statusCode(), r.body());
    }

    private static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String usage(String id) throws Exception {
        return Files.readString(DATA.resolve("usage_" + id + ".csv"));
    }

    @Nested
    @DisplayName("Позитивные")
    class Positive {

        @Test
        @DisplayName("Главная страница отдаётся")
        void index() throws Exception {
            Resp r = get("/");
            assertEquals(200, r.status());
            assertTrue(r.body().contains("Калькулятор тарифа"));
        }

        @Test
        @DisplayName("/api/tariffs и /api/samples: 5 тарифов и 10 примеров для формы")
        void catalog() throws Exception {
            assertEquals(5, get("/api/tariffs").json().size());
            assertEquals(10, get("/api/samples").json().size());
        }

        @Test
        @DisplayName("/api/validate: корректный CSV — число записей, расчёт не запускается")
        void validateOk() throws Exception {
            Resp r = post("/api/validate", usage("P03"));
            assertEquals(200, r.status());
            JsonNode j = r.json();
            assertTrue(j.get("ok").asBoolean());
            assertTrue(j.get("records").asInt() > 0);
            assertEquals(0, j.get("skipped_rows").asInt());
            assertFalse(j.has("action"), "validate не должен считать тариф");
        }

        @Test
        @DisplayName("/api/validate: файл с частью битых строк проходит, битые посчитаны")
        void validatePartlyBroken() throws Exception {
            JsonNode j = post("/api/validate", HEADER + "2026-08-05T10:00:00,call,60,0,0\nмусор\n2026-08-06T10:00:00,teleport,5,0,0\n").json();
            assertEquals(1, j.get("records").asInt());
            assertEquals(2, j.get("skipped_rows").asInt());
        }

        @Test
        @DisplayName("Пример из data: ответ совпадает с эталоном жюри (P03 → change на T02)")
        void sampleMatchesExpected() throws Exception {
            JsonNode j = get("/api/recommend?sample=usage_P03.csv&current=").json();
            assertEquals("change", j.get("action").asText());
            assertEquals("T02", j.get("tariff_id").asText());
            assertEquals(0, j.get("monthly_savings").decimalValue().compareTo(new java.math.BigDecimal("750")));
        }

        @Test
        @DisplayName("Свой файл с выбранным тарифом: считается именно с ним (выбор тарифа в пакете)")
        void postWithChosenTariff() throws Exception {
            JsonNode j = post("/api/recommend?name=usage_P03.csv&current=T02", usage("P03")).json();
            assertEquals("T02", j.get("current_tariff").asText());
            assertEquals("stay", j.get("action").asText());
            assertTrue(j.has("monthly_usage") && j.has("current_monthly_breakdown") && j.has("options_checked"));
        }

        @Test
        @DisplayName("Свой файл неизвестного абонента без тарифа: лучший вариант, без выдуманной экономии")
        void postUnknownWithoutTariff() throws Exception {
            JsonNode j = post("/api/recommend?name=" + q("my_client.csv") + "&current=", usage("P03")).json();
            assertEquals("best", j.get("recommendation").asText());
            assertTrue(j.get("monthly_savings") == null || j.get("monthly_savings").isNull());
        }

        @Test
        @DisplayName("Пакет: параллельные запросы, битый файл не мешает остальным")
        void batchIsolation() throws Exception {
            List<String> bodies = List.of(usage("P01"), usage("P03"), "это не csv\nвообще", usage("P06"));
            List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
            for (int i = 0; i < bodies.size(); i++) {
                String name = i == 2 ? "broken.csv" : "usage_P0" + new int[] {1, 3, 0, 6}[i] + ".csv";
                calls.add(http.sendAsync(HttpRequest.newBuilder(URI.create(base + "/api/recommend?name=" + name + "&current=T01"))
                        .POST(HttpRequest.BodyPublishers.ofString(bodies.get(i), StandardCharsets.UTF_8)).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
            }
            List<Integer> statuses = new ArrayList<>();
            for (var c : calls) statuses.add(c.get().statusCode());
            assertEquals(List.of(200, 200, 400, 200), statuses);
            assertEquals(200, get("/api/tariffs").status(), "сервер жив после битого файла");
        }

        @Test
        @DisplayName("/api/explain без ключа API: шаблонное объяснение с цифрами расчёта")
        void explainTemplate() throws Exception {
            assumeTrue(System.getenv("ANTHROPIC_API_KEY") == null, "с ключом ответ пишет LLM");
            Resp rec = get("/api/recommend?sample=usage_P03.csv&current=");
            JsonNode j = post("/api/explain", rec.body()).json();
            assertEquals("template", j.get("source").asText());
            assertTrue(j.get("explanation").asText().contains("750"), j.get("explanation").asText());
        }
    }

    @Nested
    @DisplayName("Негативные")
    class Negative {

        @Test
        @DisplayName("/api/validate методом GET → 400")
        void validateGet() throws Exception {
            assertEquals(400, get("/api/validate").status());
        }

        @Test
        @DisplayName("/api/validate: пустой файл → 400 «Файл пустой»")
        void validateEmpty() throws Exception {
            Resp r = post("/api/validate", "   \n");
            assertEquals(400, r.status());
            assertEquals("Файл пустой", r.json().get("error").asText());
        }

        @Test
        @DisplayName("/api/validate: нет колонок date, type, amount → 400 с причиной")
        void validateMissingColumns() throws Exception {
            Resp r = post("/api/validate", "foo,bar\n1,2\n");
            assertEquals(400, r.status());
            assertTrue(r.json().get("error").asText().contains("date, type, amount"));
        }

        @Test
        @DisplayName("/api/validate: все строки битые → 400")
        void validateAllBroken() throws Exception {
            Resp r = post("/api/validate", HEADER + "мусор\n2026-08-06T10:00:00,teleport,5,0,0\n");
            assertEquals(400, r.status());
            assertTrue(r.json().get("error").asText().contains("Ни одной корректной строки"));
        }

        @Test
        @DisplayName("Неизвестный тариф → 400 с понятной причиной и списком тарифов")
        void unknownTariff() throws Exception {
            Resp r = post("/api/recommend?name=usage_P03.csv&current=T99", usage("P03"));
            assertEquals(400, r.status());
            assertTrue(r.json().get("error").asText().startsWith("Неизвестный тариф «T99»"), r.body());
        }

        @Test
        @DisplayName("Пример с путём за пределы папки → 400 (защита от обхода каталогов)")
        void pathTraversal() throws Exception {
            assertEquals(400, get("/api/recommend?sample=" + q("../tariffs.json")).status());
            assertEquals(400, get("/api/recommend?sample=" + q("usage_../../etc.csv")).status());
        }

        @Test
        @DisplayName("Несуществующий пример → 400 «Нет файла»")
        void missingSample() throws Exception {
            Resp r = get("/api/recommend?sample=usage_P99.csv");
            assertEquals(400, r.status());
            assertTrue(r.json().get("error").asText().startsWith("Нет файла"));
        }

        @Test
        @DisplayName("Мусор вместо CSV → 400, сервер продолжает работать")
        void garbageCsv() throws Exception {
            assertEquals(400, post("/api/recommend?name=x.csv&current=T01", "\u0000\u0001 бинарный мусор").status());
            assertEquals(200, get("/api/samples").status());
        }

        @Test
        @DisplayName("/api/explain методом GET → 400; неизвестный путь → 404")
        void wrongMethodAndPath() throws Exception {
            assertEquals(400, get("/api/explain").status());
            assertEquals(404, get("/api/nope").status());
        }
    }
}
