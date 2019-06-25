package io.nshusa.app.osdc.controller

import com.google.gson.GsonBuilder
import io.nshusa.app.osdc.App
import io.nshusa.app.osdc.other.osrscd.net.CacheRequester
import io.nshusa.app.osdc.model.GithubTag
import io.nshusa.app.osdc.task.MapImageTask
import io.nshusa.app.osdc.task.MapTask
import io.nshusa.app.osdc.util.Dialogue
import javafx.application.Platform
import javafx.concurrent.Task
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.text.Text
import javafx.stage.*
import net.openrs.cache.Archive
import net.openrs.cache.Cache
import net.openrs.cache.FileStore
import net.openrs.cache.skeleton.Skeleton
import net.openrs.cache.sound.SoundEffect
import net.openrs.cache.sprite.Sprite
import net.openrs.cache.tools.AnimationTo317Dumper
import net.openrs.cache.track.Tracks
import net.openrs.cache.type.CacheIndex
import net.openrs.cache.type.identkits.IdentkitType
import net.openrs.cache.type.identkits.IdentkitTypeList
import net.openrs.cache.type.items.ItemType
import net.openrs.cache.type.items.ItemTypeList
import net.openrs.cache.type.npcs.NpcType
import net.openrs.cache.type.npcs.NpcTypeList
import net.openrs.cache.type.objects.ObjectType
import net.openrs.cache.type.objects.ObjectTypeList
import net.openrs.cache.type.overlays.OverlayType
import net.openrs.cache.type.overlays.OverlayTypeList
import net.openrs.cache.type.sequences.SequenceType
import net.openrs.cache.type.sequences.SequenceTypeList
import net.openrs.cache.type.spotanims.SpotAnimType
import net.openrs.cache.type.spotanims.SpotAnimTypeList
import net.openrs.cache.type.underlays.UnderlayType
import net.openrs.cache.type.underlays.UnderlayTypeList
import net.openrs.cache.type.varbits.VarBitType
import net.openrs.cache.type.varbits.VarBitTypeList
import net.openrs.cache.util.CompressionUtils
import net.openrs.cache.util.XTEAManager
import net.openrs.cache.util.reflect.ClassFieldPrinter
import net.openrs.cache.xtea.XteaKey
import net.openrs.util.ImageUtils
import java.awt.Color
import java.awt.Desktop
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.*
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

open class Controller : BaseController() {

    @FXML
    lateinit var title:Text

    override fun initialize(location:URL, resources:ResourceBundle?) {
        title.text = "OSDC [build ${App.osdcVersion}]"
    }

    private fun runTask(title: String, task: Task<*>) {
        val loader = FXMLLoader(App::class.java.getResource("/ui/TaskScene.fxml"))
        val root = loader.load<Parent>()

        val controller = loader.getController<TaskController>()

        controller.title.text = title
        controller.createTask(task)

        val stage = Stage()
        val scene = Scene(root)
        stage.scene = scene
        stage.isResizable = false
        stage.initStyle(StageStyle.UNDECORATED)
        stage.scene.stylesheets.add(App::class.java.getResource("/style.css").toExternalForm())
        stage.icons.add(Image(App::class.java.getResourceAsStream("/icons/icon.png")))

        val screenWidth = Screen.getPrimary().visualBounds.width
        val screenHeight = Screen.getPrimary().visualBounds.height

        var x = Math.random() * screenWidth
        var y = Math.random() * screenHeight

        stage.show()

        if (x > (screenWidth - stage.width)) {
            x = screenWidth - stage.width
        }

        if (y > (screenHeight - stage.height)) {
            y = screenHeight - stage.height
        }

        stage.x = x
        stage.y = y
    }

