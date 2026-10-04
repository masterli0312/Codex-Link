package com.codex.quota.ui.feature.taskhistory

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Only public, fixed-name model archives are accepted; never extract paths outside the cache. */
internal object VoiceModelArchive {
    fun unpack(input: InputStream, destination: File, modelName: String) {
        val root = destination.canonicalFile
        root.mkdirs()
        var total = 0L
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 5000 && entry.name.startsWith("$modelName/"))
                val relative = entry.name.removePrefix("$modelName/")
                val file = File(root, relative).canonicalFile
                require(file == root || file.toPath().startsWith(root.toPath()))
                if (entry.isDirectory) file.mkdirs() else {
                    require(file != root)
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { out ->
                        val bytes = ByteArray(8192)
                        while (true) {
                            val n = zip.read(bytes)
                            if (n < 0) break
                            total += n
                            require(total <= 256L * 1024 * 1024)
                            out.write(bytes, 0, n)
                        }
                    }
                }
            }
        }
        require(File(root, "am/final.mdl").isFile && File(root, "conf/mfcc.conf").isFile)
    }
}
