# OhMyTransfer

通过 Minecraft 原生 **Transfer** 和 **Cookie** 机制，在 Paper 服务器之间传送玩家。源服务器签发 HMAC-SHA256 票据，目标服务器在玩家进入世界前完成校验。

- 客户端直接连接目标服务器，无需部署 Velocity 或 BungeeCord。
- 同一个插件可同时发送和接收传送。
- 票据绑定目标服务器、玩家身份和有效期，使用随机 nonce 防止重复使用。
- 支持玩家自行传送、管理员传送指定玩家，以及配置热重载。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper 1.21.7 起，使用其登录阶段（PreLogin）Cookie API |
| 运行时 | Java 21 起，具体版本遵循所用 Paper 的要求 |
| 客户端 | 支持 Transfer / Cookie 的 Java 版客户端（协议自 1.20.5 引入），且与目标服务器版本兼容 |
| Floodgate（可选） | 安装后可让 Floodgate 基岩版玩家免票据直连；需在接收服安装 |
| 地图模组（可选） | Xaero's Minimap、Xaero's World Map、Diorama、JourneyMap、VoxelMap；纯客户端 mod 即可 |
| 跨服传送门（可选） | Wormholes 3.1.5 已验证，使用原生 Transfer |
| 构建工具 | JDK 21+、Maven 3.9+ |

项目以 Paper `1.21.7-R0.1-SNAPSHOT` API 编译，生成 Java 21 字节码。部署到其他 Paper 版本时，请确认 API 兼容性。

## 构建

在项目根目录执行：

```bash
mvn clean package
```

构建会运行单元测试，并生成 `target/OhMyTransfer-1.0.0.jar`。仅运行测试：

```bash
mvn test
```

## 快速开始

以下示例使用大厅 `lobby` 和生存服 `survival`，分别监听 `25565` 和 `25566`。

1. 将构建出的 JAR 放入两台服务器的 `plugins/` 目录，启动一次，生成 `plugins/OhMyTransfer/config.yml`。
2. 在接收传送的服务器的 `server.properties` 中设置 `accepts-transfers=true`，然后重启服务器。
3. 编辑两台服务器的插件配置，使用不同的 `server-id`，并将同一个 `secret` 复制到两边。`secret` 留空时，插件会随机生成一个并写回配置文件。
4. 将下面的配置分别合并到对应服务器的 `config.yml`，其余选项保持默认。

大厅：

```yaml
server-id: lobby
receiver:
  require-ticket: false
servers:
  survival:
    host: "127.0.0.1"
    port: 25566
```

生存服：

```yaml
server-id: survival
receiver:
  require-ticket: true
servers:
  lobby:
    host: "127.0.0.1"
    port: 25565
```

`host` 是**客户端连接的地址**。上面的回环地址适用于客户端和两台服务器都在同一台电脑上的测试；远程部署时请改为玩家可访问的域名或 IP。

配置完成后，在两台服务器执行 `/omt reload`。以 OP 身份进入大厅，执行 `/omt survival` 即可传送到生存服，再执行 `/omt lobby` 返回。直接连接生存服会因缺少票据而被拒绝。

## 命令与权限

`/server` 是 `/omt` 的别名，支持下表的所有用法，权限相同。如果其他插件已占用 `/server`，可以使用 `/ohmytransfer:server` 或 `/omt`。

| 命令 | 权限 | 默认授权 | 说明 |
| --- | --- | --- | --- |
| `/omt <server>` | `omt.self` | OP | 将自己传送到指定服务器 |
| `/omt <player> <server>` | `omt.use` | OP | 传送指定在线玩家，支持单玩家选择器，如 `@p` |
| `/omt reload` | `omt.use` | OP | 重新加载配置；配置无效时继续使用旧配置 |

普通玩家需要通过权限插件获得 `omt.self` 才能自行传送。控制台可以使用管理员命令。

