# 子代理、后台任务与 opencodex 概览：远程协议（1.0.9）

手机与电脑桥接沿用现有加密命令/事件通道（`RemoteCommand` / `RemoteEvent`，`schema_version 2.0`）。
本文件是手机端（Kotlin）与电脑端（remote-core.cjs）共同遵守的约定。

## 隔离要求（必须）

- 不连接、不读取任何 Claude / Anthropic 服务、目录或数据（如 `~/.claude`）。
- opencodex 概览中排除 provider 为 `claude` 或 `anthropic` 的账号与请求。
  注意：模型 ID 里出现 "claude"（如 `google-antigravity/claude-opus-4-6-thinking`）是 Antigravity 模型，不按此排除；只按 provider 排除。
- 任务清单只读取白名单字段（见下），其他字段一律忽略、不转发。不读取 `prompt_file`、`log_file` 的内容。

## 能力声明

`presence` 事件新增 `capabilities: string[]`。1.0.9 电脑组件返回
`["agents","tasks","ocx_overview"]`（未配置任务目录时不含 `"tasks"`；`ocx` 不可用时不含 `"ocx_overview"`）。
手机端没有看到对应能力时，显示“需要更新电脑组件”，不发送对应命令。

## 电脑级命令约定

与 `presence` 相同：`thread_id = host_id`，`conversation_id = sha256hex(host_id)`，`baseline_turn = action`。
事件的 `request_id = command.id`，`thread_id`、`conversation_id` 与命令一致。出错时事件 `status` 照常、`error` 填错误码。
每个事件序列化后不超过 196,608 字节；列表超限时截断并设 `partial=true`。

### 1. `agents`：列出某个主对话的子代理

- 命令：`read_thread_id = <主对话线程 ID>`。
- 事件：`status="agents"`，`agents: RemoteAgent[]`（最多 20 个，按开始时间倒序；含嵌套子代理，用 `depth` 和 `parent_thread_id` 表示层级）。
- 数据来源：共享模式优先 `thread/list`（`sourceKinds:["subAgentThreadSpawn"]`）按 `parentThreadId` 过滤，可递归一层；
  否则读取 `codexHome/sessions` 下 rollout 的首行 `session_meta`（`source.subagent.thread_spawn.parent_thread_id`），按 path+mtime 缓存。
- 错误码：`INVALID_THREAD`、`AGENTS_UNAVAILABLE`。

```
RemoteAgent {
  thread_id, parent_thread_id, depth:int,
  nickname, role,             // 可能为空
  model, effort,              // 取最近一次 turn_context；可能为空
  status: "running"|"completed"|"interrupted"|"errored"|"idle"|"unknown",
  turn_id,                    // 运行中时为活动轮次 ID，否则为空
  started_at:ms, updated_at:ms,
  last_reply                  // 最后一条助手回复，最多 400 字符
}
```

### 2. `agent_read`：只读查看子代理或后台任务的对话

- 命令：`mode="subagent"` 时 `read_thread_id=<子代理线程 ID>`；`mode="task"` 时 `target_id=<任务 ID 或 exec 会话 ID>`。
- 校验：子代理必须是 `source.subagent` 线程，且其祖先链（≤4 层）里有普通线程；任务必须出现在任务列表来源中（清单或 exec 会话）。
  rollout 路径必须 realpath 位于 `codexHome/sessions` 下。
- 事件：`status="snapshot"`，`snapshot`（与现有 `TaskConversationSnapshot` 相同结构，含 `messages`、`activities`），可设 `partial`。
- 错误码：`INVALID_THREAD`、`TASK_NOT_FOUND`、`ROLLOUT_UNAVAILABLE`。

### 3. `agent_stop`：停止子代理当前一轮

- 命令：`read_thread_id=<子代理线程 ID>`，`expected_turn_id=<手机看到的活动轮次>`。
- 仅共享模式：用共享 app-server `turn/interrupt { threadId, turnId }`。必须先确认该线程是子代理、当前活动轮次等于 `expected_turn_id`。
- 事件：`status="agent_stop"`，`reply` 为 `"stopped"` 或 `"already_finished"`；错误码 `SHARED_REQUIRED`、`STALE_ACTIVE_TURN`、`INVALID_THREAD`、`STOP_FAILED`。
- 不得中断主对话，不得重启或结束共享服务。

