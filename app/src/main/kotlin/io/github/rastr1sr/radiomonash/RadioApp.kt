package io.github.rastr1sr.radiomonash

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import java.io.File

class RadioApp : Application() {
    internal val preferences by lazy { Preferences(this) }
    internal val library by lazy { Library(this) }
    internal val reminders by lazy { Reminders(this) }
    internal val shows by lazy { Shows(this, reminders) }
    internal val chat by lazy { Chat(this) }
    internal val artwork get() = File(cacheDir, "http")

    override fun onCreate() {
        super.onCreate()
        installCache(artwork)
    }
}

internal fun Context.radio() = applicationContext as RadioApp

internal fun CreationExtras.radio() = checkNotNull(this[APPLICATION_KEY]) as RadioApp
