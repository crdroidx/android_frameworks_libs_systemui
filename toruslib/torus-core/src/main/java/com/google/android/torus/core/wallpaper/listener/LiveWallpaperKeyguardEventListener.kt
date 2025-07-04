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

package com.google.android.torus.core.wallpaper.listener

/** Interface that is used to implement specific wallpaper callbacks related to keyguard events. */
interface LiveWallpaperKeyguardEventListener {

    /** Called when the keyguard is going away. */
    fun onKeyguardGoingAway()

    fun onKeyguardAppearing()
}
