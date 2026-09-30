package com.kyant.backdrop.catalog.utils

/** Android-only entry for the upstream expect/actual awaitFrame helper. */
internal suspend fun awaitFrame() {
    kotlinx.coroutines.android.awaitFrame()
}
