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

package org.monora.uprotocol.client.android.protocol

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import com.genonbeta.android.framework.io.StreamInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import org.monora.uprotocol.client.android.config.AppConfig
import org.monora.uprotocol.client.android.data.ClientRepository
import org.monora.uprotocol.client.android.data.TransferRepository
import org.monora.uprotocol.client.android.data.UserDataRepository
import org.monora.uprotocol.client.android.database.model.UClient
import org.monora.uprotocol.client.android.database.model.UClientAddress
import org.monora.uprotocol.client.android.database.model.UTransferItem
import org.monora.uprotocol.client.android.io.DocumentFileStreamDescriptor
import org.monora.uprotocol.client.android.io.StreamInfoStreamDescriptor
import org.monora.uprotocol.client.android.util.Files
import org.monora.uprotocol.client.android.util.Graphics
import org.monora.uprotocol.core.io.StreamDescriptor
import org.monora.uprotocol.core.persistence.PersistenceException
import org.monora.uprotocol.core.persistence.PersistenceProvider
import org.monora.uprotocol.core.protocol.Client
import org.monora.uprotocol.core.protocol.ClientAddress
import org.monora.uprotocol.core.protocol.ClientType
import org.monora.uprotocol.core.transfer.TransferItem
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.inject.Inject

