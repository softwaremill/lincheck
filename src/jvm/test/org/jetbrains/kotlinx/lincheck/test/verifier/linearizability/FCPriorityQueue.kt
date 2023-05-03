/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.UnexpectedExceptionFailure
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import java.util.*
import java.util.concurrent.atomic.AtomicReferenceArray

open class FCPriorityQueue<E : Comparable<E>> {

    private val arraySize = 4

    protected val queue = PriorityQueue<E>()

    protected val combiner = AtomicReferenceArray<Any?>(arraySize)

    private val combinerFlag = atomic(false)

    private val random = Random()


    open fun poll(): E? {
        val pollOp = PollOp()
        val index = allocateInArray(pollOp)

        val action = waitForAction(index, pollOp)

        if (action == Action.LOCKED) {
            var result: E? = null
            withLock {
                result = if (combiner.get(index) != pollOp) {
                    (combiner.get(index) as FCPriorityQueue<*>.PollDone).value as E?
                } else {
                    queue.poll()
                }
                combiner.set(index, null)
                checkCombiner()
            }
            return result
        } else {
            val result = (combiner.get(index) as FCPriorityQueue<*>.PollDone).value as E?
            combiner.set(index, null)
            return result
        }
    }


    fun peek(): E? {

        val peekOp = PeekOp()
        val index = allocateInArray(peekOp)

        val action = waitForAction(index, peekOp)

        if (action == Action.LOCKED) {
            var result: E? = null
            withLock {
                if (combiner.get(index) == peekOp) {
                    result = queue.peek()
                } else {
                    result = (combiner.get(index) as FCPriorityQueue<*>.PeekDone).value as E?
                }
                combiner.set(index, null)
                checkCombiner()
            }
            return result
        } else {
            val result = (combiner.get(index) as FCPriorityQueue<*>.PeekDone).value as E?
            combiner.set(index, null)
            return result
        }
    }

    fun add(element: E) {
        val addOp = AddOp(element)
        val index = allocateInArray(addOp)

        val action = waitForAction(index, addOp)
        if (action == Action.LOCKED) {
            withLock {
                if (combiner.get(index) !is FCPriorityQueue<*>.AddDone) {
                    queue.add(element)
                    combiner.set(index, null)
                    checkCombiner()
                } else {
                    combiner.set(index, null)
                    checkCombiner()
                }
            }
        } else {
            combiner.set(index, null)
        }
    }


    protected fun checkCombiner() {
        for (i in 0 until arraySize) {
            when (val operation = combiner.get(i)) {
                is FCPriorityQueue<*>.PollOp -> {
                    val pollResult = PollDone(queue.poll())
                    combiner.set(i, pollResult)
                }

                is FCPriorityQueue<*>.AddOp -> {
                    queue.add(operation.value as E)
                    combiner.set(i, AddDone())
                }

                is FCPriorityQueue<*>.PeekOp -> {
                    val peekResult = PeekDone(queue.peek())
                    combiner.set(i, peekResult)
                }
            }
        }
    }

    private fun waitForAction(index: Int, op: Any): Action {
        val action: Action
        while (true) {
            if (combinerFlag.compareAndSet(expect = false, update = true)) {
                action = Action.LOCKED
                break
            }
            if (combiner.get(index) != op) {
                action = Action.PROCEED
                break
            }
        }
        return action
    }

    protected fun allocateInArray(thing: Any): Int {
        var index = random.nextInt(arraySize)
        while (!combiner.compareAndSet(index, null, thing)) {
            index = random.nextInt(arraySize)
        }
        return index
    }

    protected fun withLock(block: () -> Unit) {
        try {
            block.invoke()
        } finally {
            combinerFlag.value = false
        }
    }

    enum class Action {
        LOCKED, PROCEED
    }

    inner class AddOp(val value: Any)
    inner class PollDone(val value: Any?)
    inner class PeekDone(val value: Any?)
    inner class AddDone
    inner class PeekOp
    inner class PollOp

}


internal class FCPriorityQueueLincheckTest : AbstractLincheckTest() {
    private val fcPriorityQueue = FCPriorityQueue<Int>()

    @Operation
    fun add(element: Int) = fcPriorityQueue.add(element)

    @Operation
    fun peek() = fcPriorityQueue.peek()

    @Operation
    fun poll() = fcPriorityQueue.poll()

    override fun <O : Options<O, *>> O.customize() {
        sequentialSpecification(PriorityQueueSequential::class.java)
    }
}

internal class FCPriorityQueueLincheckFailingTest :
    AbstractLincheckTest(IncorrectResultsFailure::class, UnexpectedExceptionFailure::class) {
    private val fcPriorityQueue = object : FCPriorityQueue<Int>() {
        override fun poll(): Int? {
            val pollOp = PollOp()
            val index = allocateInArray(pollOp)

//            val action = waitForAction(index, pollOp)
//            if (action == Action.LOCKED) {
            var result: Int? = null
            withLock {
                result = if (combiner.get(index) != pollOp) {
                    (combiner.get(index) as FCPriorityQueue<*>.PollDone).value as Int?
                } else {
                    queue.poll()
                }
                combiner.set(index, null)
                checkCombiner()
            }
            return result
//            }
//            else {
//                val result = (combiner.get(index) as FCPriorityQueue<*>.PollDone).value as E?
//                combiner.set(index, null)
//                return result
//            }
        }
    }

    @Operation
    fun add(element: Int) = fcPriorityQueue.add(element)

    @Operation
    fun peek() = fcPriorityQueue.peek()

    @Operation
    fun poll() = fcPriorityQueue.poll()

    override fun <O : Options<O, *>> O.customize() {
        sequentialSpecification(PriorityQueueSequential::class.java)
    }
}

class PriorityQueueSequential : VerifierState() {
    private val q = PriorityQueue<Int>()

    fun poll(): Int? = q.poll()
    fun peek(): Int? = q.peek()
    fun add(element: Int) {
        q.add(element)
    }

    override fun extractState() = ArrayList(q)
}
