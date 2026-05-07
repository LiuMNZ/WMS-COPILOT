# AiOpsService 代码复习

`AiOpsService.java` 是项目里“自动化运维告警分析”的核心服务。它不负责普通聊天，也不负责上传文档；它负责组织多个 Agent 一起完成一件事：查询告警、查指标、查日志、查内部文档，然后生成一份《告警分析报告》。

可以先把它理解成一个“调度中心”：

```text
AiOpsService
  ↓
Supervisor Agent 负责指挥
  ↓
Planner Agent 负责规划、分析、写最终报告
  ↓
Executor Agent 负责执行具体查询动作
  ↓
工具：查时间 / 查内部文档 / 查 Prometheus / 查日志
```

## 1. 整体作用

文件开头的注释写得很直接：

```java
/**
 * AI Ops 智能运维服务
 * 负责多 Agent 协作的告警分析流程
 */
@Service
public class AiOpsService {
```

这里的 AI Ops 可以理解为：用 AI 帮你做运维排查。

比如系统有告警了，AI 需要：

1. 看现在有哪些告警；
2. 查 Prometheus 指标；
3. 查腾讯云日志；
4. 查公司内部运维文档；
5. 判断可能原因；
6. 给出处理建议；
7. 最后输出一份 Markdown 报告。

这个文件就是负责把这些步骤组织起来。

## 2. `@Service` 是什么意思？

```java
@Service
public class AiOpsService {
```

可以先这样理解：

- 普通 Java 类：需要 `new AiOpsService()` 才能用。
- 加了 `@Service`：Spring Boot 会自动帮你创建这个对象，并放到容器里。
- 其他类如果要用它，可以通过 `@Autowired` 自动注入。

所以这个类是一个 Spring 管理的服务类。

## 3. 成员变量：注入工具

```java
@Autowired
private DateTimeTools dateTimeTools;

@Autowired
private InternalDocsTools internalDocsTools;

@Autowired
private QueryMetricsTools queryMetricsTools;

@Autowired(required = false)
private QueryLogsTools queryLogsTools;
```

这几个都是给 Agent 调用的工具：

| 字段 | 作用 |
|------|------|
| `DateTimeTools` | 查询当前时间 |
| `InternalDocsTools` | 查询内部文档知识库 |
| `QueryMetricsTools` | 查询 Prometheus 指标 / 告警 |
| `QueryLogsTools` | 查询日志，mock 模式下才会注入 |

`@Autowired(required = false)` 的意思是：如果 Spring 容器里有 `QueryLogsTools`，就注入；没有也不要报错。

真实环境下日志查询可能由腾讯云 MCP 工具提供，不一定用这个本地 `QueryLogsTools`。

## 4. 核心方法：`executeAiOpsAnalysis`

```java
public Optional<OverAllState> executeAiOpsAnalysis(
        DashScopeChatModel chatModel,
        ToolCallback[] toolCallbacks
) throws GraphRunnerException {
    logger.info("开始执行 AI Ops 多 Agent 协作流程");

    ReactAgent plannerAgent = buildPlannerAgent(chatModel, toolCallbacks);
    ReactAgent executorAgent = buildExecutorAgent(chatModel, toolCallbacks);

    SupervisorAgent supervisorAgent = SupervisorAgent.builder()
            .name("ai_ops_supervisor")
            .description("负责调度 Planner 与 Executor 的多 Agent 控制器")
            .model(chatModel)
            .systemPrompt(buildSupervisorSystemPrompt())
            .subAgents(List.of(plannerAgent, executorAgent))
            .build();

    String taskPrompt = "你是企业级 SRE，接到了自动化告警排查任务。请结合工具调用，执行**规划→执行→再规划**的闭环，并最终按照固定模板输出《告警分析报告》。禁止编造虚假数据，如连续多次查询失败需诚实反馈无法完成的原因。";

    logger.info("调用 Supervisor Agent 开始编排...");
    return supervisorAgent.invoke(taskPrompt);
}
```

这个方法就是启动一次完整 AI 运维分析流程。

