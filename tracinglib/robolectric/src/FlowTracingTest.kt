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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.test.tracing.coroutines

import android.platform.test.annotations.EnableFlags
import com.android.app.tracing.coroutines.CoroutineTraceName
import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.android.app.tracing.coroutines.flow.collectLatestTraced
import com.android.app.tracing.coroutines.flow.collectTraced
import com.android.app.tracing.coroutines.flow.filterTraced
import com.android.app.tracing.coroutines.flow.flowName
import com.android.app.tracing.coroutines.flow.mapLatestTraced
import com.android.app.tracing.coroutines.flow.mapTraced
import com.android.app.tracing.coroutines.flow.shareInTraced
import com.android.app.tracing.coroutines.flow.stateInTraced
import com.android.app.tracing.coroutines.flow.traceAs
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.app.tracing.coroutines.traceCoroutine
import com.android.systemui.Flags.FLAG_COROUTINE_TRACING
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.job
import kotlinx.coroutines.plus
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

@EnableFlags(FLAG_COROUTINE_TRACING)
class FlowTracingTest : TestBase() {

    @Test
    fun collectFlow_simple() {
        val coldFlow = flow {
            expect("1^main")
            yield()
            expect("1^main")
            emit(42)
            expect("1^main")
            yield()
            expect("1^main")
        }

        runTest(totalEvents = 8) {
            expect("1^main")
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
                yield()
                expect("1^main")
            }
            yield()
            expect("1^main")
        }
    }

    /** @see [CoroutineTracingTest.withContext_incorrectUsage] */
    @Test
    fun collectFlow_incorrectNameUsage() =
        runTest(totalEvents = 8) {
            val coldFlow =
                flow {
                        expect(
                            "1^main"
                        ) // <-- Trace section from before withContext is open until the
                        //                      first suspension
                        yield()
                        expect()
                        emit(42)
                        expect("1^main") // <-- context changed due to context of collector
                        yield()
                        expect()
                    }
                    .flowOn(CoroutineTraceName("new-name")) // <-- BAD, DON'T DO THIS

            expect("1^main")
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
                yield()
                expect("1^main")
            }
            expect() // <-- trace sections erased due to context of emitter
        }

    @Test
    fun collectFlow_correctNameUsage() {
        val coldFlow =
            flow {
                    expect(2, "1^main", "collect:new-name")
                    yield()
                    expect(3, "1^main", "collect:new-name")
                    emit(42)
                    expect(6, "1^main", "collect:new-name")
                    yield()
                    expect(7, "1^main", "collect:new-name")
                }
                .flowName("new-name")
        runTest(totalEvents = 8) {
            expect(1, "1^main")
            coldFlow.collect {
                assertEquals(42, it)
                expect(4, "1^main", "collect:new-name", "emit:new-name")
                yield()
                expect(5, "1^main", "collect:new-name", "emit:new-name")
            }
            expect(8, "1^main")
        }
    }

    @Test
    fun collectFlow_shareIn() {
        val otherScope =
            CoroutineScope(
                createCoroutineTracingContext("other-scope", testMode = true) +
                    bgThread1 +
                    scope.coroutineContext.job
            )
        val sharedFlow =
            flow {
                    expect("1^new-name")
                    yield()
                    expect("1^new-name")
                    emit(42)
                    expect("1^new-name")
                    yield()
                    expect("1^new-name")
                }
                .shareInTraced("new-name", otherScope, SharingStarted.Eagerly, 5)
        runTest(totalEvents = 9) {
            yield()
            expect("1^main")
            val job =
                launchTraced("launch-for-collect") {
                    expect("1^main:1^launch-for-collect")
                    sharedFlow.collect {
                        assertEquals(42, it)
                        expect("1^main:1^launch-for-collect", "collect:new-name", "emit:new-name")
                        yield()
                        expect("1^main:1^launch-for-collect", "collect:new-name", "emit:new-name")
                    }
                }
            yield()
            expect("1^main")
            yield()
            job.cancel()
        }
    }

    @Test
    fun collectFlow_launchIn() {
        val coldFlow = flow {
            expectAny(arrayOf("1^main:1^"), arrayOf("1^main:2^launchIn-for-cold"))
            yield()
            expectAny(arrayOf("1^main:1^"), arrayOf("1^main:2^launchIn-for-cold"))
            emit(42)
            expectAny(arrayOf("1^main:1^"), arrayOf("1^main:2^launchIn-for-cold"))
            yield()
            expectAny(arrayOf("1^main:1^"), arrayOf("1^main:2^launchIn-for-cold"))
        }

        runTest(totalEvents = 10) {
            val sharedFlow = coldFlow.shareIn(this, SharingStarted.Eagerly, 5)
            yield()
            expect("1^main")
            coldFlow.launchInTraced("launchIn-for-cold", this)
            val job = sharedFlow.launchIn(this)
            yield()
            expect("1^main")
            job.cancel()
        }
    }

    @Test
    fun collectFlow_launchIn_and_shareIn() {
        val coldFlow = flow {
            expectAny(arrayOf("1^main:1^shareIn-name"), arrayOf("1^main:2^launchIn-for-cold"))
            yield()
            expectAny(arrayOf("1^main:1^shareIn-name"), arrayOf("1^main:2^launchIn-for-cold"))
            emit(42)
            expectAny(arrayOf("1^main:1^shareIn-name"), arrayOf("1^main:2^launchIn-for-cold"))
            yield()
            expectAny(arrayOf("1^main:1^shareIn-name"), arrayOf("1^main:2^launchIn-for-cold"))
        }

        runTest(totalEvents = 12) {
            val sharedFlow = coldFlow.shareInTraced("shareIn-name", this, SharingStarted.Eagerly, 5)
            yield()
            expect("1^main")
            coldFlow
                .onEach { expect("1^main:2^launchIn-for-cold") }
                .launchInTraced("launchIn-for-cold", this)
            val job =
                sharedFlow
                    .onEach {
                        expect(
                            "1^main:3^launchIn-for-hot",
                            "collect:shareIn-name",
                            "emit:shareIn-name",
                        )
                    }
                    .launchInTraced("launchIn-for-hot", this)
            expect("1^main")
            delay(10)
            job.cancel()
        }
    }

    @Test
    fun collectFlow_badUsageOfCoroutineTraceName_coldFlowOnDifferentThread() {
        val thread1 = bgThread1
        // Example of bad usage of CoroutineTraceName. CoroutineTraceName is an internal API.
        // It should only be used during collection, or whenever a coroutine is launched.
        // It should not be used as an intermediate operator.
        val coldFlow =
            flow {
                    expect("1^main:1^fused-name")
                    yield()
                    expect() // <-- empty due to CoroutineTraceName overwriting TraceContextElement
                    emit(21)
                    expect() // <-- empty due to CoroutineTraceName overwriting TraceContextElement
                    yield()
                    expect() // <-- empty due to CoroutineTraceName overwriting TraceContextElement
                }
                // "UNUSED_MIDDLE_NAME" is overwritten during operator fusion because the thread
                // of the flow did not change, meaning no new coroutine needed to be created.
                // However, using CoroutineTraceName("UNUSED_MIDDLE_NAME") is bad because it will
                // replace CoroutineTracingContext on the resumed thread
                .flowOn(CoroutineTraceName("UNUSED_MIDDLE_NAME") + thread1)
                .map {
                    expect("1^main:1^fused-name")
                    it * 2
                }
                .flowOn(CoroutineTraceName("fused-name") + thread1)

        runTest(totalEvents = 9) {
            expect("1^main")
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
                yield()
                expect("1^main")
            }
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_operatorFusion_preventedByTracing() {
        val coldFlow =
            flow {
                    expect("1^main:1^:1^", "collect:AAA")
                    yield()
                    expect("1^main:1^:1^", "collect:AAA")
                    emit(42)
                    expect("1^main:1^:1^", "collect:AAA")
                    yield()
                    expect("1^main:1^:1^", "collect:AAA")
                }
                .flowName("AAA")
                .flowOn(bgThread1)
                // because we added tracing, work unnecessarily runs on bgThread2. This would be
                // like adding a `.transform{}` or `.onEach{}` call between `.flowOn()` operators.
                // The problem is not unique to tracing, but this test is to show there is still
                // overhead when tracing is disabled, so it should not be used everywhere.
                .flowName("BBB")
                .flowOn(bgThread2)
                .flowName("CCC")

        runTest(totalEvents = 8) {
            expect("1^main")
            coldFlow.collectTraced(
                "DDD"
            ) { // CCC and DDD aren't fused together like how contexts are in `.flowOn()`
                assertEquals(42, it)
                expect("1^main", "collect:DDD", "collect:CCC", "emit:CCC", "emit:DDD")
                yield()
                expect("1^main", "collect:DDD", "collect:CCC", "emit:CCC", "emit:DDD")
            }
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_operatorFusion_happensBecauseNoTracing() {
        val coldFlow =
            flow {
                    expect("1^main:1^")
                    yield()
                    expect("1^main:1^")
                    emit(42)
                    expect("1^main:1^")
                    yield()
                    expect("1^main:1^")
                }
                .flowOn(bgThread1) // Operators are fused, and nothing ever executes on bgThread2
                .flowOn(bgThread2)
                .flowName("FLOW_NAME")

        runTest(totalEvents = 8) {
            expect("1^main")
            coldFlow.collectTraced(
                "COLLECT_NAME"
            ) { // FLOW_NAME and COLLECT_NAME aren't fused together like how contexts
                // are in `.flowOn()`
                assertEquals(42, it)
                expect(
                    "1^main",
                    "collect:COLLECT_NAME",
                    "collect:FLOW_NAME",
                    "emit:FLOW_NAME",
                    "emit:COLLECT_NAME",
                )
                yield()
                expect(
                    "1^main",
                    "collect:COLLECT_NAME",
                    "collect:FLOW_NAME",
                    "emit:FLOW_NAME",
                    "emit:COLLECT_NAME",
                )
            }
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_flowOnTraced() {
        val thread1 = bgThread1
        val thread2 = bgThread2
        // Example of bad usage of CoroutineTraceName. CoroutineTraceName is an internal API.
        // It should only be used during collection, or whenever a coroutine is launched.
        // It should not be used as an intermediate operator.
        val op1 = flow {
            expect("1^main:1^outer-name:1^inner-name")
            yield()
            expect()
            emit(42)
            expect()
            yield()
            expect()
        }
        val op2 = op1.flowOn(CoroutineTraceName("UNUSED_NAME") + thread2)
        val op3 = op2.onEach { expect("1^main:1^outer-name:1^inner-name") }
        val op4 = op3.flowOn(CoroutineTraceName("inner-name") + thread2)
        val op5 = op4.onEach { expect("1^main:1^outer-name") }
        val op6 = op5.flowOn(CoroutineTraceName("outer-name") + thread1)

        runTest(totalEvents = 10) {
            expect("1^main")
            op6.collect {
                assertEquals(42, it)
                expect("1^main")
                yield()
                expect("1^main")
            }
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_coldFlowOnDifferentThread() {
        val thread1 = bgThread1
        val coldFlow =
            flow {
                    expect("1^main:1^fused-name")
                    yield()
                    expect("1^main:1^fused-name")
                    emit(21)
                    expect("1^main:1^fused-name")
                    yield()
                    expect("1^main:1^fused-name")
                }
                .map {
                    expect("1^main:1^fused-name")
                    it * 2
                }
                .flowOn(CoroutineTraceName("fused-name") + thread1)

        runTest(totalEvents = 9) {
            expect("1^main")
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
                yield()
                expect("1^main")
            }
            expect("1^main")
        }
    }

    @Test
    fun collectTraced_coldFlowOnDifferentThread() {
        val thread1 = bgThread1
        val coldFlow =
            flow {
                    expect("1^main:1^")
                    yield()
                    expect("1^main:1^")
                    emit(21)
                    expect("1^main:1^")
                    yield()
                    expect("1^main:1^")
                }
                .map {
                    expect("1^main:1^")
                    it * 2
                }
                .flowOn(thread1)

        runTest(totalEvents = 9) {
            expect("1^main")
            coldFlow.collectTraced("coldFlow") {
                assertEquals(42, it)
                expect("1^main", "collect:coldFlow", "emit:coldFlow")
                yield()
                expect("1^main", "collect:coldFlow", "emit:coldFlow")
            }
            expect("1^main")
        }
    }

    @Test
    fun collectTraced_collectWithTracedReceiver() {
        val thread1 = bgThread1
        val coldFlow =
            flow {
                    expect("1^main:1^")
                    yield()
                    expect("1^main:1^")
                    emit(21)
                    expect("1^main:1^")
                    yield()
                    expect("1^main:1^")
                }
                .map {
                    expect("1^main:1^")
                    it * 2
                }
                .flowOn(thread1)

        runTest(totalEvents = 9) {
            expect("1^main")
            coldFlow.traceCoroutine("AAA") {
                collectTraced("coldFlow") {
                    assertEquals(42, it)
                    expect("1^main", "AAA", "collect:coldFlow", "emit:coldFlow")
                    yield()
                    expect("1^main", "AAA", "collect:coldFlow", "emit:coldFlow")
                }
            }
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_nameBeforeDispatcherChange() {
        val thread1 = bgThread1
        val coldFlow =
            flow {
                    expect("1^main:1^new-name")
                    yield()
                    expect("1^main:1^new-name")
                    emit(42)
                    expect("1^main:1^new-name")
                    yield()
                    expect("1^main:1^new-name")
                }
                .flowOn(CoroutineTraceName("new-name"))
                .flowOn(thread1)
        runTest(totalEvents = 6) {
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
                yield()
                expect("1^main")
            }
        }
    }

    @Test
    fun collectFlow_nameAfterDispatcherChange() {
        val thread1 = bgThread1
        val coldFlow =
            flow {
                    expect("1^main:1^new-name")
                    yield()
                    expect("1^main:1^new-name")
                    emit(42)
                    expect("1^main:1^new-name")
                    yield()
                    expect("1^main:1^new-name")
                }
                .flowOn(thread1)
                .flowOn(CoroutineTraceName("new-name"))
        runTest(totalEvents = 6) {
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
                yield()
                expect("1^main")
            }
        }
    }

    @Test
    fun collectFlow_nameBeforeAndAfterDispatcherChange() {
        val thread1 = bgThread1
        val coldFlow =
            flow {
                    expect("1^main:1^new-name")
                    yield()
                    expect("1^main:1^new-name")
                    emit(42)
                    expect("1^main:1^new-name")
                    yield()
                    expect("1^main:1^new-name")
                }
                .flowOn(CoroutineTraceName("new-name"))
                .flowOn(thread1)
                // Unused because, when fused, the previous upstream context takes precedence
                .flowOn(CoroutineTraceName("UNUSED_NAME"))

        runTest {
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
            }
            yield()
            expect("1^main")
        }
    }

    @Test
    fun collectTraced_mapLatest() {
        val coldFlow =
            flow {
                    expect("1^main:1^:1^")
                    emit(1)
                    expect("1^main:1^:1^")
                    emit(21)
                    expect("1^main:1^:1^")
                }
                .filterTraced("mod2") {
                    // called twice because upstream has 2 emits
                    expect("1^main:1^:1^", "mod2")
                    true
                }
                .run {
                    traceCoroutine("CCC") {
                        mapLatest {
                            traceCoroutine("DDD") {
                                expectAny(
                                    arrayOf("1^main:1^:1^", "1^main:1^:1^:1^", "DDD"),
                                    arrayOf("1^main:1^:1^", "1^main:1^:1^:2^", "DDD"),
                                )
                                it * 2
                            }
                        }
                    }
                }

        runTest(totalEvents = 10) {
            expect("1^main") // top-level scope
            traceCoroutine("AAA") {
                coldFlow.collectLatest {
                    traceCoroutine("BBB") {
                        delay(50)
                        assertEquals(42, it)
                        expect("1^main:1^:3^", "BBB")
                    }
                }
            }
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_badNameUsage() {
        val barrier1 = CompletableDeferred<Unit>()
        val barrier2 = CompletableDeferred<Unit>()
        val thread1 = bgThread1
        val thread2 = bgThread2
        val thread3 = bgThread3
        val coldFlow =
            flow {
                    expect("1^main:1^name-for-filter:1^name-for-map:1^name-for-emit")
                    yield()
                    expect("1^main:1^name-for-filter:1^name-for-map:1^name-for-emit")
                    emit(42)
                    barrier1.await()
                    expect("1^main:1^name-for-filter:1^name-for-map:1^name-for-emit")
                    yield()
                    expect("1^main:1^name-for-filter:1^name-for-map:1^name-for-emit")
                    barrier2.complete(Unit)
                }
                .flowOn(CoroutineTraceName("name-for-emit"))
                .flowOn(thread3)
                .map {
                    expect("1^main:1^name-for-filter:1^name-for-map")
                    yield()
                    expect("1^main:1^name-for-filter:1^name-for-map")
                    it
                }
                .flowOn(CoroutineTraceName("name-for-map")) // <-- This only works because the
                //                   dispatcher changes; this behavior should not be relied on.
                .flowOn(thread2)
                .flowOn(CoroutineTraceName("UNUSED_NAME")) // <-- Unused because, when fused, the
                //                                     previous upstream context takes precedence
                .filter {
                    expect("1^main:1^name-for-filter")
                    yield()
                    expect("1^main:1^name-for-filter")
                    true
                }
                .flowOn(CoroutineTraceName("name-for-filter"))
                .flowOn(thread1)

        runTest(totalEvents = 11) {
            expect("1^main")
            coldFlow.collect {
                assertEquals(42, it)
                expect("1^main")
                barrier1.complete(Unit)
            }
            barrier2.await()
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_withIntermediateOperatorNames() {
        val coldFlow =
            flow {
                    expect(2, "1^main", "collect:do-the-assert")
                    emit(21) // 42 / 2 = 21
                    expect(6, "1^main", "collect:do-the-assert")
                }
                .mapTraced("multiply-by-3") {
                    expect(3, "1^main", "collect:do-the-assert", "multiply-by-3")
                    it * 2
                }
                .filterTraced("mod-2") {
                    expect(4, "1^main", "collect:do-the-assert", "mod-2")
                    it % 2 == 0
                }
        runTest(totalEvents = 7) {
            expect(1, "1^main")

            coldFlow.collectTraced("do-the-assert") {
                assertEquals(42, it)
                expect(5, "1^main", "collect:do-the-assert", "emit:do-the-assert")
            }
            expect(7, "1^main")
        }
    }

    @Test
    fun collectFlow_mapLatest() {
        val coldFlow = flowOf(1, 2, 3)
        runTest(totalEvents = 6) {
            expect("1^main")
            coldFlow
                .mapLatestTraced("AAA") {
                    expectAny(
                        arrayOf(
                            "1^main:1^",
                            "collect:mapLatest:AAA",
                            "emit:mapLatest:AAA",
                            "1^main:1^:1^",
                            "AAA",
                        ),
                        arrayOf(
                            "1^main:1^",
                            "collect:mapLatest:AAA",
                            "emit:mapLatest:AAA",
                            "1^main:1^:2^",
                            "AAA",
                        ),
                        arrayOf(
                            "1^main:1^",
                            "collect:mapLatest:AAA",
                            "emit:mapLatest:AAA",
                            "1^main:1^:3^",
                            "AAA",
                        ),
                    )
                    delay(10)
                    expect("1^main:1^:3^", "AAA")
                }
                .collect()
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_collectLatest() {
        val coldFlow = flowOf(1, 2, 3)
        runTest(totalEvents = 6) {
            expect("1^main")
            coldFlow.collectLatestTraced("CCC") {
                expectAny(
                    arrayOf(
                        "1^main:1^",
                        "collect:collectLatest:CCC",
                        "emit:collectLatest:CCC",
                        "1^main:1^:1^",
                        "CCC",
                    ),
                    arrayOf(
                        "1^main:1^",
                        "collect:collectLatest:CCC",
                        "emit:collectLatest:CCC",
                        "1^main:1^:2^",
                        "CCC",
                    ),
                    arrayOf(
                        "1^main:1^",
                        "collect:collectLatest:CCC",
                        "emit:collectLatest:CCC",
                        "1^main:1^:3^",
                        "CCC",
                    ),
                )
                delay(10)
                expect("1^main:1^:3^", "CCC")
            }
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_mapLatest_collectLatest() {
        val coldFlow = flowOf(1, 2, 3)
        runTest(totalEvents = 7) {
            expect("1^main")
            coldFlow
                .mapLatestTraced("AAA") {
                    expectAny(
                        arrayOf(
                            "1^main:1^:1^",
                            "collect:mapLatest:AAA",
                            "emit:mapLatest:AAA",
                            "1^main:1^:1^:1^",
                            "AAA",
                        ),
                        arrayOf(
                            "1^main:1^:1^",
                            "collect:mapLatest:AAA",
                            "emit:mapLatest:AAA",
                            "1^main:1^:1^:2^",
                            "AAA",
                        ),
                        arrayOf(
                            "1^main:1^:1^",
                            "collect:mapLatest:AAA",
                            "emit:mapLatest:AAA",
                            "1^main:1^:1^:3^",
                            "AAA",
                        ),
                    )
                    delay(10)
                    expect("1^main:1^:1^:3^", "AAA")
                }
                .collectLatestTraced("CCC") {
                    expect(
                        "1^main:1^",
                        "collect:collectLatest:CCC",
                        "emit:collectLatest:CCC",
                        "1^main:1^:2^",
                        "CCC",
                    )
                }
            expect("1^main")
        }
    }

    @Test
    fun collectFlow_stateIn() {
        val otherScope =
            CoroutineScope(
                createCoroutineTracingContext("other-scope", testMode = true) +
                    bgThread1 +
                    scope.coroutineContext.job
            )
        val coldFlow =
            flowOf(1, 2)
                .onEach {
                    delay(2)
                    expectAny(arrayOf("1^STATE_1"), arrayOf("2^STATE_2"))
                }
                .flowOn(bgThread2)

        runTest(totalEvents = 10) {
            expect("1^main")

            val state1 = coldFlow.stateInTraced("STATE_1", otherScope.plus(bgThread2))
            val state2 = coldFlow.stateInTraced("STATE_2", otherScope, SharingStarted.Lazily, 42)

            delay(20)

            val job1 =
                state1
                    .onEach { expect("1^main:1^LAUNCH_1", "collect:STATE_1", "emit:STATE_1") }
                    .launchInTraced("LAUNCH_1", this)
            assertEquals(42, state2.value)
            val job2 =
                state2
                    .onEach { expect("1^main:2^LAUNCH_2", "collect:STATE_2", "emit:STATE_2") }
                    .launchInTraced("LAUNCH_2", this)

            delay(10)
            expect("1^main")

            delay(10)

            job1.cancel()
            job2.cancel()
        }
    }

    @Test
    fun tracedMutableStateFlow_collection() {
        val state = MutableStateFlow(1).traceAs("NAME")

        runTest(totalEvents = 3) {
            expect("1^main")
            launchTraced("LAUNCH") {
                delay(10)
                state.value = 2
            }
            val job =
                launchTraced("LAUNCH_FOR_COLLECT") {
                    state.collect {
                        expect("1^main:2^LAUNCH_FOR_COLLECT", "collect:NAME", "emit:NAME")
                    }
                }
            delay(100)
            job.cancel()
        }
    }
}
