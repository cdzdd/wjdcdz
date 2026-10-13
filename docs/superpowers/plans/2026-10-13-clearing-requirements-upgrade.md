# 课程新开发要求改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 在保留现有基金模拟业务和多银行卡数据的基础上，补齐业务编号、清算资源隔离、错误行情重新清算、当日申请文件导出，并形成课程规格与验收材料。

**Architecture:** 继续使用模块化单体和同一MySQL数据库，增加固定清算节点、持久化任务、行情修订与确认轮次。清算通过有界后台通道执行，重清先预演、再以冲正和新结果原子发布资产变化；申请文件从截止快照分页落盘。

**Tech Stack:** 现有Java 21、Spring Boot 4.1.1、Spring JDBC、MySQL 8.4、Flyway、Vue 3、TypeScript、Vitest和Playwright。连接池沿用已有Hikari依赖。

**Spec:** [新要求偏差核对](../../teacher-requirements-gap-analysis.md)、[老师开发要求](../../系统开发的一些要求.docx)、[规格说明书模板](../../模板-需求规格说明书.docx)。基线提交`17f7519`；本文件是待定稿的改造计划，不代表改造已经完成。

## Global Constraints

- 平台继续为教学模拟系统，银行卡资金指平台内模拟账户余额；不接真实资金、真实银行或真实TA。
- 保留既有开户、双卡账户、申购赎回、认购、定投、转换、分红、货币收益、换卡、清盘及审计能力。
- 金额2位、份额4位、净值6位、费率8位，金融计算用BigDecimal，沿用既定HALF_UP及业务规则快照。
- 原申请金额/份额、归属日、来源交易账户、来源卡、TA关系、费率和风险快照不得在重清时改成当前参数。
- 不自动借用另一张卡，不制造负现金、负份额或超冻结，不删除旧确认及账务历史。
- V1—V13迁移文件不可改写；新增迁移先在`fund_sim_test`验证。演示库升级前备份并核对资产。
- 不引入微服务、Redis、消息队列、分库分表或通用工作流引擎；只实现本项目固定节点和恢复规则。
- 时间必须区分申请归属日、行情所属日、清算处理日、资产业务有效日和真实运行时间。

## Review Focus

1. 旧确认产生的持仓被后续赎回或转换使用：任务5、6验证依赖重放及原申请份额不足的整体阻断。
2. 已支付模拟款项已经提现：任务5验证不足金额预演，不跨账户补扣、不留下半冲正。
3. 更正当天与历史权益日期不同：任务4、6验证原有效日、分红登记和收益尾差不双算。
4. 截止前申请事务尚未提交，或导出期间发生撤单/换卡：任务7验证封账边界及不可变字段快照。
5. 后台任务异常、进程重启或线程复用：任务3、5验证事务、连接路由、门禁和幂等恢复。

## 一 需要定稿的业务口径

下表是**计划建议值，不是老师已经给出的规定**。实施前将采用值写入正式需求基线；如果老师提供不同口径，先调整此表及对应验收用例。

