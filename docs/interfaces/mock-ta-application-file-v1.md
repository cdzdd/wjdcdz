# 模拟TA交易申请文件协议

协议版本：`MOCK-TA-APPLICATION-1`。对应需求：`EXP-001`。本协议是课程项目采用的模拟接口约定，不是老师提供的行业文件标准，也不用于真实TA或银行传输。

## 1 文件范围与生成边界

按申请归属工作日 `application_date` 和源TA分组，覆盖全平台当日有效申购、赎回、认购、转换，以及定投生成的申购订单。截止前已撤销的申请不输出，记录排除数量。确认完成不会把原申请排除，历史重导不能按当前 `SUBMITTED` 状态筛选。

封账须晚于所涵盖产品的最晚受理截止时间。封账与申请受理使用同一准入边界：截止前开始但未提交的事务必须先明确提交并纳入快照，或明确回滚；不能在文件生成后静默遗漏。封账后受理的申请归入下一开放日。截止前的撤单须在快照中体现；导出过程中撤单、换卡或确认不得修改已封存行。

示例演示时点为产品15:00截止、15:05封账、15:30前完成；这些是课程建议，不适用于截止更晚的产品。实际配置应记录在导出结果中。

快照版本内按申请编号升序，输出来源唯一键为 `source_type + source_id`。同一快照重新生成应逐字节一致；重导使用原快照，不重新读取当前卡、金额或状态。改变快照边界须产生新版本并保留旧版本。

## 2 编码和命名

- UTF-8，无BOM。分隔符为英文逗号；每行采用CRLF，末行也有CRLF。
- 第一行为固定英文表头。编号、账户、TA和来源ID均为文本，保留所有前导零；禁止转为科学计数法或浮点值。不添加Excel公式或制表符前缀，读取方按文本列导入。
- 字段包含逗号、双引号或换行时用双引号包围；内部双引号写为两个双引号。其他字段可直接输出，空值输出空字段。
- 文件名：`applications_{yyyyMMdd}_TA{sourceTaId}_v{snapshotVersion}.csv`，例如 `applications_20261013_TA1_v1.csv`。一个源TA一个文件，转换仍在源TA文件中保留目标TA。
- 没有有效申请时仍输出只含表头及CRLF的文件，行数为0；没有申请的TA集合来自封账时的有效TA配置。
- 先写临时文件，记录行数、分业务汇总、字节数和SHA-256；校验通过后原子发布完成文件。失败或中断的临时文件不能作为完成文件下载。

## 3 固定字段顺序

| 序号 | 英文字段 | 含义及约束 |
| --- | --- | --- |
| 1 | protocol_version | 固定 `MOCK-TA-APPLICATION-1` |
| 2 | application_date | 申请归属工作日，`yyyy-MM-dd` |
| 3 | application_no | `yyyyMMdd`加6位每日序列，共14位文本；对应NUM-001 |
| 4 | business_type | `PURCHASE / REDEMPTION / OFFERING / CONVERSION`；定投为PURCHASE |
| 5 | trading_account_no | 原申请交易账户编号；不能用当前默认账户替换 |
| 6 | fund_account_no | 原申请源TA基金账户编号；转换为源基金账户 |
| 7 | source_fund_code | 申购、赎回、认购所交易基金代码；转换为源基金代码 |
| 8 | target_fund_code | 仅转换必填，其他业务为空 |
| 9 | source_ta_id | 原申请源基金TA标识，决定文件分组 |
| 10 | target_ta_id | 仅转换必填，允许与源TA不同，其他业务为空 |
| 11 | application_amount | 原申请金额，2位小数；仅申购、认购必填，赎回和转换为空 |
| 12 | application_shares | 原请求份额，4位小数；仅赎回、转换必填，申购和认购为空 |
| 13 | source_sim_card_no | 原申请来源模拟卡标识；不是真实银行卡号 |
| 14 | binding_version | 原申请卡绑定版本，正整数文本 |
| 15 | accepted_business_time | 受理业务时钟，`yyyy-MM-ddTHH:mm:ss`，Asia/Shanghai；不同于真实运行时间 |
| 16 | source_type | 普通/认购/定投生成订单为 `ORDER`，转换为 `CONVERSION` |
| 17 | source_id | 原数据库订单或转换ID，正整数文本；不与轮次拼接 |

