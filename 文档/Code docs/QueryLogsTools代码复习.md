# QueryLogsTools 代码复习

`QueryLogsTools.java` 是一个 **给 AI Agent 调用的日志查询工具类**。

它的目标是：让大模型在做 AIOps 告警分析时，可以像调用函数一样查询日志。

不过注意：当前这个类主要实现的是 **Mock 模式**，也就是返回模拟日志；真实 CLS（腾讯云日志服务）查询还没实现。

## 1. 它在系统里的位置

这个类在：

```text
org.example.agent.tool.QueryLogsTools
```

开头有注解：

```java
@Component
public class QueryLogsTools {
```

意思是：Spring Boot 会把它注册成一个 Bean。

里面有两个方法加了 `@Tool`：

```java
@Tool(...)
public String getAvailableLogTopics()
```

和：

```java
@Tool(...)
public String queryLogs(...)
```

这表示它们可以被 Spring AI / Agent 当作工具调用。

可以理解成：

```text
大模型：我需要查日志
        ↓
Agent 调用 queryLogs(...)
        ↓
QueryLogsTools 返回 JSON 字符串
        ↓
大模型根据日志结果继续分析
```

## 2. 它暴露了两个工具

### 工具 1：`getAvailableLogTopics()`

作用：**告诉 Agent 有哪些日志主题可以查**。

它返回 4 类日志主题：

| 日志主题 | 用途 |
|----------|------|
| `system-metrics` | 系统指标日志，比如 CPU、内存、磁盘 |
| `application-logs` | 应用日志，比如 ERROR、慢请求、依赖超时 |
| `database-slow-query` | 数据库慢查询日志 |
| `system-events` | 系统事件，比如 Pod 重启、OOMKilled、容器崩溃 |

它还会给每个主题配一些示例查询，例如：

```text
cpu_usage:>80
level:ERROR
response_time:>3000
restart OR crash
```

所以这个方法像是 **日志工具的说明书**。

Agent 理论上应该先调用它，知道有哪些日志主题，再调用 `queryLogs` 真正查日志。

### 工具 2：`queryLogs(region, logTopic, query, limit)`

作用：**根据日志主题和查询条件返回日志**。

参数含义：

| 参数 | 说明 |
|------|------|
| `region` | 地域，例如 `ap-guangzhou` |
| `logTopic` | 日志主题，例如 `system-metrics`、`application-logs` |
| `query` | 查询条件，例如 `level:ERROR`、`cpu_usage:>80` |
| `limit` | 返回多少条，默认 20，最多 100 |

核心逻辑是：

```java
if (mockEnabled) {
    logEntries = buildMockLogs(region, logTopic, safeQuery, actualLimit);
} else {
    return buildErrorResponse("CLS 真实查询尚未实现，请启用 mock 模式进行测试");
}
```

也就是说：

- 如果配置 `cls.mock-enabled=true`：返回模拟日志；
- 如果是 `false`：直接返回错误，提示真实 CLS 查询还没实现。

## 3. Mock 日志是怎么生成的？

核心方法是：

```java
private List<LogEntry> buildMockLogs(String region, String logTopic, String query, int limit)
```

它会根据 `logTopic` 分流：

```java
switch (safeTopic) {
    case "system-metrics":
        logs.addAll(buildSystemMetricsLogs(...));
        break;
    case "application-logs":
        logs.addAll(buildApplicationLogs(...));
        break;
    case "database-slow-query":
        logs.addAll(buildDatabaseSlowQueryLogs(...));
        break;
    case "system-events":
        logs.addAll(buildSystemEventsLogs(...));
        break;
    default:
        logs.addAll(buildGenericLogs(...));
}
```

也就是：

| `logTopic` | 调用的方法 |
|-----------|------------|
| `system-metrics` | `buildSystemMetricsLogs` |
| `application-logs` | `buildApplicationLogs` |
| `database-slow-query` | `buildDatabaseSlowQueryLogs` |
| `system-events` | `buildSystemEventsLogs` |
| 其他 | `buildGenericLogs` |