| 事项 | 建议采用的口径 | 需明确的原因 |
| --- | --- | --- |
| 编号宽度 | `yyyyMMdd + 6位补零序列` | 老师文字写不足5位，示例却为6位 |
| 序列范围 | 申请、确认各有独立每日序列，各自在全平台唯一，不按客户/卡/TA重新计数 | 老师未规定序列的分段范围；两类编号允许相同文本，但业务类别必须明确 |
| 编号日期 | 申请取归属工作日；确认取本轮实际处理工作日。重清编号日期与原确认有效日分开保存 | 避免使用电脑日期或更正日期改变历史持有期/权益 |
| 非工作日确认 | 日常交易确认及重清顺延到开放日；货币收益等自然日节点不受此限制 | 保证业务编号使用工作日，并补过期订单顺延回归 |
| 申请文件日期/范围 | 按`application_date`，覆盖当日全平台有效申购、赎回、认购、转换及定投生成订单，按源TA分组 | 不等同于个人历史记录；转换保留源/目标TA，不新增同TA才能转换的限制 |
| 撤单与导出 | 截止前已撤销申请从有效申请文件排除，记录排除笔数；已确认申请仍可历史重导 | 如老师要求撤销指令文件，需另定义协议，不能根据当前SUBMITTED状态导出 |
| 演示时间节点 | 示例产品15:00截止，15:05封账快照，15:30前完成文件；实际截止晚于涵盖产品的最晚截止时间 | 老师只说指定时点，未给具体钟点；不把示例时间硬套所有产品 |
| 重清目标范围 | 覆盖确认后、模拟支付后，以及至预演冻结时点的后续申赎/转换/分红/收益依赖 | 不能缩减成只更改未确认净值；跨日依赖需按有效时间重放 |
| 无法保持原申请 | 预演发现原份额不足或现金无法回收时整体阻断该影响范围，保留旧结果和差额原因 | 是否允许其他异常处置，需业务约定；默认不自动缩单、透支或跨卡扣款 |
| 已清盘边界 | 首版识别并阻断跨越已完成清盘的重清，明确原因；若课程要求覆盖，另扩展清盘重放及验收 | 老师未规定如何撤回已终结产品的后续业务，不能默默忽略 |

## 二 数据与代码改动地图

以下是计划新增结构，执行时按依赖追加迁移。名称统一，不允许各任务自行另建同义表。

| 数据结构 | 职责 |
| --- | --- |
| `business_number_sequences`、`business_numbers` | 每日两类序列及跨订单/转换的统一号码登记；类别＋编号唯一 |
| `trade_confirmations` | 不可变确认结果：来源、确认编号、清算轮次、前一确认、行情引用、原有效日、真实处理时间、结果与生效状态 |
| `clearing_runs`、`clearing_nodes` | 持久化运行、固定节点、进度、发起人、请求键、时间、错误及恢复状态；请求键永久幂等，活动范围占用结束后释放 |
| `nav_revision_sets`、`nav_revisions` | 一次行情导入的候选集合及各基金＋行情日的不可变版本；现有`nav_records`作为当前生效行情入口 |
| `asset_checkpoints` | 升级或重清可用的账户资产基线，标识可重放起点；不能冒充缺失的旧确认历史 |
| `reclearing_items`、`reclearing_scopes` | 持久化预演差异、账户影响范围、输入版本和资产写入门禁；以`clearing_runs`记录任务状态 |
| `application_exports`、`application_export_items` | 日期/TA/协议/快照版本、不可变文件行、进度、汇总、校验和文件完成状态 |

既有`orders`和`conversions`增加申请编号与当前确认引用。资金/份额流水、持仓批次、付款、分配明细、分红权益、收益快照增加轮次、冲正或失效关联及有效日期；唯一约束同步包含轮次。普通充值、提现等非重算事实使用固定旧轮次并保留原始来源。账户资产写操作统一分配单调操作序号，流水记录原操作序号与操作内顺序；资产版本另用于预演水位。重清保留原因果顺序，不拿跨表ID或相同模拟时间猜先后。

账务统一采用“原流水＋同业务有效日反向冲正流水＋新流水”加总模型。旧确认、旧批次失效只影响当前结果引用，不把原审计流水从加总中排除；否则会排除旧流水却保留冲正而双减。当前有效持仓投影与全部流水加总必须对账一致。

旧`order_no`、主键和请求幂等键保持。旧确认无法可靠重建的显示“历史确认编号未采集”；如需补编号，单独执行可审计元数据迁移。旧资产对账后建立基线，新结果从基线开始记录完整轮次；没有完整重放依据的基线前更正须明确拒绝，不能猜测历史。

主要新增后端文件位于`server/src/main/java/com/hengsheng/fund/`：

