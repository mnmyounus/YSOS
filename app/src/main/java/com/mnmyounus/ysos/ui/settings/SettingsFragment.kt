package com.mnmyounus.ysos.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.mnmyounus.ysos.BuildConfig
import com.mnmyounus.ysos.R
import com.mnmyounus.ysos.YSOSApplication
import com.mnmyounus.ysos.databinding.FragmentSettingsBinding
import com.mnmyounus.ysos.security.BiometricAuthManager
import com.mnmyounus.ysos.util.PreferencesManager
import com.google.android.material.snackbar.Snackbar

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: PreferencesManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = (requireActivity().application as YSOSApplication).preferences
        val app = requireActivity().application as YSOSApplication

        // Theme radio
        when (prefs.themeMode) {
            PreferencesManager.THEME_LIGHT  -> binding.rbThemeLight.isChecked = true
            PreferencesManager.THEME_DARK   -> binding.rbThemeDark.isChecked = true
            else                            -> binding.rbThemeSystem.isChecked = true
        }

        binding.rgTheme.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.rb_theme_light  -> PreferencesManager.THEME_LIGHT
                R.id.rb_theme_dark   -> PreferencesManager.THEME_DARK
                else                 -> PreferencesManager.THEME_SYSTEM
            }
            prefs.themeMode = mode
            app.applyTheme(mode)
        }

        // Biometric switch
        binding.switchBiometric.isChecked = prefs.biometricEnabled
        binding.switchBiometric.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Verify biometric is actually available before enabling
                if (BiometricAuthManager.isBiometricAvailable(requireActivity() as androidx.fragment.app.FragmentActivity)) {
                    prefs.biometricEnabled = true
                } else {
                    binding.switchBiometric.isChecked = false
                    Snackbar.make(binding.root, R.string.biometric_not_available, Snackbar.LENGTH_SHORT).show()
                }
            } else {
                prefs.biometricEnabled = false
            }
        }

        // Version
        binding.tvVersion.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)

        // Evidence Vault button
        binding.btnOpenVault.setOnClickListener {
            if (prefs.biometricEnabled) {
                BiometricAuthManager.authenticate(
                    activity = requireActivity(),
                    title = getString(R.string.biometric_prompt_title),
                    subtitle = getString(R.string.biometric_prompt_subtitle),
                    onSuccess = {
                        openVault()
                    },
                    onFailure = { errorMsg ->
                        Snackbar.make(binding.root, errorMsg, Snackbar.LENGTH_SHORT).show()
                    }
                )
            } else {
                openVault()
            }
        }
    }

    private fun openVault() {
        EvidenceVaultDialog.newInstance().show(parentFragmentManager, EvidenceVaultDialog.TAG)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
