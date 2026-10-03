# 基金销售与模拟交易平台实现计划

本计划已推进到本地实现与运行验收。实际文件、接口及必要的模拟规则调整见[本地验收记录](../../acceptance.md)及[API说明](../../implementation-contract.md)。原任务列表保留为规划依据，公开部署与CI配置不属于本轮本地验收。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现可以上线持续使用的基金模拟平台，完整覆盖用户投资操作、后台登记确认及日常运营。

**Architecture:** 用户端和管理后台共用一个 Vue 网站，后端采用模块化单体，MySQL保存业务数据和任务进度。TA、支付与基金数据在系统内部模拟，定时任务与后台手动操作复用同一套事务处理函数。以一台服务器部署为目标。

**Tech Stack:** Vue 3、TypeScript、Vite、Vue Router、Pinia、Element Plus、ECharts；Java 21、Spring Boot 4.1.1、Spring Security、JdbcTemplate；MySQL 8.4、Flyway；JUnit、Vitest、Playwright；Nginx与Docker Compose。前端使用Node.js 24 LTS。

**Spec:** [基金销售与模拟交易平台设计草案](../specs/2026-10-04-fund-simulation-design.md)。执行前同时阅读设计草案和本计划。

这是2026年10月4日第二版计划，增加导师要求的多银行卡与多交易账户。用户已经确认“上线模拟交易平台”；技术栈尚未指定，本版给出建议栈。当前只有业务PDF与规划文档，没有现成代码和Git仓库。本轮更新计划与设计文档，不安装依赖、不初始化项目、不部署。

## Global Constraints

- 上线目标为模拟交易；本版不接真实资金、真实银行卡或真实TA。
- 投资者端与管理后台使用同一个网站、同一个后端和数据库。
- 统一以 Asia/Shanghai 处理业务日期，以服务端时间记录提交时间。
- 金额保存为 DECIMAL(20,2)，份额为 DECIMAL(24,4)，净值为 DECIMAL(20,6)，费率为 DECIMAL(12,8)，计算使用 BigDecimal。
- 中间计算保留至少12位，最终金额取2位、份额取4位，使用 HALF_UP；舍入差额记录在确认结果中。
- 接口以十进制字符串传递金融数值，前端只展示和预估，后端负责最终核算。
- 首次有效绑卡时按客户发放100000.00元到首个交易账户，开户重试和增绑卡不能重复发放。
- 一个客户可有多个交易账户，每账户当前绑定一张模拟卡并拥有独立资金子账户；同客户在同TA复用一个基金账户。
- 每笔交易显式保存交易账户、基金账户、来源卡和绑定版本；默认卡变化不改变历史，资金不跨账户借用。
- 正常回款按来源路径；受控换卡必须保留历史来源及逐笔改付授权，不能通过改默认卡改变收款目标。
- 账户和批次通过数据库事务及行锁防止重复花钱和重复赎回；确认与撤单竞争只能有一个成功。
- 缺少所属交易日的已发布净值时等待，不用最近一日净值代替；历史成交采用留存的规则版本。
- 不要求每一张表单独配置页面，不做动态权限设计器、任意流程编辑器或通用金融产品规则引擎。
- 使用数据库保存登录会话，线上Cookie使用HttpOnly、Secure、SameSite，保留CSRF防护。
- CSV上传只接受明确格式，大小限制5MB；资金与份额流水不可修改或删除。

## Review Focus

| 容易遗漏的条件 | 预期行为 | 验证任务 |
| --- | --- | --- |
| 截止时刻、周末或没有本日净值 | 归属正确开放日，缺净值等待，不猜成交价格 | 2、4、7 |
| 双击、网络重试和撤单与确认竞争 | 最多一个订单事件成功，不重复冻结或释放 | 3、5、6、7 |
| 多批份额、分红或转换后赎回 | 持有期与成本可追溯，费用和收益不重复计算 | 6、8、11、13 |
| 批处理执行到一半重启 | 保留已成功项，补跑未完成项，不重复收付 | 7、9至14、16 |
| 同账号跨权限访问或公开环境的演示操作 | 接口拒绝越权，公开环境不能推进时间或重置全库 | 1、15、16 |
| 同客户多卡、旧卡停用或多卡赎回部分到账 | 来源隔离、停扣款不阻止收款、逐子单重跑、有授权换卡改付 | 3A、5至14、16 |

## 功能范围和完成口径

范围包含客户档案、模拟开户、多银行卡、多交易账户、按TA管理的基金账户、默认卡、停用解绑、受控换卡、风险测评、产品与自选、净值和费率、按账户隔离的模拟资金、申购、赎回、撤单、持仓收益、交易查询、认购及退款、定投、两种分红、货币基金收益结转、基金转换、暂停与终止清盘、通知公告、后台统计、审计和导出。

多卡关系是交易模型的组成部分：客户可在交易账户A、B分别购入同一基金，持仓汇总展示但批次保持归属；默认卡只预填新交易。汇总赎回明确选择各账户份额并拆子单，分别回来源卡。首次有效绑卡发放初始资金，增绑不发钱；旧卡不可收款则待有记录的换卡处置，不自动转到另一张卡。

基金样本包含股票型、债券型、混合型净值产品和货币产品。每个功能至少有一个样本产品可操作；不要求所有产品支持所有业务。例如转换只对配置支持的净值产品组合开放。所有样本、图表和资金均标明模拟来源。

不实现场内股票或ETF/LOF交易、QDII境外日历、跨市场转托管、基金经理投资、真实支付、真实基金销售；也不把巨额赎回、定期开放和后端收费混入首版。它们是额外业务，不属于此次“场外模拟平台功能完整”的口径。

功能完成需要页面、接口、数据库记录、业务规则和关键验证全部贯通。只有按钮或静态图表不能标为完成。金额正确、历史可追溯、重启可继续、备份能恢复都是上线条件，与高并发无关。

## 开发阶段与工作量

