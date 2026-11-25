# MySQL 索引与慢查询优化

## 最左前缀与索引失效

联合索引 (a, b, c) 能支持 a、a+b、a+b+c 三种前缀,跳过 a 直接用 b 就会全表扫描。容易忽略的失效场景还有:对索引列做函数运算 where date(create_time) = '2026-01-01',隐式类型转换 where varchar_col = 123,前导通配 like '%abc',以及 or 连接的列有一侧没索引。优化器在预估扫描行数超过全表 20% 到 30% 时会主动放弃索引,这不是失效,是成本权衡。

## 执行计划的正确读法

explain 的关键列是 type、key、rows、Extra。type 从好到差是 system、const、eq_ref、ref、range、index、ALL 到 index 基本等于全表扫。rows 是预估值不是精确值,但量级可信。Extra 里出现 Using filesort 说明排序没走索引,出现 Using temporary 说明用了临时表,这两个都是需要重点优化掉的信号。想看真实扫描行数用 explain analyze,它会把实际耗时和循环次数打出来。

## 深分页的三种解法

limit 1000000, 20 会先扫描一百万行再丢弃,越翻越慢。延迟关联的写法是先只查主键 select id from t order by id limit 1000000, 20,再用这 20 个 id 回表,减少回表次数。游标分页用 where id > 上一页最后一个 id limit 20,性能恒定但只能顺序翻页。业务上更彻底的做法是限制最大页数,大部分用户根本不会翻到第一千页。

## 事务隔离级别与幻读

MySQL 默认是可重复读 RR,通过 MVCC 的快照读避免不可重复读,通过间隙锁 next-key lock 在锁定读时避免幻读。这里的关键区分是快照读与当前读:普通 select 走快照,select for update、update、delete 走当前读并加锁。很多"明明加了事务还是脏数据"的问题,根源就是把快照读当成了加锁读。

## 死锁的成因与规避

两个事务以相反顺序更新两行数据就会死锁,InnoDB 检测到之后会回滚代价小的那个,报 1213 Deadlock found。规避手段有三条:统一加锁顺序、缩短事务范围、把大事务拆小。别在事务里调用外部 HTTP 接口,一旦对方超时,锁就被持有几十秒,后续请求全排队。show engine innodb status 的 LATEST DETECTED DEADLOCK 段落会完整还原现场。

## 索引下推与覆盖索引

索引下推 ICP 让 like 与范围条件在存储引擎层就过滤,减少回表次数,Extra 显示 Using index condition。覆盖索引是查询需要的列全部在索引里,不需要回表,Extra 显示 Using index。这两个优化对千万级表的收益往往是数量级的差别。代价是索引变宽,写入变慢,所以覆盖索引要按高频查询场景定制,不是越多越好。

## 大表 DDL 与在线变更

直接 alter table 加字段在 MySQL 8 之前会锁表。MySQL 8 的 instant add column 只改元数据,毫秒级完成,但要求加在最后一列且不改变行格式。更改列类型、加索引依然需要重建,此时用 pt-online-schema-change 或者 gh-ost 做影子表同步。千万级表的加索引更稳的路径是先在从库执行,再主从切换,而不是在主库上赌。

## 慢查询日志与参数

slow_query_log 打开后,long_query_time 建议设成 0.5 秒而不是默认的 10 秒,因为 10 秒的阈值等于什么都没抓。log_queries_not_using_indexes 谨慎开启,小表全扫也会被记录,日志会被刷爆。配合 pt-query-digest 做聚合分析,能很快找出占总耗时 80% 的那几条 SQL,优化的投入产出比远高于逐条看日志。
