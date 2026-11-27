package gg.aquatic.testimpact.gradle.internal

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Writes a file so a reader never sees a half-written one: the text goes to a sibling temp file, then moves over. */
internal object AtomicFile {
    fun write(file: Path, text: String) {
        val directory = file.toAbsolutePath().parent
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "testimpact-", ".tmp")
        try {
            Files.writeString(temp, text)
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
