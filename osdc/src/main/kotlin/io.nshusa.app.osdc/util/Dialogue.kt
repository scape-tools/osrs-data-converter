package io.nshusa.app.osdc.util

import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import java.awt.Desktop
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Arrays
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextInputDialog
import javafx.scene.control.Alert

object Dialogue {
    fun openDirectory(headerText:String, dir: File) {
        val result = Dialogue.showOption(headerText, ButtonType.YES, ButtonType.NO).showAndWait()
        if (result.isPresent)
        {
            if (result.get() == ButtonType.YES)
            {
                try
                {
                    Desktop.getDesktop().open(dir)
                }
                catch (ex:Exception) {
                    Dialogue.showException("Error while trying to view image on desktop.", ex)
                }
            }
        }
    }
    fun showWarning(message:String):Alert {
        val alert = Alert(Alert.AlertType.WARNING)
        alert.title = "Warning"
        alert.headerText = null
        alert.contentText = message
        return alert
    }
    fun showInfo(title:String, message:String):Alert {
        val alert = Alert(Alert.AlertType.INFORMATION)
        alert.title = title
        alert.headerText = message
        return alert
    }
    fun showException(message:String, ex:Exception):Alert {
        val alert = Alert(Alert.AlertType.ERROR)
        alert.title = "Exception"
        alert.headerText = "Encountered an Exception"
        alert.contentText = message
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        ex.printStackTrace(pw)
        val exceptionText = sw.toString()
        val label = Label("The exception stacktrace was:")
        val textArea = TextArea(exceptionText)
        textArea.isEditable = false
        textArea.isWrapText = true
        textArea.maxWidth = java.lang.Double.MAX_VALUE
        textArea.maxHeight = java.lang.Double.MAX_VALUE
        GridPane.setVgrow(textArea, Priority.ALWAYS)
        GridPane.setHgrow(textArea, Priority.ALWAYS)
        val expContent = GridPane()
        expContent.maxWidth = java.lang.Double.MAX_VALUE
        expContent.add(label, 0, 0)
        expContent.add(textArea, 0, 1)
        alert.dialogPane.expandableContent = expContent
        return alert
    }
    fun showOption(title:String, header:String, context:String, vararg types:ButtonType):Alert {
        val alert = Alert(Alert.AlertType.CONFIRMATION)
        alert.title = title
        alert.headerText = header
        alert.contentText = context
        alert.buttonTypes.clear()
        alert.buttonTypes.addAll(Arrays.asList<ButtonType>(*types))
        return alert
    }
    fun showOption(title:String, header:String, vararg types: ButtonType):Alert {
        return showOption(title, header, "", *types)
    }
    fun showOption(header:String, vararg types:ButtonType): Alert {
        return showOption("Confirmation", header, "", *types)
    }

    fun showInput(headerText:String) : TextInputDialog {
        return showInput(headerText, "")
    }

    fun showInput(headerText:String, contextText:String):TextInputDialog {
        val input = TextInputDialog()
        input.title = "Input"
        input.headerText = headerText
        input.contentText = contextText
        return input
    }
}