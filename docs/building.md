# 构建与兼容性

使用 JDK 21 或更新版本和 Maven。在仓库根目录运行 `mvn clean package`，JAR 位于 `target/`。仅运行测试时使用 `mvn test`。

编译目标见 [pom.xml](../pom.xml)。插件需要 Paper 1.21.7 起提供的登录阶段 Cookie API，客户端也要支持原生 Transfer 和 Cookie，并与目标服兼容。运行时 Java 版本以对应 Paper 服务端的要求为准。Wormholes 桥接在 3.1.5 上验证过。