服务器名来自 `servers` 配置，自动补全会排除当前服务器。有两个权限的玩家执行 `/omt survival` 时会传送自己；要传送同名玩家，使用完整的 `/omt survival <server>`。

通过 PlugManX 热加载时，OMT 会恢复 `/omt`、`/server` 及其带命名空间的命令，不再需要额外执行 `minecraft:reload`。如果 `/server` 已被占用，仍可使用 `/ohmytransfer:server`。只修改 OMT 配置时，用 `/omt reload` 即可。

## 配置

配置路径为 `plugins/OhMyTransfer/config.yml`，带完整注释的默认文件见 [config.yml](src/main/resources/config.yml)。

```yaml
server-id: lobby
secret: ""
cookie-key: "omt:ticket"
ticket-ttl-seconds: 30
clock-skew-seconds: 5

receiver:
  require-ticket: true
  match-by: uuid
  bypass-uuids: []
  floodgate-bypass: true
  cookie-timeout-millis: 3000
  deny-message: "<red>请通过服务器传送进入本服。</red>"

map-compat:
  enabled: false
  xaero: true
  world-info: true
  join-repeat: 3
  voxelmap-server-identity: ""

wormholes:
  enabled: true
  server-map: {}

servers:
  survival:
    host: "127.0.0.1"
    port: 25566
```

| 选项 | 说明 |
| --- | --- |
| `server-id` | 本服唯一标识；其他服务器指向本服的 `servers` 键名必须与此一致 |
| `secret` | Base64 共享密钥，解码后至少 32 字节；所有互通服务器必须一致 |
| `cookie-key` | 保存票据的 Cookie 键；互通服务器应使用相同值 |
| `ticket-ttl-seconds` | 票据有效期，默认 30 秒 |
| `clock-skew-seconds` | 允许的时钟偏差，默认 5 秒；各服应保持时钟同步 |
| `receiver.require-ticket` | `true` 时拒绝缺少票据的连接；`false` 时允许无票据直连，但仍拒绝无效票据 |
| `receiver.match-by` | 玩家身份匹配方式，见下表 |
| `receiver.bypass-uuids` | 跳过票据校验的玩家 UUID 列表 |
| `receiver.floodgate-bypass` | Floodgate 基岩版玩家免票据验证直连；默认 `true`，仅在安装 Floodgate 时生效 |
| `receiver.cookie-timeout-millis` | 等待客户端返回 Cookie 的超时，默认 3000 毫秒 |
| `receiver.deny-message` | 拒绝连接时的提示，支持 MiniMessage |
| `map-compat.enabled` | 启用地图模组世界 ID 兼容；默认关闭 |
| `map-compat.xaero` | 向 Xaero's Minimap、Xaero's World Map、Diorama 发送世界 ID |
| `map-compat.world-info` | 处理 JourneyMap、VoxelMap 的 `worldinfo:world_id` 请求 |
| `map-compat.join-repeat` | 进服时 Xaero / Diorama 世界 ID 的初发与补发总次数，范围 1–5 |
| `map-compat.voxelmap-server-identity` | 支持该设置的 VoxelMap 客户端可跨地址共用地图根目录。默认为空，格式为 1–64 位小写字母、数字、`_` 或 `-`，首位需为字母或数字 |
| `wormholes.enabled` | 安装 Wormholes 时启用跨服门签票，默认开启 |
| `wormholes.server-map` | Wormholes 对端名到 OMT 目标 ID 的映射，区分大小写。未配置的对端使用同名 ID |
| `servers` | 可传送目标的名称、地址和端口 |

服务器名称只允许字母、数字、`_`、`-`、`+`，不能使用保留名称 `reload`（不区分大小写）。可以在所有服务器使用同一份目标列表，插件会排除向自身传送。

### 玩家身份匹配

