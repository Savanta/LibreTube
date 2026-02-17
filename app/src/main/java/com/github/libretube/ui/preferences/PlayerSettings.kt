package com.github.libretube.ui.preferences

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.github.libretube.R
import com.github.libretube.compat.PictureInPictureCompat
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.LocaleHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.sender.LoungeSender
import com.github.libretube.ui.base.BasePreferenceFragment
import com.github.libretube.ui.dialogs.CastPairingDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PlayerSettings : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.player_settings, rootKey)

        val defaultSubtitle = findPreference<ListPreference>(PreferenceKeys.DEFAULT_SUBTITLE)
        defaultSubtitle?.let { setupSubtitlePref(it) }

        val captionSettings = findPreference<Preference>(PreferenceKeys.CAPTION_SETTINGS)
        captionSettings?.setOnPreferenceClickListener {
            try {
                val captionSettingsIntent = Intent(Settings.ACTION_CAPTIONING_SETTINGS)
                startActivity(captionSettingsIntent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
            }
            true
        }

        val behaviorWhenMinimized =
            findPreference<ListPreference>(PreferenceKeys.BEHAVIOR_WHEN_MINIMIZED)!!
        val alternativePipControls =
            findPreference<SwitchPreferenceCompat>(PreferenceKeys.ALTERNATIVE_PIP_CONTROLS)

        val pipAvailable = PictureInPictureCompat.isPictureInPictureAvailable(requireContext())
        if (!pipAvailable) {
            with(behaviorWhenMinimized) {
                // remove PiP option entry
                entries = entries.toList().subList(1, 3).toTypedArray()
                entryValues = entryValues.toList().subList(1, 3).toTypedArray()
                if (value !in entryValues) value = entryValues.first().toString()
            }
        }

        alternativePipControls?.isVisible = pipAvailable

        val castPairingPref = findPreference<Preference>(PreferenceKeys.CAST_SENDER_PAIR)
        castPairingPref?.summary = getCastPairingSummary()
        castPairingPref?.setOnPreferenceClickListener {
            CastPairingDialog().show(parentFragmentManager, CastPairingDialog.REQUEST_KEY)
            true
        }

        val castManagePref = findPreference<Preference>(PreferenceKeys.CAST_SENDER_MANAGE)
        castManagePref?.setOnPreferenceClickListener {
            val sender = LoungeSender(requireContext())
            val devices = sender.pairedDevices()
            if (devices.isEmpty()) {
                Toast.makeText(requireContext(), R.string.cast_sender_no_devices, Toast.LENGTH_SHORT).show()
                return@setOnPreferenceClickListener true
            }

            val labels = devices.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.cast_sender_manage)
                .setItems(labels) { dialog, which ->
                    devices.getOrNull(which)?.let { device ->
                        sender.removeDevice(device)
                        // If we removed the active device, clear the active selection.
                        sender.currentDevice()?.let { active ->
                            if (active.screenId == device.screenId) {
                                sender.clearActiveDevice()
                            }
                        }
                        castPairingPref?.summary = getCastPairingSummary()
                        Toast.makeText(requireContext(), getString(R.string.cast_pair_remove), Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val castPairingPref = findPreference<Preference>(PreferenceKeys.CAST_SENDER_PAIR)
        parentFragmentManager.setFragmentResultListener(
            CastPairingDialog.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val deviceName = bundle.getString(CastPairingDialog.KEY_DEVICE_NAME)
            castPairingPref?.summary = deviceName?.let {
                getString(R.string.cast_connected)
            } ?: getCastPairingSummary()
        }
    }

    private fun setupSubtitlePref(preference: ListPreference) {
        val locales = LocaleHelper.getAvailableLocales()
        val localeNames = locales.map { it.name }
            .toMutableList()
        localeNames.add(0, requireContext().getString(R.string.none))

        val localeCodes = locales.map { it.code }
            .toMutableList()
        localeCodes.add(0, "")

        preference.entries = localeNames.toTypedArray()
        preference.entryValues = localeCodes.toTypedArray()
        preference.summaryProvider =
            Preference.SummaryProvider<ListPreference> {
                it.entry
            }
    }

    private fun getCastPairingSummary(): String {
        val current = LoungeSender(requireContext()).currentDevice()
        if (current != null) return getString(R.string.cast_connected)
        return getString(R.string.cast_sender_pair_summary)
    }
}
