# Kotlin协程原理解析

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

## 参考链接

[硬核万字解读：Kotlin 协程原理解析](https://toutiao.io/posts/vtq5kjj/preview)