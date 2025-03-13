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

package com.android.app.displaylib

import android.util.Log
import android.view.Display
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.app.tracing.traceSection
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest

/**
 * Used to create instances of type `T` for a specific display.
 *
 * This is useful for resources or objects that need to be managed independently for each connected
 * display (e.g., UI state, rendering contexts, or display-specific configurations).
 *
 * Note that in most cases this can be implemented by a simple `@AssistedFactory` with `displayId`
 * parameter
 *
 * ```kotlin
 * class SomeType @AssistedInject constructor(@Assisted displayId: Int,..)
 *      @AssistedFactory
 *      interface Factory {
 *         fun create(displayId: Int): SomeType
 *      }
 *  }
 * ```
 *
 * Then it can be used to create a [PerDisplayRepository] as follows:
 * ```kotlin
 * // Injected:
 * val repositoryFactory: PerDisplayRepositoryImpl.Factory
 * val instanceFactory: PerDisplayRepositoryImpl.Factory
 * // repository creation:
 * repositoryFactory.create(instanceFactory::create)
 * ```
 *
 * @see PerDisplayRepository For how to retrieve and manage instances created by this factory.
 */
fun interface PerDisplayInstanceProvider<T> {
    /** Creates an instance for a display. */
    fun createInstance(displayId: Int): T?
}

/**
 * Extends [PerDisplayInstanceProvider], adding support for destroying the instance.
 *
 * This is useful for releasing resources associated with a display when it is disconnected or when
 * the per-display instance is no longer needed.
 */
interface PerDisplayInstanceProviderWithTeardown<T> : PerDisplayInstanceProvider<T> {
    /** Destroys a previously created instance of `T` forever. */
    fun destroyInstance(instance: T)
}

/**
 * Provides access to per-display instances of type `T`.
 *
 * Acts as a repository, managing the caching and retrieval of instances created by a
 * [PerDisplayInstanceProvider]. It ensures that only one instance of `T` exists per display ID.
 */
interface PerDisplayRepository<T> {
    /** Gets the cached instance or create a new one for a given display. */
    operator fun get(displayId: Int): T?

    /** Debug name for this repository, mainly for tracing and logging. */
    val debugName: String

    /**
     * Callback to run when a given repository is initialized.
     *
     * This allows the caller to perform custom logic when the repository is ready to be used, e.g.
     * register to dumpManager.
     *
     * Note that the instance is *leaked* outside of this class, so it should only be done when
     * repository is meant to live as long as the caller. In systemUI this is ok because the
     * repository lives as long as the process itself.
     */
    fun interface InitCallback {
        fun onInit(debugName: String, instance: Any)
    }
}

/** Qualifier for [CoroutineScope] used for displaylib background tasks. */
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class DisplayLibBackground

/**
 * Default implementation of [PerDisplayRepository].
 *
 * This class manages a cache of per-display instances of type `T`, creating them using a provided
 * [PerDisplayInstanceProvider] and optionally tearing them down using a
 * [PerDisplayInstanceProviderWithTeardown] when displays are disconnected.
 *
 * It listens to the [DisplayRepository] to detect when displays are added or removed, and
 * automatically manages the lifecycle of the per-display instances.
 *
 * Note that this is a [PerDisplayStoreImpl] 2.0 that doesn't require [CoreStartable] bindings,
 * providing all args in the constructor.
 */
class PerDisplayInstanceRepositoryImpl<T>
@AssistedInject
constructor(
    @Assisted override val debugName: String,
    @Assisted private val instanceProvider: PerDisplayInstanceProvider<T>,
    @DisplayLibBackground bgApplicationScope: CoroutineScope,
    private val displayRepository: DisplayRepository,
    private val initCallback: PerDisplayRepository.InitCallback,
) : PerDisplayRepository<T> {

    private val perDisplayInstances = ConcurrentHashMap<Int, T?>()

    init {
        bgApplicationScope.launch("$debugName#start") { start() }
    }

    private suspend fun start() {
        initCallback.onInit(debugName, this)
        displayRepository.displayIds.collectLatest { displayIds ->
            val toRemove = perDisplayInstances.keys - displayIds
            toRemove.forEach { displayId ->
                Log.d(TAG, "<$debugName> destroying instance for displayId=$displayId.")
                perDisplayInstances.remove(displayId)?.let { instance ->
                    (instanceProvider as? PerDisplayInstanceProviderWithTeardown)?.destroyInstance(
                        instance
                    )
                }
            }
        }
    }

    override fun get(displayId: Int): T? {
        if (displayRepository.getDisplay(displayId) == null) {
            Log.e(TAG, "<$debugName: Display with id $displayId doesn't exist.")
            return null
        }

        // If it doesn't exist, create it and put it in the map.
        return perDisplayInstances.computeIfAbsent(displayId) { key ->
            Log.d(TAG, "<$debugName> creating instance for displayId=$key, as it wasn't available.")
            val instance =
                traceSection({ "creating instance of $debugName for displayId=$key" }) {
                    instanceProvider.createInstance(key)
                }
            if (instance == null) {
                Log.e(
                    TAG,
                    "<$debugName> returning null because createInstance($key) returned null.",
                )
            }
            instance
        }
    }

    @AssistedFactory
    interface Factory<T> {
        fun create(
            debugName: String,
            instanceProvider: PerDisplayInstanceProvider<T>,
        ): PerDisplayInstanceRepositoryImpl<T>
    }

    companion object {
        private const val TAG = "PerDisplayInstanceRepo"
    }

    override fun toString(): String {
        return "PerDisplayInstanceRepositoryImpl(" +
            "debugName='$debugName', instances=$perDisplayInstances)"
    }
}

/**
 * Provides an instance of a given class **only** for the default display, even if asked for another
 * display.
 *
 * This is useful in case of **flag refactors**: it can be provided instead of an instance of
 * [PerDisplayInstanceRepositoryImpl] when a flag related to multi display refactoring is off.
 *
 * Note that this still requires all instances to be provided by a [PerDisplayInstanceProvider]. If
 * you want to provide an existing instance instead for the default display, either implement it in
 * a custom [PerDisplayInstanceProvider] (e.g. inject it in the constructor and return it if the
 * displayId is zero), or use [SingleInstanceRepositoryImpl].
 */
class DefaultDisplayOnlyInstanceRepositoryImpl<T>(
    override val debugName: String,
    private val instanceProvider: PerDisplayInstanceProvider<T>,
) : PerDisplayRepository<T> {
    private val lazyDefaultDisplayInstance by lazy {
        instanceProvider.createInstance(Display.DEFAULT_DISPLAY)
    }

    override fun get(displayId: Int): T? = lazyDefaultDisplayInstance
}

/**
 * Always returns [instance] for any display.
 *
 * This can be used to provide a single instance based on a flag value during a refactor. Similar to
 * [DefaultDisplayOnlyInstanceRepositoryImpl], but also avoids creating the
 * [PerDisplayInstanceProvider]. This is useful when you want to provide an existing instance only,
 * without even instantiating a [PerDisplayInstanceProvider].
 */
class SingleInstanceRepositoryImpl<T>(override val debugName: String, private val instance: T) :
    PerDisplayRepository<T> {
    override fun get(displayId: Int): T? = instance
}
