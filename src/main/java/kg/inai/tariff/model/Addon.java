package kg.inai.tariff.model;

import java.math.BigDecimal;

/** Доппакет, подключается на месяц и добавляет объём к пакету тарифа. */
public record Addon(String id, String name, BigDecimal price, int minutes, BigDecimal gb, int sms) {
}
