package com.soywiz.korio.stream

import com.soywiz.korio.async.asyncFun
import com.soywiz.korio.async.executeInWorker
import com.soywiz.korio.util.*
import com.soywiz.korio.vfs.VfsFile
import com.soywiz.korio.vfs.VfsOpenMode
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.*

interface AsyncBaseStream {
}

interface AsyncInputStream : AsyncBaseStream {
	suspend fun read(buffer: ByteArray, offset: Int, len: Int): Int
}

interface AsyncOutputStream : AsyncBaseStream {
	suspend fun write(buffer: ByteArray, offset: Int, len: Int): Unit
}

interface AsyncLengthStream : AsyncBaseStream {
	suspend fun setLength(value: Long): Unit = throw UnsupportedOperationException()
	suspend fun getLength(): Long = throw UnsupportedOperationException()
}

interface AsyncRAInputStream {
	suspend fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int
}

interface AsyncRAOutputStream {
	suspend fun write(position: Long, buffer: ByteArray, offset: Int, len: Int): Unit
}

fun AsyncBaseStream.toAsyncStream(): AsyncStream {
	val input = this as? AsyncInputStream
	val output = this as? AsyncOutputStream
	val len = this as? AsyncLengthStream
	val closeable = this as? AsyncCloseable
	return object : AsyncStreamBase() {
		var expectedPosition: Long = 0L

		suspend override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int = asyncFun {
			if (input == null) throw UnsupportedOperationException()
			if (position != expectedPosition) throw UnsupportedOperationException("Seeking not supported!")
			val read = input.read(buffer, offset, len)
			expectedPosition += read
			read
		}

		suspend override fun write(position: Long, buffer: ByteArray, offset: Int, len: Int) = asyncFun {
			if (output == null) throw UnsupportedOperationException()
			if (position != expectedPosition) throw UnsupportedOperationException("Seeking not supported!")
			output.write(buffer, offset, len)
			expectedPosition += len
		}

		suspend override fun setLength(value: Long) = asyncFun {
			if (len == null) throw UnsupportedOperationException()
			len.setLength(value)
		}

		suspend override fun getLength(): Long = asyncFun { len?.getLength() ?: throw UnsupportedOperationException() }

		suspend override fun close(): Unit = asyncFun {
			closeable?.close()
			Unit
		}
	}.toAsyncStream()
}

open class AsyncStreamBase : AsyncCloseable, AsyncRAInputStream, AsyncRAOutputStream, AsyncLengthStream {
	suspend override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int = throw UnsupportedOperationException()
	suspend override fun write(position: Long, buffer: ByteArray, offset: Int, len: Int): Unit = throw UnsupportedOperationException()

	suspend override fun setLength(value: Long): Unit = throw UnsupportedOperationException()
	suspend override fun getLength(): Long = throw UnsupportedOperationException()

	suspend override fun close(): Unit = Unit
}

fun AsyncStreamBase.toAsyncStream(position: Long = 0L): AsyncStream = AsyncStream(this, position)

class AsyncStream(val base: AsyncStreamBase, var position: Long = 0L) : AsyncInputStream, AsyncOutputStream, AsyncCloseable {
	suspend override fun read(buffer: ByteArray, offset: Int, len: Int): Int = asyncFun {
		val read = base.read(position, buffer, offset, len)
		position += read
		read
	}

	suspend override fun write(buffer: ByteArray, offset: Int, len: Int): Unit = asyncFun {
		base.write(position, buffer, offset, len)
		position += len
		Unit
	}

	suspend fun setPosition(value: Long): Unit = run { this.position = value }
	suspend fun getPosition(): Long = this.position
	suspend fun setLength(value: Long): Unit = base.setLength(value)
	suspend fun getLength(): Long = base.getLength()

	suspend fun getAvailable(): Long = asyncFun { getLength() - getPosition() }
	suspend fun eof(): Boolean = asyncFun { this.getAvailable() <= 0L }

	// @TODO: Add refs to StreamBase?
	suspend override fun close(): Unit = base.close()

	suspend fun clone(): AsyncStream = AsyncStream(base, position)
}

class SliceAsyncStreamBase(internal val base: AsyncStreamBase, internal val baseStart: Long, internal val baseEnd: Long) : AsyncStreamBase() {
	internal val baseLength = baseEnd - baseStart

	private fun clampPosition(position: Long) = position.clamp(baseStart, baseEnd)

