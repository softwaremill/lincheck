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

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategyStateHolder
import org.jetbrains.kotlinx.lincheck.strategy.managed.getObjectNumbersMap
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy
import java.util.concurrent.atomic.AtomicInteger

// This is org.jetbrains.kotlinx.lincheck.IdeaPluginKt class

const val MINIMAL_PLUGIN_VERSION = "0.0.1"

// Invoked by Lincheck after the minimization is applied. DO NOT FORGET TO TURN OFF THE RUNNER TIMEOUTS.
fun testFailed(trace: Array<String>, version: String?, minimalPluginVersion: String) {
}

fun ideaPluginEnabled(): Boolean {
    return false // should be replaced with `true` to debug the failure
}

fun replay(): Boolean {
    return false // should be replaced with `true` to replay the failure
}

fun beforeEvent(eventId: Int, type: String) {
    val strategy = (ManagedStrategyStateHolder.strategy!! as ModelCheckingStrategy)
    strategy.enterIgnoredSection(strategy.currentThreadNumber())
    if (needVisualization()) {
        runCatching {
            val testObject =
                ((ManagedStrategyStateHolder.strategy as ModelCheckingStrategy).runner as ParallelThreadsRunner).testInstance
            val resultArray = arrayListOf<Any>()
            traverseTestObject(testObject)
            val numbersMap = getObjectNumbersMap()

            numbersMap.forEach { (clazz, innerMap) -> // getObjectNumbersMap()
                innerMap.forEach { (labeledObject, number) ->
                    resultArray.add(clazz)
                    resultArray.add(labeledObject)
                    resultArray.add(number)
                }
            }

            visualizeInstance(testObject, resultArray.toTypedArray())
        }
    }
    strategy.leaveIgnoredSection(strategy.currentThreadNumber())
}

private fun createMockObject(): Pair<DataHolder, MutableMap<Class<out Any>, MutableMap<Any, Int>>> {
    val resultMap = mutableMapOf<Class<out Any>, MutableMap<Any, Int>>()
    val stringsMap = mutableMapOf<Any, Int>()
    val dataHoldersMap = mutableMapOf<Any, Int>()

    val nestedObject = DataHolder(1, AtomicInteger(1), arrayOf("abc", "rr", "+-"))
    val rootObject = DataHolder(1, AtomicInteger(1), arrayOf("abc", "!is", "f"), nestedObject)

    stringsMap[nestedObject.data] = 1
    stringsMap[rootObject.data] = 2

    dataHoldersMap[nestedObject] = 3
    dataHoldersMap[rootObject] = 4

    resultMap[DataHolder::class.java] = dataHoldersMap
    resultMap[(emptyArray<String>())::class.java] = stringsMap

    return rootObject to resultMap
}

data class DataHolder(
    val id: Int,
    val atomicId: AtomicInteger,
    val data: Array<String>,
    val otherHolder: DataHolder? = null
)
fun visualizeInstance(testObject: Any, numbersArrayMap: Array<Any>) {}
fun needVisualization(): Boolean = false // may be replaced with 'true' in plugin

fun onThreadChange() {}
