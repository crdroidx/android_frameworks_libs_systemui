/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.mechanics.spec

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.mechanics.spring.SpringParameters
import com.android.mechanics.testing.DirectionalMotionSpecSubject.Companion.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectionalMotionSpecBuilderTest {

    @Test
    fun directionalSpec_buildEmptySpec() {
        val result = buildDirectionalMotionSpec()

        assertThat(result).breakpoints().isEmpty()
        assertThat(result).mappings().containsExactly(Mapping.Identity)
    }

    @Test
    fun directionalSpec_addBreakpointsAndMappings() {
        val result =
            buildDirectionalMotionSpec(Spring, Mapping.Zero) {
                mapping(breakpoint = 0f, mapping = Mapping.One, key = B1)
                mapping(breakpoint = 10f, mapping = Mapping.Two, key = B2)
            }

        assertThat(result).breakpoints().keys().containsExactly(B1, B2).inOrder()
        assertThat(result).breakpoints().withKey(B1).isAt(0f)
        assertThat(result).breakpoints().withKey(B2).isAt(10f)
        assertThat(result)
            .mappings()
            .containsExactly(Mapping.Zero, Mapping.One, Mapping.Two)
            .inOrder()
    }

    @Test
    fun directionalSpec_mappingBuilder_setsDefaultSpring() {
        val result =
            buildDirectionalMotionSpec(Spring) { constantValue(breakpoint = 10f, value = 20f) }

        assertThat(result).breakpoints().atPosition(10f).spring().isEqualTo(Spring)
    }

    @Test
    fun directionalSpec_mappingBuilder_canOverrideDefaultSpring() {
        val otherSpring = SpringParameters(stiffness = 10f, dampingRatio = 0.1f)
        val result =
            buildDirectionalMotionSpec(Spring) {
                constantValue(breakpoint = 10f, value = 20f, spring = otherSpring)
            }

        assertThat(result).breakpoints().atPosition(10f).spring().isEqualTo(otherSpring)
    }

    @Test
    fun directionalSpec_mappingBuilder_defaultsToNoGuarantee() {
        val result =
            buildDirectionalMotionSpec(Spring) { constantValue(breakpoint = 10f, value = 20f) }

        assertThat(result).breakpoints().atPosition(10f).guarantee().isEqualTo(Guarantee.None)
    }

    @Test
    fun directionalSpec_mappingBuilder_canSetGuarantee() {
        val guarantee = Guarantee.InputDelta(10f)
        val result =
            buildDirectionalMotionSpec(Spring) {
                constantValue(breakpoint = 10f, value = 20f, guarantee = guarantee)
            }

        assertThat(result).breakpoints().atPosition(10f).guarantee().isEqualTo(guarantee)
    }

    @Test
    fun directionalSpec_mappingBuilder_jumpTo_setsAbsoluteValue() {
        val result =
            buildDirectionalMotionSpec(Spring, Mapping.Fixed(99f)) {
                constantValue(breakpoint = 10f, value = 20f)
            }

        assertThat(result).breakpoints().positions().containsExactly(10f)
        assertThat(result).mappings().atOrAfter(10f).isConstantValue(20f)
    }

    @Test
    fun directionalSpec_mappingBuilder_jumpBy_setsRelativeValue() {
        val result =
            buildDirectionalMotionSpec(Spring, Mapping.Linear(factor = 0.5f)) {
                // At 10f the current value is 5f (10f * 0.5f)
                constantValueFromCurrent(breakpoint = 10f, delta = 30f)
            }

        assertThat(result).breakpoints().positions().containsExactly(10f)
        assertThat(result).mappings().atOrAfter(10f).isConstantValue(35f)
    }

    @Test
    fun directionalSpec_mappingBuilder_continueWithConstantValue_usesSourceValue() {
        val result =
            buildDirectionalMotionSpec(Spring, Mapping.Linear(factor = 0.5f)) {
                // At 5f the current value is 2.5f (5f * 0.5f)
                constantValueFromCurrent(breakpoint = 5f)
            }

        assertThat(result).mappings().atOrAfter(5f).isConstantValue(2.5f)
    }

    @Test
    fun directionalSpec_mappingBuilder_continueWithFractionalInput_matchesLinearMapping() {
        val result =
            buildDirectionalMotionSpec(Spring) {
                fractionalInput(breakpoint = 5f, from = 1f, fraction = .1f)
            }

        assertThat(result)
            .mappings()
            .atOrAfter(5f)
            .matchesLinearMapping(in1 = 5f, out1 = 1f, in2 = 15f, out2 = 2f)
    }

    @Test
    fun directionalSpec_mappingBuilder_continueWithTargetValue_matchesLinearMapping() {
        val result =
            buildDirectionalMotionSpec(Spring) {
                target(breakpoint = 5f, from = 1f, to = 20f)
                mapping(breakpoint = 30f, mapping = Mapping.Identity)
            }

        assertThat(result)
            .mappings()
            .atOrAfter(5f)
            .matchesLinearMapping(in1 = 5f, out1 = 1f, in2 = 30f, out2 = 20f)
    }

    @Test
    fun directionalSpec_mappingBuilder_breakpointsAtSamePosition_producesValidSegment() {
        val result =
            buildDirectionalMotionSpec(Spring) {
                target(breakpoint = 5f, from = 1f, to = 20f)
                mapping(breakpoint = 5f, mapping = Mapping.Identity)
            }
        assertThat(result)
            .mappings()
            .containsExactly(Mapping.Identity, Mapping.Fixed(1f), Mapping.Identity)
            .inOrder()
    }

    companion object {
        val Spring = SpringParameters(stiffness = 100f, dampingRatio = 1f)
        val B1 = BreakpointKey("One")
        val B2 = BreakpointKey("Two")
    }
}