以下是估算，不是交付承诺：假设3名同学每人每周投入约15小时，已有基础Web开发经验，多卡版完整版本预计11至16周；约61至87个八小时人日，较初版增加约5至8人日。业务不熟悉、独自开发或实际投入较少时应延长。先完成第7项即可演示核心闭环，第16项验收后才达到公开上线条件。

| 阶段 | 任务 | 可交付结果 | 建议节奏 |
| --- | --- | --- | --- |
| A 基础与规则 | 1至4，包含3A | 多卡与多账户、基金日历、逐账户资金和份额账 | 第1至3周 |
| B 日常交易 | 5至8 | 选卡申购、多卡赎回拆单、原路到账、换卡及资产统计 | 第4至6周 |
| C 完整业务 | 9至14 | 各扩展业务保持账户来源与回款规则 | 第7至12周 |
| D 上线准备 | 15至16 | 完整后台、自动运行、验证和备份恢复 | 第13至16周 |

各阶段是开发顺序，不是把后面功能降为可选项。可以在接口固定后并行做页面与后端，但资金、份额、订单的共享结构由同一负责人维护。

## 建议目录和职责

```text
hengSheng/
  web/
    src/router/                     用户与后台路由及导航
    src/api/                        与后端一致的请求和响应类型
    src/views/investor/              基金、交易、资产、计划、消息等页面
    src/views/admin/                 产品、业务、任务、统计与审计页面
    src/components/                 多页面实际复用的表单和展示组件
    e2e/                            跨前后台业务验证
  server/
    src/main/java/com/hengsheng/fund/
      auth/ account/ fund/ calendar/ wallet/ portfolio/
      trade/ clearing/ offering/ sip/ dividend/ money/
      conversion/ lifecycle/ operations/ common/
    src/main/resources/db/migration/  按任务添加的SQL迁移
    src/test/java/com/hengsheng/fund/ 与业务包对应的测试
  deploy/                           Docker镜像、Compose、Nginx与备份脚本
  docs/                             规则、接口、部署说明和答辩材料
```

每个业务包包含自己的接口、服务、查询和请求类型。只抽取金额精度、时间、分页和异常响应等确定共用的代码。数据库迁移按顺序追加，已发布迁移不修改；不生成空包、通用仓库父类或用不到的接口。

下列完整路径相对项目根目录；省略重复前缀的Java业务文件（例如 `fund/FundService.java`）位于 `server/src/main/java/com/hengsheng/fund/`，测试文件位于 `server/src/test/java/com/hengsheng/fund/`，`V*.sql`位于 `server/src/main/resources/db/migration/`。省略前端页面目录的文件使用本任务紧邻的完整页面目录。它们是计划创建的文件，并非已经存在。外部接口统一以 `/api` 开头，普通用户接口只处理当前登录者的数据，后台接口由角色限制。列表采用每页20条、最大100条分页；不能以先读取全表的方式实现导出。

## 接口约定

| 业务 | 主要接口 |
| --- | --- |
| 账户 | `POST /api/auth/register`、`POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/me`、`POST /api/account/open`、`POST /api/risk-assessments` |
| 银行卡与多账户 | `GET/POST /api/bank-cards`、`PUT /api/bank-cards/{id}/default`、`PATCH /api/bank-cards/{id}/status`、`POST /api/bank-cards/{id}/unbind`、`GET /api/trading-accounts`、`GET /api/fund-accounts`、`POST /api/card-change-requests`、`POST /api/admin/card-change-requests/{id}/approve` |
| 产品 | `GET /api/funds`、`GET /api/fund-classes/{id}`、`GET /api/fund-classes/{id}/history`、`PUT/DELETE /api/favorites/{classId}` |
| 资金和资产 | `GET /api/trading-accounts/{id}/wallet`、`POST /api/trading-accounts/{id}/top-ups`、`POST /api/trading-accounts/{id}/withdrawals`、`GET /api/portfolio`、`GET /api/cash-entries`，后两项支持当前客户的交易账户筛选 |
| 交易 | `POST /api/trades/quotes`、`POST /api/orders`、`POST /api/redemption-groups`、`POST /api/redemption-groups/{id}/cancel`、`POST /api/orders/{id}/cancel`、`GET /api/orders`、`GET /api/orders/{id}` |
| 扩展 | `POST /api/offering-orders`、`POST /api/sip-plans`、`PATCH /api/sip-plans/{id}`、`POST /api/conversions`、`PUT /api/dividend-preferences/{classId}` |
| 运营 | `GET /api/notifications`、`PATCH /api/notifications/{id}`、`GET /api/exports/orders`、`GET /api/exports/cash-entries` |
| 后台 | `/api/admin/funds`、`/api/admin/navs`、`/api/admin/trading-calendar`、`/api/admin/jobs`、`/api/admin/offerings`、`/api/admin/dividends`、`/api/admin/liquidations`、`/api/admin/reconciliation` |

创建交易、资金和扩展业务时必须传请求去重键 `requestKey`；请求不传用户ID作为授权依据。购入请求显式含tradingAccountId、bankCardId、bindingVersion；先查幂等记录再校验当前卡状态，重试不能因默认卡或绑定版本变化变成新申请。汇总赎回含各tradingAccountId及shares，返回交易组与逐账户子单。报价返回 `estimated=true`、价格日期、逐账户预计费用及规则版本，实际成交使用订单归属日的发布净值。错误响应统一含 `code`、`message`、`requestId`；页面显示明确原因，例如余额不足、等待净值、收款卡受阻或风险测评过期。

## 任务1 账户与可运行基础

**依赖：** 无。

**Files:** 创建 `web/package.json`、`web/src/router/index.ts`、`web/src/api/account.ts`、`web/src/views/investor/LoginView.vue`、`server/pom.xml`、`server/src/main/java/com/hengsheng/fund/auth/SecurityConfig.java`、`account/AccountService.java`、`common/ApiErrorHandler.java`、`common/BusinessClock.java`、`operations/AuditService.java`；SQL为 `server/src/main/resources/db/migration/V1__accounts_and_audit.sql`；测试为 `server/src/test/java/com/hengsheng/fund/account/AccountIntegrationTest.java`。

