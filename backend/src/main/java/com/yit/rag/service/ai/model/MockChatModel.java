package com.yit.rag.service.ai.model;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 本地模拟模型（mock 提供方）。
 *
 * <p>这个类解决什么问题：真实的 DeepSeek / 通义 / OpenAI 都需要 API Key，
 * 而且联网调用会带来费用与不确定性。但「面试演示」和「CI 跑测试」都要求
 * 离线也能把完整链路跑通 —— 检索、引用、流式输出、成本记账一个都不能少。
 *
 * <p>所以 mock 不是一个「返回固定字符串」的敷衍实现，而是<b>真的按 Prompt 语义干活</b>：
 * <ol>
 *   <li>从系统提示词里用固定标记切出「参考资料 / 工具结果」和「用户问题」</li>
 *   <li>按「问题词在句子里命中多少」给每个句子打分，挑最相关的几句</li>
 *   <li>组装成带引用编号的回答，并输出与真实模型同构的 token 统计</li>
 * </ol>
 *
 * <p>关键价值：<b>智能客服的 Tool Calling 在 mock 模式下也能完整演示</b>。
 * 因为客户服务 Agent 走的路径是「工具结果回灌到 Prompt 里」，
 * mock 模型只要把回灌进来的订单信息读出来组织成人话即可，
 * 完全不需要模型真的会 function calling。这样离线演示时
 * 「查订单 → 复述订单状态 → 建工单 → 转人工」整条链路都能看到真实效果。
 */
@Slf4j
public class MockChatModel implements ChatModel {

    /** 上下文块标记：与后端拼 Prompt 时使用的标记保持一致 */
    private static final String CTX_START = "【参考资料】";
    private static final String CTX_END = "【用户问题】";
    private static final String TOOL_START = "【工具调用结果】";
    private static final String Q_START = "【用户问题】";

    /** 断句：中文句末标点或换行 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？；\\n])");

    @Override
    public ChatResult chat(String systemPrompt, String userMessage, List<ChatTurn> history, Double temperature) {
        long start = System.currentTimeMillis();
        String system = systemPrompt == null ? "" : systemPrompt;
        String question = userMessage == null ? "" : userMessage;

        // 1) 优先看有没有工具调用结果 —— 智能客服场景的核心路径
        String toolResult = extractBlock(system, TOOL_START, CTX_START);
        if (toolResult.isBlank()) {
            toolResult = extractBlock(system, TOOL_START, Q_START);
        }

        String content;
        if (!toolResult.isBlank()) {
            content = composeWithToolResult(question, toolResult);
        } else {
            // 2) 没有工具结果，就走 RAG 检索上下文
            String context = extractBlock(system, CTX_START, CTX_END);
            if (context.isBlank()) {
                context = extractBlock(system, "【知识库参考】", CTX_END);
            }
            content = composeWithContext(question, context);
        }

        // token 估算：中文大致 2 字符 ≈ 1 token，与 AI 服务侧 MockLLM 的算法保持一致
        int promptTokens = Math.max(1, (system.length() + question.length()) / 2);
        int completionTokens = Math.max(1, content.length() / 2);
        long latency = System.currentTimeMillis() - start + 20;  // 加一点延迟，模拟真实调用

        log.debug("[mock] 生成回答 {} 字，promptTokens={} completionTokens={}",
                content.length(), promptTokens, completionTokens);
        return new ChatResult(content, "mock", "mock-chat", promptTokens, completionTokens, latency);
    }

    // ==================================================================
    //  回答组装
    // ==================================================================

    /** 有工具结果时：把结构化结果复述成人话（不做任何编造） */
    private String composeWithToolResult(String question, String toolResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("（离线模拟回答 · 智能客服工具链已生效）\n\n");
        sb.append("我帮你查到了以下信息：\n");
        for (String line : toolResult.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                sb.append("- ").append(t).append('\n');
            }
        }
        sb.append('\n');
        sb.append("如果以上信息没有解决你的问题，可以直接回复「转人工」，我马上为你转接人工客服。");
        return sb.toString();
    }

    /** 有检索上下文时：按问题词命中度挑句子，拼成带引用的回答 */
    private String composeWithContext(String question, String context) {
        if (context.isBlank() || context.contains("无检索结果")) {
            return "（离线模拟回答）你问的是「" + abbreviate(question, 40) + "」。\n"
                    + "当前没有检索到知识库内容，因此不编造答案。\n"
                    + "建议：确认该问题是否属于当前知识库的范围，或转人工客服协助。";
        }
        List<String> picks = bestSentences(question, context, 4);
        if (picks.isEmpty()) {
            picks = sentences(context).stream().limit(3).toList();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("（离线模拟回答 · 检索增强已生效）\n\n");
        sb.append("根据知识库内容，关于「").append(abbreviate(question, 40)).append("」：\n");
        for (String p : picks) {
            sb.append("- ").append(p).append('\n');
        }
        sb.append("\n以上结论来自检索到的资料片段 [1][2]，可在引用区查看原文与页码。");
        return sb.toString();
    }

    // ==================================================================
    //  工具方法
    // ==================================================================

    /** 从文本里切出两个标记之间的内容 */
    static String extractBlock(String text, String startMarker, String endMarker) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int i = text.indexOf(startMarker);
        if (i < 0) {
            return "";
        }
        i += startMarker.length();
        int j = endMarker == null || endMarker.isEmpty() ? -1 : text.indexOf(endMarker, i);
        return text.substring(i, j > 0 ? j : text.length()).trim();
    }

    /** 按中文标点断句，过滤掉过短的碎片 */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String part : SENTENCE_SPLIT.split(text)) {
            String t = part.strip();
            if (t.length() >= 8) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 句子相关性打分：用「问题字符与句子字符的重合度」近似。
     *
     * <p>为什么不引入分词库？因为 mock 的目的是「离线可用、零依赖」，
     * 用字符 bigram 重合已经足够把最相关的句子排到前面，
     * 而真实的分词与向量化是 AI 服务的职责，不该在这里重复实现。
     */
    static List<String> bestSentences(String question, String context, int limit) {
        Set<String> qGrams = bigrams(question);
        record Scored(int score, int len, String text) {
        }
        List<Scored> scored = new ArrayList<>();
        for (String s : sentences(context)) {
            Set<String> sGrams = bigrams(s);
            int overlap = 0;
            for (String g : qGrams) {
                if (sGrams.contains(g)) {
                    overlap++;
                }
            }
            if (overlap > 0) {
                scored.add(new Scored(overlap, s.length(), s));
            }
        }
        scored.sort((a, b) -> a.score() != b.score() ? b.score() - a.score() : a.len() - b.len());
        List<String> out = new ArrayList<>();
        for (Scored s : scored) {
            if (out.size() >= limit) {
                break;
            }
            out.add(s.text());
        }
        return out;
    }

    /** 中文按 2 字符滑窗取 bigram，英文/数字按整词 */
    private static Set<String> bigrams(String text) {
        Set<String> grams = new LinkedHashSet<>();
        if (text == null) {
            return grams;
        }
        String cleaned = text.replaceAll("[\\s，。！？；：、（）【】《》\\[\\](){}.,!?;:'\"-]", "");
        for (int i = 0; i + 2 <= cleaned.length(); i++) {
            grams.add(cleaned.substring(i, i + 2));
        }
        return grams;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').strip();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    @Override
    public String providerCode() {
        return "mock";
    }

    @Override
    public String modelName() {
        return "mock-chat";
    }
}