- `BusinessNumberService.java`：唯一编号。
- `ConfirmationService.java`：确认历史、当前生效引用和轮次去重。
- `ClearingService.java`、`ClearingController.java`：固定节点、任务提交、执行及查询。
- `ClearingExecutionContext.java`、`ClearingDataSourceConfig.java`：后台执行上下文、连接路由与事务配置。
- `ReclearingService.java`：影响预演、门禁、固定业务类型重放及原子发布；只在复杂度实际需要时拆分预演计算文件。
- `ApplicationExportService.java`、`ApplicationExportController.java`：模拟TA文件快照、生成、下载和重导。

修改既有`CoreController`、`ScheduledJobs`、`FundService`、`TradeService`、`AccountService`、`AdvancedSupport`及扩展业务Service；查询侧修改`PortfolioService`、`AdvancedService`和涉及待付款/权益的查询。前端主要修改后台`Products.vue`、`Trading.vue`及订单/转换详情、`domain.ts`；复用现有表格与表单。

## 三 固定清算节点与执行规则

固定节点为`SEAL_APPLICATIONS`封账、`EXPORT_APPLICATIONS`导出、`IMPORT_MARKET`行情、`CONFIRM`确认、`PAY`付款、`RECONCILE`对账；已有SIP、MONEY等作为独立业务任务保留，不为其建立通用可编程流程。

募集结果处理、分红快照/执行和清盘执行也通过同一后台通道调用原业务服务，其专用手动接口改为返回runId；不能在普通清算移入队列后留下这些批量写资产的同步旁路。客户开户、申购/赎回/认购受理和偏好设置仍走实时通道。

封账/导出以申请归属日运行，是独立支路。确认以清算处理日扫描到期申请，使用各申请归属日的行情，不能拿当天行情替代。行情修订会使相关确认、付款及对账结果需要重算，不重复受理申请或再次生成定投订单。ALL只执行当前时点可运行的节点，尚未截止的导出可等待，不阻断到期确认。

运行状态：`QUEUED / RUNNING / WAITING / COMPLETED / NO_WORK / FAILED / INTERRUPTED / REJECTED`。节点状态同样持久化。资产不足预演为`BLOCKED`；新确认结果生效后旧结果为`REVERSED`。中断恢复只恢复未完成节点和未提交结果，不重新生效已提交账务。

手动任务和定时任务共用一个执行通道，初始1个工作线程、队列容量4。`requestKey`永久幂等；同日/类型/范围只在活动时占用唯一`active_scope_key`，终态或退出本轮扫描的WAITING释放为NULL，新请求允许再次扫描，原请求重试仍返回原运行。中断而结果尚未查明的作业先恢复/核对再释放。自动扫描每一轮使用新请求键，不能以业务日期固定一个永久键。

任务在入队前持久化；队列满不扩成无界数据库积压，准入失败返回429，已记录但无法入队的运行标记REJECTED并释放占用。重启后恢复持久任务。

后台提交只返回任务ID和`202 Accepted`，进度通过GET查询。角色在提交及正式执行时校验，发起人和业务时间写入运行记录；工作线程使用该运行上下文，不能依赖HTTP请求已经结束的会话。线程结束必须清理业务时间、数据源和认证上下文，审计明确区分管理员与系统任务。

## 四 分阶段实施任务

后端用独立MySQL测试库，定向命令为`mvn -f server/pom.xml -Dtest=测试类名 test`；先加载README中测试库环境变量。每个业务任务遵循“测试失败→最小实现→定向通过→完整套件→独立提交”。文档任务使用内容核对，不制作无意义代码测试。

### Task 1 形成改造需求基线和验收规格

**Files:** 创建`docs/requirements-specification.md`、按老师模板形成提交件`docs/需求规格说明书.docx`、`docs/interfaces/mock-ta-application-file-v1.md`；更新偏差说明和`docs/implementation-contract.md`。

**Interfaces:** 输出`NUM-001`编号、`CLR-001`执行隔离、`RCL-001`重新清算、`EXP-001`申请文件四组需求及前述口径表，后续任务必须引用同一编号和字段定义。