**Interfaces:** 提供 `AccountService.open(long userId, String requestKey): AccountView`，开通客户资格而非建立唯一交易账户；`BusinessClock.now(): Instant`；`AuditService.record(long actorId, String action, String objectType, String objectId, String reason): void`。真正的交易账户与首笔初始资金在任务3A绑卡时建立。

- [ ] 写 `loginLogoutAndOwnership`、`disabledUserCannotStartNewTrade` 测试：普通用户访问后台返回403；A读取B的订单返回404；停用用户不能提交新交易；退出后会话不可继续使用。
- [ ] 运行测试确认预期行为尚未具备，再初始化可启动的前后端、数据库迁移、账户与角色 INVESTOR/OPERATOR/ADMIN、密码哈希、数据库会话和CSRF保护。
- [ ] 实现模拟开户、个人资料、修改密码、管理员审计密码重置；重置后强制用户改密，不在日志或审计中存密码。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=AccountIntegrationTest test` 和 `npm --prefix web run build`；应用能从空数据库启动，用户端和后台导航正确隔离。开户到首次绑卡及资金发放在任务3A完成后联合验收；客户与交易账户关系为一对多。

## 任务2 基金目录与交易日历

**依赖：** 任务1。

**Files:** 创建 `fund/FundService.java`、`fund/NavService.java`、`calendar/TradingCalendarService.java`、`web/src/api/funds.ts`、`web/src/views/investor/FundMarketView.vue`、`FundDetailView.vue`、`web/src/views/admin/FundManageView.vue`、`NavManageView.vue`；SQL为 `V2__funds_calendar_nav.sql`；测试为 `fund/FundCatalogIntegrationTest.java`、`calendar/TradingCalendarTest.java`。

**Interfaces:** 提供 `TradingCalendarService.applicationDate(long classId, Instant submittedAt): LocalDate`、`addTradingDays(long classId, LocalDate start, int days): LocalDate`；`NavService.published(long classId, LocalDate date): Optional<BigDecimal>`。基金接口返回规则版本和产品开放状态，历史金融数值为字符串。

- [ ] 写截止时刻和关闭日期用例：2026-10-12 14:59:59属于12日，15:00:00属于13日；16日15:01属于19日；17日申请属于19日；示例申赎均开放，12日至16日及19日为开放日。
- [ ] 先运行失败用例，再实现基金与份额类别、TA登记机构、股票/债券/混合/货币分类、搜索筛选、自选、详情、公告、费率和规则版本、日历、净值草稿及发布。每个基金明确所属模拟TA；截止时刻测试采用注入时钟。
- [ ] 导入12个明确标注模拟的样本：净值产品覆盖股票、混合、债券及A/C类别；包含至少一个募集样本、一个可转换组合和一个货币样本。只在测试/演示配置创建种子，生产不自动重置。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=FundCatalogIntegrationTest,TradingCalendarTest test`；同类别同日期发布唯一、使用过的净值不能覆盖、5MB以内CSV格式错误给出行号、详情及历史曲线可用。

## 任务3 模拟钱包与份额账

**依赖：** 任务1、2。

**Files:** 创建 `wallet/WalletService.java`、`portfolio/PositionService.java`、`web/src/views/investor/WalletView.vue`；修改 `account/AccountService.java`；SQL为 `V3__wallets_positions_and_entries.sql`；测试为 `wallet/WalletIntegrationTest.java`、`portfolio/PositionIntegrationTest.java`。

**Interfaces:** 提供 `WalletService.reserve(long tradingAccountId, long orderId, BigDecimal amount): void`、`consumeReservation(long orderId): void`、`releaseReservation(long orderId): void`、`credit(long tradingAccountId, String sourceType, String sourceId, BigDecimal amount): void`；`PositionService.reserveFifo(long tradingAccountId, long classId, long orderId, BigDecimal shares): List<LotAllocation>`，其中 `LotAllocation` 保存批次ID、交易账户、基金账户、来源卡、份额、取得日期和对应成本。调用必须处于外层业务事务，外层验证账户归属；不存在仅凭userId选择资金来源的扣款接口。

- [ ] 写双申请、多批冻结和账户隔离用例：同账户余额1200.00时两个1000.00预留只能一个成功；1000.0000份冻结300.0000后可用为700.0000；A余额不足但B充足时A申请失败，B余额不变。此阶段用测试夹具建立账户，实际绑卡和初始发放在3A接入。
- [ ] 运行失败用例，再实现钱包、资金流水、模拟充值提现、持仓批次、份额流水、批次预留、唯一事件与数据库行锁。
- [ ] 模拟充值提现显式选择交易账户并校验归属、正数及金额精度；每交易账户一个资金子账户，唯一约束及流水去重包含账户。任务3A绑定首卡时原子发放初始资金。用户不能自行删除账户账本或重置公开平台账户。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=WalletIntegrationTest,PositionIntegrationTest test`；资金和份额总量等于流水推算值；SQL异常回滚后不能只更新余额而缺失流水。

## 任务3A 多银行卡与账户管理

**依赖：** 任务1至3。基础绑定、默认卡、状态和资金隔离在本任务完成；任务7接入存量付款与换卡改付，任务9至14补齐各扩展业务的解绑阻塞和来源继承。

**Files:** 创建 `account/BankCardService.java`、`account/TradingAccountService.java`、`account/FundAccountService.java`、`account/CardChangeService.java`、`web/src/api/bank-cards.ts`、`web/src/views/investor/BankCardsView.vue`、`TradingAccountsView.vue`、`web/src/views/admin/CustomerAccountsView.vue`、`CardChangesView.vue`；SQL为 `V3_1__multiple_accounts_and_cards.sql`；测试为 `account/MultiAccountIntegrationTest.java`、`account/CardChangeIntegrationTest.java`。

**Interfaces:** 提供 `BankCardService.bind(long userId, BindCardCommand command): TradingAccountView`、`setDefault(long userId, long cardId): void`、`changeStatus(long userId, long cardId, CardStatus status): void`、`setReceiveState(long adminId, long cardId, ReceiveState state, String reason): void`、`unbind(long userId, long cardId): void`；`FundAccountService.ensureLinked(long userId, long tradingAccountId, long registrarId): FundAccountView`；`CardChangeService.request(long userId, CardChangeCommand command): CardChangeView`、`approve(long adminId, long requestId): CardChangeView`。BindCardCommand含系统模拟卡标识、银行、模拟持卡人和requestKey；换卡command另含原交易账户、理由和新卡信息。

- [ ] 写首次/重复绑定、多卡、同TA复用与归属用例：客户首卡获得100000.00；增绑B为0；重复开户和重复绑定不加钱；同TA经A/B买入共用基金账户，不同TA创建不同账户；他人卡和重复绑定卡被拒绝。
- [ ] 先运行失败用例，再建立客户、多交易账户、每账户一个资金子账户、模拟卡与绑定版本、TA基金账户及关联；原“用户唯一钱包/交易账户”改为交易账户资金隔离，不创建可重复消费的客户总钱包。
- [ ] 写默认卡和状态用例：改默认不改旧订单；DISABLED只禁止新扣款，RECEIVABLE仍可收款；全部卡不可扣款时默认为空；模拟单笔/单日限额按账户连续计算，换卡不重置。
- [ ] 实现解绑校验及同账户锁：非零资金或份额不能解绑；历史保留，默认卡需另指定或清空。后续交易、定投、红利、收益尾差与在途款纳入同一阻塞校验，不预留“可强制清空”入口。
- [ ] 实现同银行、同持卡人、未绑其他账户的新卡受控替换；留存申请、批准、理由和生效版本，替换保持交易账户编号与钱包。旧卡BLOCKED不以全部在途到账作为核准条件；完整改付行为在任务7验收。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=MultiAccountIntegrationTest,CardChangeIntegrationTest test`。卡列表和账户详情能显示资金、持仓、在途、关联TA、不能解绑的原因及换卡记录；无真实卡号输入。

