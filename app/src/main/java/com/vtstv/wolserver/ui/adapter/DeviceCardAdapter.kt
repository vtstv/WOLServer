/**
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
package com.vtstv.wolserver.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vtstv.wolserver.R
import com.vtstv.wolserver.core.engine.DevicePinger
import com.vtstv.wolserver.data.model.WolDevice

/**
 * 10-Foot Leanback RecyclerView Adapter for TV device cards.
 * Provides D-Pad focus scaling, glowing stroke, and action triggers.
 */
class DeviceCardAdapter(
    private val devices: List<WolDevice>,
    private val getStatus: (String) -> DevicePinger.PingResult?,
    private val onWake: (WolDevice) -> Unit,
    private val onEdit: (WolDevice) -> Unit,
    private val onDelete: (WolDevice) -> Unit,
    private val onAddClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_DEVICE = 0
        private const val TYPE_ADD = 1
    }

    override fun getItemCount(): Int = devices.size + 1

    override fun getItemViewType(position: Int): Int {
        return if (position < devices.size) TYPE_DEVICE else TYPE_ADD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_DEVICE) {
            val view = inflater.inflate(R.layout.item_device_card, parent, false)
            DeviceViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_add_device, parent, false)
            AddViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is DeviceViewHolder) {
            val device = devices[position]
            holder.bind(device, getStatus(device.id), onWake, onEdit, onDelete)
        } else if (holder is AddViewHolder) {
            holder.bind(onAddClick)
        }
    }

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textIcon: TextView = itemView.findViewById(R.id.textDeviceIcon)
        private val textStatus: TextView = itemView.findViewById(R.id.textDeviceStatus)
        private val textName: TextView = itemView.findViewById(R.id.textDeviceName)
        private val textMac: TextView = itemView.findViewById(R.id.textDeviceMac)
        private val textBroadcast: TextView = itemView.findViewById(R.id.textDeviceBroadcast)
        private val btnWake: Button = itemView.findViewById(R.id.btnWakeDevice)
        private val btnEdit: Button = itemView.findViewById(R.id.btnEditDevice)
        private val btnDelete: Button = itemView.findViewById(R.id.btnDeleteDevice)

        fun bind(
            device: WolDevice,
            status: DevicePinger.PingResult?,
            onWake: (WolDevice) -> Unit,
            onEdit: (WolDevice) -> Unit,
            onDelete: (WolDevice) -> Unit
        ) {
            val iconEmoji = when (device.iconType) {
                "server" -> "🗄️"
                "laptop" -> "💻"
                "console" -> "🎮"
                "tv" -> "📺"
                else -> "🖥️"
            }

            textIcon.text = iconEmoji
            textName.text = device.name
            textMac.text = device.macAddress
            textBroadcast.text = "${device.broadcastAddress}:${device.port}"

            if (status != null && status.isOnline) {
                textStatus.text = itemView.context.getString(R.string.status_online, status.latencyMs)
                textStatus.setTextColor(Color.parseColor("#00E676"))
            } else {
                textStatus.text = itemView.context.getString(R.string.status_offline)
                textStatus.setTextColor(Color.parseColor("#94A3B8"))
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                val scale = if (hasFocus) 1.04f else 1.0f
                itemView.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            }

            itemView.setOnClickListener { onWake(device) }
            btnWake.setOnClickListener { onWake(device) }
            btnEdit.setOnClickListener { onEdit(device) }
            btnDelete.setOnClickListener { onDelete(device) }
        }
    }

    class AddViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(onAddClick: () -> Unit) {
            itemView.setOnFocusChangeListener { _, hasFocus ->
                val scale = if (hasFocus) 1.04f else 1.0f
                itemView.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            }
            itemView.setOnClickListener { onAddClick() }
        }
    }
}
