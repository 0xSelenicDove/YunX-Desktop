package com.yunx.app.ui.jcef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class MacHelperTest {
    @Test
    fun `bundled helper installs with a valid signature and is not rewritten`() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        val directory = Files.createTempDirectory("yunx-helper-test").toFile()
        try {
            JcefNativesInstaller.installMacHelper(directory)
            val binary = directory.resolve("jcef Helper.app/Contents/MacOS/jcef Helper")
            assertTrue(binary.canExecute())
            val modified = binary.lastModified()
            JcefNativesInstaller.installMacHelper(directory)
            assertEquals(modified, binary.lastModified())
        } finally {
            directory.deleteRecursively()
        }
    }
}