### 4. `tasks`：后台任务（codex exec 会话）

- 事件：`status="tasks"`，`tasks: RemoteBackgroundTask[]`（最多 50 个；运行中在前，其余按开始时间倒序；默认最近 7 天）。
- 来源 A：`connection.json` 的 `externalTasksDir` 指向的目录（未配置则不提供此能力）中的 `<id>.json`。
  白名单字段：`id,title,model,effort,worktree,branch,codex_session_id,rollout,resumed,status,started_at,ended_at,process_started_at,exit_code,pid,launcher_pid`。
  同一个 `codex_session_id` 的多次运行（resume）合并为一个任务，`runs` 记次数，状态取最新一次。
- 来源 B：`codexHome/sessions` 最近 7 天内首行 `session_meta` 满足 `originator="codex_exec"`、`source="exec"`、无父线程的会话，且不在来源 A 中。
  标题取第一条用户消息前 120 字符。`can_stop=false`。
- rollout 只做尾部增量读取（每个任务记住偏移，最多读末尾 4 MB），提取：最近一轮 `turn_context` 的模型与思考强度、`task_complete`、最后一条助手回复、最近步骤。

```
RemoteBackgroundTask {
  id, session_id, title,
  model, effort, provider_kind: "openai"|"antigravity"|"other",
  worktree_name,              // 只给目录名，不给完整路径
  branch,
  status: "starting"|"running"|"succeeded"|"failed"|"stopped"|"unknown",
  started_at:ms, ended_at:ms(0 表示未结束), runs:int, resumed:bool,
  can_stop:bool,              // 有清单且状态为 starting/running
  last_reply,                 // ≤400 字符
  last_step,                  // ≤200 字符，如“执行：npm test”“修改 3 个文件”
  tokens:long                 // 最近 token_count 的累计值，没有则 0
}
```

### 5. `task_stop`：停止后台任务

- 命令：`target_id=<任务 ID>`。
- 步骤：
  1. 在任务目录写入 `<id>.stop`（`wx`，已存在视为成功）。等待清单 `status` 变为 `stopped`/`failed`/`succeeded`，最多 15 秒。
  2. 若 `launcher_pid` 已不存在（派发脚本没了）且清单仍为 running：核验 `pid` 进程存在、其启动时间与 `process_started_at` 相差 ≤5 秒、
     其进程树中含命令行包含 `codex` 与 `exec` 的进程，且该进程工作目录或命令行与清单 `worktree` 对应；全部通过才 `taskkill /T /F /PID <pid>`，
     然后原子改写清单 `status="stopped"`、`ended_at`、`exit_code=-1`。任一核验失败则不结束任何进程。
- 事件：`status="task_stop"`，`reply` 为 `"stopped"` 或 `"already_finished"`；错误码 `TASK_NOT_FOUND`、`STOP_UNVERIFIED`、`STOP_TIMEOUT`、`TASKS_DISABLED`。

### 6. `ocx_overview`：opencodex 概览（只读）

- 事件：`status="ocx_overview"`，`overview: RemoteOcxOverview`。
- 来源：`~/.opencodex/codex-quota-cache.json`、`ocx account list` 与 `ocx logs history --json`（最近 200 条）。命令超时 8 秒；失败时 `error="OCX_UNAVAILABLE"`。
  只调用只读命令，绝不调用会修改配置的命令。

```
RemoteOcxOverview {
  accounts: [{ label, plan, selected:bool, short_left_percent:int, weekly_left_percent:int, short_reset_at:ms, weekly_reset_at:ms }],
  requests: [{ model, provider, inbound: "responses"|"messages"|"chat"|"other", count:int, failures:int, tokens:long, last_at:ms, last_error }],
  window_minutes:int
}
```

`label` 用 opencodex 的掩码标签（如 `pf88341`）或账号 ID 后 6 位，不输出邮箱、密钥。
`inbound="messages"` 表示外部客户端请求，手机端显示为“外部客户端请求”，不出现 Claude 字样。

## RemoteEvent 新增字段

`capabilities: string[]`、`agents: RemoteAgent[]`、`tasks: RemoteBackgroundTask[]`、`overview: RemoteOcxOverview?`。
新增 `status` 值：`agents`、`tasks`、`agent_stop`、`task_stop`、`ocx_overview`（`agent_read` 复用 `snapshot`）。
