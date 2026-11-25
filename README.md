# OhMyTransfer

OhMyTransfer 让玩家在 Paper 服务器之间直接传送，不需要 Velocity 或 BungeeCord。源服通过 Minecraft 原生机制发起转服，目标服在登录时检查票据。玩家可用 `/server <目标>`，服主也可以传送指定玩家。

## 安装

按[构建说明](docs/building.md)生成 JAR，安装到参与传送的每台服务器。再按[双服部署示例](docs/setup.md)设置服务器标识、共享密钥和客户端能访问的目标地址。接收传送的服务器需要开启 `accepts-transfers`。

[集成指南](docs/integrations.md)介绍地图模组、Floodgate 和 Wormholes 跨服门。OhMyTransfer 使用 [MIT 许可证](LICENSE)。
