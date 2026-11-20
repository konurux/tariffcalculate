package kg.inai.tariff.core;

import com.fasterxml.jackson.databind.JsonNode;
import kg.inai.tariff.io.CatalogLoader;
import kg.inai.tariff.io.Json;
import kg.inai.tariff.io.UsageCsvParser;
import kg.inai.tariff.model.Addon;
import kg.inai.tariff.model.CostBreakdown;
import kg.inai.tariff.model.MonthUsage;
import kg.inai.tariff.model.PlanOption;
import kg.inai.tariff.model.Tariff;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Точка входа ТЗ: recommend(usage_file) → JSON (Map, сериализуется Jackson). */
public final class TariffService {

    private final Config cfg;
    private final List<Tariff> tariffs;
    private final List<Addon> addons;
    private final Path dataDir;

    public TariffService(Config cfg, List<Tariff> tariffs, List<Addon> addons, Path dataDir) {
        this.cfg = cfg;
        this.tariffs = tariffs;
        this.addons = addons;
        this.dataDir = dataDir;
    }

    /** Загружает tariffs.json и addons.json из папки. */
    public static TariffService fromDir(Path dataDir, Config cfg) throws IOException {
        return new TariffService(cfg,
                CatalogLoader.loadTariffs(dataDir.resolve("tariffs.json")),
                CatalogLoader.loadAddons(dataDir.resolve("addons.json")),
                dataDir);
    }

    public List<Tariff> tariffs() {
        return tariffs;
    }

    public Map<String, Object> recommend(Path usageFile) throws IOException {
        return recommend(usageFile, null);
    }

    public Map<String, Object> recommend(Path usageFile, String currentTariffId) throws IOException {
        UsageCsvParser.Result parsed = UsageCsvParser.parse(usageFile);
        String subscriber = subscriberId(usageFile);
        SubscriberInfo info = lookupSubscriber(subscriber, usageFile.getFileName().toString());
        String curId = firstNonBlank(currentTariffId, parsed.currentTariff(), info == null ? null : info.tariffId());
        return recommend(info == null ? subscriber : info.id(), parsed, curId, info == null ? null : info.months());
    }

    /** Расчёт по тексту CSV (веб-форма). Имя файла нужно, чтобы найти абонента в subscribers.json. */
    public Map<String, Object> recommendCsv(String fileName, String csv, String currentTariffId) throws IOException {
        UsageCsvParser.Result parsed = UsageCsvParser.parse(csv);
        String name = Path.of(fileName).getFileName().toString();
        String subscriber = subscriberId(Path.of(name));
        SubscriberInfo info = lookupSubscriber(subscriber, name);
        String curId = firstNonBlank(currentTariffId, parsed.currentTariff(), info == null ? null : info.tariffId());
        return recommend(info == null ? subscriber : info.id(), parsed, curId, info == null ? null : info.months());
    }

    public Map<String, Object> recommend(String subscriber, UsageCsvParser.Result parsed, String curId) {
        return recommend(subscriber, parsed, curId, null);
    }

