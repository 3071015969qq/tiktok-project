# tiktok-project
仿抖音项目-Java重写版

### 链路追踪：

由于是微服务架构，存在一个功能的实现需要多个服务间相互调用，为了能明确一个功能的服务调用流程，我们使用基于jaeger依赖的链路追踪

访问网址：`http://8.134.208.93:16686/search`可以查询每个接口的服务调用流程



### 请求鉴权设计：

登陆后的绝大部分功能都是需要前端携带token请求，后端处理请求前先检验token的合法性。但由于每个接口都有这个token判断的逻辑，代码耦合度太高。

这里使用springboot的HandlerMethodArgumentResolver（参数解析器），通过在参数添加注解标识来拦截请求，鉴权后再执行后续逻辑。



### 评论模块设计：

#### 获取视频评论缓存问题：

要想获取视频的评论缓存，最简单的做法就是用该视频的id作为key，评论列表作为value存入redis中。每次有新的用户发布评论就将对应视频的评论缓存全部删除，生成新的评论列表存入进行更新。

如果这个视频非常火，用户评论数在短时间内剧增，也就出现了一些问题，：

1. 缓存列表需要不断更新，缓存的命中率就十分的低，这种设计导致缓存意义不大。
2. 频繁的序列化和反序列化：因为将对象从内存转化为二进制或json格式存入redis中需要消耗cpu资源进行序列化，频繁的更新增大了cpu的压力
3. 随着评论数据量的增大，可能形成大key，不仅对redis服务有影响，反序列化出来的评论列表也就越大，有内存溢出的风险

#### 解决思路：

首先我们肯定不能总是一次性把所有评论都拿出来，可以先拿比如说300条，等到滑动到底了再触发请求再拿300条存入缓存。但是我们返回给前端是20条

我们把这300条数据只取出主键索引，也就是评论id按评论时间升序存入redis的一个zset中【1-300】，让value内容可以减少。这个列表称为【评论id列表缓存】。如果拿出下一批就是【301-600】，key为：` index-301_600`。

如果又来了一条新的，【601-？？？】，前端拿肯定是拿不到20条，那就再往前面的zset也就是【index-301-600】再拿20条，此时会拿出20条+的数据。同时新增一条K-V的具体评论内容

对于具体的评论内容，我们通过它的评论id和评论内容按K-V缓存来处理（string类型），拿到【评论id列表缓存】后遍历K获取每一条具体内容V。

每次需要更新缓存的时候我们就只需要更新【评论id列表缓存】，具体的string没必要变动

#### 新增评论后的缓存流程：

1. 用户insert一条评论，清空该视频的【评论id列表缓存】，新发布的具体评论string单独缓存，旧的具体评论内容仍然保留
2. 重新select出最新的300条评论，300个评论id存入新的【评论id列表缓存】，300条具体评论内容
3. 返回前20条数据给前端
> 其中，第2步的【重新select出最新的300条评论】可以不需要，重新select的目的就是为了向用户展示最新的评论数据；
> 但如果前端能够在用户发布新评论后及时将这条新评论展示出来也能达到目的，此时用户看到自己发表的新评论其实并不是来源于后端，而是前端展示的假数据。但实际上这条新评论都已经存入数据库和缓存了；
> 

#### 设计中仍存在的问题：

1. 如果拿一个【评论id列表缓存】的区间段数据不满足20条时会拿上一个区间段，为了减少下标的索引计算就索性再拿20条数据了。但这样就会导致每次拿到的数据条数不一定是20条，但至少始终是在【0-40】之间
2. 随着数据量的增加，内存的占用肯定会不可避免的增高，这时可以将序列化的格式由json改为protobuf



### 视频流模块设计：

#### 获取视频信息问题：

获取一个视频的整体信息包括了：**视频基本信息、评论数、点赞数、视频作者信息**。起初是通过feign client同步调用获取，这样一旦某个接口调研时间较长就会导致整体获取响应时间延长

> 举个栗子：假设一个接口有以下三个任务：A、B、C，各任务的执行分别为2s、2s、3s。如果是同步机制，要想执行当前任务就必须要等前一个任务执行完成，这样总时间九尾就为2+2+3 = 7s。
>
> 如果是异步，在同一个时间段内，三个任务都是在执行的，这样总的执行时长就取决于最耗时的那个任务（任务C），也就是3s。

**改进：创建线程池，使用调用者线程的拒绝策略，异步获取以上四个信息后，再统一返回整体视频信息的结果。**

> 线程池的参数该设置成多少？

一般说来核心线程数的设置：

- 如果是CPU密集型应用，则线程池大小设置为N+1，线程的应用场景：主要是复杂算法
- 如果是IO密集型应用，则线程池大小设置为2N+1，线程的应用场景：主要是：数据库数据的交互，文件上传下载，网络数据传输等等

在这个获取视频信息的功能中，这些Feign任务其实都是数据库的查询任务，所以是IO密集型.

### （聊天模块设计）
#### 使用：
启动后访问http://localhost:8026/page/login.html进行用户名设置即可使用
#### 发送消息、接收消息、展示消息：
采用websocket的方式监听session中的用户，以及客户端发送的消息，对消息进行异步转发给各个客户端到前端展示消息，任何用户
每发一条消息都会存储到数据库，用户下次打开聊天框的时候会请求后端，后端查询数据库，将得到的数据根据时间顺序返回消息列表
前端遍历展示消息
=======

### 关注模块设计：

本模块涉及到：用户关注/取消关注功能的实现，获取用户的关注用户列表和粉丝列表以及获取朋友列表。

整个模块中，我们维护了两个key：

followUserIdKey-----当前用户的关注者id列表，followerIdKey----当前用户的粉丝id列表。

这两个key可以帮助我们快速获取到关注用户列表和粉丝列表，而不用再去数据库中进行查询操作，当然也能帮助我们获取朋友列表。

1、在用户/取消关注功能的实现中，我们需要先从数据库中查询是否有相关用户的记录，有则说明两个用户之间存在过关注/取消关注的操作，此时我们根据actiontype判断是关注操作还是取消关注操作。

若没有相关记录，则是关注操作，插入相关记录即可。

在这上面的过程中，上述这两个key会有相应的增删操作，我们需要通过执行lua脚本，来保证redis命令的原子性—— 当用户关注另一个用户时，对另一个用户来说，他多了一个粉丝。当判断到lua脚本执行失败时，我们需要回滚数据库和redis，保证redis缓存和数据库的数据一致性，操作无误后删除该用户缓存以及视频缓存。



2、对于获取关注用户列表和粉丝列表，我们先尝试从缓存中获取，获取不到再去数据库中获取，通过获取保存在redis中的相应的id列表，调用user服务，返回相应的用户列表，然后再将用户列表缓存进redis中。



3、获取朋友列表，只需比对用户的关注用户id和粉丝id有没有交集，交集部分即为互相关注的用户，调用user服务即可获取朋友列表（message模块还未完善）。



4、缺点：使用redis保存id列表可以减少获取关注用户和粉丝用户操作时都从数据库中查询记录的操作，这在一定程度上可以减轻mysql的负担，但是维护的两类key在关注/取消关注操作时要特别注意原子性问题，这会让关注功能变得繁琐，而且可能会出现数据不一致的问题。





### （待更）

