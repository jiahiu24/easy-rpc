# easy-rpc
从0实现rpc轮子项目-实习
env:jdk8
## 启动：
1.运行server.main
2.运行client.main
使用到的中间件：
netty网络通信-zookeeper服务注册-SPI服务加载-guava-retry重试和熔断
## 其他：
启动zookeeper
   docker run -d --name zookeeper-2080 -p 2080:2181 zookeeper:3.5.8
