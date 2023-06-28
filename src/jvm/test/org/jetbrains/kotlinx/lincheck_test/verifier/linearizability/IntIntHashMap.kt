/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.verifier.linearizability

import kotlinx.atomicfu.AtomicIntArray
import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest

/**
 * Int-to-Int hash map with open addressing and linear probes.
 */
open class IntIntHashMap {
    private val core = atomic(Core(INITIAL_CAPACITY))

    /**
     * Returns value for the corresponding key or zero if this key is not present.
     *
     * @param key a positive key.
     * @return value for the corresponding or zero if this key is not present.
     * @throws IllegalArgumentException if key is not positive.
     */
    open operator fun get(key: Int): Int {
        require(key > 0) { "Key must be positive: $key" }
        while (true) {
            val core = getCore()

            val res = core.getInternal(key)
            if (res != NEEDS_REHASH)
                return toValue(res)

            core.rehash()
            this.core.compareAndSet(core, core.next.value!!)
        }
    }

    internal fun getCore() = core.value

    /**
     * Changes value for the corresponding key and returns old value or zero if key was not present.
     *
     * @param key   a positive key.
     * @param value a positive value.
     * @return old value or zero if this key was not present.
     * @throws IllegalArgumentException if key or value are not positive, or value is equal to
     * [Integer.MAX_VALUE] which is reserved.
     */
    fun put(key: Int, value: Int): Int {
        require(key > 0) { "Key must be positive: $key" }
        require(isValue(value)) { "Invalid value: $value" }
        return toValue(putCore(key, value))
    }

    /**
     * Removes value for the corresponding key and returns old value or zero if key was not present.
     *
     * @param key a positive key.
     * @return old value or zero if this key was not present.
     * @throws IllegalArgumentException if key is not positive.
     */
    fun remove(key: Int): Int {
        require(key > 0) { "Key must be positive: $key" }
        return toValue(putCore(key, DEL_VALUE))
    }

    private fun putCore(key: Int, value: Int): Int {
        while (true) {
            val core = getCore()
            val res = core.putInternal(key, value)
            if (res != NEEDS_REHASH) return res

            core.rehash()
            this.core.compareAndSet(core, core.next.value!!)
        }
    }


    internal class Core(capacity: Int) {
        // Pairs of <key, value> here, the actual size of the map is twice as big.
        private val _size = capacity * 2
        private val array = AtomicIntArray(_size)
        private val _shift: Int

        val next = atomic<Core?>(null)

        init {
            val mask = capacity - 1
            assert(mask > 0 && mask and capacity == 0) { "Capacity must be power of 2: $capacity" }
            _shift = 32 - Integer.bitCount(mask)
        }

        fun getInternal(key: Int): Int {
            var hash = index(key)

            for (probes in 0 until MAX_PROBES) {
                val internalValue = array[hash + 1].value
                if (internalValue == MOVED_VALUE) return NEEDS_REHASH

                val internalKey = array[hash].value
                if (internalKey == NULL_KEY) return NULL_VALUE
                if (internalKey == key) return unlock(internalValue)

                hash = (hash + 2) % _size
            }

            return NULL_VALUE
        }

        fun putInternal(key: Int, value: Int): Int {
            var hash = index(key)

            for (probes in 0 until MAX_PROBES) {
                val internalValue: Int = array[hash + 1].value
                if (isLocked(internalValue)) return NEEDS_REHASH

                val internalKey: Int = array[hash].value
                if (internalKey == NULL_KEY) {
                    if (value == DEL_VALUE) return NULL_VALUE
                    if (setKeyValue(hash, key, value)) return internalValue
                    continue
                }
                if (internalKey == key) {
                    if (array[hash + 1].compareAndSet(internalValue, value)) return internalValue
                    continue
                }

                hash = (hash + 2) % _size
            }

            return NEEDS_REHASH
        }