| `match-by` | 校验规则 | 适用场景 |
| --- | --- | --- |
| `uuid` | UUID 一致 | 各服使用相同的玩家 UUID 体系，默认值 |
| `name` | 玩家名一致，区分大小写 | 各服因登录方式不同而产生不同 UUID |
| `uuid-or-name` | UUID 或玩家名任一一致 | 混合身份体系 |

### Floodgate 支持

安装 Floodgate 后，`receiver.floodgate-bypass: true` 会让 Floodgate 玩家在目标服务器免票据验证直连。该判断使用 Floodgate API 的玩家记录，不会仅凭 UUID 形态放行普通 Java 玩家。未安装 Floodgate 时此选项不起作用；设为 `false` 后 Floodgate 玩家与其他玩家一样必须携带有效票据。

### 地图模组兼容

在需要兼容的每台后端服务器设置 `map-compat.enabled: true`，再让客户端重新连接。Xaero's Minimap、Xaero's World Map 和 Diorama 会在频道注册、切世界或重生时收到世界 ID。JourneyMap 和 VoxelMap 首次请求后，切世界时会收到相同格式的世界 ID。

世界 ID 由 `server-id` 和世界 UUID 计算，两个值不变时重启不会改 ID。原版主世界、下界和末地共享同一个 ID，各维度仍分别存储地图。旧版 Paper 或多世界插件的同名前缀维度仍支持按 `_nether` / `_the_end` 归组。

- **Xaero、Diorama、JourneyMap** 仍由客户端按服务器条目（地址或条目名称）区分地图根目录。原版 26.3 的 Transfer 会保留最初的服务器条目，只改变实际连接的后端地址。因此建议玩家从固定入口进入，再用 `/server` 切服。从另一条目直连同一后端时，世界 ID 不会合并两个根目录。Xaero World Map 若仍用手动选图，可在地图左下角切到 Server 模式，并重新绑定旧地图。
- **新版 VoxelMap** 支持服务端指定地图根目录。要让多个地址共用根目录，在对应后端设置相同的 `voxelmap-server-identity`（例如 `my-network`）。各后端仍应保留不同的 `server-id`，避免不同世界串图。首次启用前建议备份客户端地图。VoxelMap 可能迁移已有地址的数据，但不会合并多个地址已有的地图。

首次启用世界 ID 后，旧地图和路标可能还在原目录，需要按模组对应的迁移方式恢复。

装有 MapModCompanion 时，OhMyTransfer 不重复发送对应的 Xaero 和 `worldinfo` 世界 ID。装有 JourneyMap Paper 插件时，由它独占共享的 `worldinfo` 频道。装有 VoxelMap Paper 插件时，请在该插件配置中设置 `serverIdentity`，OhMyTransfer 不覆盖已注册 `voxelmap:settings` 的客户端世界名。如果同一客户端同时运行 JourneyMap 和 VoxelMap，共享频道不能只发给其中一个模组。

### Wormholes 跨服门

