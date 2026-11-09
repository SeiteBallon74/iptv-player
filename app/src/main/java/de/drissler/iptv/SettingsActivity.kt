package de.drissler.iptv

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val url = findViewById<EditText>(R.id.url)
        val ua = findViewById<EditText>(R.id.ua)
        val buffer = findViewById<RadioGroup>(R.id.buffer)

        url.setText(Prefs.url(this))
        ua.setText(Prefs.rawUserAgent(this))
        ua.hint = Prefs.DEFAULT_UA
        buffer.check(
            when (Prefs.bufferMode(this)) {
                0 -> R.id.bufFast
                2 -> R.id.bufStable
                else -> R.id.bufNormal
            }
        )

        findViewById<Button>(R.id.save).setOnClickListener {
            val u = url.text.toString().trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                Toast.makeText(this, "Link muss mit http:// oder https:// beginnen", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val mode = when (buffer.checkedRadioButtonId) {
                R.id.bufFast -> 0
                R.id.bufStable -> 2
                else -> 1
            }
            Prefs.save(this, u, ua.text.toString().trim(), mode)
            finish()
        }
        url.requestFocus()
    }
}