## 任务4 费用与交易资格

**依赖：** 任务2、3、3A。

**Files:** 创建 `trade/FeeCalculator.java`、`trade/TradeEligibilityService.java`、`account/RiskAssessmentService.java`、`web/src/views/investor/RiskAssessmentView.vue`、`web/src/views/admin/FeeRulesView.vue`；SQL为 `V4__risk_and_fee_versions.sql`；测试为 `trade/FeeCalculatorTest.java`、`trade/TradeEligibilityTest.java`。

**Interfaces:** 提供 `FeeCalculator.purchase(BigDecimal amount, FeeRule rule, BigDecimal nav): PurchaseCalculation`，返回净金额、费用、确认份额和舍入差额；`redeem(List<LotAllocation> lots, FeeRule rule, BigDecimal nav, LocalDate applicationDate): RedemptionCalculation`；`TradeEligibilityService.check(long userId, long tradingAccountId, long classId, TradeType type, BigDecimal quantity, Instant at): EligibilityResult`，返回归属日期、基金账户、卡绑定版本和固定的风险、费率及规则版本。

- [ ] 写公式、边界档位及测评用例；比例费率1%、金额1212.00、净值1.200000应得费用12.00、净金额1200.00、份额1000.0000；固定费与持有期7日/30日边界分别有预期结果。
- [ ] 先验证测试失败，再实现外扣法、固定金额收费、批次赎回费、最低金额/份额/剩余份额规则、风险等级1至5与365日有效期。
- [ ] 验证风险过期或不匹配时拒绝申购、认购、定投和转换转入，但不拒绝赎回；卡停扣款、持卡人不一致、非本人交易账户或超过单笔/单日额度时拒绝购入；收款受阻不消灭存量权益。提交后修改费率不改变原订单费用规则。
- [ ] 运行 `mvn -f server/pom.xml -Dtest=FeeCalculatorTest,TradeEligibilityTest test`，所有数值按设计精度断言；订单报价标明估算及所用数据日期。

## 任务5 申购申请与订单查询

**依赖：** 任务1至4，包含3A。

**Files:** 创建 `trade/OrderService.java`、`trade/OrderController.java`、`web/src/api/orders.ts`、`web/src/views/investor/PurchaseView.vue`、`OrderListView.vue`、`OrderDetailView.vue`；SQL为 `V5__orders_and_events.sql`；测试为 `trade/PurchaseSubmissionTest.java`。

**Interfaces:** 提供 `OrderService.submitPurchase(long userId, long tradingAccountId, long bankCardId, long bindingVersion, long classId, BigDecimal amount, String requestKey): OrderView`；订单响应含交易账户、基金账户、来源卡及绑定版本、ID、类型、状态、提交时间、归属日、金额或份额、固定规则版本、确认数据、计划与实际支付日、等待/失败原因。