    @FXML
    private fun dumpAnimations() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/index2/")

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val task = object:Task<Boolean>() {
            override fun call(): Boolean {
                try {
                    Cache(FileStore.open(cachePath)).use { cache ->
                        val skeletonTable = cache.getReferenceTable(CacheIndex.SKELETONS)

                        val skeletons = arrayOfNulls<Array<Skeleton?>>(skeletonTable.capacity())

                        for (mainSkeletonId in 0 until skeletonTable.capacity()) {

                            if (skeletonTable.getEntry(mainSkeletonId) == null) {
                                continue
                            }

                            val bos = ByteArrayOutputStream()

                            val archive = Archive.decode(cache.read(CacheIndex.SKELETONS, mainSkeletonId).data, skeletonTable.getEntry(mainSkeletonId).size()) ?: continue

                            DataOutputStream(bos).use { dos ->
                                val subSkeletonCount = archive.size()
                                skeletons[mainSkeletonId] = arrayOfNulls(subSkeletonCount)
                                AnimationTo317Dumper.headerPacked = false
                                for (subSkeletonId in 0 until subSkeletonCount) {
                                    AnimationTo317Dumper.readNext(cache, dos, archive, mainSkeletonId, subSkeletonId, skeletons)
                                }
                            }

                            FileOutputStream(File(dir, "$mainSkeletonId.gz")).use { fos ->
                                fos.write(CompressionUtils.gzip(bos.toByteArray()))
                            }

                            val progress = (mainSkeletonId + 1).toDouble() / skeletonTable.capacity() * 100

                            updateMessage(String.format("%.2f%s", progress, "%"))
                            updateProgress((mainSkeletonId + 1).toDouble(), skeletonTable.capacity().toDouble())

                        }

                    }

                    return true
                } catch (ex: Exception) {
                    Platform.runLater {
                        Dialogue.showException("Exception", ex).show()
                    }
                }

                return false
            }

        }

