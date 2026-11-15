// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.wangzan101.mika.R
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Consent belongs to one failed session attempt and is never persisted. */
class OperatorOfflineActivity: Activity() {
    private var requestId:String?=null
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        requestId=intent.getStringExtra("request")
        if(!pending.containsKey(requestId)){finish();return}
        AlertDialog.Builder(this).setMessage(R.string.phone_operator_offline)
            .setPositiveButton(R.string.phone_operator_once){_,_->complete(true)}
            .setNegativeButton(android.R.string.cancel){_,_->complete(false)}
            .setOnCancelListener{complete(false)}.show()
    }
    private fun complete(accepted:Boolean){pending.remove(requestId)?.complete(accepted);finish()}
    override fun onDestroy(){if(!isChangingConfigurations)pending.remove(requestId)?.complete(false);super.onDestroy()}
    companion object {
        private val pending=ConcurrentHashMap<String,CompletableFuture<Boolean>>()
        fun ask(context:Context):Boolean {
            val id=UUID.randomUUID().toString();val reply=CompletableFuture<Boolean>();pending[id]=reply
            return try {
                context.startActivity(Intent(context,OperatorOfflineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("request",id))
                reply.get(120,TimeUnit.SECONDS)
            } catch(_:Exception){false} finally {pending.remove(id)}
        }
    }
}
