# 恒生基金业务模拟平台

用于大学课程的场外公募基金销售业务模拟。平台使用模拟银行卡、模拟余额和演示净值，围绕开户、适当性、交易受理、TA确认、赎回回款及后台清算演示业务流程。

本地前后台保留原模拟业务，并增加业务编号、异步清算、行情重清和模拟TA申请文件。本轮后端194项功能检查、前端16项单元测试与6条实际浏览器业务流程通过，演示库已升级并核对资产与时钟一致。升级证据及验证范围见[清算升级验收记录](docs/clearing-upgrade-acceptance.md)和[需求验收矩阵](docs/requirements-test-matrix.md)，原版本记录保留在[本地运行验收记录](docs/acceptance.md)。

## Windows 本地启动

需要 **Java 21、Maven 3.9+、Node.js 22+**。项目使用 Vue 3 / Vite 6、Spring Boot 4、MySQL 8.4。本机已有 Node 22.14 可以直接使用。

在项目根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Local.ps1
```

也可以双击 `scripts\Start-Local.cmd`。首次运行会下载官方 Windows 便携版 MySQL 8.4.11、生成随机数据库口令、初始化两个项目数据库，然后构建后端和前端。无需 Docker，也不注册 Windows 服务。

| 服务 | 地址 |
| --- | --- |
| 网站 | http://127.0.0.1:5173 |
| 后端 | http://127.0.0.1:8080 |
| 后端健康检查 | http://127.0.0.1:8080/actuator/health |
| 项目 MySQL | 127.0.0.1:13306 |

现有 MySQL 5.7 服务和 3306 端口不会被修改。项目数据库 `fund_sim` 用于演示，初次安装创建 `fund_sim_test` 用于测试；本机清算升级验收使用新建的 `fund_sim_upgrade_test`，原测试库保留。测试脚本读取本地配置中的测试库名称与连接地址，只允许这两个明确的测试库，拒绝演示库。数据库程序、持久化数据、凭据、日志和备份都位于 Git 忽略的 `.runtime/`。口令保存在 `.runtime/local-env.json`，请勿把此目录提交或公开。

停止项目：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Stop-Local.ps1
```

也可以双击 `scripts\Stop-Local.cmd`。停止脚本只操作 PID 文件与命令行共同确认属于本项目的进程。数据保留，下次启动继续使用。开发时使用 `Stop-Local.ps1 -KeepDatabase` 可以保留项目数据库；只启动数据库使用 `Start-Local.ps1 -DatabaseOnly`。

本地演示账号（仅 local / demo 配置）：

| 角色 | 用户名 | 密码 |
| --- | --- | --- |
| 投资者 | demo | Demo12345! |
| 管理员 | admin | Admin12345! |

## 演示顺序

具体账号、逐卡金额、业务时钟、页面操作和预期结果见[课程演示示例指南](docs/demo-guide.md)。首次演示可直接按其中的双卡申购、确认、赎回与到账主线操作。新增四项要求按[清算升级演示指南](docs/clearing-demo-guide.md)操作，涵盖申请与确认编号、后台进度、净值修订预演、旧确认历史、申请文件下载和同快照重导。

1. 使用投资者账号登录，完成开户与风险测评，添加两张模拟银行卡。
2. 在不同交易账户充值模拟资金，查看基金与费率，提交申购。提交后先显示等待确认。
3. 使用管理员账号维护净值与交易日，执行确认批次；投资者查看持仓、成交净值、费用和原交易账户来源。
4. 按交易账户申请赎回，执行确认和回款批次，检查份额、冻结金额、到账状态与流水。
5. 在扩展业务中演示定投、认购成立或失败、分红、货币基金收益、同账户基金转换，以及换卡审核和清算。

业务日期与批次执行用于课程演示。T+n 按产品规则和交易日计算；页面上的报价为预估，确认批次才产生最终成交结果。参考资料和设计位于 `docs/`。

