# 基金业务助手专属知识维护

`docs/assistant-knowledge/`中的24篇短文是首版专属知识的唯一输入目录，每篇第一行是一个常见问题，后文是一份完整答案。本说明放在目录外，避免被当成知识条目。目录仅含平台操作、模拟交易规则和常见问题，不含演示口令、个人账户资料、运行凭据或真实投资推荐。

内容依据是当前发布基线已实现的`web/src/pages/Help.vue`、`README.md`及后端服务：AccountService（开户、测评、多卡）、FundService（产品参数、日期与适当性）、TradeService和FundMath（交易、确认、费用）、CardChangeService（换卡）、SipService、ConversionService、OfferingService、DividendService、MoneyIncomeService、LifecycleService、PortfolioService，以及后台Trading.vue、业务编号与申请文件协议。课程需求与演示指南只作辅助，不把未合并安全改造计划中的目标功能当成当前能力。

维护时先核对实际代码、产品参数与页面名称。15:00、T+1、T+3属于默认或示例参数，具体以发布产品及订单记录为准。不要把教学示例写成全部产品的固定承诺，不给出真实基金推荐或收益保证。助手没有订单查询权限，不应声称读取了用户实时余额、持仓或执行结果；回答只解释流程，用户自行在基金页面核对并提交。

每篇保持一个常见问句和一份可独立检索的答案，尽量不超过1000个中文字。需要增补时使用小写字母、数字和短横线文件名，以`.md`结尾；不要放README、子目录、链接、压缩包、账号表或其他资料。全文须为UTF-8文本，且知识总量保持小规模。

在最终基金源码已提交且工作树干净时执行：

```sh
python deploy/package-assistant-knowledge.py
```

脚本只读取当前Git提交追踪的该目录文件，拒绝符号链接、非规范名称、超过32篇、单篇64KiB或合计256KiB的输入。输出`.runtime/runtime/fund-assistant-knowledge-<commit12>.tar.gz`及同名`.json`交付元数据，不覆盖同版本既有文件。包根只有`manifest.json`与平铺Markdown文件，manifest绑定规范仓库、完整提交和逐篇SHA-256；旁置元数据另记录整包SHA-256。

知识包与基金主运行包必须来自同一个最终源码提交；不能用知识分支的中间提交冒充最终发布版本。两份运行产物通过现有运维仓库私有交付、核验、合并与发布，不进入源码Git。运维负责安全解包并导入问答服务的基金专属范围，知识导入或模型不可用不得阻止基金基本业务。

验证packager可运行`python -m unittest discover -s deploy/tests -p test_assistant_knowledge.py -v`。这些测试验证交付身份、边界及摘要，不代替真实知识导入、检索或模型回答验收。
