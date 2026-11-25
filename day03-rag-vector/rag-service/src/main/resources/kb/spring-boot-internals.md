# Spring Boot 启动与自动配置

## 启动流程的三个阶段

SpringApplication.run 可以拆成三段。第一段是 prepareContext 之前的环境准备:加载 application.yml、绑定 Environment、发布 ApplicationEnvironmentPreparedEvent,这一步决定了 profile 是否生效。第二段是 refreshContext,也就是 AbstractApplicationContext.refresh 的十二个步骤,BeanFactory 后置处理、Bean 实例化、AOP 代理都发生在这里。第三段是 afterRefresh,执行 ApplicationRunner 与 CommandLineRunner。线上启动慢,九成问题出在第二段。

## 自动配置的生效条件

自动配置类并不是无条件加载,而是被一批条件注解层层筛选。@ConditionalOnClass 看类路径是否存在,@ConditionalOnMissingBean 看容器里是否已经有人提供了同类型的 Bean,@ConditionalOnProperty 看配置项。排查某个自动配置为什么没生效,最有效的办法是启动时加 --debug,控制台会打印 CONDITIONS EVALUATION REPORT,把 Positive matches 和 Negative matches 逐条列出来,比翻源码快得多。

## 循环依赖与三级缓存

单例 Bean 的字段注入循环依赖靠三级缓存解决:singletonObjects 放成品,singletonFactories 放能产出早期引用的工厂,earlySingletonObjects 放被提前暴露的半成品。构造器注入的循环依赖无法用三级缓存解决,启动时直接抛 BeanCurrentlyInCreationException,这是设计上的刻意为之,因为构造器阶段对象还没造出来,没有任何东西可以提前暴露。Spring Boot 2.6 之后默认禁止循环依赖,要放开得显式设置 spring.main.allow-circular-references=true。

## 配置文件加载优先级

同一份配置可能出现在五个地方,优先级从高到低是:命令行参数、Java 系统属性、操作系统环境变量、jar 包外部的 application.yml、jar 包内部的 application.yml。profile 专属文件 application-prod.yml 会覆盖 application.yml 里的同名键。容器化部署时最容易踩的坑是把配置写进镜像里的 application.yml,结果改配置必须重新打镜像,正确做法是全部走环境变量或外部挂载。

## Actuator 健康检查的真实语义

/actuator/health 返回 UP 不代表业务可用。默认的 HealthIndicator 只检查数据源连接、磁盘空间、Redis ping 这类基础设施,不会检查下游 HTTP 服务是否超时。生产上应该自己实现 HealthIndicator,对核心依赖做带超时的探测,并且用 groups 把 liveness 与 readiness 分开:liveness 只反映进程是否卡死,readiness 才反映能不能接流量。两者混用会导致滚动发布时流量被错误地打进来。

## 优雅停机的顺序

server.shutdown=graceful 只是第一步。完整的优雅停机要保证四件事:注册中心先摘除实例、网关停止转发新请求、已经在处理的请求跑完、线程池与连接池按序关闭。Spring Boot 提供 SmartLifecycle,phase 值越小越晚停,可以据此编排关闭顺序。Kubernetes 环境下还要配合 preStop hook 睡几秒,否则 Service 的 endpoints 更新有延迟,仍会有请求打到正在关闭的 Pod 上。

## 线程池配置与 Tomcat 调优

server.tomcat.threads.max 默认 200,这个值不是越大越好。真正的并发能力取决于最慢的那个下游依赖:如果一次请求平均要等 300 毫秒的数据库,200 个线程的理论上限就是每秒 660 左右。盲目加到 500 只会让数据库连接池成为新瓶颈,并且把内存中的排队时间拉长。正确的顺序是先压测拿到 P99,再根据 Little 定律估算所需并发,最后反推线程数与连接池大小。

## 日志与链路追踪

MDC 里的 traceId 是同一线程内传递的,一旦进入异步线程池或者 @Async 方法就会丢。解决办法有两种:用 TaskDecorator 包装线程池,在提交任务时复制 MDC;或者直接上 Micrometer Tracing,让 traceId 由 Observation 上下文携带,跨线程自动传播。前者轻量,后者能覆盖 HTTP 客户端与消息队列。生产环境推荐后者,因为一次跨服务调用的排查成本远高于引入依赖的成本。

## 打包与分层镜像

Spring Boot 3 的 spring-boot-maven-plugin 支持分层 jar,把依赖和业务代码分成 layers.idx 里声明的几层。这样业务代码改动只有几百 KB,Docker 构建时依赖层命中缓存,镜像推送时间从分钟级降到秒级。配合 buildpacks 可以完全不用写 Dockerfile:mvn spring-boot:build-image 直接产出符合 OCI 规范的镜像,并且自动带上 JVM 参数调优的启动脚本。
