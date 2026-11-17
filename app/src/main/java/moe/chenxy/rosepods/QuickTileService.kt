package moe.chenxy.rosepods

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.quicksettings.TileService
import moe.chenxy.rosepods.core.PodsContract
import moe.chenxy.rosepods.core.PodsState

/**
 * 快捷设置磁贴：不开 App 也能切降噪模式。
 *
 * 点击 = 让系统侧按「深度降噪 → 通透 → 普通」循环切换；
 * 磁贴标题跟随系统侧回报的模式更新。
 */
class QuickTileService : TileService() {

    private var receiver: BroadcastReceiver? = null

    override fun onStartListening() {
        super.onStartListening()
        registerStateReceiver()
        sendBroadcast(Intent(PodsContract.ACTION_STATE_REQ).setPackage(PodsContract.BT_PKG))
    }

    override fun onStopListening() {
        unregisterStateReceiver()
        super.onStopListening()
    }

    override fun onClick() {
        sendBroadcast(
            Intent(PodsContract.ACTION_CMD)
                .setPackage(PodsContract.BT_PKG)
                .putExtra(PodsContract.EXT_CMD, PodsContract.CMD_CYCLE)
        )
        updateTile(PodsState(), pending = true)
    }

    private fun registerStateReceiver() {
        if (receiver != null) return
        val rx = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != PodsContract.ACTION_STATE) return
                val state = PodsState(
                    connected = intent.getBooleanExtra(PodsContract.EXT_CONNECTED, false),
                    mode = intent.getIntExtra(PodsContract.EXT_MODE, 0),
                    systemOwned = true,
                )
                updateTile(state)
            }
        }
        receiver = rx
        val filter = IntentFilter(PodsContract.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(rx, filter)
        }
    }

    private fun unregisterStateReceiver() {
        val rx = receiver ?: return
        receiver = null
        runCatching { unregisterReceiver(rx) }
    }

    private fun updateTile(state: PodsState, pending: Boolean = false) {
        val tile = qsTile ?: return
        tile.label = when {
            pending -> "RosePods 切换中"
            state.connected -> "RosePods · ${PodsContract.modeName(state.mode)}"
            else -> "RosePods"
        }
        tile.state = if (state.connected) android.service.quicksettings.Tile.STATE_ACTIVE
        else android.service.quicksettings.Tile.STATE_INACTIVE
        tile.updateTile()
    }
}