        fun rehash() {
            fun getUnlocked(index: Int): Int {
                while (true) {
                    val value = array[index].value
                    if (isLocked(value)) return unlock(value)
                    if (array[index].compareAndSet(value, lock(value))) return unlock(value)
                }
            }

            next.compareAndSet(null, Core(_size * 2))

            for (i in 0 until _size step 2) {
                if (array[i + 1].value == MOVED_VALUE) continue

                val value = getUnlocked(i + 1)
                next.value!!.move(array[i].value, value)

                array[i + 1].getAndSet(MOVED_VALUE)
            }
        }

        fun move(key: Int, value: Int) {
            var hash = index(key)

            for (probes in 0 until MAX_PROBES) {
                val internalKey: Int = array[hash].value
                if (internalKey == NULL_KEY) {
                    if (setKeyValue(hash, key, value)) return
                    continue
                }
                if (internalKey == key) {
                    array[hash + 1].compareAndSet(NULL_VALUE, value)
                    return
                }

                hash = (hash + 2) % _size
            }

            throw IllegalStateException("Probes are exceeded")
        }

        /**
         * Returns an initial index in map to look for a given key.
         */
        private fun index(key: Int): Int = (key * MAGIC ushr _shift) * 2

        private fun setKeyValue(hash: Int, key: Int, value: Int): Boolean =
            array[hash].compareAndSet(NULL_KEY, key) && array[hash + 1].compareAndSet(NULL_VALUE, value)
    }
}

private const val MAGIC = -0x61c88647 // golden ratio
private const val MOST_LEFT_BIT = 1 shl 31 // int with only most left bit 1
private const val INITIAL_CAPACITY = 2 // !!! DO NOT CHANGE INITIAL CAPACITY !!!
private const val MAX_PROBES = 8 // max number of probes to find an item
private const val NULL_KEY = 0 // missing key (initial value)
private const val NULL_VALUE = 0 // missing value (initial value)
private const val DEL_VALUE = Int.MAX_VALUE // mark for removed value
private const val MOVED_VALUE = Int.MIN_VALUE // mark for moved value
private const val NEEDS_REHASH = -1 // returned by `putInternal` to indicate that rehash is needed

// Checks is the value is in the range of allowed values
private fun isValue(value: Int): Boolean = value in (1 until DEL_VALUE)

// Converts internal value to the public results of the methods
private fun toValue(value: Int): Int = if (isValue(value)) value else 0

private fun lock(value: Int): Int = value or MOST_LEFT_BIT
private fun unlock(value: Int): Int = value and MOST_LEFT_BIT.inv()
private fun isLocked(value: Int): Boolean = value and MOST_LEFT_BIT != 0


@Param.Params(
    value = [
        Param(name = "value", gen = IntGen::class, conf = "1:100"),
        Param(name = "key", gen = IntGen::class, conf = "1:6")
    ]
)
internal class IntIntHashMapLincheckTest : AbstractLincheckTest() {
    private val intIntHashMap = object : IntIntHashMap() {

    }

    @Operation(params = ["key"])
    fun remove(key: Int) = intIntHashMap.remove(key)

    @Operation(params = ["key", "value"])
    fun put(key: Int, value: Int) = intIntHashMap.put(key, value)

    @Operation(params = ["key"])
    fun get(key: Int) = intIntHashMap.get(key)
}


@Param.Params(
    value = [
        Param(name = "value", gen = IntGen::class, conf = "1:100"),
        Param(name = "key", gen = IntGen::class, conf = "1:6")
    ]
)
internal class IntIntHashMapLincheckFailingTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private val intIntHashMap = object : IntIntHashMap() {
        override fun get(key: Int): Int {
            require(key > 0) { "Key must be positive: $key" }
            while (true) {
                val core = getCore()

                val res = core.getInternal(key)
                // BUG! Forgot to rehash
                return toValue(res)
            }
        }
    }

    @Operation(params = ["key"])
    fun remove(key: Int) = intIntHashMap.remove(key)

    @Operation(params = ["key", "value"])
    fun put(key: Int, value: Int) = intIntHashMap.put(key, value)

    @Operation(params = ["key"])
    fun get(key: Int) = intIntHashMap.get(key)
}

