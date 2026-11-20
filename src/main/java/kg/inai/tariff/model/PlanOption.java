package kg.inai.tariff.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Вариант «тариф + доппакеты» с посчитанной стоимостью по месяцам. */
public record PlanOption(Tariff tariff, List<Addon> addons,
                         Map<String, CostBreakdown> monthly, BigDecimal avgMonthly) {

    /** Сумма за все месяцы — точная, без деления (средняя может округлиться, сумма — нет). */
    public BigDecimal total() {
        BigDecimal sum = BigDecimal.ZERO;
        for (CostBreakdown c : monthly.values()) {
            sum = sum.add(c.total());
        }
        return sum;
    }

    public boolean isSame(Tariff current) {
        return tariff.id().equals(current.id()) && addons.isEmpty();
    }

    public String label() {
        if (addons.isEmpty()) {
            return tariff.name();
        }
        StringBuilder sb = new StringBuilder(tariff.name());
        for (Addon a : addons) {
            sb.append(a.name().startsWith("+") ? " " : " + ").append(a.name());
        }
        return sb.toString();
    }
}
