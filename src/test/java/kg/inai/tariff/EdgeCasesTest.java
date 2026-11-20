package kg.inai.tariff;

import kg.inai.tariff.core.Config;
import kg.inai.tariff.core.CostCalculator;
import kg.inai.tariff.core.Recommender;
import kg.inai.tariff.core.TariffService;
import kg.inai.tariff.core.UsageAggregator;
import kg.inai.tariff.io.UsageCsvParser;
import kg.inai.tariff.model.MonthUsage;
import kg.inai.tariff.model.Tariff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static kg.inai.tariff.Fixtures.d;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Граничные случаи ТЗ 2 (обязательный пункт 6). */
class EdgeCasesTest {

    private final Config cfg = Config.builder().dataAmountInGb(false).build();
    private final UsageAggregator agg = new UsageAggregator(cfg);
    private final CostCalculator calc = new CostCalculator(cfg);

    private Map<YearMonth, MonthUsage> months(String csvBody) {
        return agg.aggregate(UsageCsvParser.parse("date,type,amount,roaming,duration_sec\n" + csvBody).records());
    }

    private MonthUsage single(String csvBody) {
        Map<YearMonth, MonthUsage> m = months(csvBody);
        assertEquals(1, m.size());
        return m.values().iterator().next();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, d(expected).compareTo(actual), () -> "ожидалось " + expected + ", получено " + actual);
    }

    private Map<String, Object> recommend(String csvBody, String current, List<Tariff> tariffs) {
        var service = new TariffService(cfg, tariffs, List.of(Fixtures.gb5()), null);
        var parsed = UsageCsvParser.parse("date,type,amount,roaming,duration_sec\n" + csvBody);
        return service.recommend("test", parsed, current);
    }

    @Test
    @DisplayName("1. Нулевое потребление: платится только абонплата, рекомендация «остаться»")
    void zeroUsage() {
        Map<String, Object> r = recommend("", "T1", List.of(Fixtures.start(), Fixtures.big()));
        assertEquals("stay", r.get("recommendation"));
        assertMoney("150", (BigDecimal) r.get("current_cost"));
        assertMoney("0", (BigDecimal) r.get("savings_per_month"));
    }

    @Test
    @DisplayName("2. Ровно лимит минут: перерасхода нет")
    void exactlyMinuteLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("2026-08-01 10:00:00,call,60,0,\n");
        }
        MonthUsage u = single(sb.toString());
        assertEquals(100, u.minutes);
        assertMoney("150", calc.monthCost(Fixtures.start(), List.of(), u).total());
    }

    @Test
    @DisplayName("3. Лимит + 1 секунда: одна лишняя минута по цене сверх пакета")
    void limitPlusOneSecond() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 99; i++) {
            sb.append("2026-08-01 10:00:00,call,60,0,\n");
        }
        sb.append("2026-08-01 11:00:00,call,61,0,\n");
        MonthUsage u = single(sb.toString());
        assertEquals(101, u.minutes);
        assertMoney("151.5", calc.monthCost(Fixtures.start(), List.of(), u).total());
    }

    @Test
    @DisplayName("4. Звонок 0 секунд = 0 минут; 1 сек = 1 мин; 60 сек = 1 мин")
    void zeroSecondCall() {
        assertEquals(0, UsageAggregator.callMinutes(d("0")));
        assertEquals(1, UsageAggregator.callMinutes(d("1")));
        assertEquals(1, UsageAggregator.callMinutes(d("60")));
        assertEquals(2, UsageAggregator.callMinutes(d("60.5")));
        assertEquals(0, single("2026-08-01 10:00:00,call,0,0,\n").minutes);
    }

    @Test
    @DisplayName("5. Сессия через границу 07:00: ночной объём делится пропорционально")
    void sessionCrossingNightEnd() {
        MonthUsage u = single("2026-08-01 06:30:00,data,1000,0,3600\n");
        assertMoney("500", u.nightMb);
    }

    @Test
    @DisplayName("6. Сессия через границу 01:00 и через полночь")
    void sessionCrossingNightStart() {
        assertMoney("300", single("2026-08-01 00:30:00,data,600,0,3600\n").nightMb);
        assertMoney("100", single("2026-08-01 23:30:00,data,400,0,7200\n").nightMb);
        assertTrue(agg.isNight(LocalDateTime.of(2026, 8, 1, 6, 59, 59)));
        assertTrue(!agg.isNight(LocalDateTime.of(2026, 8, 1, 7, 0)));
        assertTrue(agg.isNight(LocalDateTime.of(2026, 8, 1, 1, 0)));
        assertTrue(!agg.isNight(LocalDateTime.of(2026, 8, 1, 0, 59, 59)));
    }

    @Test
    @DisplayName("7. Ночной безлимит действует только на тарифе с флагом")
    void nightUnlimitedOnlyWhenFlagged() {
        MonthUsage u = single("2026-08-01 02:00:00,data,5120,0,\n");
        assertMoney("270", calc.monthCost(Fixtures.start(), List.of(), u).total());
        assertMoney("170", calc.monthCost(Fixtures.night(), List.of(), u).total());
    }

    @Test
    @DisplayName("8. Роуминг тарифицируется отдельно и не съедает пакет")
    void roamingNotInPackage() {
        MonthUsage u = single("""
                2026-08-01 10:00:00,call,120,1,
                2026-08-01 10:05:00,sms,1,1,
                2026-08-01 10:10:00,call,60,0,
                """);
        assertEquals(1, u.minutes);
        assertEquals(2, u.roamMinutes);
        assertEquals(1, u.roamSms);
        assertMoney("230", calc.monthCost(Fixtures.start(), List.of(), u).total());
    }

    @Test
    @DisplayName("9. Ровно лимит по ГБ (3072 МБ = 3 ГБ): перерасхода нет")
    void exactlyDataLimit() {
        MonthUsage u = single("2026-08-01 12:00:00,data,3072,0,\n");
        assertMoney("150", calc.monthCost(Fixtures.start(), List.of(), u).total());
    }

    @Test
    @DisplayName("10. Экономия за 3 месяца ровно равна плате за смену → «остаться»")
    void paybackExactlyFeeMeansStay() {
        Tariff a = new Tariff("A", "A", d("200"), null, null, null, d("0"), d("0"), d("0"), false, d("0"), d("0"), d("0"));
        Tariff b = new Tariff("B", "B", d("180"), null, null, null, d("0"), d("0"), d("0"), false, d("0"), d("0"), d("0"));
        var strict = new Recommender(Config.builder().switchFee(d("60")).build())
                .decide(Map.of(), a, List.of(a, b), List.of());
        assertTrue(strict.stay());
        var inclusive = new Recommender(Config.builder().switchFee(d("60")).paybackInclusive(true).build())
                .decide(Map.of(), a, List.of(a, b), List.of());
        assertTrue(!inclusive.stay());
        assertTrue(!new Recommender(cfg).decide(Map.of(), a, List.of(a, b), List.of()).stay());
    }

    @Test
    @DisplayName("11. Небольшая экономия (16 сом/мес) → «остаться», хотя другой тариф дешевле")
    void smallSavingsStay() {
        Tariff a = Fixtures.big();
        Tariff b = new Tariff("T9", "Дешевле", d("284"), 300, d("10"), 100,
                d("1.2"), d("50"), d("1"), false, d("30"), d("350"), d("8"));
        Map<String, Object> r = recommend("", "T2", List.of(a, b));
        assertEquals("stay", r.get("recommendation"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cheapest = (Map<String, Object>) r.get("cheapest_option");
        assertEquals("T9", cheapest.get("tariff"));
    }

    @Test
    @DisplayName("12. Выгодный переход: Базовый → Старт при малом потреблении")
    void switchWhenPaysOff() {
        Map<String, Object> r = recommend("2026-08-01 10:00:00,call,60,0,\n", "T2",
                List.of(Fixtures.start(), Fixtures.big()));
        assertEquals("switch", r.get("recommendation"));
        assertEquals("T1", r.get("recommended_tariff"));
        assertMoney("300", (BigDecimal) r.get("current_cost"));
        assertMoney("150", (BigDecimal) r.get("new_cost"));
        assertMoney("150", (BigDecimal) r.get("savings_per_month"));
    }

    @Test
    @DisplayName("13. Доппакет выгоднее перерасхода: Старт + 5 ГБ")
    void addonBeatsOverage() {
        Map<String, Object> r = recommend("2026-08-01 12:00:00,data,7168,0,\n", "T1",
                List.of(Fixtures.start(), Fixtures.big()));
        assertEquals("switch", r.get("recommendation"));
        assertMoney("0", (BigDecimal) r.get("switch_fee"));
        assertEquals("T1", r.get("recommended_tariff"));
        assertEquals(List.of("A_GB5"), r.get("recommended_addons"));
        assertMoney("250", (BigDecimal) r.get("new_cost"));
    }

    @Test
    @DisplayName("14. Средняя считается по месяцам, звонки агрегируются помесячно")
    void averageAcrossMonths() {
        Map<String, Object> r = recommend("""
                2026-08-15 10:00:00,call,6060,0,
                2026-09-15 10:00:00,call,60,0,
                2026-10-15 10:00:00,call,60,0,
                """, "T1", List.of(Fixtures.start()));
        assertMoney("150.5", (BigDecimal) r.get("current_cost"));
        assertEquals(List.of("2026-08", "2026-09", "2026-10"), r.get("months"));
    }

    @Test
    @DisplayName("16. Текущий тариф неизвестен: определяем самый выгодный, экономию не выдумываем")
    void unknownCurrentTariffPicksBest() {
        Map<String, Object> r = recommend("2026-08-01 12:00:00,data,7168,0,\n", null,
                List.of(Fixtures.start(), Fixtures.big()));
        assertEquals("best", r.get("recommendation"));
        assertEquals("T1", r.get("recommended_tariff"));
        assertEquals(List.of("A_GB5"), r.get("recommended_addons"));
        assertMoney("250", (BigDecimal) r.get("new_cost"));
        assertEquals(null, r.get("current_cost"));
        assertEquals(null, r.get("savings_per_month"));
    }

    @Test
    @DisplayName("15. Грязный CSV: запятая в числе, ; как разделитель, битые строки считаются")
    void dirtyCsv() {
        var res = UsageCsvParser.parse("""
                date;type;amount
                01.08.2026 10:00;call;"90,5"
                2026-08-01 11:00:00;roaming_sms;1
                not-a-date;call;10
                2026-08-01 12:00:00;fax;1

                2026-08-01 13:00:00;data;abc
                """);
        assertEquals(2, res.records().size());
        assertEquals(3, res.skipped());
        assertTrue(res.records().get(1).roaming());
    }
}
