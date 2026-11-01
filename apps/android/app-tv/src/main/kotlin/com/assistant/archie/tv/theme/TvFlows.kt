package com.assistant.archie.tv.theme

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/**
 * At most one value per [ms] (conflated: the newest wins, the first one passes at once). Spec 15
 * §6.2: streaming state reaches TV screens at ≤ 10 Hz (conversation) or 2 Hz (lists).
 */
fun <T> Flow<T>.sampled(ms: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(ms)
}
