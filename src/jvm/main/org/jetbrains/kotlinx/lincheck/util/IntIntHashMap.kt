/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

/**
 * An implementation of a HashMap using integer pairs.
 * It provides methods for adding, getting and clearing integer key-value pairs.
 * This map resizes automatically when it reaches a certain load factor.
 *
 * @property capacity The initial capacity of the map. It gets doubled every time a resize operation is triggered.
 * @constructor Creates an empty map with a specified initial capacity.
 */
class IntIntHashMap(private var capacity: Int = DEFAULT_CAPACITY) {
    // Array to store the key-value pairs. The keys and values are stored in an alternating sequence.
    private var keyValuePairs: IntArray

    // Current number of key-value pairs in the map.
    private var size: Int

    // Initializes the key-value pairs array and sets the size to 0.
    init {
        if (capacity <= 0) {
            throw IllegalArgumentException("Capacity should be greater than 0.")
        }
        keyValuePairs = IntArray(capacity * 2) { UNUSED }
        size = 0
    }

    /**
     * Adds a new key-value pair to the map or updates the value of an existing key.
     * If the load factor is reached, it triggers a resize operation.
     *
     * @param key The key to be added.
     * @param value The value to be associated with the key.
     */
    fun put(key: Int, value: Int) {
        if (size >= capacity * LOAD_FACTOR) {
            resize()
        }

        var index = hash(key) * 2
        while (keyValuePairs[index] != UNUSED) {
            if (keyValuePairs[index] == key) {
                keyValuePairs[index + 1] = value
                return
            }
            index = (index + 2) % (capacity * 2)
        }
        keyValuePairs[index] = key
        keyValuePairs[index + 1] = value
        size++
    }

    /**
     * Returns the value to which the specified key is mapped, or UNUSED if this map contains no mapping for the key.
     *
     * @param key The key whose associated value is to be returned.
     * @return The value to which the specified key is mapped, or UNUSED if this map contains no mapping for the key.
     */
    fun get(key: Int): Int {
        var index = hash(key) * 2
        while (keyValuePairs[index] != UNUSED) {
            if (keyValuePairs[index] == key) {
                return keyValuePairs[index + 1]
            }
            index = (index + 2) % (capacity * 2)
        }
        return UNUSED // or any sentinel value indicating that key was not found
    }

    /**
     * Computes the hash of the key.
     *
     * @param key The key to compute the hash for.
     * @return The computed hash.
     */
    private fun hash(key: Int): Int {
        return key % capacity
    }

    /**
     * Resizes the map to double its current capacity. This involves rehashing of the keys.
     */
    private fun resize() {
        val oldCapacity = capacity
        capacity *= 2
        val oldKeyValuePairs = keyValuePairs
        keyValuePairs = IntArray(capacity * 2) { UNUSED }
        size = 0

        for (i in 0 until oldCapacity * 2 step 2) {
            if (oldKeyValuePairs[i] != UNUSED) {
                put(oldKeyValuePairs[i], oldKeyValuePairs[i + 1])
            }
        }
    }

    /**
     * Clears the map, removing all key-value pairs.
     */
    fun clear() {
        keyValuePairs.fill(UNUSED)
        size = 0
    }

    companion object {
        // Default capacity of the map when not provided in the constructor.
        private const val DEFAULT_CAPACITY = 128
        // Load factor for triggering a resize operation.
        private const val LOAD_FACTOR = 0.75
        // Sentinel value for unused spots in the key-value pairs array.
        private const val UNUSED = -1
    }
}
