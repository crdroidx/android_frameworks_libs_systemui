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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.mechanics.behavior

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.android.mechanics.spec.Breakpoint
import com.android.mechanics.spec.BreakpointKey
import com.android.mechanics.spec.DirectionalMotionSpec
import com.android.mechanics.spec.InputDirection
import com.android.mechanics.spec.Mapping
import com.android.mechanics.spec.MotionSpec
import com.android.mechanics.spec.OnChangeSegmentHandler
import com.android.mechanics.spec.SegmentData
import com.android.mechanics.spec.SegmentKey
import com.android.mechanics.spec.builder
import com.android.mechanics.spec.reverseBuilder
import com.android.mechanics.spring.SpringParameters

/** Motion spec for a vertically expandable container. */
class EdgeContainerExpansionSpec(
    val visibleHeight: Dp = Defaults.VisibleHeight,
    val preDetachRatio: Float = Defaults.PreDetachRatio,
    val detachHeight: Dp = Defaults.DetachHeight,
    val attachHeight: Dp = Defaults.AttachHeight,
    val widthOffset: Dp = Defaults.WidthOffset,
    val minRadius: Dp = Defaults.MinRadius,
    val radius: Dp = Defaults.Radius,
    val attachSpring: SpringParameters = Defaults.AttachSpring,
    val detachSpring: SpringParameters = Defaults.DetachSpring,
    val opacitySpring: SpringParameters = Defaults.OpacitySpring,
) {
    fun createHeightSpec(motionScheme: MotionScheme, density: Density): MotionSpec {
        return with(density) {
            val spatialSpring = SpringParameters(motionScheme.defaultSpatialSpec())

            val detachSpec =
                DirectionalMotionSpec.builder(
                        initialMapping = Mapping.Zero,
                        defaultSpring = spatialSpring,
                    )
                    .toBreakpoint(0f, key = Breakpoints.Attach)
                    .continueWith(Mapping.Linear(preDetachRatio))
                    .toBreakpoint(detachHeight.toPx(), key = Breakpoints.Detach)
                    .completeWith(Mapping.Identity, detachSpring)

            val attachSpec =
                DirectionalMotionSpec.reverseBuilder(defaultSpring = spatialSpring)
                    .toBreakpoint(attachHeight.toPx(), key = Breakpoints.Detach)
                    .completeWith(mapping = Mapping.Zero, attachSpring)

            val segmentHandlers =
                mapOf<SegmentKey, OnChangeSegmentHandler>(
                    SegmentKey(Breakpoints.Detach, Breakpoint.maxLimit.key, InputDirection.Min) to
                        { currentSegment, _, newDirection ->
                            if (newDirection != currentSegment.direction) currentSegment else null
                        },
                    SegmentKey(Breakpoints.Attach, Breakpoints.Detach, InputDirection.Max) to
                        { currentSegment: SegmentData, newInput: Float, newDirection: InputDirection
                            ->
                            if (newDirection != currentSegment.direction && newInput >= 0)
                                currentSegment
                            else null
                        },
                )

            MotionSpec(
                maxDirection = detachSpec,
                minDirection = attachSpec,
                segmentHandlers = segmentHandlers,
            )
        }
    }

    fun createWidthSpec(
        intrinsicWidth: Float,
        motionScheme: MotionScheme,
        density: Density,
    ): MotionSpec {
        return with(density) {
            MotionSpec.builder(
                    SpringParameters(motionScheme.defaultSpatialSpec()),
                    initialMapping = { input ->
                        val fraction = (input / detachHeight.toPx()).fastCoerceIn(0f, 1f)
                        intrinsicWidth - lerp(widthOffset.toPx(), 0f, fraction)
                    },
                )
                .complete()
        }
    }

    fun createAlphaSpec(motionScheme: MotionScheme, density: Density): MotionSpec {
        return with(density) {
            val detachSpec =
                DirectionalMotionSpec.builder(
                        SpringParameters(motionScheme.defaultEffectsSpec()),
                        initialMapping = Mapping.Zero,
                    )
                    .toBreakpoint(visibleHeight.toPx())
                    .completeWith(Mapping.One, opacitySpring)

            val attachSpec =
                DirectionalMotionSpec.builder(
                        SpringParameters(motionScheme.defaultEffectsSpec()),
                        initialMapping = Mapping.Zero,
                    )
                    .toBreakpoint(visibleHeight.toPx())
                    .completeWith(Mapping.One, opacitySpring)

            MotionSpec(maxDirection = detachSpec, minDirection = attachSpec)
        }
    }

    companion object {
        object Breakpoints {
            val Attach = BreakpointKey("EdgeContainerExpansion::Attach")
            val Detach = BreakpointKey("EdgeContainerExpansion::Detach")
        }

        object Defaults {
            val VisibleHeight = 24.dp
            val PreDetachRatio = .25f
            val DetachHeight = 80.dp
            val AttachHeight = 40.dp

            val WidthOffset = 28.dp

            val MinRadius = 28.dp
            val Radius = 46.dp

            val AttachSpring = SpringParameters(stiffness = 380f, dampingRatio = 0.9f)
            val DetachSpring = SpringParameters(stiffness = 380f, dampingRatio = 0.9f)
            val OpacitySpring = SpringParameters(stiffness = 1200f, dampingRatio = 0.99f)
        }
    }
}