1. 两端安装 OMT 和 Wormholes。保留目标服现有的 `receiver.require-ticket: true`、身份匹配规则，以及 `server.properties` 中的 `accepts-transfers=true`。互通两端的 OMT 密钥和 cookie 键必须一致。
2. 用 Wormholes 正常建立对端和跨服门。保持其 `wormholes.toml` 的 `[main].traversal-api-enabled = true`，建议在 `[network]` 设置 `transfer-mode = "packet"`。代理转服不会把原生 Transfer cookie 带到目标后端，桥接会取消这类穿越。 [Wormholes 网络配置](https://docs.volmitsoftware.com/wormholes/01-installation-configuration)
3. 将对端的 OMT `server-id` 配成源服 `servers` 下的键。如果名称不同，例如 Wormholes 对端叫 `survival-gateway`，而 OMT ID 是 `survival`，在源服加上如下映射。双向门需要分别配置两个方向。

```yaml
wormholes:
  enabled: true
  server-map:
    survival-gateway: survival
```

目标 Wormholes 确认准入后，源服 OMT 在可取消的穿越事件中签票并提交 cookie。源服安装 AuthMe 时，玩家须已登录，AuthMe API 不可用也会取消穿越。未知目标、无效映射或写票失败都会取消并提示。Wormholes 仍负责投影、连接地址、转服、落点和回执。同服门以及原有 `/omt` 用法不受影响。

`/wh server connect <对端>` 和 `/wh server <对端>` 不触发穿越事件，桥接开启时会阻止玩家直接使用这些命令。请走跨服门，或用 `/omt <目标 ID>` 普通转服。Wormholes 内部在落点被拒后尝试自动返回、其他插件直接调用其内部转服 API 等非穿越路径也不签票，要求票据的后端会拒绝这些无票连接。

### 配置升级

启动或执行 `/omt reload` 时，插件会将新增配置项及其注释补入现有配置，并在日志中列出新增项。已有值会保留，`servers` 列表由服主管理，不会重新加入示例服务器。

## 工作原理

1. 源服务器生成票据，写入玩家的 `omt:ticket` Cookie，再发送 Transfer 请求。
2. 客户端连接目标服务器，在登录阶段（`AsyncPlayerPreLoginEvent`）返回票据。
3. 目标服务器在 PreLogin 校验签名、格式版本、目标标识、玩家身份、有效期及 nonce。
4. 校验通过后记录 nonce 并放行登录；玩家进服后清空票据 Cookie。失败则在 PreLogin 直接断开连接。

票据使用二进制格式，包含版本号、签发服务器、目标服务器、玩家 UUID、玩家名、签发时间、过期时间、16 字节 nonce 和 32 字节 HMAC-SHA256。编码定义见 [TicketCodec.java](src/main/java/ink/ziip/ohmytransfer/ticket/TicketCodec.java)。

票据签名保证内容完整性，内容本身不加密。持有共享密钥的服务器都能签发票据，应将密钥仅交给受信任的服务器。防重放记录保存在接收服务器内存中，重启后清空；票据仍受有效期约束。离线模式下，玩家身份的真实性还取决于所用登录机制。

## 常见问题

| 现象或日志 | 检查项 |
| --- | --- |
| 无法接收传送 | 目标服是否设置 `accepts-transfers=true` 并重启；客户端能否访问目标地址和端口 |
| `no ticket` | 来源是否通过 `/omt` 发起传送；两边的 `cookie-key` 是否一致 |
| `BAD_SIGNATURE` | 两边是否使用同一个 `secret`，修改后是否重载 |
| `WRONG_TARGET` | 来源 `servers` 中的目标键名是否与目标服 `server-id` 一致 |
| `WRONG_PLAYER` | 两边 UUID 体系是否一致，`match-by` 是否符合登录方式 |
| `EXPIRED` / `NOT_YET_VALID` | 服务器时钟是否同步，传送耗时是否超过票据有效期 |
| `REPLAYED` | 同一票据已被消费，应从源服务器重新发起传送 |
| 大厅无法直连 | 大厅应设置 `receiver.require-ticket: false` |

## 开发

Java 包名为 `ink.ziip.ohmytransfer`，Maven 坐标为 `ink.ziip.ohmytransfer:oh-my-transfer`。

```text
src/main/java/ink/ziip/ohmytransfer/
├── OhMyTransferPlugin.java   插件入口与配置加载
├── command/                 命令注册与补全
├── config/                  配置解析与新增配置项补全
├── listener/                PreLogin 票据校验
├── map/                     地图模组世界 ID 兼容
└── ticket/                  票据编解码、签名与防重放
src/main/resources/          插件描述与默认配置
src/test/java/               票据、配置升级与命令路由测试
```

手动联调可按快速开始部署两台本地 Paper 服务器，依次验证双向传送、无票据直连拒绝，以及更换目标服密钥后出现 `BAD_SIGNATURE`。

## 许可证

本项目采用 [MIT License](LICENSE)。
