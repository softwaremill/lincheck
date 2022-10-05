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

import org.jetbrains.kotlinx.lincheck.TransformationClassLoader.REMAPPED_PACKAGE_CANONICAL_NAME
import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategyStateHolder
import org.jetbrains.kotlinx.lincheck.strategy.managed.getObjectNumber
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.math.BigInteger
import java.util.*
import java.util.concurrent.atomic.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.Continuation

//fun main() {
//    val o = Channel<Int>(2)
//    o.offer(42)
//    o.offer(43)
//    GlobalScope.launch {
//        o.send(44)
//    }
//    Thread.sleep(100)

//    val o = LockFreeSet()
//    o.add(42)
//    o.add(43)
//    o.add(45)
//    SourceStringReader(visualize(o)).outputImage(FileOutputStream(File("./test.png")))
//}

fun testObjectPlantUMLVisualisation() =
    ((ManagedStrategyStateHolder.strategy as ModelCheckingStrategy).runner as ParallelThreadsRunner).testInstance.let {
        visualize(it)
    }

fun visualize(obj: Any): String {
    val sb = StringBuilder()
    sb.appendLine("@startuml")
    visualize(obj, sb, Collections.newSetFromMap(IdentityHashMap()))
    sb.appendLine("@enduml")
    return sb.toString()
}

private fun name(obj: Any): String =
    obj.javaClass.canonicalName.replace("[]", "_ARRAY_").replace("tran\$f*rmed", "______transformed_____") + "___________________" + getObjectNumber(obj.javaClass, obj)

private fun title(obj: Any): String =
    obj.javaClass.simpleName + "#" + getObjectNumber(obj.javaClass, obj)

private fun visualize(obj: Any, sb: StringBuilder, visualized: MutableSet<Any>) {
    if (!visualized.add(obj)) return
    val name = name(obj)
    val title = title(obj)
    var clazz: Class<*>? = obj.javaClass
    if (clazz!!.isArray) {
        sb.appendLine("map \"$title\" as $name {")
        if (obj is IntArray) {
            obj.forEachIndexed { i, o ->
                sb.appendLine("$i => $o")
            }
        } else if (obj is LongArray) {
            obj.forEachIndexed { i, o ->
                sb.appendLine("$i => $o")
            }
        } else if (obj is ByteArray) {
            obj.forEachIndexed { i, o ->
                sb.appendLine("$i => $o")
            }
        } else if (obj is CharArray) {
            obj.forEachIndexed { i, o ->
                sb.appendLine("$i => $o")
            }
        } else if (obj is BooleanArray) {
            obj.forEachIndexed { i, o ->
                sb.appendLine("$i => $o")
            }
        } else if (obj is FloatArray) {
            obj.forEachIndexed { i, o ->
                sb.appendLine("$i => $o")
            }
        } else if (obj is DoubleArray) {
            obj.forEachIndexed { i, o ->
                sb.appendLine("$i => $o")
            }
        } else if (obj is Array<*>) {
            val toVisualize = ArrayList<Any>()
            obj.forEachIndexed { i, o ->
                // TODO support arrays properly
                if (o == null) {
                    sb.appendLine("$i => null")
                } else {
                    val s = stringRepresentation(o)
                    if (s != null) {
                        sb.appendLine("$i => $s")
                    } else {
                        sb.appendLine("$i => ${title(o)}")
                    }
                }
            }
            toVisualize.forEach { visualize(it, sb, visualized) }
        }
        sb.appendLine("}")
        return
    }
    sb.appendLine("object \"$title\" as $name")
    while (clazz != null) {
        clazz.declaredFields.filter { f ->
            !Modifier.isStatic(f.modifiers) &&
            !f.isEnumConstant &&
            f.name != "serialVersionUID"
        }.forEach { f ->
            try {
                f.isAccessible = true
                val fieldName = f.name
                var value: Any? = f.get(obj)

                val atomic = value?.javaClass?.canonicalName?.let {
                    it == REMAPPED_PACKAGE_CANONICAL_NAME + "java.util.concurrent.atomic.AtomicInteger" ||
                    it == REMAPPED_PACKAGE_CANONICAL_NAME + "java.util.concurrent.atomic.AtomicLong" ||
                    it == REMAPPED_PACKAGE_CANONICAL_NAME + "java.util.concurrent.atomic.AtomicReference" ||
                    it == REMAPPED_PACKAGE_CANONICAL_NAME + "java.util.concurrent.atomic.AtomicBoolean"
                } ?: false
                if (atomic) {
                    value = value!!.javaClass.getDeclaredMethod("get").invoke(value)
                }

                if (value?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicRef") value = value.javaClass.getDeclaredField("value").apply { isAccessible = true }.get(value)
                if (value?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicInt") value = value.javaClass.getDeclaredField("value").apply { isAccessible = true }.get(value)
                if (value?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicLong") value = value.javaClass.getDeclaredField("value").apply { isAccessible = true }.get(value)
                if (value?.javaClass?.canonicalName == "kotlinx.atomicfu.AtomicBoolean") value = value.javaClass.getDeclaredField("_value").apply { isAccessible = true }.get(value)

                if (value is AtomicIntegerArray) value = (0..value.length()).map { (value as AtomicIntegerArray).get(it) }.toIntArray()
                if (value is AtomicReferenceArray<*>) value = (0..value.length()).map { (value as AtomicReferenceArray<*>).get(it) }.toTypedArray()

                if (value?.javaClass?.canonicalName?.startsWith("java.lang.invoke.") ?: false) {
                    // Ignore
                } else if (value is AtomicReferenceFieldUpdater<*,*> || value is AtomicIntegerFieldUpdater<*> || value is AtomicLongFieldUpdater<*>) {
                    // Ignore
                } else if (value is ReentrantLock) {
                    sb.appendLine("$name : $fieldName = $value")
                } else {
                    val stringValue = stringRepresentation(value)
                    if (stringValue != null) {
                        sb.appendLine("$name : $fieldName = $stringValue")
                    } else {
                        if (value == null) {
                            sb.appendLine("$name --> $fieldName = null")
                        } else {
                            val valueTitle = name(value)
                            visualize(value, sb, visualized)
                            sb.appendLine("$name --> $valueTitle : $fieldName")
                        }
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                // Ignore
            }
        }
        clazz = clazz.superclass
    }
}

// Try to construct a string representation
private fun stringRepresentation(obj: Any?): String? {
    if (obj == null || obj.javaClass.isImmutableWithNiceToString)
        return obj.toString()
    val id = getObjectNumber(obj.javaClass, obj)
    if (obj is CharSequence) {
        return "\"$obj\""
    }
    if (obj is Continuation<*>) {
        val runner = (ManagedStrategyStateHolder.strategy as? ModelCheckingStrategy)?.runner as? ParallelThreadsRunner
        val thread = runner?.executor?.threads?.find { it.cont === obj }
        return if (thread == null) "cont@$id" else "cont@$id[Thread-${thread.iThread + 1}]"
    }
    return null
}

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
private val Class<out Any>.isImmutableWithNiceToString
    get() = this.canonicalName in listOf(
        java.lang.Integer::class.java,
        java.lang.Long::class.java,
        java.lang.Short::class.java,
        java.lang.Double::class.java,
        java.lang.Float::class.java,
        java.lang.Character::class.java,
        java.lang.Byte::class.java,
        java.lang.Boolean::class.java,
        BigInteger::class.java,
        BigDecimal::class.java,
        kotlinx.coroutines.internal.Symbol::class.java,
    ).map { it.canonicalName } || this.isEnum