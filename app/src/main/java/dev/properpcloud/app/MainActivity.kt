package dev.properpcloud.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                setText(R.string.app_name)
                textSize = 30f
            })
            addView(TextView(context).apply {
                setText(R.string.bootstrap_status)
                textSize = 17f
            })
        }

        setContentView(content)
    }
}
