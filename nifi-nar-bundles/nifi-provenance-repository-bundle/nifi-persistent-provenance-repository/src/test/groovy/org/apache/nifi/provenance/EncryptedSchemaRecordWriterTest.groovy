package org.apache.nifi.provenance

import org.apache.nifi.flowfile.FlowFile
import org.apache.nifi.provenance.serialization.RecordReader
import org.apache.nifi.provenance.serialization.RecordWriter
import org.apache.nifi.provenance.toc.StandardTocReader
import org.apache.nifi.provenance.toc.StandardTocWriter
import org.apache.nifi.provenance.toc.TocReader
import org.apache.nifi.provenance.toc.TocUtil
import org.apache.nifi.provenance.toc.TocWriter
import org.apache.nifi.util.file.FileUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.encoders.Hex
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.security.Security
import java.util.concurrent.atomic.AtomicLong

import static org.apache.nifi.provenance.TestUtil.createFlowFile

@RunWith(JUnit4.class)
class EncryptedSchemaRecordWriterTest extends AbstractTestRecordReaderWriter {
    private static final Logger logger = LoggerFactory.getLogger(EncryptedSchemaRecordWriterTest.class)

    private static final String KEY_HEX_128 = "0123456789ABCDEFFEDCBA9876543210"
    private static final String KEY_HEX_256 = KEY_HEX_128 * 2
    private static final String KEY_HEX = isUnlimitedStrengthCryptoAvailable() ? KEY_HEX_256 : KEY_HEX_128
    private static final int IV_LENGTH = 16

    private static final String TRANSIT_URI = "nifi://unit-test"
    private static final String PROCESSOR_TYPE = "Mock Processor"
    private static final String COMPONENT_ID = "1234"

    private static final int UNCOMPRESSED_BLOCK_SIZE = 1024 * 32
    private static final int MAX_ATTRIBUTE_SIZE = 2048

    private static final AtomicLong idGenerator = new AtomicLong(0L)
    private File journalFile
    private File tocFile

    private static KeyProvider mockKeyProvider
    private static ProvenanceEventEncryptor mockEncryptor

    @ClassRule
    public static TemporaryFolder tempFolder = new TemporaryFolder()

    private static String ORIGINAL_LOG_LEVEL

    @BeforeClass
    static void setUpOnce() throws Exception {
        ORIGINAL_LOG_LEVEL = System.getProperty("org.slf4j.simpleLogger.log.org.apache.nifi.provenance")
        System.setProperty("org.slf4j.simpleLogger.log.org.apache.nifi.provenance", "DEBUG")

        Security.addProvider(new BouncyCastleProvider())

        logger.metaClass.methodMissing = { String name, args ->
            logger.info("[${name?.toUpperCase()}] ${(args as List).join(" ")}")
        }

        mockKeyProvider = [getKey: { String keyId ->
            logger.mock("Requesting key ID: ${keyId}")
            new SecretKeySpec(Hex.decode(KEY_HEX), "AES")
        }] as KeyProvider

        mockEncryptor = [encrypt: { ProvenanceEventRecord record ->
            logger.mock("Encrypting ${(record as StandardProvenanceEventRecord).getBestEventIdentifier()}")
            new EncryptedProvenanceEventRecord(new StandardProvenanceEventRecord.Builder())
        }] as ProvenanceEventEncryptor
    }

    @Before
    void setUp() throws Exception {
        journalFile = new File("target/storage/${UUID.randomUUID()}/testEventIdFirstSchemaRecordReaderWriter");
        tocFile = TocUtil.getTocFile(journalFile)
        idGenerator.set(0L)
    }

    @After
    void tearDown() throws Exception {
        FileUtils.deleteFile(journalFile.getParentFile(), true)
    }

    @AfterClass
    static void tearDownOnce() throws Exception {
        if (ORIGINAL_LOG_LEVEL) {
            System.setProperty("org.slf4j.simpleLogger.log.org.apache.nifi.provenance", ORIGINAL_LOG_LEVEL)
        }
    }

    private static boolean isUnlimitedStrengthCryptoAvailable() {
        Cipher.getMaxAllowedKeyLength("AES") > 128
    }

