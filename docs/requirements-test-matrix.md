# 清算升级需求与验收矩阵

基线：2026年10月13日需求规格；矩阵更新：2026年10月14日。用于把老师四项新要求、保留业务、源码、测试和实际演示对应起来。需求状态“接受”表示目标采用，不能据此认定实现或测试通过。

本矩阵已静态核对源码和测试名称，逐项区分执行证据与待记录结果。原版本的acceptance.md不能替代新增四项验收；隔离浏览器、真实服务和性能测试各自支持其覆盖范围。

## 四项新增要求

| 需求与验收点 | 当前实现依据 | 对应测试证据入口 | 本轮结果 |
| --- | --- | --- | --- |
| NUM-T01 14位编号、跨客户/卡/业务唯一、并发及日切 | BusinessNumberService；TradeService、OfferingService、ConversionService；V14 | BusinessNumberIntegrationTest.dailyNumbersAreUniqueAcrossAccountsAndTypes、concurrentNumbersAreUnique、kindsAndNextDayHaveIndependentSequences | 最终回归通过：并发与双卡14位编号；对应9项编号测试通过 |
| NUM-T02 重试/等待不增有效号、事务失败无孤立记录 | BusinessNumberService、ConfirmationService；业务事务内登记 | retryAndWaitingDoNotCreateAnotherNumber、failedTransactionDoesNotLeaveEffectiveOrphanRecords | 最终回归通过：等待/重试/回滚与永久请求键检查 |
| NUM-T03 非工作日、上限、旧数据及重清新号 | ConfirmationService、ReclearingService；订单与转换确认历史页 | cutoffAndNonWorkingDaysUseApplicationBusinessDate、sequenceLimitNeverWraps、legacyRecordsRemainReadable；ReclearingIntegrationTest.consecutiveRoundsAndRepeatedExecutionPreserveOneActiveResult | 最终回归通过：闭日确认等待、序列上限、旧号保留及重清新号；历史缺失字段保持空值 |
| CLR-T01 异步、有界队列、请求幂等、再扫描、启动恢复 | ClearingService、ClearingController、ScheduledJobs、AdvancedController；V15 | ClearingJobIntegrationTest.legacyPostIsAcceptedAndRequestKeyIsPermanent、completedScopeCanBeScannedAgainWithNewRequestKey、manualAndSchedulerCoalesceAndQueueFullIsExplicit、startupRecoveryResumesPersistedRunningTask | 最终回归通过：202/429、角色撤权、持久键、再扫描、启动恢复；重复运行采用新产品真实锁屏障 |
| CLR-T02 连接限额、正确事务路由、失败回滚、稳定分页 | ClearingDataSourceConfig、ClearingExecutionContext、FundService；实时8/清算2 | DataSourceRoutingIntegrationTest.batchPoolCannotConsumeRealtimeQuota、batchFailureRollsBackWalletPositionAndEntries、routingCannotChangeInsideAnExistingTransaction；BatchPagingIntegrationTest.marketIncludesEveryProductAcrossThreePages、oneBadProductDoesNotLoseCommittedCountsOrStopLaterProducts | 最终回归通过：实时8/清算2、事务路由及回滚、多页处理及部分失败计数 |
| CLR-T03 同基金无关账户与并行性能门槛 | ReclearingService账户范围门禁；共享MySQL | ReclearingSameFundConcurrencyTest.unrelatedAccountCanSubmitWhilePublisherHoldsAffectedAccount；NFR-T02正式测量 | 同基金无关账户实际并发通过；当前RC三组60秒短档P95及活动窗口门槛通过，完整长档未测 |
| RCL-T01 双卡申购、已付/未付款、净差额与历史 | NavRevisionService、ReclearingService、ConfirmationService；V16、V17 | ReclearingIntegrationTest.twoCardPurchaseCanBeRecleared、paidPayoutUsesOnlyNetDifferenceAndCannotPayAgain、paidPayoutAfterWithdrawalBlocksWholeScope | 最终回归通过：双卡、已付净差额、资金不足阻断、有效确认历史 |
| RCL-T02 后续赎回/FIFO/转换/分红/收益依赖 | ReclearingService及V20扩展输入快照 | laterFifoRedemptionAndPendingFreezeUseRebuiltLots、conversionAndItsTargetRemainInTheAccountClosure、historicalRightsUseOriginalEffectiveDate；ReclearingRightsIntegrationTest；ReclearingDependencyIntegrationTest | 最终回归通过：FIFO多批转换、虚拟批次及分红引用、现金/再投资/收益/尾差；旧缺失输入阻断 |
| RCL-T03 份额/现金不足、旧证据缺失、版本漂移及清盘边界 | ReclearingService.preview/apply；blockers与scopeVersion | fixedLaterRedemptionCannotBorrowFutureShares、unverifiableCashBaselineIsBlockedBeforePublication、previewBecomesStaleAfterAssetWrite、newAffectedAccountInvalidatesMembership；源码清盘/缺分红收益快照校验 | 最终回归通过：不足、不可核验基线及预演漂移；已清盘按明确边界阻断 |
| RCL-T04 集合原子发布、失败、重启、重复请求 | NavRevisionService集合；ReclearingService原子发布与门禁 | multiRowMarketRevisionIsAppliedAsOneSet、failureBeforePublicationLeavesOriginalState、consecutiveRoundsAndRepeatedExecutionPreserveOneActiveResult；ReclearingRecoveryIntegrationTest.killedPublisherRollsBackThenFreshJvmRecoversSameRunOnce | 最终回归通过：多行集合、原子失败回滚与真实新JVM恢复；同请求一次生效 |
| EXP-T01 四业务/定投、全平台源TA、撤单排除 | ApplicationExportService、TradingDayGate；V18、V19、V21 | ApplicationExportIntegrationTest.dailyExportIsAnAsynchronousAuthorizedTask；ApplicationExportSnapshotIntegrationTest.allAcceptedBusinessesKeepOriginalInputAndSourceTa、moreThanOnePageKeepsEveryApplicationSorted | 最终回归通过：四业务、定投等价行、源TA分组、撤单及401条分页 |
| EXP-T02 确认后原申请、来源卡不变、同快照重导 | 受理快照与application_export_items；generate/regenerate | sameSnapshotRegenerationHasSameChecksumAfterRenameCardChangeAndConfirmation、missingAcceptanceSourceFailsWithSpecificSourceInsteadOfPublishingIncompleteFile、missingOrderFundAccountCannotSilentlyDropRow | 最终回归通过：原申请字段、同快照SHA一致、缺来源明确失败；受理后源/目标代码保护 |
| EXP-T03 格式、空文件、权限、截止竞态与失败保护 | 固定17列、UTF-8无BOM、CRLF、.part原子发布 | protocolEmptyFileHasNoBomFixedHeaderNameAndDeadline、fieldsWithCommaQuoteAndNewlineAreCsvEscaped、exportControllerRequiresAdminOrOperatorForDetailsAndDownloads；ApplicationExportRecoveryTest | 既有导出及实际在途封账20项通过：RR漏单RED→READ_COMMITTED GREEN；权限、空文件和故障恢复保持 |
| EXP-T04 1万/10万行性能与内存 | ApplicationExportService游标分页/BufferedWriter；V21申请号索引 | ApplicationExportPerformanceTest.measureRealSourcesAndLargePrebuiltSnapshotFiles；scripts/Test-ApplicationExportPerformance.ps1 | 当前RC真实源专项：1万1.879秒/10万10.017秒，采样增量堆约24MiB；111000源行、内容及SHA一致 |

