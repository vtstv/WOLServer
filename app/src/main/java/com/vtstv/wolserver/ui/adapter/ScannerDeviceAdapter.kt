package com.vtstv.wolserver.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vtstv.wolserver.R
import com.vtstv.wolserver.data.model.DiscoveredDevice

/**
 * RecyclerView Adapter for LAN subnet discovered devices.
 */
class ScannerDeviceAdapter(
    private val items: List<DiscoveredDevice>,
    private val onAdd: (DiscoveredDevice) -> Unit
) : RecyclerView.Adapter<ScannerDeviceAdapter.ViewHolder>() {

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_scanned_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], onAdd)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textIcon: TextView = itemView.findViewById(R.id.textScannedIcon)
        private val textHost: TextView = itemView.findViewById(R.id.textScannedHostname)
        private val textIp: TextView = itemView.findViewById(R.id.textScannedIp)
        private val textMac: TextView = itemView.findViewById(R.id.textScannedMac)
        private val btnAdd: Button = itemView.findViewById(R.id.btnScannedAdd)

        fun bind(item: DiscoveredDevice, onAdd: (DiscoveredDevice) -> Unit) {
            textIcon.text = when (item.vendor) {
                "server" -> "🗄️"
                "laptop" -> "💻"
                "console" -> "🎮"
                "tv" -> "📺"
                else -> "🖥️"
            }
            textHost.text = item.hostname
            textIp.text = item.ip
            textMac.text = item.mac

            itemView.setOnFocusChangeListener { _, hasFocus ->
                val scale = if (hasFocus) 1.02f else 1.0f
                itemView.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            }

            btnAdd.setOnClickListener { onAdd(item) }
            itemView.setOnClickListener { onAdd(item) }
        }
    }
}