	private fun clampPositionLen(position: Long, len: Int): Pair<Long, Int> {
		if (position < 0L) throw IllegalArgumentException("Invalid position")
		val targetStartPosition = clampPosition(this.baseStart + position)
		val targetEndPosition = clampPosition(targetStartPosition + len)
		val targetLen = (targetEndPosition - targetStartPosition).toInt()
		return Pair(targetStartPosition, targetLen)
	}

	suspend override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int = asyncFun {
		val (targetStartPosition, targetLen) = clampPositionLen(position, len)
		base.read(targetStartPosition, buffer, offset, targetLen)
	}

	suspend override fun write(position: Long, buffer: ByteArray, offset: Int, len: Int) = asyncFun {
		val (targetStartPosition, targetLen) = clampPositionLen(position, len)
		base.write(targetStartPosition, buffer, offset, targetLen)
	}

	suspend override fun getLength(): Long = baseLength

	suspend override fun close() = base.close()

	override fun toString(): String = "SliceAsyncStreamBase($base, $baseStart, $baseEnd)"
}

suspend fun AsyncStream.sliceWithStart(start: Long): AsyncStream = asyncFun { sliceWithBounds(start, this.getLength()) }

fun AsyncStream.sliceWithSize(start: Long, length: Long): AsyncStream = sliceWithBounds(start, start + length)

fun AsyncStream.slice(range: IntRange): AsyncStream = sliceWithBounds(range.start.toLong(), (range.endInclusive.toLong() + 1))
fun AsyncStream.slice(range: LongRange): AsyncStream = sliceWithBounds(range.start, (range.endInclusive + 1))

fun AsyncStream.sliceWithBounds(start: Long, end: Long): AsyncStream {
	// @TODO: Check bounds
	return if (this.base is SliceAsyncStreamBase) {
		SliceAsyncStreamBase(this.base.base, this.base.baseStart + start, this.base.baseStart + end).toAsyncStream()
	} else {
		SliceAsyncStreamBase(this.base, start, end).toAsyncStream()
	}
}

suspend fun AsyncStream.slice(): AsyncStream = asyncFun { this.sliceWithSize(0L, this.getLength()) }

suspend fun AsyncStream.readSlice(length: Long): AsyncStream = asyncFun {
	val start = getPosition()
	val out = this.sliceWithSize(start, length)
	setPosition(start + length)
	out
}

suspend fun AsyncStream.readStream(length: Int): AsyncStream = readSlice(length.toLong())
suspend fun AsyncStream.readStream(length: Long): AsyncStream = readSlice(length)

suspend fun AsyncInputStream.readStringz(charset: Charset = Charsets.UTF_8): String = asyncFun {
	val buf = ByteArrayOutputStream()
	val temp = BYTES_TEMP
	while (true) {
		val read = read(temp, 0, 1)
		if (read <= 0) break
		buf.write(temp[0].toInt())
	}
	buf.toByteArray().toString(charset)
}

suspend fun AsyncInputStream.readStringz(len: Int, charset: Charset = Charsets.UTF_8): String = asyncFun {
	val res = readBytes(len)
	val index = res.indexOf(0.toByte())
	String(res, 0, if (index < 0) len else index, charset)
}

suspend fun AsyncInputStream.readString(len: Int, charset: Charset = Charsets.UTF_8): String = asyncFun { readBytes(len).toString(charset) }

suspend fun AsyncOutputStream.writeStringz(str: String, charset: Charset = Charsets.UTF_8) = this.writeBytes(str.toBytez(charset))
suspend fun AsyncOutputStream.writeStringz(str: String, len: Int, charset: Charset = Charsets.UTF_8) = this.writeBytes(str.toBytez(len, charset))

suspend fun AsyncOutputStream.writeString(string: String, charset: Charset = Charsets.UTF_8): Unit = asyncFun { writeBytes(string.toByteArray(charset)) }

suspend fun AsyncInputStream.readExact(buffer: ByteArray, offset: Int, len: Int) = asyncFun {
	var remaining = len
	var coffset = offset
	while (remaining > 0) {
		val read = read(buffer, coffset, remaining)
		if (read < 0) break
		if (read == 0) throw IllegalStateException("Not enough data")
		coffset += read
		remaining -= read
	}
}

suspend private fun AsyncInputStream.readTempExact(len: Int, temp: ByteArray = BYTES_TEMP): ByteArray = asyncFun {
	temp.apply { readExact(temp, 0, len) }
}

