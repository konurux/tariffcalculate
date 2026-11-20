package kg.inai.tariff.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Строка детализации.
 *
 * @param amount      call — секунды, sms — штуки, data — МБ
 * @param durationSec длительность data-сессии, если есть в данных (иначе null)
 */
public record UsageRecord(LocalDateTime dateTime, UsageType type, BigDecimal amount,
                          boolean roaming, Integer durationSec) {
}
