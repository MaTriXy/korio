package com.soywiz.korio.util

import com.soywiz.klock.Klock
import com.soywiz.korio.async.Promise
import com.soywiz.korio.async.async
import com.soywiz.korio.async.async2
import com.soywiz.korio.time.TimeProvider
import kotlin.reflect.KClass

class AsyncInmemoryCache {
	data class Entry(val timestamp: Long, val data: Promise<Any?>)

	val cache = LinkedHashMap<String, Entry?>()

	fun <T : Any> get(clazz: KClass<T>, key: String, ttlMs: Int) = AsyncInmemoryEntry<T>(clazz, this, key, ttlMs)

	//fun <T : Any?> getTyped(clazz: Class<T>, key: String = clazz, ttl: TimeSpan) = AsyncInmemoryEntry(clazz, this, key, ttl)

	@Suppress("UNCHECKED_CAST")
	suspend fun <T : Any?> get(key: String, ttlMs: Int, gen: suspend () -> T): T {
		val entry = cache[key]
		if (entry == null || (Klock.currentTimeMillis() - entry.timestamp) >= ttlMs) {
			cache[key] = AsyncInmemoryCache.Entry(TimeProvider.now(), async2(gen) as Promise<Any?>)
		}
		return (cache[key]!!.data as Promise<T>).await()
	}

	//suspend fun <T : Any?> get(key: String, ttl: TimeSpan, gen: () -> Promise<T>) = await(getAsync(key, ttl, gen))
}

class AsyncInmemoryEntry<T : Any>(val clazz: KClass<T>, val cache: AsyncInmemoryCache, val key: String, val ttlMs: Int) {
	//fun getAsync(gen: () -> Promise<T>): Promise<T> = async { cache.get(key, ttl, gen) }

	suspend fun get(routine: suspend () -> T) = cache.get(key, ttlMs, routine)
}
