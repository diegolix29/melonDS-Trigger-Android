package me.magnum.melonds.github

import me.magnum.melonds.github.dtos.ReleaseDto
import retrofit2.http.GET

interface GitHubApi {
    @GET("/repos/diegolix29/melonDS-Trigger-Android/releases/latest")
    suspend fun getLatestRelease(): ReleaseDto

    @GET("/repos/diegolix29/melonDS-Trigger-Android/releases/tags/nightly-release")
    suspend fun getLatestNightlyRelease(): ReleaseDto
}
