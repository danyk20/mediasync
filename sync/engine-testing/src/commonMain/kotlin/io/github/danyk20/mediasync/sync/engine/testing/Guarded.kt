/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine.testing

import io.github.danyk20.mediasync.core.util.Lock

/*
 * Collections that the sessions' threads share in the fakes. Every call takes the collection's own lock, so no
 * element is lost or seen half-written. Tests read them after the sessions ended.
 */

/** A list every call of which takes the list's lock. */
class GuardedList<T> : AbstractMutableList<T>() {
    private val lock = Lock()
    private val items = mutableListOf<T>()

    override val size: Int get() = lock.withLock { items.size }

    override fun get(index: Int): T = lock.withLock { items[index] }

    override fun set(index: Int, element: T): T = lock.withLock { items.set(index, element) }

    override fun add(index: Int, element: T) = lock.withLock { items.add(index, element) }

    override fun add(element: T): Boolean = lock.withLock { items.add(element) }

    override fun removeAt(index: Int): T = lock.withLock { items.removeAt(index) }

    override fun clear() = lock.withLock { items.clear() }

    /** Adds [element] and returns its index, in one step. */
    fun addIndexed(element: T): Int = lock.withLock {
        items.add(element)
        items.size - 1
    }
}

/** A set every call of which takes the set's lock. Iterators work on a copy. */
class GuardedSet<T> : AbstractMutableSet<T>() {
    private val lock = Lock()
    private val items = mutableSetOf<T>()

    override val size: Int get() = lock.withLock { items.size }

    override fun add(element: T): Boolean = lock.withLock { items.add(element) }

    override fun contains(element: T): Boolean = lock.withLock { element in items }

    override fun remove(element: T): Boolean = lock.withLock { items.remove(element) }

    override fun iterator(): MutableIterator<T> = lock.withLock { items.toMutableSet().iterator() }

    override fun clear() = lock.withLock { items.clear() }
}

/** A map every call of which takes the map's lock. [entries] is a copy: changing it does not change the map. */
class GuardedMap<K, V>(initial: Map<K, V> = emptyMap()) : AbstractMutableMap<K, V>() {
    private val lock = Lock()
    private val map = HashMap(initial)

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = lock.withLock { map.entries.toMutableSet() }

    override val size: Int get() = lock.withLock { map.size }

    override fun get(key: K): V? = lock.withLock { map[key] }

    override fun containsKey(key: K): Boolean = lock.withLock { map.containsKey(key) }

    override fun put(key: K, value: V): V? = lock.withLock { map.put(key, value) }

    override fun remove(key: K): V? = lock.withLock { map.remove(key) }

    override fun clear() = lock.withLock { map.clear() }

    /** Like [kotlin.collections.getOrPut], atomic: [defaultValue] runs once per key, under the lock. */
    fun getOrPut(key: K, defaultValue: () -> V): V = lock.withLock { map.getOrPut(key, defaultValue) }
}

/** A counter that threads share. */
class GuardedCounter {
    private val lock = Lock()
    private var value = 0

    fun get(): Int = lock.withLock { value }

    fun set(newValue: Int) = lock.withLock { value = newValue }

    fun incrementAndGet(): Int = lock.withLock { ++value }

    /** Returns the value before the increment, as AtomicInteger does. */
    fun getAndIncrement(): Int = lock.withLock { value++ }
}
