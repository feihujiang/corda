package net.corda.node.services.persistence

import com.codahale.metrics.MetricRegistry
import com.google.common.annotations.VisibleForTesting
import com.google.common.hash.Hashing
import com.google.common.hash.HashingInputStream
import com.google.common.io.CountingInputStream
import net.corda.core.contracts.Attachment
import net.corda.core.createDirectory
import net.corda.core.crypto.SecureHash
import net.corda.core.div
import net.corda.core.extractZipFile
import net.corda.core.isDirectory
import net.corda.core.node.services.AttachmentStorage
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.loggerFor
import net.corda.node.services.api.AcceptsFileUpload
import net.corda.node.services.database.RequeryConfiguration
import net.corda.node.services.persistence.schemas.AttachmentEntity
import net.corda.node.services.persistence.schemas.Models
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.jar.JarInputStream
import javax.annotation.concurrent.ThreadSafe

/**
 * Stores attachments in H2 database.
 */
@ThreadSafe
class NodeAttachmentService(override var storePath: Path, dataSourceProperties: Properties, metrics: MetricRegistry) : AttachmentStorage, AcceptsFileUpload {
    private val log = loggerFor<NodeAttachmentService>()

    val configuration = RequeryConfiguration(dataSourceProperties)
    val session = configuration.sessionForModel(Models.PERSISTENCE)

    @VisibleForTesting
    var checkAttachmentsOnLoad = true

    private val attachmentCount = metrics.counter("Attachments")
    @Volatile override var automaticallyExtractAttachments = false

    init {
        require(storePath.isDirectory()) { "$storePath must be a directory" }

        session.withTransaction {
            attachmentCount.inc(session.count(AttachmentEntity::class).get().value().toLong())
        }
    }

    @CordaSerializable
    class HashMismatchException(val expected: SecureHash, val actual: SecureHash) : Exception() {
        override fun toString() = "File $expected hashed to $actual: corruption in attachment store?"
    }

    /**
     * Wraps a stream and hashes data as it is read: if the entire stream is consumed, then at the end the hash of
     * the read data is compared to the [expected] hash and [HashMismatchException] is thrown by [close] if they didn't
     * match. The goal of this is to detect cases where attachments in the store have been tampered with or corrupted
     * and no longer match their file name. It won't always work: if we read a zip for our own uses and skip around
     * inside it, we haven't read the whole file, so we can't check the hash. But when copying it over the network
     * this will provide an additional safety check against user error.
     */
    private class HashCheckingStream(val expected: SecureHash.SHA256,
                                     val expectedSize: Int,
                                     input: InputStream,
                                     private val counter: CountingInputStream = CountingInputStream(input),
                                     private val stream: HashingInputStream = HashingInputStream(Hashing.sha256(), counter)) : FilterInputStream(stream) {
        override fun close() {
            super.close()

            if (counter.count != expectedSize.toLong()) return

            val actual = SecureHash.SHA256(stream.hash().asBytes())
            if (actual != expected)
                throw HashMismatchException(expected, actual)
        }
    }

    private class AttachmentImpl(override val id: SecureHash,
                                 private val attachment: ByteArray,
                                 private val checkOnLoad: Boolean) : Attachment {
        override fun open(): InputStream {

            var stream = ByteArrayInputStream(attachment)

            // This is just an optional safety check. If it slows things down too much it can be disabled.
            if (id is SecureHash.SHA256 && checkOnLoad)
                return HashCheckingStream(id, attachment.size, stream)

            return stream
        }

        override fun equals(other: Any?) = other is Attachment && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    override fun openAttachment(id: SecureHash): Attachment? {
        val attachment = session.withTransaction {
            try {
                session.select(AttachmentEntity::class)
                        .where(AttachmentEntity.ATT_ID.eq(id))
                        .get()
                        .single()
            } catch (e: NoSuchElementException) {
                null
            }
        } ?: return null

        return AttachmentImpl(id, attachment.content, checkAttachmentsOnLoad)
    }

    // TODO: PLT-147: The attachment should be randomised to prevent brute force guessing and thus privacy leaks.
    override fun importAttachment(jar: InputStream): SecureHash {
        require(jar !is JarInputStream)

        // Read the file into RAM, hashing it to find the ID as we go. The attachment must fit into memory.
        // TODO: Switch to a two-phase insert so we can handle attachments larger than RAM.
        // To do this we must pipe stream into the database without knowing its hash, which we will learn only once
        // the insert/upload is complete. We can then query to see if it's a duplicate and if so, erase, and if not
        // set the hash field of the new attachment record.
        val hs = HashingInputStream(Hashing.sha256(), jar)
        val bytes = hs.readBytes()
        checkIsAValidJAR(ByteArrayInputStream(bytes))
        val id = SecureHash.SHA256(hs.hash().asBytes())

        val count = session.withTransaction {
            session.count(AttachmentEntity::class)
                    .where(AttachmentEntity.ATT_ID.eq(id))
                    .get().value()
        }

        if (count > 0) {
            throw FileAlreadyExistsException(id.toString())
        }

        session.withTransaction {
            val attachment = AttachmentEntity()
            attachment.attId = id
            attachment.content = bytes
            session.insert(attachment)
        }

        attachmentCount.inc()

        log.info("Stored new attachment $id")

        if (automaticallyExtractAttachments) {
            val extractTo = storePath / "$id.jar"
            try {
                extractTo.createDirectory()
                extractZipFile(ByteArrayInputStream(bytes), extractTo)
            } catch(e: FileAlreadyExistsException) {
                log.trace("Did not extract attachment jar to directory because it already exists")
            } catch(e: Exception) {
                log.error("Failed to extract attachment jar $id, ", e)
                // TODO: Delete the extractTo directory here.
            }
        }

        return id
    }

    private fun checkIsAValidJAR(stream: InputStream) {
        // Just iterate over the entries with verification enabled: should be good enough to catch mistakes.
        // Note that JarInputStream won't throw any kind of error at all if the file stream is in fact not
        // a ZIP! It'll just pretend it's an empty archive, which is kind of stupid but that's how it works.
        // So we have to check to ensure we found at least one item.
        val jar = JarInputStream(stream, true)
        var count = 0
        while (true) {
            val cursor = jar.nextJarEntry ?: break
            val entryPath = Paths.get(cursor.name)
            // Security check to stop zips trying to escape their rightful place.
            require(!entryPath.isAbsolute) { "Path $entryPath is absolute" }
            require(entryPath.normalize() == entryPath) { "Path $entryPath is not normalised" }
            require(!('\\' in cursor.name || cursor.name == "." || cursor.name == "..")) { "Bad character in $entryPath" }
            count++
        }
        require(count > 0) { "Stream is either empty or not a JAR/ZIP" }
    }

    // Implementations for AcceptsFileUpload
    override val dataTypePrefix = "attachment"
    override val acceptableFileExtensions = listOf(".jar", ".zip")
    override fun upload(file: InputStream) = importAttachment(file).toString()
}
