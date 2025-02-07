/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.app.tracing.coroutines.flow

import com.android.app.tracing.coroutines.CoroutineTraceName
import com.android.app.tracing.coroutines.traceCoroutine
import com.android.app.tracing.coroutines.traceName
import com.android.app.tracing.traceSection
import kotlin.experimental.ExperimentalTypeInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow as safeFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform

/** @see kotlinx.coroutines.flow.internal.unsafeFlow */
@OptIn(ExperimentalTypeInference::class)
@PublishedApi
internal inline fun <T> unsafeFlow(
    @BuilderInference crossinline block: suspend FlowCollector<T>.() -> Unit
): Flow<T> {
    return object : Flow<T> {
        override suspend fun collect(collector: FlowCollector<T>) {
            collector.block()
        }
    }
}

/** @see kotlinx.coroutines.flow.unsafeTransform */
@PublishedApi
internal inline fun <T, R> Flow<T>.unsafeTransform(
    crossinline transform: suspend FlowCollector<R>.(value: T) -> Unit
): Flow<R> = unsafeFlow { collect { value -> transform(value) } }

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private open class TracedSharedFlow<T>(private val name: String, private val flow: SharedFlow<T>) :
    SharedFlow<T> by flow {
    override val replayCache: List<T>
        get() = traceSection("replayCache:$name") { flow.replayCache }

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        traceCoroutine("collect:$name") {
            flow.collect { traceCoroutine("emit:$name") { collector.emit(it) } }
        }
    }
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private open class TracedStateFlow<T>(private val name: String, private val flow: StateFlow<T>) :
    StateFlow<T> by flow, TracedSharedFlow<T>(name, flow) {
    override val value: T
        get() = traceCoroutine("get:$name") { flow.value }

    override val replayCache: List<T>
        get() = super.replayCache

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        super.collect(collector)
    }
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class TracedMutableSharedFlow<T>(
    private val name: String,
    private val flow: MutableSharedFlow<T>,
) : MutableSharedFlow<T> by flow, TracedSharedFlow<T>(name, flow) {

    override val replayCache: List<T>
        get() = super.replayCache

    override suspend fun emit(value: T) {
        traceCoroutine("emit:$name") { flow.emit(value) }
    }

    override fun tryEmit(value: T): Boolean {
        return traceCoroutine("tryEmit:$name") { flow.tryEmit(value) }
    }

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        super.collect(collector)
    }
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class TracedMutableStateFlow<T>(
    private val name: String,
    private val flow: MutableStateFlow<T>,
) : MutableStateFlow<T> by flow, TracedStateFlow<T>(name, flow) {

    override var value: T
        get() = super.value
        set(newValue) {
            traceSection("updateState:$name") { flow.value = newValue }
        }

    override val replayCache: List<T>
        get() = super.replayCache

    override suspend fun emit(value: T) {
        traceCoroutine("emit:$name") { flow.emit(value) }
    }

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        traceCoroutine("collect:$name") {
            flow.collect { traceCoroutine("emit:$name") { collector.emit(it) } }
        }
    }

    override fun compareAndSet(expect: T, update: T): Boolean {
        traceSection("updateState:$name") {
            return flow.compareAndSet(expect, update)
        }
    }
}

/**
 * Helper for adding trace sections for when a trace is collected.
 *
 * For example, the following would `emit(1)` from a trace section named "my-flow" and collect in a
 * coroutine scope named "my-launch".
 *
 * ```
 *   val flow {
 *     // The open trace section here would be:
 *     // "coroutine execution;my-launch", and "collect:my-flow"
 *     emit(1)
 *   }
 *   launchTraced("my-launch") {
 *     .flowName("my-flow")
 *     .collect {
 *       // The open trace sections here would be:
 *       // "coroutine execution;my-launch", "collect:my-flow", and "emit:my-flow"
 *     }
 *   }
 * ```
 *
 * TODO(b/334171711): Rename via @Deprecated("Renamed to .traceAs()", ReplaceWith("traceAs(name)"))
 */
