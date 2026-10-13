# 本地平台 API 说明

统一JSON字段camelCase；金融数值输出十进制字符串。API成功直接返回对象或数组（不套额外data）；错误通常为 `{code,message,requestId}`。默认服务8080，Vite5173代理/api。用户与管理员在同一Vue网站。原版本验收见acceptance.md，清算升级验证状态见requirements-test-matrix.md。本文件记录当前接口约定，不表示性能或全回归已验收。

## 登录

- GET /api/auth/csrf -> {token,headerName}；前端请求启动先取token，所有写请求发送该header，登录后重新获取。
- POST /api/auth/login {username,password} -> 用户对象；POST /api/auth/logout。
- POST /api/auth/register {username,password,displayName}；GET /api/me -> {id,username,displayName,role,riskLevel,riskExpiresAt,defaultTradingAccountId,opened}。
- POST /api/account/open {}；POST /api/risk-assessments {answers:[1..5的10项]}；POST /api/account/password {oldPassword,newPassword}。
- 本地初始化 demo / Demo12345!、admin / Admin12345!；初始化只在local/demo配置，公开部署必须环境变量设管理员密码。

## 基础和核心API

- GET /api/funds -> [{id,code,name,type,manager,registrarId,riskLevel,state,nav,navDate,purchaseRate,redemptionRate,minPurchase,minRedemption,purchaseOpen,redemptionOpen}]
- GET /api/fund-classes/{id}；GET /api/fund-classes/{id}/history -> [{date,nav,incomePerTenThousand,annualYield}]。
- GET /api/favorites -> 基金ID数组；PUT/DELETE /api/favorites/{fundId}。
- GET /api/bank-cards -> 卡/交易账户联表数组：{id,bankName,simNumber,holderName,status,receiveState,tradingAccountId,accountNo,bindingVersion,available,frozen,isDefault}。
- POST /api/bank-cards {bankName,holderName,requestKey} -> 创建模拟卡及交易账户，后台生成SIM标识。
- PUT /api/bank-cards/{id}/default {}；PATCH /api/bank-cards/{id}/status {status:"ACTIVE"|"DISABLED"}；POST /api/bank-cards/{id}/unbind {}。
- GET /api/trading-accounts -> 卡联表；GET /api/fund-accounts -> [{id,accountNo,registrarName,registrarId,status}]。
- POST /api/trading-accounts/{id}/top-ups {amount,requestKey}；POST /api/trading-accounts/{id}/withdrawals同样；GET /api/cash-entries?tradingAccountId=可选 -> 流水数组。
- POST /api/trades/quotes {type:"PURCHASE"|"REDEMPTION",fundClassId,tradingAccountId,amount或shares} -> {estimated,nav,navDate,fee,netAmount,shares,applicationDate}。
- POST /api/orders {type,fundClassId,tradingAccountId,bankCardId,bindingVersion,amount或shares,requestKey} -> 订单。
- GET /api/orders?tradingAccountId=可选 -> [{id,orderNo,applicationNo,confirmationNo,currentConfirmationId,type,fundClassId,fundName,tradingAccountId,bankName,sourceCardId,status,amount,shares,nav,fee,netAmount,applicationDate,confirmationDate,paymentDate,reason,groupId,createdAt}]。
- GET /api/orders/{id}；POST /api/orders/{id}/cancel {requestKey}；POST /api/redemption-groups {fundClassId,allocations:[{tradingAccountId,shares}],requestKey}。
- GET /api/portfolio -> {totalAssets,totalIncome,available,frozen,pending,invested,positions:[{fundClassId,fundName,tradingAccountId,bankName,shares,frozenShares,availableShares,cost,nav,marketValue,profit}],accounts:[...]}

## 扩展API

- POST /api/offering-orders，字段与申购同；GET /api/offering-orders -> 订单数组。
- GET/POST /api/sip-plans，创建{fundClassId,tradingAccountId,bankCardId,bindingVersion,amount,frequency:"WEEKLY"|"MONTHLY",day,startDate,requestKey}；PATCH /api/sip-plans/{id} {status:"ACTIVE"|"PAUSED"|"TERMINATED",或amount/day/账户参数}；GET /api/sip-runs。
- GET /api/dividends；GET /api/dividend-preferences -> [{fundClassId,mode}]；PUT /api/dividend-preferences/{id} {mode:"CASH"|"REINVEST"}。
- GET /api/money-income -> 日收益；GET /api/conversion-pairs -> [{sourceClassId,targetClassId}]。
- POST /api/conversions {tradingAccountId,sourceClassId,targetClassId,shares,requestKey}；GET /api/conversions。
- POST /api/card-change-requests {tradingAccountId,bankName,holderName,reason,requestKey}；GET /api/card-change-requests。
- GET /api/notifications；PATCH /api/notifications/{id} {read:true}；GET /api/announcements。
- GET /api/exports/orders；GET /api/exports/cash-entries -> 安全CSV。

