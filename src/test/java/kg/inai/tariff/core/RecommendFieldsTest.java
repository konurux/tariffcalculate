package kg.inai.tariff.core;

import kg.inai.tariff.io.UsageCsvParser;
import kg.inai.tariff.model.MonthUsage;
import kg.inai.tariff.model.Tariff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Поля ответа recommend(), на которых построены сайт и пакетная загрузка: расход по месяцам,
 * разбивка счёта, лимиты тарифов, число перебранных вариантов и правила смены тарифа.
 */
class RecommendFieldsTest {

    private static final Path DATA = Path.of("src/test/resources/inai");
    private static final List<String> SUBSCRIBERS = List.of("P01", "P02", "P03", "P04", "P05", "P06", "P07", "P08", "P09", "P10");
    private static final List<YearMonth> MONTHS = List.of(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10));
    private static final String HEADER = "date,type,amount,duration_sec,roaming\n";

    private final TariffService service = service();

    private static TariffService service() {
        try {
            return TariffService.fromDir(DATA, Config.defaults());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> recommend(String id) throws IOException {
        return service.recommend(DATA.resolve("usage_" + id + ".csv"), null);
    }

    private static BigDecimal dec(Object v) {
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }

    private static void assertMoney(Object expected, Object actual, String what) {
        assertTrue(dec(expected).subtract(dec(actual)).abs().compareTo(new BigDecimal("0.01")) < 0,
                () -> what + ": ожидалось " + expected + ", получено " + actual);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.get("tariff_comparison");
    }

    /** Каждая из 6 частей округлена до копейки отдельно, поэтому их сумма может отличаться от итога на несколько тыйынов. */
    private static void assertPartsAddUp(Object total, BigDecimal parts, String what) {
        assertTrue(dec(total).subtract(parts).abs().compareTo(new BigDecimal("0.03")) <= 0,
                () -> what + ": итог " + total + ", сумма частей " + parts);
    }

    private static BigDecimal sumParts(Map<String, Object> b) {
        return dec(b.get("fee")).add(dec(b.get("addons"))).add(dec(b.get("over_minutes")))
                .add(dec(b.get("over_data"))).add(dec(b.get("over_sms"))).add(dec(b.get("roaming")));
    }

    @Nested
    @DisplayName("Позитивные")
    class Positive {

        @Test
        @DisplayName("monthly_usage: ровно три месяца из subscribers.json и те же числа, что даёт агрегация")
        void monthlyUsageMatchesAggregation() throws IOException {
            Map<String, Object> r = recommend("P03");
            Map<String, Object> usage = map(r.get("monthly_usage"));
            assertEquals(r.get("months"), List.copyOf(usage.keySet()));
            Map<YearMonth, MonthUsage> agg = new UsageAggregator(Config.defaults())
                    .aggregate(UsageCsvParser.parse(DATA.resolve("usage_P03.csv")).records());
            for (YearMonth ym : MONTHS) {
                Map<String, Object> m = map(usage.get(ym.toString()));
                MonthUsage u = agg.getOrDefault(ym, new MonthUsage());
                assertEquals(u.minutes, ((Number) m.get("minutes")).longValue(), ym + " minutes");
                assertEquals(u.sms, ((Number) m.get("sms")).longValue(), ym + " sms");
                assertEquals(0, u.dataMb.divide(BigDecimal.valueOf(1024), 3, java.math.RoundingMode.HALF_UP)
                        .compareTo(dec(m.get("data_gb"))), ym + " data_gb");
            }
        }

        @Test
        @DisplayName("current_monthly_breakdown: части счёта складываются в итог и совпадают со счётом по месяцам у всех 10 абонентов")
        void currentBreakdownAddsUp() throws IOException {
            for (String id : SUBSCRIBERS) {
                Map<String, Object> r = recommend(id);
                Map<String, Object> bd = map(r.get("current_monthly_breakdown"));
                Map<String, Object> costs = map(r.get("current_monthly_costs"));
                assertEquals(costs.keySet(), bd.keySet(), id + " месяцы");
                for (String m : costs.keySet()) {
                    Map<String, Object> b = map(bd.get(m));
                    assertPartsAddUp(b.get("total"), sumParts(b), id + " " + m + " сумма частей");
                    assertMoney(costs.get(m), b.get("total"), id + " " + m + " итог месяца");
                }
            }
        }

        @Test
        @DisplayName("options_checked: перебраны все варианты — 5 тарифов × 10 комбинаций доппакетов = 50")
        void optionsChecked() throws IOException {
            for (String id : SUBSCRIBERS) {
                assertEquals(50, ((Number) recommend(id).get("options_checked")).intValue(), id);
            }
        }

        @Test
        @DisplayName("Строки сравнения: разбивки сходятся со стоимостью, с пакетами не дороже, чем без них")
        void comparisonBreakdownsMatchCosts() throws IOException {
            for (String id : SUBSCRIBERS) {
                Map<String, Object> r = recommend(id);
                assertEquals(service.tariffs().size(), rows(r).size(), id + " все тарифы в сравнении");
                for (Map<String, Object> row : rows(r)) {
                    String what = id + " " + row.get("tariff");
                    assertPartsAddUp(row.get("best_cost"), sumParts(map(row.get("breakdown"))), what + " breakdown");
                    assertPartsAddUp(row.get("cost_without_addons"), sumParts(map(row.get("breakdown_without_addons"))), what + " без пакетов");
                    assertTrue(dec(row.get("best_cost")).compareTo(dec(row.get("cost_without_addons"))) <= 0, what + " пакеты не дороже");
                }
            }
        }

        @Test
        @DisplayName("Лимиты в строках совпадают с каталогом тарифов, лимиты пакетов — с выбранными доппакетами")
        void limitsMatchCatalog() throws IOException {
            Map<String, Object> r = recommend("P10");
            for (Map<String, Object> row : rows(r)) {
                Tariff t = service.tariffs().stream().filter(x -> x.id().equals(row.get("tariff"))).findFirst().orElseThrow();
                Map<String, Object> lim = map(row.get("limits"));
                assertEquals(t.minutes(), lim.get("minutes"), t.id() + " minutes");
                assertEquals(t.sms(), lim.get("sms"), t.id() + " sms");
                assertEquals(0, t.gb().compareTo(dec(lim.get("data_gb"))), t.id() + " gb");
                assertEquals(t.nightUnlimited(), lim.get("night_unlimited"), t.id() + " ночь");
                @SuppressWarnings("unchecked")
                List<String> addons = (List<String>) row.get("best_addons");
                Map<String, Object> add = map(row.get("addon_limits"));
                if (addons.isEmpty()) {
                    assertEquals(0, dec(add.get("data_gb")).signum(), t.id() + " без пакетов");
                }
            }
            Map<String, Object> start = rows(r).stream().filter(x -> "T01".equals(x.get("tariff"))).findFirst().orElseThrow();
            assertEquals(List.of("A01"), start.get("best_addons"));
            assertEquals(0, new BigDecimal("5").compareTo(dec(map(start.get("addon_limits")).get("data_gb"))), "A01 = +5 ГБ");
        }

        @Test
        @DisplayName("Ночной тариф: ночной трафик не входит в пакетный расход и показан отдельно")
        void nightTrafficSeparated() throws IOException {
            Map<String, Object> r = recommend("P06");
            Map<String, Object> night = rows(r).stream().filter(x -> Boolean.TRUE.equals(map(x.get("limits")).get("night_unlimited"))).findFirst().orElseThrow();
            Map<String, Object> day = rows(r).stream().filter(x -> Boolean.FALSE.equals(map(x.get("limits")).get("night_unlimited"))).findFirst().orElseThrow();
            BigDecimal free = dec(map(night.get("usage")).get("night_free_gb"));
            assertTrue(free.signum() > 0, "у P06 есть ночной трафик");
            assertEquals(0, dec(map(day.get("usage")).get("night_free_gb")).signum(), "на дневном тарифе ночь не бесплатна");
            assertEquals(0, dec(map(day.get("usage")).get("data_gb")).subtract(free)
                    .compareTo(dec(map(night.get("usage")).get("data_gb"))), "весь трафик = пакетный + ночной");
        }

        @Test
        @DisplayName("switch_rules: плата 50 сом, окупаемость 3 месяца, строго больше, пакеты к своему тарифу бесплатно")
        void switchRules() throws IOException {
            Map<String, Object> rules = map(recommend("P01").get("switch_rules"));
            assertMoney("50", rules.get("fee"), "fee");
            assertEquals(3, rules.get("payback_months"));
            assertEquals(false, rules.get("payback_inclusive"));
            assertEquals(false, rules.get("fee_for_addon_only_change"));
        }

        @Test
        @DisplayName("Выбранный вручную тариф перекрывает subscribers.json (как при выборе тарифа в пакете)")
        void chosenTariffOverridesSubscribers() throws IOException {
            Map<String, Object> r = service.recommend(DATA.resolve("usage_P03.csv"), "T02");
            assertEquals("T02", r.get("current_tariff"));
            assertEquals("stay", r.get("action"));
            assertMoney("420", r.get("current_monthly_cost"), "счёт на «Комфорте»");
        }

        @Test
        @DisplayName("Без текущего тарифа: лучший вариант, расход и перебор есть, разбивки текущего счёта нет")
        void bestModeFields() {
            var parsed = UsageCsvParser.parse(HEADER + "2026-08-05T10:00:00,call,600,0,0\n");
            Map<String, Object> r = service.recommend("unknown", parsed, null);
            assertEquals("best", r.get("recommendation"));
            assertNotNull(r.get("monthly_usage"));
            assertEquals(50, ((Number) r.get("options_checked")).intValue());
            assertNull(r.get("current_monthly_breakdown"));
            assertNull(r.get("monthly_savings"));
        }

        @Test
        @DisplayName("Пустой файл при заданных месяцах: три месяца, только абонплата, без деления на ноль")
        void emptyCsvListedMonths() {
            Map<String, Object> r = service.recommend("E", UsageCsvParser.parse(HEADER), "T01", MONTHS);
            Map<String, Object> usage = map(r.get("monthly_usage"));
            assertEquals(3, usage.size());
            usage.values().forEach(v -> assertEquals(0L, ((Number) map(v).get("minutes")).longValue()));
            map(r.get("current_monthly_breakdown")).values().forEach(v -> assertMoney(map(v).get("fee"), map(v).get("total"), "только абонплата"));
        }
    }

    @Nested
    @DisplayName("Негативные")
    class Negative {

        @Test
        @DisplayName("Неизвестный текущий тариф → ошибка со списком доступных тарифов")
        void unknownTariff() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.recommend(DATA.resolve("usage_P03.csv"), "T99"));
            assertTrue(e.getMessage().startsWith("Неизвестный тариф «T99»"), e.getMessage());
            assertTrue(e.getMessage().contains("T01"), e.getMessage());
        }

        @Test
        @DisplayName("Тариф не передан ни одним способом при явном вызове с тарифом null и списком месяцев → лучший вариант, а не ошибка")
        void noTariffIsBestNotError() {
            Map<String, Object> r = service.recommend("X", UsageCsvParser.parse(HEADER), null, MONTHS);
            assertEquals("best", r.get("recommendation"));
        }

        @Test
        @DisplayName("Нет обязательных колонок date, type, amount → понятная ошибка")
        void missingColumns() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> UsageCsvParser.parse("foo,bar\n1,2\n"));
            assertTrue(e.getMessage().contains("date, type, amount"), e.getMessage());
        }

        @Test
        @DisplayName("Битые строки пропускаются и считаются, расчёт по остальным не ломается")
        void brokenRowsSkipped() {
            var parsed = UsageCsvParser.parse(HEADER + """
                    2026-08-05T10:00:00,call,60,0,0
                    мусор
                    2026-13-45T99:00:00,call,10,0,0
                    2026-08-06T10:00:00,teleport,5,0,0
                    2026-08-07T10:00:00,call,-5,0,0
                    """);
            assertEquals(1, parsed.records().size());
            assertEquals(4, parsed.skipped());
            Map<String, Object> r = service.recommend("X", parsed, "T01", MONTHS);
            assertEquals(4, r.get("skipped_rows"));
            assertEquals(1L, ((Number) map(map(r.get("monthly_usage")).get("2026-08")).get("minutes")).longValue());
        }

        @Test
        @DisplayName("Отрицательный объём не попадает в расход")
        void negativeAmountIgnored() {
            var parsed = UsageCsvParser.parse(HEADER + "2026-08-05T10:00:00,data,-3,0,0\n");
            assertTrue(parsed.records().isEmpty());
            Map<String, Object> r = service.recommend("X", parsed, "T01", MONTHS);
            assertEquals(0, dec(map(map(r.get("monthly_usage")).get("2026-08")).get("data_gb")).signum());
            assertFalse(rows(r).isEmpty());
        }
    }
}