    private static
    final FlowFile buildFlowFile(Map attributes = [:], long id = idGenerator.getAndIncrement(), long fileSize = 3000L) {
        if (!attributes?.uuid) {
            attributes.uuid = UUID.randomUUID().toString()
        }
        createFlowFile(id, fileSize, attributes)
    }

    private
    static ProvenanceEventRecord buildEventRecord(FlowFile flowfile = buildFlowFile(), ProvenanceEventType eventType = ProvenanceEventType.RECEIVE, String transitUri = TRANSIT_URI, String componentId = COMPONENT_ID, String componentType = PROCESSOR_TYPE, long eventTime = System.currentTimeMillis()) {
        final ProvenanceEventBuilder builder = new StandardProvenanceEventRecord.Builder()
        builder.setEventTime(eventTime)
        builder.setEventType(eventType)
        builder.setTransitUri(transitUri)
        builder.fromFlowFile(flowfile)
        builder.setComponentId(componentId)
        builder.setComponentType(componentType)
        builder.build()
    }

    @Override
    protected RecordWriter createWriter(
            final File file,
            final TocWriter tocWriter, final boolean compressed, final int uncompressedBlockSize) throws IOException {
        return new EncryptedSchemaRecordWriter(file, idGenerator, tocWriter, compressed, uncompressedBlockSize, IdentifierLookup.EMPTY, mockKeyProvider, mockEncryptor, 1)
    }

    @Override
    protected RecordReader createReader(
            final InputStream inputStream,
            final String journalFilename, final TocReader tocReader, final int maxAttributeSize) throws IOException {
        return new EventIdFirstSchemaRecordReader(inputStream, journalFilename, tocReader, maxAttributeSize)
    }

    /**
     * Build a record and write it with a standard writer and the encrypted writer. Recover with the standard reader and the contents of the encrypted record should be unreadable.
     */
    @Test
    void testShouldWriteEncryptedRecord() {
        // Arrange
        final ProvenanceEventRecord record = buildEventRecord()
        logger.info("Built sample PER: ${record}")

        TocWriter tocWriter = new StandardTocWriter(tocFile, false, false)

        RecordWriter encryptedWriter = createWriter(journalFile, tocWriter, false, UNCOMPRESSED_BLOCK_SIZE)
        logger.info("Generated encrypted writer: ${encryptedWriter}")

        RecordWriter standardWriter = new EventIdFirstSchemaRecordWriter(journalFile, idGenerator, tocWriter, false, UNCOMPRESSED_BLOCK_SIZE, IdentifierLookup.EMPTY)
        logger.info("Generated standard writer: ${standardWriter}")

        // Act
        int standardRecordId = idGenerator.getAndIncrement()
        standardWriter.writeHeader(standardRecordId)
        standardWriter.writeRecord(record)
        logger.info("Wrote standard record ${standardRecordId} to journal")

        // TODO: Don't increment this time? 1 -> 3?
        int encryptedRecordId = idGenerator.getAndIncrement()
        encryptedWriter.writeHeader(encryptedRecordId)
        encryptedWriter.writeRecord(record)
        logger.info("Wrote encrypted record ${encryptedRecordId} to journal")
        
        // Assert
        TocReader tocReader = new StandardTocReader(tocFile)
        final FileInputStream fis = new FileInputStream(journalFile)
        final RecordReader reader = new EventIdFirstSchemaRecordReader(fis, journalFile.getName(), tocReader, MAX_ATTRIBUTE_SIZE)
        logger.info("Generated standard reader: ${reader}")

        ProvenanceEventRecord standardEvent = reader.nextRecord()
        assert standardEvent
        assert standardRecordId as long == standardEvent.getEventId()
        assert record.componentId == standardEvent.getComponentId()
        assert record.componentType == standardEvent.getComponentType()
        logger.info("Successfully read standard record: ${standardEvent}")

        ProvenanceEventRecord encryptedEvent = reader.nextRecord()
        assert encryptedEvent
        assert encryptedRecordId as long == encryptedEvent.getEventId()
        assert record.componentId == encryptedEvent.getComponentId()
        assert record.componentType == encryptedEvent.getComponentType()
        logger.info("Successfully read encrypted record: ${encryptedEvent}")

        assert !reader.nextRecord()
    }
}
