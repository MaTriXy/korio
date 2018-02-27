@file:Suppress("EXPERIMENTAL_FEATURE_WARNING")

package com.soywiz.korio.async

import com.soywiz.klock.Klock
import com.soywiz.korio.KorioNative
import com.soywiz.korio.coroutine.*
import com.soywiz.korio.error.invalidOp
import com.soywiz.korio.lang.AtomicInteger
import com.soywiz.korio.lang.Closeable
import com.soywiz.korio.lang.printStackTrace
import com.soywiz.korio.time.TimeProvider
import com.soywiz.korio.util.clamp

abstract class EventLoopFactory {
	abstract fun createEventLoop(): EventLoop
}

val eventLoopFactoryDefaultImpl: EventLoopFactory get() = KorioNative.eventLoopFactoryDefaultImpl

val tasksInProgress = AtomicInteger(0)

// @TODO: Check CoroutineDispatcher
abstract class EventLoop(val captureCloseables: Boolean) : Closeable {
	constructor() : this(captureCloseables = true)

	val coroutineContext = EventLoopCoroutineContext(this)

	companion object {
		fun main(eventLoop: EventLoop, entry: suspend EventLoop.() -> Unit): Unit {
			tasksInProgress.incrementAndGet()
			eventLoop.setImmediate {
				entry.korioStartCoroutine(eventLoop, object : Continuation<Unit> {
					override val context: CoroutineContext = EventLoopCoroutineContext(eventLoop)

					override fun resume(value: Unit) {
						tasksInProgress.decrementAndGet()
					}

					override fun resumeWithException(exception: Throwable) {
						tasksInProgress.decrementAndGet()
						exception.printStackTrace()
					}
				})
			}
			eventLoop.loop()
		}

		operator fun invoke(entry: suspend EventLoop.() -> Unit): Unit = main(entry)

		fun main(entry: suspend EventLoop.() -> Unit): Unit = main(eventLoopFactoryDefaultImpl.createEventLoop()) {
			this.entry()
		}
	}

	abstract protected fun setTimeoutInternal(ms: Int, callback: () -> Unit): Closeable

	open protected fun setIntervalInternal(ms: Int, callback: () -> Unit): Closeable {
		var cancelled = false
		fun step() {
			setTimeoutInternal(ms, {
				if (!cancelled) {
					callback()
					step()
				}
			})
		}
		step()
		return Closeable { cancelled = true }
	}

	open protected fun setImmediateInternal(handler: () -> Unit): Unit = run { setTimeoutInternal(0, handler) }

	var fps: Double = 60.0

	var lastRequest = 0.0
	open protected fun requestAnimationFrameInternal(callback: () -> Unit): Closeable {
		val step = 1000.0 / fps
		val now = Klock.currentTimeMillisDouble()
		if (lastRequest == 0.0) lastRequest = now
		lastRequest = now
		return setTimeoutInternal((step - (now - lastRequest)).clamp(0.0, step).toInt(), callback)
	}

	open fun loop(): Unit = Unit

	private val closeables = LinkedHashSet<Closeable>()

	private fun Closeable.capture(): Closeable {
		if (captureCloseables) {
			val closeable = this
			closeables += closeable
			return Closeable {
				closeables -= closeable
				closeable.close()
			}
		} else {
			return this
		}
	}

	fun setImmediate(handler: () -> Unit): Unit = setImmediateInternal(handler)
	fun setTimeout(ms: Int, callback: () -> Unit): Closeable = setTimeoutInternal(ms, callback).capture()
	fun setInterval(ms: Int, callback: () -> Unit): Closeable = setIntervalInternal(ms, callback).capture()

	fun setIntervalImmediate(ms: Int, callback: () -> Unit): Closeable {
		setImmediateInternal(callback)
		return setIntervalInternal(ms, callback).capture()
	}

	fun requestAnimationFrame(callback: () -> Unit): Closeable {
		return requestAnimationFrameInternal(callback).capture()
	}

	fun queue(handler: () -> Unit): Unit = setImmediate(handler)

	open fun <T> queueContinuation(continuation: Continuation<T>, result: T): Unit = queue { continuation.resume(result) }
	open fun <T> queueContinuationException(continuation: Continuation<T>, result: Throwable): Unit = queue { continuation.resumeWithException(result) }

	fun animationFrameLoop(callback: () -> Unit): Closeable {
		var closeable: Closeable? = null
		var step: (() -> Unit)? = null
		var cancelled = false
		step = {
			//println("animationFrameLoop:cancelled:$cancelled")
			if (!cancelled) {
				//println("--callback[")
				callback()
				//println("--callback]")
				closeable = this.requestAnimationFrameInternal(step!!)
			} else {
				//println("--cancelled!")
			}
		}
		step()
		return Closeable {
			cancelled = true
			closeable?.close()
		}.capture()
	}

	override fun close() {
		for (closeable in closeables) {
			closeable.close()
		}
		closeables.clear()
	}

	open val time: Long get() = TimeProvider.now()

	open fun step(ms: Int): Unit = Unit

	suspend fun sleep(ms: Int): Unit = suspendCancellableCoroutine { c ->
		val cc = setTimeout(ms) { c.resume(Unit) }
		c.onCancel { cc.close() }
	}

	suspend fun sleepNextFrame(): Unit = suspendCancellableCoroutine { c ->
		val cc = requestAnimationFrame { c.resume(Unit) }
		c.onCancel { cc.close() }
	}
}

class EventLoopCoroutineContext(val eventLoop: EventLoop) : AbstractCoroutineContextElement(EventLoopCoroutineContext.Key) {
	companion object Key : CoroutineContextKey<EventLoopCoroutineContext>
}

val CoroutineContext.eventLoop: EventLoop
	get() {
		return this[EventLoopCoroutineContext.Key]?.eventLoop
			?: invalidOp("No EventLoop associated to this CoroutineContext")
	}

val Continuation<*>.eventLoop: EventLoop get() = this.context.eventLoop

suspend fun CoroutineContext.sleep(ms: Int) = this.eventLoop.sleep(ms)