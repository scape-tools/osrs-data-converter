package io.nshusa.app.osdc.util

object Misc {

    fun launchURL(url: String) {
        val osName = System.getProperty("os.name")
        try {
            if (osName.startsWith("Mac OS")) {
                val fileMgr = Class.forName("com.apple.eio.FileManager")
                val openURL = fileMgr.getDeclaredMethod("openURL", String::class.java)
                openURL.invoke(null, arrayOf<Any>(url))
            } else if (osName.startsWith("Windows"))
                Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler " + url)
            else {
                val browsers = arrayOf("firefox", "opera", "konqueror", "epiphany", "mozilla", "netscape", "safari")
                var browser: String? = null
                var count = 0
                while (count < browsers.size && browser == null) {
                    if (Runtime.getRuntime().exec(arrayOf("which", browsers[count]))
                            .waitFor() == 0)
                        browser = browsers[count]
                    count++
                }
                if (browser == null) {
                    throw Exception("Could not find web browser")
                } else
                    Runtime.getRuntime().exec(arrayOf(browser, url))
            }
        } catch (ex: Exception) {
            Dialogue.showWarning("Failed to open url.").showAndWait()
        }

    }

}