suspend fun AsyncInputStream.read(data: ByteArray): Int = read(data, 0, data.size)
suspend fun AsyncInputStream.read(data: UByteArray): Int = read(data.data, 0, data.size)

suspend fun AsyncInputStream.readBytes(len: Int): ByteArray = asyncFun {
	val ba = ByteArray(len)
	Arrays.copyOf(ba, read(ba, 0, len))
}

suspend fun AsyncInputStream.readBytesExact(len: Int): ByteArray = asyncFun { ByteArray(len).apply { readExact(this, 0, len) } }

suspend fun AsyncInputStream.readU8(): Int = asyncFun { readTempExact(1).readU8(0) }
suspend fun AsyncInputStream.readU16_le(): Int = asyncFun { readTempExact(2).readU16_le(0) }
suspend fun AsyncInputStream.readU24_le(): Int = asyncFun { readTempExact(3).readU24_le(0) }
suspend fun AsyncInputStream.readU32_le(): Long = asyncFun { readTempExact(4).readU32_le(0) }
suspend fun AsyncInputStream.readS16_le(): Int = asyncFun { readTempExact(2).readS16_le(0) }
suspend fun AsyncInputStream.readS32_le(): Int = asyncFun { readTempExact(4).readS32_le(0) }
suspend fun AsyncInputStream.readS64_le(): Long = asyncFun { readTempExact(8).readS64_le(0) }
suspend fun AsyncInputStream.readF32_le(): Float = asyncFun { readTempExact(4).readF32_le(0) }
suspend fun AsyncInputStream.readF64_le(): Double = asyncFun { readTempExact(8).readF64_le(0) }
suspend fun AsyncInputStream.readU16_be(): Int = asyncFun { readTempExact(2).readU16_be(0) }
suspend fun AsyncInputStream.readU24_be(): Int = asyncFun { readTempExact(3).readU24_be(0) }
suspend fun AsyncInputStream.readU32_be(): Long = asyncFun { readTempExact(4).readU32_be(0) }
suspend fun AsyncInputStream.readS16_be(): Int = asyncFun { readTempExact(2).readS16_be(0) }
suspend fun AsyncInputStream.readS32_be(): Int = asyncFun { readTempExact(4).readS32_be(0) }
suspend fun AsyncInputStream.readS64_be(): Long = asyncFun { readTempExact(8).readS64_be(0) }
suspend fun AsyncInputStream.readF32_be(): Float = asyncFun { readTempExact(4).readF32_be(0) }
suspend fun AsyncInputStream.readF64_be(): Double = asyncFun { readTempExact(8).readF64_be(0) }

suspend fun AsyncStream.hasLength(): Boolean = asyncFun {
	try {
		getLength(); true
	} catch (t: Throwable) {
		false
	}
}

suspend fun AsyncStream.hasAvailable(): Boolean = asyncFun {
	try {
		getAvailable(); true
	} catch (t: Throwable) {
		false
	}
}

suspend fun AsyncStream.readAll(): ByteArray = asyncFun {
	if (hasAvailable()) {
		val available = getAvailable().toInt()
		readBytes(available)
	} else {
		val out = ByteArrayOutputStream()
		val temp = BYTES_TEMP
		while (true) {
			val r = read(temp, 0, temp.size)
			if (r <= 0) break
			out.write(temp, 0, r)
		}
		out.toByteArray()
	}
}

// readAll alias
suspend fun AsyncStream.readAvailable(): ByteArray = readAll()

suspend fun AsyncInputStream.readUByteArray(count: Int): UByteArray = asyncFun { UByteArray(readBytesExact(count)) }

suspend fun AsyncInputStream.readShortArray_le(count: Int): ShortArray = asyncFun { readBytesExact(count * 2).readShortArray_le(0, count) }
suspend fun AsyncInputStream.readShortArray_be(count: Int): ShortArray = asyncFun { readBytesExact(count * 2).readShortArray_be(0, count) }

suspend fun AsyncInputStream.readCharArray_le(count: Int): CharArray = asyncFun { readBytesExact(count * 2).readCharArray_le(0, count) }
suspend fun AsyncInputStream.readCharArray_be(count: Int): CharArray = asyncFun { readBytesExact(count * 2).readCharArray_be(0, count) }