金额和份额都是原申请事实。申购、认购的 `orders.shares` 可能在确认时变为结果份额，不能导出成申请份额。定投只输出生成的那笔申购，不另输出计划或执行记录。赎回/转换保留原请求份额，不能以最终金额或重清结果替代。CSV中不输出确认编号、费用或确认净值；它是申请文件，不是确认结果文件。

字段必须从受理快照获得：基金/TA关系、交易账户、来源卡、绑定版本、金额或份额、申请归属日、受理业务时间和来源ID。历史缺少可靠申请快照或业务编号时应报告具体来源并拒绝将该快照发布为完整文件，不能猜测字段、补造历史时间或静默漏行。

## 4 示例

下例为便于阅读显示的CSV；真实文件每行以CRLF结束。

```csv
protocol_version,application_date,application_no,business_type,trading_account_no,fund_account_no,source_fund_code,target_fund_code,source_ta_id,target_ta_id,application_amount,application_shares,source_sim_card_no,binding_version,accepted_business_time,source_type,source_id
MOCK-TA-APPLICATION-1,2026-10-13,20261013000001,PURCHASE,TR000001,FA000001,F000001,,1,,1000.00,,SIM000001,1,2026-10-13T10:00:00,ORDER,101
MOCK-TA-APPLICATION-1,2026-10-13,20261013000002,REDEMPTION,TR000001,FA000001,F000001,,1,,,100.0000,SIM000001,1,2026-10-13T10:01:00,ORDER,102
MOCK-TA-APPLICATION-1,2026-10-13,20261013000003,OFFERING,TR000002,FA000002,F000002,,1,,500.00,,SIM000002,2,2026-10-13T10:02:00,ORDER,103
MOCK-TA-APPLICATION-1,2026-10-13,20261013000004,CONVERSION,TR000001,FA000001,F000001,F000003,1,2,,50.0000,SIM000001,1,2026-10-13T10:03:00,CONVERSION,11
```

CSV转义示例：原文本 `SIM,"演示"` 输出为 `"SIM,""演示"""`。原文本含换行时整个字段带引号，字段内部换行保留。编号必须仍为文本 `20261013000001`，不得变为 `2.0261013E+13`。

空文件示例只有下面这一行及结尾CRLF：

```csv
protocol_version,application_date,application_no,business_type,trading_account_no,fund_account_no,source_fund_code,target_fund_code,source_ta_id,target_ta_id,application_amount,application_shares,source_sim_card_no,binding_version,accepted_business_time,source_type,source_id
```

## 5 调用和验收

输入为工作日、源TA范围、快照版本、协议版本、请求幂等键；具有导出管理权限的操作员或管理员可提交、重导和下载，后台调度以系统身份使用同一节点。接口实际路径以 `../implementation-contract.md` 为准，实现需引用本协议字段，不另定义同义字段。

输出包括导出ID、状态、快照边界、各TA文件名、有效行数、撤单排除数、分业务金额/份额汇总、SHA-256和真实完成时间。QUEUED/RUNNING状态不能因计数为0显示“无待处理业务”。磁盘不足、字段缺失、非法工作日、未到封账时点和权限不足必须有明确原因；未到时点可以等待，坏文件不能发布为完成。

验收EXP-T01：覆盖双卡、四种业务和定投生成订单，核对快照与文件一一对应，按源TA分组、目标TA正确，无重复/漏行，撤单排除计数正确。

验收EXP-T02：确认后再导出申购，申请金额不变、申请份额仍为空；赎回/转换份额不变。导出期间换卡不改变来源字段，重导字节及SHA-256一致。

验收EXP-T03：检查UTF-8无BOM、17列表头顺序、CRLF、CSV转义、文本编号、空文件及失败临时文件不可下载；截止前未提交受理事务的竞态可重复验证。

验收EXP-T04：课程建议测1万行10秒内、10万行专项60秒内完成，快照和文件生成阶段各自记录时间及堆峰值，批次增量堆不超过64MiB；流式生成不拼接完整文件到内存。测量软硬件、数据、版本和结果须保留，未测不能认定达标。