public fun <T> Flow<T>.flowName(name: String): Flow<T> = traceAs(name)

public fun <T> Flow<T>.traceAs(name: String): Flow<T> {
    return if (com.android.systemui.Flags.coroutineTracing()) {
        unsafeFlow {
            traceCoroutine("collect:$name") {
                collect { value -> traceCoroutine("emit:$name") { emit(value) } }
            }
        }
    } else {
        this
    }
}

public fun <T> SharedFlow<T>.traceAs(name: String): SharedFlow<T> =
    if (com.android.systemui.Flags.coroutineTracing()) TracedSharedFlow(name, this) else this

public fun <T> StateFlow<T>.traceAs(name: String): StateFlow<T> =
    if (com.android.systemui.Flags.coroutineTracing()) TracedStateFlow(name, this) else this

public fun <T> MutableSharedFlow<T>.traceAs(name: String): MutableSharedFlow<T> =
    if (com.android.systemui.Flags.coroutineTracing()) TracedMutableSharedFlow(name, this) else this

public fun <T> MutableStateFlow<T>.traceAs(name: String): MutableStateFlow<T> =
    if (com.android.systemui.Flags.coroutineTracing()) TracedMutableStateFlow(name, this) else this

public fun <T> Flow<T>.onEachTraced(name: String, action: suspend (T) -> Unit): Flow<T> {
    return onEach { value -> traceCoroutine(name) { action(value) } }
}

/**
 * NOTE: [Flow.collect] is a member function and takes precedence if this function is imported as
 * `collect` and the default parameter is used. (In Kotlin, when an extension function has the same
 * receiver type, name, and applicable arguments as a class member function, the member takes
 * precedence).
 *
 * For example,
 * ```
 * import com.android.app.tracing.coroutines.flow.collectTraced as collect
 * ...
 * flowOf(1).collect { ... } // this will call `Flow.collect`
 * flowOf(1).collect(null) { ... } // this will call `collectTraced`
 * ```
 *
 * @see kotlinx.coroutines.flow.collect
 */
public suspend fun <T> Flow<T>.collectTraced(name: String, collector: FlowCollector<T>) {
    if (com.android.systemui.Flags.coroutineTracing()) {
        traceAs(name).collect(collector)
    } else {
        collect(collector)
    }
}

/** @see kotlinx.coroutines.flow.collect */
public suspend fun <T> Flow<T>.collectTraced(name: String) {
    if (com.android.systemui.Flags.coroutineTracing()) {
        traceAs(name).collect()
    } else {
        collect()
    }
}

/** @see kotlinx.coroutines.flow.collect */
public suspend fun <T> Flow<T>.collectTraced(collector: FlowCollector<T>) {
    if (com.android.systemui.Flags.coroutineTracing()) {
        collectTraced(name = collector.traceName, collector = collector)
    } else {
        collect(collector)
    }
}

@OptIn(ExperimentalTypeInference::class)
@ExperimentalCoroutinesApi
public fun <T, R> Flow<T>.mapLatestTraced(
    name: String,
    @BuilderInference transform: suspend (value: T) -> R,
): Flow<R> {
    return if (com.android.systemui.Flags.coroutineTracing()) {
        traceAs("mapLatest:$name").mapLatest { traceCoroutine(name) { transform(it) } }
    } else {
        mapLatest(transform)
    }
}

@OptIn(ExperimentalTypeInference::class)
@ExperimentalCoroutinesApi
public fun <T, R> Flow<T>.mapLatestTraced(
    @BuilderInference transform: suspend (value: T) -> R
): Flow<R> {
    return if (com.android.systemui.Flags.coroutineTracing()) {
        mapLatestTraced(transform.traceName, transform)
    } else {
        mapLatestTraced(transform)
    }
}

/** @see kotlinx.coroutines.flow.collectLatest */
internal suspend fun <T> Flow<T>.collectLatestTraced(
    name: String,
    action: suspend (value: T) -> Unit,
) {
    if (com.android.systemui.Flags.coroutineTracing()) {
        return traceAs("collectLatest:$name").collectLatest { traceCoroutine(name) { action(it) } }
    } else {
        collectLatest(action)
    }
}

