package kg.inai.tariff.core;

/** Текущий тариф абонента не указан ни явно, ни в CSV, ни в subscribers.json. */
public class MissingCurrentTariffException extends IllegalArgumentException {

    public MissingCurrentTariffException(String message) {
        super(message);
    }
}
