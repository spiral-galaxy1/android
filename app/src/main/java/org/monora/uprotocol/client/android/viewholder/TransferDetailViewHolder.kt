/*
 * Copyright (C) 2021 Veli Tasalı
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.monora.uprotocol.client.android.viewholder

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.monora.uprotocol.client.android.database.model.TransferDetail
import org.monora.uprotocol.client.android.databinding.ListTransferBinding
import org.monora.uprotocol.client.android.fragment.TransferHistoryAdapter.ClickType
import org.monora.uprotocol.client.android.viewmodel.content.TransferDetailContentViewModel

class TransferDetailViewHolder(private val binding: ListTransferBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(transferDetail: TransferDetail, clickListener: (TransferDetail, ClickType) -> Unit) {
        binding.viewModel = TransferDetailContentViewModel(transferDetail)
        binding.container.setOnClickListener {
            clickListener(transferDetail, ClickType.Default)
        }
        binding.rejectButton.setOnClickListener {
            clickListener(transferDetail, ClickType.Reject)
        }

        val toggleListener = View.OnClickListener {
            clickListener(transferDetail, ClickType.ToggleTask)
        }
        binding.acceptButton.setOnClickListener(toggleListener)
        binding.toggleButton.setOnClickListener(toggleListener)

        binding.executePendingBindings()
    }
}