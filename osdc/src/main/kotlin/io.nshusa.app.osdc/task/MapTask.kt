package io.nshusa.app.osdc.task

import io.nshusa.app.osdc.controller.Controller
import io.nshusa.app.osdc.util.Dialogue
import javafx.application.Platform
import javafx.concurrent.Task
import net.openrs.cache.Cache
import net.openrs.cache.FileStore
import net.openrs.cache.util.CompressionUtils
import net.openrs.cache.util.XTEAManager
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class MapTask(val cachePath: File) : Task<Boolean>() {

    override fun call(): Boolean {
        try {
            Cache(FileStore.open(cachePath)).use { cache ->
                val dumpDir = File("./dump/")

                if (!dumpDir.exists()) {
                    dumpDir.mkdirs()
                }

                val dir = File(dumpDir, "index4")

                if (!dir.exists()) {
                    dir.mkdirs()
                }

                val raf = RandomAccessFile(File(dumpDir, "map_index").toPath().toString(), "rw")

                var total = 0
                var end = 0L
                var mapCount = 0
                var landCount = 0

                raf.seek(2L)

                for (i in 0 until 256) {
                    for (j in 0 until 256) {
                        val var1 = (i shl 8) or j
                        val x = cache.getFileId(5, "m${i}_$j")
                        val y = cache.getFileId(5, "l${i}_$j")

                        if (x == -1 || y == -1) {
                            continue
                        }

                        raf.writeShort(var1)
                        raf.writeShort(x)
                        raf.writeShort(y)
                        total++
                    }
                }

                end = raf.filePointer
                raf.seek(0L)
                raf.writeShort(total)
                raf.seek(end)
                raf.close()

                for (i in 0 until Controller.MAX_REGION) {
                    val xteas = XTEAManager.lookup(i)

                    val x = i shr 8
                    val y = i and 0xFF

                    val map = cache.getFileId(5, "m${x}_$y")
                    val land = cache.getFileId(5, "l${x}_$y")

                    if (map != -1) {
                        try {
                            val container = cache.read(5, map)
                            val data = ByteArray(container.data.limit())
                            container.data.get(data)

                            FileOutputStream(File(dir, "$map.gz")).use { fos ->
                                fos.write(CompressionUtils.gzip(data))
                            }

                            mapCount++
                        } catch (ex: Exception) {

                        }
                    }

                    if (land != -1) {
                        try {
                            val container = cache.read(5, land, xteas)
                            val data = ByteArray(container.data.limit())
                            container.data.get(data)

                            FileOutputStream(File(dir, "$land.gz")).use { fos ->
                                fos.write(CompressionUtils.gzip(data))
                            }

                            landCount++
                        } catch (ex: Exception) {

                        }
                    }

                    val progress = (i + 1).toDouble() / Controller.MAX_REGION * 100

                    updateMessage(String.format("%.2f%s", progress, "%"))
                    updateProgress((i + 1).toDouble(), Controller.MAX_REGION.toDouble())

                }

            }
            return true
        } catch (ex: Exception) {
            Platform.runLater { Dialogue.showException("Error!", ex).show() }
        }
        return false
    }

}