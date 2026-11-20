package kg.inai.tariff.core;

import kg.inai.tariff.io.UsageCsvParser;
import kg.inai.tariff.model.Addon;
import kg.inai.tariff.model.MonthUsage;
import kg.inai.tariff.model.Tariff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 12 граничных сценариев ТЗ 2 по правилам организаторов (CLARIFICATIONS §2):
 * интернет в ГБ (1 ГБ = 1024 МБ), каждый звонок округляется вверх до минуты, ночь [01:00, 07:00),
 * роуминг отдельно, смена тарифа окупается только если экономия за 3 месяца СТРОГО больше 50 сом.
 */
class TzEdgeCasesTest {

    private final Config cfg = Config.defaults();
    private final UsageAggregator agg = new UsageAggregator(cfg);
    private final CostCalculator calc = new CostCalculator(cfg);

    private static final List<YearMonth> MONTHS = List.of(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10));
    private static final String HEADER = "date,type,amount,duration_sec,roaming\n";

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    /** Пакет 300 мин / 5 ГБ / 100 SMS за 200 сом; сверх: 2 сом/мин, 80 сом/ГБ, 1 сом/SMS; роуминг 15 / 500 / 5. */
    private static Tariff pack300() {
        return new Tariff("T300", "Пакет 300", d("200"), 300, d("5"), 100,
                d("2"), d("80"), d("1"), false, d("15"), d("500"), d("5"));
    }

    /** То же, но с ночным безлимитом. */
    private static Tariff night300() {
        return new Tariff("TN", "Ночной 300", d("250"), 300, d("5"), 100,
                d("2"), d("80"), d("1"), true, d("15"), d("500"), d("5"));
    }

    /** Без пакета минут: каждая минута платная — удобно проверять, списана ли минута. */
    private static Tariff noMinutes(String id, String fee) {
        return new Tariff(id, id, d(fee), 0, d("5"), 100,
                d("1"), d("80"), d("1"), false, d("15"), d("500"), d("5"));
    }

    /** Большой пакет минут при той же цене. */
    private static Tariff manyMinutes(String id, String fee) {
        return new Tariff(id, id, d(fee), 1000, d("5"), 100,
                d("1"), d("80"), d("1"), false, d("15"), d("500"), d("5"));
    }

    private MonthUsage single(String rows) {
        Map<YearMonth, MonthUsage> m = agg.aggregate(UsageCsvParser.parse(HEADER + rows).records());
        assertEquals(1, m.size(), "ожидался один месяц");
        return m.values().iterator().next();
    }

    private BigDecimal cost(Tariff t, MonthUsage u) {
        return calc.monthCost(t, List.of(), u).total();
    }

    private Map<String, Object> recommend(String rows, String current, List<Tariff> tariffs, List<Addon> addons) {
        TariffService service = new TariffService(cfg, tariffs, addons, null);
        return service.recommend("X", UsageCsvParser.parse(HEADER + rows), current, MONTHS);
    }

    private static void assertMoney(String expected, Object actual) {
        BigDecimal a = actual instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(actual));
        assertEquals(0, d(expected).compareTo(a), () -> "ожидалось " + expected + ", получено " + a);
    }

    @Test
    @DisplayName("1. Звонок ровно 0 секунд: 0 минут, пакет не уменьшается, перерасхода нет")
    void test_zero_duration_call() {
        MonthUsage u = single("2026-08-05T10:00:00,call,0,0,0\n");
        assertEquals(0, u.minutes);
        assertMoney("100", cost(noMinutes("A", "100"), u));
    }

    @Test
    @DisplayName("2. Звонок 1 секунда списывает ровно 1 минуту")
    void test_call_ceil_rounding_single_second() {
        MonthUsage u = single("2026-08-05T10:00:00,call,1,0,0\n");
        assertEquals(1, u.minutes);
        assertMoney("101", cost(noMinutes("A", "100"), u));
        assertMoney("200", cost(pack300(), u));
    }

    @Test
    @DisplayName("3. Звонки 60 и 61 секунда: 1 и 2 минуты; округляется каждый звонок отдельно")
    void test_call_boundary_minutes() {
        assertEquals(1, UsageAggregator.callMinutes(d("60")));
        assertEquals(2, UsageAggregator.callMinutes(d("61")));
        assertEquals(2, UsageAggregator.callMinutes(d("120")));
        assertEquals(3, UsageAggregator.callMinutes(d("121")));
        MonthUsage u = single("""
                2026-08-05T10:00:00,call,61,0,0
                2026-08-05T11:00:00,call,61,0,0
                """);
        assertEquals(4, u.minutes);
    }

    @Test
    @DisplayName("4. Расход ровно по лимиту (300 мин 00 с, 5 ГБ, 100 SMS): перерасход 0")
    void test_exact_package_limit() {
        MonthUsage u = single("""
                2026-08-05T10:00:00,call,18000,0,0
                2026-08-06T12:00:00,data,5,0,0
                2026-08-07T12:00:00,sms,100,0,0
                """);
        assertEquals(300, u.minutes);
        assertEquals(100, u.sms);
        var c = calc.monthCost(pack300(), List.of(), u);
        assertMoney("0", c.overMinutes());
        assertMoney("0", c.overData());
        assertMoney("0", c.overSms());
        assertMoney("200", c.total());
    }

    @Test
    @DisplayName("5. Превышение на минимальный квант: 300 мин 1 с, 5 ГБ + 1 МБ, 101 SMS")
    void test_slight_overage() {
        MonthUsage u = single("""
                2026-08-05T10:00:00,call,18001,0,0
                2026-08-06T12:00:00,data,5.0009765625,0,0
                2026-08-07T12:00:00,sms,101,0,0
                """);
        assertEquals(301, u.minutes);
        var c = calc.monthCost(pack300(), List.of(), u);
        assertMoney("2", c.overMinutes());
        assertMoney("0.078125", c.overData());
        assertMoney("1", c.overSms());
        assertMoney("203.078125", c.total());
    }

    @Test
    @DisplayName("6. Нулевое потребление за 3 месяца: только абонплата, без деления на 0")
    void test_zero_total_usage() {
        List<Tariff> tariffs = List.of(noMinutes("CHEAP", "100"), pack300());
        Map<String, Object> r = recommend("", "T300", tariffs, List.of(gb5()));
        assertEquals("change", r.get("action"));
        assertEquals("CHEAP", r.get("tariff_id"));
        assertEquals(List.of(), r.get("addon_ids"));
        assertMoney("200", r.get("current_monthly_cost"));
        assertMoney("100", r.get("new_monthly_cost"));
        @SuppressWarnings("unchecked")
        Map<String, Object> months = (Map<String, Object>) r.get("monthly_costs");
        assertEquals(3, months.size());
        months.values().forEach(v -> assertMoney("100", v));
        Map<String, Object> stay = recommend("", "CHEAP", tariffs, List.of(gb5()));
        assertEquals("stay", stay.get("action"));
        assertMoney("0", stay.get("monthly_savings"));
    }

    @Test
    @DisplayName("7. Сессия 00:45–01:15: половина до 01:00 платная, половина после — бесплатна")
    void test_night_session_entry_split() {
        MonthUsage u = single("2026-08-05T00:45:00,data,1,1800,0\n");
        assertMoney("1024", u.dataMb);
        assertMoney("512", u.nightMb);
        assertEquals(900, agg.nightSeconds(LocalDateTime.of(2026, 8, 5, 0, 45), 1800));
        MonthUsage big = single("""
                2026-08-05T00:45:00,data,1,1800,0
                2026-08-06T12:00:00,data,5,0,0
                """);
        assertMoney("40", calc.monthCost(night300(), List.of(), big).overData());
        assertMoney("80", calc.monthCost(pack300(), List.of(), big).overData());
    }

    @Test
    @DisplayName("8. Сессия 06:45–07:15: до 07:00 безлимит, с 07:00:00 — дневной пакет")
    void test_night_session_exit_split() {
        MonthUsage u = single("2026-08-05T06:45:00,data,1,1800,0\n");
        assertMoney("512", u.nightMb);
        assertTrue(agg.isNight(LocalDateTime.of(2026, 8, 5, 6, 59, 59)));
        assertFalse(agg.isNight(LocalDateTime.of(2026, 8, 5, 7, 0, 0)));
        assertTrue(agg.isNight(LocalDateTime.of(2026, 8, 5, 1, 0, 0)));
        assertFalse(agg.isNight(LocalDateTime.of(2026, 8, 5, 0, 59, 59)));
        assertMoney("0", single("2026-08-05T07:00:00,data,1,0,0\n").nightMb);
    }

    @Test
    @DisplayName("9. Роуминг не расходует домашние пакеты и ночной безлимит, тарифицируется отдельно")
    void test_roaming_isolation() {
        MonthUsage u = single("""
                2026-08-05T02:00:00,call,120,0,1
                2026-08-05T02:10:00,data,1,600,1
                2026-08-05T02:20:00,sms,1,0,1
                """);
        assertEquals(0, u.minutes);
        assertEquals(0, u.sms);
        assertMoney("0", u.dataMb);
        assertMoney("0", u.nightMb);
        assertEquals(2, u.roamMinutes);
        assertEquals(1, u.roamSms);
        var c = calc.monthCost(night300(), List.of(), u);
        assertMoney("535", c.roaming());
        assertMoney("785", c.total());
    }

    /** Звонки по месяцам: на «A» каждая минута по 1 сом, на «B» те же минуты внутри пакета. */
    private static String minutesByMonth(int aug, int sep, int oct) {
        return "2026-08-10T10:00:00,call," + aug * 60 + ",0,0\n"
                + "2026-09-10T10:00:00,call," + sep * 60 + ",0,0\n"
                + "2026-10-10T10:00:00,call," + oct * 60 + ",0,0\n";
    }

    @Test
    @DisplayName("10. Экономия ровно 50.00 сом за 3 месяца (16.666… сом/мес) → «остаться»")
    void test_threshold_stay_exactly_50() {
        Map<String, Object> r = recommend(minutesByMonth(16, 17, 17), "A",
                List.of(noMinutes("A", "100"), manyMinutes("B", "100")), List.of());
        assertEquals("stay", r.get("action"));
        assertEquals("A", r.get("tariff_id"));
        Recommender rec = new Recommender(cfg);
        var cur = rec.evaluate(noMinutes("A", "100"), List.of(), months(16, 17, 17));
        var alt = rec.evaluate(manyMinutes("B", "100"), List.of(), months(16, 17, 17));
        assertFalse(rec.paysOff(cur, alt, noMinutes("A", "100")));
    }

    @Test
    @DisplayName("11. Экономия 51 сом за 3 месяца (17 сом/мес) → переход окупается")
    void test_threshold_switch_51() {
        Map<String, Object> r = recommend(minutesByMonth(17, 17, 17), "A",
                List.of(noMinutes("A", "100"), manyMinutes("B", "100")), List.of());
        assertEquals("change", r.get("action"));
        assertEquals("B", r.get("tariff_id"));
        assertMoney("17", r.get("monthly_savings"));
        assertMoney("50", r.get("switch_fee"));
        assertMoney("1", r.get("net_savings_3m"));
    }

    private Map<YearMonth, MonthUsage> months(int aug, int sep, int oct) {
        return agg.aggregate(UsageCsvParser.parse(HEADER + minutesByMonth(aug, sep, oct)).records());
    }

    private static Addon gb5() {
        return new Addon("A01", "5 ГБ", d("100"), 0, d("5"), 0);
    }

    @Test
    @DisplayName("12. Комбинации доппакетов: 0, 1 или 2 (одинаковые или разные), не больше двух")
    void test_addon_combinations_limit() {
        List<Addon> addons = List.of(gb5(),
                new Addon("A02", "100 минут", d("90"), 100, d("0"), 0),
                new Addon("A03", "100 SMS", d("40"), 0, d("0"), 100));
        List<List<Addon>> combos = new Recommender(cfg).addonCombos(addons);
        assertEquals(10, combos.size());
        assertTrue(combos.stream().allMatch(c -> c.size() <= 2));
        assertEquals(1, combos.stream().filter(List::isEmpty).count());
        assertEquals(3, combos.stream().filter(c -> c.size() == 1).count());
        assertEquals(6, combos.stream().filter(c -> c.size() == 2).count());
        Set<List<String>> asIds = new HashSet<>();
        for (List<Addon> c : combos) {
            assertTrue(asIds.add(c.stream().map(Addon::id).sorted().toList()), "повтор комбинации " + c);
        }
        assertTrue(asIds.contains(List.of("A01", "A01")));

        Map<String, Object> r = recommend("""
                2026-08-06T12:00:00,data,15,0,0
                2026-09-06T12:00:00,data,15,0,0
                2026-10-06T12:00:00,data,15,0,0
                """, "T300", List.of(pack300()), addons);
        assertEquals("add_packages", r.get("action"));
        assertEquals(List.of("A01", "A01"), r.get("addon_ids"));
        assertMoney("1000", r.get("current_monthly_cost"));
        assertMoney("400", r.get("new_monthly_cost"));
        assertMoney("0", r.get("switch_fee"));
    }

    /** Подстраховка для теста 10: средняя 350/3 действительно не представима точно в десятичной записи. */
    @Test
    @DisplayName("10a. 50/3 в десятичной записи округляется — сравнение порога должно быть точным")
    void test_threshold_division_is_inexact() {
        BigDecimal avg = d("50").divide(d("3"), MathContext.DECIMAL64);
        assertTrue(avg.multiply(d("3")).compareTo(d("50")) != 0, "если это равенство, тест 10 ничего не ловит");
    }
}
