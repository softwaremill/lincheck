/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.ensure
import org.jetbrains.kotlinx.lincheck.strategy.managed.Remapping
import org.jetbrains.kotlinx.lincheck.strategy.managed.resynchronize
import org.jetbrains.kotlinx.lincheck.utils.*


/**
 * Execution represents a set of events belonging to single program's execution.
 */
interface Execution<out E : ThreadEvent> : Collection<E> {
    val threadMap: ThreadMap<SortedList<E>>

    operator fun get(tid: ThreadID): SortedList<E>? =
        threadMap[tid]

    override fun contains(element: @UnsafeVariance E): Boolean =
        get(element.threadId, element.threadPosition) == element

    override fun containsAll(elements: Collection<@UnsafeVariance E>): Boolean =
        elements.all { contains(it) }

    override fun iterator(): Iterator<E> =
        threadIDs.map { get(it)!! }.asSequence().flatten().iterator()

}

interface MutableExecution<E: ThreadEvent> : Execution<E> {
    fun add(event: E)
    fun cut(tid: ThreadID, pos: Int)
}

val Execution<*>.threadIDs: Set<ThreadID>
    get() = threadMap.keys

val Execution<*>.maxThreadID: ThreadID
    get() = threadIDs.maxOrNull() ?: -1

fun Execution<*>.getThreadSize(tid: ThreadID): Int =
    get(tid)?.size ?: 0

fun Execution<*>.lastPosition(tid: ThreadID): Int =
    getThreadSize(tid) - 1

fun<E : ThreadEvent> Execution<E>.firstEvent(tid: ThreadID): E? =
    get(tid)?.firstOrNull()

fun<E : ThreadEvent> Execution<E>.lastEvent(tid: ThreadID): E? =
    get(tid)?.lastOrNull()

operator fun<E : ThreadEvent> Execution<E>.get(tid: ThreadID, pos: Int): E? =
    get(tid)?.getOrNull(pos)

fun<E : ThreadEvent> Execution<E>.nextEvent(event: E): E? =
    get(event.threadId)?.let { events ->
        require(events[event.threadPosition] == event)
        events.getOrNull(event.threadPosition + 1)
    }

fun<E : ThreadEvent> MutableExecution<E>.cut(event: E) =
    cut(event.threadId, event.threadPosition)

fun<E : ThreadEvent> MutableExecution<E>.cutNext(event: E) =
    cut(event.threadId, 1 + event.threadPosition)

fun<E : ThreadEvent> Execution(nThreads: Int): Execution<E> =
    MutableExecution(nThreads)

fun<E : ThreadEvent> MutableExecution(nThreads: Int): MutableExecution<E> =
    ExecutionImpl(ArrayMap(*(0 until nThreads)
        .map { (it to sortedArrayListOf<E>()) }
        .toTypedArray()
    ))

fun<E : ThreadEvent> executionOf(vararg pairs: Pair<ThreadID, List<E>>): Execution<E> =
    mutableExecutionOf(*pairs)

fun<E : ThreadEvent> mutableExecutionOf(vararg pairs: Pair<ThreadID, List<E>>): MutableExecution<E> =
    ExecutionImpl(ArrayMap(*pairs
        .map { (tid, events) -> (tid to SortedArrayList(events)) }
        .toTypedArray()
    ))

