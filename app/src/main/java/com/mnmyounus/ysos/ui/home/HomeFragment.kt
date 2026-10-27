package com.mnmyounus.ysos.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.mnmyounus.ysos.R
import com.mnmyounus.ysos.YSOSApplication
import com.mnmyounus.ysos.databinding.FragmentHomeBinding
import com.mnmyounus.ysos.service.PanicForegroundService
import com.mnmyounus.ysos.service.ScreenRecordingService
import com.mnmyounus.ysos.util.PermissionHelper

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()
    private lateinit var prefs: com.mnmyounus.ysos.util.PreferencesManager

    // Permission request for camera/mic/notifications
    private val permissionLauncher: ActivityResultLauncher<Array<String>> = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            requestScreenCapture()
        } else {
            Snackbar.make(
                binding.root,
                getString(R.string.perm_camera_rationale),
                Snackbar.LENGTH_LONG
            ).setAction(R.string.grant_permission) {
                permissionLauncher.launch(PermissionHelper.PANIC_PERMISSIONS)
            }.show()
        }
    }

    // Screen capture permission result
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val screenIntent = result.data?.apply {
                putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, result.resultCode)
            }
            activatePanic(screenIntent)
        } else {
            // Start without screen recording
            activatePanic(null)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = (requireActivity().application as YSOSApplication).preferences

        // Update country label
        val country = prefs.selectedCountryName
        if (country.isNotEmpty()) {
            binding.tvSelectedCountry.text = country
        } else {
            binding.tvSelectedCountry.text = getString(R.string.select_country)
        }

        // First launch prompt
        if (prefs.isFirstLaunch || prefs.selectedCountryCode.isEmpty()) {
            prefs.isFirstLaunch = false
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.welcome_title)
                .setMessage(R.string.welcome_select_country)
                .setPositiveButton(R.string.welcome_get_started) { _, _ ->
                    findNavController().navigate(R.id.directoryFragment)
                }
                .setNegativeButton(R.string.dismiss, null)
                .show()
        }

        binding.btnViewDirectory.setOnClickListener {
            findNavController().navigate(R.id.directoryFragment)
        }

        binding.btnPanic.setOnClickListener {
            if (PanicForegroundService.isRunning) {
                stopPanic()
            } else {
                startPanicFlow()
            }
        }

        viewModel.isRecording.observe(viewLifecycleOwner) { isRecording ->
            updateRecordingUI(isRecording)
        }

        // Sync with service state on resume
        if (PanicForegroundService.isRunning != viewModel.isRecording.value) {
            viewModel.setRecording(PanicForegroundService.isRunning)
        }
    }

    private fun startPanicFlow() {
        if (!PermissionHelper.hasAllPermissions(requireContext(), PermissionHelper.PANIC_PERMISSIONS)) {
            permissionLauncher.launch(PermissionHelper.PANIC_PERMISSIONS)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val projMgr = requireContext()
            .getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projMgr.createScreenCaptureIntent())
    }

    private fun activatePanic(screenIntent: Intent?) {
        PanicForegroundService.startRecording(requireContext(), screenIntent)
        viewModel.setRecording(true)
    }

    private fun stopPanic() {
        PanicForegroundService.stopRecording(requireContext())
        viewModel.setRecording(false)
        Snackbar.make(binding.root, R.string.recording_stopped, Snackbar.LENGTH_SHORT).show()
    }

    private fun updateRecordingUI(isRecording: Boolean) {
        if (isRecording) {
            binding.btnPanic.text = getString(R.string.btn_panic_stop)
            binding.btnPanic.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), android.R.color.holo_red_dark)
            binding.layoutRecordingStatus.visibility = View.VISIBLE
            binding.viewPulseRing.visibility = View.VISIBLE
            binding.tvPanicHint.visibility = View.GONE
            // Blink animation on recording dot
            val blink = AnimationUtils.loadAnimation(requireContext(), R.anim.blink)
            binding.viewRecordingDot.startAnimation(blink)
        } else {
            binding.btnPanic.text = getString(R.string.btn_panic_activate)
            binding.btnPanic.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.panic_red)
            binding.layoutRecordingStatus.visibility = View.GONE
            binding.viewPulseRing.visibility = View.GONE
            binding.tvPanicHint.visibility = View.VISIBLE
            binding.viewRecordingDot.clearAnimation()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
