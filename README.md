# Kotlin协程原理解析

基于Kotlin源码1.4.3

## 相关概念

* 协程体：协程中要执行的操作，它是一个被suspend修饰的lambda 表达式;
* 协程体类:编译器会将协程体编译成封装协程体操作的匿名内部类;
* 协程构建器:用于构建协程的函数，比如launch，async;
* 挂起函数:由suspend修饰的函数，挂起函数只能在挂起函数或者协程体中被调用,可以在挂起函数中调用其它挂起函数实现不阻塞当前执行线程的线程切换，比如withContext()，但挂起函数并不一定会挂起，如果没有执行挂起操作;
* 挂起点：一般对应挂起函数被调用的位置;
* 续体:续体的换概念可以理解为挂起后，协程体中剩余要执行代码，笔者在文章中，将其看作为协程体类，在协程体类中封装了协程的要执行的操作，由状态机的状态将操作分割了成不同的片段，每一个状态对应不同代码片段的执行，可以与续体的概念对应。

## 核心类

### 1.SuspendLambda

协程体会编译成一个继承SuspendLambda的类

继承链：SuspendLambda->ContinuationImpl->BaseContinuationImpl->Continuation

### 3.CoroutineContext上下文

CoroutineContext协程的上下文，这是一个数据集合接口声明，协程中Job、Dispatcher调度器都可以是它的元素,CoroutineContext有一个非常好的作用就是我们可以通过它拿到Job、Dispatcher调度器等数据。

CoroutineContext是一个接口，声明的方法展示了它的能力，是一个以Key为索引的数据集合，它的Key是一个interface，每一个元素的类型是Element，而Element又实现CoroutineContext，所以它既可以是一个集合的元素，也可以是一个集合。

CombinedContext是CoroutineContext接口的具体实现类，存在两个属性，其中element是一个Element，代表集合的元素，left是一个CoroutineContext，代表链表的下一个节点。

通过CoroutineContext#plus可以看出，CoroutineContext的数据存储方式是一个左向链表，链表的每一个节点是CombinedContext，并且存在拦截器的情况下，拦截器永远是链表尾部的元素，这样设计目的是因为拦截器的使用频率很高，为了更快的读取拦截器;

### 4.CoroutineStart启动模式

CoroutineStart 是协程的启动模式，存在以下4种模式：

* DEFAULT 立即调度，可以在执行前被取消
* LAZY 需要时才启动，需要start、join等函数触发才可进行调度
* ATOMIC 立即调度，协程肯定会执行，执行前不可以被取消
* UNDISPATCHED 立即在当前线程执行，直到遇到第一个挂起点（可能切线程）

### 5.suspend CoroutineScope.() -> Unit

suspend CoroutineScope.() -> Unit 协程体，这是一个Lambda表达式，也就是协程中要执行的代码块，即上文中launch函数闭包中的代码，这是一个被suspend修饰符修饰的"CoroutineScope扩展函数类型"的参数，这样定义的好处就是可以在协程体中访问这个对象的属性，比如CoroutineContext上下文集合。

### 6.Dispatchers调度器

Dispatchers是协程中提供的线程调度器，用来切换线程，指定协程所运行的线程。

Dispatchers中提供了4种类型调度器：

* Default 默认调度器，适合CPU密集型任务调度器 比如逻辑计算；
* Main UI调度器；
* Unconfined 无限制调度器，对协程执行的线程不做限制，协程恢复时可以在任意线程；
* IO IO调度器，适合IO密集型任务调度器 比如读写文件，网络请求等。

所有的调度器都是CoroutineDispatcher的子类

1.CoroutineDispatcher调度器

CoroutineDispatcher继承AbstractCoroutineContextElement，AbstractCoroutineContextElement是Element接口的一个抽象实现类，而Element又实现CoroutineContext接口，所以调度器本身既是一个CoroutineContext，也可以作为CoroutineContext集合的元素存放其中。

CoroutineDispatcher还实现ContinuationInterceptor接口，ContinuationInterceptor 是一个拦截器的接口定义，也是Kotlin协程提供的拦截器的规范。

首先ContinuationInterceptor实现CoroutineContext.Element接口，Element是集合的元素类型，所以拦截器可以作为CoroutineContext集合的一个元素存放其中。

