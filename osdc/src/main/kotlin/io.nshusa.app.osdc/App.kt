package io.nshusa.app.osdc

import io.nshusa.app.osdc.util.VersionUtils
import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import javafx.stage.StageStyle
import java.io.IOException
import java.io.File
import java.io.FileReader
import java.net.URL
import java.util.*

class App: Application() {

    private val properties = Properties()

    override fun init() {

        try {
            Scanner(URL(versionUrl).openStream()).use { it ->
                val version = it.nextLine().trim()
                latestVersion = version
                if (VersionUtils.versionCompare(osdcVersion, version) < 0) {
                    shouldUpdate = true
                }
            }
        } catch (ex: IOException) {
            ex.printStackTrace()
        }

        try {
            val file = File("./version.properties")

            if (file.exists()) {
                FileReader(file).use { reader ->
                    properties.load(reader)
                    osrsVersion = Integer.parseInt(properties.getProperty("version"))
                }
            }
        } catch (ex: IOException) {
            ex.printStackTrace()
        }
    }

    override fun start(stage: Stage) {
        App.stage = stage
        val root : Parent? = FXMLLoader.load(App::class.java.getResource("/ui/Main.fxml"))
        val scene = Scene(root)
        scene.stylesheets.add(App::class.java.getResource("/style.css").toExternalForm())
        stage.title = "OSDC"
        stage.centerOnScreen()
        stage.isResizable = false
        stage.sizeToScene()
        stage.initStyle(StageStyle.UNDECORATED)
        stage.scene = scene
        stage.icons.add(Image(App::class.java.getResourceAsStream("/icons/icon.png")))

        if (shouldUpdate) {
            update()
        }

        stage.show()

    }

    private fun update() {
        val loader = FXMLLoader(App::class.java.getResource("/ui/UpdateScene.fxml"))
        val root = loader.load<Parent>()

        val stage = Stage()
        stage.scene = Scene(root)
        stage.initStyle(StageStyle.UNDECORATED)
        stage.icons.add(Image(App::class.java.getResourceAsStream("/icons/icon.png")))
        stage.scene.stylesheets.add(App::class.java.getResource("/style.css").toExternalForm())
        stage.sizeToScene()
        stage.centerOnScreen()
        stage.showAndWait()
    }

    companion object {

        private var shouldUpdate = false

        val osdcVersion = "2.0.11"

        var latestVersion = osdcVersion

        val versionUrl = "https://dl.dropboxusercontent.com/s/hy1wsrd8gni9od1/version.txt"

        var osrsVersion = 177

        var stage: Stage = Stage()

        @JvmStatic
        fun main(args:Array<String>) {
            launch(App::class.java)
        }

    }
}