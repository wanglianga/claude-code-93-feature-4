# 建设银行网点排队叫号与适老服务预约平台

> 技术栈：Vue 3 + Vite（前端） · Spring Boot 3 + Spring Security/JWT + JPA（后端） · PostgreSQL 16（数据库） · Nginx（静态托管/反代） · Docker Compose 一键部署

## 原始需求

建设银行网点排队叫号与适老服务预约平台，可采用 Vue 3、Spring Boot 和 PostgreSQL。客户到网点办理开户、转账、挂失、社保卡、理财咨询或大额现金业务时，平台记录客户类型、预约来源、业务类型、风险等级、是否老人、轮椅或听力引导需求和预计办理时长。大堂经理根据客户材料完整度、柜员技能、现金库存、理财经理排班和窗口负载进行分流。老人预约服务时，平台可安排陪同引导、优先窗口、材料预审和家属远程确认。若客户长时间等待、大额转账触发反诈核验、老人忘带证件、柜员临时离岗、自助机故障或客户投诉插队，平台要把大堂经理、柜员、理财经理、安保、客服和客户放在同一排队记录中处理。办理结束后，等待时长、业务结果、风险提示、投诉和回访进入网点运营档案。平台还要处理现金业务、反诈拦截、无障碍通道、养老金集中发放日和网点突发停电。银行网点排队既涉及服务效率，也涉及合规审核和老人安全。平台还要记录客户是否被引导到正确渠道，比如柜台、自助机、理财室或远程客服，避免所有业务都挤压柜台窗口。

## 本期特性：台位设备离线 / 转台失败四方协同处置

在既有平台之上，本期实现**台位异常处置闭环**：客户已取号、已生成叫号预估后，台位设备离线或转台失败时，系统生成**不可篡改处置单**，以**同一业务单号**驱动**前台、柜台、设备维修、风控**四个角色协同，主管复核收尾。

### 核心规则实现对照

| 需求 | 实现 |
|---|---|
| 不可篡改处置单 | 设备离线/转台失败瞬间快照：客户、队列位置、业务类型、优先级、原台位、已等待时长、预计叫号时间、失败原因；快照字段写入后不再更新，**SHA-256 哈希链**存证（`GET /api/dispositions/verify-chain` 全链校验） |
| 同一业务单号四方协同 | 处置单号 `D<业务单号>-<序号>`；前台处置、柜台叫号、维修闭环、风控告警、主管复核全部挂在同一 `ticketNo` 上，统一查询 `/api/orders/{ticketNo}/trace` |
| 前台三方案 | ①保留原排队权重后迁移 ②转人工窗口（可自动选负载最低人工台位）③客户暂缓（冻结等待计时，权重保留可恢复） |
| 迁移原子性 | 台位行级悲观锁 + 单据悲观锁 + 乐观锁版本号：原子释放原台位占用 → 锁定目标台位容量 → 继承已等待时长（累积等待段保留）→ 重算预计叫号时间 |
| 目标被并发占用 | 容量校验失败即回滚到**可解释的待分配状态**（`PENDING_ALLOC` + 原因文案），不重复排队、不丢失原队列位置（权重快照/等待时长完整保留），处置单保持可再次处置 |
| 维修后不自动回队 | 维修完成仅生成**公平恢复建议**：按故障时权重快照、当前窗口负载、同类业务服务能力计算建议台位与插入位次（**不插队首**）；主管复核通过才回队，可改派或退回前台 |
| 风控告警不阻断 | 重复取号（同人 2 小时内在途单）、跨窗口频繁转台（≥3 次）、人工插队（指定低权重号码先叫）生成带 `traceId` 的可追溯告警；告警从不阻断业务，**紧急且已完成身份核验**的单据自动标记「紧急已核验·不阻断」 |
| 统一查询 | 前台「统一查询」按业务单号输出：前台处置记录+动作流水、维修闭环、客户通知、队列变更时间线、主管复核结果、风控告警 |
| 幂等与并发 | 写接口支持 `Idempotency-Key` 响应回放；设备事件 `eventId` 全局唯一（反复上报只处理一次）；处置动作幂等键唯一约束；并发转台悲观锁串行化；并发叫号不会双重叫号；乐观锁冲突返回 409 可解释文案 |