上表测试类路径均位于server/src/test/java/com/hengsheng/fund；生产类位于server/src/main/java/com/hengsheng/fund。验收点完整语义以[正式规格](requirements-specification.md)及[文件协议](interfaces/mock-ta-application-file-v1.md)为准。

## 保留业务与非功能范围

| 范围 | 证据入口 | 限制或待验事项 |
| --- | --- | --- |
| BIZ-T01至BIZ-T04 登录开户、适当性、多卡、产品、申赎/撤单/付款 | CoreIntegrationTest、CoreRulesTest、BusinessNumberIntegrationTest；live-workflows.spec.ts双卡及来源主流程 | 最终回归功能检查及原5条实际服务流程通过；最后整合结果见clearing-upgrade-acceptance.md |
| BIZ-T05 认购与定投 | AdvancedIntegrationTest、OfferingSipRegressionTest；管理员募集异步浏览器用例 | 必须等待runId对应任务完成，不依据202认定成立/退款 |
| BIZ-T06 分红与货币收益 | MoneyLifecycleIntegrationTest、MoneyExitIntegrationTest、ReclearingRightsIntegrationTest、ReclearingDependencyIntegrationTest | 历史权益有效日、负收益、退出尾差及重清不可重复 |
| BIZ-T07 转换 | AdvancedIntegrationTest、ReclearingIntegrationTest转换闭包；Benefits确认历史 | 原账户及源/目标TA保持，跨TA配对仍需验证 |
| BIZ-T08 换卡、现金更正、清盘 | AdvancedIntegrationTest、MoneyExitIntegrationTest；原demo-guide.md扩展主线 | 现金更正不替代重清；已完成清盘重放为延后范围 |
| BIZ-T09 查询、导出、通知、审计 | JobHistoryIntegrationTest、platform.spec.ts、live-workflows.spec.ts | 个人CSV与全平台TA文件权限/用途不同 |
| NFR-T01/T04 环境、升级和资产兼容 | V14至V25迁移；Backup-Local.ps1、独立测试库资产核对 | 演示库12项迁移按序通过，资产及业务时钟保持、现金/份额/冻结/成本对账0差异，实际接口检查27项通过；本地网站与健康检查就绪。没有整库恢复验收 |
| NFR-T02 性能 | 固定机器/版本/堆/配额，基线、清算并行、文件并行各3次 | P95、错误率、耗时、内存和对账须同时保留；完整正式测量待记录 |
| NFR-T03 精度与账务 | FundMathTest、AdvancedAmountsTest、CoreRulesTest、重清各场景 | 金额2位、份额4位、净值6位、费率8位；旧＋冲正＋新加总一致 |
| NFR-T05/T07 容错、错误与恢复 | ClearingJobIntegrationTest、ReclearingRecoveryIntegrationTest、ApplicationExportRecoveryTest | 实际重启/故障证据需与事务内失败用例分别记录 |
| NFR-T06 界面 | web/src/domain.test.ts；platform.spec.ts异步、预演、下载、确认历史用例 | 隔离mock通过只能支持展示逻辑，实际API流程另验 |
| NFR-T08 权限 | 后端角色/所属账户检查；导出详情/下载权限用例；操作员浏览器用例 | 普通用户不得后台修订/重清/下载全平台；操作员可标准任务/文件 |
| NFR-T09 课程交付 | 规格、接口、协议、本矩阵与演示指南 | 不发布凭据/账户文件；不把建议或未测项目写成通过 |

