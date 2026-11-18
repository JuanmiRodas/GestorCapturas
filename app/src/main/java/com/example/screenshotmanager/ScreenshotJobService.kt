package com.example.screenshotmanager

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore
import kotlin.concurrent.thread

/**
 * Android itself starts this job when a new image appears, even after the system (OxygenOS
 * especially) has killed the app, and it notifies the new screenshots.
 */
class ScreenshotJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        AppLog.i("Job started (new image)")
        thread {
            NewScreenshots.check(this)
            jobFinished(params, false)
            // A content-triggered job fires once, so it has to be scheduled again every time;
            // a second check covers a screenshot that landed while it wasn't scheduled.
            schedule(this)
            NewScreenshots.check(this)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        AppLog.i("Job stopped by the system")
        return true
    }

    companion object {
        const val JOB_ID = 1001

        fun schedule(context: Context) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ScreenshotJobService::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    )
                )
                .setTriggerContentUpdateDelay(500)
                .setTriggerContentMaxDelay(3000)
                .build()
            val result = context.getSystemService(JobScheduler::class.java).schedule(job)
            if (result != JobScheduler.RESULT_SUCCESS) AppLog.e("Job could not be scheduled ($result)")
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        }
    }
}
