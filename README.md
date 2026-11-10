# 小小书 AI 助手（xiaoxiaoshu-ai）

基于 **Spring AI 2.0** 的智能对话平台，包含 AI 通用助手与 AI 智能客服两种角色，支持 SSE 流式输出、思维链展示、多轮会话记忆、工具调用、MCP 接入、RAG 知识库检索、联网搜索、图片上传与大文件分片续传。

> 本项目为学习实践用途，参考[《从零手撸：Spring AI 2.0 项目》](https://www.quanxiaoha.com/column/10617.html)专栏的功能设计独立实现。

---

## 技术栈

| 分类 | 选型 |
| --- | --- |
| 基础框架 | Spring Boot 4.1.2、JDK 21（启用虚拟线程） |
| AI 框架 | Spring AI 2.0.2 |
| 大模型 | DeepSeek（默认）、OpenAI 兼容端点、Ollama 本地模型，三者可切换 |
| 协议 | SSE（`SseEmitter`）流式推送、MCP（streamable-http） |
| 持久层 | Spring JDBC + MySQL 8（会话与消息落库） |
| 对象存储 | MinIO |
| 前端 | Vue 3 + Vite 5 + TailwindCSS 3 + Ant Design Vue 4 + marked / dompurify |

---

## 项目结构

```
xiaoxiaoshu-ai
├── src/main/java/com/quanxiaoha/xiaoxiaoshu/ai
│   ├── chat/        SSE 流式对话、会话管理、消息持久化
│   ├── config/      ChatModel / ChatMemory / 多模型适配配置
│   ├── tool/        工具调用（Tool Calling）实现
│   ├── mcp/         MCP 客户端注册与懒加载、失败冷却
│   ├── rag/         知识库加载、切分、检索与引用
│   ├── prompt/      提示词模板（角色 / few-shot / CoT / 格式化）
│   └── storage/     MinIO 存储、图片上传、大文件分片与断点续传
├── src/main/resources
│   ├── application.yml
│   ├── schema.sql        建表脚本（启动时自动执行）
│   └── knowledge/*.md    RAG 语料
├── mcp-server/           独立 MCP Server 子项目（:7081）
└── web/                  Vue 3 前端
```

---

## 功能清单

| 功能 | 说明 |
| --- | --- |
| SSE 流式对话 | `POST /api/chat/stream`，事件类型 `meta` / `delta` / `reasoning` / `done` / `error`，支持展示模型思维链 |
| 多轮会话记忆 | `MessageWindowChatMemory` 维护上下文窗口；会话与消息通过 `ChatRepository` 落库 MySQL，重启后回灌历史 |
| 双角色切换 | `mode=assistant`（通用助手）/ `mode=service`（智能客服），分别加载不同系统提示词 |
| 工具调用 | 5 个内置工具：`getCurrentTime`、`calculate`（自研递归下降表达式求值）、`searchKnowledge`、`conversationStats`（使用 `ToolContext`）、`webSearch` |
| MCP 接入 | 内置 `mcp-server` 子项目暴露 `getQQInfo` / `getQQAvatar`；主应用 `McpToolRegistry` 实现按需懒加载与失败冷却，并预留高德地图 MCP 配置 |
| RAG 检索 | `KnowledgeBase` 加载 `resources/knowledge/*.md`，按段落切分，中文 bigram 关键词检索 + 同义词扩展 + 重排，返回带出处的引用 |
| 联网搜索 | 抓取必应搜索结果页并解析，作为 `webSearch` 工具的数据源 |
| 提示词工程 | 模板变量、few-shot、CoT、角色设定、输出格式化等多类提示词演示接口 |
| 多模型 | DeepSeek / OpenAI 兼容 / Ollama 三套配置，`CustomOpenAiChatModel` 手写 REST 调用并支持流式 |
| 图片上传 | 上传至 MinIO，提取文件名、尺寸、色调等信息注入提示词 |
| 大文件上传 | 分片上传 `POST /api/files/chunk` + 合并 `POST /api/files/merge` + 断点续传 `GET /api/files/chunks/{uploadId}` |
| 演示模式 | 未配置 API Key 时自动进入本地规则回复模式，便于无密钥情况下跑通链路 |

### 与教程方案的差异（如实说明）

- **RAG**：当前为纯内存的中文关键词检索，未接入向量数据库与 `EmbeddingModel`。这样无需额外的向量库和 Embedding 密钥即可运行，但语义召回能力弱于向量检索。
- **多模态**：图片未走视觉模型，而是把图片的元信息（文件名 / 尺寸 / 主色调）转成文本注入提示词。
- **联网搜索**：直接解析搜索结果页 HTML，未接入 SearXNG 等自建搜索服务，稳定性依赖页面结构。
- **助手与客服**：共用同一套 `ChatService`，通过 `mode` 切换系统提示词，而非两套独立服务。
- **大文件上传**：已实现分片与断点续传，未实现基于文件哈希的「秒传」；前端目前仍走单文件上传接口。
- **会话记忆**：使用内存版 `MessageWindowChatMemory` + 自建 MySQL 持久化，未使用 `JdbcChatMemoryRepository`。

---

## REST 接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/chat/stream` | SSE 流式对话 |
| GET / POST | `/api/conversations` | 会话列表 / 新建会话 |
| GET | `/api/conversations/{id}/messages` | 拉取会话历史消息 |
| DELETE | `/api/conversations/{id}` | 删除会话 |
| GET | `/api/status` | 运行状态（是否演示模式、已注册工具与 MCP） |
| POST | `/api/files/images` | 图片上传 |
| POST | `/api/files/chunk` | 大文件分片上传 |
| POST | `/api/files/merge` | 分片合并 |
| POST | `/api/files/uploadId` | 申请上传 ID |
| GET | `/api/files/chunks/{uploadId}` | 查询已上传分片（断点续传） |
| GET | `/api/files/**` | 文件下载 |
| GET | `/api/prompt/*` | 各类提示词模板演示 |
| GET | `/api/models` | 可用模型列表 |

---

## 中间件依赖

| 中间件 | 端口 | 用途 |
| --- | --- | --- |
| MySQL | 3306 | 库名 `xiaoxiaoshu_ai`，`schema.sql` 启动时自动建表 |
| MinIO | 9000 | 图片与大文件存储，bucket `xiaoxiaoshu-ai` |
| MCP Server | 7081 | 本项目内置子模块，非外部依赖 |

---

## 快速开始

### 1. 配置密钥（重要）

**不要把真实 API Key 写进 `application.yml` 并提交。** 通过环境变量注入：

```bash
# Windows PowerShell
$env:DEEPSEEK_API_KEY = "你的密钥"
$env:OPENAI_API_KEY   = "你的密钥"      # 可选
$env:OPENAI_BASE_URL  = "https://api.openai.com"
$env:MINIO_ACCESS_KEY = "minioadmin"
$env:MINIO_SECRET_KEY = "minioadmin"

# Linux / macOS
export DEEPSEEK_API_KEY=你的密钥
```

`application.yml` 中所有敏感项均已改为 `${ENV_VAR:默认值}` 形式，未配置密钥时应用会以演示模式启动。

同时按需修改 `spring.datasource` 的 MySQL 账号密码（默认 `root` / `123456`）。

### 2. 启动 MCP Server（可选）

```bash
cd mcp-server
mvn spring-boot:run
```

服务监听 `http://127.0.0.1:7081/mcp`。不启动也不影响主应用，MCP 工具会进入冷却并自动降级。

### 3. 启动后端

```bash
mvn spring-boot:run
# 或
mvn clean package -DskipTests && java -jar target/*.jar
```

后端监听 `http://localhost:7080`。

### 4. 启动前端

```bash
cd web
npm install
npm run dev        # 开发模式
npm run build      # 构建，产物输出到 src/main/resources/static/ui
```

构建后可直接访问 `http://localhost:7080/ui` 使用打包进后端的页面。

---

## 说明

- 仓库已通过 `.gitignore` 排除构建产物（`target/`、`static/ui/`）、运行日志、前端依赖（`node_modules/`）、本地数据库文件（`data/*.db`）与调试临时文件。
- 前端构建产物不入库，克隆后需执行 `npm run build` 生成。
