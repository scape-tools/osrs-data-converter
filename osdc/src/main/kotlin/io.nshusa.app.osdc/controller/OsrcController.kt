package io.nshusa.app.osdc.controller

import javafx.fxml.FXML
import java.net.URL
import java.util.*
import javafx.animation.PauseTransition
import javafx.concurrent.Task
import javafx.scene.control.Button
import javafx.scene.text.Text
import javafx.util.Duration
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import io.nshusa.app.osdc.App
import io.nshusa.app.osdc.other.osrscd.net.CacheRequester
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.paint.Color
import javafx.stage.Stage

class OsrcController : Controller() {

    @FXML
    lateinit var requestBtn: Button

    @FXML
    lateinit var  statusText: Text

    @FXML
    lateinit var osrcPane: BorderPane

    override fun initialize(location: URL, resources: ResourceBundle?) {

    }

    @FXML
    private fun requestRevision() {
        val requester = CacheRequester()

        createTask(object : Task<Boolean>() {

            @Throws(Exception::class)
            override fun call(): Boolean? {

                updateMessage("Connecting...")

                requester.connect("oldschool" + 1 + ".runescape.com", App.osrsVersion)

                while (requester.currentState != CacheRequester.State.CONNECTED) {

                    if (requester.currentState == CacheRequester.State.OUTDATED) {
                        updateMessage(String.format("Requesting=%d", requester.revision))
                    }

                    requester.process()
                }

                if (requester.revision > App.osrsVersion) {
                    App.osrsVersion = requester.revision
                    PrintWriter(FileWriter(File("./version.properties"))).use { writer -> writer.println(String.format("version=%d", requester.revision)) }
                }

                updateMessage(String.format("Success!\nRevision=%d", requester.revision))
                return true
            }

        })

    }

    override fun setCurrentStage() {
        currentStage = osrcPane.scene.window as Stage
    }

    override fun closeProgram() {
        val stage = osrcPane.scene.window as Stage
        stage.close()
    }

    override fun handleMouseDragged(event: MouseEvent) {
        currentStage = osrcPane.scene.window as Stage
        currentStage.x = event.screenX - xOffset
        currentStage.y = event.screenY - yOffset
    }

    private fun createTask(task: Task<*>) {

        requestBtn.isVisible = false
        statusText.textProperty().unbind()
        statusText.textProperty().bind(task.messageProperty())
        statusText.fill = Color.WHITE

        Thread(task).start()

        task.setOnSucceeded({ _ ->

            val pause = PauseTransition(Duration.seconds(6.0))

            pause.setOnFinished { _ ->
                statusText.textProperty().unbind()
                statusText.text = ""
                requestBtn.isVisible = true
            }

            pause.play()
        })

        task.setOnFailed({ _ ->

            val pause = PauseTransition(Duration.seconds(6.0))

            pause.setOnFinished { _ ->
                statusText.textProperty().unbind()
                statusText.text = ""
                requestBtn.isVisible = true
            }

            pause.play()

        })
    }


}