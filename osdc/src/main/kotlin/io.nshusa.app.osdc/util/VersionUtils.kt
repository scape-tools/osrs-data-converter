package io.nshusa.app.osdc.util

object VersionUtils {

    fun versionCompare(str1: String, str2: String): Int {
        val vals1 = str1.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val vals2 = str2.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        var i = 0
        while (i < vals1.size && i < vals2.size && vals1[i] == vals2[i]) {
            i++
        }

        if (i < vals1.size && i < vals2.size) {
            val diff = Integer.valueOf(vals1[i]).compareTo(Integer.valueOf(vals2[i]))
            return Integer.signum(diff)
        }

        return Integer.signum(vals1.size - vals2.size)
    }

}