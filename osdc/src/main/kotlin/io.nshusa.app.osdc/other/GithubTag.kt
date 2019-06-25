package io.nshusa.app.osdc.other

class GithubTag(val name: String, val zipball_url: String, val tarball_url: String, val commit: Commit) {

    companion object {
        class Commit(val sha: String, val url: String)
    }

}