### 新增/变更接口（本期）

```
POST /api/devices/events                 设备状态上报（eventId 幂等；OFFLINE→处置单+维修单）
GET  /api/dispositions?status=           处置单列表
GET  /api/dispositions/{id}              处置单+动作流水
GET  /api/dispositions/verify-chain      哈希链不可篡改校验
POST /api/dispositions/{id}/actions      前台处置：MIGRATE / TO_MANUAL / DEFER（幂等）
POST /api/dispositions/{id}/review       主管复核：approve(可改派) / 退回
POST /api/tickets/{id}/transfer          手动转台（失败自动生成 TRANSFER_FAILED 处置单）
POST /api/tickets/{id}/resume            暂缓恢复（权重保留）
GET  /api/repairs                        维修单列表
POST /api/repairs/{id}/start|complete    维修接单/闭环（闭环生成公平恢复建议，幂等）
GET  /api/risk-alerts                    风控告警列表
POST /api/risk-alerts/{id}/ack           告警确认
GET  /api/notifications                  客户通知
GET  /api/orders/{ticketNo}/trace        统一查询（全要素）
```

## 一、功能与角色

平台围绕**同一条排队记录（Ticket）**组织多方协同：客户、大堂经理（主管）、前台、柜员、理财经理、设备维修、风控、安保、客服。

| 角色 | 账号 | 主要权限/视图 |
|---|---|---|
| 客户 | `customer01` | 线上取号（幂等防重）、适老服务预约、紧急业务+身份核验标记、查看排队进度与时间线、取消排队 |
| 大堂经理（主管） | `manager` | 网点 KPI 看板、分流调度、**恢复建议复核**（通过/改派/退回）、现金库存、应急 |
| **前台** | `frontdesk01` | **处置单工作台**（迁移/转人工/暂缓三方案）、待分配与暂缓客户处理、手动转台、**统一查询**、客户通知 |
| 柜员 | `teller01` / `teller02` / `teller03` | 本窗口叫下一位、指定号码叫号、**设备离线上报**、临时离岗、办结 |
| 理财经理 | `advisor01` | 理财室排队、理财咨询叫号、双录办结 |
| **设备维修** | `repair01` | **设备状态上报（幂等演示）**、维修单接单/闭环、公平恢复建议预览 |
| **风控** | `risk01` | **重复取号/频繁转台/人工插队告警**确认与追溯 |
| 安保 | `security01` | 大额转账反诈核验、无障碍通道、维序 |
| 客服 | `service01` | 投诉插队受理、长等关怀、办结回访、远程渠道引导 |

> 统一演示密码：**`ccb@123456`**（登录页点击账号可自动填充）

### 核心业务规则（既有）

- **取号/预约信息**：客户类型、预约来源、业务类型、风险等级、老人/轮椅/听力标记、预计办理时长、金额；新增**紧急业务/身份核验**标记与**排队权重**（优先级 + 等待老化）。
- **分流防挤兑**：按业务类型、风险等级、老人、金额、材料完整度推荐渠道；分流到台位即**生成预计叫号时间**（按队列位次 × 台位均件时长，每 30 秒老化重算）。
- **适老服务**：老人自动建议爱心窗口；陪同引导、材料预审、家属远程确认；忘带证件挂起不占窗口。
- **异常协同**：长等预警、大额反诈核验、柜员离岗回池、投诉插队、突发停电全部沉淀在同一记录时间线。
- **现金业务**：大额取现校验库存、自动调拨、办结扣减、低储告警。
- **运营档案**：等待时长、业务结果、风险提示、投诉回访、渠道分布统计。