- [ ] 将老师四项要求、模板必填章节、建议口径、待确认边界分别写入规格，保留不适用标题。
- [ ] 用实际课程项目、小组及成员信息填写Word模板，替换功能示例，逐页渲染核对；不得把示例USB Key或商业密级写成本系统功能/属性。
- [ ] 固定模拟CSV字段顺序：协议版本、申请归属日、申请编号、业务类型、交易账户、基金账户、源/目标基金、源/目标TA、金额、份额、来源模拟卡、绑定版本、受理业务时间、来源类型/ID；UTF-8、CSV转义、文本编号、换行、文件名及空文件均给示例。
- [ ] 文件金额/份额表示原申请：申购、认购写申请金额且申请份额为空；赎回、转换写请求份额。不得把确认后覆写的orders.shares当成申购申请份额。
- [ ] 核对每条接受的需求都有输入、权限、异常、输出和验收方法；口径定稿后提交文档基线，未定稿部分不得在实现中暗选。

### Task 2 统一申请和确认编号

**Files:** 新建`BusinessNumberService.java`、`ConfirmationService.java`及`V14__business_numbers.sql`；修改`TradeService`、`OfferingService`、`ConversionService`和订单查询；测试`BusinessNumberIntegrationTest.java`。

**Interfaces:** `BusinessNumberService.next(String kind, LocalDate businessDate): String`，kind只允许`APPLICATION/CONFIRMATION`；`ConfirmationService.record(String sourceType, long sourceId, long roundId, LocalDate confirmationNumberDate, LocalDate effectiveDate, Map<String,Object> result): long`返回确认ID，同来源同轮次重试返回原ID。任务2暂用roundId=0表示旧执行入口，任务3接入后新确认使用真实runId；历史及编号登记不伪造缺失时间。

- [ ] 测试`dailyNumbersAreUniqueAcrossAccountsAndTypes`：同日双卡、订单与转换共用申请序列；并发生成不重复；日期与6位补零按基线检查。
- [ ] 测试`retryAndWaitingDoNotCreateAnotherNumber`、`legacyRecordsRemainReadable`：重复申请保持编号，未到确认时间/缺净值不生成成功确认编号，失败事务没有有效孤立记录，旧数据不改资产。
- [ ] 运行`BusinessNumberIntegrationTest`观察预期失败，再实现事务内序列登记、来源绑定及成功确认记录；定投仅沿用生成的申购，不另发申请号。
- [ ] 定向测试及完整后端套件通过；检查日切、非工作日、截止时点、序列上限不截断/回绕；提交编号改造。

### Task 3 持久化任务与资源隔离

**Files:** 新建`ClearingService.java`、`ClearingController.java`、`ClearingExecutionContext.java`、`ClearingDataSourceConfig.java`、`V15__clearing_runs_and_nav_revisions.sql`；修改`CoreController`、`AdvancedController`、`ScheduledJobs`、`FundService`、`application.yml`；测试`ClearingJobIntegrationTest.java`、`DataSourceRoutingIntegrationTest.java`、`BatchPagingIntegrationTest.java`。同步调整现有`CoreIntegrationTest`、`AdvancedIntegrationTest`、`JobHistoryIntegrationTest`、`MoneyLifecycleIntegrationTest`、`MoneyExitIntegrationTest`、`OfferingSipRegressionTest`的批次调用与等待结果方式。

**Interfaces:** `ClearingService.submit(String type, LocalDate businessDate, Map<String,Object> parameters, Long actorId, String requestKey): long`；`get(long runId): Map<String,Object>`输出state、nodes、progress、counts、times、reason；`execute(long runId): void`仅由后台通道调用。

