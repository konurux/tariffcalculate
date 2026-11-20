package kg.inai.tariff;

import kg.inai.tariff.model.Addon;
import kg.inai.tariff.model.Tariff;

import java.math.BigDecimal;

final class Fixtures {

    private Fixtures() {
    }

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    /** 150 сом: 100 мин, 3 ГБ, 50 SMS; сверх: 1.5 / 60 / 1. Роуминг 35 / 400 / 10. */
    static Tariff start() {
        return new Tariff("T1", "Старт", d("150"), 100, d("3"), 50,
                d("1.5"), d("60"), d("1"), false, d("35"), d("400"), d("10"));
    }

    /** То же, но с ночным безлимитом и абонплатой 170. */
    static Tariff night() {
        return new Tariff("TN", "Ночной", d("170"), 100, d("3"), 50,
                d("1.5"), d("60"), d("1"), true, d("35"), d("400"), d("10"));
    }

    /** 300 сом, большой пакет. */
    static Tariff big() {
        return new Tariff("T2", "Базовый", d("300"), 300, d("10"), 100,
                d("1.2"), d("50"), d("1"), false, d("30"), d("350"), d("8"));
    }

    static Addon gb5() {
        return new Addon("A_GB5", "+5 ГБ", d("100"), 0, d("5"), 0);
    }
}
