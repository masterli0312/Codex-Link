# Codex Cloud（Codex Link 1.0.2，实验性）

> 本版本包含原生接入，但完整新建、发布与普通云对话续聊尚未完成全流程实机验收。以下是界面提供的流程，不代表所有步骤已验证可用。

## 普通用户的流程

1. 在「对话 → Cloud」选择真实 ChatGPT 账号，进入「管理云环境」。
2. 打开已有环境；或创建环境并选择 GitHub 项目，名称会按项目自动填写。
3. 点击「自动准备环境」（新环境为「创建并准备」），确认模型和思考强度，再点「确认并开始」。模型目录和可用强度来自当前账号的官方 Cloud，选择按账号和 workspace 加密保存。Codex 检查项目、安装依赖并测试；需要补充信息时，用自然语言回复准备对话。
4. 查看结果，完成后点击「发布环境」。只有发布操作成功、完成确认且重新读取的版本一致，才显示已发布。
5. 点击「使用此环境」，进入云任务输入页。

默认不用填写脚本。安装脚本、启动指令、网络策略收进折叠的「高级设置」。GitHub 是项目代码来源，执行环境由 OpenAI 提供；无仓库环境未验证，因此未提供空白环境。首次 GitHub 授权仍需在官方界面确认，App 不保存 GitHub Token。

管理目录不定时反复刷新；准备任务执行期间静默读取进展，任务结束后停止自动查询。首次加载和手动刷新才显示加载反馈，后台查询不禁用已加载配置的操作，不清空输入中的回复。

环境选择器显示所有官方返回的环境；未发布环境标明状态，点击继续原生配置，不把草稿当作可运行环境。准备对话和普通 Cloud 对话使用固定在底部的输入框；执行中可补充要求（`turn/steer`，校验当前轮次），结束后可继续追问（`turn/start`，显式选择模型和强度）。刚发送的轮次尚未进入 HTTP 历史时继续查询，不把上一轮已完成状态误认为新回复已结束。

电脑对话列表点击输入框后进入专注输入布局，收起最近对话列表，在键盘上方显示环境与输入框；App 不再单独提供「项目」选项。

## 新版接入

| 用途 | 固定官方服务 |
| --- | --- |
| 环境配置、草稿、发布操作、线程及历史 | https://codex-cloud-backend.chatgpt.com |
| Cloud app-server | wss://codex-cloud-backend.chatgpt.com/ |
| 官方账号已授权的仓库目录 | https://chatgpt.com/backend-api/wham/github/list-repositories |

- 环境目录读取 user / workspace 两个 scope，分页并按配置 ID 去重。
- 原生配置创建、准备对话、草稿保存及发布使用新版协议。
- 保存使用 `base_version_id` 与 `expected_revision`，保留秘密引用及其他无关字段。
- 发布执行 begin → operation SUCCEEDED → complete → 重新读取并核对版本。已接受不代表已发布。
- 已发布配置用于 Cloud `thread/start`，随后提交 `turn/start`；配置、版本、草稿、运行环境、线程 ID 分开保存。
- 旧 `CloudApi` / `CloudWire` 保留为旧协议参考，不再作为新版 Cloud 的运行入口。不能把新版配置 ID 塞进 `wham/tasks`。
- 初次读取近期轮次，支持「加载更早消息」。较早历史随实时更新保留并去重；手机缓存上限为 300 条较早消息或约 192 KiB 文本，达到上限后停止继续翻页，不声称任意长对话均完整保留。
- Cloud 对话提供重命名、归档和恢复，列表可切换「已归档」。写操作等待官方确认后更新本地状态，不盲目重试未知结果。

## 安全与恢复

手机直接访问官方服务，OpenAI 凭据不经过配对电脑或自建通知中继。HTTP 使用所选账号 OAuth Bearer 与 `ChatGPT-Account-ID`；Cloud WebSocket 的 Bearer 子协议同样属于敏感请求头，禁止日志和持久化。

额度与 Cloud 共用每账号 Token 续期锁。读取遇到 401 最多续期重试一次，账号/workspace 不符即停止。HTTP 禁止重定向和自动重试写请求；HTML 403 与 JSON 权限拒绝分开处理。

缓存、草稿和操作记录按 `CloudIdentity(accountId, workspaceId)` 隔离，以 Android Keystore 加密保存到 `noBackupFilesDir`。删除账号或清空数据会清除对应 Cloud 文件。创建/准备/发布结果不确定时保留已确认 ID 和进度，不自动重放写请求，不误报成功。

## 实际验证

- 桌面官方 OAuth 读取新版环境目录、配置和已授权仓库目录 HTTP 200，找到已有「Codex-Usage」配置。
- Cloud WebSocket 握手 101，initialize 与 model/list 成功，只读验证没有创建环境或任务。
- 手机已安装简化原生配置包，真实读取到测试账号的配置和原有准备对话。管理页面连续观察没有反复加载，脚本默认隐藏。
- 最新续聊修复版构建成功，Cloud 相关 34 项单测通过，lint 通过。涵盖执行中追加输入、完成后追问、未知结果不重放、旧历史不会停止新轮次查询、后台更新不清空输入。
- 手机真实自动准备请求已被官方接受；独立 HTTP 历史确认最新准备轮次已完成，官方回复报告工具链安装和验证完成。独立配置读取确认安装脚本和启动指令草稿已保存；发布尚未验证，不据准备完成宣称环境已发布。
- 最新续聊修复 APK 已于 2026-10-04 12:58 覆盖安装，手机 base.apk 的 SHA256 与构建包一致。布局和真实续聊的完整实机验收未完成。
- 本轮未完成真实创建、草稿 PATCH、发布和普通云任务提交的完整验证。模拟测试或页面打开不能代替真实环境已可使用的证据。
- 现有配置为第 1 版；目录存在不等于发布完成。

Cloud 本身不依赖配对电脑开机，手机读取/发送仍需能访问 OpenAI 的网络。准备环境已加入模型/思考强度确认，普通任务也已接入模型与强度选择。Cloud 后台通知、附件、秘密/变量的原生编辑、VPN 及本地原对话无损迁移尚未接入。历史分页与归档/恢复已实现并有回归检查，仍不代表完整 Cloud 流程均通过真机验收。

## 依据

- [官方 Cloud 概览](https://learn.chatgpt.com/docs/cloud)
- [官方环境流程](https://learn.chatgpt.com/docs/environments/cloud-environments)
- [新版接口调查](https://github.com/dvcrn/mcp-server-codex-cloud/blob/851921583ed1e82b8ad597ed40c009954beb54cf/CODEX_CLOUD_NEW.md)
- [环境 API 参考](https://github.com/dvcrn/mcp-server-codex-cloud/blob/851921583ed1e82b8ad597ed40c009954beb54cf/src/environments.ts)
- [线程与准备参考](https://github.com/dvcrn/mcp-server-codex-cloud/blob/851921583ed1e82b8ad597ed40c009954beb54cf/src/tasks.ts)

请求结构同时对照了当前安装的官方桌面客户端只读资源；没有修改官方程序。该后端不是公开稳定的第三方 API 合约，服务端改变时需要重新核对。