- [ ] 先测试手动/定时同时提交仅执行一份、队列满有明确状态、重启恢复不丢任务；失败测试确认当前同步路径不满足目标。
- [ ] 测试`completedScopeCanBeScannedAgainWithNewRequestKey`：当天NO_WORK/完成后出现新到期业务可再跑，旧请求重试仍返回旧运行，不被历史范围唯一键永久封死。
- [ ] 建立固定节点和持久队列恢复；新`POST /api/admin/clearing-runs`提交，GET运行列表/详情查询。旧GET任务历史保留，旧POST任务改成异步202并同步更新调用方与契约测试，不能同时留下绕过队列的执行入口。
- [ ] 使用同库两池，建议实时池8连接、清算池2连接；一个Primary路由DataSource和同一个事务管理器供现有Db/事务模板使用。进入任何事务前设置BATCH路由，事务内不得切换，结束finally清理。
- [ ] 测试`batchFailureRollsBackWalletPositionAndEntries`及`batchPoolCannotConsumeRealtimeQuota`；不能只换清算JdbcTemplate而沿用另一个数据源的事务管理器。
- [ ] 将行情外层整批事务改为产品＋行情日短事务，候选查询固定页200条、稳定游标，禁止对状态变化的候选使用OFFSET；验证多页无漏单及必要锁顺序。
- [ ] 定向及完整套件通过，记录现有API异步兼容变化并提交。

路由和事务配合依据：[Spring AbstractRoutingDataSource](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/lookup/AbstractRoutingDataSource.html)、[DataSourceTransactionManager](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/DataSourceTransactionManager.html)。两池限制连接占用，不隔离共享MySQL的CPU/磁盘，因此仍须缩短锁和实测。

### Task 4 行情版本与可冲正确认基础

**Files:** 修改`FundService`、`ConfirmationService`、`TradeService`、`AccountService`、`AdvancedSupport`；追加`V16__confirmation_rounds_and_asset_effects.sql`；测试`ConfirmationRoundIntegrationTest.java`、`AssetEffectIntegrationTest.java`。

**Interfaces:** `FundService.createRevisionSet(List<Map<String,Object>> rows, String requestKey, String reason, long actorId): long`返回候选集合ID；每行明确fundClassId、marketDate、nav。单条表单与行情CSV修订均走此入口，一次集合可包含多产品/日期，重清以整个集合预演，不能漏掉重跑导入文件的其他行。`ConfirmationService.reverse(long confirmationId, long runId): void`登记冲正效果，与新结果同一发布事务内使用。`TradeService.calculateConfirmation(Map<String,Object> application, List<Map<String,Object>> allocations, Map<String,Object> navs, LocalDate effectiveDate): Map<String,Object>`只计算原申请结果，由正常确认及重清共同使用，不重新受理或直接修改钱包。

- [ ] 先测试原已发布净值仍禁止普通覆盖，修订版本可留存而不立即影响资产；两轮结果有独立确认号和前后关联。
- [ ] 改造来源唯一键与记录状态：轮次独立，旧持仓/付款/分配/确认保留并失效；不把`source_id`拼接轮次，以免破坏现有数字来源关联。
- [ ] 分开资产有效日期与真实记账时间，新结果沿用原确认有效日和持有起点；修订确认编号用本轮处理工作日，二者不能混用。
- [ ] 测试`originalPlusReversalPlusNewEffectsReconcile`、`historicalRightsUseOriginalEffectiveDate`和`multiRowMarketRevisionIsAppliedAsOneSet`；改所有`status!='PAID'`应收判断为有效待付状态集合，排除已冲正付款。
- [ ] 扩展对账到可用/冻结现金、可用/冻结份额、成本、有效确认、付款及尾差；定向及完整套件通过后提交。

### Task 5 普通交易及模拟付款重清

**Files:** 新建`ReclearingService.java`、`V17__reclearing_scopes.sql`；修改`TradeService`、`AccountService`、`PortfolioService`、`ClearingController`；测试`ReclearingIntegrationTest.java`、`ReclearingRecoveryIntegrationTest.java`。

