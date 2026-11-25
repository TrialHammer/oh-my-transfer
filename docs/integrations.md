# 接入其他插件与模组

## Floodgate

目标服装有 Floodgate 时，`receiver.floodgate-bypass` 允许它识别的基岩版玩家免票据直连。若目标服要求所有玩家都持票，将此选项设为 `false`。

## 地图模组

在参与同步的后端启用 `map-compat.enabled`，再让客户端重连。Xaero、Diorama、JourneyMap 和 VoxelMap 可以用服务器发送的世界 ID 区分世界。改变 `server-id`、世界 UUID 或识别设置可能改变地图保存位置，启用前先备份客户端地图和路标。

世界 ID 不会合并客户端分别按服务器条目保存的地图。建议玩家从固定入口进服，再通过插件切换服务器。支持服务端身份的 VoxelMap 客户端可用共享的 `voxelmap-server-identity`，各后端仍应使用不同的 `server-id`。如果已安装 VoxelMap 服务端插件，在它的配置中设置 `serverIdentity`。MapModCompanion 或 JourneyMap 服务端插件接管的频道不会重复发送。

## Wormholes 跨服门

在两端安装 OhMyTransfer 和 Wormholes，保留目标端票据校验，配置相同的共享密钥和 Cookie 键。启用 Wormholes 的穿越 API，并使用原生 `packet` 传送，代理模式不会携带传送 Cookie。让对端名对应 OhMyTransfer 的目标 ID。名称不同时，在源服配置 `wormholes.server-map`，双向门在两端分别设置。

穿门时插件为玩家写入票据。源服装有 AuthMe 时，玩家须先登录。Wormholes 的直接连接命令不走穿越事件，所以桥接开启时请通过跨服门或 `/omt <目标>` 转服。有关穿越 API 和传送模式，见 [Wormholes 配置参考](https://docs.volmitsoftware.com/wormholes/01-installation-configuration)。
