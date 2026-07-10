package io.github.sensorprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** ADB-only command bridge included in debug builds, never in release APKs. */
class DebugCommandReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val command=intent.getStringExtra(EXTRA_COMMAND)?:return
        context.startActivity(Intent(context,MainActivity::class.java).apply{
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_COMMAND,command)
        })
    }
    companion object{
        const val ACTION="io.github.sensorprobe.DEBUG_COMMAND"
        const val EXTRA_COMMAND="command"
    }
}