- [ ] 写余额、重复请求和未知价用例：余额2000.00申购1200.00后，可用800.00、冻结1200.00；订单SUBMITTED且成交净值为空；相同请求键返回同一订单；相同键不同金额返回409。
- [ ] 先运行失败用例，再在同一事务保存订单、指定账户资金和额度预留、规则与卡快照及订单事件；首次该TA购入关联基金账户。列表及详情根据当前用户过滤，可按账户/卡筛选。
- [ ] 完成基金详情到选卡选账户、校验、风险提示、估算、提交与订单查询；网络重试使用原请求键和原显式账户参数，改默认卡不改变已冻结资金和将生成批次的来源。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=PurchaseSubmissionTest test`；页面不会把受理误写成成交，历史订单能查询实际费用与净值日期。

## 任务6 赎回与撤单

**依赖：** 任务3至5，包含3A。

**Files:** 修改 `trade/OrderService.java`；创建 `trade/CancellationService.java`、`web/src/views/investor/RedeemView.vue`；修改订单详情页；SQL为 `V6__order_lot_allocations.sql`；测试为 `trade/RedemptionSubmissionTest.java`、`trade/CancellationRaceTest.java`。

**Interfaces:** 提供 `OrderService.submitRedemption(long userId, long tradingAccountId, long classId, BigDecimal shares, String requestKey): OrderView`、`submitRedemptionGroup(long userId, long classId, List<AccountRedemption> allocations, String requestKey): OrderGroupView`；AccountRedemption含tradingAccountId与shares；`CancellationService.cancel(long userId, long orderId, String requestKey): OrderView`、`cancelGroup(long userId, long groupId, String requestKey): OrderGroupView`。子单留存各批次和来源账户，交易组状态由子单推导。

- [ ] 写全部/部分赎回、最低剩余份额、多批持有期和撤单竞争用例；1000.0000份赎回300.0000后冻结300.0000；同一份额不能再赎回；撤单与确认只能一方成功。
- [ ] 写多账户拆单用例：A有1000.0000份、B有500.0000份，选择A赎回1000.0000与B赎回200.0000，产生两个子单；在无费净值1.20时应分别回款1200.00与240.00。若B子单不满足最低量，整组无订单、无残留冻结。
- [ ] 先运行失败用例，再实现账户内FIFO、逐子单限制及费用预估；多账户预留按账户ID排序锁定，在一个事务中整组成功或失败。确认前撤单释放原账户现金或份额，不能重算释放量；已有不可撤子单时不能整体撤组。
- [ ] 实现取消期限及状态控制；已确认订单禁止撤单或删除，认购按示例规则不撤单。停用账户的后台处置必须记录原因，不能静默扣留已确认应付款。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=RedemptionSubmissionTest,CancellationRaceTest test`；多批部分赎回最后一笔消费剩余成本，避免累计舍入丢失。

## 任务7 TA确认与到账闭环

**依赖：** 任务5、6。

**Files:** 创建 `clearing/ClearingService.java`、`clearing/SettlementService.java`、`clearing/JobService.java`、`clearing/ScheduledJobs.java`、`web/src/views/admin/TradingJobsView.vue`；SQL为 `V7__jobs_and_settlement.sql`；测试为 `clearing/ClearingIntegrationTest.java`、`clearing/RecoveryIntegrationTest.java`。

**Interfaces:** 提供 `ClearingService.confirm(long orderId): OrderView`、`SettlementService.pay(long orderId): OrderView`、`payPayout(long payoutId): PayoutView`、`JobService.run(JobType type, LocalDate businessDate): JobRunView`、`runDue(Instant now): List<JobRunView>`。PayoutView含来源交易账户、原卡/来源明细、实际目标卡、绑定版本、金额、换卡授权和阻塞原因；订单、红利、退款及清盘复用逐付款记录处理。任务类型先实现CONFIRM和PAY，不增加通用工作流框架。

- [ ] 写端到端账务用例：净值1.200000、无费申购1200.00，确认后消耗冻结并获得1000.0000份；赎回300.0000份得到360.00在途，支付后钱包增加360.00；重复确认支付没有第二笔流水。计划确认日09:00前和计划支付日09:30前，手动任务也不得提前处理。
- [ ] 写缺净值与中断恢复用例：缺所属日净值保持SUBMITTED；批次处理第一笔成功第二笔异常，补跑仅完成第二笔；错误不得改变历史成功结果。
- [ ] 写多卡收款与受控改付场景：A/B各自回款不受默认卡变化影响；A停用扣款仍收款；A收款BLOCKED保留原应付款；经ADMIN批准同银行换为A2并逐笔授权改付，A2收到一次，历史来源仍为A。无授权不得付B，成功付款不得再改付。
- [ ] 先运行失败用例，再实现逐订单和逐付款事务、来源与目标、改付记录、事件唯一约束、批次结果、手动与每5分钟到期扫描；多卡组一笔到账后重启，只补另一笔。支付受阻显示等待处置而非丢失债权；换卡不能重复发钱或改写历史流水。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=ClearingIntegrationTest,RecoveryIntegrationTest test`；`web/e2e/core-trade.spec.ts`贯通用户申购、后台发布净值/确认、用户赎回和后台支付。
- [ ] 完成一次本地核心闭环演示，保留订单、钱包和批次对账记录。确认按钮只执行规则，不作为管理员自由批准用户交易的审批。

## 任务8 资产总览与收益统计

**依赖：** 任务7。

**Files:** 创建 `portfolio/PortfolioQueryService.java`、`portfolio/ReconciliationService.java`、`web/src/views/investor/PortfolioView.vue`、`web/src/views/admin/ReconciliationView.vue`；测试为 `portfolio/PortfolioAccountingTest.java`；按需追加 `V8__portfolio_queries_indexes.sql`，不重复保存可查询推导的总余额。

**Interfaces:** 提供 `PortfolioQueryService.summary(long userId, LocalDate valuationDate): PortfolioView`、`ReconciliationService.check(LocalDate date): ReconciliationResult`。资产总额与累计收益采用设计草案公式，成本按批次保存。

- [ ] 写入金不是收益、冻结不能双计、多批部分赎回及提现场景；额外充值5000.00不增加收益；冻结申购款只在对应资金子账户计一次；赎回已确认未支付仍计入来源账户资产；客户总额等于A/B等各账户合计；改默认与换卡不改变资产或收益。
- [ ] 先运行失败用例，再实现现金、持仓、市值、在途、累计费用、持仓盈亏和累计收益查询，以及钱包/订单/批次/流水的对账。
- [ ] 完成客户总览、按交易账户/卡查看持仓资产、TA基金账户查询、净值日期提示、资金份额流水和逐账户后台对账；同基金汇总仍可展开来源卡与批次。每个统计指标附计算口径，不显示没有定义的年化收益。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=PortfolioAccountingTest test`。任务11至14追加分红、货币收益、转换和清盘后，再运行该测试验证总资产与收益守恒。

## 任务9 认购及募集生命周期

**依赖：** 任务7、8。

**Files:** 创建 `offering/OfferingService.java`、`web/src/views/investor/OfferingView.vue`、`web/src/views/admin/OfferingManageView.vue`；SQL为 `V9__offerings.sql`；测试为 `offering/OfferingIntegrationTest.java`。