它做了 4 件事：

1. 创建 Planner Agent。
2. 创建 Executor Agent。
3. 创建 Supervisor Agent。
4. 调用 `supervisorAgent.invoke(taskPrompt)`，真正启动多 Agent 协作。

返回值是：

```java
Optional<OverAllState>
```

可以理解成：Agent 执行完以后留下来的整体状态对象，里面可能包含 Planner 的结果、Executor 的结果等。`Optional` 表示结果可能存在，也可能不存在，比直接返回 `null` 安全一些。

## 5. `extractFinalReport`：从结果里拿最终报告

```java
public Optional<String> extractFinalReport(OverAllState state) {
    logger.info("开始提取最终报告...");

    Optional<AssistantMessage> plannerFinalOutput = state.value("planner_plan")
            .filter(AssistantMessage.class::isInstance)
            .map(AssistantMessage.class::cast);

    if (plannerFinalOutput.isPresent()) {
        String reportText = plannerFinalOutput.get().getText();
        logger.info("成功提取到 Planner 最终报告，长度: {}", reportText.length());
        return Optional.of(reportText);
    } else {
        logger.warn("未能提取到 Planner 最终报告");
        return Optional.empty();
    }
}
```

这个方法做的是：从 Agent 执行结果里取出最终报告文本。

关键是：

```java
state.value("planner_plan")
```

因为创建 Planner Agent 时指定了：

```java
.outputKey("planner_plan")
```

意思是：Planner 的输出会保存到状态里的 `planner_plan` 这个 key 下。

流程：

1. 从 `state` 里取 `planner_plan`；
2. 判断它是不是 `AssistantMessage`；
3. 如果是，就拿它的 `.getText()`；
4. 返回最终 Markdown 报告；
5. 如果取不到，就返回 `Optional.empty()`。

## 6. `buildPlannerAgent`：创建规划 Agent

```java
private ReactAgent buildPlannerAgent(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
    return ReactAgent.builder()
            .name("planner_agent")
            .description("负责拆解告警、规划与再规划步骤")
            .model(chatModel)
            .systemPrompt(buildPlannerPrompt())
            .methodTools(buildMethodToolsArray())
            .tools(toolCallbacks)
            .outputKey("planner_plan")
            .build();
}
```

它的意思是：

```text
创建一个叫 planner_agent 的智能体
它使用 chatModel 这个大模型
它的系统提示词是 buildPlannerPrompt()
它可以调用本地工具 methodTools
它也可以调用 MCP 工具 toolCallbacks
它的输出保存到 planner_plan
```

Planner 的职责是：想清楚下一步做什么，并最终写报告。

## 7. `buildExecutorAgent`：创建执行 Agent

```java
private ReactAgent buildExecutorAgent(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
    return ReactAgent.builder()
            .name("executor_agent")
            .description("负责执行 Planner 的首个步骤并及时反馈")
            .model(chatModel)
            .systemPrompt(buildExecutorPrompt())
            .methodTools(buildMethodToolsArray())
            .tools(toolCallbacks)
            .outputKey("executor_feedback")
            .build();
}
```

Executor 是“执行者”。

例如 Planner 说：“去查最近 1 小时 CPU 使用率。”Executor 就负责真正调用工具查数据，然后把结果写成反馈。

它的输出 key 是：

```java
.outputKey("executor_feedback")
```

所以 Executor 的结果会放到状态里的 `executor_feedback` 里面。

## 8. `buildMethodToolsArray`：决定本地工具列表

```java
private Object[] buildMethodToolsArray() {
    if (queryLogsTools != null) {
        return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
    } else {
        return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
    }
}
```

这个方法返回一个工具数组。

如果 `queryLogsTools != null`，说明本地 mock 日志工具存在，于是工具包括：

```text
时间工具
内部文档工具
Prometheus 指标工具
日志工具
```

如果 `queryLogsTools == null`，说明真实环境日志查询交给 MCP，那么本地方法工具里就不放 `QueryLogsTools`。

## 9. `buildPlannerPrompt`：Planner 的工作说明书