    /**
     * @param listedMonths месяцы расчёта из subscribers.json (ровно три, в том числе пустые);
     *                     null — берём месяцы, встретившиеся в детализации
     */
    public Map<String, Object> recommend(String subscriber, UsageCsvParser.Result parsed, String curId,
                                         List<YearMonth> listedMonths) {
        Map<YearMonth, MonthUsage> months = months(parsed, listedMonths);
        if (curId == null) {
            return bestWithoutCurrent(subscriber, parsed, months);
        }
        Tariff current = findTariff(curId);
        Recommender.Decision d = new Recommender(cfg).decide(months, current, tariffs, addons);

        Map<String, Object> out = new LinkedHashMap<>();
        putJuryFields(out, subscriber, d, current);
        out.put("subscriber", subscriber);
        out.put("months", months.keySet().stream().map(YearMonth::toString).toList());
        out.put("skipped_rows", parsed.skipped());
        out.put("current_tariff", current.id());
        out.put("current_tariff_name", current.name());
        out.put("current_cost", money(d.current().avgMonthly()));
        out.put("current_monthly_costs", monthlyTotals(d.current()));

        if (d.stay()) {
            out.put("recommendation", "stay");
            out.put("recommended_tariff", current.id());
            out.put("recommended_tariff_name", current.name());
            out.put("recommended_addons", List.of());
            out.put("recommended_addon_names", List.of());
            out.put("recommended_label", current.name());
            out.put("new_cost", money(d.current().avgMonthly()));
            out.put("savings_per_month", money(BigDecimal.ZERO));
            out.put("reason", stayReason(d));
        } else {
            PlanOption c = d.chosen();
            BigDecimal perMonth = d.current().avgMonthly().subtract(c.avgMonthly());
            BigDecimal horizon = BigDecimal.valueOf(cfg.paybackMonths);
            out.put("recommendation", "switch");
            out.put("recommended_tariff", c.tariff().id());
            out.put("recommended_tariff_name", c.tariff().name());
            out.put("recommended_addons", c.addons().stream().map(Addon::id).toList());
            out.put("recommended_addon_names", c.addons().stream().map(Addon::name).toList());
            out.put("recommended_label", c.label());
            out.put("new_cost", money(c.avgMonthly()));
            out.put("savings_per_month", money(perMonth));
            out.put("switch_fee", money(d.switchFee()));
            out.put("net_savings_" + cfg.paybackMonths + "_months",
                    money(perMonth.multiply(horizon).subtract(d.switchFee())));
            out.put("reason", "Вариант «" + c.label() + "» дешевле на " + money(perMonth)
                    + " сом/мес; плата за переход " + money(d.switchFee()) + " сом окупается за "
                    + cfg.paybackMonths + " мес.");
        }
        out.put("new_monthly_costs", monthlyTotals(d.stay() ? d.current() : d.chosen()));
        out.put("cheapest_option", optionJson(d.cheapest()));
        out.put("tariff_comparison", comparison(d.allOptions(), months));
        out.put("switch_rules", switchRules());
        out.put("monthly_usage", monthlyUsage(months));
        out.put("current_monthly_breakdown", monthlyBreakdown(d.current()));
        out.put("options_checked", d.allOptions().size());
        return out;
    }

    /**
     * Текущий тариф нигде не указан: определяем самый выгодный тариф по потреблению.
     * Экономию и окупаемость смены (50 сом) без текущего тарифа посчитать нельзя — эти поля null.
     */
    private Map<String, Object> bestWithoutCurrent(String subscriber, UsageCsvParser.Result parsed,
                                                   Map<YearMonth, MonthUsage> months) {
        Recommender.Exploration e = new Recommender(cfg).explore(months, tariffs, addons);
        PlanOption best = e.cheapest();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", subscriber);
        out.put("action", "best");
        out.put("tariff_id", best.tariff().id());
        out.put("addon_ids", addonIds(best));
        out.put("current_monthly_cost", null);
        out.put("new_monthly_cost", money(best.avgMonthly()));
        out.put("monthly_savings", null);
        out.put("switch_fee", null);
        out.put("net_savings_3m", null);
        out.put("monthly_costs", monthlyTotals(best));
        out.put("subscriber", subscriber);
        out.put("months", months.keySet().stream().map(YearMonth::toString).toList());
        out.put("skipped_rows", parsed.skipped());
        out.put("current_tariff", null);
        out.put("current_tariff_name", null);
        out.put("current_cost", null);
        out.put("current_monthly_costs", Map.of());
        out.put("recommendation", "best");
        out.put("recommended_tariff", best.tariff().id());
        out.put("recommended_tariff_name", best.tariff().name());
        out.put("recommended_addons", best.addons().stream().map(Addon::id).toList());
        out.put("recommended_addon_names", best.addons().stream().map(Addon::name).toList());
        out.put("recommended_label", best.label());
        out.put("new_cost", money(best.avgMonthly()));
        out.put("savings_per_month", null);
        out.put("reason", "Текущий тариф не указан, поэтому показываем самый выгодный вариант при этом потреблении. "
                + "Укажите текущий тариф, чтобы посчитать экономию и окупаемость смены.");
        out.put("new_monthly_costs", monthlyTotals(best));
        out.put("cheapest_option", optionJson(best));
        out.put("tariff_comparison", comparison(e.allOptions(), months));
        out.put("switch_rules", switchRules());
        out.put("monthly_usage", monthlyUsage(months));
        out.put("options_checked", e.allOptions().size());
        return out;
    }

