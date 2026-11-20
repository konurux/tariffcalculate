package kg.inai.tariff;

import com.fasterxml.jackson.databind.JsonNode;
import kg.inai.tariff.core.Config;
import kg.inai.tariff.core.TariffService;
import kg.inai.tariff.io.Json;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сверка с открытыми эталонами организаторов (02_tariffs/expected.json и 12 наборов edge_cases).
 * Проверяем всё, что сравнивает жюри: действие, тариф, доппакеты и суммы до 0.01 сома.
 */
class OrganizerExpectedTest {

    private static final Path ROOT = Path.of("src/test/resources/inai");

    @TestFactory
    Stream<DynamicTest> matchesOrganizerExpected() throws Exception {
        List<Path> sets = new ArrayList<>();
        sets.add(ROOT);
        try (Stream<Path> s = Files.list(ROOT.resolve("edge_cases"))) {
            s.filter(Files::isDirectory).sorted().forEach(sets::add);
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (Path set : sets) {
            TariffService service = TariffService.fromDir(set, Config.defaults());
            JsonNode expected = Json.MAPPER.readTree(Files.readString(set.resolve("expected.json")));
            for (JsonNode exp : expected) {
                String id = exp.get("id").asText();
                String name = (set.equals(ROOT) ? "" : set.getFileName() + "/") + id;
                tests.add(DynamicTest.dynamicTest(name, () -> check(service, set, exp)));
            }
        }
        return tests.stream();
    }

    private static void check(TariffService service, Path set, JsonNode exp) throws Exception {
        String id = exp.get("id").asText();
        TariffService.SubscriberInfo info = service.lookupSubscriber(id, null);
        assertTrue(info != null, "нет абонента " + id + " в subscribers.json");
        Map<String, Object> r = service.recommend(set.resolve(info.usageFile()), null);

        assertEquals(exp.get("action").asText(), r.get("action"), id + " action");
        assertEquals(exp.get("tariff_id").asText(), r.get("tariff_id"), id + " tariff_id");
        List<String> expAddons = new ArrayList<>();
        exp.get("addon_ids").forEach(a -> expAddons.add(a.asText()));
        assertEquals(expAddons, r.get("addon_ids"), id + " addon_ids");
        for (String f : List.of("current_monthly_cost", "new_monthly_cost", "monthly_savings",
                "switch_fee", "net_savings_3m")) {
            assertMoney(exp.get(f), r.get(f), id + " " + f);
        }
        JsonNode expMonths = exp.get("monthly_costs");
        @SuppressWarnings("unchecked")
        Map<String, Object> months = (Map<String, Object>) r.get("monthly_costs");
        expMonths.fieldNames().forEachRemaining(m -> assertMoney(expMonths.get(m), months.get(m), id + " " + m));
    }

    private static void assertMoney(JsonNode expected, Object actual, String what) {
        BigDecimal e = expected.decimalValue();
        BigDecimal a = actual instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(actual));
        assertTrue(e.subtract(a).abs().compareTo(new BigDecimal("0.01")) < 0,
                what + ": ожидалось " + e + ", получено " + a);
    }
}
