package kg.inai.tariff.core;

import kg.inai.tariff.model.Addon;
import kg.inai.tariff.model.CostBreakdown;
import kg.inai.tariff.model.MonthUsage;
import kg.inai.tariff.model.Tariff;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/** Стоимость месяца на тарифе с доппакетами (обязательные пункты 2 и 3). */
public final class CostCalculator {

    private final Config cfg;

    public CostCalculator(Config cfg) {
        this.cfg = cfg;
    }

    public CostBreakdown monthCost(Tariff t, List<Addon> addons, MonthUsage u) {
        BigDecimal addonPrice = BigDecimal.ZERO;
        long addMin = 0;
        long addSms = 0;
        BigDecimal addGb = BigDecimal.ZERO;
        for (Addon a : addons) {
            addonPrice = addonPrice.add(a.price());
            addMin += a.minutes();
            addSms += a.sms();
            addGb = addGb.add(a.gb());
        }

        BigDecimal billableMb = t.nightUnlimited() ? u.dataMb.subtract(u.nightMb) : u.dataMb;
        if (billableMb.signum() < 0) {
            billableMb = BigDecimal.ZERO;
        }
        BigDecimal usedGb = toGb(billableMb);

        BigDecimal overMin = t.minutes() == null ? BigDecimal.ZERO
                : positive(BigDecimal.valueOf(u.minutes - (t.minutes() + addMin)));
        BigDecimal overSms = t.sms() == null ? BigDecimal.ZERO
                : positive(BigDecimal.valueOf(u.sms - (t.sms() + addSms)));
        BigDecimal overGb = t.gb() == null ? BigDecimal.ZERO
                : positive(usedGb.subtract(t.gb().add(addGb)));
        if (cfg.roundGbUp && overGb.signum() > 0) {
            overGb = overGb.setScale(0, RoundingMode.CEILING);
        }

        BigDecimal roaming = BigDecimal.valueOf(u.roamMinutes).multiply(t.roamMin())
                .add(toGb(u.roamMb).multiply(t.roamGb()))
                .add(BigDecimal.valueOf(u.roamSms).multiply(t.roamSms()));

        return new CostBreakdown(
                t.fee(),
                addonPrice,
                overMin.multiply(t.priceMin()),
                overGb.multiply(t.priceGb()),
                overSms.multiply(t.priceSms()),
                roaming);
    }

    private BigDecimal toGb(BigDecimal mb) {
        return mb.divide(BigDecimal.valueOf(cfg.mbPerGb), MathContext.DECIMAL64);
    }

    private static BigDecimal positive(BigDecimal x) {
        return x.signum() > 0 ? x : BigDecimal.ZERO;
    }
}
