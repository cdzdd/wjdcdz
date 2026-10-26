# 交付 Ferrari Server 运维仓库

业务源码保留在 `cdzdd/wjdcdz`；`cdzdd/Ferrari_Server` 只登记基金平台的运行版本、Compose 配置、网络入口与资源。部署配置通过运维仓库 PR 合并后，由运维对话执行发布，合并不自动部署。

在干净且已提交的源码工作树中，使用 PowerShell、Java21/Maven、Node22/npm 和 Python3 执行：

```powershell
pwsh -File deploy/build-runtime.ps1
```

脚本构建 JAR 和前端生产文件，不运行数据库集成测试；测试应在发布前另行完成。输出 `.runtime/runtime/fund-<提交前12位>.tar.gz` 及旁置 manifest，终端返回包 SHA256。包根只有 `manifest.json`、`server/Dockerfile`、`server/app.jar`、`web/Dockerfile`、`web/nginx.conf` 与 `web/dist/**`。不含业务源码、真实环境变量、数据库、备份和账号文件，不提交 Git。

manifest 记录精确 `sourceCommit`、`sourceRepository`、逐文件 SHA256、`baseImages` 和目标镜像名 `ferrari-fund-server:<12位SHA>` / `ferrari-fund-web:<12位SHA>`。运维应在接入 PR 中记录包 SHA256和该源码版本；服务器校验包后，分别用 `server/`、`web/` 为上下文构建运行镜像，无需安装 Maven 或 Node。Linux amd64 基础镜像来自官方 Temurin、Nginx；离线导入时另核验镜像来源与摘要。本机没有 Docker 时，打包成功不代表镜像已在云机运行。

运行环境由运维仓库 Compose 注入。下列设置无需改变本地开发默认行为：

| 环境变量 | 默认值 | 运维用法 |
| --- | --- | --- |
| `SESSION_COOKIE_NAME` | `SESSION` | 每项目设置独立名称；HTTPS 可用 `__Host-fund-session` |
| `COOKIE_SECURE` | `false` | HTTPS 设 `true`；私有 HTTP 验收保持 `false` |
| `FORWARD_HEADERS_STRATEGY` | `NONE` | 仅在后端只能经受信任代理到达且转发头由入口覆盖时设 `FRAMEWORK` |

会话 Cookie 的 Path 固定 `/`，不设置父域 Domain；HttpOnly 与 SameSite=Lax 保持。子域名或端口安排不能替代独立鉴权与 CSRF 校验。原项目 Compose 已转发新增变量，Ferrari 接入配置也应显式注入。

前端与自身 `/api` 继续同源，Nginx 配置由运维接入目录提供；镜像内不固化域名、Cookie口令或服务器 IP。私有验收与公网发布分开记录。正式域名/HTTPS接入时，复核整个代理链的真实协议，避免内层 HTTP 覆盖可信入口的 HTTPS 信息。

普通更新保留数据卷。回退应用镜像不回退数据库迁移；数据库变更须在运维发布流程中另行备份、评估和验证。