## 4. 各类 Mock 日志内容

### `buildSystemMetricsLogs`

模拟系统资源类问题：

- CPU 使用率过高；
- 内存使用率过高；
- Full GC 频繁；
- 磁盘空间不足。

例如查询里包含：

```text
cpu
>80
memory
oom
disk
filesystem
```

就会返回对应类型的模拟日志。

### `buildApplicationLogs`

模拟应用层问题：

- 数据库连接池耗尽；
- `OutOfMemoryError`；
- HTTP 500；
- 慢请求；
- Redis 超时；
- MQ 积压。

例如查询里包含：

```text
error
fatal
500
slow
response_time
redis
database
mq
```

就会返回相关日志。

### `buildDatabaseSlowQueryLogs`

模拟数据库慢 SQL：

- `orders` 表慢查询；
- `users` 表全表扫描；
- `UPDATE orders` 锁等待。

这些日志用于解释 **慢响应告警** 或 **服务不可用** 可能和数据库有关。

### `buildSystemEventsLogs`

模拟系统事件：

- Pod 重启；
- OOMKilled；
- OOM Killer 杀进程。

通常用于解释：

```text
服务不可用
内存过高
容器重启
```

这类告警。

### `buildGenericLogs`

如果主题不认识，或者没有匹配日志，就生成一些通用日志：

```text
日志消息 #0
日志消息 #1
...
```

这更像兜底数据。

## 5. 返回的数据结构

它最终返回的是 JSON 字符串，不是 Java 对象。

单条日志结构是 `LogEntry`：

```java
public static class LogEntry {
    private String timestamp;
    private String level;
    private String service;
    private String instance;
    private String message;
    private Map<String, String> metrics;
}
```

也就是每条日志包含：

| 字段 | 说明 |
|------|------|
| `timestamp` | 时间 |
| `level` | 日志级别，如 `INFO` / `WARN` / `ERROR` |
| `service` | 服务名 |
| `instance` | 实例 / Pod / 节点 |
| `message` | 日志正文 |
| `metrics` | 附带指标字段，比如 CPU、内存、SQL 耗时等 |

查询结果结构是 `QueryLogsOutput`：

```java
public static class QueryLogsOutput {
    private boolean success;
    private String region;
    private String logTopic;
    private String query;
    private List<LogEntry> logs;
    private int total;
    private String message;
}
```

## 6. 它和 AIOps 的关系

在 `AiOpsService` 里有：

```java
@Autowired(required = false)
private QueryLogsTools queryLogsTools;
```

如果 `QueryLogsTools` 被注入，它会加入 Agent 的本地工具列表。

这样 Planner / Executor 在做告警分析时，就可以调用它查日志。

例如：

```text
告警：HighMemoryUsage
Planner：需要查内存和 OOM 日志
Executor：调用 queryLogs(logTopic="system-events", query="oom_kill")
QueryLogsTools：返回 OOMKilled 模拟日志
Planner：根据日志写根因分析
```

## 7. 当前实现的关键限制

最重要的一点：**真实 CLS 查询还没有实现**。

代码里写的是：

```java
return buildErrorResponse("CLS 真实查询尚未实现，请启用 mock 模式进行测试");
```

所以如果你的配置是：

```yaml
cls:
  mock-enabled: false
```

这个工具会直接返回错误。

但项目里真实日志查询主要可能是走 **腾讯云 MCP**，所以 `QueryLogsTools` 更像是：

- 本地 mock 测试工具；
- 没接 MCP 时的演示工具；
- 给 Agent 模拟日志证据用的工具。

## 8. 一句话总结

`QueryLogsTools.java` 是一个 **供 AI Agent 调用的日志查询工具**：

它告诉 Agent 有哪些日志主题，并根据主题和查询条件返回日志结果；但当前主要是 **Mock 日志生成器**，用于模拟 CPU、内存、应用错误、慢 SQL、Pod 重启等运维场景，帮助 AIOps 流程生成告警分析报告。