suspend fun AsyncInputStream.readIntArray_le(count: Int): IntArray = asyncFun { readBytesExact(count * 4).readIntArray_le(0, count) }
suspend fun AsyncInputStream.readIntArray_be(count: Int): IntArray = asyncFun { readBytesExact(count * 4).readIntArray_be(0, count) }

suspend fun AsyncInputStream.readLongArray_le(count: Int): LongArray = asyncFun { readBytesExact(count * 8).readLongArray_le(0, count) }
suspend fun AsyncInputStream.readLongArray_be(count: Int): LongArray = asyncFun { readBytesExact(count * 8).readLongArray_le(0, count) }

suspend fun AsyncInputStream.readFloatArray_le(count: Int): FloatArray = asyncFun { readBytesExact(count * 4).readFloatArray_le(0, count) }
suspend fun AsyncInputStream.readFloatArray_be(count: Int): FloatArray = asyncFun { readBytesExact(count * 4).readFloatArray_be(0, count) }

suspend fun AsyncInputStream.readDoubleArray_le(count: Int): DoubleArray = asyncFun { readBytesExact(count * 8).readDoubleArray_le(0, count) }
suspend fun AsyncInputStream.readDoubleArray_be(count: Int): DoubleArray = asyncFun { readBytesExact(count * 8).readDoubleArray_be(0, count) }

suspend fun AsyncOutputStream.writeBytes(data: ByteArray): Unit = write(data, 0, data.size)
suspend fun AsyncOutputStream.writeBytes(data: ByteArraySlice): Unit = write(data.data, data.position, data.length)
suspend fun AsyncOutputStream.write8(v: Int): Unit = asyncFun { write(BYTES_TEMP.apply { write8(0, v) }, 0, 1) }
suspend fun AsyncOutputStream.write16_le(v: Int): Unit = asyncFun { write(BYTES_TEMP.apply { write16_le(0, v) }, 0, 2) }
suspend fun AsyncOutputStream.write24_le(v: Int): Unit = asyncFun { write(BYTES_TEMP.apply { write24_le(0, v) }, 0, 3) }
suspend fun AsyncOutputStream.write32_le(v: Int): Unit = asyncFun { write(BYTES_TEMP.apply { write32_le(0, v) }, 0, 4) }
suspend fun AsyncOutputStream.write32_le(v: Long): Unit = asyncFun { write(BYTES_TEMP.apply { write32_le(0, v) }, 0, 4) }
suspend fun AsyncOutputStream.write64_le(v: Long): Unit = asyncFun { write(BYTES_TEMP.apply { write64_le(0, v) }, 0, 8) }
suspend fun AsyncOutputStream.writeF32_le(v: Float): Unit = asyncFun { write(BYTES_TEMP.apply { writeF32_le(0, v) }, 0, 4) }
suspend fun AsyncOutputStream.writeF64_le(v: Double): Unit = asyncFun { write(BYTES_TEMP.apply { writeF64_le(0, v) }, 0, 8) }
suspend fun AsyncOutputStream.write16_be(v: Int): Unit = asyncFun { write(BYTES_TEMP.apply { write16_be(0, v) }, 0, 2) }
suspend fun AsyncOutputStream.write24_be(v: Int): Unit = asyncFun { write(BYTES_TEMP.apply { write24_be(0, v) }, 0, 3) }
suspend fun AsyncOutputStream.write32_be(v: Int): Unit = asyncFun { write(BYTES_TEMP.apply { write32_be(0, v) }, 0, 4) }
suspend fun AsyncOutputStream.write32_be(v: Long): Unit = asyncFun { write(BYTES_TEMP.apply { write32_be(0, v) }, 0, 4) }
suspend fun AsyncOutputStream.write64_be(v: Long): Unit = asyncFun { write(BYTES_TEMP.apply { write64_be(0, v) }, 0, 8) }
suspend fun AsyncOutputStream.writeF32_be(v: Float): Unit = asyncFun { write(BYTES_TEMP.apply { writeF32_be(0, v) }, 0, 4) }
suspend fun AsyncOutputStream.writeF64_be(v: Double): Unit = asyncFun { write(BYTES_TEMP.apply { writeF64_be(0, v) }, 0, 8) }

fun SyncStream.toAsync(): AsyncStream = this.base.toAsync().toAsyncStream(this.position)
fun SyncStreamBase.toAsync(): AsyncStreamBase = SyncAsyncStreamBase(this)