**Interfaces:** 提供 `OfferingService.subscribe(long userId, long tradingAccountId, long bankCardId, long bindingVersion, long classId, BigDecimal amount, String requestKey): OrderView`、`finalizeOffering(long classId, OfferingOutcome outcome, LocalDate offeringEndDate): JobRunView`；outcome为ESTABLISHED或FAILED。认购受理原子扣款形成该账户认购在途，不提前按T+1确认；失败通过任务7的付款记录回来源结算路径。

- [ ] 写募集截止、成立和失败用例：无费、无利息、初始净值1.000000时1200.00认购得到1200.0000份；失败退1200.00到原账户/卡；重复成立退款不重复执行。认购在途、确认份额与退款应收只计一种；默认卡改B不使A的退款改付B，A收款受阻显示待退款。
- [ ] 写有模拟利息用例，按设计自然日差和365基数核对；成功利息增加净认购金额，失败退原认购总额加利息。
- [ ] 先运行失败用例，再实现RAISING到BUILDING到OPEN、募集失败到FAILED、募集参数和结果的后台操作；申请与结果都留存审计。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=OfferingIntegrationTest,PortfolioAccountingTest test`；封闭建仓期不能日常赎回；募集失败后账户和收益统计能对账；认购在途或退款未支付时不能解绑银行卡。

## 任务10 定投计划

**依赖：** 任务7。

**Files:** 创建 `sip/SipService.java`、`web/src/views/investor/SipPlansView.vue`、`web/src/views/admin/SipRunsView.vue`；修改 `clearing/ScheduledJobs.java`；SQL为 `V10__sip_plans_and_runs.sql`；测试为 `sip/SipExecutionTest.java`。

**Interfaces:** 提供 `SipService.create(long userId, SipPlanCommand command): SipPlanView`、`change(long userId, long planId, SipPlanChange command): SipPlanView`、`executeDue(LocalDate date): JobRunView`。SipPlanCommand包括tradingAccountId、bankCardId、bindingVersion、classId、amount、WEEKLY/MONTHLY、星期或1至28日、起始日期；每期固定实际来源，使用计划ID和原计划执行日去重。

- [ ] 写周末顺延、重复运行、余额不足和暂停用例：同计划同一期只有一笔申购；执行16日计划不重复补扣；关闭日顺延；暂停期间恢复后不补历史期次；计划绑A后默认改B仍扣A，A不足或停扣款不借B资金。
- [ ] 先运行失败用例，再将到期计划转为任务5的申购申请，复用资格校验和资金预留；不另实现一套扣款逻辑。
- [ ] 完成选卡创建、修改未来期次和扣款账户、暂停恢复终止、每期结果与失败通知；受控换卡只更新未来执行的合法卡版本，不改历史期次；未终止或仅暂停的计划阻止原卡解绑。拒绝每月29至31日，表单解释限制。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=SipExecutionTest test`；计划终止不偷偷取消已生成订单，已生成订单按日常申购规则查询或撤单。

## 任务11 现金分红与红利再投资

**依赖：** 任务7、8。

**Files:** 创建 `dividend/DividendService.java`、`web/src/views/investor/DividendRecordsView.vue`、`web/src/views/admin/DividendManageView.vue`；SQL为 `V11__dividends_and_entitlements.sql`；测试为 `dividend/DividendIntegrationTest.java`。

**Interfaces:** 提供 `DividendService.snapshot(long eventId): JobRunView`、`execute(long eventId): JobRunView`、`setPreference(long userId, long classId, DividendMode mode): void`；mode为CASH或REINVEST。偏好按客户和类别共用，权益按交易账户及历史来源卡分开；事件保存登记日、除息日、支付日、每份金额和再投资净值日。

- [ ] 写权益快照和除息场景：1000.0000份每份分红0.10，应得100.00；不考虑市场变化时净值由1.20降至1.10，除息后100.00在途补足资产，除息前不额外计入100.00；重复支付不增加现金或份额。
- [ ] 写登记后卖出仍按快照支付、登记后买入无该次权益、再投资形成新批次用例；具体登记日规则遵循设计草案的模拟口径。同客户A1000.0000份、B500.0000份每份分红0.10，各付100.00及50.00，不能因客户级去重只给一张卡付款。
- [ ] 先运行失败用例，再实现分红方式截止、逐账户/来源不可变权益快照、来源路径付款和红利再投资；再投资新批次保留来源，不合并进默认卡。未发红利阻止解绑。权益快照在登记日结束后且应确认订单处理完整后生成；发布除息净值只做一次，分红程序不再减净值。
- [ ] 成本规则：按权益比例减少原批次成本，允许调整后的成本为负；现金分红不增加批次，红利再投资新批次成本为再投资金额。累计收益仍由资产与外部入金计算，避免把再投资当充值。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=DividendIntegrationTest,PortfolioAccountingTest test`，现金与再投资两种模式的资产总额在同一除息场景下相等。

## 任务12 货币基金每日收益

**依赖：** 任务7、8。

**Files:** 创建 `money/MoneyIncomeService.java`、`web/src/views/admin/MoneyIncomeView.vue`；修改基金详情、持仓、任务调度；SQL为 `V12__money_income_snapshots.sql`；测试为 `money/MoneyIncomeIntegrationTest.java`。

**Interfaces:** 提供 `MoneyIncomeService.snapshot(LocalDate date): JobRunView`、`accrue(LocalDate date): JobRunView`。货币产品申赎复用订单与账本，但成交净值固定为1.000000；资格按交易账户、类别和来源卡记录，每日收益及尾差不跨账户合并。

- [ ] 写每日复投、周末、负收益与精度尾差场景：合资格10000.0000份、万份收益1.00产生1.0000份；同日重跑不再增加；负万份收益按同样精度扣减并通知，不把负收益强行变成0。
- [ ] 先运行失败用例，再实现自然日逐账户/来源资格快照、每日结转、账户内尾差结转、收益查询及发布；新增批次继承来源。收益快照日与执行日分开，补跑按历史资格；两张卡同日各自入账，残留未结尾差阻止解绑。
- [ ] 执行顺序按业务日期：先完成前一日期已到期交易，再建立本日收益资格快照和计息；补跑逐日推进，避免一次重启把数日收益都按当前持仓计算。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=MoneyIncomeIntegrationTest,PortfolioAccountingTest test`；同场景七日年化展示值改变不改变当日实际结转收益。