这个方法返回一大段字符串，告诉 Planner：

- 你要读取当前任务；
- 你要看 Executor 反馈；
- 你要决定下一步；
- 你的输出要包含 `decision`；
- 不能编造数据；
- 最终报告必须按固定 Markdown 模板输出。

最关键的是这个 `decision`：

| decision | 含义 |
|----------|------|
| `PLAN` | 继续规划 |
| `EXECUTE` | 让 Executor 去执行一步 |
| `FINISH` | 结束并输出最终报告 |

也就是说 Planner 不只是一次性回答，它会参与循环：

```text
规划 → 执行 → 看反馈 → 再规划 → 最终报告
```

当 Planner 判断 `decision=FINISH` 时，它不能再输出 JSON，而是必须输出一份 Markdown 报告。这样前端可以直接渲染 Markdown。

## 10. `buildExecutorPrompt`：Executor 的工作说明书

Executor 的职责更具体：只执行 Planner 最新计划里的第一步。

比如 Planner 计划是：

```json
{
  "decision": "EXECUTE",
  "step": "查询 ap-guangzhou 区域最近 1 小时错误日志"
}
```

Executor 就应该调用日志工具，查完后返回类似：

```json
{
  "status": "SUCCESS",
  "summary": "近1小时发现 10 条 error 日志",
  "evidence": "...",
  "nextHint": "建议继续检查 CPU 指标"
}
```

它不会自己写最终报告，它只是把证据交给 Planner。

## 11. `buildSupervisorSystemPrompt`：Supervisor 的调度规则

Supervisor 是总控：

- 需要计划时，找 Planner；
- Planner 说要执行时，找 Executor；
- Executor 回来后，再让 Planner 判断下一步；
- 直到 Planner 说 `FINISH`。

所以这个类是一个多 Agent 编排器，不是一个单 Agent 直接回答。

## 12. 一次完整调用会怎么跑？

```text
Controller 调用 AiOpsService.executeAiOpsAnalysis(...)
        ↓
创建 Planner Agent
        ↓
创建 Executor Agent
        ↓
创建 Supervisor Agent
        ↓
Supervisor 收到任务："请执行告警排查并输出报告"
        ↓
Supervisor 调 Planner
        ↓
Planner 决定：先查 Prometheus 告警
        ↓
Supervisor 调 Executor
        ↓
Executor 调 QueryMetricsTools 查询 Prometheus
        ↓
Executor 返回查询结果
        ↓
Supervisor 再调 Planner
        ↓
Planner 决定：再查日志 / 查文档 / 或 FINISH
        ↓
循环若干次
        ↓
Planner 输出 Markdown《告警分析报告》
        ↓
extractFinalReport 从 state 里取出报告
```

## 13. 和其他文件的关系

| 文件 | 作用 |
|------|------|
| `ChatController` | 提供 HTTP 接口，比如 `/chat`、`/chat_stream`、AIOps 相关接口 |
| `ChatService` | 创建普通聊天 Agent，构造普通聊天 system prompt |
| `AiOpsService` | 创建多 Agent 运维分析流程 |
| `QueryMetricsTools` | 查 Prometheus 指标 / 告警 |
| `QueryLogsTools` | mock 日志查询工具 |
| MCP 工具 | 真实腾讯云日志查询等外部工具 |
| `InternalDocsTools` | 查内部知识库 |
| `VectorSearchService` | 查 Milvus 里的相似文档 |

`AiOpsService` 自己不直接查数据库、不直接查 Prometheus。它让 Agent 通过工具去查。

## 14. 记忆版总结

最简单记法：

```text
AiOpsService = 自动运维排查的总编排服务
Planner Agent = 想下一步怎么查
Executor Agent = 真正调用工具查
Supervisor Agent = 控制 Planner 和 Executor 轮流工作
extractFinalReport = 从最终状态里拿 Markdown 报告
```

它的核心价值不是某一段 Java 算法，而是把“大模型 + 工具 + 多 Agent 协作 + 固定报告模板”组织成一个完整运维排查流程。
