# ChatController aiOps 方法复习

`ChatController` 里的 `aiOps()` 方法是 **AI 智能运维分析接口**。

它对应的 HTTP 地址是：

```java
@PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
public SseEmitter aiOps()
```

因为 `ChatController` 类上还有：

```java
@RequestMapping("/api")
```

所以完整接口是：

```text
POST /api/ai_ops
```

它做的事情是：前端一点“智能运维分析”，后端自动启动多 Agent 告警分析流程，然后把分析进度和最终报告通过 SSE 流式推给前端。

## 1. 和 `/chat` 的区别

普通 `/chat` 是用户问一句，AI 回一句。

而 `/api/ai_ops` 不需要用户传具体问题，它内部直接启动一套固定任务：

```text
读取告警 → 拆解任务 → 查指标 / 查日志 / 查文档 → 分析根因 → 输出告警分析报告
```

它更像一个“自动运维巡检 / 排障按钮”。

## 2. 为什么返回 `SseEmitter`？

```java
public SseEmitter aiOps() {
    SseEmitter emitter = new SseEmitter(600000L);
```

`SseEmitter` 是 Spring 用来做 SSE 流式返回的对象。

简单理解：

- 普通接口：一次请求，一次返回完整 JSON。
- SSE 接口：请求不断开，后端可以一点点往前端发消息。

这里设置了：

```java
600000L
```

也就是 **10 分钟超时**。因为告警分析可能会查日志、查指标、跑多轮 Agent，耗时较长。

## 3. 为什么用 `executor.execute(...)`？

```java
executor.execute(() -> {
    try {
        ...
    } catch (Exception e) {
        ...
    }
});
```

这表示：把真正耗时的 AI 运维分析放到线程池里异步执行。

原因是：

- AI 分析可能很慢；
- 如果直接在 HTTP 请求线程里跑，容易阻塞；
- 用线程池后，方法可以先返回 `emitter`，后续慢慢往前端推送消息。

可以理解成：

```text
主线程：把 SSE 通道先建立好
后台线程：慢慢执行 AI 分析，并不断通过 emitter 发送结果
```

## 4. 第一步：创建 DashScope 大模型

```java
DashScopeApi dashScopeApi = chatService.createDashScopeApi();
DashScopeChatModel chatModel = DashScopeChatModel.builder()
        .dashScopeApi(dashScopeApi)
        .defaultOptions(DashScopeChatOptions.builder()
                .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                .withTemperature(0.3)
                .withMaxToken(8000)
                .withTopP(0.9)
                .build())
        .build();
```

这段是在创建一个阿里 DashScope 聊天模型。

几个参数含义：

| 参数 | 含义 |
|------|------|
| `temperature(0.3)` | 随机性较低，适合运维分析这种需要稳定、严谨的场景 |
| `maxToken(8000)` | 最多输出较长内容，因为最终报告可能比较长 |
| `topP(0.9)` | 控制采样范围，属于模型生成参数 |

这里和普通聊天不同：普通聊天可能更自由；AIOps 报告更需要稳定，所以 `temperature` 设置得更低。

## 5. 第二步：拿到 MCP 工具

```java
ToolCallback[] toolCallbacks = tools.getToolCallbacks();
```

这里拿的是 MCP 或 Spring AI 工具回调。

这些工具可能包括：

- 腾讯云日志查询工具；
- 外部 MCP 提供的工具；
- 其他可被 Agent 调用的工具。

后面会传给 `AiOpsService`，让 Planner / Executor Agent 能调用这些工具查真实数据。

## 6. 第三步：先给前端发一条提示

```java
emitter.send(SseEmitter.event()
        .name("message")
        .data(SseMessage.content("正在读取告警并拆解任务...\n")));
```

这行就是给前端发一条流式消息：

```text
正在读取告警并拆解任务...
```

用户看到后知道后端已经开始跑，不是页面卡死。

## 7. 第四步：调用 `AiOpsService` 执行多 Agent 分析

```java
Optional<OverAllState> overAllStateOptional =
        aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks);
```

这是整个方法最核心的一行。

它会进入 `AiOpsService`，在那里创建：

```text
Supervisor Agent
Planner Agent
Executor Agent
```

然后执行：

```text
规划 → 执行 → 再规划 → 最终报告
```

