package io.nshusa.app.osdc.controller

import io.nshusa.app.osdc.App
import io.nshusa.app.osdc.util.Misc
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.paint.Color
import javafx.scene.text.Text
import javafx.stage.Stage
import java.net.URL
import java.util.*

class UpdateController : BaseController() {

    @FXML
    lateinit var updatePane : BorderPane

    @FXML
    lateinit var mainText : Text

    @FXML
    lateinit var versionText : Text

    override fun initialize(location: URL, resources: ResourceBundle?) {
        mainText.fill = Color.WHITE
        versionText.fill = Color.WHITE
        versionText.text = App.latestVersion
    }

    @FXML
    private fun okBtn() {
        Misc.launchURL(threadUrl)
        closeProgram()
    }

    @FXML
    private fun cancelBtn() {
        val stage = updatePane.scene.window as Stage
        stage.close()
    }

    override fun setCurrentStage() {
        currentStage = updatePane.scene.window as Stage
    }

    override fun closeProgram() {
        Platform.exit()
        val stage = updatePane.scene.window as Stage
        stage.close()
    }

    override fun handleMouseDragged(event: MouseEvent) {
        currentStage = updatePane.scene.window as Stage
        currentStage.x = event.screenX - xOffset
        currentStage.y = event.screenY - yOffset
    }

    companion object {
        val threadUrl = "https://sellfy.com/p/juuq/"
    }

}