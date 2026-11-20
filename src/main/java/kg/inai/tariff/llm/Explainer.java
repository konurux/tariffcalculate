package kg.inai.tariff.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.MessageCreateParams;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Бонус: объяснение выбора простым языком.
 * <p>
 * Если задан ANTHROPIC_API_KEY — текст пишет Claude по фактам из результата recommend().
 * Без ключа или при любой ошибке API — детерминированный шаблон, чтобы демо и расчёт
 * никогда не зависели от сети. Сам расчёт LLM не трогает: цифры берутся только из JSON.
 */
public final class Explainer {

    public static final String MODEL = "claude-opus-5-5";

    private static final String SYSTEM_PROMPT = """
            Ты помогаешь абоненту мобильного оператора в Кыргызстане понять, какой тариф ему выгоднее.
            Тебе дают факты, уже посчитанные программой. Напиши объяснение на русском языке:
            3–5 коротких предложений, простыми словами, без таблиц, списков и markdown.
            Скажи, что программа советует (перейти или остаться), сколько абонент платит сейчас,
            сколько будет платить и сколько сэкономит в месяц. Если совет «остаться», объясни почему.
            Упомяни, за счёт чего получается разница (перерасход, ночной интернет, доппакет, роуминг),
            если это видно из фактов. Используй только числа из фактов, ничего не придумывай и не пересчитывай.
            Суммы пиши в сомах.""";

    private final AnthropicClient client;

    private Explainer(AnthropicClient client) {
        this.client = client;
    }

    /** Клиент создаётся, только если есть ключ; иначе работает шаблон. */
    public static Explainer fromEnv() {
        boolean hasKey = notBlank(System.getenv("ANTHROPIC_API_KEY"))
                || notBlank(System.getenv("ANTHROPIC_AUTH_TOKEN"));
        if (!hasKey) {
            return new Explainer(null);
        }
        AnthropicClient c = AnthropicOkHttpClient.builder()
                .fromEnv()
                .timeout(Duration.ofSeconds(60))
                .maxRetries(1)
                .build();
        return new Explainer(c);
    }

    public boolean llmEnabled() {
        return client != null;
    }

    /** Возвращает {explanation, source: "llm" | "template", model?, note?}. */
    public Map<String, Object> explain(Map<String, Object> result) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (client != null) {
            try {
                String text = askClaude(facts(result));
                if (text != null && !text.isBlank()) {
                    out.put("explanation", text.trim());
                    out.put("source", "llm");
                    out.put("model", MODEL);
                    return out;
                }
                out.put("note", "LLM вернула пустой ответ — показан шаблон");
            } catch (RuntimeException e) {
                out.put("note", "LLM недоступна (" + e.getClass().getSimpleName() + ") — показан шаблон");
            }
        } else {
            out.put("note", "ANTHROPIC_API_KEY не задан — показан шаблон");
        }
        out.put("explanation", template(result));
        out.put("source", "template");
        return out;
    }

    private String askClaude(String facts) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .system(SYSTEM_PROMPT)
                .addUserMessage("Факты:\n" + facts)
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                .addBeta("server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        BetaMessage msg = client.beta().messages().create(params);
        boolean refused = msg.stopReason().map(r -> r.toString().equalsIgnoreCase("refusal")).orElse(false);
        if (refused) {
            return null;
        }
        return msg.content().stream()
                .flatMap(b -> b.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"));
    }

    /** Компактные факты для промпта: только то, что уже посчитано. */
    static String facts(Map<String, Object> r) {
        StringBuilder sb = new StringBuilder();
        line(sb, "Текущий тариф", r.get("current_tariff_name"));
        line(sb, "Сейчас платит в среднем, сом/мес", r.get("current_cost"));
        line(sb, "Помесячно сейчас", r.get("current_monthly_costs"));
        Object rec = r.get("recommendation");
        line(sb, "Рекомендация", "switch".equals(rec) ? "перейти"
                : "best".equals(rec) ? "текущий тариф неизвестен — подобран самый выгодный, экономию не считаем"
                : "остаться");
        line(sb, "Рекомендованный вариант", r.get("recommended_label"));
        line(sb, "Новая стоимость, сом/мес", r.get("new_cost"));
        line(sb, "Экономия, сом/мес", r.get("savings_per_month"));
        line(sb, "Плата за смену тарифа, сом", r.get("switch_fee"));
        r.keySet().stream().filter(k -> k.startsWith("net_savings_")).findFirst()
                .ifPresent(k -> line(sb, "Чистая выгода за 3 месяца с учётом платы, сом", r.get(k)));
        line(sb, "Причина (от программы)", r.get("reason"));
        Object cheapest = r.get("cheapest_option");
        if (cheapest instanceof Map<?, ?> c) {
            line(sb, "Самый дешёвый вариант вообще", c.get("tariff_name") + " " + c.get("addons")
                    + ", " + c.get("avg_monthly_cost") + " сом/мес");
        }
        Object cmp = r.get("tariff_comparison");
        if (cmp instanceof List<?> rows) {
            sb.append("Сравнение тарифов (лучший вариант на каждом):\n");
            for (Object o : rows) {
                if (o instanceof Map<?, ?> row) {
                    sb.append("  - ").append(row.get("tariff_name"));
                    Object addons = row.get("best_addon_names");
                    if (addons instanceof List<?> l && !l.isEmpty()) {
                        sb.append(" + ").append(String.join(", ", l.stream().map(String::valueOf).toList()));
                    }
                    sb.append(": ").append(row.get("best_cost")).append(" сом/мес (без доппакетов ")
                            .append(row.get("cost_without_addons")).append(")\n");
                }
            }
        }
        return sb.toString();
    }

    /** Запасной текст без LLM. */
    static String template(Map<String, Object> r) {
        if ("best".equals(r.get("recommendation"))) {
            return "При вашем потреблении самый выгодный вариант — «" + r.get("recommended_label")
                    + "»: в среднем " + money(r.get("new_cost")) + " сом в месяц. "
                    + "Чтобы узнать, сколько вы сэкономите по сравнению с нынешним тарифом, укажите его в форме.";
        }
        String cur = String.valueOf(r.get("current_tariff_name"));
        String curCost = money(r.get("current_cost"));
        if ("switch".equals(r.get("recommendation"))) {
            String rec = String.valueOf(r.get("recommended_label"));
            String net = r.keySet().stream().filter(k -> k.startsWith("net_savings_")).findFirst()
                    .map(k -> money(r.get(k))).orElse(null);
            return "Сейчас на тарифе «" + cur + "» вы платите в среднем " + curCost + " сом в месяц. "
                    + "С вариантом «" + rec + "» за то же потребление вышло бы " + money(r.get("new_cost"))
                    + " сом — это на " + money(r.get("savings_per_month")) + " сом в месяц дешевле. "
                    + "Смена тарифа стоит " + money(r.get("switch_fee")) + " сом"
                    + (net != null ? ", и даже с этой платой за 3 месяца вы сэкономите " + net + " сом." : ".");
        }
        String reason = String.valueOf(r.getOrDefault("reason", ""));
        return "Советуем остаться на тарифе «" + cur + "»: вы платите в среднем " + curCost
                + " сом в месяц. " + reason;
    }

    private static void line(StringBuilder sb, String k, Object v) {
        if (v != null) {
            sb.append(k).append(": ").append(v).append('\n');
        }
    }

    private static String money(Object v) {
        if (v instanceof BigDecimal b) {
            return b.stripTrailingZeros().scale() <= 0 ? b.setScale(0).toPlainString() : b.toPlainString();
        }
        return String.valueOf(v);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
