package com.mnmyounus.ysos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EmergencyDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(contacts: List<EmergencyContact>)

    @Query("SELECT DISTINCT countryCode, countryName FROM emergency_contacts ORDER BY countryName ASC")
    fun getAllCountries(): Flow<List<CountryInfo>>

    @Query("SELECT * FROM emergency_contacts WHERE countryCode = :countryCode ORDER BY category, name")
    fun getContactsByCountry(countryCode: String): Flow<List<EmergencyContact>>

    @Query("SELECT * FROM emergency_contacts WHERE countryCode = :code AND category = :cat ORDER BY name")
    fun getContactsByCountryAndCategory(code: String, cat: String): Flow<List<EmergencyContact>>

    @Query("SELECT COUNT(*) FROM emergency_contacts")
    suspend fun getCount(): Int

    @Query("SELECT DISTINCT category FROM emergency_contacts WHERE countryCode = :countryCode ORDER BY category")
    fun getCategoriesForCountry(countryCode: String): Flow<List<String>>
}

data class CountryInfo(
    val countryCode: String,
    val countryName: String
)