## 二、演示数据

启动后自动初始化（仅首次）：

- 11 个账号（上表，密码均为 `ccb@123456`）；
- 5 个服务点（含**排队容量**与**可办业务类型**）：1 号综合柜台(3)、2 号现金柜台(3)、爱心优先窗口(2)、智能柜员机 01(2)、理财室 A(2)；
- 现金库存 120 万元（预警线 30 万）；当日已标记「养老金集中发放日」；
- 6 张在途排队单，其中 **A002、Z002 已分配至智能柜员机 01 且已生成叫号预估**（用于演示设备离线处置全流程）；另有 S001（老人社保卡）、Z001（大额转账反诈）、A001（VIP 开户）、L001（理财室）。

## 三、快速启动（宿主 Docker Compose，一键部署）

前置：宿主已安装 Docker Engine 与 Docker Compose 插件。

```bash
cd 93-feature-4-b
cp .env.example .env          # 可按需要改 CC_PUBLISH_PORT（宿主端口）
docker compose up -d --build  # 构建并启动 db / app / web 三个服务
docker compose ps             # 等待 app、web 健康（healthy）
```

仅 **web 门户端口发布到宿主**（`CC_PUBLISH_PORT:8080`）；PostgreSQL 与后端端口均不对外暴露。

```bash
docker compose port web 8080    # 例如 0.0.0.0:3193
# 浏览器打开 http://localhost:3193
curl -fsS http://localhost:${CC_PUBLISH_PORT:-3193}/actuator/health   # {"status":"UP"}
```

停止与清理：

```bash
docker compose down            # 保留数据卷
docker compose down -v         # 清空数据库卷，下次启动重新播种演示数据
```

### 本地开发（无 Docker 时）

```bash
# 1) 准备 PostgreSQL（本机 5432 或自定义端口均可），建库 branch_queue
# 2) 启动后端（默认连 jdbc:postgresql://db:5432/branch_queue，用环境变量覆盖）
cd backend
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/branch_queue \
SPRING_DATASOURCE_USERNAME=ccb SPRING_DATASOURCE_PASSWORD=ccb_pass \
mvn spring-boot:run
# 3) 前端开发服（另开终端，代理 /api 到 8080）
cd frontend && npm install && npm run dev
# 或将前端构建产物交给后端单进程托管：
cd frontend && npm run build && cp -r dist/* ../backend/target/classes/static/
# 打开 http://localhost:8080
```

## 四、验证方式（本题）

### 自动化冒烟（82 项断言，已实际执行通过）

```bash
# 后端运行中（本地或 compose 均可），执行：
node scripts/smoke.mjs           # BASE 默认 http://localhost:8080，可用 BASE=... 覆盖
# 输出末尾：通过 82 项，失败 0 项 / ALL_SMOKE_PASSED
```

覆盖本题全部验收点：幂等取号（同键重复提交同一号码）、设备离线生成处置单（同 eventId 重发/已离线重复上报均不重复）、哈希链校验、容量冲突回滚待分配（不重复排队）、迁移继承等待时长并重算 ETA、并发转台只成交一笔、转人工、暂缓/恢复权重保留、转台失败自动生成处置单、维修闭环不自动回队、恢复建议按权重快照不插队首、主管复核通过/退回、三类风控告警（紧急已核验不阻断）、统一查询全要素、并发叫号无重复。

### 手工演示走查路径（端到端）

