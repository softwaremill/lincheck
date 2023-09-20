/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.test.*
import java.util.concurrent.*

@Param(name = "value", gen = IntGen::class, conf = "1:5")
class SkipListMapTest : AbstractLincheckTest() {
    private val skiplist = ConcurrentSkipListMap<Int, Int>()

    @Operation
    fun put(key: Int, value: Int) = skiplist.put(key, value)

    @Operation
    fun get(key: Int) = skiplist.get(key)

    @Operation
    fun containsKey(key: Int) = skiplist.containsKey(key)

    @Operation
    fun remove(key: Int) = skiplist.remove(key)

    override fun <O : Options<O, *>> O.customize() {
        // Make Lincheck deterministic in init/post parts.
        actorsBefore(0)
        actorsAfter(0)
    }
}
