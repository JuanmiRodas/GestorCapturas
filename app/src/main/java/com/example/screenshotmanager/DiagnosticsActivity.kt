package com.example.screenshotmanager

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import kotlin.concurrent.thread

/** Shows the diagnostics report (state, permissions, pCloud session and log) and shares it. */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var report: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        findViewById<View>(R.id.root).padForSystemBars()
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        report = findViewById(R.id.report)

        findViewById<View>(R.id.shareReport).setOnClickListener {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_title))
                        .putExtra(Intent.EXTRA_TEXT, report.text.toString()),
                    getString(R.string.diagnostics_share),
                )
            )
        }
        findViewById<View>(R.id.clearLog).setOnClickListener {
            AppLog.clear()
            refresh()
        }
        refresh()
    }

    private fun refresh() {
        report.setText(R.string.diagnostics_loading)
        thread {
            val text = Diagnostics.report(applicationContext)
            runOnUiThread { if (!isFinishing) report.text = text }
        }
    }
}
