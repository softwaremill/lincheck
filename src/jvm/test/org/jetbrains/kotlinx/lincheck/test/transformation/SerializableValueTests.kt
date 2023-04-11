/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.test.*
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.util.concurrent.atomic.*

class SerializableResultTest : AbstractLincheckTest() {
    private val counter = AtomicReference(ValueHolder(0))

    @Operation
    fun getAndSet(key: Int) = counter.getAndSet(ValueHolder(key))

    override fun LincheckOptionsImpl.customize() {
        testingTimeInSeconds = 1
    }
}

class SerializableJavaUtilResultTest : AbstractLincheckTest() {
    private val value = listOf(1, 2)

    @Operation
    fun get(key: Int) = value

    override fun LincheckOptionsImpl.customize() {
        testingTimeInSeconds = 1
    }
}

class SerializableJavaUtilResultIncorrectTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private val value = mutableListOf(1, 2)

    @Operation
    fun get(key: Int): List<Int> {
        value[0]++
        return value
    }
}

class SerializableNullResultTest {
    @Test
    fun test() {
        val a = ValueResult(null)
        val value = ValueHolder(0)
        val loader = TransformationClassLoader { CancellabilitySupportClassTransformer(it) }
        val transformedValue = value.convertForLoader(loader)
        val b = ValueResult(transformedValue)
        // check that no exception was thrown
        assertFalse(a == b)
        assertFalse(b == a)
    }
}

@Param(name = "key", gen = ValueHolderGen::class)
class SerializableParameterTest : AbstractLincheckTest() {
    private val counter = AtomicInteger(0)

    @Operation
    fun operation(@Param(name = "key") key: ValueHolder): Int = counter.addAndGet(key.value)

    override fun LincheckOptionsImpl.customize() {
        testingTimeInSeconds = 1
    }
}

@Param(name = "key", gen = ValueHolderGen::class)
class SerializableParameterIncorrectTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private var counter = 0

    @Operation
    fun operation(@Param(name = "key") key: ValueHolder): Int {
        counter += key.value
        return counter
    }
}

class ValueHolderGen(randomProvider: RandomProvider, conf: String) : ParameterGenerator<ValueHolder> {
    override fun generate(): ValueHolder {
        return listOf(ValueHolder(1), ValueHolder(2)).random()
    }
}

@Param(name = "key", gen = JavaUtilGen::class)
class SerializableJavaUtilParameterTest : AbstractLincheckTest() {
    @Operation
    fun operation(@Param(name = "key") key: List<Int>): Int = key[0] + key.sum()

    override fun LincheckOptionsImpl.customize() {
        testingTimeInSeconds = 1
    }
}

class JavaUtilGen(randomProvider: RandomProvider, conf: String) : ParameterGenerator<List<Int>> {
    override fun generate() = listOf(1, 2)
}

data class ValueHolder(val value: Int) : Serializable

@Param(name = "key", gen = NullGen::class)
class SerializableNullParameterTest : AbstractLincheckTest() {
    @Operation
    fun operation(@Param(name = "key") key: List<Int>?): Int = key?.sum() ?: 0

    override fun LincheckOptionsImpl.customize() {
        testingTimeInSeconds = 1
    }
}

class NullGen(randomProvider: RandomProvider, conf: String) : ParameterGenerator<List<Int>?> {
    override fun generate() = null
}