**Interfaces:** `ReclearingService.preview(long revisionSetId, long actorId): Map<String,Object>`返回previewId、scopeVersion、affectedAccounts、changes、blockers；`execute(long previewId, String scopeVersion, String requestKey, long actorId): long`返回后台runId，校验账户资产版本、行情有效确认集合水位及完整依赖成员集合，不能只核对预演时已经列出的账户。

- [ ] 先测试`twoCardPurchaseCanBeRecleared`：净申购1000元，错误净值1确认1000份，修订1.25后800份；两卡分别计算，成本及原申请编号保持，新确认号产生，旧结果可查。
- [ ] 测试赎回未支付、已支付且可回收、已提现导致资金不足；不足时预演输出账户与缺口，原有效结果完全不变，不缩单、不跨卡扣款。
- [ ] 原款未付只替换有效待付款，不提前加钱包；原款已付则发布时结算净差额，新轮次付款记录为已结算/PAID并关联旧款，不能生成PENDING后再支付整笔新金额。
- [ ] 现金不足按当前账户更正净差额和保留的充值/提现事实判断，不要求先从钱包物理扣回全部旧款再入新款；份额依赖仍按原有效时间校验，不能拿之后新买入的份额补历史赎回。
- [ ] 预演从错误行情引用出发构造截至版本水位的影响范围；执行前设置受影响账户写门禁并再次校验版本，新充值/申赎等使预演过期时要求重算预演。
- [ ] 从可核验基线重建账户，保持充值、提现和人工差额更正事实；旧效果按逆序抵消，新效果按原有效时间和原操作序号重放，保留申请规则，不调用submit重新受理。
- [ ] 课程规模下使用一个发布事务提交整个预演影响范围的资产差额、旧结果失效、新确认/付款和生效行情指针；预演计算在事务外。现金汇总更新不得出现人为中间负余额，读取不得看到半冲正。
- [ ] 在发布事务提交前核对受影响账户的现金、冻结、份额、成本、有效付款及输入水位；任何对账失败回滚整个范围，不能发布后才发现失败并留下新旧混合结果。
- [ ] 发布使用受影响账户门禁和资产锁，不复用会在整次事务持有产品锁的旧confirmOne/addLot入口；必要产品/行情指针锁仅在提交前的短临界段获取。测试同一基金的未受影响账户实时申赎仍可运行，不能只证明不同基金可用。
- [ ] 测试`failureBeforePublicationLeavesOriginalState`、`sameReclearingRequestAppliesOnceAfterRestart`、`previewBecomesStaleAfterAssetWrite`；最终状态和门禁一起收敛，不能按超时盲目解锁。
- [ ] 定向及完整套件通过，提交可运行的普通交易重清里程碑；此时尚不能宣称所有扩展依赖已覆盖。

### Task 6 跨日与扩展依赖重放

**Files:** 修改`ReclearingService`、`ConversionService`、`DividendService`、`MoneyIncomeService`、`OfferingService`、`SipService`、`LifecycleService`、`CardChangeService`、`CashCorrectionService`及权益/付款查询；测试`ReclearingDependencyIntegrationTest.java`、`ReclearingRightsIntegrationTest.java`。

**Interfaces:** 沿用任务5preview/execute入口；预演中changes按原有效时间、原操作序号及操作内顺序排序，blockers列出来源类型/ID、账户、原因和不足量；全部确认结果沿用任务2/4的轮次机制。

- [ ] 先测试后续部分/全额赎回、FIFO成本、待确认赎回/转换的冻结重新分配；固定原申请份额不足时阻断整个影响范围。
- [ ] 追踪转换到目标批次及其后续赎回；同账户相关资产采用保守闭包，不要求最小依赖图。定投重算已有订单，不能再次运行当期扣款。
- [ ] 测试已登记/现金支付/再投资分红及日收益、负收益、尾差和全部退出；旧＋同有效日冲正＋新结果只产生一份有效权益。
- [ ] 测试重清期间换卡/解绑/付款受门禁保护，原授权链和来源账户保持；原费率变化、产品暂停或终止不改变已有申请快照，已清盘边界按需求基线阻断。
- [ ] 全部依赖场景通过并提交；若任何既有业务类型尚无可重放依据，明确阻断和范围，不能静默忽略后宣称完整重清。

