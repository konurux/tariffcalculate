package kg.inai.tariff.model;

import java.math.BigDecimal;

/** Потребление за один месяц. Домашнее и роуминговое считаются раздельно. */
public final class MonthUsage {
    public long minutes;
    public long sms;
    /** Весь домашний трафик, МБ. */
    public BigDecimal dataMb = BigDecimal.ZERO;
    /** Часть dataMb, попавшая в ночное окно 01:00–07:00. */
    public BigDecimal nightMb = BigDecimal.ZERO;
    public long roamMinutes;
    public long roamSms;
    public BigDecimal roamMb = BigDecimal.ZERO;

    @Override
    public String toString() {
        return "MonthUsage{min=" + minutes + ", sms=" + sms + ", dataMb=" + dataMb
                + ", nightMb=" + nightMb + ", roamMin=" + roamMinutes + ", roamSms=" + roamSms
                + ", roamMb=" + roamMb + '}';
    }
}
