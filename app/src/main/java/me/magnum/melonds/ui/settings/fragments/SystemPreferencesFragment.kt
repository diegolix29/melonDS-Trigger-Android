package me.magnum.melonds.ui.settings.fragments

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.magnum.melonds.R
import me.magnum.melonds.common.DirectoryAccessValidator
import me.magnum.melonds.common.UriPermissionManager
import me.magnum.melonds.impl.SettingsBackupManager
import me.magnum.melonds.ui.settings.PreferenceFragmentTitleProvider
import java.util.Calendar
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class SystemPreferencesFragment : BasePreferenceFragment(), PreferenceFragmentTitleProvider {

    @Inject lateinit var uriPermissionManager: UriPermissionManager
    @Inject lateinit var directoryAccessValidator: DirectoryAccessValidator
    @Inject lateinit var settingsBackupManager: SettingsBackupManager

    private val backupInternalLayoutLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { settingsBackupManager.backupInternalLayout(uri) }
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(requireContext())
                                .setMessage(R.string.internal_layout_backup_success)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                    .onFailure {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), R.string.internal_layout_backup_error, Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }
    }

    private val backupExternalLayoutLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { settingsBackupManager.backupExternalLayout(uri) }
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(requireContext())
                                .setMessage(R.string.external_layout_backup_success)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                    .onFailure {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), R.string.external_layout_backup_error, Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }
    }

    private val restoreInternalLayoutLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { settingsBackupManager.restoreInternalLayout(uri) }
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(requireContext())
                                .setMessage(R.string.internal_layout_restore_success)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                    .onFailure {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), R.string.internal_layout_restore_error, Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }
    }

    private val restoreExternalLayoutLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { settingsBackupManager.restoreExternalLayout(uri) }
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(requireContext())
                                .setMessage(R.string.external_layout_restore_success)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                    .onFailure {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), R.string.external_layout_restore_error, Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }
    }

    override fun getTitle() = getString(R.string.category_system)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_system, rootKey)
        val jitPreference = findPreference<SwitchPreference>("enable_jit")!!

        if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) {
            jitPreference.isEnabled = false
            jitPreference.isChecked = false
            jitPreference.setSummary(R.string.jit_not_supported)
        }

        findPreference<Preference>("backup_internal_layout")?.setOnPreferenceClickListener {
            backupInternalLayoutLauncher.launch(null)
            true
        }
        findPreference<Preference>("backup_external_layout")?.setOnPreferenceClickListener {
            backupExternalLayoutLauncher.launch(null)
            true
        }
        findPreference<Preference>("restore_internal_layout")?.setOnPreferenceClickListener {
            restoreInternalLayoutLauncher.launch(null)
            true
        }
        findPreference<Preference>("restore_external_layout")?.setOnPreferenceClickListener {
            restoreExternalLayoutLauncher.launch(null)
            true
        }
        
        findPreference<Preference>("rtc_time_settings")?.setOnPreferenceClickListener {
            showRTCTimeSettingsDialog()
            true
        }
        
        // Update the RTC time preference summary to show current time
        updateRTCTimePreferenceSummary()
    }
    
    private fun updateRTCTimePreferenceSummary() {
        val rtcPreference = findPreference<Preference>("rtc_time_settings")
        if (rtcPreference != null) {
            try {
                // Check if emulator is running before trying to get RTC time
                val rtcTime = try {
                    getCurrentRTCTime()
                } catch (e: UnsatisfiedLinkError) {
                    // Native library not loaded or emulator not initialized
                    -1L
                } catch (e: Exception) {
                    // Other native errors
                    -1L
                }
                
                if (rtcTime > 0) {
                    val date = java.util.Date(rtcTime * 1000) // Convert seconds to milliseconds
                    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    val formattedTime = formatter.format(date)
                    rtcPreference.summary = "Current RTC time: $formattedTime"
                } else {
                    rtcPreference.summary = "Emulator not running - RTC time unavailable"
                }
            } catch (e: Exception) {
                rtcPreference.summary = "Unable to get current RTC time"
            }
        }
    }
    
    private fun showRTCTimeSettingsDialog() {
        val calendar = Calendar.getInstance()
        
        // Create options for the dialog
        val options = arrayOf("Set to current time", "Set custom time", "Reset to system time")
        
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rtc_time_settings)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> setCurrentTime()
                    1 -> showCustomTimeDialog()
                    2 -> resetToSystemTime()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    
    private fun setCurrentTime() {
        val calendar = Calendar.getInstance()
        callNativeSetRTCDateTime(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND)
        )
    }
    
    private fun resetToSystemTime() {
        // Reset offset to 0 to use system time
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Call native function to reset RTC offset
                try {
                    resetRTCOffset()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "RTC time reset to system time", Toast.LENGTH_SHORT).show()
                        // Update the preference summary to show the new time
                        updateRTCTimePreferenceSummary()
                    }
                } catch (e: UnsatisfiedLinkError) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Emulator not running - cannot reset RTC time", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Failed to reset RTC time", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showCustomTimeDialog() {
        val calendar = Calendar.getInstance()
        
        // Show date picker first
        val datePickerDialog = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                // After date is selected, show time picker
                val timePickerDialog = TimePickerDialog(
                    requireContext(),
                    { _, hourOfDay, minute ->
                        callNativeSetRTCDateTime(year, month, dayOfMonth, hourOfDay, minute, 0)
                        Toast.makeText(requireContext(), "RTC time set to custom time", Toast.LENGTH_SHORT).show()
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true
                )
                timePickerDialog.show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        datePickerDialog.show()
    }
    
    private fun callNativeSetRTCDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Call native function to set RTC time
                // Convert month from Android Calendar (0-11) to native format (1-12)
                try {
                    setRTCDateTime(year, month + 1, day, hour, minute, second)
                    withContext(Dispatchers.Main) {
                        val timeString = String.format("%04d-%02d-%02d %02d:%02d:%02d", year, month + 1, day, hour, minute, second)
                        Toast.makeText(requireContext(), "RTC time set to: $timeString", Toast.LENGTH_LONG).show()
                        // Update the preference summary to show the new time
                        updateRTCTimePreferenceSummary()
                    }
                } catch (e: UnsatisfiedLinkError) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Emulator not running - cannot set RTC time", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Failed to set RTC time", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private external fun resetRTCOffset()
    private external fun setRTCDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int)
    private external fun getCurrentRTCTime(): Long
}