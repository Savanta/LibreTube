package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.databinding.DialogCastPairingBinding
import com.github.libretube.sender.LoungeSender
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import android.util.Log

class CastPairingDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogCastPairingBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cast_pairing_title)
            .setView(binding.root)
            .setPositiveButton(R.string.cast_pair, null)
            .setNeutralButton(R.string.cast_pair_remove, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = binding.pairingCode.text?.toString().orEmpty().trim()
                val isValid = code.length == 12 && code.all { it.isDigit() }
                if (!isValid) {
                    Toast.makeText(requireContext(), R.string.invalid_input, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                pairDevice(binding, code)
            }

            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val sender = LoungeSender(requireContext())
                sender.clearDevice()
                Toast.makeText(requireContext(), R.string.cast_pair_remove, Toast.LENGTH_SHORT).show()
                setFragmentResult(REQUEST_KEY, Bundle.EMPTY)
                dismiss()
            }
        }

        return dialog
    }

    private fun pairDevice(binding: DialogCastPairingBinding, pairingCode: String) {
        binding.pairingCode.isEnabled = false
        lifecycleScope.launch {
            val sender = LoungeSender(requireContext())
            val result = sender.pair(pairingCode)
            binding.pairingCode.isEnabled = true
            if (result.isSuccess) {
                val device = result.getOrNull()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.cast_pair_success, device?.name ?: "TV"),
                    Toast.LENGTH_SHORT
                ).show()
                val bundle = Bundle().apply {
                    putString(KEY_DEVICE_NAME, device?.name)
                }
                setFragmentResult(REQUEST_KEY, bundle)
                dismiss()
            } else {
                Log.e(TAG, "Pairing failed", result.exceptionOrNull())
                Toast.makeText(requireContext(), R.string.cast_pair_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val REQUEST_KEY = "cast_pairing"
        const val KEY_DEVICE_NAME = "device_name"
        private const val TAG = "CastPairingDialog"
    }
}
