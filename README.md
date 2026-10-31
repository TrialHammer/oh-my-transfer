# OhMyTransfer

通过 Minecraft 原生 **Transfer** 和 **Cookie** 机制，在 Paper 服务器之间传送玩家。源服务器签发 HMAC-SHA256 票据，目标服务器在玩家进入世界前完成校验。

- 客户端直接连接目标服务器，无需部署 Velocity 或 BungeeCord。
- 同一个插件可同时发送和接收传送。
- 票据绑定目标服务器、玩家身份和有效期，使用随机 nonce 防止重复使用。
- 支持玩家自行传送、管理员传送指定玩家，以及配置热重载。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper 1.21.7 起，使用其配置阶段事件 API |
| 运行时 | Java 21 起，具体版本遵循所用 Paper 的要求 |
| 客户端 | 支持 Transfer / Cookie 的 Java 版客户端（协议自 1.20.5 引入），且与目标服务器版本兼容 |
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

| 命令 | 权限 | 默认授权 | 说明 |
| --- | --- | --- | --- |
| `/omt <server>` | `omt.self` | OP | 将自己传送到指定服务器 |
| `/omt <player> <server>` | `omt.use` | OP | 传送指定在线玩家，支持单玩家选择器，如 `@p` |
| `/omt reload` | `omt.use` | OP | 重新加载配置；配置无效时继续使用旧配置 |

普通玩家需要通过权限插件获得 `omt.self` 才能自行传送。控制台可以使用管理员命令。

服务器名来自 `servers` 配置，自动补全会排除当前服务器。有两个权限的玩家执行 `/omt survival` 时会传送自己；要传送同名玩家，使用完整的 `/omt survival <server>`。

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
  cookie-timeout-millis: 3000
  deny-message: "<red>请通过服务器传送进入本服。</red>"

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
| `receiver.cookie-timeout-millis` | 等待客户端返回 Cookie 的超时，默认 3000 毫秒 |
| `receiver.deny-message` | 拒绝连接时的提示，支持 MiniMessage |
| `servers` | 可传送目标的名称、地址和端口 |

服务器名称只允许字母、数字、`_`、`-`、`+`，不能使用保留名称 `reload`（不区分大小写）。可以在所有服务器使用同一份目标列表，插件会排除向自身传送。

### 玩家身份匹配

| `match-by` | 校验规则 | 适用场景 |
| --- | --- | --- |
| `uuid` | UUID 一致 | 各服使用相同的玩家 UUID 体系，默认值 |
| `name` | 玩家名一致，区分大小写 | 各服因登录方式不同而产生不同 UUID |
| `uuid-or-name` | UUID 或玩家名任一一致 | 混合身份体系 |

### 配置升级

启动或执行 `/omt reload` 时，插件会将新增配置项及其注释补入现有配置，并在日志中列出新增项。已有值会保留，`servers` 列表由服主管理，不会重新加入示例服务器。

## 工作原理

1. 源服务器生成票据，写入玩家的 `omt:ticket` Cookie，再发送 Transfer 请求。
2. 客户端连接目标服务器，在配置阶段返回票据。
3. 目标服务器校验签名、格式版本、目标标识、玩家身份、有效期及 nonce。
4. 校验通过后记录 nonce 并清空票据 Cookie，随后允许玩家进入世界；失败则断开连接。

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
├── listener/                配置阶段票据校验
└── ticket/                  票据编解码、签名与防重放
src/main/resources/          插件描述与默认配置
src/test/java/               票据、配置升级与命令路由测试
```

手动联调可按快速开始部署两台本地 Paper 服务器，依次验证双向传送、无票据直连拒绝，以及更换目标服密钥后出现 `BAD_SIGNATURE`。

## 许可证

本项目采用 [MIT License](LICENSE)。