## 已有验证记录与本轮填写要求

父任务在2026年10月14日完成本轮前端验证：npm test的16个用例通过、npm run typecheck及npm run build通过、隔离Playwright 9项通过且6项真实服务用例跳过。该结果支持前端单元、类型、构建及模拟响应界面；实际服务角色与后台流程需独立记录。后续变更需保留对应命令、时间、版本、总数、失败/跳过和原因。

正式需求Word已保留老师模板结构和内容标题，封面小组/姓名按用户要求留空，结构检查可读取并核对需求编号及17列协议。bundled renderer未找到soffice.exe；独立原生Word自动化亦未完成页面导出，因此逐页视觉验收、目录页码及分页不能标为通过。

| 最终证据 | 记录位置 | 当前状态 |
| --- | --- | --- |
| 全部后端测试与各定向故障测试 | clearing-upgrade-acceptance.md；本机upgrade-final-backend-verify-5.log | 最终196项，0失败/0错误/2按需性能跳过，即194项功能检查通过；打包成功 |
| 前端单元、类型检查、构建及隔离浏览器 | 父任务本轮验证记录，2026-10-14 | 单元16/16；typecheck/build通过；隔离浏览器9通过、6真实服务跳过 |
| 实际服务角色、修订/预演/运行/历史、下载/重导 | clearing-upgrade-acceptance.md及本机浏览器证据 | 原5条两轮通过；新流程独立1项通过，0失败/0跳过，17字段/同SHA/阻断/权限均通过 |
| 性能三组对比与大文件来源/快照分段测量 | clearing-performance-report.md | 当前RC三组900请求、0技术错误，P95/活动窗口检查及全账务核对通过；RC真源10万行10.017秒/24MiB；108分钟完整档未测 |
| 演示库备份、迁移后多卡资产对账 | clearing-upgrade-acceptance.md及本机前后快照 | V14–V25全部迁移成功；23订单/18卡/8持仓、现金/份额/成本/时钟一致；逐账户及批次对账0差异 |
| Word逐页视觉及目录页码 | 文档QA记录 | 未完成；渲染受环境限制 |

## 浏览器人工验收

按[清算升级演示指南](clearing-demo-guide.md)使用admin和一个现场新注册客户。记录申请号、第一次确认号、修订集合ID、预演ID、runId、重清新确认号、导出ID和SHA-256；核对原金额/来源卡/有效日保持、旧确认REVERSED、新确认ACTIVE、双卡份额变化及同快照重导一致。出现BLOCKED或过期预演时，旧有效结果应保持，按具体原因重新预演；不能删历史或跨卡补扣以完成演示。
