package kg.inai.tariff.core;

import kg.inai.tariff.model.Addon;
import kg.inai.tariff.model.CostBreakdown;
import kg.inai.tariff.model.MonthUsage;
import kg.inai.tariff.model.PlanOption;
import kg.inai.tariff.model.Tariff;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Перебор «тариф + 0–2 доппакета» и решение «перейти / остаться» (пункты 4 и 5). */
public final class Recommender {

    /** Итог подбора. {@code chosen == null} означает «остаться». */
    public record Decision(PlanOption current, PlanOption cheapest, PlanOption chosen,
                           BigDecimal switchFee, List<PlanOption> allOptions) {
        public boolean stay() {
            return chosen == null;
        }
    }

    private final Config cfg;
    private final CostCalculator calc;

    public Recommender(Config cfg) {
        this.cfg = cfg;
        this.calc = new CostCalculator(cfg);
    }

    public Decision decide(Map<YearMonth, MonthUsage> months, Tariff current,
                           List<Tariff> tariffs, List<Addon> addons) {
        Map<YearMonth, MonthUsage> m = months.isEmpty() ? emptyMonth() : months;

        PlanOption cur = evaluate(current, List.of(), m);
        List<PlanOption> options = allOptions(m, tariffs, addons);
        List<PlanOption> ranked = new ArrayList<>(options);
        ranked.sort(ranking(current.id()));
        PlanOption cheapest = ranked.get(0);

        for (PlanOption o : ranked) {
            if (o.isSame(current)) {
                continue;
            }
            if (paysOff(cur, o, current)) {
                return new Decision(cur, cheapest, o, switchFee(current, o), options);
            }
        }
        return new Decision(cur, cheapest, null, BigDecimal.ZERO, options);
    }

    /**
     * Другой тариф: экономия × 3 строго больше платы за смену (50 сом).
     * Тот же тариф + доппакеты: смены нет, пакеты бесплатно — достаточно положительной экономии.
     */
    boolean paysOff(PlanOption cur, PlanOption o, Tariff current) {
        BigDecimal diffSum = cur.total().subtract(o.total());
        if (diffSum.signum() <= 0) {
            return false;
        }
        BigDecimal fee = switchFee(current, o);
        if (fee.signum() == 0) {
            return true;
        }
        BigDecimal saved = diffSum.multiply(BigDecimal.valueOf(cfg.paybackMonths));
        BigDecimal threshold = fee.multiply(BigDecimal.valueOf(Math.max(1, cur.monthly().size())));
        int cmp = saved.compareTo(threshold);
        return cfg.paybackInclusive ? cmp >= 0 : cmp > 0;
    }

    /** Итог подбора, когда текущий тариф абонента неизвестен: только самый выгодный вариант. */
    public record Exploration(PlanOption cheapest, List<PlanOption> allOptions) {
    }

    /** Подбор без текущего тарифа: экономию и окупаемость посчитать нельзя, лучший вариант — можно. */
    public Exploration explore(Map<YearMonth, MonthUsage> months, List<Tariff> tariffs, List<Addon> addons) {
        Map<YearMonth, MonthUsage> m = months.isEmpty() ? emptyMonth() : months;
        List<PlanOption> options = allOptions(m, tariffs, addons);
        List<PlanOption> ranked = new ArrayList<>(options);
        ranked.sort(ranking(null));
        return new Exploration(ranked.get(0), options);
    }

    private List<PlanOption> allOptions(Map<YearMonth, MonthUsage> m, List<Tariff> tariffs, List<Addon> addons) {
        List<PlanOption> options = new ArrayList<>();
        for (Tariff t : tariffs) {
            for (List<Addon> combo : addonCombos(addons)) {
                options.add(evaluate(t, combo, m));
            }
        }
        return options;
    }

    /**
     * Порядок вариантов: по средней стоимости; при равенстве — «остаться» (текущий тариф без пакетов),
     * затем меньше доппакетов, затем tariff_id, затем отсортированный список addon_id лексикографически.
     */
    static Comparator<PlanOption> ranking(String currentId) {
        return Comparator
                .comparing(PlanOption::total)
                .thenComparing(o -> !(currentId != null && o.tariff().id().equals(currentId) && o.addons().isEmpty()))
                .thenComparingInt(o -> o.addons().size())
                .thenComparing(o -> o.tariff().id())
                .thenComparing(Recommender::sortedAddonIds, Recommender::compareLists);
    }

    private static List<String> sortedAddonIds(PlanOption o) {
        return o.addons().stream().map(Addon::id).sorted().toList();
    }

    private static int compareLists(List<String> a, List<String> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    public BigDecimal switchFee(Tariff current, PlanOption option) {
        boolean tariffChanged = !option.tariff().id().equals(current.id());
        return tariffChanged || cfg.feeForAddonOnlyChange ? cfg.switchFee : BigDecimal.ZERO;
    }

    PlanOption evaluate(Tariff t, List<Addon> combo, Map<YearMonth, MonthUsage> months) {
        Map<String, CostBreakdown> monthly = new LinkedHashMap<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<YearMonth, MonthUsage> e : months.entrySet()) {
            CostBreakdown c = calc.monthCost(t, combo, e.getValue());
            monthly.put(NO_USAGE.equals(e.getKey()) ? NO_USAGE_LABEL : e.getKey().toString(), c);
            sum = sum.add(c.total());
        }
        BigDecimal avg = sum.divide(BigDecimal.valueOf(months.size()), MathContext.DECIMAL64);
        return new PlanOption(t, combo, monthly, avg);
    }

    /** Все комбинации из 0..maxAddons доппакетов (без повторов, если не разрешено). */
    List<List<Addon>> addonCombos(List<Addon> addons) {
        List<List<Addon>> out = new ArrayList<>();
        build(addons, 0, new ArrayList<>(), out);
        return out;
    }

    private void build(List<Addon> addons, int start, List<Addon> cur, List<List<Addon>> out) {
        out.add(List.copyOf(cur));
        if (cur.size() == cfg.maxAddons) {
            return;
        }
        for (int i = start; i < addons.size(); i++) {
            cur.add(addons.get(i));
            build(addons, cfg.allowDuplicateAddons ? i : i + 1, cur, out);
            cur.remove(cur.size() - 1);
        }
    }

    /** Нулевое потребление: один пустой месяц, платится только абонплата. */
    private static Map<YearMonth, MonthUsage> emptyMonth() {
        Map<YearMonth, MonthUsage> m = new LinkedHashMap<>();
        m.put(NO_USAGE, new MonthUsage());
        return m;
    }

    /** Служебный ключ для файла без записей. */
    private static final YearMonth NO_USAGE = YearMonth.of(1, 1);
    static final String NO_USAGE_LABEL = "no_usage";
}
