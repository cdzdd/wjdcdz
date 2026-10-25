# 阿里云首次公网试部署

2026年10月25日完成首次部署。本记录对应原计划任务16的云端试运行阶段；业务程序以f550884b3c476fa15b697503c95ff07a3cd62752为基线，另含本次HTTP请求编号兼容修复与导出目录权限修正。资金、银行卡、TA和产品均为教学模拟。

## 访问与服务器

网站地址：http://47.113.221.167/ 。当前通过公网IP和HTTP访问，尚未配置域名与HTTPS。

| 项目 | 当前值 |
| --- | --- |
| 地域 | 华南2（河源），cn-heyuan |
| 实例 | i-f8zg3qmdh6ojg7trhx60 |
| 配置 | 经济型e，ecs.e-c1m2.large，2 vCPU / 4GiB |
| 系统盘 | 40GiB ESSD Entry |
| 操作系统 | Alibaba Cloud Linux 3.2104 LTS，x86_64 |
| 算力包 | 科创包，购买时额度2000元，到期日2027年4月25日 |
| 创建页参考费用 | 实例及默认盘合计0.231660元/小时，实际以账单及包抵扣明细为准 |
| 程序目录 | /opt/hengsheng/hengsheng-fund-simulation |
| 运行方式 | Docker26.1.3、Compose2.27，MySQL8.4.11、Java21、Nginx1.28 |

网站公开TCP80。SSH22仅允许阿里云Workbench管理网段100.104.0.0/16；默认Windows3389规则已替换。数据库3306和后端8080没有映射到宿主机公网。安全组为sg-f8zg3qmdh6ojg7twcmew。公网流量按CDT规则处理，套餐页显示国内20GB/月免费额度，超额另付。

管理员使用独立随机初始口令，未启用本地demo账号或已知默认口令。本机访问账号文件位于工作区忽略目录.runtime/cloud-deploy/access-accounts.txt，严禁提交Git。也可在网站新注册一个自己的模拟投资者账号。云端配置文件为程序目录下的.env，仅root可读；更改环境变量不会自动重置已存在的数据库或平台管理员密码。

## 本次实际验证

- 本地Java打包及前端类型检查、生产构建通过；云端实际构建了复制预构建产物的运行镜像。没有把本机无Docker写成镜像构建通过。
- 云端导入的MySQL、Nginx、Temurin镜像来自官方Docker Hub，linux/amd64的config和各层摘要经过校验，实际Docker Image ID与官方config摘要一致。云机直连Docker Hub及官方加速器未能拉取所需镜像，因此通过Workbench传输了已校验的离线包。
- 三个容器已启动，健康检查通过。后台以prod运行，关闭demo数据种子和可推进时钟；实际查询24个成功迁移，与源码24个SQL一致，最高编号V25（历史未使用V7）。导出命名卷已验证fund用户可写。
- 公网首页、/healthz及/api/auth/csrf返回200，真实浏览器管理员登录、产品列表和后台页面正常显示。
- 31项实际公网接口检查通过：鉴权、prod状态、开户测评、双卡独立资金、充值和申购幂等、14位申请号、重复键内容校验、撤单、默认卡切换、冻结余额、无提前持仓及账务对平。
- 真实公网浏览器暴露了HTTP下crypto.randomUUID不可用的问题，影响请求编号生成和部分页面初始化。已修复为优先原生UUID、HTTP下用crypto.getRandomValues生成UUIDv4，保留安全随机性。回归先复现原TypeError，再取得18项前端测试与生产构建通过；新资源index-CInWnzHk.js已实际发布，数据库和后端保持运行。
- 修复后另用真实公网浏览器登录测试投资者，打开双卡账户页、选择A卡、预估并提交100元模拟申购，得到申请20261026000004，再从交易记录撤单成功。A卡恢复可用99000.00/冻结1000.00，B卡保持可用500.00/冻结500.00；新页面脚本错误为0。未用仅API成功替代页面验收。
- 两笔主申购申请号为20261026000001、20261026000002，申请日2026-10-26，状态SUBMITTED。实际时间尚未到确认日，本次没有推进生产时钟，也没有声称云端已验收确认、赎回、到账或重清。
- 本次只初始化两家课程模拟TA、CLOUD001/CLOUD002教学债券、模拟交易日历和历史净值；未上传本机客户数据库。日历按教学工作日设置，需要在覆盖期结束前维护，不能作为真实交易所交易日历。
- 已生成首次SQL备份，路径见下文。初始空载观测约为后端209MiB、MySQL457MiB、Nginx3MiB，系统盘约6.6GiB已用、31GiB可用；这不是压力测试结论。

端口检测结合安全组、Docker发布端口、宿主机监听与实际协议响应。本机代理会使单纯socket.connect对不可达地址也返回成功，因此没有把该结果当作端口开放证据。

本次公网HTTP验证不等同正式网站全部上线验收。域名、HTTPS、异机定时备份、完整业务周期和云端恢复演练尚待后续完成。历史本地回归及性能证据保留在相应验收报告，不替代本次云环境结论。

## 日常管理

在阿里云ECS控制台选择河源地域和本实例，通过Workbench免密连接。首次使用所需服务关联角色AliyunServiceRoleForECSWorkbench已由用户确认授权；无需开放SSH给全网。

```sh
cd /opt/hengsheng/hengsheng-fund-simulation
docker compose --env-file .env -f deploy/compose.yaml ps
docker compose --env-file .env -f deploy/compose.yaml logs --tail=100 server
docker stats --no-stream
```

停止或恢复平台：

```sh
docker compose --env-file .env -f deploy/compose.yaml stop
docker compose --env-file .env -f deploy/compose.yaml start
```

停止容器会保留数据，也不会停止ECS计费。实例或套餐不再需要时，应先保存数据，再在阿里云管理实例和计费；套餐到期或额度用尽后实例不会自动释放。不要使用down -v清理问题，否则会删除数据库卷。

备份：

```sh
sh deploy/backup.sh
```

首次备份文件：.runtime/backups/fund_sim-20261025-224614.sql（相对于服务器程序目录）。备份包含账号、会话和模拟业务数据，保留在受保护目录；后续应复制到另一台受信任机器并演练恢复。本次未执行覆盖演示库的恢复操作。

## 构建与升级记录

预构建程序包SHA256为6930660657c5150b918dbafa0dc29fcd59cd61d8954ed6517b06640e0f25831a；官方基础镜像离线包SHA256为31e06fdc84e231867e2a10d375726e93aa1831386a792c187e64b8465657225f。包、逐文件清单、实际公网验收报告和私有账号文件保存在本机.runtime/cloud-deploy；无凭据的进度沿用原任务16台账。

本次运行镜像预先创建并授权/data/ta-exports给非root的fund用户，避免新命名卷导致TA文件无法写入。相同修正已补回deploy/Dockerfile.server，供后续从源码构建使用。原多阶段构建的Maven/Node部分未在云端重跑，业务JAR和前端是本地预构建后上传。

首次程序包保留原始构建清单；HTTP兼容修复另用web-http-fix.tar.gz更新网站，SHA256为df7e49ca42e963d5c91ebc4e4c0a2fa678dfd2216d369d1406547d1bdcfd4635。复用首次离线程序包时必须同时应用该前端修复，或直接从本次分支的源码重新构建。旧网站镜像已保留为hengsheng-web:before-http-fix；数据库无需因该修复回滚。

后续升级先备份数据库和导出文件，再保留当前版本镜像及.env，按README构建和启动。只回退应用镜像不能回退已执行的数据库迁移。涉及HTTPS、端口、实例规格或额外付费产品时，应按实际目标单独配置和验收。
