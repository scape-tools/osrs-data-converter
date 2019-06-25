package io.nshusa.app.osdc.controller

import io.nshusa.app.osdc.App
import javafx.application.Platform
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.Node
import javafx.scene.input.InputEvent
import javafx.scene.input.MouseEvent
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import net.openrs.cache.Cache
import net.openrs.cache.FileStore
import java.io.File
import java.net.URL
import java.util.*

abstract class BaseController : Initializable {

    var xOffset:Double = 0.toDouble()
    var yOffset:Double = 0.toDouble()

    var cachePath : File? = null

    override fun initialize(location: URL, resources: ResourceBundle?) {

    }

    protected fun checkForCacheDirectory() : Boolean {
        if (cachePath == null) {
            val chooser = DirectoryChooser()
            chooser.title = "Select directory containing osrs cache"
            chooser.initialDirectory = File("./")

            val result = chooser.showDialog(App.stage) ?: return false

            try {
                Cache(FileStore.open(result)).use {
                    cachePath = result
                    return true
                }
            } catch (ex: Exception) {
                return false
            }
        }
        return true
    }

    @FXML
    open fun setCurrentStage() {
        currentStage = App.stage
    }

    @FXML
    private fun minimizeProgram(e: ActionEvent) {
        val node = e.source as Node
        currentStage = node.scene.window as Stage
        currentStage.isIconified = true
    }

    @FXML
    open fun closeProgram() {
        Platform.exit()
    }

    @FXML
    open fun handleMouseDragged(event: MouseEvent) {
        currentStage = App.stage
        currentStage.x = event.screenX - xOffset
        currentStage.y = event.screenY - yOffset
    }
    @FXML
    open fun handleMousePressed(event:MouseEvent) {
        xOffset = event.sceneX
        yOffset = event.sceneY
    }

    companion object {
        var currentStage = App.stage
    }

}