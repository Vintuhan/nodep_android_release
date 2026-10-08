package com.nodep.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class BlockActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_block)
        findViewById<TextView>(R.id.block_detail).text =
            intent.getStringExtra(EXTRA_DETAIL) ?: ""
        findViewById<Button>(R.id.btn_home).setOnClickListener {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(home)
            finish()
        }
    }

    companion object {
        const val EXTRA_DETAIL = "detail"
    }
}