## 后台API

- GET /api/admin/dashboard -> {users,accounts,funds,orders,pendingOrders,totalAssets,jobs,pendingPayments}。
- GET /api/admin/users；PATCH /api/admin/users/{id} {enabled}；GET /api/admin/accounts；GET /api/admin/orders；GET /api/admin/positions；GET /api/admin/audit。
- GET /api/admin/funds；POST /api/admin/funds {code,name,type,manager,registrarId,riskLevel,state,purchaseRate,redemptionRate,minPurchase,minRedemption,purchaseOpen,redemptionOpen}；PATCH /api/admin/funds/{id} 字段同上。
- POST /api/admin/navs {fundClassId,date,nav}；GET /api/admin/navs；POST /api/admin/navs/import CSV multipart。
- GET /api/admin/trading-calendar；POST /api/admin/trading-calendar {date,open}。
- GET /api/admin/jobs 为既有任务汇总历史；GET /api/admin/jobs/context 返回业务时钟/真实时间。POST /api/admin/jobs {type:"CONFIRM"|"PAY"|"SIP"|"MONEY"|"MARKET"|"ALL",date,requestKey} 改为异步提交，受理返回202与{runId,state}，不再同步返回成交汇总。后续GET /api/admin/clearing-runs/{runId}查询结果。
- GET /api/admin/payouts；GET /api/admin/card-change-requests；POST /api/admin/card-change-requests/{id}/approve {reason}；PATCH /api/admin/bank-cards/{id}/receive-state {state:"RECEIVABLE"|"BLOCKED",reason}。
- POST /api/admin/offerings/{fundId}/finalize {outcome:"ESTABLISHED"|"FAILED",endDate,initialNav,interestRate,requestKey} -> 202 {runId,state}。
- GET/POST /api/admin/dividends，创建{fundClassId,recordDate,exDate,paymentDate,perShare}；POST /api/admin/dividends/{id}/snapshot {requestKey}、POST /api/admin/dividends/{id}/execute {requestKey} -> 202 {runId,state}。
- GET/POST /api/admin/money-income，写入{fundClassId,date,incomePerTenThousand,annualYield}。
- GET/POST /api/admin/conversion-pairs {sourceClassId,targetClassId,enabled}。
- POST /api/admin/funds/{id}/terminate {reason}仍同步修改产品状态；POST /api/admin/liquidations {fundClassId,perShare,date,reason,requestKey} -> 202 {runId,state}；GET /api/admin/liquidations。
- GET /api/admin/reconciliation -> {ok,issues:[...]}; GET/POST /api/admin/announcements {title,body}。
- GET /api/admin/clock -> {dateTime,demo}；POST /api/admin/clock {dateTime:"yyyy-MM-ddTHH:mm:ss"}仅demo可用。

## 清算运行 CLR-001

- POST /api/admin/clearing-runs {type,businessDate,parameters:{...},requestKey} -> 202 {runId,state}。businessDate也兼容date；标准任务类型沿用ALL/MARKET/CONFIRM/PAY/SIP/MONEY，专用募集、分红、清盘、重清和文件入口提交同一通道。
- GET /api/admin/clearing-runs -> 最近200条运行；GET /api/admin/clearing-runs/{id} -> {runId,type,businessDate,state,nodes,progress,counts,times,result,reason,...}。progress为{completed,total,percent}，counts为{processed,waiting,failed}；times包括业务时钟与真实创建/开始/结束时间。节点自身有状态、数量和原因。
- 普通任务与文件需要OPERATOR或ADMIN；重新清算与清盘需要ADMIN，实际执行时再次校验发起人的角色及停用状态。后台线程使用持久运行的身份和业务时钟。