后台任务提交返回任务ID，业务结果以任务完成状态为准。任务中心可查询排队、运行、等待、失败、节点进度和真实运行时间，刷新或离开后可按任务ID继续查询；运行中零计数仍显示处理中。募集结果、分红快照/执行和清盘也通过后台通道提交，需等任务完成后再查看资产或推进业务时钟。

后台任务历史统计本次成功、等待和失败的处理项数，包含普通交易、定投、转换、分红和货币收益；它不是累计订单数。“处理情况”只显示非零数量，空批次显示“本次无需处理”。自动任务每五分钟扫描一次，到达产品最晚截止后另提交申请文件；没有到期或新增业务时显示“无待处理业务”。真实运行时间与模拟业务时钟分别显示；旧记录保留原统计，未采集的真实时间标为“旧记录未采集”。

申请编号和每轮成功确认编号采用 `yyyyMMdd` 加6位序列，两类各自每日全平台唯一。旧UUID订单号继续可查；没有采集完整快照或确认历史的旧记录明确提示缺失，不能拿这些记录直接重清或生成完整申请文件。重清只允许完整可重放范围，原份额不足、已支付资金不能回收、已清盘或输入版本过期时会阻断，保留旧有效结果。

申请文件按申请归属日和源登记机构生成全平台模拟CSV，个人交易记录的“导出CSV”仍只导出本人历史。文件协议见[模拟TA申请文件协议](docs/interfaces/mock-ta-application-file-v1.md)，正式课程规格见[需求规格正文](docs/requirements-specification.md)与[Word提交件](docs/需求规格说明书.docx)。Word封面小组、姓名按要求留空；视觉分页核验状态见验收矩阵。

## 测试

先启动独立数据库，再把本地凭据加载到当前 PowerShell（以下命令不会打印口令）：

```powershell
.\scripts\Start-Local.ps1 -DatabaseOnly
$localConfig = Get-Content .\.runtime\local-env.json -Raw | ConvertFrom-Json
$env:DB_URL = $localConfig.testJdbcUrl
$env:DB_TEST_URL = $localConfig.testJdbcUrl
$env:DB_USER = $localConfig.user
$env:DB_PASSWORD = $localConfig.password
$env:SPRING_DATASOURCE_URL = $localConfig.testJdbcUrl
$env:SPRING_DATASOURCE_USERNAME = $localConfig.user
$env:SPRING_DATASOURCE_PASSWORD = $localConfig.password
mvn -f .\server\pom.xml test
Push-Location .\web
npm ci
npm run test
npm run build
Pop-Location
```

后端数据库测试使用本地配置中的独立测试库，避免清空演示库。新清算流程的真实浏览器验收可运行 `scripts/Test-ClearingAcceptance.ps1`，在18880/15173的临时服务完成后自动停止。前端端到端测试也可先启动完整平台，再在 `web/` 运行 `npx playwright install chromium`，设置 `$env:E2E_API = '1'` 后运行 `npm run test:e2e`，启用实际账户与交易测试；这会在所连接的库中创建演示记录。

本地演示时钟初始为2026年10月12日，可在管理员后台推进。资金和记录持久保存，重启不会清空；推进后请以后台显示的业务日期填写净值、募集结果及分红日期。货币收益在归属自然日结束后确认，任务日期D处理截至D-1的收益。

后台现金更正必须关联原入账或提现流水、填写理由并追加差额，原记录不修改；冻结、份额和完整交易重算不通过现金更正入口处理。

运行环境检查：

```powershell
.\scripts\Test-LocalRuntime.ps1
# 同时检查应用构建及 HTTP 服务：
.\scripts\Test-LocalRuntime.ps1 -IncludeApps
```

此检查会验证 MySQL 版本和独立端口、项目数据库连接以及停止脚本拒绝误杀无关 PID。检查过程中会停止前端进程，`-IncludeApps` 会重新启动完整平台。

## 本地备份与恢复

