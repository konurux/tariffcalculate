package kg.inai.tariff.model;

import java.math.BigDecimal;

/**
 * Тариф. Лимит {@code null} означает безлимит.
 *
 * @param fee            абонплата, сом/мес
 * @param minutes        пакет минут
 * @param gb             пакет ГБ
 * @param sms            пакет SMS
 * @param priceMin       цена минуты сверх пакета
 * @param priceGb        цена ГБ сверх пакета
 * @param priceSms       цена SMS сверх пакета
 * @param nightUnlimited безлимитный интернет 01:00–07:00
 * @param roamMin        цена минуты в роуминге (не входит в пакет)
 * @param roamGb         цена ГБ в роуминге
 * @param roamSms        цена SMS в роуминге
 */
public record Tariff(
        String id,
        String name,
        BigDecimal fee,
        Integer minutes,
        BigDecimal gb,
        Integer sms,
        BigDecimal priceMin,
        BigDecimal priceGb,
        BigDecimal priceSms,
        boolean nightUnlimited,
        BigDecimal roamMin,
        BigDecimal roamGb,
        BigDecimal roamSms) {
}