状态包括QUEUED、RUNNING、WAITING、COMPLETED、NO_WORK、FAILED、PARTIAL及REJECTED_QUEUE_FULL。PARTIAL表示某些独立处理项成功、另有失败，不能按全部成功理解；REJECTED_QUEUE_FULL是规格REJECTED的队列满细分。队列满响应429，包含可查询的runId和拒绝状态；该请求键保留原拒绝记录，稍后重提需新键。QUEUED/RUNNING的计数为0不能据此推断NO_WORK。

标准任务中的MARKET对应规格的IMPORT_MARKET；ALL顺序执行行情、确认、扩展处理、付款同步及对账。封账与导出从申请文件专用入口提交EXPORT运行，进度中区分SEAL_APPLICATIONS与EXPORT_APPLICATIONS；不要求在任务类型菜单再选同义操作。SIP与MONEY按既有业务独立扫描。自动扫描和人工提交共用单工作线程及最多4个等待任务，实时池8连接、清算池2连接；两池连接限额不能单独证明共享MySQL的CPU/磁盘性能达标。

requestKey在同一发起人下永久幂等，改变参数复用键会409；活动范围重复提交会合并，新键在原运行终态后可再扫描当天新业务。运行中断由启动恢复处理未完成节点，已提交效果保持幂等。页面需要先等待运行完成，再推进下一步业务日期或核对资产。

## 编号与确认历史 NUM-001

applicationNo为申请归属开放日加6位序列；confirmationNo为本轮处理开放日加6位序列。两类独立每日全平台序列，从000001开始，超过999999拒绝；原orderNo、来源ID及请求键保持。重复受理返回原申请号，等待确认不产生成功确认号。认购与定投生成的申购沿用订单申请号，转换使用同一申请序列。

- GET /api/orders/{id}/confirmations 仅允许订单所属投资者。
- GET /api/conversions/{id}/confirmations 仅允许转换所属投资者。
- 历史行包含confirmationNo、roundId、confirmationNumberDate、effectiveDate、marketDate、processedAt、status、previousConfirmationId及适用的金额/份额/净值/费用字段。status为ACTIVE或REVERSED；新确认保留原资产业务有效日，编号日期和真实处理时间分别保存。

旧数据编号为空时显示“历史申请编号未采集”或“历史确认编号未采集”，不能替换成UUID来声称满足新编号，也不能伪造历史确认。旧记录仍可读；缺可靠受理、确认或扩展依赖快照时，重清或申请文件报告具体来源并阻断。

## 行情修订与重新清算 RCL-001

- POST /api/admin/nav-revisions {fundClassId,marketDate,nav,reason,requestKey} -> {revisionSetId}。仅ADMIN，先保存候选集合，不立即更改有效净值或资产。
- POST /api/admin/nav-revisions/import，multipart字段file、reason、requestKey -> {revisionSetId}。UTF-8 CSV表头fundClassId,date,nav，最大5MB；所有行在同一修订集合处理。
- GET /api/admin/nav-revisions -> 最近200条集合。
- POST /api/admin/reclearing/previews {revisionSetId}、GET /api/admin/reclearing/previews/{id} -> {previewId,revisionSetId,scopeVersion,state,affectedAccounts,changes,changesTotal,nextCursor,blockers,runId}。首次最多200条差异。
- GET /api/admin/reclearing/previews/{id}/items?afterId=游标 -> {items,nextCursor,total}，固定每页200条；游标是已读差异位置。
- POST /api/admin/reclearing/runs {previewId,scopeVersion,requestKey} -> 202 {runId,state}，后续查清算运行。上述预演和执行均仅ADMIN。

READY允许提交，BLOCKED列出来源类型/ID、交易账户、原因与shortage并保留旧有效结果。预演或提交期间发生资产/依赖变化会拒绝过期预演，页面清除旧预演后重新生成。执行保存原申请、来源账户/卡/TA、规则快照和有效日，重放相关后续交易、转换、分红、货币收益；定投不再生成另一笔申请。原请求份额不足、可回收现金不足、已清盘或历史证据缺失均不得透支、跨卡补扣或部分发布。资产、冲正、新结果及有效行情指针一起提交；旧记录保留查询。

## 模拟TA申请文件 EXP-001

