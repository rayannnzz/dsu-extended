package com.dsu.extended.compat

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class Ext4ImageTest {

    private val buildProp = """
        ro.build.version.sdk=34
        ro.build.version.release=14
        ro.product.cpu.abi=arm64-v8a
        ro.product.cpu.abilist=arm64-v8a,armeabi-v7a
        ro.vndk.version=34
        ro.treble.enabled=true
        ro.build.type=user
        ro.build.fingerprint=test/device/arm64:14/UQ1A/1:user/release-keys
        ro.build.version.security_patch=2026-09-01
    """.trimIndent()

    @Test
    fun readsBuildPropFromExt4ImageWithFourKilobyteBlocks() {
        val image = createExt4Image(blockSize = 4096)
        val props = withSource(image) { source ->
            val ext4 = Ext4Image(source)
            val info = ext4.open()
            assertTrue(info != null)
            assertTrue(info!!.rawSize > 0L)
            ext4.readBuildProps()
        }
        assertEquals("34", props["ro.build.version.sdk"])
        assertEquals("arm64-v8a", props["ro.product.cpu.abi"])
        assertEquals("user", props["ro.build.type"])
    }

    @Test
    fun readsBuildPropFromExt4ImageWithOneKilobyteBlocks() {
        val image = createExt4Image(blockSize = 1024)
        val props = withSource(image) { source ->
            Ext4Image(source).apply { open() }.readBuildProps()
        }
        assertEquals("34", props["ro.build.version.sdk"])
        assertEquals("arm64-v8a", props["ro.product.cpu.abi"])
    }

    @Test
    fun analyzerReadsExt4AndGzipImages() {
        val image = createExt4Image(blockSize = 4096)
        val ext4Facts = analyze(image, gzip = false)
        assertEquals(ImageFileSystem.EXT4, ext4Facts.fileSystem)
        assertEquals(34, ext4Facts.sdk)
        assertEquals("arm64-v8a", ext4Facts.abi)
        assertEquals("ext4", ext4Facts.propsSource)

        val gzip = File(image.parentFile, "system.img.gz")
        FileInputStream(image).use { input ->
            GZIPOutputStream(FileOutputStream(gzip)).use { output -> input.copyTo(output) }
        }
        val gzipFacts = analyze(gzip, gzip = true)
        assertEquals(ImageContainer.GZIP, gzipFacts.container)
        assertEquals(ImageFileSystem.EXT4, gzipFacts.fileSystem)
        assertEquals(34, gzipFacts.sdk)
        assertEquals("arm64-v8a", gzipFacts.abi)
    }

    @Test
    fun analyzerReadsSparseExt4WithoutDecodingWholeImage() {
        val image = createExt4Image(blockSize = 4096)
        val sparseFile = File(image.parentFile, "system.sparse.img")
        sparseFile.writeBytes(sparsify(image.readBytes(), blockSize = 4096, blocksPerChunk = 256))
        val counting = CountingSeekableSource(openSource(sparseFile))
        try {
            val facts = GsiImageAnalyzer().analyze(
                GsiImageInput(
                    displayName = sparseFile.name,
                    storedSize = sparseFile.length(),
                    declaredRawSize = image.length(),
                    seekable = counting,
                    streamFactory = { FileInputStream(sparseFile) },
                ),
                budget = ScanBudget(5_000L),
            )
            assertTrue(facts.sparse)
            assertEquals(ImageFileSystem.EXT4, facts.fileSystem)
            assertEquals("ext4", facts.propsSource)
            assertEquals(34, facts.sdk)
            assertEquals("arm64-v8a", facts.abi)
            assertEquals(image.length(), facts.rawSizeBytes)
            assertTrue(
                "ext4 metadata should be read without scanning the image, read=${counting.bytesRead}",
                counting.bytesRead < image.length() / 2,
            )
        } finally {
            counting.close()
        }
    }

    @Test
    fun analyzerStopsAtBudgetAndKeepsDeclaredSize() {
        val image = createExt4Image(blockSize = 4096)
        val gzip = File(image.parentFile, "budget.img.gz")
        FileInputStream(image).use { input ->
            GZIPOutputStream(FileOutputStream(gzip)).use { output -> input.copyTo(output) }
        }
        val declaredSize = image.length()
        val facts = withSource(gzip) { source ->
            GsiImageAnalyzer().analyze(
                GsiImageInput(
                    displayName = gzip.name,
                    storedSize = gzip.length(),
                    declaredRawSize = declaredSize,
                    seekable = source,
                    streamFactory = { FileInputStream(gzip) },
                ),
                budget = ScanBudget(0L),
            )
        }
        assertEquals(ImageContainer.GZIP, facts.container)
        assertEquals(declaredSize, facts.rawSizeBytes)
        assertFalse(facts.scanComplete)
        assertNull(facts.error)
    }

    private class CountingSeekableSource(
        private val delegate: SeekableSource,
    ) : SeekableSource {
        var bytesRead = 0L

        override val size: Long
            get() = delegate.size

        override fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            length: Int,
        ): Int {
            val read = delegate.readAt(offset, destination, destinationOffset, length)
            if (read > 0) bytesRead += read
            return read
        }

        override fun openStream(): InputStream = delegate.openStream()

        override fun close() = delegate.close()
    }

    private fun openSource(file: File): SeekableSource {
        val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        return ChannelSeekableSource(channel, channel)
    }

    private fun sparsify(image: ByteArray, blockSize: Int, blocksPerChunk: Int): ByteArray {
        val chunkBytes = blockSize * blocksPerChunk
        require(chunkBytes > 0 && image.size % chunkBytes == 0) {
            "Image size must align with the sparse chunk size"
        }
        val totalBlocks = image.size / blockSize
        val totalChunks = image.size / chunkBytes
        val out = ByteArrayOutputStream()
        out.write(le32(0xED26FF3A.toInt()))
        out.write(le16(1))
        out.write(le16(0))
        out.write(le16(28))
        out.write(le16(12))
        out.write(le32(blockSize))
        out.write(le32(totalBlocks))
        out.write(le32(totalChunks))
        out.write(le32(0))
        for (index in 0 until totalChunks) {
            out.write(le16(0xCAC1))
            out.write(le16(0))
            out.write(le32(blocksPerChunk))
            out.write(le32(chunkBytes))
            out.write(image, index * chunkBytes, chunkBytes)
        }
        return out.toByteArray()
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

    private fun analyze(file: File, gzip: Boolean): GsiFacts {
        return withSource(file) { source ->
            GsiImageAnalyzer().analyze(
                GsiImageInput(
                    displayName = file.name,
                    storedSize = file.length(),
                    declaredRawSize = if (gzip) {
                        File(file.absolutePath.removeSuffix(".gz")).length()
                    } else {
                        file.length()
                    },
                    seekable = source,
                    streamFactory = { FileInputStream(file) },
                ),
            )
        }
    }

    private fun <T> withSource(file: File, block: (SeekableSource) -> T): T {
        val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        val source = ChannelSeekableSource(channel, channel)
        return try {
            block(source)
        } finally {
            source.close()
        }
    }

    private fun createExt4Image(blockSize: Int): File {
        assumeTrue("mke2fs is required for this test", commandExists("mke2fs"))
        val directory = createTempDir("dsu-ext4")
        val root = File(directory, "root")
        root.mkdirs()
        File(root, "build.prop").writeText(buildProp)
        val image = File(directory, "system.img")
        val blocks = if (blockSize == 1024) 8192 else 2048
        val process = ProcessBuilder(
            "mke2fs",
            "-F",
            "-q",
            "-t",
            "ext4",
            "-b",
            blockSize.toString(),
            "-d",
            root.absolutePath,
            image.absolutePath,
            blocks.toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertTrue("mke2fs failed: $output", exit == 0)
        assertTrue(image.exists())
        return image
    }

    private fun createTempDir(prefix: String): File {
        val directory = kotlin.io.path.createTempDirectory(prefix).toFile()
        directory.deleteOnExit()
        return directory
    }

    private fun commandExists(command: String): Boolean {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", "command -v $command").start()
            process.waitFor() == 0
        }.getOrDefault(false)
    }
}
