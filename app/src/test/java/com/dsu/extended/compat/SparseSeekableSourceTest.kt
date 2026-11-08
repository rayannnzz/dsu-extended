package com.dsu.extended.compat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SparseSeekableSourceTest {

    @Test
    fun mapsLogicalReadsAcrossChunkTypes() {
        val container = ByteArrayInputStream(buildSparse()).asSeekable()
        val sparse = SparseSeekableSource(container)
        try {
            assertEquals(16L, sparse.size)

            val header = sparse.readExactly(0L, 4)
            assertArrayEquals("ABCD".toByteArray(), header)

            val middle = ByteArray(12)
            assertEquals(12, sparse.readAt(2L, middle))
            assertArrayEquals(
                "CD".toByteArray() +
                    byteArrayOf(0x44, 0x33, 0x22, 0x11) +
                    ByteArray(4) +
                    "WX".toByteArray(),
                middle,
            )

            val streamed = sparse.openStream().readBytes()
            assertArrayEquals(expectedLogicalImage(), streamed)
        } finally {
            sparse.close()
        }
    }

    @Test
    fun doesNotExpandDontCareChunks() {
        val totalBlocks = 1_000_000_000L
        val blockSize = 4096
        val sparse = SparseSeekableSource(
            ByteArrayInputStream(buildDontCareSparse(totalBlocks, blockSize)).asSeekable(),
        )
        try {
            assertEquals(totalBlocks * blockSize, sparse.size)
            val tail = ByteArray(4)
            assertEquals(4, sparse.readAt(sparse.size - 4, tail))
            assertArrayEquals(ByteArray(4), tail)
        } finally {
            sparse.close()
        }
    }

    private fun expectedLogicalImage(): ByteArray =
        "ABCD".toByteArray() +
            byteArrayOf(0x44, 0x33, 0x22, 0x11) +
            ByteArray(4) +
            "WXYZ".toByteArray()

    private fun buildSparse(): ByteArray {
        val out = ByteArrayOutputStream()
        writeHeader(out, blockSize = 4, totalBlocks = 4, totalChunks = 4)

        writeChunk(out, type = 0xCAC1, chunkBlocks = 1, data = "ABCD".toByteArray())
        writeChunk(out, type = 0xCAC2, chunkBlocks = 1, data = le32(0x11223344))
        writeChunk(out, type = 0xCAC3, chunkBlocks = 1, data = ByteArray(0))
        writeChunk(out, type = 0xCAC1, chunkBlocks = 1, data = "WXYZ".toByteArray())
        return out.toByteArray()
    }

    private fun buildDontCareSparse(totalBlocks: Long, blockSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeHeader(out, blockSize = blockSize, totalBlocks = totalBlocks, totalChunks = 1)
        writeChunk(out, type = 0xCAC3, chunkBlocks = totalBlocks, data = ByteArray(0))
        return out.toByteArray()
    }

    private fun writeHeader(
        out: ByteArrayOutputStream,
        blockSize: Int,
        totalBlocks: Long,
        totalChunks: Long,
    ) {
        out.write(le32(0xED26FF3A.toInt()))
        out.write(le16(1))
        out.write(le16(0))
        out.write(le16(28))
        out.write(le16(12))
        out.write(le32(blockSize))
        out.write(le32(totalBlocks.toInt()))
        out.write(le32(totalChunks.toInt()))
        out.write(le32(0))
    }

    private fun writeChunk(
        out: ByteArrayOutputStream,
        type: Int,
        chunkBlocks: Long,
        data: ByteArray,
    ) {
        out.write(le16(type))
        out.write(le16(0))
        out.write(le32(chunkBlocks.toInt()))
        out.write(le32(data.size))
        out.write(data)
    }

    private fun ByteArrayInputStream.asSeekable(): SeekableSource {
        val bytes = readBytes()
        return object : SeekableSource {
            override val size: Long
                get() = bytes.size.toLong()

            override fun readAt(
                offset: Long,
                destination: ByteArray,
                destinationOffset: Int,
                length: Int,
            ): Int {
                if (offset < 0L || length <= 0 || offset >= bytes.size) return -1
                val count = minOf(length.toLong(), bytes.size - offset).toInt()
                bytes.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + count)
                return count
            }

            override fun openStream(): java.io.InputStream = ByteArrayInputStream(bytes)

            override fun close() = Unit
        }
    }

    private fun le16(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value shr 8).toByte())

    private fun le32(value: Int): ByteArray =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )
}