返回的 `OverAllState` 可以理解为 **多 Agent 执行完后的总状态**，里面保存了 Planner 和 Executor 的输出。

为什么是 `Optional<OverAllState>`？

因为流程可能失败，可能拿不到结果，所以用 `Optional` 表示“可能有，也可能没有”。

## 8. 如果没有结果，返回错误

```java
if (overAllStateOptional.isEmpty()) {
    emitter.send(SseEmitter.event().name("message")
            .data(SseMessage.error("多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
    emitter.complete();
    return;
}
```

如果多 Agent 没有返回有效状态，就：

1. 发一条错误消息给前端；
2. `emitter.complete()` 结束 SSE；
3. `return` 停止后续逻辑。

## 9. 第五步：从状态里提取最终报告

```java
OverAllState state = overAllStateOptional.get();
Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);
```

前面 `executeAiOpsAnalysis` 得到的是一个复杂状态对象。

这里调用 `extractFinalReport`，从里面取出最终 Markdown 报告文本。

对应 `AiOpsService` 里是从：

```text
planner_plan
```

这个 key 里取 Planner 最终输出。

## 10. 第六步：如果拿到报告，就分块发送给前端

```java
String finalReportText = finalReportOptional.get();
```

如果报告存在，会先发送一个分隔线：

```java
emitter.send(... "=".repeat(60) ...)
```

再发送标题：

```java
"📋 **告警分析报告**\n\n"
```

然后把报告拆成每 50 个字符一块：

```java
int chunkSize = 50;
for (int i = 0; i < finalReportText.length(); i += chunkSize) {
    int end = Math.min(i + chunkSize, finalReportText.length());
    String chunk = finalReportText.substring(i, end);

    emitter.send(SseEmitter.event().name("message")
            .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
}
```

为什么要拆块？

因为这是流式接口。如果一次性发完整报告，前端可能感觉还是“等了一大段时间才出来”。拆成小块后，前端可以像打字机一样逐渐显示。

## 11. 如果没拿到报告怎么办？

```java
emitter.send(SseEmitter.event().name("message")
        .data(SseMessage.content("⚠️ 多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
```

这表示：Agent 流程跑完了，但没有成功提取到最终报告。

于是给前端发一个提示，而不是直接静默失败。

## 12. 最后发送 done 并关闭连接

```java
emitter.send(SseEmitter.event().name("message")
        .data(SseMessage.done(), MediaType.APPLICATION_JSON));
emitter.complete();
```

这两步很重要：

1. `SseMessage.done()`：告诉前端“内容发完了”。
2. `emitter.complete()`：关闭 SSE 连接。

如果不 complete，前端可能一直以为还在加载。

## 13. 异常处理

```java
catch (Exception e) {
    logger.error("AI Ops 多 Agent 协作失败", e);
    try {
        emitter.send(SseEmitter.event().name("message")
                .data(SseMessage.error("AI Ops 流程失败: " + e.getMessage()), MediaType.APPLICATION_JSON));
    } catch (IOException ex) {
        logger.error("发送错误消息失败", ex);
    }
    emitter.completeWithError(e);
}
```

如果过程中任何地方报错，比如：

- 模型调用失败；
- MCP 工具失败；
- SSE 发送失败；
- 多 Agent 编排异常；

就会：

1. 打错误日志；
2. 给前端发一条错误消息；
3. 用 `completeWithError` 结束连接。

## 14. 一次完整调用流程

```text
前端 POST /api/ai_ops
        ↓
创建 SseEmitter，准备流式返回
        ↓
放到线程池里异步执行
        ↓
创建 DashScope ChatModel
        ↓
获取 MCP 工具 ToolCallback
        ↓
先发一条“正在读取告警并拆解任务...”
        ↓
调用 AiOpsService.executeAiOpsAnalysis()
        ↓
Supervisor / Planner / Executor 多 Agent 协作
        ↓
得到 OverAllState
        ↓
extractFinalReport 提取最终 Markdown 报告
        ↓
把报告每 50 字符一块推给前端
        ↓
发送 done
        ↓
关闭 SSE 连接
```

## 15. 一句话总结

`aiOps()` 方法就是 **“智能运维报告生成接口”**：

它不接收用户问题，而是自动启动 `AiOpsService` 的多 Agent 告警分析流程，查真实工具数据，最后通过 SSE 把《告警分析报告》流式推给前端。
