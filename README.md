# CleanPlayers

一款适用于 **Paper 1.21+** 的离线玩家数据自动清理插件。它按 `playerdata/*.dat` 中的 `lastPlayed` 判定离线时长，自动备份后再删除玩家数据，支持保护名单、刷号前缀清理、在线玩家豁免、定时任务与一键恢复。

- Java 21 / Paper API 1.21+
- 所有文件 IO 均在异步线程执行，清理过程中不影响玩家登录
- 删除前强制校验 UUID、在线状态与备份完整性，备份失败即跳过

## 功能

- **离线时长清理**：扫描配置世界的 `playerdata`，读取 NBT 的 `lastPlayed`（兼容 Paper 的 `Paper.LastSeen` / `Paper.LastLogin`），超过 `offline-days` 即纳入清理。
- **刷号前缀清理**：`name-prefixes` 中的前缀（如 `x_`）匹配 `usercache.json` 名字，无论最后登录时间多久都清理。
- **占位缓存清理**：可选清理 `usercache.json` 中从未生成 `playerdata` 的占位 UUID（先备份 usercache）。
- **保护名单**：原版白名单 / OP / 封禁玩家默认保护；另支持按玩家名、UUID、IP 保护；拥有 `cleanplayers.bypass` 的玩家自我豁免。
- **备份与恢复**：删除前复制到 `plugins/CleanPlayers/backups/日期/时间/`，校验字节数与 SHA-256 一致后才删除；`/cleanplayers restore` 从最近备份恢复 playerdata、advancements、stats。
- **定时清理**：`check-interval-hours` 异步周期执行；`cleanup-on-startup: true` 时开服后自动执行一次。
- **审计日志**：所有清理 / 恢复操作写入 `plugins/CleanPlayers/logs/audit-YYYY-MM-DD.log`，完成后控制台输出并通知在线管理员。
- **安全拒绝**：`config.yml` 无法解析、`data/protect.yml` 损坏或含有非法条目、插件目录不可写时，插件仍会加载并响应指令，但会拒绝一切清理/恢复操作，修复后 `/cleanplayers reload` 即可恢复。
- **备份校验**：每个备份文件复制后校验字节数与 SHA-256，任一不一致则该玩家整体跳过删除。

## 安装

1. 从 Releases 下载 `CleanPlayers-1.0.3.jar`。
2. 放入服务器 `plugins/` 目录。
3. 启动服务器，按需编辑 `plugins/CleanPlayers/config.yml`。
4. `/cleanplayers reload` 重载配置。

## 指令

| 指令 | 说明 | 权限 |
| --- | --- | --- |
| `/cleanplayers dryrun` | 模拟清理，仅列出将删除的玩家与文件 | `cleanplayers.use` |
| `/cleanplayers run` | 立即执行真实清理（先备份） | `cleanplayers.use` |
| `/cleanplayers restore <玩家名/UUID>` | 从最近备份恢复该玩家数据 | `cleanplayers.restore` |
| `/cleanplayers protect add <玩家名/UUID/IP>` | 加入保护名单 | `cleanplayers.protect` |
| `/cleanplayers protect remove <玩家名/UUID/IP>` | 移出保护名单 | `cleanplayers.protect` |
| `/cleanplayers protect list` | 查看保护名单 | `cleanplayers.protect` |
| `/cleanplayers backup list` | 查看最近 20 次备份 | `cleanplayers.use` |
| `/cleanplayers backup purge` | 手动清理过期备份 | `cleanplayers.backup.purge` |
| `/cleanplayers reload` | 重载配置与保护名单 | `cleanplayers.reload` |

仅注册完整指令 `/cleanplayers`，不使用短别名，避免与其它插件的 `/cp` 冲突。

> 命令本身不预置权限节点，因此即使玩家没有权限也能收到明确提示；拥有 `cleanplayers.admin` 或对应子权限即可使用。

## 排查「指令无法执行」

1. 控制台执行 `plugins`，确认 `CleanPlayers` 显示为绿色（未加载时请看 `logs/latest.log` 中的报错）。
2. 启动日志应包含 `Command registered: /cleanplayers` 与 `Enabled in ...`。
3. 游戏内执行需要权限：OP 默认拥有；使用权限插件时请授予 `cleanplayers.admin`（包含全部子权限）。
4. 若仍提示指令冲突，可使用 Bukkit 的插件限定形式 `/cleanplayers:cleanplayers`。
5. 若 `config.yml` 或 `data/protect.yml` 损坏，插件仍会加载并响应指令，但清理会被安全拒绝，修复后执行 `/cleanplayers reload`。
6. `data/protect.yml` 采用严格解析：只要 YAML 语法错误、字段不是列表，或存在非法 UUID/IP/玩家名，就会整体拒绝加载并禁止清理，避免保护名单被当作空列表而误删。

## 排查「x_ 前缀机器人没被清理」

先执行 `/cleanplayers dryrun`，看输出中的 `Protected/skipped:` 与 `Names resolved:`：

- `Names resolved: 0 / 1000`：`usercache.json` 已没有这些名字。前缀清理依赖 `usercache.json` 与 `data/player-ips.json`，让机器人重新登录一次即可重新记录名字；无法重新登录时，可在保护真实玩家后临时把 `offline-days` 设为 `0` 清理所有离线账号。
- `online=1000`：机器人仍在线，或退出未超过 `active-guard-minutes`，离线保护窗口过后再清理。
- `vanilla_protected=1000`：命中白名单 / OP / 封禁保护，按需关闭对应的 `protect-vanilla-*`。
- `cache_only_ignored=1000`：只有 usercache 记录、没有 playerdata，需要 `purge-usercache-orphans: true`。
- `too_recent=1000` 且 `Prefix matches: 0`：名字未解析到，按第一条处理。
- `scanned 0 player(s)`：`worlds` 未包含实际世界目录，补全后 `/cleanplayers reload`。


## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `cleanplayers.use` | OP | dryrun / run / backup list |
| `cleanplayers.protect` | OP | 管理保护名单 |
| `cleanplayers.restore` | OP | 恢复玩家数据 |
| `cleanplayers.reload` | OP | 重载配置 |
| `cleanplayers.backup.purge` | OP | 手动清理备份 |
| `cleanplayers.notify` | OP | 接收清理完成通知 |
| `cleanplayers.bypass` | 无 | 玩家自身永不被清理 |
| `cleanplayers.admin` | OP | 包含以上全部权限 |

## 配置

`plugins/CleanPlayers/config.yml`：

```yaml
offline-days: 90            # 离线超过该天数的玩家会被清理；0 表示所有离线玩家
check-interval-hours: 0     # 自动清理间隔小时，0 表示关闭，仅手动执行
cleanup-on-startup: false   # 开服后自动执行一次真实清理（同样先备份、走全部安全校验）
backup-keep-days: 7         # 备份保留天数，0 表示永久保留

name-prefixes:              # 刷号前缀，匹配 usercache 名字，忽略大小写
  - x_

worlds:                     # 需要扫描的世界目录（相对服务器根目录）
  - world
  - world_nether
  - world_the_end

# 安全
active-guard-minutes: 5     # 刚加入/退出的玩家在 N 分钟内不清理
max-players-per-run: 0      # 单次最多清理人数，0 表示不限制
delete-related-data: true   # 同时清理 advancements 与 stats
protect-vanilla-whitelist: true
protect-vanilla-ops: true
protect-vanilla-banned: true
io-threads: 2               # 备份/删除的并行 IO 线程数（1-8）

# 通知
notify-admins: true

# 占位缓存
purge-usercache-orphans: false
```

## 判定逻辑

一次清理按以下顺序处理每个玩家：

1. 在线、正处于登录流程、或处于 `active-guard-minutes` 保护窗口内 → 跳过。
2. 原版白名单 / OP / 封禁玩家（可分别关闭）→ 跳过。
3. 保护名单中的玩家名、UUID 或 IP 匹配 → 跳过。
4. 拥有 `cleanplayers.bypass` 的玩家 → 跳过。
5. 名字匹配 `name-prefixes` → 清理。
6. `lastPlayed` 有效且离线天数超过 `offline-days` → 清理。
7. 无有效时间戳的 playerdata → 不清理。
8. 仅存在于 usercache、无 playerdata 的占位 UUID → 仅在 `purge-usercache-orphans: true` 时清理缓存记录。

清理开始前与删除前都会重新检查在线状态，玩家在扫描期间上线会立即取消对其的清理。

## 备份与恢复

备份目录结构：

```
plugins/CleanPlayers/backups/
  2026-10-31/
    2026-10-31_18-30-00/
      manifest.json
      world/
        world/playerdata/<uuid>.dat
        world/advancements/<uuid>.json
        world/stats/<uuid>.json
        world_nether/playerdata/<uuid>.dat
      server/usercache.json          # 仅清理占位缓存时存在
```

- 每个文件复制后都会校验字节数与 SHA-256，任一备份失败（或大小/摘要不一致）则该玩家整体跳过，不执行删除。
- `/cleanplayers backup purge` 与启动时、每日定时任务会删除超过 `backup-keep-days` 的备份目录；没有 `manifest.json` 的目录不会被自动删除。
- `/cleanplayers restore <玩家名/UUID>`：
  1. 解析 UUID（支持玩家名、在线玩家、IP 映射表、备份 manifest 中的历史名字）。
  2. 玩家在线或处于活动保护窗口 → 拒绝。
  3. 找到最近一个包含该玩家数据的备份。
  4. 先把服务器上现存文件回滚备份到 `backups/_restore-rollback/<时间戳>/`（防止误覆盖）。
  5. 按世界将 playerdata / advancements / stats 复制回对应目录并校验。
  6. 写入审计日志。

## 数据文件

| 文件 | 说明 |
| --- | --- |
| `plugins/CleanPlayers/config.yml` | 主配置 |
| `plugins/CleanPlayers/data/protect.yml` | 保护名单（YAML，`players` / `uuids` / `ips`） |
| `plugins/CleanPlayers/data/player-ips.json` | 玩家名/UUID/IP 映射（加入服务器时记录，最多保留 8 个 IP） |
| `plugins/CleanPlayers/logs/audit-YYYY-MM-DD.log` | 清理与恢复审计日志 |

## IP 保护的局限

- 原版 playerdata 通常不保存最后登录 IP，因此 IP 保护主要依赖插件在玩家加入时记录（`player-ips.json`）。
- 对于加入前就已被清理的历史玩家，插件会尽力从 NBT 中查找 `lastIP` 等字段，但并非所有服务端都写入该字段。
- 代理服（BungeeCord / Velocity）玩家上报的 IP 可能是代理地址，请按实际网络拓扑配置。

## 构建

```bash
mvn clean package
```

产物：`target/CleanPlayers-1.0.3.jar`。要求 JDK 21+，Maven 首次构建需能访问 PaperMC 仓库与 JitPack。

## 许可

MIT License，见 [LICENSE](LICENSE)。