## 任务13 基金转换

**依赖：** 任务6至8。

**Files:** 创建 `conversion/ConversionService.java`、`web/src/views/investor/ConversionView.vue`、`web/src/views/admin/ConversionPairsView.vue`；SQL为 `V13__conversion_pairs_and_links.sql`；测试为 `conversion/ConversionIntegrationTest.java`。

**Interfaces:** 提供 `ConversionService.submit(long userId, long tradingAccountId, long sourceClassId, long targetClassId, BigDecimal shares, String requestKey): ConversionView`、`confirm(long conversionId): ConversionView`。只使用所选交易账户的来源批次，目标批次保留该账户、基金账户和来源卡。仅允许后台配置的同管理人净值产品组合，货币产品不参与。

- [ ] 写支持关系、两侧价格及事务一致性用例：无费转出300.0000份、源净值1.20、目标净值1.80得到200.0000份；钱包不变；目标净值缺失时不能先扣源份额。
- [ ] 先运行失败用例，再实现FIFO冻结、来源持有期费用、申购费率补差、关联订单和一个事务内的转出转入确认；新批次承接转出份额成本，不能把转入金额记为外部入金。
- [ ] 完成选账户转换、报价、资格提示、申请、处理中与完成页面；默认卡改B不把A的转换份额移给B。同账户因换卡存在不同历史来源时，目标份额按来源明细形成批次、承接成本和授权链，不能丢失来源。取消释放来源冻结，确定失败恢复冻结，补跑不能产生第二个目标批次；有转换在途不能解绑。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=ConversionIntegrationTest,PortfolioAccountingTest test`；有费转换核对资产减少等于转换费用及记录的舍入差额。

## 任务14 暂停终止与清盘

**依赖：** 任务9至13。

**Files:** 创建 `lifecycle/FundLifecycleService.java`、`lifecycle/LiquidationService.java`、`web/src/views/admin/FundLifecycleView.vue`；SQL为 `V14__liquidation_events_and_items.sql`；测试为 `lifecycle/LiquidationIntegrationTest.java`。

**Interfaces:** 提供 `FundLifecycleService.changeRestrictions(long classId, TradingRestrictions restrictions, String reason): void`、`terminate(long classId, String reason): void`、`LiquidationService.execute(long eventId): JobRunView`。TradingRestrictions分别包含申购、赎回、估值发布、支付的暂停状态；清盘事件保存最终每份分配额。

- [ ] 写只暂停申购、只暂停赎回、已有订单与停止定投用例；终止后不能新增申请；未完成订单、在途资金或未结分红收益存在时拒绝执行清盘。
- [ ] 先运行失败用例，再实现限制与恢复、明确原因和生效时间、终止前订单处置、按剩余份额发放清盘金额与份额核销。
- [ ] 写A1000.0000份、B500.0000份、分配额1.05、无待处理业务时分别回款1050.00和525.00并核销各自份额；付款按来源账户/卡，受阻时保留清盘应收。重复执行不重复支付；清盘后记录仍可查询，款项未支付不能解绑。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=LiquidationIntegrationTest,PortfolioAccountingTest test`；后台不能任意修改最终分配额后重跑已完成账户；份额核销后待支付清盘款仍计入账户资产，不能因收款卡受阻消失。

## 任务15 运营后台与信息服务

**依赖：** 任务1至14。

**Files:** 创建 `operations/NotificationService.java`、`operations/OperationsQueryService.java`、`operations/ExportService.java`、`operations/CorrectionService.java`、`web/src/views/investor/NotificationsView.vue`、`HelpView.vue`、`web/src/views/admin/DashboardView.vue`、`AuditView.vue`；SQL为 `V15__notifications_and_operations.sql`；测试为 `operations/OperationsIntegrationTest.java`。

**Interfaces:** 提供 `NotificationService.create(long userId, String sourceType, String sourceId, String title, String message): void`、`OperationsQueryService.dashboard(LocalDate from, LocalDate to): DashboardView`、`ExportService.orders(long userId, LocalDate from, LocalDate to, OutputStream output): void`；`CorrectionService.correct(CorrectionCommand command): CorrectionView`，command保存原记录、差额、原因和操作者，不删除原记录。

- [ ] 写通知去重、导出越权、CSV公式注入和账务更正场景；同事件一条通知；A不能导出B记录；以`= + - @`开头的非数值文本安全转义；只有ADMIN可提交有理由的更正。
- [ ] 先运行失败用例，再串联交易受理、确认、支付、定投失败、分红和清盘通知；站内消息作为默认渠道，不依赖短信或邮件服务。
- [ ] 完成公告帮助、按客户/交易账户/来源卡的记录导出、按日期统计、异常查询、任务错误详情、操作员权限与审计。导出保留来源卡脱敏标识、收款目标和换卡授权；日志不记录真实完整卡号。OPERATOR负责产品数据和批处理，ADMIN负责账户权限、换卡改付和更正；不制作任意可配置权限系统。
- [ ] 所有已成交账务更正必须追加关联流水、再次对账并通知；不在后台提供任意SQL执行或直接改余额功能。
- [ ] 验证 `mvn -f server/pom.xml -Dtest=OperationsIntegrationTest test`；每一个范围内功能都有可到达的页面入口、真实接口和持久化结果，无静态占位按钮。

## 任务16 自动运行与上线验收

**依赖：** 任务1至15。

**Files:** 创建 `fund/SimulatedMarketDataService.java`、`deploy/compose.yaml`、`deploy/server.Dockerfile`、`deploy/web.Dockerfile`、`deploy/nginx.conf`、`deploy/backup.ps1`或`deploy/backup.sh`（按部署服务器选一个）、`.env.example`、`docs/deployment.md`、`docs/acceptance.md`、`web/e2e/full-business.spec.ts`；测试为 `clearing/ProductionSchedulerTest.java`。CI配置在选择仓库托管服务后按同一命令添加，不引入第二套构建步骤。

