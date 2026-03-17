package com.example.windtouch // <--- DHYAAN DE: Ye line apne project ke hisab se rakhna!

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- BUTTON 1: DISPLAY OVER OTHER APPS ---
        val btnOverlay = findViewById<Button>(R.id.btn_permission)
        btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                // User ko permission page par bhejo
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay Permission Already Granted!", Toast.LENGTH_SHORT).show()
            }
        }

        // --- BUTTON 2: CAMERA PERMISSION (Ye wala error de raha tha, ab theek hai) ---
        val btnCamera = findViewById<Button>(R.id.btn_camera_permission)
        btnCamera.setOnClickListener {
            // Check karo permission hai ya nahi
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                // Agar nahi hai, toh maango
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            } else {
                Toast.makeText(this, "Camera Permission Already Granted!", Toast.LENGTH_SHORT).show()
            }
        }

        // --- BUTTON 3: ACCESSIBILITY SERVICE ---
        val btnAccessibility = findViewById<Button>(R.id.btn_accessibility)
        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }
}