class MainPersistenceProvider @Inject constructor(
    @ApplicationContext val context: Context,
    private val clientRepository: ClientRepository,
    private val userDataRepository: UserDataRepository,
    private val transferRepository: TransferRepository,
) : PersistenceProvider {
    override fun approveInvalidationOfCredentials(client: Client): Boolean {
        check(client is UClient) {
            "Unexpected implementation type"
        }

        return false
    }

    override fun containsTransfer(groupId: Long): Boolean = runBlocking {
        transferRepository.containsTransfer(groupId)
    }

    override fun createClientAddressFor(address: InetAddress, clientUid: String) = UClientAddress(
        address, clientUid, System.currentTimeMillis()
    )

    override fun createClientFor(
        uid: String,
        nickname: String,
        manufacturer: String,
        product: String,
        type: ClientType,
        versionName: String,
        versionCode: Int,
        protocolVersion: Int,
        protocolVersionMin: Int,
    ) = UClient(
        uid, nickname, manufacturer, product, type, versionName, versionCode, protocolVersion, protocolVersionMin,
    )

    override fun createTransferItemFor(
        groupId: Long,
        id: Long,
        name: String,
        mimeType: String,
        size: Long,
        directory: String?,
        type: TransferItem.Type,
    ): TransferItem = UTransferItem(id, groupId, name, mimeType, size, directory, uniqueFileName(), type)

    override fun getCertificate(): X509Certificate = userDataRepository.certificate

    override fun getClient(): UClient = userDataRepository.clientStatic()

    override fun getClientFor(uid: String): UClient? = runBlocking { clientRepository.getDirect(uid) }

    override fun getClientNickname(): String = userDataRepository.clientNicknameStatic()

    override fun getClientUid(): String = userDataRepository.clientUid()

    override fun getDescriptorFor(transferItem: TransferItem): StreamDescriptor {
        check(transferItem is UTransferItem) {
            "Unknown item type"
        }

        // TODO: 7/19/21 Cache the 'Transfer' instance
        val transfer = runBlocking {
            transferRepository.getTransfer(transferItem.groupId) ?: throw IOException()
        }

        return if (transferItem.itemType == TransferItem.Type.Incoming) {
            DocumentFileStreamDescriptor(Files.getIncomingFile(context, transferItem, transfer))
        } else {
            StreamInfoStreamDescriptor(StreamInfo.from(context, Uri.parse(transferItem.location)))
        }
    }

    override fun getFirstReceivableItem(groupId: Long) = runBlocking {
        transferRepository.getReceivable(groupId)
    }

    override fun getNetworkPin(): Int {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        var pin = sharedPreferences.getInt("pin", 0)

        if (pin == 0) {
            pin = SecureRandom().nextInt()
            sharedPreferences.edit()
                .putInt("pin", pin)
                .apply()
        }

        return pin
    }

    override fun getPrivateKey(): PrivateKey = userDataRepository.keyFactory.generatePrivate(
        PKCS8EncodedKeySpec(userDataRepository.keyPair.private.encoded)
    )

    override fun getPublicKey(): PublicKey = userDataRepository.keyFactory.generatePublic(
        X509EncodedKeySpec(userDataRepository.keyPair.public.encoded)
    )

    override fun hasRequestForInvalidationOfCredentials(clientUid: String): Boolean {
        Log.d(TAG, "hasRequestForInvalidationOfCredentials: $clientUid")
        return false
    }

    override fun loadTransferItem(
        clientUid: String,
        groupId: Long,
        id: Long,
        type: TransferItem.Type,
    ): TransferItem = runBlocking {
        transferRepository.getTransferItem(groupId, id, type) ?: throw PersistenceException("Item does not exist")
    }

    override fun openInputStream(descriptor: StreamDescriptor): InputStream {
        if (descriptor is StreamInfoStreamDescriptor) {
            return descriptor.streamInfo.openInputStream(context)
        } else if (descriptor is DocumentFileStreamDescriptor) {
            return context.contentResolver.openInputStream(descriptor.documentFile.getUri()) ?: throw IOException(
                "Supported resource did not open"
            )
        }

        throw RuntimeException("Unsupported descriptor.")
    }

    override fun openOutputStream(descriptor: StreamDescriptor): OutputStream {
        if (descriptor is StreamInfoStreamDescriptor) {
            return descriptor.streamInfo.openOutputStream(context)
        } else if (descriptor is DocumentFileStreamDescriptor) {
            return context.contentResolver.openOutputStream(descriptor.documentFile.getUri()) ?: throw IOException(
                "Supported resource did not open"
            )
        }

        throw RuntimeException("Unsupported descriptor.")
    }

    override fun persist(client: Client, updating: Boolean) {
        if (client is UClient) runBlocking {
            if (updating) {
                clientRepository.update(client)
            } else {
                clientRepository.insert(client)
            }
        } else {
            throw UnsupportedOperationException()
        }
    }

    override fun persist(clientAddress: ClientAddress) {
        if (clientAddress is UClientAddress) runBlocking {
            clientRepository.insert(clientAddress)
        } else {
            throw UnsupportedOperationException()
        }
    }

    override fun persist(clientUid: String, item: TransferItem) {
        if (item is UTransferItem) runBlocking {
            transferRepository.update(item)
        } else {
            throw UnsupportedOperationException()
        }
    }

    override fun persist(clientUid: String, itemList: MutableList<out TransferItem>) {
        if (itemList.isEmpty()) return

        runBlocking {
            transferRepository.insert(itemList.filterIsInstance(UTransferItem::class.java))
        }
    }

    override fun persistClientPicture(client: Client, data: ByteArray?, checksum: Int) = runBlocking {
        Graphics.saveClientPicture(context, clientRepository, client, data, checksum)
    }

    override fun revokeNetworkPin() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt("pin", 0)
            .apply()
    }

    override fun saveRequestForInvalidationOfCredentials(clientUid: String) {
        Log.d(TAG, "saveRequestForInvalidationOfCredentials: $clientUid")
    }

    override fun setState(clientUid: String, item: TransferItem, state: TransferItem.State, e: Exception?) {
        if (item is UTransferItem) {
            item.state = state
        } else {
            throw UnsupportedOperationException()
        }
    }

    companion object {
        private val TAG = MainPersistenceProvider::class.simpleName
    }
}

fun uniqueFileName() = ".${UUID.randomUUID()}.${AppConfig.EXT_FILE_PART}"