    /**
     * Поля в формате эталона организаторов (expected.json): action ∈ {stay, change, add_packages}.
     * monthly_savings не вычитает плату за переход, net_savings_3m — вычитает.
     */
    private void putJuryFields(Map<String, Object> out, String subscriber, Recommender.Decision d, Tariff current) {
        PlanOption rec = d.stay() ? d.current() : d.chosen();
        BigDecimal monthly = d.current().avgMonthly().subtract(rec.avgMonthly());
        BigDecimal fee = d.stay() ? BigDecimal.ZERO : d.switchFee();
        String action = d.stay() ? "stay"
                : rec.tariff().id().equals(current.id()) ? "add_packages" : "change";
        out.put("id", subscriber);
        out.put("action", action);
        out.put("tariff_id", rec.tariff().id());
        out.put("addon_ids", addonIds(rec));
        out.put("current_monthly_cost", money(d.current().avgMonthly()));
        out.put("new_monthly_cost", money(rec.avgMonthly()));
        out.put("monthly_savings", money(monthly));
        out.put("switch_fee", money(fee));
        out.put("net_savings_3m", money(monthly.multiply(BigDecimal.valueOf(cfg.paybackMonths)).subtract(fee)));
        out.put("monthly_costs", monthlyTotals(rec));
    }

    private static List<String> addonIds(PlanOption o) {
        return o.addons().stream().map(Addon::id).sorted().toList();
    }

    /** Месяцы расчёта: из subscribers.json (пустые — с нулевым потреблением) или из самой детализации. */
    private Map<YearMonth, MonthUsage> months(UsageCsvParser.Result parsed, List<YearMonth> listed) {
        Map<YearMonth, MonthUsage> aggregated = new UsageAggregator(cfg).aggregate(parsed.records());
        if (listed == null || listed.isEmpty()) {
            return aggregated;
        }
        Map<YearMonth, MonthUsage> out = new LinkedHashMap<>();
        for (YearMonth ym : listed) {
            out.put(ym, aggregated.getOrDefault(ym, new MonthUsage()));
        }
        return out;
    }

    public String toJson(Object result) throws IOException {
        return Json.MAPPER.writeValueAsString(result);
    }

    private String stayReason(Recommender.Decision d) {
        if (d.cheapest().isSame(d.current().tariff())) {
            return "Текущий тариф уже самый выгодный.";
        }
        BigDecimal saved = d.current().avgMonthly().subtract(d.cheapest().avgMonthly())
                .multiply(BigDecimal.valueOf(cfg.paybackMonths));
        return "Самый дешёвый вариант «" + d.cheapest().label() + "» экономит " + money(saved)
                + " сом за " + cfg.paybackMonths + " мес., это не окупает плату за смену тарифа "
                + money(d.switchFee()) + " сом.";
    }

    /** Лучший вариант на каждом тарифе — для графика сравнения. */
    private List<Map<String, Object>> comparison(List<PlanOption> options, Map<YearMonth, MonthUsage> months) {
        Map<String, PlanOption> bestPerTariff = new LinkedHashMap<>();
        Map<String, PlanOption> plainPerTariff = new LinkedHashMap<>();
        for (PlanOption o : options) {
            String id = o.tariff().id();
            if (o.addons().isEmpty()) {
                plainPerTariff.put(id, o);
            }
            PlanOption prev = bestPerTariff.get(id);
            if (prev == null || o.avgMonthly().compareTo(prev.avgMonthly()) < 0) {
                bestPerTariff.put(id, o);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, PlanOption> e : bestPerTariff.entrySet()) {
            PlanOption best = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tariff", e.getKey());
            row.put("tariff_name", best.tariff().name());
            row.put("cost_without_addons", money(plainPerTariff.get(e.getKey()).avgMonthly()));
            row.put("best_addons", best.addons().stream().map(Addon::id).toList());
            row.put("best_addon_names", best.addons().stream().map(Addon::name).toList());
            row.put("best_cost", money(best.avgMonthly()));
            row.put("breakdown", averageBreakdown(best));
            row.put("breakdown_without_addons", averageBreakdown(plainPerTariff.get(e.getKey())));
            row.put("monthly_costs", monthlyTotals(best));
            row.put("usage", averageUsage(best.tariff(), months));
            row.put("limits", limits(best.tariff()));
            row.put("addon_limits", addonLimits(best.addons()));
            out.add(row);
        }
        out.sort(Comparator.comparing(r -> (BigDecimal) r.get("best_cost")));
        return out;
    }

    /**
     * Средний расход за месяц для графика «расход и лимиты». Интернет — тот, что идёт в пакет:
     * на тарифе с ночным безлимитом трафик 01:00–07:00 не считается и показан отдельно (night_free_gb).
     * Роуминг в пакет не входит и здесь не учитывается.
     */
    private Map<String, Object> averageUsage(Tariff t, Map<YearMonth, MonthUsage> months) {
        long minutes = 0, sms = 0;
        BigDecimal dataMb = BigDecimal.ZERO, nightMb = BigDecimal.ZERO;
        for (MonthUsage u : months.values()) {
            minutes += u.minutes;
            sms += u.sms;
            dataMb = dataMb.add(u.dataMb);
            nightMb = nightMb.add(u.nightMb);
        }
        BigDecimal n = BigDecimal.valueOf(Math.max(1, months.size()));
        BigDecimal free = t.nightUnlimited() ? nightMb : BigDecimal.ZERO;
        BigDecimal gbDiv = BigDecimal.valueOf(cfg.mbPerGb).multiply(n);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minutes", BigDecimal.valueOf(minutes).divide(n, 1, RoundingMode.HALF_UP).stripTrailingZeros());
        m.put("data_gb", dataMb.subtract(free).max(BigDecimal.ZERO).divide(gbDiv, 2, RoundingMode.HALF_UP).stripTrailingZeros());
        m.put("sms", BigDecimal.valueOf(sms).divide(n, 1, RoundingMode.HALF_UP).stripTrailingZeros());
        m.put("night_free_gb", free.divide(gbDiv, 2, RoundingMode.HALF_UP).stripTrailingZeros());
        return m;
    }

    /** Правила смены тарифа — чтобы страница считала экономию для любого выбранного тарифа так же, как расчёт. */
    private Map<String, Object> switchRules() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fee", money(cfg.switchFee));
        m.put("payback_months", cfg.paybackMonths);
        m.put("payback_inclusive", cfg.paybackInclusive);
        m.put("fee_for_addon_only_change", cfg.feeForAddonOnlyChange);
        return m;
    }

