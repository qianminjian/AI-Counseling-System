package com.mindsafe.ai.risk;

import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.common.enums.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SemanticRiskClassifier 单元测试（RISK-202，design/04 §18.3）
 * <p>
 * 覆盖：L0-L5 → 四色档位映射、SAF_001 四占位符渲染、
 * 失败安全（LLM 异常/解析失败/超时 → null 降级纯硬规则）。
 */
class SemanticRiskClassifierTest {

    private PromptTemplateService promptTemplateService;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;

    private SemanticRiskClassifier classifier;

    @BeforeEach
    void setUp() {
        promptTemplateService = mock(PromptTemplateService.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(promptTemplateService.render(eq(PromptTemplateService.SAF_001), anyMap()))
                .thenReturn("SAF-001 渲染后模板");

        // 测试用短门禁（500ms），超时测试单独构造更小值；
        // P1-3 板块02：executor 参数用 Runnable::run 直连（同步执行，保持确定性）
        classifier = new SemanticRiskClassifier(builder, promptTemplateService, 500, Runnable::run);
    }

    private void llmReturns(String response) {
        when(callSpec.content()).thenReturn(response);
    }

    @Nested
    @DisplayName("L0-L5 → 四色档位映射（隐性表达补召）")
    class LevelMapping {

        @Test
        @DisplayName("L4/L5（\"如果我消失就好了\"类隐性表达）→ RED")
        void l4l5_mapsToRed() {
            llmReturns("{\"risk_level\": \"L4\", \"evidence\": [\"如果我消失就好了\"]}");
            assertThat(classifier.classify("如果我消失就好了", null, null, 4)).isEqualTo(RiskLevel.RED);

            llmReturns("{\"risk_level\": \"L5\"}");
            assertThat(classifier.classify("想睡一辈子不醒", null, null, 4)).isEqualTo(RiskLevel.RED);
        }

        @Test
        @DisplayName("L3 → ORANGE；L1/L2 → YELLOW")
        void l3_orange_l1l2_yellow() {
            llmReturns("{\"risk_level\": \"L3\"}");
            assertThat(classifier.classify("把我的奥特曼卡都送人", null, null, 3)).isEqualTo(RiskLevel.ORANGE);

            llmReturns("{\"risk_level\": \"L2\"}");
            assertThat(classifier.classify("最近总是很累", null, null, 3)).isEqualTo(RiskLevel.YELLOW);

            llmReturns("{\"risk_level\": \"L1\"}");
            assertThat(classifier.classify("有点不开心", null, null, 3)).isEqualTo(RiskLevel.YELLOW);
        }

        @Test
        @DisplayName("L0 / 无法解析的响应 → null（无语义风险）")
        void l0_orGarbage_returnsNull() {
            llmReturns("{\"risk_level\": \"L0\"}");
            assertThat(classifier.classify("今天体育课很好玩", null, null, 3)).isNull();

            llmReturns("我不是 JSON");
            assertThat(classifier.classify("今天体育课很好玩", null, null, 3)).isNull();

            llmReturns(null);
            assertThat(classifier.classify("今天体育课很好玩", null, null, 3)).isNull();
        }
    }

    @Nested
    @DisplayName("SAF_001 模板渲染")
    class TemplateRendering {

        @Test
        @DisplayName("四占位符齐备；上下文/历史为空时给默认值")
        @SuppressWarnings("unchecked")
        void rendersAllPlaceholders_withDefaults() {
            llmReturns("{\"risk_level\": \"L0\"}");

            classifier.classify("消息内容", null, null, 5);

            ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
            verify(promptTemplateService).render(eq(PromptTemplateService.SAF_001), captor.capture());
            Map<String, String> vars = captor.getValue();
            assertThat(vars)
                    .containsEntry("current_message", "消息内容")
                    .containsEntry("recent_context", "无")
                    .containsEntry("risk_history_summary", "无历史记录")
                    .containsEntry("grade_level", "5");
        }
    }

    @Nested
    @DisplayName("失败安全（§18.3：降级纯硬规则，不阻断对话）")
    class FailSafe {

        @Test
        @DisplayName("LLM 调用异常 → null，不抛出")
        void llmFailure_returnsNull() {
            when(callSpec.content()).thenThrow(new RuntimeException("LLM unavailable"));

            assertThat(classifier.classify("如果我消失就好了", null, null, 4)).isNull();
        }

        @Test
        @DisplayName("超过延迟门禁 → null（放弃本轮语义补召，硬规则兜底）")
        void timeout_returnsNull() {
            ChatClient.Builder builder = mock(ChatClient.Builder.class);
            when(builder.build()).thenReturn(chatClient);
            // 超时路径必须真实异步（直连会阻塞本线程直到 sleep 结束，测不出门禁）；
            // 测试局部小池，非生产自建（P1-3 板块02 收敛的是生产代码侧）
            SemanticRiskClassifier tight = new SemanticRiskClassifier(builder, promptTemplateService, 50,
                    java.util.concurrent.Executors.newSingleThreadExecutor());
            when(callSpec.content()).thenAnswer(inv -> {
                Thread.sleep(400);
                return "{\"risk_level\": \"L4\"}";
            });

            assertThat(tight.classify("如果我消失就好了", null, null, 4)).isNull();
        }
    }
}
