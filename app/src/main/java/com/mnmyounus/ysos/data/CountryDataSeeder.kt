package com.mnmyounus.ysos.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

/**
 * Seeds the Room database from the bundled emergency_data.json asset.
 * Called once when the database is first created or found empty.
 */
object CountryDataSeeder {

    suspend fun seed(context: Context, dao: EmergencyDao) {
        try {
            val inputStream = context.assets.open("emergency_data.json")
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<List<EmergencyContactJson>>() {}.type
            val jsonContacts: List<EmergencyContactJson> = Gson().fromJson(reader, type)
            reader.close()
            dao.insertAll(jsonContacts.map { it.toEntity() })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private data class EmergencyContactJson(
        val countryCode: String,
        val countryName: String,
        val category: String,
        val name: String,
        val phoneNumber: String,
        val email: String = "",
        val notes: String = ""
    ) {
        fun toEntity() = EmergencyContact(
            countryCode = countryCode,
            countryName = countryName,
            category = category,
            name = name,
            phoneNumber = phoneNumber,
            email = email,
            notes = notes
        )
    }
}
