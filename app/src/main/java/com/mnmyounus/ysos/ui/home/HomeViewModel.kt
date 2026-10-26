package com.mnmyounus.ysos.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.mnmyounus.ysos.service.PanicForegroundService

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _isRecording = MutableLiveData(PanicForegroundService.isRunning)
    val isRecording: LiveData<Boolean> = _isRecording

    fun setRecording(active: Boolean) {
        _isRecording.value = active
    }
}
