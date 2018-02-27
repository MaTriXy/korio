@file:Suppress("EXPERIMENTAL_FEATURE_WARNING")

package com.soywiz.korio.async

import com.soywiz.korio.lang.Closeable

class Signal<T>(val onRegister: () -> Unit = {}) { //: AsyncSequence<T> {
	inner class Node(val once: Boolean, val item: (T) -> Unit) : Closeable {
		override fun close() {
			handlers.remove(this)
		}
	}

	private var handlersToRun = ArrayList<Node>()
	private var handlers = ArrayList<Node>()
	private var handlersNoOnce = ArrayList<Node>()

	val listenerCount: Int get() = handlers.size

	fun once(handler: (T) -> Unit): Closeable = _add(true, handler)
	fun add(handler: (T) -> Unit): Closeable = _add(false, handler)

	fun clear() = handlers.clear()

	private fun _add(once: Boolean, handler: (T) -> Unit): Closeable {
		onRegister()
		val node = Node(once, handler)
		handlers.add(node)
		return node
	}

	operator fun invoke(value: T) {
		val oldHandlers = handlers
		handlersNoOnce.clear()
		handlersToRun.clear()
		for (handler in oldHandlers) {
			handlersToRun.add(handler)
			if (!handler.once) handlersNoOnce.add(handler)
		}
		val temp = handlers
		handlers = handlersNoOnce
		handlersNoOnce = temp

		for (handler in handlersToRun) {
			handler.item(value)
		}
	}

	operator fun invoke(handler: (T) -> Unit): Closeable = add(handler)

	suspend fun listen(): SuspendingSequence<T> = asyncGenerate {
		while (true) {
			yield(waitOne())
		}
	}


//override fun iterator(): AsyncIterator<T> = asyncGenerate {
//	while (true) {
//		yield(waitOne())
//	}
//}.iterator()
}

//class AsyncSignal<T>(context: CoroutineContext) {

//}

fun <TI, TO> Signal<TI>.mapSignal(transform: (TI) -> TO): Signal<TO> {
	val out = Signal<TO>()
	this.add { out(transform(it)) }
	return out
}

operator fun Signal<Unit>.invoke() = invoke(Unit)

suspend fun <T> Signal<T>.waitOne(): T = suspendCancellableCoroutine { c ->
	var close: Closeable? = null
	close = once {
		close?.close()
		c.resume(it)
	}
	c.onCancel {
		close?.close()
	}
}

fun <T> Signal<T>.waitOnePromise(): Promise<T> {
	val deferred = Promise.Deferred<T>()
	var close: Closeable? = null
	close = once {
		close?.close()
		deferred.resolve(it)
	}
	deferred.onCancel {
		close.close()
	}
	return deferred.promise
}