### Task 7 当日申请封账与文件导出

**Files:** 新建`ApplicationExportService.java`、`ApplicationExportController.java`、`V18__application_exports.sql`；修改`TradeService.submit/cancel`、`ConversionService.submit/cancel`、`OfferingService`、`ClearingService`、`ScheduledJobs`，所有申请/撤单事务共同遵守封账边界；测试`ApplicationExportIntegrationTest.java`、`ApplicationExportRecoveryTest.java`。

**Interfaces:** `create(LocalDate applicationDate, long registrarId, String requestKey, long actorId): long`；`generate(long exportId): void`；`get(long exportId): Map<String,Object>`；`regenerate(long exportId, String requestKey, long actorId): long`只重导同一快照。

- [ ] 先测试普通申购/赎回/认购/转换及定投生成订单的日期、TA和撤单范围；统一认购过截止归属日，不能在封账后新增同日认购。
- [ ] 封账和申请受理/撤单使用统一业务日提交边界，等待截止前已进入的事务完成，再分页固化字段快照；仅记录MAX(id)不足以冻结状态与字段。
- [ ] 从不可变快照按序号分页、BufferedWriter写受控目录`.part`，校验行数/金额/份额汇总与SHA-256后原子发布，成功状态后才允许下载。空文件有明确表头和统计。
- [ ] 接口位于`/api/admin/application-exports`：POST生成、GET列表/详情、GET `/{id}/download`、POST `/{id}/regenerate`；文件路径不能由客户端传入。手动与截止调度共用后台通道。
- [ ] 测试`sameSnapshotRegenerationHasSameChecksum`、`sealDoesNotLoseInFlightApplication`、`laterCardChangeDoesNotChangeExport`、`partialFileCannotBeDownloaded`；磁盘满/中断/重复作业和下载权限正确。
- [ ] 定向及完整套件通过，提交导出；保留个人历史CSV原用途，不改成全平台文件。

### Task 8 页面与接口升级

**Files:** 修改`web/src/pages/admin/Products.vue`、`Trading.vue`、`Benefits.vue`、`web/src/pages/Orders.vue`、`Benefits.vue`、`domain.ts`、`web/e2e/platform.spec.ts`、`web/e2e/live-workflows.spec.ts`及`docs/implementation-contract.md`；管理员与投资者各自的Benefits页面分别处理提交进度/结果读取，实际服务测试的批次辅助函数等待任务完成后才能推进下一业务时点。

**Interfaces:** 消费任务3运行接口、任务5预演/执行接口和任务7导出接口；编号详情返回原申请、当前确认及历史轮次。

行情与重清接口由`ClearingController`提供：`POST /api/admin/nav-revisions {fundClassId,marketDate,nav,reason,requestKey}`创建单条候选集合，`POST /api/admin/nav-revisions/import`以multipart文件、reason、requestKey创建多行候选集合，都返回revisionSetId；`POST /api/admin/reclearing/previews {revisionSetId}`、`GET /api/admin/reclearing/previews/{id}`查询影响；`POST /api/admin/reclearing/runs {previewId,scopeVersion,requestKey}`返回runId。影响明细用固定200条游标分页，不能把大范围明细塞入单个响应。

- [ ] 先补浏览器失败用例：修订净值→影响预演→差异/不足提示→提交→进度→历史确认；导出状态→下载→重导；投资者可见自身确认历史。
- [ ] 页面显示业务日、行情日、轮次和真实时间；QUEUED/RUNNING不能因为计数暂为0就显示“无待处理业务”。保留只显示非零处理数量的既有展示。
- [ ] 刷新或离开后仍可通过任务ID查询；预演过期要求重新预演；重清执行仅ADMIN，普通节点和导出按OPERATOR/ADMIN权限，下载单独鉴权。
- [ ] 运行`npm test`、`npm run typecheck`、`npm run build`及隔离浏览器回归；再跑实际服务角色和主流程验收，提交页面改造。