    /** Лимиты пакета тарифа; null — безлимит. */
    private static Map<String, Object> limits(Tariff t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minutes", t.minutes());
        m.put("data_gb", t.gb());
        m.put("sms", t.sms());
        m.put("night_unlimited", t.nightUnlimited());
        return m;
    }

    /** Сколько добавляют доппакеты варианта к лимитам тарифа. */
    private static Map<String, Object> addonLimits(List<Addon> list) {
        long min = 0, sms = 0;
        BigDecimal gb = BigDecimal.ZERO;
        for (Addon a : list) {
            min += a.minutes();
            sms += a.sms();
            gb = gb.add(a.gb());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minutes", min);
        m.put("data_gb", gb.stripTrailingZeros());
        m.put("sms", sms);
        return m;
    }

    /** Средние за месяц составляющие стоимости — для графика и подсказки. */
    /** Расход по каждому месяцу расчёта: домашний, ночной и роуминг отдельно. */
    private Map<String, Object> monthlyUsage(Map<YearMonth, MonthUsage> months) {
        BigDecimal mbPerGb = BigDecimal.valueOf(cfg.mbPerGb);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<YearMonth, MonthUsage> e : months.entrySet()) {
            MonthUsage u = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("minutes", u.minutes);
            m.put("sms", u.sms);
            m.put("data_gb", u.dataMb.divide(mbPerGb, 3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
            m.put("night_gb", u.nightMb.divide(mbPerGb, 3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
            m.put("roam_minutes", u.roamMinutes);
            m.put("roam_sms", u.roamSms);
            m.put("roam_gb", u.roamMb.divide(mbPerGb, 3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
            out.put(e.getKey().toString(), m);
        }
        return out;
    }

    /** Из чего складывается счёт варианта в каждом месяце. */
    private static Map<String, Object> monthlyBreakdown(PlanOption o) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, CostBreakdown> e : o.monthly().entrySet()) {
            CostBreakdown c = e.getValue();
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            m.put("fee", money(c.fee()));
            m.put("addons", money(c.addons()));
            m.put("over_minutes", money(c.overMinutes()));
            m.put("over_data", money(c.overData()));
            m.put("over_sms", money(c.overSms()));
            m.put("roaming", money(c.roaming()));
            m.put("total", money(c.total()));
            out.put(e.getKey(), m);
        }
        return out;
    }

    private static Map<String, BigDecimal> averageBreakdown(PlanOption o) {
        BigDecimal fee = BigDecimal.ZERO, addons = BigDecimal.ZERO, overMin = BigDecimal.ZERO;
        BigDecimal overData = BigDecimal.ZERO, overSms = BigDecimal.ZERO, roaming = BigDecimal.ZERO;
        for (CostBreakdown c : o.monthly().values()) {
            fee = fee.add(c.fee());
            addons = addons.add(c.addons());
            overMin = overMin.add(c.overMinutes());
            overData = overData.add(c.overData());
            overSms = overSms.add(c.overSms());
            roaming = roaming.add(c.roaming());
        }
        BigDecimal n = BigDecimal.valueOf(Math.max(1, o.monthly().size()));
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put("fee", avg(fee, n));
        m.put("addons", avg(addons, n));
        m.put("over_minutes", avg(overMin, n));
        m.put("over_data", avg(overData, n));
        m.put("over_sms", avg(overSms, n));
        m.put("roaming", avg(roaming, n));
        return m;
    }

    private static BigDecimal avg(BigDecimal sum, BigDecimal n) {
        return money(sum.divide(n, java.math.MathContext.DECIMAL64));
    }

    private Map<String, Object> optionJson(PlanOption o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tariff", o.tariff().id());
        m.put("tariff_name", o.tariff().name());
        m.put("addons", o.addons().stream().map(Addon::id).toList());
        m.put("avg_monthly_cost", money(o.avgMonthly()));
        m.put("monthly_costs", monthlyTotals(o));
        return m;
    }

    private static Map<String, BigDecimal> monthlyTotals(PlanOption o) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (Map.Entry<String, CostBreakdown> e : o.monthly().entrySet()) {
            m.put(e.getKey(), money(e.getValue().total()));
        }
        return m;
    }

    public static BigDecimal money(BigDecimal x) {
        return x.setScale(2, RoundingMode.HALF_UP);
    }

    private Tariff findTariff(String id) {
        if (id == null) {
            throw new MissingCurrentTariffException("Не указан текущий тариф абонента: передайте --current, "
                    + "колонку tariff в CSV или subscribers.json рядом с файлами.");
        }
        for (Tariff t : tariffs) {
            if (t.id().equalsIgnoreCase(id) || t.name().equalsIgnoreCase(id)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Неизвестный тариф «" + id + "». Доступны: "
                + tariffs.stream().map(Tariff::id).toList());
    }

    /** Запись абонента из subscribers.json. */
    public record SubscriberInfo(String id, String tariffId, List<YearMonth> months, String usageFile) {
    }

    /**
     * subscribers.json в любом из видов:
     * [{"id": "P01", "current_tariff_id": "T01", "months": [...], "usage_file": "usage_P01.csv"}],
     * {"P01": "T01"} или {"P01": {"tariff": "T01"}}. Ищем по id или по имени файла детализации.
     */
    public SubscriberInfo lookupSubscriber(String subscriber, String fileName) throws IOException {
        for (SubscriberInfo s : subscribers()) {
            if (s.id().equals(subscriber) || s.id().equals("usage_" + subscriber)
                    || (fileName != null && fileName.equals(s.usageFile()))) {
                return s;
            }
        }
        return null;
    }

    /** Все абоненты из subscribers.json (пустой список, если файла нет). */
    public List<SubscriberInfo> subscribers() throws IOException {
        if (dataDir == null) {
            return List.of();
        }
        Path f = dataDir.resolve("subscribers.json");
        if (!Files.exists(f)) {
            return List.of();
        }
        JsonNode root = Json.MAPPER.readTree(Files.readString(f));
        List<SubscriberInfo> out = new ArrayList<>();
        if (root.isObject() && !root.has("subscribers")) {
            root.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                String tariff = v.isObject() ? tariffField(v) : v.asText();
                out.add(new SubscriberInfo(e.getKey(), tariff, v.isObject() ? monthsField(v) : null, null));
            });
            return out;
        }
        JsonNode list = root.isObject() ? root.get("subscribers") : root;
        for (JsonNode s : list) {
            out.add(new SubscriberInfo(
                    Json.text(s, "", "id", "subscriber_id", "subscriber"),
                    tariffField(s), monthsField(s), Json.text(s, null, "usage_file", "file")));
        }
        return out;
    }

    private static String tariffField(JsonNode s) {
        return Json.text(s, null, "current_tariff_id", "tariff", "current_tariff", "tariff_id");
    }

    private static List<YearMonth> monthsField(JsonNode s) {
        JsonNode m = Json.pick(s, "months");
        if (m == null || !m.isArray()) {
            return null;
        }
        List<YearMonth> out = new ArrayList<>();
        m.forEach(x -> out.add(YearMonth.parse(x.asText().trim())));
        return out;
    }

    public static String subscriberId(Path usageFile) {
        String name = usageFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem.startsWith("usage_") ? stem.substring("usage_".length()) : stem;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