/** @see kotlinx.coroutines.flow.collectLatest */
public suspend fun <T> Flow<T>.collectLatestTraced(action: suspend (value: T) -> Unit) {
    if (com.android.systemui.Flags.coroutineTracing()) {
        collectLatestTraced(action.traceName, action)
    } else {
        collectLatest(action)
    }
}

/** @see kotlinx.coroutines.flow.transform */
@OptIn(ExperimentalTypeInference::class)
public inline fun <T, R> Flow<T>.transformTraced(
    name: String,
    @BuilderInference crossinline transform: suspend FlowCollector<R>.(value: T) -> Unit,
): Flow<R> {
    return if (com.android.systemui.Flags.coroutineTracing()) {
        // Safe flow must be used because collector is exposed to the caller
        safeFlow {
            collect { value ->
                traceCoroutine(name) {
                    return@collect transform(value)
                }
            }
        }
    } else {
        transform(transform)
    }
}

/** @see kotlinx.coroutines.flow.filter */
public inline fun <T> Flow<T>.filterTraced(
    name: String,
    crossinline predicate: suspend (T) -> Boolean,
): Flow<T> {
    return if (com.android.systemui.Flags.coroutineTracing()) {
        unsafeTransform { value ->
            if (traceCoroutine(name) { predicate(value) }) {
                emit(value)
            }
        }
    } else {
        filter(predicate)
    }
}

/** @see kotlinx.coroutines.flow.map */
public inline fun <T, R> Flow<T>.mapTraced(
    name: String,
    crossinline transform: suspend (value: T) -> R,
): Flow<R> {
    return if (com.android.systemui.Flags.coroutineTracing()) {
        unsafeTransform { value ->
            val transformedValue = traceCoroutine(name) { transform(value) }
            emit(transformedValue)
        }
    } else {
        map(transform)
    }
}

/** @see kotlinx.coroutines.flow.shareIn */
public fun <T> Flow<T>.shareInTraced(
    name: String,
    scope: CoroutineScope,
    started: SharingStarted,
    replay: Int = 0,
): SharedFlow<T> {
    // .shareIn calls this.launch(context), where this === scope, and the previous upstream flow's
    // context is passed to launch (caveat: the upstream context is only passed to the downstream
    // SharedFlow if certain conditions are met). For instead, if the upstream is a SharedFlow,
    // the `.flowOn()` operator will have no effect.
    return maybeFuseTraceName(name).shareIn(scope, started, replay).traceAs(name)
}

/** @see kotlinx.coroutines.flow.stateIn */
public fun <T> Flow<T>.stateInTraced(
    name: String,
    scope: CoroutineScope,
    started: SharingStarted,
    initialValue: T,
): StateFlow<T> {
    // .stateIn calls this.launch(context), where this === scope, and the previous upstream flow's
    // context is passed to launch
    return maybeFuseTraceName(name).stateIn(scope, started, initialValue).traceAs(name)
}

/** @see kotlinx.coroutines.flow.stateIn */
public suspend fun <T> Flow<T>.stateInTraced(name: String, scope: CoroutineScope): StateFlow<T> {
    // .stateIn calls this.launch(context), where this === scope, and the previous upstream flow's
    // context is passed to launch
    return maybeFuseTraceName(name).stateIn(scope).traceAs(name)
}

public fun <T> MutableSharedFlow<T>.asSharedFlowTraced(name: String): SharedFlow<T> {
    return asSharedFlow().traceAs(name)
}

public fun <T> MutableStateFlow<T>.asStateFlowTraced(name: String): StateFlow<T> {
    return asStateFlow().traceAs(name)
}

private fun <T> Flow<T>.maybeFuseTraceName(name: String): Flow<T> =
    if (com.android.systemui.Flags.coroutineTracing()) flowOn(CoroutineTraceName(name)) else this