1. **柜员** `teller01` 或 **维修** `repair01`：对「智能柜员机 01」上报设备离线（维修视图可重复点「同一事件 ID 重发」验证幂等）→ A002/Z002 自动生成处置单并转待分配，客户收到通知。
2. **前台** `frontdesk01`：处置单工作台选「保留权重迁移」→ 目标选已满台位 → 看回滚提示（待分配原因可解释）；再选有空余台位 → 迁移成功，等待时长继承、叫号预估重算。另一张单可演示「转人工窗口」「客户暂缓→恢复」。
3. **维修** `repair01`：维修单「开始维修 → 维修完成」→ 系统对待恢复客户生成公平恢复建议（客户未自动回队）。
4. **主管** `manager`：「恢复复核」页查看建议依据（权重快照/窗口负载/同类业务能力）→ 通过（可改派）或退回前台。
5. **风控** `risk01`：查看重复取号（客户连续取号触发）、频繁转台、人工插队告警并确认；紧急+已核验客户的告警带「不阻断」标记。
6. **前台**：「统一查询」输入 `Z002` → 一站查看处置记录、维修闭环、客户通知、队列变更时间线、主管复核结果、风控告警；「处置单哈希链」KPI 显示完整。

## 五、目录结构

```
93-feature-4-b/
├── docker-compose.yml          # db(postgres) + app(springboot) + web(nginx+vue)
├── .env.example
├── scripts/smoke.mjs           # 本题端到端冒烟验证（82 项断言）
├── backend/
│   ├── Dockerfile              # 多阶段：maven 构建 → jre 运行（非 root + HEALTHCHECK）
│   └── src/main/java/com/ccb/qb/
│       ├── model/Enums.java          # 全部业务枚举（含处置/维修/风控/队列变更）
│       ├── entity/                   # 既有 6 个 + 新增 8 个（处置单/动作/维修单/设备事件/风控告警/通知/队列时间线/幂等键）
│       ├── repo/                     # JPA Repository（含悲观锁查询）
│       ├── service/
│       │   ├── QueueService.java     # 取号/分流/叫号/反诈/事件（接入权重/ETA/风控/时间线）
│       │   ├── DispositionService.java # 本期核心：设备事件幂等/哈希链处置单/原子迁移回滚/维修闭环/公平恢复/复核/统一查询
│       │   ├── QueueOps.java         # 权重/等待继承/ETA 重算/时间线/通知 共享组件
│       │   ├── RiskService.java      # 重复取号/频繁转台/人工插队检测
│       │   └── IdempotencyService.java # Idempotency-Key 响应回放
│       ├── config/DataSeeder.java    # 演示数据种子（11 账号/容量台位/6 在途单）
│       └── web/                      # QueueController + DispositionController + 异常处理（409 冲突文案）
└── frontend/
    ├── Dockerfile              # 多阶段：node 构建 → nginx 托管（非 root + HEALTHCHECK）
    └── src/
        ├── views/                  # 既有 7 视图 + 新增：前台处置/设备维修/风控告警/恢复复核
        └── components/             # 队列表、协同弹窗、横幅
```

## 六、配置项（环境变量）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CC_PUBLISH_PORT` | `3193` | 发布到宿主的 web 门户端口 |
| `JWT_SECRET` | 内置演示值 | JWT 签名密钥，生产请更换 |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://db:5432/branch_queue` | compose 内数据库地址 |
| `SPRING_DATASOURCE_USERNAME/PASSWORD` | `ccb / ccb_pass` | 数据库账号 |

应用侧业务参数（`backend/src/main/resources/application.yml`）：长等阈值 `app.queue.long-wait-seconds=600`、大额阈值 `app.queue.large-amount-threshold=50000`。

## 七、验证结论（实际执行记录）

以下验证均在本工作区实际执行通过（2026-09-30）：

- `mvn compile`：后端编译通过；
- `npm run build`：前端构建通过（vite 产物 151KB）；
- 本地 PostgreSQL 15 + 后端实跑，`node scripts/smoke.mjs`：**82 项断言全部通过（ALL_SMOKE_PASSED）**，覆盖第四节列出的全部验收点；
- 单进程托管检查：后端放行静态资源后 `GET /`、JS/CSS 资产均 200，未登录访问 `/api/**` 返回 401；
- 运行日志无 ERROR（仅 Hibernate DDL 提示性 WARN）。
