package kg.inai.tariff.model;

import java.math.BigDecimal;

/** Разбивка стоимости одного месяца. */
public record CostBreakdown(BigDecimal fee, BigDecimal addons, BigDecimal overMinutes,
                            BigDecimal overData, BigDecimal overSms, BigDecimal roaming) {

    public BigDecimal total() {
        return fee.add(addons).add(overMinutes).add(overData).add(overSms).add(roaming);
    }
}
