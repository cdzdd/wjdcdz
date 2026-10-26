# 阿里云首次公网试部署

2026年10月25日完成首次部署。本记录对应原计划任务16的云端试运行阶段；业务程序以f550884b3c476fa15b697503c95ff07a3cd62752为基线，另含本次HTTP请求编号兼容修复与导出目录权限修正。资金、银行卡、TA和产品均为教学模拟。

2026年10月26日首次试运行结束。网站已停止，阿里云控制台确认实例i-f8zg3qmdh6ojg7trhx60及关联系统盘d-f8zg3qmdh6ojg7tqvo56释放成功（1个实例、1个盘、0个快照）。按用户要求不备份，原盘内数据库、导出文件和SQL备份随盘删除。本机代码、部署包与科创算力包保留。

释放后刷新河源云盘列表为空；释放前关联快照和策略均为0，快照服务未开通，公网地址为随实例回收的固定IP。本次未发现需要另行删除的关联计费资源。免费VPC、交换机、安全组与RAM角色保留，无关既有OSS不在清理范围。这台实例及系统盘已释放，释放前的用量仍可能延迟出账，本记录不代表账号级零费用。

## 下次4核8GiB部署的已确认偏好

2026年10月26日用户要求：下次创建4核8GiB服务器时，开通主机全面防护和ECS文件备份基础版，尽量保持两项附加服务在免费额度内。本次仅保存偏好与部署建议，没有创建服务器、开通服务或设置定时任务。届时仍需核对实际领取资格、权益与收费页面。

- 主机防护仅绑定目标1台ECS，安全态势扫描范围仅该实例；系统漏洞修复控制每天1次免费授权，超额修复不自动执行。免费额度不是费用硬封顶，超额按量收费；不能满足免费条件时先说明费用变化。
- 文件备份按账号全地域共享100GiB块存储额度核算，包括其它仍保留备份的已释放实例。下次磁盘容量先按项目实际需求配置；创建、扩容或增加备份实例前复核总容量，不为了用满额度而扩盘。备份安排在低峰，数据库另外做一致性SQL导出与恢复验证。
- 下次退役时，除ECS/磁盘外还须核对云安全付费绑定和文件云备份；若不保留备份，则取消文件备份并确认备份版本删除，不能只暂停任务。原实例已释放的事实不变，本段描述未来要求。

多项目部署建议：单台ECS、每个独立项目一个Compose项目、各用独立子域名，共用Nginx HTTPS入口。示例fund.example.com、tools.example.com仅作占位，不代表已有域名或DNS。

- 每项目独立目录、环境变量/密钥、数据卷、网络、数据库及数据库账号，默认分别运行各自数据库容器；同一项目的前端及/api保持同源。公共入口仅连接项目web入口，数据库不加入公共入口网络、不映射公网端口。
- 统一入口对外提供80/443，各项目web绑定不同的127.0.0.1端口；需要管理连接时保留受限来源。每项目独立设置会话Cookie名称，不设置父域Domain，HTTPS下启用Secure/HttpOnly并保留CSRF校验、严格跨域策略；适配时优先使用__Host-前缀。Cookie Path或不同端口不能作为项目安全隔离边界。
- 基金项目下次适配需复核：deploy/compose.yaml固定项目名与默认80端口；application.yml的COOKIE_SECURE默认false；双层代理的X-Forwarded-Proto可信转发；当前JVM堆上限不等于容器内存上限。增加容器CPU/内存限制、日志轮转，并逐个项目验证部署/重启/登录/退出互不串扰。
- 单台宿主机、磁盘和公共入口仍是共同故障点。不同信任方的项目或需要更强安全隔离时采用独立ECS。若继续使用河源等内地地域，对外提供网站服务前按要求完成ICP备案；购买服务器前核查该计费方式的备案资格。本次未进行备案或购买域名。

