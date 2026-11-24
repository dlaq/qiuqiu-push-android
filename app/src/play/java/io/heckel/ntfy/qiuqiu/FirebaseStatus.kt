package io.heckel.ntfy.qiuqiu

import android.content.Context
import com.google.firebase.FirebaseApp

object FirebaseStatus {
    fun ready(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()
}
