package kg.inai.tariff.llm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Шаблонное объяснение и факты для промпта — без сети. */
class ExplainerTest {

    private static Map<String, Object> switchResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("current_tariff_name", "Базовый");
        r.put("current_cost", new BigDecimal("356.00"));
        r.put("recommendation", "switch");
        r.put("recommended_label", "Старт +5 ГБ");
        r.put("new_cost", new BigDecimal("300.00"));
        r.put("savings_per_month", new BigDecimal("56.00"));
        r.put("switch_fee", new BigDecimal("50.00"));
        r.put("net_savings_3_months", new BigDecimal("118.00"));
        r.put("reason", "дешевле");
        r.put("tariff_comparison", List.of(Map.of("tariff_name", "Старт", "best_addon_names", List.of("+5 ГБ"),
                "best_cost", new BigDecimal("300.00"), "cost_without_addons", new BigDecimal("390.00"))));
        return r;
    }

    @Test
    void templateForSwitchUsesExactNumbers() {
        String t = Explainer.template(switchResult());
        assertTrue(t.contains("356"), t);
        assertTrue(t.contains("300"), t);
        assertTrue(t.contains("56"), t);
        assertTrue(t.contains("118"), t);
        assertTrue(t.contains("Старт +5 ГБ"), t);
    }

    @Test
    void templateForStayIncludesReason() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("current_tariff_name", "Ночной");
        r.put("current_cost", new BigDecimal("350.00"));
        r.put("recommendation", "stay");
        r.put("reason", "Экономия не окупает плату 50 сом.");
        String t = Explainer.template(r);
        assertTrue(t.startsWith("Советуем остаться на тарифе «Ночной»"), t);
        assertTrue(t.contains("не окупает"), t);
    }

    @Test
    void factsContainComparison() {
        String f = Explainer.facts(switchResult());
        assertTrue(f.contains("Рекомендация: перейти"), f);
        assertTrue(f.contains("Старт + +5 ГБ: 300.00"), f);
    }

    @Test
    void withoutKeyFallsBackToTemplate() {
        if (System.getenv("ANTHROPIC_API_KEY") != null) {
            return;
        }
        Map<String, Object> out = Explainer.fromEnv().explain(switchResult());
        assertTrue("template".equals(out.get("source")), out.toString());
    }
}
