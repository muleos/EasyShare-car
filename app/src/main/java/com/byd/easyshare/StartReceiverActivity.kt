package com.byd.easyshare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.byd.easyshare.utils.checkBluetoothPermissions
import com.byd.easyshare.utils.checkNotificationPermission
import com.byd.easyshare.utils.checkP2pPermissions

class StartReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val enabled = !intent.getBooleanExtra("shouldStop", false)
        MyApplication.getInstance().setBackgroundReceiveEnabled(enabled)
        if (enabled && (!checkBluetoothPermissions() || !checkP2pPermissions() ||
                !checkNotificationPermission())) {
            Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_LONG).show()
        }

        finish()
    }

    companion object {
        fun getIntent(context: Context, shouldStop: Boolean): Intent {
            return Intent(context, StartReceiverActivity::class.java).apply {
                putExtra("shouldStop", shouldStop)
            }
        }
    }
}
