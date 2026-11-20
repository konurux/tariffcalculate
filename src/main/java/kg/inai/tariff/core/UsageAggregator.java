package kg.inai.tariff.core;

import kg.inai.tariff.model.MonthUsage;
import kg.inai.tariff.model.UsageRecord;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Агрегация детализации по месяцам (обязательный пункт 1 и часть пункта 3). */
public final class UsageAggregator {

    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    private final Config cfg;

    public UsageAggregator(Config cfg) {
        this.cfg = cfg;
    }

    /** Звонок округляется вверх до целой минуты; 0 секунд → 0 минут. */
    public static long callMinutes(BigDecimal seconds) {
        if (seconds.signum() <= 0) {
            return 0;
        }
        return seconds.divide(SIXTY, 0, RoundingMode.CEILING).longValueExact();
    }

    public Map<YearMonth, MonthUsage> aggregate(List<UsageRecord> records) {
        Map<YearMonth, MonthUsage> months = new TreeMap<>();
        for (UsageRecord r : records) {
            MonthUsage m = months.computeIfAbsent(YearMonth.from(r.dateTime()), k -> new MonthUsage());
            switch (r.type()) {
                case CALL -> {
                    long min = callMinutes(r.amount());
                    if (r.roaming()) {
                        m.roamMinutes += min;
                    } else {
                        m.minutes += min;
                    }
                }
                case SMS -> {
                    long cnt = r.amount().setScale(0, RoundingMode.CEILING).longValue();
                    if (r.roaming()) {
                        m.roamSms += cnt;
                    } else {
                        m.sms += cnt;
                    }
                }
                case DATA -> {
                    BigDecimal mb = toMb(r.amount());
                    if (r.roaming()) {
                        m.roamMb = m.roamMb.add(mb);
                    } else {
                        m.dataMb = m.dataMb.add(mb);
                        m.nightMb = m.nightMb.add(nightPartMb(r, mb));
                    }
                }
            }
        }
        return months;
    }

    /**
     * Ночная доля трафика сессии. Если известна длительность — делим объём пропорционально
     * времени внутри окна 01:00–07:00 (сессия через границу ночи). Иначе — по времени начала.
     */
    BigDecimal nightPartMb(UsageRecord r, BigDecimal mb) {
        Integer dur = r.durationSec();
        if (dur != null && dur > 0) {
            long night = nightSeconds(r.dateTime(), dur);
            if (night == 0) {
                return BigDecimal.ZERO;
            }
            if (night == dur) {
                return mb;
            }
            return mb.multiply(BigDecimal.valueOf(night))
                    .divide(BigDecimal.valueOf(dur), MathContext.DECIMAL64);
        }
        return isNight(r.dateTime()) ? mb : BigDecimal.ZERO;
    }

    /** data.amount → МБ: по правилам организаторов amount задан в ГБ (1 ГБ = 1024 МБ). */
    private BigDecimal toMb(BigDecimal amount) {
        return cfg.dataAmountInGb ? amount.multiply(BigDecimal.valueOf(cfg.mbPerGb)) : amount;
    }

    public boolean isNight(LocalDateTime dt) {
        var t = dt.toLocalTime();
        return !t.isBefore(cfg.nightStart) && t.isBefore(cfg.nightEnd);
    }

    /** Сколько секунд интервала [start, start + durationSec) лежит в ночных окнах. */
    public long nightSeconds(LocalDateTime start, long durationSec) {
        if (durationSec <= 0) {
            return 0;
        }
        LocalDateTime end = start.plusSeconds(durationSec);
        long total = 0;
        for (LocalDate day = start.toLocalDate().minusDays(1);
             !day.atTime(cfg.nightStart).isAfter(end); day = day.plusDays(1)) {
            LocalDateTime ns = day.atTime(cfg.nightStart);
            LocalDateTime ne = day.atTime(cfg.nightEnd);
            LocalDateTime from = start.isAfter(ns) ? start : ns;
            LocalDateTime to = end.isBefore(ne) ? end : ne;
            if (to.isAfter(from)) {
                total += Duration.between(from, to).getSeconds();
            }
        }
        return total;
    }
}