参考：[主机防护权益](https://help.aliyun.com/zh/ecs/user-guide/explanation-of-comprehensive-protection-benefits-for-ecs-hosts)、[文件备份权益](https://help.aliyun.com/zh/ecs/user-guide/file-backup-essential-edition-benefit-description)、[Compose网络](https://docs.docker.com/compose/how-tos/networking/)、[Cookie规则](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie)、[ICP备案说明](https://help.aliyun.com/zh/ecs/support/what-do-i-do-if-i-cannot-access-a-website-hosted-on-an-instance)。

## 首次部署资源记录

原公网IP为`47.113.221.167`，现已停止使用，不应继续访问或向其提交原账号凭据。首次试运行通过HTTP验证，未配置域名与HTTPS。

| 项目 | 首次部署值（实例与系统盘已释放） |
| --- | --- |
| 地域 | 华南2（河源），cn-heyuan |
| 实例 | i-f8zg3qmdh6ojg7trhx60 |
| 配置 | 经济型e，ecs.e-c1m2.large，2 vCPU / 4GiB |
| 系统盘 | d-f8zg3qmdh6ojg7tqvo56，40GiB ESSD Entry |
| 操作系统 | Alibaba Cloud Linux 3.2104 LTS，x86_64 |
| 算力包 | 科创包，购买时额度2000元，到期日2027年4月25日 |
| 创建页参考费用 | 实例及默认盘合计0.231660元/小时，实际以账单及包抵扣明细为准 |
| 程序目录 | /opt/hengsheng/hengsheng-fund-simulation |
| 运行方式 | Docker26.1.3、Compose2.27，MySQL8.4.11、Java21、Nginx1.28 |

试运行时网站公开TCP80。SSH22仅允许阿里云Workbench管理网段100.104.0.0/16；默认Windows3389规则已替换。数据库3306和后端8080没有映射到宿主机公网。安全组为sg-f8zg3qmdh6ojg7twcmew。公网流量按CDT规则处理，套餐页显示国内20GB/月免费额度，超额另付。

试运行管理员使用独立随机初始口令，未启用本地demo账号或已知默认口令。原账号对应的云端数据库已删除；本机忽略目录.runtime/cloud-deploy中的历史账号文件不得提交Git，也不得再向原IP使用。重新部署时应生成新的部署口令与访问账号。

## 2026年10月25日实际验证（历史证据）

- 本地Java打包及前端类型检查、生产构建通过；云端实际构建了复制预构建产物的运行镜像。没有把本机无Docker写成镜像构建通过。
- 云端导入的MySQL、Nginx、Temurin镜像来自官方Docker Hub，linux/amd64的config和各层摘要经过校验，实际Docker Image ID与官方config摘要一致。云机直连Docker Hub及官方加速器未能拉取所需镜像，因此通过Workbench传输了已校验的离线包。
- 三个容器已启动，健康检查通过。后台以prod运行，关闭demo数据种子和可推进时钟；实际查询24个成功迁移，与源码24个SQL一致，最高编号V25（历史未使用V7）。导出命名卷已验证fund用户可写。
- 公网首页、/healthz及/api/auth/csrf返回200，真实浏览器管理员登录、产品列表和后台页面正常显示。
- 31项实际公网接口检查通过：鉴权、prod状态、开户测评、双卡独立资金、充值和申购幂等、14位申请号、重复键内容校验、撤单、默认卡切换、冻结余额、无提前持仓及账务对平。
- 真实公网浏览器暴露了HTTP下crypto.randomUUID不可用的问题，影响请求编号生成和部分页面初始化。已修复为优先原生UUID、HTTP下用crypto.getRandomValues生成UUIDv4，保留安全随机性。回归先复现原TypeError，再取得18项前端测试与生产构建通过；新资源index-CInWnzHk.js已实际发布，修复期间数据库和后端保持运行。
- 修复后另用真实公网浏览器登录测试投资者，打开双卡账户页、选择A卡、预估并提交100元模拟申购，得到申请20261026000004，再从交易记录撤单成功。A卡恢复可用99000.00/冻结1000.00，B卡保持可用500.00/冻结500.00；新页面脚本错误为0。未用仅API成功替代页面验收。
- 两笔主申购申请号为20261026000001、20261026000002，申请日2026-10-26，状态SUBMITTED。实际时间尚未到确认日，本次没有推进生产时钟，也没有声称云端已验收确认、赎回、到账或重清。
- 本次只初始化两家课程模拟TA、CLOUD001/CLOUD002教学债券、模拟交易日历和历史净值；未上传本机客户数据库。日历按教学工作日设置，需要在覆盖期结束前维护，不能作为真实交易所交易日历。
- 已生成首次SQL备份，路径见下文。初始空载观测约为后端209MiB、MySQL457MiB、Nginx3MiB，系统盘约6.6GiB已用、31GiB可用；这不是压力测试结论。

端口检测结合安全组、Docker发布端口、宿主机监听与实际协议响应。本机代理会使单纯socket.connect对不可达地址也返回成功，因此没有把该结果当作端口开放证据。

本次公网HTTP验证不等同正式网站全部上线验收。域名、HTTPS、异机定时备份、完整业务周期和云端恢复演练尚待后续完成。历史本地回归及性能证据保留在相应验收报告，不替代本次云环境结论。

## 重新部署后的管理参考

以下命令供未来重新部署后参考，不能用于连接已释放实例。应在新实例中部署项目、确认程序目录和配置后执行。首次试运行所需服务关联角色AliyunServiceRoleForECSWorkbench已由用户确认授权；通过Workbench连接无需开放SSH给全网。

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

停止容器会保留数据，也不会停止ECS计费。实例不再需要时，应根据是否保留数据的要求，在阿里云释放实例并核查关联计费资源；套餐到期或额度用尽后实例不会自动释放。不要使用down -v清理问题，否则会删除数据库卷。

备份：

```sh
sh deploy/backup.sh
```

首次试运行曾在服务器程序目录生成.runtime/backups/fund_sim-20261025-224614.sql，最终备份为fund_sim-20261025-231503.sql。两份均未下载到本机，现已随系统盘释放而删除，不能用于恢复原云端数据。未来如需保留新部署的数据，应将备份复制到另一台受信任机器并演练恢复。

## 构建与升级记录

预构建程序包SHA256为6930660657c5150b918dbafa0dc29fcd59cd61d8954ed6517b06640e0f25831a；官方基础镜像离线包SHA256为31e06fdc84e231867e2a10d375726e93aa1831386a792c187e64b8465657225f。包、逐文件清单、实际公网验收报告和私有账号文件保存在本机.runtime/cloud-deploy；无凭据的进度沿用原任务16台账。

本次运行镜像预先创建并授权/data/ta-exports给非root的fund用户，避免新命名卷导致TA文件无法写入。相同修正已补回deploy/Dockerfile.server，供后续从源码构建使用。原多阶段构建的Maven/Node部分未在云端重跑，业务JAR和前端是本地预构建后上传。

首次程序包保留原始构建清单；HTTP兼容修复另用web-http-fix.tar.gz更新网站，SHA256为df7e49ca42e963d5c91ebc4e4c0a2fa678dfd2216d369d1406547d1bdcfd4635。复用首次离线程序包时必须同时应用该前端修复，或直接从本次分支的源码重新构建。试运行时曾保留hengsheng-web:before-http-fix镜像供回退，现已随原系统盘删除。

后续升级先备份数据库和导出文件，再保留当前版本镜像及.env，按README构建和启动。只回退应用镜像不能回退已执行的数据库迁移。涉及HTTPS、端口、实例规格或额外付费产品时，应按实际目标单独配置和验收。
