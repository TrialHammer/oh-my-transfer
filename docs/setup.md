# 连接两台服务器

将 JAR 放入两台服的 `plugins/`，启动一次生成 [config.yml](../src/main/resources/config.yml)。给每台服不同的 `server-id`，将在启动时生成的一个 `secret` 复制到所有互通服务器。它们也必须使用相同的 `cookie-key`。在会接收转服的每台服的 `server.properties` 设 `accepts-transfers=true`，然后重启。

假设入口是 `lobby`，后端是 `survival`。入口服允许直接登录，后端只允许有票据的转服连接。以下地址是示例，必须换成玩家客户端能访问的地址。

**大厅**

```yaml
server-id: lobby
receiver:
  require-ticket: false
servers:
  survival:
    host: survival.example.org
    port: 25565
```

**生存服**

```yaml
server-id: survival
receiver:
  require-ticket: true
  floodgate-bypass: false
servers:
  lobby:
    host: lobby.example.org
    port: 25565
```

用 OP 登录大厅，运行 `/omt survival` 测试，再用 `/omt lobby` 返回。用 `omt.self` 授权玩家自行转服。拥有 `omt.use` 的管理员可以运行 `/omt <玩家> <目标>` 和 `/omt reload`。如果其他插件占用了 `/server`，仍可使用 `/omt` 或 `/ohmytransfer:server`。

## 遇到连接问题

如果客户端无法连接，先查 `accepts-transfers` 和目标地址是否可达。缺少票据时，确认来源通过 OhMyTransfer 发起传送，并核对两边的 `cookie-key`。签名不匹配时检查 `secret`，目标不匹配时检查来源的目标键与目标服 `server-id`。身份不匹配时检查两边的 UUID 体系和 `receiver.match-by`。出现过期错误时，同步服务器时钟并检查传送耗时。

票据签名不加密内容。只把共享密钥交给受信任的服务端。防重放记录保存在接收服内存中，会随重启清空，票据本身仍受有效期限制。部署离线模式时，玩家身份还依赖所用的登录机制。