**Interfaces:** 提供 `SimulatedMarketDataService.publishDue(LocalDate date): JobRunView`；生产任务调用已有 `runDue`，先生成应发布的模拟净值和自然日货币收益，再处理到期业务。演示时钟只在demo/test配置启用；生产配置不能接受外部时间推进。

- [ ] 写自动运行和重启场景：固定样本和日期生成相同模拟数据；同类别同日期仅发布一次；真实时钟下到期订单可以无人点击而完成；重启后补跑不重复记账。
- [ ] 实现明确标为合成数据的每日市场数据源，以基金代码、日期和固定种子确定数据，使用固定算法并留存已发布结果；后台人工导入只可替换未发布草稿。净值在所属开放日15:10后发布，前一自然日货币收益在00:10后结转，不能提前发布未来价格。对配置分红事件的产品，生成器在除息日扣除每份分红一次，保存除息事件ID，人工导入的除息净值不再扣减。公开用户页面标明模拟来源。
- [ ] 完成空库迁移、首次管理员初始化、按说明一次性导入模拟基金及交易日历、生产配置与开发配置分离、镜像构建、同域反向代理、HTTPS配置说明、健康检查、日志轮转、登录限速和密码重置留痕；管理员初始密码由部署环境提供。缺日历停止并提示，日历覆盖期结束前30天显示维护提醒，不能悄悄把未知日期当开放日。
- [ ] 建立每24小时异机备份、保留最近7个每日备份、旧版本应用与迁移记录。演练在新数据库恢复备份，并比较钱包合计、订单数、持仓合计和关键任务进度。数据库迁移回退采用已验证的前向修复或恢复方案，不能只回退应用镜像就宣称数据恢复。
- [ ] 运行 `mvn -f server/pom.xml verify`、`npm --prefix web run typecheck`、`npm --prefix web run test -- --run`、`npm --prefix web run build`、`npm --prefix web run test:e2e`、`docker compose -f deploy/compose.yaml config`。端到端测试使用独立数据库，不清理公开用户的数据；测试后端真实MySQL事务，不用H2替代涉及锁与精度的测试。
- [ ] 在 `web/e2e/multi-card.spec.ts` 覆盖一客户两卡、多TA开户、选卡买入、默认变更、多卡赎回、退款分红、停用解绑和有授权换卡改付；卡A收款受阻且已有在途时可完成受控更换并支付，不能要求坏卡先到账导致死锁。
- [ ] 在拟上线环境执行完整业务验收与恢复演练，记录版本、失败项和修复结果，再完成上线交付。备份核对逐账户余额、来源批次、绑定历史及改付授权完整保留。域名、服务器和运行密钥由实际部署时提供，本计划不自动采购或发布。

## 执行纪律

每个任务的测试先覆盖真正可能出错的业务条件，确认失败后再实现，随后运行该任务验证命令。普通布局和文案调整不制作与实现重复的单元测试；金融公式、账务事务、权限、任务去重及恢复必须有验证。

每个任务完成后审查所改变的文件、接口与数据迁移，验证正常路径及失败分支，再进入依赖它的任务。开始实施时初始化版本管理；每个独立业务交付形成一个可回溯提交，不在本轮创建空代码或提交文档之外的文件。

前端可以在接口类型固定后并行开发；基金产品和展示可与扩展业务页面并行。订单、钱包、份额批次、清算和收益统计不能由多个任务同时改动其共享定义而不协调。发生范围变更时先更新设计和任务验收条件。

## 最终交付清单

- [ ] 用户端和后台页面、完整API与数据迁移；12个标注模拟的基金样本；客户、多卡、多交易账户、TA基金账户关系完整。
- [ ] 核心及扩展业务可操作：申购、赎回、撤单、认购、定投、两类分红、货币收益、转换和清盘。
- [ ] 自动日常处理、异常恢复、逐账户资金份额对账、通知公告、CSV导入导出与审计；默认卡、解绑、换卡授权及改付业务可演示。
- [ ] 通过业务、权限、重启恢复和全流程验证的记录；没有未解释的余额或份额差异。
- [ ] 部署、备份恢复、升级和管理员操作说明；上线环境完成一次恢复演练。
- [ ] 课程材料：需求说明、业务流程、数据关系图、接口说明、测试报告与答辩演示脚本。

## 参考依据

业务结构来自项目内《股票基金业务.pdf》，重点为第2、24、39至45页；其中历史示例不直接作为现行统一交易规则。设计草案已经明确本项目的简化参数和范围。

普通交易规则可参考[景顺长城基金常见问题](https://www.igwfmc.com/main/khfw/service-5.html)，费用公式可参考[嘉实基金费率计算器](https://www.jsfund.cn/Services/cn/jsp/calc/index_new.jsp)，分红含义可参考[易方达基金投资者教育](https://edu.efunds.com.cn/c/17/17886.shtml)。这些是业务依据，不意味着平台接入这些机构或复制其全部规则。

多账户结构参考[申万菱信网上交易规则](https://www.swsmu.com/contents/2020/3/31-978c2f501e84428c86bf721ce46c32a8.html)，回款路径参考[证监会销售办法第二十条](https://www.csrc.gov.cn/csrc/c106256/c1653806/content.shtml)，受控同银行换卡参考[泰康基金银行卡说明](https://www.tkfunds.com.cn/contents/2022/11/17-4686227112f74da0adfc02e767ecc971.html)。具体机构存在不同限制；同银行换卡和本项目模拟账户规则为明确的选定范围，不是全行业统一限制。

技术版本与部署依据：[Spring Boot系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[Node.js版本状态](https://nodejs.org/en/about/previous-releases)、[Vue文档](https://vuejs.org/guide/introduction.html)、[MySQL文档](https://dev.mysql.com/doc/refman/8.4/en/)、[Docker Compose文档](https://docs.docker.com/compose/)、[Playwright文档](https://playwright.dev/docs/intro)。初始化项目时锁定已验证的补丁版本；依赖升级另做兼容性验证。
