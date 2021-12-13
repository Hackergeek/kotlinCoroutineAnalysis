/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.coroutines

/**
 * Persistent context for the coroutine. It is an indexed set of [Element] instances.
 * An indexed set is a mix between a set and a map.
 * Every element in this set has a unique [Key].
 */
@SinceKotlin("1.3")
public interface CoroutineContext {
    /**
     * Returns the element with the given [key] from this context or `null`.
     */
     // 由operator修饰的操作符重载，对应“[]”操作符  
     // 通过key获取一个Element对象 
    public operator fun <E : Element> get(key: Key<E>): E?

    /**
     * Accumulates entries of this context starting with [initial] value and applying [operation]
     * from left to right to current accumulator value and each element of this context.
     */
    // 遍历当前集合的每一个Element，并对每一个元素进行operation操作，将操作后的结果进行累加，以initial为起始开始累加，最终返回一个新的CoroutineContext上下文  
    public fun <R> fold(initial: R, operation: (R, Element) -> R): R

    /**
     * Returns a context containing elements from this context and elements from  other [context].
     * The elements from this context with the same key as in the other one are dropped.
     */
     // 由operator修饰的操作符重载，对应“+”操作符；
     // 合并两个CoroutineContext对象中的Element元素，将合并后的上下文返回，如果存在相同key的Element对象，则对其进行覆盖；
     // EmptyCoroutineContext一个空实现的上下文；
     // CombinedContext是CoroutineContext接口的一个实现类，也是链表的具体实现的一个节点，节点存在两个元素：element 当前的节点的集合元素，left CoroutineContext类型，指向链表的下一个元素；
     // 另外plus函数在合并上下文的过程中将Key为ContinuationInterceptor的元素保持在链表的尾部，方便其快速的读取；
    public operator fun plus(context: CoroutineContext): CoroutineContext =
        // 如果待合并的context是一个空上下文，返回当前的上下文
        if (context === EmptyCoroutineContext) this else // fast path -- avoid lambda creation
            // fold遍历context集合  
            context.fold(this) { acc, element -> //acc为当前上下文的集合，element为context集合的元素 
                //移除aac集合中的element元素，并返回移除后的一个集合 
                val removed = acc.minusKey(element.key)
                if (removed === EmptyCoroutineContext) 
                    element // 如果移除后集合是一个空的上下文集合，那么当前element元素为合并后的上下文集合 
                else {
                    // make sure interceptor is always last in the context (and thus is fast to get when present)
                    val interceptor = removed[ContinuationInterceptor] //获取拦截器 
                    if (interceptor == null) 
                        // 如果interceptor为空，生成CombinedContext节点，CombinedContext元素为element，指向的链表节点是removed
                        CombinedContext(removed, element) 
                    else {
                        // 将拦截器移至链表尾部方便读取  
                        val left = removed.minusKey(ContinuationInterceptor)
                        if (left === EmptyCoroutineContext) CombinedContext(element, interceptor) else
                            CombinedContext(CombinedContext(left, element), interceptor)
                    }
                }
            }

    /**
     * Returns a context containing elements from this context, but without an element with
     * the specified [key].
     */
    // 删除对应key的Element元素，返回删除后CoroutineContext  
    public fun minusKey(key: Key<*>): CoroutineContext

    /**
     * Key for the elements of [CoroutineContext]. [E] is a type of element with this key.
     */
    // 集合中每个元素的key  
    public interface Key<E : Element>

    /**
     * An element of the [CoroutineContext]. An element of the coroutine context is a singleton context by itself.
     */
    // 集合中的元素定义，也是一个接口  
    public interface Element : CoroutineContext {
        /**
         * A key of this coroutine context element.
         */
        // 元素的key  
        public val key: Key<*>

        // 通过key获取该元素，对应操作符[]  
        public override operator fun <E : Element> get(key: Key<E>): E? =
            @Suppress("UNCHECKED_CAST")
            if (this.key == key) this as E else null

        // 提供遍历上下文中所有元素的能力。  
        public override fun <R> fold(initial: R, operation: (R, Element) -> R): R =
            operation(initial, this)
        // 删除对应key的Element元素  
        public override fun minusKey(key: Key<*>): CoroutineContext =
            if (this.key == key) EmptyCoroutineContext else this
    }
}
