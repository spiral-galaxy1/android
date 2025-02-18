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

package org.monora.uprotocol.client.android.viewmodel

import android.content.Context
import android.text.format.DateUtils
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import org.monora.uprotocol.client.android.data.SharedTextRepository
import org.monora.uprotocol.client.android.model.ContentModel
import org.monora.uprotocol.client.android.model.DateSectionContentModel
import javax.inject.Inject

@HiltViewModel
class SharedTextsViewModel @Inject internal constructor(
    @ApplicationContext context: Context,
    sharedTextRepository: SharedTextRepository,
) : ViewModel() {
    val sharedTexts = Transformations.switchMap(sharedTextRepository.getSharedTexts()) { list ->
        val newList = ArrayList<ContentModel>()
        var previous: DateSectionContentModel? = null

        list.forEach {
            val dateText = DateUtils.formatDateTime(context, it.created, DateUtils.FORMAT_SHOW_DATE)
            if (previous?.dateText != dateText) {
                newList.add(DateSectionContentModel(dateText, it.created).also { model -> previous = model })
            }
            newList.add(it)
        }

        MutableLiveData(newList)
    }
}