        runTask("Animation Converter", task)

    }

    @FXML
    private fun dumpItemDefFields() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val input = Dialogue.showInput("Enter a prefix: (optional)").showAndWait()

        val task = object: Task<Boolean>() {
            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val list = ItemTypeList()
                    list.initialize(cache)

                    val fieldPrinter = ClassFieldPrinter()
                    fieldPrinter.setDefaultObject(ItemType(0))

                    var count = 0

                    val size = list.size()

                    for (i in 0 until size) {
                        val def = list.list(i) ?: continue

                        def.name ?: continue

                        try {
                            fieldPrinter.printFields(def, input.orElse(""))
                            count++
                        } catch (ex: Exception) {

                        }

                        val progress = (i + 1).toDouble() / size * 100

                        updateMessage(String.format("%.2f%s", progress, "%"))
                        updateProgress((i + 1).toDouble(), size.toDouble())

                    }

                    FileWriter(File(dir, "itemdef_fields.txt")).use { writer ->
                        writer.write(fieldPrinter.builder.toString())
                    }

                }
                return true
            }
        }

        runTask("Item Definition Field Dumper", task)

    }

    @FXML
    private fun dumpNpcDefFields() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val input = Dialogue.showInput("Enter a prefix: (optional)").showAndWait()

        val task = object: Task<Boolean>() {
            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val list = NpcTypeList()
                    list.initialize(cache)

                    val fieldPrinter = ClassFieldPrinter()
                    fieldPrinter.setDefaultObject(NpcType(0))

                    var count = 0

                    val size = list.size()

                    for (i in 0 until size) {
                        val def = list.list(i) ?: continue

                        def.name ?: continue

                        try {
                            fieldPrinter.printFields(def, input.orElse(""))
                            count++
                        } catch (ex: Exception) {

                        }

                        val progress = (i + 1).toDouble() / size * 100

                        updateMessage(String.format("%.2f%s", progress, "%"))
                        updateProgress((i + 1).toDouble(), size.toDouble())

                    }

                    FileWriter(File(dir, "npcdef_fields.txt")).use { writer ->
                        writer.write(fieldPrinter.builder.toString())
                    }

                }
                return true
            }
        }

        runTask("Npc Definition Field Dumper", task)

    }

    @FXML
    private fun dumpObjectDefFields() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val input = Dialogue.showInput("Enter a prefix: (optional)").showAndWait()

        val task = object: Task<Boolean>() {
            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val list = ObjectTypeList()
                    list.initialize(cache)

                    val fieldPrinter = ClassFieldPrinter()
                    fieldPrinter.setDefaultObject(ObjectType(0))

                    var count = 0

                    val size = list.size()

                    for (i in 0 until size) {
                        val def = list.list(i) ?: continue

                        def.name ?: continue

                        try {
                            fieldPrinter.printFields(def, input.orElse(""))
                            count++
                        } catch (ex: Exception) {

                        }

                        val progress = (i + 1).toDouble() / size * 100

                        updateMessage(String.format("%.2f%s", progress, "%"))
                        updateProgress((i + 1).toDouble(), size.toDouble())

                    }

                    FileWriter(File(dir, "objdef_fields.txt")).use { writer ->
                        writer.write(fieldPrinter.builder.toString())
                    }

                }
                return true
            }
        }

        runTask("Object Definition Field Dumper", task)

    }

    @FXML
    private fun dumpAnimFields() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val input = Dialogue.showInput("Enter a prefix: (optional)").showAndWait()

        val task = object: Task<Boolean>() {
            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val list = SequenceTypeList()
                    list.initialize(cache)

                    val fieldPrinter = ClassFieldPrinter()
                    fieldPrinter.setDefaultObject(SequenceType(0))

                    var count = 0

                    val size = list.size()

                    for (i in 0 until size) {
                        val def = list.list(i) ?: continue

                        try {
                            fieldPrinter.printFields(def, input.orElse(""))
                            count++
                        } catch (ex: Exception) {

                        }

                        val progress = (i + 1).toDouble() / size * 100

                        updateMessage(String.format("%.2f%s", progress, "%"))
                        updateProgress((i + 1).toDouble(), size.toDouble())

                    }

                    FileWriter(File(dir, "anim_fields.txt")).use { writer ->
                        writer.write(fieldPrinter.builder.toString())
                    }

                }
                return true
            }
        }

        runTask("Anim Field Dumper", task)

    }

    @FXML
    private fun dumpGfxFields() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val input = Dialogue.showInput("Enter a prefix: (optional)").showAndWait()

        val task = object: Task<Boolean>() {
            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val list = SpotAnimTypeList()
                    list.initialize(cache)

                    val fieldPrinter = ClassFieldPrinter()
                    fieldPrinter.setDefaultObject(SpotAnimType(0))

                    var count = 0

                    val size = list.size()

                    for (i in 0 until size) {
                        val def = list.list(i) ?: continue

                        try {
                            fieldPrinter.printFields(def, input.orElse(""))
                            count++
                        } catch (ex: Exception) {

                        }

                        val progress = (i + 1).toDouble() / size * 100

                        updateMessage(String.format("%.2f%s", progress, "%"))
                        updateProgress((i + 1).toDouble(), size.toDouble())

                    }

                    FileWriter(File(dir, "gfx_fields.txt")).use { writer ->
                        writer.write(fieldPrinter.builder.toString())
                    }

                }
                return true
            }
        }

        runTask("Gfx Field Dumper", task)

    }

    @FXML
    private fun dumpVarbitFields() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val input = Dialogue.showInput("Enter a prefix: (optional)").showAndWait()

        val task = object: Task<Boolean>() {
            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val list = VarBitTypeList()
                    list.initialize(cache)

                    val fieldPrinter = ClassFieldPrinter()
                    fieldPrinter.setDefaultObject(VarBitType(0))

                    var count = 0

                    val size = list.size()

                    for (i in 0 until size) {
                        val def = list.list(i) ?: continue

                        try {
                            fieldPrinter.printFields(def, input.orElse(""))
                            count++
                        } catch (ex: Exception) {

                        }

                        val progress = (i + 1).toDouble() / size * 100

                        updateMessage(String.format("%.2f%s", progress, "%"))
                        updateProgress((i + 1).toDouble(), size.toDouble())

                    }

                    FileWriter(File(dir, "varbit_fields.txt")).use { writer ->
                        writer.write(fieldPrinter.builder.toString())
                    }

                }
                return true
            }
        }

        runTask("Varbit Field Dumper", task)

    }

    @FXML
    private fun dumpIdkFields() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val input = Dialogue.showInput("Enter a prefix: (optional)").showAndWait()

        val task = object: Task<Boolean>() {
            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val list = IdentkitTypeList()
                    list.initialize(cache)

                    val fieldPrinter = ClassFieldPrinter()
                    fieldPrinter.setDefaultObject(IdentkitType(0))

                    var count = 0

                    val size = list.size()

                    for (i in 0 until size) {
                        val def = list.list(i) ?: continue

                        try {
                            fieldPrinter.printFields(def, input.orElse(""))
                            count++
                        } catch (ex: Exception) {

                        }

                        val progress = (i + 1).toDouble() / size * 100

                        updateMessage(String.format("%.2f%s", progress, "%"))
                        updateProgress((i + 1).toDouble(), size.toDouble())

                    }

                    FileWriter(File(dir, "idk_fields.txt")).use { writer ->
                        writer.write(fieldPrinter.builder.toString())
                    }

                }
                return true
            }
        }

        runTask("IDK Field Dumper", task)

    }

    @FXML
    private fun dumpFloFields() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val input = Dialogue.showInput("Enter a prefix: (optional)").showAndWait()

        val task = object: Task<Boolean>() {
            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val underlays = UnderlayTypeList()
                    underlays.initialize(cache)

                    val overlays = OverlayTypeList()
                    overlays.initialize(cache)


                    val underlayPrinter = ClassFieldPrinter()
                    underlayPrinter.setDefaultObject(UnderlayType(0))

                    val overlayPrinter = ClassFieldPrinter()
                    overlayPrinter.setDefaultObject(OverlayType(0))

                    var count = 0

                    val totalCount = underlays.size() + overlays.size()

                    for (i in 0 until underlays.size()) {
                        val def = underlays.list(i) ?: continue

                        try {
                            underlayPrinter.printFields(def, input.orElse(""))
                        } catch (ex: Exception) {

                        }

                        val progress = (count + 1).toDouble() / totalCount * 100

                        updateMessage(String.format("%.2f%s", progress, "%"))
                        updateProgress((count + 1).toDouble(), totalCount.toDouble())

                        count++
                    }

                    for (i in 0 until overlays.size()) {
                        val def = overlays.list(i) ?: continue

                        try {
                            overlayPrinter.printFields(def, input.orElse(""))
                        } catch (ex: Exception) {

                        }

                        val progress = (count + 1).toDouble() / totalCount * 100

                        updateMessage(String.format("%.2f%s", progress, "%"))
                        updateProgress((count + 1).toDouble(), totalCount.toDouble())

                        count++

                    }

                    FileWriter(File(dir, "underlay_fields.txt")).use { writer ->
                        writer.write(underlayPrinter.builder.toString())
                    }

                    FileWriter(File(dir, "overlay_fields.txt")).use { writer ->
                        writer.write(overlayPrinter.builder.toString())
                    }

                }
                return true
            }
        }

        runTask("Flo Field Dumper", task)

    }

    @FXML
    private fun scrapXTEAS() {
        val outputDir = File("./dump/xteas")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val task = object:Task<Boolean>() {
            override fun call(): Boolean {
                try {
                    val gson = GsonBuilder().setPrettyPrinting().create()
                    val xteaUrl = "https://scape.tools/api/osrs/xteas"

                    val connection = URL(xteaUrl).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows; U; Windows NT 6.1; en-GB;     rv:1.9.2.13) Gecko/20101203 Firefox/3.6.13 (.NET CLR 3.5.30729)")
                    connection.connectTimeout = 3000

                    if (connection.responseCode != 200) {
                        Platform.runLater {
                            Dialogue.showWarning("Failed to retrieve xteas from $xteaUrl").show()
                        }
                        return false
                    }

                    BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        val xteas = gson.fromJson(reader, Array<XteaKey>::class.java)
                        val json = gson.toJson(xteas)
                        PrintWriter(FileWriter(File(outputDir, "xteas.json"))).use { writer ->
                            writer.write(json)
                            val progress = 100.0 / 1
                            updateMessage(String.format("%.2f%s", progress, "%"))
                            updateProgress(100.0, 100.0)
                        }
                    }

                } catch (ex: IOException) {
                    Platform.runLater {
                        Dialogue.showException("Exception", ex).show()
                    }
                }
                return true
            }
        }

        runTask("XTEA Fetcher", task)

    }

    @FXML
    private fun dumpSoundEffects() {
        if (!checkForCacheDirectory()) {
            return
        }

        val outputDir = File("./dump/sounds/")

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val task = object:Task<Boolean>() {
            override fun call(): Boolean {

                Cache(FileStore.open(cachePath?.toPath().toString())).use { cache ->

                    val table = cache.getReferenceTable(CacheIndex.SOUND_EFFECT)

                    for (i in 0 until table.capacity()) {

                        try {
                            val soundEffect = SoundEffect.decode(cache, i) ?: continue

                            val data = soundEffect.mix()

                            val audioFormat = AudioFormat(22_050f, 8, 1, true, false)

                            val ais = AudioInputStream(ByteArrayInputStream(data), audioFormat, data.size.toLong())

                            val bos = ByteArrayOutputStream()

                            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, bos)

                            FileOutputStream(File(outputDir, "$i.wav")).use { fos ->
                                fos.write(bos.toByteArray())

                                val progress = (i + 1).toDouble() / table.capacity() * 100

                                updateMessage(String.format("%.2f%s", progress, "%"))
                                updateProgress((i + 1).toDouble(), table.capacity().toDouble())

                            }
                        } catch (ex: Exception) {
                            continue
                        }

                    }

                }

                return true
            }

        }

        runTask("Sound Effect Exporter", task)

    }

    @FXML
    private fun dumpIdk() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val task = object:Task<Boolean>() {
            override fun call(): Boolean {

                Cache(FileStore.open(cachePath)).use { cache ->

                    val list = IdentkitTypeList()
                    list.initialize(cache)

                    DataOutputStream(FileOutputStream(File(dir, "idk.dat"))).use { dos ->
                        dos.writeShort(list.size())

                        for (i in 0 until list.size()) {
                            val def = list.list(i)

                            def.encode(dos)

                            val progress = (i + 1).toDouble() / list.size() * 100

                            updateMessage(String.format("%.2f%s", progress, "%"))
                            updateProgress((i + 1).toDouble(), list.size().toDouble())
                        }
                    }

                }

                return true
            }
        }

        runTask("IDK Converter", task)


    }

    @FXML
    private fun dumpItemDefs() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val task = object:Task<Boolean>() {
            override fun call(): Boolean {
                try {
                    Cache(FileStore.open(cachePath)).use { cache ->
                        val list = ItemTypeList()
                        list.initialize(cache)

                        val dat = DataOutputStream(FileOutputStream(File(dir, "obj.dat")))
                        val idx = DataOutputStream(FileOutputStream(File(dir, "obj.idx")))

                        idx.writeShort(list.size())
                        dat.writeShort(list.size())

                        for (i in 0 until list.size()) {
                            val def = list.list(i)

                            val start = dat.size()

                            def?.encode(dat)
                            dat.writeByte(0)

                            val end = dat.size()

                            idx.writeShort(end - start)

                            val progress = (i + 1).toDouble() / list.size() * 100

                            updateMessage(String.format("%.2f%s", progress, "%"))
                            updateProgress((i + 1).toDouble(), list.size().toDouble())
                        }

                        idx.close()
                        dat.close()

                    }
                } catch (ex: Exception) {
                    Platform.runLater {
                        Dialogue.showException("Error", ex)
                    }
                }
                return true
            }
        }

        runTask("Item Definition Converter", task)
    }

    @FXML
    private fun dumpNpcDefs() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val task = object:Task<Boolean>() {
            override fun call(): Boolean {
                try {
                    Cache(FileStore.open(cachePath)).use { cache ->

                        val list = NpcTypeList()
                        list.initialize(cache)

                        val dat = DataOutputStream(FileOutputStream(File(dir, "npc.dat")))
                        val idx = DataOutputStream(FileOutputStream(File(dir, "npc.idx")))

                        idx.writeShort(list.size())
                        dat.writeShort(list.size())

                        for (i in 0 until list.size()) {
                            val def = list.list(i)

                            val start = dat.size()

                            def?.encode(dat)
                            dat.writeByte(0)

                            val end = dat.size()

                            idx.writeShort(end - start)

                            val progress = (i + 1).toDouble() / list.size() * 100

                            updateMessage(String.format("%.2f%s", progress, "%"))
                            updateProgress((i + 1).toDouble(), list.size().toDouble())

                        }

                        dat.close()
                        idx.close()

                    }
                } catch (ex: Exception) {
                    Platform.runLater {
                        Dialogue.showException("Error!", ex)
                    }
                }
                return true
            }
        }

        runTask("Npc Definition Converter", task)

    }

    @FXML
    private fun dumpObjectDefs() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val task = object:Task<Boolean>() {
            override fun call(): Boolean {

                Cache(FileStore.open(cachePath)).use { cache ->
                    val list = ObjectTypeList()
                    list.initialize(cache)

                    val dos = DataOutputStream(FileOutputStream(File(dir, "loc.dat")))
                    val idx = DataOutputStream(FileOutputStream(File(dir, "loc.idx")))

                    dos.writeShort(list.size())
                    idx.writeShort(list.size())

                    for (i in 0 until list.size()) {
                        val def = list.list(i)

                        val start = dos.size()

                        def?.encode(dos)
                        dos.writeByte(0)

                        val end = dos.size()

                        idx.writeShort(end - start)

                        val progress = (i + 1).toDouble() / list.size() * 100

                        updateMessage(String.format("%.2f%s", progress, "%"))
                        updateProgress((i + 1).toDouble(), list.size().toDouble())
                    }

                    dos.close()
                    idx.close()

                }

                return true
            }

        }

        runTask("Object Definition Converter", task)

    }

    @FXML
    private fun dumpAnimationDefs() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val task = object:Task<Boolean>() {
            override fun call(): Boolean {
                try {
                    Cache(FileStore.open(cachePath)).use { cache ->
                        val list = SequenceTypeList ()
                        list.initialize(cache)

                        DataOutputStream(FileOutputStream(File(dir, "seq.dat"))).use { dos ->

                            dos.writeShort(list.size())

                            for (i in 0 until list.size()) {
                                val anim = list.list(i)

                                anim?.encode(dos)

                                dos.writeByte(0)

                                val progress = (i + 1).toDouble() / list.size() * 100

                                updateMessage(String.format("%.2f%s", progress, "%"))
                                updateProgress((i + 1).toDouble(), list.size().toDouble())
                            }
                        }
                    }

                } catch (ex: Exception) {
                    Platform.runLater {
                        Dialogue.showException("Error", ex)
                    }
                }
                return true
            }
        }

        runTask("Animation Definition Converter", task)

    }

    @FXML
    private fun dumpGraphicDefs() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val task = object:Task<Boolean>() {
            override fun call(): Boolean {
                try {
                    Cache(FileStore.open(cachePath)).use { cache ->
                        val list = SpotAnimTypeList()
                        list.initialize(cache)

                        DataOutputStream(FileOutputStream(File(dir, "spotanim.dat"))).use { dos ->
                            dos.writeShort(list.size())

                            for (i in 0 until list.size()) {
                                val gfx = list.list(i) ?: SpotAnimType(0)

                                gfx.encode(dos)

                                val progress = (i + 1).toDouble() / list.size() * 100

                                updateMessage(String.format("%.2f%s", progress, "%"))
                                updateProgress((i + 1).toDouble(), list.size().toDouble())
                            }
                        }
                    }
                } catch (ex: Exception) {
                    Platform.runLater {
                        Dialogue.showException("Error", ex)
                    }
                }
                return true
            }
        }

        runTask("GFX Converter", task)

    }

    @FXML
    private fun dumpVarbitDefs() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val task = object:Task<Boolean>() {

            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val list = VarBitTypeList()
                    list.initialize(cache)

                    DataOutputStream(FileOutputStream(File(dir, "varbit.dat"))).use { dos ->

                        dos.writeShort(list.size())

                        for (i in 0 until list.size()) {

                            val varbit = list.list(i)

                            if (varbit != null) {
                                varbit.encode(dos)
                            } else {
                                dos.writeByte(0)
                            }

                            val progress = (i + 1).toDouble() / list.size() * 100
                            updateMessage(String.format("%.2f%s", progress, "%"))
                            updateProgress((i + 1).toDouble(), list.size().toDouble())
                        }
                    }

                }
                return true
            }

        }

        runTask("Varbit Converter", task)

    }

    @FXML
    private fun dumpFloorDefs() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val task = object:Task<Boolean>() {

            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val underlays = UnderlayTypeList()
                    val overlays = OverlayTypeList()

                    underlays.initialize(cache)
                    overlays.initialize(cache)

                    val totalCount = underlays.size() + overlays.size()

                    DataOutputStream(FileOutputStream(File(dir, "flo.dat"))).use { dos ->

                        var count = 0

                        dos.writeShort(underlays.size())

                        for (i in 0 until underlays.size()) {
                            val underlay = underlays.list(i)

                            if (underlay != null) {
                                underlay.encode(dos)
                            } else {
                                dos.writeByte(0)
                            }

                            val progress = (count + 1).toDouble() / totalCount * 100

                            updateMessage(String.format("%.2f%s", progress, "%"))
                            updateProgress((count + 1).toDouble(), totalCount.toDouble())

                            count++
                        }

                        dos.writeShort(overlays.size())

                        for (i in 0 until overlays.size()) {
                            val overlay = overlays.list(i)

                            if (overlay != null) {
                                overlay.encode(dos)
                            } else {
                                dos.writeByte(0)
                            }

                            val progress = (count + 1).toDouble() / totalCount * 100

                            updateMessage(String.format("%.2f%s", progress, "%"))
                            updateProgress((count + 1).toDouble(), totalCount.toDouble())

                            count++
                        }

                    }

                }
                return true
            }

        }

        runTask("Overlay and Underlay Converter", task)

    }

    @FXML
    private fun dumpModels() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/index1")

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val task = object:Task<Boolean>() {

            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val table = cache.getReferenceTable(CacheIndex.MODELS)

                    for (i in 0 until table.capacity()) {
                        table.getEntry(i) ?: continue

                        val container = cache.read(CacheIndex.MODELS, i)

                        val bytes = ByteArray(container.data.limit())
                        container.data.get(bytes)

                        DataOutputStream(FileOutputStream(File(dir, "$i.gz"))).use { dos ->
                            dos.write(CompressionUtils.gzip(bytes))
                        }


                        val progress = (i + 1).toDouble() / table.capacity() * 100

                        updateMessage(String.format("%.2f%s", progress, "%"))
                        updateProgress((i + 1).toDouble(), table.capacity().toDouble())

                    }

                }
                return true
            }

        }

        runTask("Model Exporter", task)


    }

    @FXML
    private fun dumpMidis() {
        if (!checkForCacheDirectory()) {
            return
        }

        val outputDir = File("./dump/index3")

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val task = object:Task<Boolean>() {
            override fun call(): Boolean {

                try {

                    Cache(FileStore.open(cachePath)).use { cache ->
                        Tracks.initialize(cache)

                        for (i in 0 until Tracks.getTrack1Count()) {
                            val track = Tracks.getTrack1(i) ?: continue

                            DataOutputStream(FileOutputStream(File(outputDir, "$i.gz"))).use { dos ->
                                dos.write(CompressionUtils.gzip(track.decoded))
                            }

                            val progress = (i + 1).toDouble() / Tracks.getTrack1Count() * 100

                            updateMessage(String.format("%.2f%s", progress, "%"))
                            updateProgress((i + 1).toDouble(), Tracks.getTrack1Count().toDouble())

                        }

                    }

                } catch (ex: Exception) {
                    Platform.runLater {
                        Dialogue.showException("Error!", ex).show()
                    }
                }

                return true
            }
        }

        runTask("Midi Converter", task)

    }

    @FXML
    private fun dumpItemList() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val task = object:Task<Boolean>() {
            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val list = ItemTypeList()
                    list.initialize(cache)

                    PrintWriter(File(dir, "item_list.txt")).use { it ->
                        for (i in 0 until list.size()) {

                            val type = list.list(i) ?: continue

                            if (type.name.equals("null", true)) {
                                continue
                            }

                            it.println("$i: ${type.name}")

                            val progress = (i + 1).toDouble() / list.size() * 100

                            updateMessage(String.format("%.2f%s", progress, "%"))
                            updateProgress((i + 1).toDouble(), list.size().toDouble())

                        }
                    }
                    return true
                }
            }
        }

        runTask("Item List Task", task)


    }

    @FXML
    private fun dumpNpcList() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val task = object:Task<Boolean>() {
            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val list = NpcTypeList()
                    list.initialize(cache)

                    PrintWriter(File(dir, "npc_list.txt")).use { it ->
                        for (i in 0 until list.size()) {

                            val type = list.list(i) ?: continue

                            if (type.name.equals("null", true)) {
                                continue
                            }

                            it.println("$i: ${type.name}")

                            val progress = (i + 1).toDouble() / list.size() * 100

                            updateMessage(String.format("%.2f%s", progress, "%"))
                            updateProgress((i + 1).toDouble(), list.size().toDouble())

                        }
                    }
                    return true
                }
            }
        }

        runTask("Npc List Task", task)

    }

    @FXML
    private fun dumpObjectList() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val task = object:Task<Boolean>() {
            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val list = ObjectTypeList()
                    list.initialize(cache)

                    PrintWriter(File(dir, "object_list.txt")).use { it ->
                        for (i in 0 until list.size()) {

                            val type = list.list(i) ?: continue

                            if (type.name.equals("null", true)) {
                                continue
                            }

                            it.println("$i: ${type.name}")

                            val progress = (i + 1).toDouble() / list.size() * 100

                            updateMessage(String.format("%.2f%s", progress, "%"))
                            updateProgress((i + 1).toDouble(), list.size().toDouble())

                        }
                    }
                    return true
                }
            }
        }

        runTask("Object List Task", task)

    }


    @FXML
    private fun dumpSprites() {
        if (!checkForCacheDirectory()) {
            return
        }

        val dir = File("./dump/sprites/")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val task = object:Task<Boolean>() {
            override fun call(): Boolean {
                Cache(FileStore.open(cachePath)).use { cache ->
                    val table = cache.getReferenceTable(8)

                    for (i in 0 until table.capacity()) {
                        try {
                            table.getEntry(i) ?: continue

                            val container = cache.read(8, i)

                            val sprite = Sprite.decode(container.data)

                            for (frame in 0 until sprite.size()) {
                                try {
                                    val file = File(dir, "${i}_$frame.png")

                                    val bimage = ImageUtils.createColoredBackground(ImageUtils.makeColorTransparent(sprite.getFrame(frame), Color.WHITE), Color(0xFF00FF, false))

                                    ImageIO.write(bimage, "png", file)
                                } catch (ex: Exception) {
                                    continue
                                }
                            }

                        } catch (ex: Exception) {
                            continue
                        }

                        val progress = (i + 1).toDouble() / table.capacity() * 100

                        updateMessage(String.format("%.2f%s", progress, "%"))
                        updateProgress((i + 1).toDouble(), table.capacity().toDouble())

                        val container = cache.read(10, cache.getFileId(10, "title.jpg"))
                        val bytes = ByteArray(container.data.capacity())
                        container.data.get(bytes)

                        Files.write(File(dir, "title.jpg").toPath(), bytes)

                    }

                    return true
                }
            }
        }

        runTask("Sprite Exporter", task)

    }

    @FXML
    private fun dumpMaps() {
        if (!checkForCacheDirectory()) {
            return
        }

        val chooser = FileChooser()
        chooser.title = "Select xteas.json"
        chooser.initialDirectory = File("./")

        val xteaDir = chooser.showOpenDialog(App.stage) ?: return

        if (!XTEAManager.load(xteaDir)) {
            Dialogue.showWarning("Could not load any xteas from ${xteaDir.path}.").show()
            return
        }

        runTask("Map Converter", MapTask(cachePath!!))
    }

    @FXML
    private fun dumpMapImage() {
        if (!checkForCacheDirectory()) {
            return
        }

        val chooser = FileChooser()
        chooser.title = "Select xteas.json"
        chooser.initialDirectory = File("./")
        val xteaFile = chooser.showOpenDialog(App.stage) ?: return

        val outputDir = File("./dump/")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        runTask("Map Image Task", MapImageTask(cachePath!!.toPath(), xteaFile.toPath(), outputDir.toPath()))
    }

    @FXML
    private fun checkRevision() {
         val loader = FXMLLoader(App::class.java.getResource("/ui/OsrcScene.fxml"))
        val root = loader.load<Parent>()

        val stage = Stage()
        stage.scene = Scene(root)
        stage.isResizable = false
        stage.initStyle(StageStyle.UNDECORATED)
        stage.scene.stylesheets.add(App::class.java.getResource("/style.css").toExternalForm())
        stage.icons.add(Image(App::class.java.getResourceAsStream("/icons/icon.png")))
        stage.centerOnScreen()
        stage.show()
    }

    @FXML
    private fun fetchCache() {
        val requester = CacheRequester()

        runTask("OSRS Cache Fetcher", requester)
    }

    @FXML
    private fun viewDirectory() {
        val dir = File("./dump/")

        if (!dir.exists()) {
            dir.mkdirs()
        }
        try
        {
            Desktop.getDesktop().open(dir)
        }
        catch (ex:Exception) {
            Dialogue.showException("Error while trying to view image on desktop.", ex)
        }
    }

    companion object {
        val MAX_REGION = 32768
    }

}