package io.nshusa.app.osdc.controller

import javafx.animation.PauseTransition
import javafx.concurrent.Task
import javafx.fxml.FXML
import javafx.scene.control.ProgressBar
import javafx.scene.input.MouseEvent
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.util.Duration
import java.net.URL
import java.util.*

open class TaskController : BaseController() {

    @FXML
    lateinit var taskPane: VBox

    @FXML
    lateinit var progressText: Text

    @FXML
    lateinit var title: Text

    @FXML
    lateinit var progressBar: ProgressBar

    override fun initialize(location: URL, resources: ResourceBundle?) {
        title.fill = Color.WHITE
    }

    @FXML
    override fun setCurrentStage() {
        currentStage = getStage()
    }

    @FXML
    override fun closeProgram() {
        val stage = getStage()
        stage.close()
    }

    @FXML
    override fun handleMouseDragged(event: MouseEvent) {
        currentStage = taskPane.scene.window as Stage
        currentStage.x = event.screenX - xOffset
        currentStage.y = event.screenY - yOffset
    }

    open fun createTask(task: Task<*>) {
        progressText.textProperty().unbind()
        progressText.textProperty().bind(task.messageProperty())
        progressBar.progressProperty().unbind()
        progressBar.progressProperty().bind(task.progressProperty())
        progressText.fill = Color.WHITE
        progressBar.isVisible = true

        Thread(task).start()

        task.setOnSucceeded({ _ ->

            progressText.textProperty().unbind()

            val pause = PauseTransition(Duration.seconds(2.0))

            pause.setOnFinished { _ ->
                getStage().close()
            }

            pause.play()
        })

        task.setOnFailed({ _ ->

            val pause = PauseTransition(Duration.seconds(2.0))

            pause.setOnFinished { _ ->
                progressText.textProperty().unbind()
                progressText.text = "Oops, something went wrong!"
            }

            pause.play()

        })
    }

    fun getStage() : Stage {
        return taskPane.scene.window as Stage
    }


}