### Task 9 性能、升级与课程验收

**Files:** 新建`scripts/Test-ClearingPerformance.ps1`、`scripts/Seed-PerformanceData.ps1`、`docs/clearing-performance-report.md`、`docs/requirements-test-matrix.md`；更新README、验收记录和演示指南。

- [ ] 只在独立测试库创建数据，固定软硬件、Java堆、连接配额和输入脚本，分别测无批次、清算并行、文件生成并行；正确性校验与性能数据一起记录。
- [ ] 采用下节建议门槛跑首次基线，调整值需在规格中说明；未测或未达标不能写成已完成。
- [ ] 演示库升级前备份；测试V14—V18迁移、基线建立、旧卡/订单读取和旧资产一致。逆向业务重清不使用整库恢复；部署回退如需恢复SQL，先停服务并说明会覆盖备份后的新业务。
- [ ] 按需求编号汇总测试、代码和结果版本；输出正式规格、接口协议、流程图、性能报告和新演示脚本。全回归通过后才认定四项新增要求完成。

## 五 建议性能验收基线

以下数字是本项目建议值，需结合首次实测定稿，并非老师规定或当前实测成绩。

| 项目 | 建议基线/门槛 |
| --- | --- |
| 参考环境 | 4核、8GB、SSD；Java堆512MiB；记录实际版本和资源占用 |
| 常规数据 | 1000客户、每人2账户、20基金、1万历史申请、2000待确认 |
| 实时请求 | 20会话、总5请求/秒；预热2分钟、测10分钟，每组3次 |
| 实时响应 | 有效请求P95≤1秒，且并行P95≤max(基线P95×1.2，基线P95＋50ms)；技术错误、重复扣款为0 |
| 正常清算 | 2000笔确认≤3分钟，无漏单、重复确认或负资产 |
| 文件导出 | 1万行≤10秒，10万行专项≤60秒；批次增量堆≤64MiB，记录快照构建与文件写入两段峰值 |
| 重清范围 | 课程验收以最多2000笔受影响确认为基线；整个范围一次发布，对更大范围另评估发布窗口，不能偷偷分段产生半生效结果 |
| 重清并行 | 受影响账户短暂写门禁；无关账户正常业务仍达到实时门槛；门禁及恢复状态可查询 |

“不影响性能”采用这些可复现实测结果证明，不承诺共享数据库绝对零影响。10万行只是针对老师大数据导出要求的专项测量，不把整个演示平台扩大成企业系统。

## 六 里程碑与完成判定

| 里程碑 | 包含任务 | 可展示成果 |
| --- | --- | --- |
| M1 规格与编号 | 1—2 | 老师条款可追踪，申请/确认有合规业务编号，旧数据可读 |
| M2 清算执行基础 | 3—4 | 异步任务、进度、节点状态、连接配额、行情/确认版本和有效账务 |
| M3 重清核心与依赖 | 5—6 | 确认后、支付后及相关跨日业务可重算；不足/已清盘等边界明确阻断；历史完整 |
| M4 申请文件与页面 | 7—8 | 业务日截止快照、TA模拟文件、下载重导、完整管理员和投资者可视结果 |
| M5 课程交付 | 9 | 性能对比、升级验证、正式规格与测试矩阵、新演示脚本 |

任务2的编号与任务3的任务通道可以在字段契约确定后并行；任务7的文件协议/UI原型可并行准备，但封账实现依赖任务3。任务4—6共享账务唯一键和有效轮次定义，必须串行整合。最终验收以四项老师要求和全部保留业务的回归为准，不能只展示一个净值改错按钮或一份CSV。