在ContinuationInterceptor中定义了一个伴生对象Key,它的类型是CoroutineContext.Key<Element>，所以Key也是拦截器作为Element元素的索引，Key是一个伴生对象，可以通过类名访问它，则CoroutineContext[ContinuationInterceptor]就可以在集合中获取到拦截器。这里使用伴生对象作为集合元素的索引，一是伴生对象成员全局唯一，另一个通过类名访问集合元素，更直观。

ContinuationInterceptor#interceptContinuation的作用是对协程体类对象continuation做一次包装，并返回了一个新的Continuation对象，而这个新的Continuation对象本质上是代理了原有的协程体类对象continuation。

2.DispatchedContinuation包装

DispatchedContinuation是出现的第二个Continuation对象(第一个是协程体），代理协程体Continuation对象并持有线程调度器，它的作用就是使用线程调度器将协程体调度到指定的线程执行。

DispatchedContinuation 存在两个参数，拦截器dispatcher（在这里就是指的就是线程调度器Dispatcher）另一个参数continuation指协程体类对象；

DispatchedContinuation也实现了Continuation接口，并重写resumeWith()，内部实现逻辑：

1.如果需要线程调度，则调用dispatcher#dispatch进行调度，而dispatch()的第二个参数是一个runnable对象（这里传参为this，即DispatchedContinuation对象本身，DispatchedContinuation同时还实现了Runnable接口），不难猜出，这个runnable就会运行在调度的线程上；

2.不需要调度则直接调用协程体类continuation对象的resumeWith()，前面的章节中提到,协程体的运行就是协程体类Continuation对象的resumeWith()被触发,所以这里就会让协程体在当前线程运行；

另外还有一个方法resumeCancellableWith()，它和resumeWith()的实现很类似，在不同的启动模式下调度线程的方法调用不同。比如默认的启动模式调用resumeCancellableWith()，ATOMIC启动模式则调用resumeWith()。

在这里的dispatcher是抽象对象，具体的调度逻辑，在相应的调度器实现中封装，比如示例中的Dispatchers.Default。

至此，经过拦截器的处理这时候协程体Continuation对象被包装成了带有调度逻辑的DispatchedContinuation对象。

DispatchedContinuation还继承了DispatchedTask类

在DispatchedContinuation中，重写了delegate属性并赋值为this，所以在DispatchedTask中，delegate就是DispatchedContinuation。

在run()的逻辑中，通过DispatchedContinuation拿到了原始的协程体类Continuation对象，并通过Continuation的扩展方法resume()触发协程体的resumeWith()，到这里就清楚了，只要让这个runable在指定的的线程运行就实现了线程的调度。而调度器的实现就是将这个这个runable对象再指定的线程运行，这也是dispatcher#dispatch()的作用。

3.Dispatchers.Default默认调度器
CoroutineScheduler是一个Kotlin封装的线程池,协程运行的线程由coroutineScheduler分配。

在ExperimentalCoroutineDispatcher中找到调度器dispatch()方法的实现，它的实现很简单，调用coroutineScheduler.dispatch()。

调度器的dispatch(CoroutineContext,Runnable)方法声明有两个参数，其中第二个参数Runnable，在分析DispatchedContinuation的章节中提到，传参为DispatchedContinuation自身，这个DispatchedContinuation也作为coroutineScheduler.dispatch()方法的调用参数。

4.Worker线程

CoroutineScheduler是一个Kotlin实现的线程池，提供协程运行的线程。

CoroutineScheduler是一个线程池，它生成的就是线程，Worker就是Kotlin协程的线程，Worker的实现是继承了Thread，本质上还是对java线程的一次封装，另下文中提及的Task实际为一个DispatchedContinuation对象，DispatchedContinuation继承Task；

Worker存在5种状态：

CPU_ACQUIRED 获取到cpu权限
BLOCKING 正在执行IO阻塞任务
PARKING 已处理完所有任务，线程挂起
DORMANT 初始态
TERMINATED 终止态

Worker继承Thread是一个线程，线程的启动会执行run方法，在Worker的run()中，调用runWorker()，而runWorker()中首先启动了一个有条件的死循环，在线程的状态未被置为TERMINATED终止时，线程一直存活，在循环体中遍历私有和全局任务队列，此时分为两个分支：

1. 如找到Task,则运行该Task
2. 如未找到判断是否存在可窃取的任务，这里的判断条件是根据minDelayUntilStealableTaskNs来进行的，它的定义就是经过本身值的时间之后，至少存在一个可窃取的任务：

minDelayUntilStealableTaskNs非0时，重新扫描一遍队列，是否已有任务，如依然没有任务，进入下次循环，这次循环将线程阻塞minDelayUntilStealableTaskNs纳秒后唤醒，同时将minDelayUntilStealableTaskNs置为0；
minDelayUntilStealableTaskNs为0，没有可偷窃的任务，将线程进行挂起，等待唤醒;

查找任务时，首先检查CPU权限，这里存在两种情况：
1.可以占用cpu权限，这里有一个反饥饿随机数的机制，随机从线程私有队列及全局队列中获取任务，如果获取不到，则通过trySteal(blockingOnly = false)方法，尝试从其它线程获取cpu密集型任务或者IO任务；

globalFirst是一种反饥饿机制，作用就是概率性的从本地队列及全局队列中获取Task，确保内部和外部任务的进度;

2.不能占用cpu权限，这里源码中有一段注释：If we can't acquire a CPU permit -- attempt to find blocking task，在获取不到cpu许可时，尝试找到一个阻塞任务。这里的处理是优先取本地队列任务，未获取到则取全局IO队列，都未获取到，则通过trySteal(blockingOnly = true)方法，尝试从其它线程获取IO任务；

新创建线程存在两个限制条件：

1.非阻塞线程数小于核心线程数量；
2.已创建的线程数量小于最大线程数量；

当创建好一个线程之后，如果满足非阻塞线程数量为1，同时核心数量总数大于1时，再次创建一个新的线程，用来“偷窃”其它线程的任务，这样做的目的是为了提高效率;

在beforeTask()的处理中，如果当前任务为IO任务，且当前线程占有CPU权限，会对权限进行释放，紧接着会唤醒一个线程，如没有待唤醒的线程，会尝试新建一个线程并启动，IO任务占用的CPU很少，这样做可以让新唤醒或者新建的线程占用cpu的时间片执行其他task;

executeTask()方法执行一个任务，在执行任务前，及任务结束后，都对阻塞型任务做了一些处理，这是因为阻塞的任务开始后不需要或者占用很少cpu的权限，所以当前线程如果占有cpu权限，为了提高资源的利用率，可以释放cpu权限，而且可以通过唤醒或者新建一个线程去占用这个cpu时间片去执行其它的任务，当任务结束后，也将线程的状态重置为初始态;

在CoroutineScheduler#dispatch()中，会将Runbale对象封装成一个Task,如当前线程是一个Worker，优先将task添加至当前线程的任务队列，否则会将任务添加到Global队列中，最后进行线程唤起或者创建新线程执行该任务；

至此，对Kotlin协程中一些核心类进行了分析，对其作用做个总结如下：
协程体类：封装协程体的操作逻辑。
Dispatchers ：提供4种线程调度器。
CoroutineDispatcher ：调度器的父类，CoroutineDispatcher#interceptContinuation()将协程体类对象包装成DispatchedContinuation。
DispatchedContinuation：代理协程体类对象，并持有线程调度器。
CoroutineScheduler:线程池，提供协程运行的线程。
Worker：Worker的实现是继承了Thread，本质上还是对java线程的一次封装。

IO调度器是Dispatchers.Default内的一个变量，并且它和Default调度器共享CoroutineScheduler线程池。

launch函数的返回值是一个Job,通过launch或者async创建的协程都会返回一个Job实例，它的作用是管理协程的生命周期，也作为协程的唯一标志。

Job的状态：
New: 新建
Active: 活跃
Cancelling: 正在取消
Cancelled: 已取消
Completing: 完成中
Completed: 已完成

StandaloneCoroutine具体来说是一个协程对象，继承AbstractCoroutine，并重写了handleJobException()异常处理方法，所有的协程对象都继承AbstractCoroutine。
AbstractCoroutine继承实现了JobSupport、Job、Continuation、CoroutineScope。

Job实现了CoroutineContext.Element，它是CoroutineContext集合的元素类型，并且Key为Job。Job内提供了isActive、isCompleted、isCancelled属性用以判断协程的状态，以及取消协程、等待协程完成、监听协程状态的操作


## 参考链接

[硬核万字解读：Kotlin 协程原理解析](https://toutiao.io/posts/vtq5kjj/preview)