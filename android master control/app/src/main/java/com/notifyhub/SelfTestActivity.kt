package com.notifyhub

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SelfTestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TypedTextService.sendSelfTest(this)
        AppStatusReporter.report(this, force = true)
        Toast.makeText(this, "Typed self-test queued — check dashboard within 5 seconds", Toast.LENGTH_LONG).show()
        finish()
    }
}