fun SyncStream.toAsyncInWorker(): AsyncStream = this.base.toAsyncInWorker().toAsyncStream(this.position)
fun SyncStreamBase.toAsyncInWorker(): AsyncStreamBase = SyncAsyncStreamBaseInWorker(this)

class SyncAsyncStreamBase(val sync: SyncStreamBase) : AsyncStreamBase() {
	suspend override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int = sync.read(position, buffer, offset, len)
	suspend override fun write(position: Long, buffer: ByteArray, offset: Int, len: Int) = sync.write(position, buffer, offset, len)
	suspend override fun setLength(value: Long) = run { sync.length = value }
	suspend override fun getLength(): Long = sync.length
}

class SyncAsyncStreamBaseInWorker(val sync: SyncStreamBase) : AsyncStreamBase() {
	suspend override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int = executeInWorker { sync.read(position, buffer, offset, len) }
	suspend override fun write(position: Long, buffer: ByteArray, offset: Int, len: Int) = executeInWorker { sync.write(position, buffer, offset, len) }
	suspend override fun setLength(value: Long) = executeInWorker { sync.length = value }
	suspend override fun getLength(): Long = executeInWorker { sync.length }
}

suspend fun AsyncOutputStream.writeStream(source: AsyncInputStream): Unit = source.copyTo(this)

suspend fun AsyncOutputStream.writeFile(source: VfsFile): Unit = asyncFun {
	val out = this@writeFile
	source.openUse(VfsOpenMode.READ) {
		out.writeStream(this)
	}
}

suspend fun AsyncInputStream.copyTo(target: AsyncOutputStream): Unit = asyncFun {
	val chunk = BYTES_TEMP
	while (true) {
		val count = this.read(chunk)
		if (count <= 0) break
		target.write(chunk, 0, count)
	}
	Unit
}

suspend fun AsyncStream.writeToAlign(alignment: Int, value: Int = 0) = asyncFun {
	val nextPosition = getPosition().nextAlignedTo(alignment.toLong())
	val data = ByteArray((nextPosition - getPosition()).toInt())
	Arrays.fill(data, value.toByte())
	writeBytes(data)
}

suspend fun AsyncStream.skipToAlign(alignment: Int) = asyncFun {
	val nextPosition = getPosition().nextAlignedTo(alignment.toLong())
	readBytes((nextPosition - getPosition()).toInt())
}

suspend fun AsyncStream.truncate() = asyncFun { setLength(getPosition()) }

suspend fun AsyncOutputStream.writeCharArray_le(array: CharArray) = writeBytes(ByteArray(array.size * 2).apply { writeArray_le(0, array) })
suspend fun AsyncOutputStream.writeShortArray_le(array: ShortArray) = writeBytes(ByteArray(array.size * 2).apply { writeArray_le(0, array) })
suspend fun AsyncOutputStream.writeIntArray_le(array: IntArray) = writeBytes(ByteArray(array.size * 4).apply { writeArray_le(0, array) })
suspend fun AsyncOutputStream.writeLongArray_le(array: LongArray) = writeBytes(ByteArray(array.size * 8).apply { writeArray_le(0, array) })
suspend fun AsyncOutputStream.writeFloatArray_le(array: FloatArray) = writeBytes(ByteArray(array.size * 4).apply { writeArray_le(0, array) })
suspend fun AsyncOutputStream.writeDoubleArray_le(array: DoubleArray) = writeBytes(ByteArray(array.size * 8).apply { writeArray_le(0, array) })

suspend fun AsyncOutputStream.writeCharArray_be(array: CharArray) = writeBytes(ByteArray(array.size * 2).apply { writeArray_be(0, array) })
suspend fun AsyncOutputStream.writeShortArray_be(array: ShortArray) = writeBytes(ByteArray(array.size * 2).apply { writeArray_be(0, array) })
suspend fun AsyncOutputStream.writeIntArray_be(array: IntArray) = writeBytes(ByteArray(array.size * 4).apply { writeArray_be(0, array) })
suspend fun AsyncOutputStream.writeLongArray_be(array: LongArray) = writeBytes(ByteArray(array.size * 8).apply { writeArray_be(0, array) })
suspend fun AsyncOutputStream.writeFloatArray_be(array: FloatArray) = writeBytes(ByteArray(array.size * 4).apply { writeArray_be(0, array) })
suspend fun AsyncOutputStream.writeDoubleArray_be(array: DoubleArray) = writeBytes(ByteArray(array.size * 8).apply { writeArray_be(0, array) })