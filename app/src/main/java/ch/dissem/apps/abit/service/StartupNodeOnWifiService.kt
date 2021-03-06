package ch.dissem.apps.abit.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import android.support.annotation.RequiresApi
import ch.dissem.apps.abit.util.NetworkUtils
import ch.dissem.apps.abit.util.Preferences

/**
 * Starts the full node if
 * * it is active
 * * it is not already running
 *
 * And stops it when the preconditions for the job (unmetered network) aren't met anymore.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class StartupNodeOnWifiService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val bmc = Singleton.getBitmessageContext(this)
        if (Preferences.isFullNodeActive(this) && !bmc.isRunning()) {
            NetworkUtils.doStartBitmessageService(applicationContext)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?) = if (Preferences.isWifiOnly(this)) {
        // Don't actually stop the service, otherwise it will be stopped after 1 or 10 minutes
        // depending on Android version.
        Preferences.isFullNodeActive(this)
    } else {
        false
    }
}
