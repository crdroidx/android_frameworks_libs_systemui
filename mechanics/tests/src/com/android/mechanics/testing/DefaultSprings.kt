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

package com.android.mechanics.testing

import com.android.mechanics.spring.SpringParameters

object DefaultSprings {
    val matStandardDefault =
        SpringParameters(
            stiffness = StandardMotionTokens.SpringDefaultSpatialStiffness,
            dampingRatio = StandardMotionTokens.SpringDefaultSpatialDamping,
        )
    val matStandardFast =
        SpringParameters(
            stiffness = StandardMotionTokens.SpringFastSpatialStiffness,
            dampingRatio = StandardMotionTokens.SpringFastSpatialDamping,
        )
    val matExpressiveDefault =
        SpringParameters(
            stiffness = ExpressiveMotionTokens.SpringDefaultSpatialStiffness,
            dampingRatio = ExpressiveMotionTokens.SpringDefaultSpatialDamping,
        )
    val matExpressiveFast =
        SpringParameters(
            stiffness = ExpressiveMotionTokens.SpringFastSpatialStiffness,
            dampingRatio = ExpressiveMotionTokens.SpringFastSpatialDamping,
        )

    internal object StandardMotionTokens {
        val SpringDefaultSpatialDamping = 0.9f
        val SpringDefaultSpatialStiffness = 700.0f
        val SpringDefaultEffectsDamping = 1.0f
        val SpringDefaultEffectsStiffness = 1600.0f
        val SpringFastSpatialDamping = 0.9f
        val SpringFastSpatialStiffness = 1400.0f
        val SpringFastEffectsDamping = 1.0f
        val SpringFastEffectsStiffness = 3800.0f
        val SpringSlowSpatialDamping = 0.9f
        val SpringSlowSpatialStiffness = 300.0f
        val SpringSlowEffectsDamping = 1.0f
        val SpringSlowEffectsStiffness = 800.0f
    }

    internal object ExpressiveMotionTokens {
        val SpringDefaultSpatialDamping = 0.8f
        val SpringDefaultSpatialStiffness = 380.0f
        val SpringDefaultEffectsDamping = 1.0f
        val SpringDefaultEffectsStiffness = 1600.0f
        val SpringFastSpatialDamping = 0.6f
        val SpringFastSpatialStiffness = 800.0f
        val SpringFastEffectsDamping = 1.0f
        val SpringFastEffectsStiffness = 3800.0f
        val SpringSlowSpatialDamping = 0.8f
        val SpringSlowSpatialStiffness = 200.0f
        val SpringSlowEffectsDamping = 1.0f
        val SpringSlowEffectsStiffness = 800.0f
    }
}
