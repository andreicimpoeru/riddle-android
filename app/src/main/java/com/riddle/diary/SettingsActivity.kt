package com.riddle.diary

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.riddle.diary.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var store: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = SettingsStore(this)
        binding.apiKeyInput.setText(store.apiKey)
        binding.apiBaseInput.setText(store.apiBase)
        binding.apiModelInput.setText(store.apiModel)

        binding.saveButton.setOnClickListener {
            store.apiKey = binding.apiKeyInput.text?.toString().orEmpty()
            store.apiBase = binding.apiBaseInput.text?.toString().orEmpty()
            store.apiModel = binding.apiModelInput.text?.toString().orEmpty()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }

        binding.openDiaryButton.setOnClickListener {
            if (!store.isConfigured()) {
                Toast.makeText(this, R.string.no_api_key, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, RiddleActivity::class.java))
        }
    }
}