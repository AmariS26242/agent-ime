package app.agentime

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = EndpointStore(this)
        val backend = findViewById<EditText>(R.id.editBackend)
        val token = findViewById<EditText>(R.id.editToken)
        backend.setText(prefs.backendUrl())
        token.setText(prefs.token())

        findViewById<Button>(R.id.btnOpenImeSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btnPicker).setOnClickListener {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showInputMethodPicker()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.setBackendUrl(backend.text.toString().trim())
            prefs.setToken(token.text.toString().trim())
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
    }
}
