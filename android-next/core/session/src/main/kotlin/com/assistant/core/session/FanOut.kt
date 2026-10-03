package com.assistant.core.session

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lossless fan-out: every subscriber has its own UNLIMITED channel, so a slow collector never makes
 * another one (or the socket) drop anything (spec 12 L-2). Delivery starts at subscription time:
 * subscribe before connecting (or use [subscribe] eagerly).
 */
class FanOut<T> {
    private val subscribers = CopyOnWriteArrayList<Channel<T>>()

    fun publish(value: T) {
        for (s in subscribers) s.trySend(value)
    }

    /** Eager subscription: registered now; cancel the channel to unsubscribe. */
    fun subscribe(): ReceiveChannel<T> {
        val ch = Channel<T>(Channel.UNLIMITED)
        subscribers.add(ch)
        ch.invokeOnClose { subscribers.remove(ch) }
        return ch
    }

    /** Registered when collection starts. */
    fun asFlow(): Flow<T> = flow {
        val ch = subscribe()
        try {
            for (v in ch) emit(v)
        } finally {
            ch.cancel()
        }
    }

    val subscriberCount: Int get() = subscribers.size
}
