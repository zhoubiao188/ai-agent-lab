# Java 并发与虚拟线程

## 线程池的七个参数怎么定

核心线程数、最大线程数、队列容量三者决定了饱和策略何时触发。Executors.newFixedThreadPool 用了无界队列 LinkedBlockingQueue,最大线程数永远是核心线程数,任务堆积到 OOM 才有人发现。生产上必须手写 ThreadPoolExecutor,给队列一个明确容量,并显式指定拒绝策略。队列满了之后 CallerRunsPolicy 会让提交任务的线程自己执行,形成天然的背压。

## 虚拟线程的适用边界

JDK 21 的虚拟线程把线程成本从 MB 级降到 KB 级,适合大量阻塞在 IO 上的任务。但它不能解决 CPU 密集型任务的吞吐问题,也不会让 synchronized 块变成可挂起。虚拟线程遇到 synchronized 包裹的阻塞调用会被钉住(pinned),平台线程被占住,吞吐反而下降。迁移前用 -Djdk.tracePinnedThreads=full 跑一遍,找出所有钉住点,把 synchronized 换成 ReentrantLock 再上。

## CompletableFuture 的异常黑洞

thenApply 里抛出的异常不会中断主线程,而是把 future 标记成异常完成。如果调用方只用 join 拿了结果却没检查异常,错误就被吞掉了。thenCompose 用于串联有依赖的异步任务,thenCombine 用于并行后合并,这两个方法名很容易写混。另外默认的 ForkJoinPool.commonPool 只有 CPU 核数减一个线程,IO 密集场景必须传入自定义 Executor,否则会把公共池占满影响其他组件。

## 锁的选择与死锁排查

synchronized 在 JDK 15 之后已经做过偏向锁移除与轻量级锁优化,短临界区下的表现与 ReentrantLock 差距很小。ReentrantLock 的价值在于可中断、可超时、可公平。tryLock 带超时是防死锁最实用的手段:拿不到锁就放弃并记录,而不是无限等待。真出现死锁时,jstack 会打印 Found one Java-level deadlock,直接告诉你哪两个线程互相持有什么锁,不需要猜。

## 并发容器的选择

HashMap 在多线程下扩容可能形成环形链表导致 CPU 100%,这是老版本的问题,JDK 8 之后改成了尾插 + 红黑树,但仍不是线程安全的。ConcurrentHashMap 的分段思想在 JDK 8 换成了 CAS 加 synchronized 锁单个桶,读操作完全无锁。CopyOnWriteArrayList 适合读多写极少(比如监听器列表),写时复制整个数组,写频繁时会疯狂 GC。

## 原子类与 CAS 的 ABA 问题

AtomicInteger 的 incrementAndGet 是 CAS 自旋,高竞争下失败重试会空转消耗 CPU,这时候用 LongAdder 更合适,它把计数分散到多个 Cell 上,求和时才聚合。CAS 的经典缺陷是 ABA:值从 A 变 B 又变回 A,比较时看不出变化。AtomicStampedReference 用版本号解决这个问题,代价是每次操作多维护一个 int。

## 内存模型与可见性

volatile 保证可见性与禁止指令重排,但不保证原子性,count++ 这类复合操作依然会丢更新。happens-before 规则里最常用的是程序次序、监视器锁、volatile 变量、线程启动与终止这四条。单例的双重检查锁必须给实例字段加 volatile,否则可能出现"引用已赋值但构造未完成"的半初始化对象,这是最经典的可见性事故。

## ThreadLocal 的内存泄漏

ThreadLocal 的 Entry 是弱引用指向 key,但 value 是强引用。线程池里的线程长期存活,如果用完不 remove,value 就跟着线程一直活着。这就是内存泄漏的真实成因。用拦截器或者 try-finally 保证 remove 是最低要求。InheritableThreadLocal 在池化线程里几乎必然失效,因为线程是复用的,子线程创建时机早于值的写入,要传递上下文请用 TransmittableThreadLocal 或显式传参。
