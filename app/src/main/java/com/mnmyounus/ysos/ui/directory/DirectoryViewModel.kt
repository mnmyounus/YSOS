package com.mnmyounus.ysos.ui.directory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import com.mnmyounus.ysos.data.CountryInfo
import com.mnmyounus.ysos.data.EmergencyContact
import com.mnmyounus.ysos.data.EmergencyDatabase

class DirectoryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = EmergencyDatabase.getDatabase(application).emergencyDao()

    val countries: LiveData<List<CountryInfo>> = dao.getAllCountries().asLiveData()

    private val _selectedCountryCode = MutableLiveData<String>()
    val selectedCountryCode: LiveData<String> = _selectedCountryCode

    private val _selectedCategory = MutableLiveData<String>()

    val categories: LiveData<List<String>> = _selectedCountryCode.switchMap { code ->
        dao.getCategoriesForCountry(code).asLiveData()
    }

    val contacts: LiveData<List<EmergencyContact>> = _selectedCategory.switchMap { cat ->
        val code = _selectedCountryCode.value ?: ""
        if (cat.isEmpty()) {
            dao.getContactsByCountry(code).asLiveData()
        } else {
            dao.getContactsByCountryAndCategory(code, cat).asLiveData()
        }
    }

    fun selectCountry(code: String) {
        _selectedCountryCode.value = code
        _selectedCategory.value = ""
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
    }
}
