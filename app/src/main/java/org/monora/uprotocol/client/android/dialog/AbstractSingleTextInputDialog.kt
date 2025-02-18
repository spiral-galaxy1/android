/*
 * Copyright (C) 2019 Veli Tasalı
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
package org.monora.uprotocol.client.android.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.monora.uprotocol.client.android.R

/**
 * created by: Veli
 * date: 26.02.2018 07:55
 */
abstract class AbstractSingleTextInputDialog(context: Context) : AbstractFailureAwareDialog(context) {
    private val containerView: ViewGroup = LayoutInflater.from(context).inflate(
        R.layout.layout_dialog_single_text_input, null
    ) as ViewGroup

    protected val editText: EditText = containerView.findViewById(R.id.layout_dialog_single_text_input_text)

    override fun show(): AlertDialog {
        setView(containerView)
        setNegativeButton(R.string.butn_close, null)

        val dialog = super.show()
        editText.requestFocus()
        return dialog
    }
}