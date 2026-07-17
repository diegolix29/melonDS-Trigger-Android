package me.magnum.melonds.domain.repositories

import me.magnum.melonds.domain.model.appupdate.AppUpdate

interface UpdatesRepository {
    suspend fun checkNewUpdate(): Result<AppUpdate?>
    suspend fun forceCheckNewUpdate(): Result<AppUpdate?>
    fun skipUpdate(update: AppUpdate)
    fun notifyUpdateDownloaded(update: AppUpdate)
}