- POST /api/admin/application-exports {date,registrarId,requestKey} -> 202 {exportId,runId,state}；date兼容businessDate。
- GET /api/admin/application-exports -> 最近200条导出；GET /api/admin/application-exports/{id}返回状态、businessDate、registrarId、protocolVersion、snapshotVersion、deadlineBusinessTime、completedBusinessTime、completedBeforeDeadline、fileName、rowCount、excludedCancelledCount、amountTotal、sharesTotal、checksum、fileBytes、businessSummary、createdAt、startedAt、completedAt、errorReason等。封账边界存于application_days，不是导出详情的sealedBusinessTime字段；文件字节数及各业务汇总持久保存，分段耗时在运行result的snapshotMillis、fileMillis、elapsedMillis中查询。
- GET /api/admin/application-exports/{id}/download仅完成文件可用，带会话鉴权返回UTF-8 CSV附件；文件未完成、缺校验和或仅.part文件时拒绝。
- POST /api/admin/application-exports/{id}/regenerate {requestKey} -> 202 {exportId,runId,state}；复用原exportId和不可变快照，校验和应一致。所有查询/提交/下载/重导均需OPERATOR或ADMIN，投资者无权获取全平台文件。

按申请归属开放日与源TA生成，不按当前SUBMITTED筛选；协议和17列顺序见[模拟TA申请文件协议](interfaces/mock-ta-application-file-v1.md)。当前实现使用所涵盖产品最晚截止后5分钟封账，最晚截止后30分钟作为演示完成目标；未到封账时点提交返回409并说明时间条件，非法开放日拒绝。截止自动扫描通过同一EXPORT通道处理各TA，受理/撤单与封账共享交易日门禁。

快照按申请号稳定游标每页200条，字段从受理快照读取；确认后申购/认购仍导出原申请金额且申请份额为空。撤单排除并计数，转换同时保留源/目标TA；缺可靠来源不得静默漏行。文件按UTF-8无BOM及CRLF流式写受控目录的.part，校验后原子发布并保存SHA-256。用户输入不得指定本地文件路径。个人GET /api/exports/orders仍为本人历史CSV，与本接口用途不同。

## 模块职责

server包含账户、产品、交易、清算与扩展业务，web包含投资者端与后台界面，scripts提供Windows本地运行，deploy提供后续部署模板。金额和份额由后端核算，来源与幂等标识保留到数据库。

## 后端共享接口

另提供 `GET /api/admin/cash-entries`、`GET /api/admin/corrections` 和管理员专用 `POST /api/admin/corrections {originalEntryId,availableDelta,reason,requestKey}`。更正原记录不变，新增关联差额。完整路径以CoreController与AdvancedController的映射为准。

核心提供public Db类：`rows(String sql,Object...args): List<Map<String,Object>>`（camelCase键，BigDecimal字符串）；`one(String sql,Object...args): Map<String,Object>`（无记录报404）；`scalar(String sql,Object...args): BigDecimal`（空为0）；`insert(String sql,Object...args): long`（生成ID）；`update(String sql,Object...args): int`；`decimal(Object value): BigDecimal`静态；`money(BigDecimal value): BigDecimal`静态；所有SQL参数绑定。

核心提供 public AccountService：`userId():long`、`requireRole(String role):void`、`audit(String action,String objectType,long objectId,String reason):void`、`notifyUser(long userId,String type,String sourceId,String title,String message):void`、`linkFundAccount(long userId,long tradingAccountId,long fundId):long`、`credit(long tradingAccountId,String sourceType,String sourceId,BigDecimal amount):void`、`clock():LocalDateTime`。

核心提供 TradeService：`submit(long userId,Map<String,Object> input):Map<String,Object>`（PURCHASE/REDEMPTION，事务及去重）、`cancel(long userId,long orderId):Map`、`payout(long userId,long tradingAccountId,long sourceCardId,String sourceType,String sourceId,BigDecimal amount,LocalDate dueDate):long`（新增应付款）、`addLot(long userId,long tradingAccountId,long fundId,long sourceCardId,long fundAccountId,BigDecimal shares,BigDecimal cost,LocalDate date,String sourceType,String sourceId):long`、`reserveLots(long userId,long tradingAccountId,long fundId,BigDecimal shares):List<Map<String,Object>>`（返回lotId、shares、cost、sourceCardId、fundAccountId、acquiredDate，事务中调用）、`consumeLots(List<Map<String,Object>> allocations,String sourceType,String sourceId):void`、`releaseLots(List<Map<String,Object>> allocations):void`。

扩展业务不得用用户总余额扣款；需要基础schema表的确切列由核心在创建后及时通知根代理。共享数据表允许sql追加字段，但不得把历史来源卡覆盖成默认卡。
