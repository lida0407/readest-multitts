package com.readest.multitts.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Window
import com.readest.multitts.databinding.DialogMultittsDownloadBinding
import com.readest.multitts.tts.MultiTTSManager

class MultiTTSDownloadDialog(
    context: Context,
    private val onDismissCallback: (() -> Unit)? = null
) : Dialog(context) {

    private lateinit var binding: DialogMultittsDownloadBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogMultittsDownloadBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val isInstalled = MultiTTSManager.isMultiTTSInstalled(context)
        if (isInstalled) {
            binding.tvDialogTitle.text = "MultiTTS Engine (Installed)"
            binding.tvDialogMessage.text = "MultiTTS is installed on your device! You can configure voice packs or open system speech settings."
            binding.btnDownloadMultiTTS.text = "Check MultiTTS Updates"
        }

        binding.btnDownloadMultiTTS.setOnClickListener {
            MultiTTSManager.openDownloadPage(context, useMirror = false)
            dismiss()
        }

        binding.btnDownloadVoicePacks.setOnClickListener {
            MultiTTSManager.openVoicePacksPage(context)
            dismiss()
        }

        binding.btnOpenSystemTtsSettings.setOnClickListener {
            MultiTTSManager.openSystemTtsSettings(context)
            dismiss()
        }

        binding.btnDialogDismiss.setOnClickListener {
            dismiss()
        }

        setOnDismissListener {
            onDismissCallback?.invoke()
        }
    }
}