private class ExecutionImpl<E : ThreadEvent>(
    override val threadMap: ArrayMap<SortedMutableList<E>>
) : MutableExecution<E> {

    override var size: Int = threadMap.values.sumOf { it.size }
        private set

    override fun isEmpty(): Boolean =
        (size > 0)

    override fun get(tid: ThreadID): SortedMutableList<E>? =
        threadMap[tid]

    override fun add(event: E) {
        ++size
        threadMap[event.threadId]!!
            .ensure { event.parent == it.lastOrNull() }
            .also { it.add(event) }
    }

    override fun cut(tid: ThreadID, pos: Int) {
        val threadEvents = get(tid) ?: return
        size -= (threadEvents.size - pos)
        threadEvents.cut(pos)
    }

    override fun equals(other: Any?): Boolean =
        (other is ExecutionImpl<*>) && (size == other.size) && (threadMap == other.threadMap)

    override fun hashCode(): Int =
       threadMap.hashCode()

    override fun toString(): String = buildString {
        appendLine("<======== Execution Graph @${hashCode()} ========>")
        threadIDs.toList().sorted().forEach { tid ->
            val events = threadMap[tid] ?: return@forEach
            appendLine("[-------- Thread #${tid} --------]")
            for (event in events) {
                appendLine("$event")
                if (event.dependencies.isNotEmpty()) {
                    appendLine("    dependencies: ${event.dependencies.joinToString()}}")
                }
            }
        }
    }

}

fun<E : ThreadEvent> Execution<E>.toFrontier(): ExecutionFrontier<E> =
    toMutableFrontier()

fun<E : ThreadEvent> Execution<E>.toMutableFrontier(): MutableExecutionFrontier<E> =
    threadIDs.map { tid ->
        tid to get(tid)?.lastOrNull()
    }.let {
        mutableExecutionFrontierOf(*it.toTypedArray())
    }

fun<E : ThreadEvent> Execution<E>.buildIndexer() = object : Indexer<E> {

    private val events = enumerationOrderSortedList()

    private val eventIndices = threadMap.mapValues { (_, threadEvents) ->
        List(threadEvents.size) { pos ->
            events.indexOf(threadEvents[pos]).ensure { it >= 0 }
        }
    }

    override fun get(i: Int): E {
        return events[i]
    }

    override fun index(x: E): Int {
        return eventIndices[x.threadId]!![x.threadPosition]
    }

}

fun<E : ThreadEvent> Execution<E>.isBlockedDanglingRequest(event: E): Boolean {
    return event.label.isRequest && event.label.isBlocking &&
            (event == this[event.threadId]?.last())
}

fun<E : ThreadEvent> Execution<E>.computeVectorClock(event: E, relation: Relation<E>): VectorClock {
    check(this is ExecutionImpl<E>)
    val clock = MutableVectorClock(threadMap.capacity)
    for (i in 0 until threadMap.capacity) {
        val threadEvents = get(i) ?: continue
        clock[i] = (threadEvents.binarySearch { !relation(it, event) } - 1)
            .coerceAtLeast(-1)
    }
    return clock
}

fun<E : ThreadEvent> Execution<E>.enumerationOrderSortedList(): List<E> =
    this.sorted()

fun<E : ThreadEvent> Execution<E>.resynchronize(algebra: SynchronizationAlgebra): Remapping {
    val remapping = Remapping()
    // TODO: refactor, simplify & unify cases
    for (event in enumerationOrderSortedList()) {
        remapping.resynchronize(event, algebra)
    }
    return remapping
}

typealias ExecutionCounter = IntArray

abstract class ExecutionRelation<E : ThreadEvent>(
    val execution: Execution<E>,
    val respectsProgramOrder: Boolean = true,
) : Relation<E> {

    val indexer = execution.buildIndexer()

    fun buildExternalCovering() = object : Covering<E> {

        init {
            require(respectsProgramOrder)
        }

        val relation = this@ExecutionRelation

        private val nThreads = 1 + execution.maxThreadID

        val covering: List<List<E>> = execution.indices.map { index ->
            val event = indexer[index]
            val clock = execution.computeVectorClock(event, relation)
            (0 until nThreads).mapNotNull { tid ->
                if (tid != event.threadId && clock[tid] != -1)
                    execution[tid, clock[tid]]
                else null
            }
        }

        override fun invoke(x: E): List<E> =
            covering[indexer.index(x)]

    }

}

fun<E : ThreadEvent> executionRelation(
    execution: Execution<E>,
    relation: Relation<E>,
    respectsProgramOrder: Boolean = true,
) = object : ExecutionRelation<E>(execution, respectsProgramOrder) {

    override fun invoke(x: E, y: E): Boolean = relation(x, y)

}