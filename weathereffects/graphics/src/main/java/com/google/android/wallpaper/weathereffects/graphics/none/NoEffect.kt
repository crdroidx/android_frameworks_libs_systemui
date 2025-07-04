/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.google.android.wallpaper.weathereffects.graphics.none

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.SizeF
import com.google.android.wallpaper.weathereffects.graphics.WeatherEffect
import com.google.android.wallpaper.weathereffects.graphics.utils.MatrixUtils

/** Simply draws foreground and background images with no weather effect. */
class NoEffect(
    private var foreground: Bitmap,
    private var background: Bitmap,
    private var surfaceSize: SizeF,
) : WeatherEffect {
    override fun resize(newSurfaceSize: SizeF) {
        surfaceSize = newSurfaceSize
    }

    override fun update(deltaMillis: Long, frameTimeNanos: Long) {}

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(
            background,
            MatrixUtils.centerCropMatrix(
                surfaceSize,
                SizeF(background.width.toFloat(), background.height.toFloat()),
            ),
            null,
        )
        canvas.drawBitmap(
            foreground,
            MatrixUtils.centerCropMatrix(
                surfaceSize,
                SizeF(foreground.width.toFloat(), foreground.height.toFloat()),
            ),
            null,
        )
    }

    override fun reset() {}

    override fun release() {}

    override fun setIntensity(intensity: Float) {}

    override fun setBitmaps(foreground: Bitmap?, background: Bitmap): Boolean {
        // Only when background changes, we can infer the bitmap set changes
        if (this.background != background) {
            this.background.recycle()
            this.foreground.recycle()
            return false
        }
        this.background = background
        this.foreground = foreground ?: background
        return true
    }
}