备份使用项目内 MySQL 8 的 `mysqldump`，不使用系统安装的 MySQL：

```powershell
.\scripts\Backup-Local.ps1
```

文件保存在 `.runtime/backups/fund_sim-yyyyMMdd-HHmmss.sql`。备份文件包含账户和交易数据，应与凭据一样保管。

恢复会覆盖备份中同名表的数据；先停止应用、保留数据库，再指定要恢复的备份：

```powershell
.\scripts\Stop-Local.ps1 -KeepDatabase
.\scripts\Restore-Local.ps1 -BackupFile .\.runtime\backups\fund_sim-时间戳.sql
.\scripts\Start-Local.ps1
```

`Backup-Local.ps1` 和 `Restore-Local.ps1` 的 `-TestDatabase` 参数仅用于测试库备份恢复验证。停止或升级时保留 `.runtime/mysql-data` 及配置文件；不要靠删除数据目录解决启动问题。

## Linux Docker Compose 部署

2026年10月25日的阿里云首次公网试运行已于10月26日结束，实例和系统盘已释放。历史验收及重新部署后的管理参考见[云端部署记录](docs/deployment.md)；本机代码、部署包及科创算力包保留。

服务器需要 Docker Engine 与 Compose v2。将代码部署到 Linux 后，在项目根目录执行：

```sh
cp .env.example .env
# 编辑 .env，替换数据库、数据库 root、管理员三个独立口令。
docker compose --env-file .env -f deploy/compose.yaml config --quiet
docker compose --env-file .env -f deploy/compose.yaml up -d --build
docker compose --env-file .env -f deploy/compose.yaml ps
```

Compose 包含 MySQL 8.4、Java 后端与 Nginx 静态网站。只对外暴露网站端口，数据库和后端走容器内部网络；`mysql_data` 命名卷保存数据库。后端使用 `prod` 配置，管理员口令通过 `APP_ADMIN_PASSWORD` 设置。首次初始化后修改环境变量不会自动更改已有数据库用户或平台管理员密码。

`WEB_PORT` 默认 80。通过域名和 HTTPS 反向代理提供访问时，把 `.env` 中 `COOKIE_SECURE` 改为 `true` 后重建后端容器；代理应转发原始协议与主机信息。普通 `docker compose ... down` 保留数据卷；不要使用 `down -v`，除非明确要删除整个部署数据。

健康检查按数据库、后端、网站顺序启动。查看日志：

```sh
docker compose --env-file .env -f deploy/compose.yaml logs --tail=100 server
```

Linux 备份与恢复：

```sh
sh deploy/backup.sh
sh deploy/restore.sh /absolute/path/to/backup.sql
```

备份同样保存在 `.runtime/backups`。恢复脚本先停应用、导入所指定 SQL，再重新启动应用；MySQL 容器和数据卷保留。应在可信服务器上保管 SQL 文件，并定期把备份复制到另一台机器。

## 常见启动问题

- **端口占用**：检查 13306、8080、5173；脚本会报错，不会自动结束占用者。
- **数据库启动失败**：检查 `.runtime/logs/mysql-error.log`。Windows MySQL 需要 Microsoft Visual C++ 运行库，本机已安装；缺少时应先安装微软官方运行库。
- **构建失败**：确认 `java -version` 为 21、`mvn -version` 能运行、`node --version` 为 22+，首次安装需要访问 Maven、npm 和 MySQL 官方下载站。
- **应用启动失败**：检查 `.runtime/logs/backend-stdout.log` 与 `backend-stderr.log`；前端日志是 `web-stdout.log` 和 `web-stderr.log`。
- **拷贝项目目录后数据库路径失效**：`.runtime/mysql.ini` 和 `.runtime/local-env.json` 记录绝对路径，迁移时一并更新路径，保留原数据并先备份。

Windows 原生启动、数据库连接和备份可在本机验证。Linux 容器配置需要在安装 Docker 的 Linux 主机上进一步验证镜像构建、启动和恢复。
