package toys.compy.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class CompyStorageTest {
    @Test
    fun removableIdentityAcceptsUnlabelledAndAgreeingCards() = withRoot { root ->
        CompyStorage.validateRemovableIdentity(root, CARD_ID)

        writeCardIdentity(root, LABEL, CARD_ID)
        CompyStorage.validateRemovableIdentity(root, CARD_ID)
    }

    @Test
    fun removableIdentityRejectsCopiedRenamedAndMultipleMarkers() = withRoot { root ->
        writeCardIdentity(root, LABEL, "fs:1111-2222")
        expectIOException { CompyStorage.validateRemovableIdentity(root, CARD_ID) }

        root.listFiles()!!.single().delete()
        writeCardIdentity(root, LABEL, CARD_ID, fileName = "CompySD9999.sdcard.json")
        expectIOException { CompyStorage.validateRemovableIdentity(root, CARD_ID) }

        writeCardIdentity(root, LABEL, CARD_ID)
        expectIOException { CompyStorage.validateRemovableIdentity(root, CARD_ID) }
    }

    @Test
    fun internalIdentityRequiresSchemaVersionSerialAndCanonicalId() = withRoot { root ->
        val identity = File(root, CompyStorageContract.INTERNAL_IDENTITY_FILE)
        identity.writeText(
            JSONObject()
                .put("format", CompyStorageContract.INTERNAL_IDENTITY_FORMAT)
                .put("format_ver", CompyStorageContract.INTERNAL_IDENTITY_FORMAT_VERSION)
                .put("storage_schema_ver", CompyStorageContract.STORAGE_SCHEMA_VERSION)
                .put("device_id", DEVICE_ID)
                .put("serial", "fixture-serial")
                .toString(),
        )
        assertEquals(DEVICE_ID, CompyStorage.readInternalDeviceId(identity))

        val original = JSONObject(identity.readText())
        val missingSchemaVersion = JSONObject(original.toString())
        missingSchemaVersion.remove("storage_schema_ver")
        identity.writeText(missingSchemaVersion.toString())
        expectIOException { CompyStorage.readInternalDeviceId(identity) }
        identity.writeText(JSONObject(original.toString()).put("serial", "").toString())
        expectIOException { CompyStorage.readInternalDeviceId(identity) }
        identity.writeText(JSONObject(original.toString()).put("device_id", DEVICE_ID.uppercase()).toString())
        expectIOException { CompyStorage.readInternalDeviceId(identity) }
    }

    @Test
    fun missingInternalIdentityCanBeAdoptedWithoutOverwritingExistingIdentity() = withRoot { root ->
        val compyDirectory = File(root, "Documents/compy")
        val identity = File(compyDirectory, CompyStorageContract.INTERNAL_IDENTITY_FILE)
        try {
            CompyStorage.readInternalDeviceId(identity)
            fail("Expected missing internal identity")
        } catch (error: MissingInternalIdentityException) {
            assertEquals(identity, error.identityFile)
        }

        val adopted = CompyStorage.adoptInternalStorage(compyDirectory, DEVICE_ID, "fixture-serial")

        assertEquals(DEVICE_ID, adopted.id)
        assertEquals(compyDirectory, adopted.compyDirectory)
        assertEquals(DEVICE_ID, CompyStorage.readInternalDeviceId(identity))
        assertFalse(compyDirectory.listFiles().orEmpty().any { it.name.startsWith(".incoming.") })

        expectIOException {
            CompyStorage.adoptInternalStorage(
                compyDirectory,
                "00000000-0000-0000-0000-000000000001",
                "replacement-serial",
            )
        }
        assertEquals(DEVICE_ID, CompyStorage.readInternalDeviceId(identity))
        assertTrue(identity.isFile)
    }

    private fun writeCardIdentity(
        root: File,
        label: String,
        cardId: String,
        fileName: String = "$label${CompyStorageContract.CARD_IDENTITY_FILE_SUFFIX}",
    ) {
        val check =
            MessageDigest.getInstance("SHA-256")
                .digest(label.toByteArray())
                .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
                .take(CompyStorageContract.LABEL_CHECK_LENGTH)
        File(root, fileName).writeText(
            JSONObject()
                .put("format", CompyStorageContract.CARD_IDENTITY_FORMAT)
                .put("format_ver", CompyStorageContract.CARD_IDENTITY_FORMAT_VERSION)
                .put("storage_schema_ver", CompyStorageContract.STORAGE_SCHEMA_VERSION)
                .put("label", label)
                .put("label_check", check)
                .put("card_id", cardId)
                .toString(),
        )
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
            // Expected.
        }
    }

    private fun withRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory("compy-storage-identity-test").toFile()
        try {
            test(root)
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private const val LABEL = "CompySD0013"
        private const val CARD_ID = "fs:7aff-7538"
        private const val DEVICE_ID = "b16674a2-e659-40b9-9884-4c96d604f4b3"
    }
}
