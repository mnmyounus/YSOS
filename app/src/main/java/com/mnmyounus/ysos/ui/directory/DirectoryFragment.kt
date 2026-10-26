package com.mnmyounus.ysos.ui.directory

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.mnmyounus.ysos.R
import com.mnmyounus.ysos.YSOSApplication
import com.mnmyounus.ysos.data.CountryInfo
import com.mnmyounus.ysos.data.EmergencyContact
import com.mnmyounus.ysos.databinding.FragmentDirectoryBinding
import com.mnmyounus.ysos.util.PermissionHelper

class DirectoryFragment : Fragment() {

    private var _binding: FragmentDirectoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DirectoryViewModel by viewModels()
    private lateinit var contactAdapter: EmergencyContactAdapter
    private var countryList: List<CountryInfo> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDirectoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        contactAdapter = EmergencyContactAdapter(
            onCallClick = { contact -> dialNumber(contact.phoneNumber) },
            onEmailClick = { contact -> sendEmail(contact.email) }
        )

        binding.rvContacts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = contactAdapter
        }

        // Load saved country from prefs
        val prefs = (requireActivity().application as YSOSApplication).preferences
        val savedCode = prefs.selectedCountryCode

        viewModel.countries.observe(viewLifecycleOwner) { countries ->
            countryList = countries
            val names = countries.map { it.countryName }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
            binding.actvCountry.setAdapter(adapter)

            // Restore previously selected country
            if (savedCode.isNotEmpty()) {
                val saved = countries.firstOrNull { it.countryCode == savedCode }
                if (saved != null) {
                    binding.actvCountry.setText(saved.countryName, false)
                    viewModel.selectCountry(savedCode)
                }
            }
        }

        binding.actvCountry.setOnItemClickListener { _, _, position, _ ->
            val selected = countryList[position]
            prefs.selectedCountryCode = selected.countryCode
            prefs.selectedCountryName = selected.countryName
            viewModel.selectCountry(selected.countryCode)
        }

        viewModel.categories.observe(viewLifecycleOwner) { categories ->
            binding.tabLayout.removeAllTabs()
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText("All"))
            categories.forEach { cat ->
                binding.tabLayout.addTab(
                    binding.tabLayout.newTab().setText(categoryLabel(cat))
                )
            }
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val pos = tab?.position ?: 0
                if (pos == 0) {
                    viewModel.selectCategory("")
                } else {
                    val cats = viewModel.categories.value ?: return
                    val idx = pos - 1
                    if (idx < cats.size) viewModel.selectCategory(cats[idx])
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        viewModel.contacts.observe(viewLifecycleOwner) { contacts ->
            contactAdapter.submitList(contacts)
            binding.tvEmpty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
            binding.rvContacts.visibility = if (contacts.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun dialNumber(number: String) {
        if (!PermissionHelper.hasCallPermission(requireContext())) {
            requestPermissions(PermissionHelper.CALL_PERMISSIONS, 42)
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")))
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(binding.root, R.string.error_no_call_app, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun sendEmail(email: String) {
        if (email.isBlank()) return
        try {
            startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                    .apply { putExtra(Intent.EXTRA_SUBJECT, "Emergency Assistance Request") }
            )
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(binding.root, R.string.error_no_email_app, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun categoryLabel(cat: String): String = when (cat) {
        "police"    -> getString(R.string.category_police)
        "ambulance" -> getString(R.string.category_ambulance)
        "fire"      -> getString(R.string.category_fire)
        "disaster"  -> getString(R.string.category_disaster)
        else        -> cat.replaceFirstChar { it.uppercase() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
