# 本地平台 API 说明

统一JSON字段camelCase；金融数值输出十进制字符串。API成功直接返回对象或数组（不套额外data）；错误为 `{code,message,requestId}`。默认服务8080，Vite5173代理/api。用户与管理员在同一Vue网站。实现与验收说明见acceptance.md。

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
- GET /api/orders?tradingAccountId=可选 -> [{id,orderNo,type,fundClassId,fundName,tradingAccountId,bankName,sourceCardId,status,amount,shares,nav,fee,netAmount,applicationDate,confirmationDate,paymentDate,reason,groupId,createdAt}]。
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
- GET /api/admin/jobs；POST /api/admin/jobs {type:"CONFIRM"|"PAY"|"SIP"|"MONEY"|"MARKET"|"ALL",date}；结果{processed,waiting,failed,details}。
- GET /api/admin/payouts；GET /api/admin/card-change-requests；POST /api/admin/card-change-requests/{id}/approve {reason}；PATCH /api/admin/bank-cards/{id}/receive-state {state:"RECEIVABLE"|"BLOCKED",reason}。
- POST /api/admin/offerings/{fundId}/finalize {outcome:"ESTABLISHED"|"FAILED",endDate,initialNav,interestRate}。
- GET/POST /api/admin/dividends，创建{fundClassId,recordDate,exDate,paymentDate,perShare}；POST /api/admin/dividends/{id}/snapshot {}；POST /api/admin/dividends/{id}/execute {}。
- GET/POST /api/admin/money-income，写入{fundClassId,date,incomePerTenThousand,annualYield}。
- GET/POST /api/admin/conversion-pairs {sourceClassId,targetClassId,enabled}。
- POST /api/admin/funds/{id}/terminate {reason}；POST /api/admin/liquidations {fundClassId,perShare,date,reason}；GET /api/admin/liquidations。
- GET /api/admin/reconciliation -> {ok,issues:[...]}; GET/POST /api/admin/announcements {title,body}。
- GET /api/admin/clock -> {dateTime,demo}；POST /api/admin/clock {dateTime:"yyyy-MM-ddTHH:mm:ss"}仅demo可用。

## 模块职责

server包含账户、产品、交易、清算与扩展业务，web包含投资者端与后台界面，scripts提供Windows本地运行，deploy提供后续部署模板。金额和份额由后端核算，来源与幂等标识保留到数据库。

## 后端共享接口

另提供 `GET /api/admin/cash-entries`、`GET /api/admin/corrections` 和管理员专用 `POST /api/admin/corrections {originalEntryId,availableDelta,reason,requestKey}`。更正原记录不变，新增关联差额。完整路径以CoreController与AdvancedController的映射为准。

核心提供public Db类：`rows(String sql,Object...args): List<Map<String,Object>>`（camelCase键，BigDecimal字符串）；`one(String sql,Object...args): Map<String,Object>`（无记录报404）；`scalar(String sql,Object...args): BigDecimal`（空为0）；`insert(String sql,Object...args): long`（生成ID）；`update(String sql,Object...args): int`；`decimal(Object value): BigDecimal`静态；`money(BigDecimal value): BigDecimal`静态；所有SQL参数绑定。

核心提供 public AccountService：`userId():long`、`requireRole(String role):void`、`audit(String action,String objectType,long objectId,String reason):void`、`notifyUser(long userId,String type,String sourceId,String title,String message):void`、`linkFundAccount(long userId,long tradingAccountId,long fundId):long`、`credit(long tradingAccountId,String sourceType,String sourceId,BigDecimal amount):void`、`clock():LocalDateTime`。

核心提供 TradeService：`submit(long userId,Map<String,Object> input):Map<String,Object>`（PURCHASE/REDEMPTION，事务及去重）、`cancel(long userId,long orderId):Map`、`payout(long userId,long tradingAccountId,long sourceCardId,String sourceType,String sourceId,BigDecimal amount,LocalDate dueDate):long`（新增应付款）、`addLot(long userId,long tradingAccountId,long fundId,long sourceCardId,long fundAccountId,BigDecimal shares,BigDecimal cost,LocalDate date,String sourceType,String sourceId):long`、`reserveLots(long userId,long tradingAccountId,long fundId,BigDecimal shares):List<Map<String,Object>>`（返回lotId、shares、cost、sourceCardId、fundAccountId、acquiredDate，事务中调用）、`consumeLots(List<Map<String,Object>> allocations,String sourceType,String sourceId):void`、`releaseLots(List<Map<String,Object>> allocations):void`。

扩展业务不得用用户总余额扣款；需要基础schema表的确切列由核心在创建后及时通知根代理。共享数据表允许sql追加字段，但不得把历史来源卡覆盖成默认卡。
