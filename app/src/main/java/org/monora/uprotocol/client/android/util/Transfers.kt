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

package org.monora.uprotocol.client.android.util

import com.genonbeta.android.framework.io.DocumentFile
import org.monora.uprotocol.client.android.database.model.UTransferItem
import org.monora.uprotocol.core.transfer.TransferItem
import java.io.File

/**
 * created by: veli
 * date: 06.04.2018 17:01
 */
object Transfers {
    fun createStructure(
        list: MutableList<UTransferItem>,
        progress: Progress,
        groupId: Long,
        contextFile: DocumentFile,
        directory: String? = null,
        progressCallback: (progress: Progress, file: DocumentFile) -> Unit
    ) {
        if (contextFile.isFile()) {
            progress.index += 1
            progressCallback(progress, contextFile)

            val id = progress.index.toLong() // With 'groupId', this will become unique (enough).

            list.add(
                UTransferItem(
                    id,
                    groupId,
                    contextFile.getName(),
                    contextFile.getType(),
                    contextFile.getLength(),
                    directory,
                    contextFile.getUri().toString(),
                    TransferItem.Type.Outgoing,
                )
            )
        } else if (!contextFile.isDirectory()) {
            return
        }

        val files = contextFile.listFiles()
        progress.total += files.size
        progressCallback(progress, contextFile)

        for (file in files) {
            createStructure(
                list,
                progress,
                groupId,
                file,
                directory?.let {
                    it + File.separator + file.getName()
                },
                progressCallback,
            )
        }
    }
}

data class Progress(var total: Int, var index: Int = 0)