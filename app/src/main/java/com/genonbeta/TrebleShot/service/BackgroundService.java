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

package com.genonbeta.TrebleShot.service;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.genonbeta.TrebleShot.R;
import com.genonbeta.TrebleShot.app.Service;
import com.genonbeta.TrebleShot.config.AppConfig;
import com.genonbeta.TrebleShot.config.Keyword;
import com.genonbeta.TrebleShot.database.Kuick;
import com.genonbeta.TrebleShot.object.*;
import com.genonbeta.TrebleShot.protocol.DeviceBlockedException;
import com.genonbeta.TrebleShot.protocol.DeviceInsecureException;
import com.genonbeta.TrebleShot.service.backgroundservice.BackgroundTask;
import com.genonbeta.TrebleShot.task.FileTransferTask;
import com.genonbeta.TrebleShot.task.IndexTransferTask;
import com.genonbeta.TrebleShot.util.*;
import com.genonbeta.TrebleShot.util.communicationbridge.DifferentClientException;
import com.genonbeta.android.database.SQLQuery;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONException;
import org.json.JSONObject;
import org.monora.coolsocket.core.CoolSocket;
import org.monora.coolsocket.core.session.ActiveConnection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BackgroundService extends Service
{
    public static final String TAG = BackgroundService.class.getSimpleName();

    public static final String
            ACTION_CLIPBOARD = "com.genonbeta.TrebleShot.action.CLIPBOARD",
            ACTION_DEVICE_ACQUAINTANCE = "com.genonbeta.TrebleShot.transaction.action.DEVICE_ACQUAINTANCE",
            ACTION_DEVICE_APPROVAL = "com.genonbeta.TrebleShot.action.DEVICE_APPROVAL",
            ACTION_END_SESSION = "com.genonbeta.TrebleShot.action.END_SESSION",
            ACTION_FILE_TRANSFER = "com.genonbeta.TrebleShot.action.FILE_TRANSFER",
            ACTION_INCOMING_TRANSFER_READY = "com.genonbeta.TrebleShot.transaction.action.INCOMING_TRANSFER_READY",
            ACTION_KILL_SIGNAL = "com.genonbeta.intent.action.KILL_SIGNAL",
            ACTION_PIN_USED = "com.genonbeta.TrebleShot.transaction.action.PIN_USED",
            ACTION_START_TRANSFER = "com.genonbeta.intent.action.START_TRANSFER",
            ACTION_STOP_TASK = "com.genonbeta.TrebleShot.transaction.action.CANCEL_JOB",
            ACTION_TASK_CHANGE = "com.genonbeta.TrebleShot.transaction.action.TASK_STATUS_CHANGE", // FIXME: only the parent activity should listen to this
            EXTRA_CLIPBOARD_ACCEPTED = "extraClipboardAccepted",
            EXTRA_CLIPBOARD_ID = "extraTextId",
            EXTRA_CONNECTION = "extraConnectionAdapterName",
            EXTRA_DEVICE = "extraDevice",
            EXTRA_DEVICE_PIN = "extraDevicePin",
            EXTRA_GROUP = "extraGroup",
            EXTRA_IDENTITY = "extraIdentity",
            EXTRA_ACCEPTED = "extraAccepted",
            EXTRA_REQUEST_ID = "extraRequest",
            EXTRA_TRANSFER_TYPE = "extraTransferType";

    private final List<BackgroundTask> mTaskList = new ArrayList<>();
    private final CommunicationServer mCommunicationServer = new CommunicationServer();
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(10);
    private final LocalBinder mBinder = new LocalBinder();
    private WebShareServer mWebShareServer;
    private NsdDiscovery mNsdDiscovery;
    private NotificationHelper mNotificationHelper;
    private WifiManager.WifiLock mWifiLock;
    private MediaScannerConnection mMediaScanner;
    private HotspotManager mHotspotManager;

    @Override
    public IBinder onBind(Intent intent)
    {
        return mBinder;
    }

    @Override
    public void onCreate()
    {
        super.onCreate();

        WifiManager wifiManager = ((WifiManager) getApplicationContext().getSystemService(Service.WIFI_SERVICE));

        mWebShareServer = new WebShareServer(this, AppConfig.SERVER_PORT_WEBSHARE);
        mNotificationHelper = new NotificationHelper(getNotificationUtils());
        mNsdDiscovery = new NsdDiscovery(getApplicationContext(), getKuick(), getDefaultPreferences());
        mMediaScanner = new MediaScannerConnection(this, null);
        mHotspotManager = HotspotManager.newInstance(this);

        if (wifiManager != null)
            mWifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG);

        mMediaScanner.connect();
        mNsdDiscovery.registerService();

        if (mWifiLock != null)
            mWifiLock.acquire();

        tryStartingServices();
        takeForeground(true);
    }

    private void takeForeground(boolean take)
    {
        if (take)
            startForeground(NotificationHelper.ID_BG_SERVICE, getNotificationHelper().getForegroundNotification().build());
        else
            stopForeground(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, final int startId)
    {
        super.onStartCommand(intent, flags, startId);

        if (intent != null)
            Log.d(TAG, "onStart() : action = " + intent.getAction());

        if (intent != null && AppUtils.checkRunningConditions(this)) {
            if (ACTION_FILE_TRANSFER.equals(intent.getAction())) {
                Device device = intent.getParcelableExtra(EXTRA_DEVICE);
                TransferGroup group = intent.getParcelableExtra(EXTRA_GROUP);
                final int notificationId = intent.getIntExtra(NotificationUtils.EXTRA_NOTIFICATION_ID, -1);
                final boolean isAccepted = intent.getBooleanExtra(EXTRA_ACCEPTED, false);

                getNotificationHelper().getUtils().cancel(notificationId);

                try {
                    if (device == null || group == null)
                        throw new Exception("The device or group instance is broken");

                    FileTransferTask task = FileTransferTask.createFrom(getKuick(), group, device,
                            TransferObject.Type.INCOMING);

                    new Thread(() -> {
                        try (CommunicationBridge bridge = CommunicationBridge.connect(getKuick(), task.connection,
                                task.device, 0)) {

                            bridge.notifyStateOfTransferRequest(group.id, isAccepted);
                        } catch (IOException e) {
                            e.printStackTrace();
                        } catch (DifferentClientException e) {
                            e.printStackTrace();
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }).start();

                    if (isAccepted)
                        run(task);
                    else {
                        getKuick().remove(getKuick().getWritableDatabase(), task.assignee, task.group, null);
                        getKuick().broadcast();
                    }
                } catch (Exception e) {
                    e.printStackTrace();

                    if (isAccepted)
                        getNotificationHelper().showToast(R.string.mesg_somethingWentWrong);
                }
            } else if (ACTION_DEVICE_APPROVAL.equals(intent.getAction())) {
                Device device = intent.getParcelableExtra(EXTRA_DEVICE);
                boolean accepted = intent.getBooleanExtra(EXTRA_ACCEPTED, false);
                int notificationId = intent.getIntExtra(NotificationUtils.EXTRA_NOTIFICATION_ID, -1);
                int suggestedPin = intent.getIntExtra(EXTRA_DEVICE_PIN, -1);

                getNotificationHelper().getUtils().cancel(notificationId);

                if (device != null) {
                    device.isBlocked = !accepted;

                    if (accepted)
                        device.receiveKey = suggestedPin;

                    getKuick().update(device);
                    getKuick().broadcast();
                }
            } else if (ACTION_CLIPBOARD.equals(intent.getAction()) && intent.hasExtra(EXTRA_CLIPBOARD_ACCEPTED)) {
                int notificationId = intent.getIntExtra(NotificationUtils.EXTRA_NOTIFICATION_ID, -1);
                long clipboardId = intent.getLongExtra(EXTRA_CLIPBOARD_ID, -1);
                boolean isAccepted = intent.getBooleanExtra(EXTRA_CLIPBOARD_ACCEPTED, false);
                TextStreamObject textStreamObject = new TextStreamObject(clipboardId);

                getNotificationHelper().getUtils().cancel(notificationId);

                try {
                    getKuick().reconstruct(textStreamObject);

                    if (isAccepted) {
                        ClipboardManager cbManager = ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE));

                        if (cbManager != null) {
                            cbManager.setPrimaryClip(ClipData.newPlainText("receivedText", textStreamObject.text));
                            Toast.makeText(this, R.string.mesg_textCopiedToClipboard, Toast.LENGTH_SHORT).show();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if (ACTION_END_SESSION.equals(intent.getAction())) {
                stopSelf();
            } else if (ACTION_START_TRANSFER.equals(intent.getAction()) && intent.hasExtra(EXTRA_GROUP)
                    && intent.hasExtra(EXTRA_DEVICE) && intent.hasExtra(EXTRA_TRANSFER_TYPE)) {
                Device device = intent.getParcelableExtra(EXTRA_DEVICE);
                TransferGroup group = intent.getParcelableExtra(EXTRA_GROUP);
                TransferObject.Type type = (TransferObject.Type) intent.getSerializableExtra(EXTRA_TRANSFER_TYPE);

                try {
                    if (device == null || group == null || type == null)
                        throw new Exception();

                    FileTransferTask task = (FileTransferTask) findTaskBy(FileTransferTask.identifyWith(group.id,
                            device.uid, type));

                    if (task == null)
                        run(FileTransferTask.createFrom(getKuick(), group, device, type));
                    else
                        Toast.makeText(this, getString(R.string.mesg_groupOngoingNotice, task.object.name),
                                Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if (ACTION_STOP_TASK.equals(intent.getAction()) && intent.hasExtra(EXTRA_IDENTITY)) {
                int notificationId = intent.getIntExtra(NotificationUtils.EXTRA_NOTIFICATION_ID, -1);
                Identity identity = intent.getParcelableExtra(EXTRA_IDENTITY);

                try {
                    BackgroundTask task = findTaskBy(identity);

                    if (task == null) {
                        getNotificationHelper().getUtils().cancel(notificationId);
                    } else {
                        // FIXME: 16.03.2020 Should we use this notification?
                        //task.notification = getNotificationHelper().notifyStuckThread(task);

                        if (task.isInterrupted())
                            task.forceQuit();
                        else
                            task.interrupt(true);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        takeForeground(false);

        try {
            mCommunicationServer.stop();
        } catch (InterruptedException ignored) {
        }

        mMediaScanner.disconnect();
        mNsdDiscovery.unregisterService();
        mWebShareServer.stop();

        {
            ContentValues values = new ContentValues();
            values.put(Kuick.FIELD_TRANSFERGROUP_ISSHAREDONWEB, 0);
            getKuick().update(new SQLQuery.Select(Kuick.TABLE_TRANSFERGROUP)
                    .setWhere(String.format("%s = ?", Kuick.FIELD_TRANSFERGROUP_ISSHAREDONWEB),
                            String.valueOf(1)), values);
        }

        if (getHotspotUtils().unloadPreviousConfig())
            Log.d(TAG, "onDestroy: Stopping hotspot (previously started)=" + getHotspotUtils().disable());

        if (getWifiLock() != null && getWifiLock().isHeld()) {
            getWifiLock().release();
            Log.d(TAG, "onDestroy: Releasing Wi-Fi lock");
        }

        stopForeground(true);

        synchronized (mTaskList) {
            for (BackgroundTask task : mTaskList) {
                task.interrupt(false);
                Log.d(TAG, "onDestroy(): Ongoing indexing stopped: " + task.getTitle());
            }
        }

        AppUtils.generateNetworkPin(this);
        getKuick().broadcast();
    }

    public void attach(BackgroundTask task)
    {
        runInternal(task);
    }

    public boolean canStopService()
    {
        return getTaskList().size() > 0 || mHotspotManager.isStarted() || mWebShareServer.hadClients();
    }

    @Nullable
    public BackgroundTask findTaskBy(Identity identity)
    {
        List<BackgroundTask> taskList = findTasksBy(identity);
        return taskList.size() > 0 ? taskList.get(0) : null;
    }

    @NonNull
    public synchronized List<BackgroundTask> findTasksBy(Identity identity)
    {
        synchronized (mTaskList) {
            return findTasksBy(mTaskList, identity);
        }
    }

    public static <T extends BackgroundTask> List<T> findTasksBy(List<T> taskList, Identity identity)
    {
        List<T> foundList = new ArrayList<>();
        for (T task : taskList)
            if (task.getIdentity().equals(identity))
                foundList.add(task);
        return foundList;
    }

    private HotspotManager getHotspotUtils()
    {
        return mHotspotManager;
    }

    public WifiConfiguration getHotspotConfig()
    {
        return getHotspotUtils().getConfiguration();
    }

    public MediaScannerConnection getMediaScanner()
    {
        return mMediaScanner;
    }

    public NotificationHelper getNotificationHelper()
    {
        return mNotificationHelper;
    }

    private ExecutorService getSelfExecutor()
    {
        return mExecutor;
    }

    public List<BackgroundTask> getTaskList()
    {
        return mTaskList;
    }

    public <T extends BackgroundTask> List<T> getTaskListOf(Class<T> clazz)
    {
        synchronized (mTaskList) {
            return getTaskListOf(mTaskList, clazz);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends BackgroundTask> List<T> getTaskListOf(List<? extends BackgroundTask> taskList,
                                                                   Class<T> clazz)
    {
        List<T> foundList = new ArrayList<>();
        for (BackgroundTask task : taskList)
            if (clazz.isInstance(task))
                foundList.add((T) task);
        return foundList;
    }

    private WifiManager.WifiLock getWifiLock()
    {
        return mWifiLock;
    }

    public static int hashIntent(@NonNull Intent intent)
    {
        StringBuilder builder = new StringBuilder()
                .append(intent.getComponent())
                .append(intent.getData())
                .append(intent.getPackage())
                .append(intent.getAction())
                .append(intent.getFlags())
                .append(intent.getType());

        if (intent.getExtras() != null)
            builder.append(intent.getExtras().toString());

        return builder.toString().hashCode();
    }

    public boolean hasTaskOf(Class<? extends BackgroundTask> clazz)
    {
        synchronized (mTaskList) {
            return hasTaskOf(mTaskList, clazz);
        }
    }

    public static boolean hasTaskOf(List<? extends BackgroundTask> taskList, Class<? extends BackgroundTask> clazz)
    {
        for (BackgroundTask task : taskList)
            if (clazz.isInstance(task))
                return true;
        return false;
    }

    public static boolean hasTaskWith(List<? extends BackgroundTask> taskList, Identity identity)
    {
        for (BackgroundTask task : taskList)
            if (task.getIdentity().equals(identity))
                return true;
        return false;
    }

    public void interruptTasksBy(Identity identity, boolean userAction)
    {
        synchronized (mTaskList) {
            for (BackgroundTask task : findTasksBy(identity))
                task.interrupt(userAction);
        }
    }

    private boolean isProcessRunning(long groupId, String deviceId, TransferObject.Type type)
    {
        return findTaskBy(FileTransferTask.identifyWith(groupId, deviceId, type)) != null;
    }

    protected synchronized <T extends BackgroundTask> void registerWork(T task)
    {
        synchronized (mTaskList) {
            mTaskList.add(task);
        }

        Log.d(TAG, "registerWork: " + task.getClass().getSimpleName());
        sendBroadcast(new Intent(ACTION_TASK_CHANGE));
    }

    public static <T extends BackgroundTask> void run(Activity activity, T task)
    {
        try {
            AppUtils.getBgService(activity).run(task);
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    public void run(final BackgroundTask runningTask)
    {
        getSelfExecutor().submit(() -> attach(runningTask));
    }

    private void runInternal(BackgroundTask runningTask)
    {
        registerWork(runningTask);

        try {
            runningTask.run(this);
        } catch (Exception e) {
            e.printStackTrace();
        }

        unregisterWork(runningTask);
    }

    public void toggleHotspot()
    {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(this))
            return;

        if (getHotspotUtils().isEnabled())
            getHotspotUtils().disable();
        else
            Log.d(TAG, "toggleHotspot: Enabling=" + getHotspotUtils().enableConfigured(AppUtils.getHotspotName(
                    this), null));
    }

    /**
     * Some services like file transfer server, web share portal server involve writing and reading data.
     * So, it is best to avoid starting them when the app doesn't have the right permissions.
     */
    public boolean tryStartingServices()
    {
        Log.d(TAG, "tryStartingServices: Starting...");

        if (mWebShareServer.isAlive() && mCommunicationServer.isListening())
            return true;

        if (!AppUtils.checkRunningConditions(this)) {
            Log.d(TAG, "tryStartingServices: The app doesn't have the satisfactory permissions to start " +
                    "services.");
            return false;
        }


        if (!mCommunicationServer.isListening()) {
            try {
                mCommunicationServer.start();
            } catch (Exception e) {
                e.printStackTrace();
                Log.e(TAG, "tryStartingServices: Cannot start the service=" + mCommunicationServer.isListening());
            }
        }

        try {
            mWebShareServer.setAsyncRunner(new WebShareServer.BoundRunner(
                    Executors.newFixedThreadPool(AppConfig.WEB_SHARE_CONNECTION_MAX)));
            mWebShareServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start Web Share Server");
            return false;
        }

        return true;
    }

    protected synchronized void unregisterWork(BackgroundTask task)
    {
        synchronized (mTaskList) {
            mTaskList.remove(task);
            // FIXME: 20.03.2020 Should we stop the service if there is no task left?
        }

        Log.d(TAG, "unregisterWork: " + task.getClass().getSimpleName());
        sendBroadcast(new Intent(ACTION_TASK_CHANGE));
    }

    class CommunicationServer extends CoolSocket
    {
        CommunicationServer()
        {
            super(AppConfig.SERVER_PORT_COMMUNICATION);
            getConfigFactory().setAcceptTimeout(AppConfig.DEFAULT_SOCKET_TIMEOUT_LARGE);
            getConfigFactory().setReadTimeout(AppConfig.DEFAULT_SOCKET_TIMEOUT);
        }

        @Override
        public void onConnected(final ActiveConnection activeConnection)
        {
            // check if the same address has other connections and limit that to 5
            try {
                activeConnection.reply(AppUtils.getDeviceId(BackgroundService.this));

                JSONObject response = activeConnection.receive().getAsJson();
                final int activePin = getDefaultPreferences().getInt(Keyword.NETWORK_PIN, -1);
                final boolean hasPin = activePin != -1 && response.has(Keyword.DEVICE_PIN)
                        && activePin == response.getInt(Keyword.DEVICE_PIN);
                final Device device = new Device();
                final DeviceAddress deviceAddress = new DeviceAddress(activeConnection.getAddress().getHostAddress());

                try {
                    DeviceLoader.loadFrom(getKuick(), response, device, hasPin);
                } catch (DeviceBlockedException e) {
                    throw e;
                } catch (DeviceInsecureException e) {
                    getNotificationHelper().notifyConnectionRequest(device, device.receiveKey);
                } finally {
                    DeviceLoader.processConnection(getKuick(), device, deviceAddress);
                    activeConnection.reply(AppUtils.getLocalDeviceAsJson(BackgroundService.this, device, 0));
                }

                if (hasPin) // pin is known, should be changed. Warn the listeners.
                    sendBroadcast(new Intent(ACTION_PIN_USED));

                getKuick().broadcast();

                response = activeConnection.receive().getAsJson();

                switch (response.getString(Keyword.REQUEST)) {
                    case (Keyword.REQUEST_TRANSFER):
                        if (response.has(Keyword.INDEX) && response.has(Keyword.TRANSFER_GROUP_ID)
                                && !hasTaskOf(IndexTransferTask.class)) {
                            long groupId = response.getLong(Keyword.TRANSFER_GROUP_ID);
                            String jsonIndex = response.getString(Keyword.INDEX);

                            run(new IndexTransferTask(groupId, jsonIndex, device, deviceAddress, hasPin));
                        }
                        break;
                    case (Keyword.REQUEST_TRANSFER_STATE):
                        if (response.has(Keyword.TRANSFER_GROUP_ID)) {
                            int groupId = response.getInt(Keyword.TRANSFER_GROUP_ID);
                            boolean isAccepted = response.getBoolean(Keyword.TRANSFER_IS_ACCEPTED);

                            TransferGroup group = new TransferGroup(groupId);
                            TransferAssignee assignee = new TransferAssignee(group, device,
                                    TransferObject.Type.OUTGOING);

                            try {
                                getKuick().reconstruct(group);
                                getKuick().reconstruct(assignee);

                                if (!isAccepted) {
                                    getKuick().remove(assignee);
                                    getKuick().broadcast();
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        break;
                    case (Keyword.REQUEST_CLIPBOARD):
                        if (response.has(Keyword.TRANSFER_TEXT)) {
                            TextStreamObject textStreamObject = new TextStreamObject(AppUtils.getUniqueNumber(),
                                    response.getString(Keyword.TRANSFER_TEXT));

                            getKuick().publish(textStreamObject);
                            getKuick().broadcast();
                            getNotificationHelper().notifyClipboardRequest(device, textStreamObject);
                        }
                        break;
                    case (Keyword.REQUEST_ACQUAINTANCE):
                        sendBroadcast(new Intent(ACTION_DEVICE_ACQUAINTANCE)
                                .putExtra(EXTRA_DEVICE, device)
                                .putExtra(EXTRA_CONNECTION, deviceAddress));
                        break;
                    case (Keyword.REQUEST_HANDSHAKE):
                        break;
                    case (Keyword.REQUEST_TRANSFER_JOB):
                        if (response.has(Keyword.TRANSFER_GROUP_ID)) {
                            int groupId = response.getInt(Keyword.TRANSFER_GROUP_ID);
                            String typeValue = response.getString(Keyword.TRANSFER_TYPE);

                            try {
                                TransferObject.Type type = TransferObject.Type.valueOf(typeValue);

                                // The type is reversed to match our side
                                if (TransferObject.Type.INCOMING.equals(type))
                                    type = TransferObject.Type.OUTGOING;
                                else if (TransferObject.Type.OUTGOING.equals(type))
                                    type = TransferObject.Type.INCOMING;

                                TransferGroup group = new TransferGroup(groupId);
                                getKuick().reconstruct(group);

                                Log.d(BackgroundService.TAG, "CommunicationServer.onConnected(): "
                                        + "groupId=" + groupId + " typeValue=" + typeValue);

                                if (!isProcessRunning(groupId, device.uid, type)) {
                                    FileTransferTask task = new FileTransferTask();
                                    task.activeConnection = activeConnection;
                                    task.group = group;
                                    task.device = device;
                                    task.type = type;
                                    task.assignee = new TransferAssignee(group, device, type);
                                    task.index = new IndexOfTransferGroup(group);

                                    getKuick().reconstruct(task.assignee);

                                    if (TransferObject.Type.OUTGOING.equals(type)) {
                                        Log.d(TAG, "onConnected: Informing before starting to send.");

                                        attach(task);
                                    } else if (TransferObject.Type.INCOMING.equals(type)) {
                                        JSONObject currentReply = new JSONObject();
                                        boolean result = device.isTrusted;

                                        if (!result)
                                            currentReply.put(Keyword.ERROR, Keyword.ERROR_NOT_TRUSTED);

                                        Log.d(TAG, "onConnected: Replied: " + currentReply.toString());
                                        Log.d(TAG, "onConnected: " + activeConnection.receive().getAsString());

                                        if (result)
                                            attach(task);

                                        Log.d(TAG, "onConnected: " + activeConnection.receive().getAsString());
                                    }
                                } else
                                    response.put(Keyword.ERROR, Keyword.ERROR_NOT_ACCESSIBLE);
                            } catch (Exception e) {
                                response.put(Keyword.ERROR, Keyword.ERROR_NOT_FOUND);
                            }
                        }
                        break;
                }
            } catch (DeviceInsecureException e) {
                // TODO: 8/11/20 Close safely
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public class LocalBinder extends Binder
    {
        public BackgroundService getService()
        {
            return BackgroundService.this;
        }
    }
}
