package me.magnum.melonds.ui.settings.fragments

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.smp.masterswitchpreference.MasterSwitchPreference
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.magnum.melonds.R
import me.magnum.melonds.common.DirectoryAccessValidator
import me.magnum.melonds.common.UriPermissionManager
import me.magnum.melonds.domain.model.appupdate.AppUpdate
import me.magnum.melonds.domain.repositories.UpdatesRepository
import me.magnum.melonds.domain.services.UpdateInstallManager
import me.magnum.melonds.extensions.isSustainedPerformanceModeAvailable
import me.magnum.melonds.impl.SettingsBackupManager
import me.magnum.melonds.ui.settings.PreferenceFragmentHelper
import me.magnum.melonds.ui.settings.PreferenceFragmentTitleProvider
import javax.inject.Inject

@AndroidEntryPoint
class GeneralPreferencesFragment : BasePreferenceFragment(), PreferenceFragmentTitleProvider {

    private val helper by lazy { PreferenceFragmentHelper(this, uriPermissionManager, directoryAccessValidator) }
    @Inject lateinit var uriPermissionManager: UriPermissionManager
    @Inject lateinit var directoryAccessValidator: DirectoryAccessValidator
    @Inject lateinit var settingsBackupManager: SettingsBackupManager
    @Inject lateinit var updatesRepository: UpdatesRepository
    @Inject lateinit var updateInstallManager: UpdateInstallManager
    @Inject lateinit var markwon: Markwon

    private lateinit var rewindPreference: MasterSwitchPreference

    private val backupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { settingsBackupManager.backup(uri) }
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(requireContext())
                                .setMessage(R.string.settings_backup_success)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                    .onFailure {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), R.string.settings_backup_error, Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }
    }

    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { settingsBackupManager.restore(uri) }
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(requireContext())
                                .setMessage(R.string.settings_restore_success)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                    .onFailure {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), R.string.settings_restore_error, Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_general, rootKey)
        addPreferencesFromResource(R.xml.pref_general_updates)

        rewindPreference = findPreference("enable_rewind")!!
        val sustainedPerformancePreference = findPreference<SwitchPreference>("enable_sustained_performance")!!

        helper.bindPreferenceSummaryToValue(rewindPreference)
        sustainedPerformancePreference.isVisible = requireContext().isSustainedPerformanceModeAvailable()

        findPreference<Preference>("backup_settings")?.setOnPreferenceClickListener {
            backupLauncher.launch(null)
            true
        }
        findPreference<Preference>("restore_settings")?.setOnPreferenceClickListener {
            restoreLauncher.launch(null)
            true
        }
        findPreference<Preference>("github_check_for_updates_now")?.setOnPreferenceClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = updatesRepository.forceCheckNewUpdate()
                withContext(Dispatchers.Main) {
                    result.onSuccess { update ->
                        if (update != null) {
                            showUpdateAvailableDialog(update)
                        } else {
                            Toast.makeText(requireContext(), R.string.no_update_available, Toast.LENGTH_SHORT).show()
                        }
                    }.onFailure {
                        Toast.makeText(requireContext(), R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // Set proper value for Rewind preference since the value is not updated when returning from the fragment
        rewindPreference.onPreferenceChangeListener?.onPreferenceChange(rewindPreference, rewindPreference.sharedPreferences?.getBoolean(rewindPreference.key, false))
    }

    override fun getTitle() = getString(R.string.category_general)

    private fun showUpdateAvailableDialog(update: AppUpdate) {
        when (update.type) {
            AppUpdate.Type.PRODUCTION -> {
                val message = markwon.toMarkdown(update.description)
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.update_available, getReadableVersionString(update.newVersion)))
                    .setMessage(message)
                    .setPositiveButton(R.string.update) { _, _ ->
                        startUpdateDownload(update)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .setNeutralButton(R.string.skip_update) { _, _ ->
                        updatesRepository.skipUpdate(update)
                    }
                    .show()
            }
            AppUpdate.Type.NIGHTLY -> {
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.nightly_update_available))
                    .setMessage(getString(R.string.nightly_update_available_message))
                    .setPositiveButton(R.string.update) { _, _ ->
                        startUpdateDownload(update)
                    }
                    .setNegativeButton(R.string.remind_later_update) { _, _ ->
                        updatesRepository.skipUpdate(update)
                    }
                    .show()
            }
        }
    }

    private fun startUpdateDownload(update: AppUpdate) {
        lifecycleScope.launch {
            updateInstallManager.downloadAndInstallUpdate(update).collectLatest { progress ->
                when (progress) {
                    is me.magnum.melonds.domain.model.DownloadProgress.DownloadUpdate -> {
                        // Could show progress dialog here if needed
                    }
                    is me.magnum.melonds.domain.model.DownloadProgress.DownloadComplete -> {
                        updatesRepository.notifyUpdateDownloaded(update)
                        Toast.makeText(requireContext(), R.string.update_download_complete, Toast.LENGTH_SHORT).show()
                    }
                    is me.magnum.melonds.domain.model.DownloadProgress.DownloadFailed -> {
                        Toast.makeText(requireContext(), R.string.update_download_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun getReadableVersionString(version: me.magnum.melonds.domain.model.Version): String {
        val typeString = when(version.type) {
            me.magnum.melonds.domain.model.Version.ReleaseType.ALPHA -> getString(R.string.version_alpha)
            me.magnum.melonds.domain.model.Version.ReleaseType.BETA -> getString(R.string.version_beta)
            me.magnum.melonds.domain.model.Version.ReleaseType.FINAL -> ""
            me.magnum.melonds.domain.model.Version.ReleaseType.NIGHTLY -> return getString(R.string.version_nightly)
        }
        return "$typeString${if (typeString.isEmpty()) "" else " "}${version.major}.${version.minor}.${version.patch}"
    }
}