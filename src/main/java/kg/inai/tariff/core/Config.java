package kg.inai.tariff.core;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Все спорные допущения в одном месте. Если организаторы ответят иначе — меняем здесь,
 * тесты подскажут, что сломалось.
 */
public final class Config {

    /** Ночное окно безлимитного интернета: [01:00, 07:00). */
    public final LocalTime nightStart;
    public final LocalTime nightEnd;
    /** Сколько МБ в 1 ГБ. */
    public final int mbPerGb;
    /** true — data.amount в ГБ (правила организаторов), false — в МБ. */
    public final boolean dataAmountInGb;
    /** false — перерасход по ГБ пропорционально; true — вверх до целого ГБ. */
    public final boolean roundGbUp;
    /** Плата за смену тарифа, сом. */
    public final BigDecimal switchFee;
    /** Горизонт окупаемости, мес. */
    public final int paybackMonths;
    /** false: переход, если экономия за горизонт строго больше платы; true: больше или равна. */
    public final boolean paybackInclusive;
    /** Платится ли switchFee, если тариф тот же и меняются только доппакеты. */
    public final boolean feeForAddonOnlyChange;
    /** Максимум доппакетов в комбинации. */
    public final int maxAddons;
    /** Можно ли подключить один и тот же доппакет дважды (по правилам организаторов — да). */
    public final boolean allowDuplicateAddons;

    private Config(Builder b) {
        this.nightStart = b.nightStart;
        this.nightEnd = b.nightEnd;
        this.mbPerGb = b.mbPerGb;
        this.dataAmountInGb = b.dataAmountInGb;
        this.roundGbUp = b.roundGbUp;
        this.switchFee = b.switchFee;
        this.paybackMonths = b.paybackMonths;
        this.paybackInclusive = b.paybackInclusive;
        this.feeForAddonOnlyChange = b.feeForAddonOnlyChange;
        this.maxAddons = b.maxAddons;
        this.allowDuplicateAddons = b.allowDuplicateAddons;
    }

    public static Config defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private LocalTime nightStart = LocalTime.of(1, 0);
        private LocalTime nightEnd = LocalTime.of(7, 0);
        private int mbPerGb = 1024;
        private boolean dataAmountInGb = true;
        private boolean roundGbUp = false;
        private BigDecimal switchFee = new BigDecimal("50");
        private int paybackMonths = 3;
        private boolean paybackInclusive = false;
        private boolean feeForAddonOnlyChange = false;
        private int maxAddons = 2;
        private boolean allowDuplicateAddons = true;

        public Builder mbPerGb(int v) { this.mbPerGb = v; return this; }
        public Builder dataAmountInGb(boolean v) { this.dataAmountInGb = v; return this; }
        public Builder roundGbUp(boolean v) { this.roundGbUp = v; return this; }
        public Builder switchFee(BigDecimal v) { this.switchFee = v; return this; }
        public Builder paybackMonths(int v) { this.paybackMonths = v; return this; }
        public Builder paybackInclusive(boolean v) { this.paybackInclusive = v; return this; }
        public Builder feeForAddonOnlyChange(boolean v) { this.feeForAddonOnlyChange = v; return this; }
        public Builder maxAddons(int v) { this.maxAddons = v; return this; }
        public Builder allowDuplicateAddons(boolean v) { this.allowDuplicateAddons = v; return this; }

        public Config build() {
            return new Config(this);
        }
    }
}
