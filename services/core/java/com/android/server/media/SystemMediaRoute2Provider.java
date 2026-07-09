/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.media;

import static android.media.MediaRoute2Info.FEATURE_LIVE_AUDIO;
import static android.media.MediaRoute2Info.FEATURE_LIVE_VIDEO;
import static android.media.MediaRoute2Info.FEATURE_LOCAL_PLAYBACK;
import static android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_DOCK;
import static android.media.MediaRoute2Info.TYPE_HDMI;
import static android.media.MediaRoute2Info.TYPE_USB_DEVICE;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADPHONES;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADSET;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.AudioRoutesInfo;
import android.media.AudioSystem;
import android.media.IAudioRoutesObserver;
import android.media.IAudioService;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRoute2ProviderService;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;

import java.util.Objects;

/**
 * Provides routes for local playbacks such as phone speaker, wired headset, or Bluetooth speakers.
 */
// TODO: check thread safety. We may need to use lock to protect variables.
class SystemMediaRoute2Provider extends MediaRoute2Provider {
    private static final String TAG = "MR2SystemProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    static final String DEFAULT_ROUTE_ID = "DEFAULT_ROUTE";
    static final String DEVICE_ROUTE_ID = "DEVICE_ROUTE";
    static final String HDMI_ROUTE_ID = "HDMI_ROUTE";
    static final String USB_ROUTE_ID = "USB_ROUTE";
    static final String SYSTEM_SESSION_ID = "SYSTEM_SESSION";

    private final AudioManager mAudioManager;
    private final IAudioService mAudioService;
    private final Handler mHandler;
    private final Context mContext;
    private final BluetoothRouteProvider mBtRouteProvider;

    private static ComponentName sComponentName = new ComponentName(
            SystemMediaRoute2Provider.class.getPackage().getName(),
            SystemMediaRoute2Provider.class.getName());

    private String mSelectedRouteId;
    // For apps without MODIFYING_AUDIO_ROUTING permission.
    // This should be the currently selected route.
    MediaRoute2Info mDefaultRoute;
    MediaRoute2Info mDeviceRoute;
    MediaRoute2Info mHdmiRoute;
    MediaRoute2Info mUsbRoute;
    RoutingSessionInfo mDefaultSessionInfo;
    final AudioRoutesInfo mCurAudioRoutesInfo = new AudioRoutesInfo();
    int mDeviceVolume;

    private final Object mRequestLock = new Object();
    @GuardedBy("mRequestLock")
    private volatile SessionCreationRequest mPendingSessionCreationRequest;

    final IAudioRoutesObserver.Stub mAudioRoutesObserver = new IAudioRoutesObserver.Stub() {
        @Override
        public void dispatchAudioRoutesChanged(final AudioRoutesInfo newRoutes) {
            mHandler.post(() -> {
                updateDeviceRoute(newRoutes);
                notifyProviderState();
            });
        }
    };

    SystemMediaRoute2Provider(Context context, UserHandle user) {
        super(sComponentName);

        mIsSystemRouteProvider = true;
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mAudioService = IAudioService.Stub.asInterface(
                ServiceManager.getService(Context.AUDIO_SERVICE));
        AudioRoutesInfo newAudioRoutes = null;
        try {
            newAudioRoutes = mAudioService.startWatchingRoutes(mAudioRoutesObserver);
        } catch (RemoteException e) {
        }
        updateDeviceRoute(newAudioRoutes);

        // Restore user's audio route preference from persist property.
        // If user previously forced speaker, re-apply FORCE_SPEAKER so
        // AudioPolicyManager doesn't auto-route to HDMI on boot.
        String savedRoute = SystemProperties.get("persist.audio.output.route", "auto");
        if ("speaker".equals(savedRoute)) {
            AudioSystem.setForceUse(AudioSystem.FOR_MEDIA, AudioSystem.FORCE_SPEAKER);
            mSelectedRouteId = DEVICE_ROUTE_ID;
            Log.i(TAG, "Restored audio route preference: speaker (FORCE_SPEAKER)");
        } else {
            // "hdmi", "usb", or "auto" — let AudioPolicyManager decide
            AudioSystem.setForceUse(AudioSystem.FOR_MEDIA, AudioSystem.FORCE_NONE);
            Log.i(TAG, "Restored audio route preference: " + savedRoute + " (FORCE_NONE)");
        }

        // .getInstance returns null if there is no bt adapter available
        mBtRouteProvider = BluetoothRouteProvider.createInstance(context, (routes) -> {
            publishProviderState();

            boolean sessionInfoChanged;
            sessionInfoChanged = updateSessionInfosIfNeeded();
            if (sessionInfoChanged) {
                notifySessionInfoUpdated();
            }
        });
        updateSessionInfosIfNeeded();

        IntentFilter intentFilter = new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION);
        intentFilter.addAction(AudioManager.STREAM_DEVICES_CHANGED_ACTION);
        mContext.registerReceiverAsUser(new AudioManagerBroadcastReceiver(), user,
                intentFilter, null, null);

        if (mBtRouteProvider != null) {
            mHandler.post(() -> {
                mBtRouteProvider.start(user);
                notifyProviderState();
            });
        }
        updateVolume();
    }

    @Override
    public void setCallback(Callback callback) {
        super.setCallback(callback);
        notifyProviderState();
        notifySessionInfoUpdated();
    }

    @Override
    public void requestCreateSession(long requestId, String packageName, String routeId,
            Bundle sessionHints) {
        // Assume a router without MODIFY_AUDIO_ROUTING permission can't request with
        // a route ID different from the default route ID. The service should've filtered.
        if (TextUtils.equals(routeId, DEFAULT_ROUTE_ID)) {
            mCallback.onSessionCreated(this, requestId, mDefaultSessionInfo);
            return;
        }
        if (TextUtils.equals(routeId, mSelectedRouteId)) {
            mCallback.onSessionCreated(this, requestId, mSessionInfos.get(0));
            return;
        }

        synchronized (mRequestLock) {
            // Handle the previous request as a failure if exists.
            if (mPendingSessionCreationRequest != null) {
                mCallback.onRequestFailed(this, mPendingSessionCreationRequest.mRequestId,
                        MediaRoute2ProviderService.REASON_UNKNOWN_ERROR);
            }
            mPendingSessionCreationRequest = new SessionCreationRequest(requestId, routeId);
        }

        transferToRoute(requestId, SYSTEM_SESSION_ID, routeId);
    }

    @Override
    public void releaseSession(long requestId, String sessionId) {
        // Do nothing
    }

    @Override
    public void updateDiscoveryPreference(RouteDiscoveryPreference discoveryPreference) {
        // Do nothing
    }

    @Override
    public void selectRoute(long requestId, String sessionId, String routeId) {
        // Do nothing since we don't support multiple BT yet.
    }

    @Override
    public void deselectRoute(long requestId, String sessionId, String routeId) {
        // Do nothing since we don't support multiple BT yet.
    }

    @Override
    public void transferToRoute(long requestId, String sessionId, String routeId) {
        if (TextUtils.equals(routeId, DEFAULT_ROUTE_ID)) {
            // The currently selected route is the default route.
            return;
        }
        if (TextUtils.equals(routeId, DEVICE_ROUTE_ID)
                || TextUtils.equals(routeId, HDMI_ROUTE_ID)
                || TextUtils.equals(routeId, USB_ROUTE_ID)) {
            if (mBtRouteProvider != null) {
                mBtRouteProvider.transferTo(null);
            }
            mSelectedRouteId = routeId;
            String routeTarget = "speaker";
            if (TextUtils.equals(routeId, HDMI_ROUTE_ID)) {
                routeTarget = "hdmi";
            } else if (TextUtils.equals(routeId, USB_ROUTE_ID)) {
                routeTarget = "usb";
            }
            try {
                SystemProperties.set("persist.audio.output.route", routeTarget);
            } catch (Exception e) {
                Log.w(TAG, "Failed to set audio route property", e);
            }

            // Use AudioSystem.setForceUse() to actually control AudioPolicyManager
            // routing. This is the proper mechanism — setParameters() only sends
            // passthrough strings to HAL modules without affecting APM routing.
            if ("speaker".equals(routeTarget)) {
                // FORCE_SPEAKER tells AudioPolicyManager to route media audio
                // to the built-in speaker, ignoring connected HDMI/USB devices.
                AudioSystem.setForceUse(AudioSystem.FOR_MEDIA, AudioSystem.FORCE_SPEAKER);
                Log.i(TAG, "Audio route: forcing SPEAKER");
            } else {
                // FORCE_NONE lets AudioPolicyManager auto-route to the highest
                // priority connected device. Priority order:
                //   USB > HDMI > Wired Headphones > Speaker
                // So if HDMI is connected, audio goes to HDMI.
                // If USB is connected (higher priority), audio goes to USB.
                AudioSystem.setForceUse(AudioSystem.FOR_MEDIA, AudioSystem.FORCE_NONE);
                Log.i(TAG, "Audio route: FORCE_NONE (auto-route to " + routeTarget + ")");
            }

            boolean sessionInfoChanged = updateSessionInfosIfNeeded();
            if (sessionInfoChanged) {
                notifySessionInfoUpdated();
            }
            return;
        }
        if (mBtRouteProvider != null) {
            mBtRouteProvider.transferTo(routeId);
        }
    }

    @Override
    public void setRouteVolume(long requestId, String routeId, int volume) {
        if (!TextUtils.equals(routeId, mSelectedRouteId)) {
            return;
        }
        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
    }

    @Override
    public void setSessionVolume(long requestId, String sessionId, int volume) {
        // Do nothing since we don't support grouping volume yet.
    }

    @Override
    public void prepareReleaseSession(String sessionId) {
        // Do nothing since the system session persists.
    }

    public MediaRoute2Info getDefaultRoute() {
        return mDefaultRoute;
    }

    public RoutingSessionInfo getDefaultSessionInfo() {
        return mDefaultSessionInfo;
    }

    private void updateDeviceRoute(AudioRoutesInfo newRoutes) {
        int name = R.string.default_audio_route_name;
        int type = TYPE_BUILTIN_SPEAKER;
        if (newRoutes != null) {
            mCurAudioRoutesInfo.mainType = newRoutes.mainType;
            if ((newRoutes.mainType & AudioRoutesInfo.MAIN_HEADPHONES) != 0) {
                type = TYPE_WIRED_HEADPHONES;
                name = com.android.internal.R.string.default_audio_route_name_headphones;
            } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_HEADSET) != 0) {
                type = TYPE_WIRED_HEADSET;
                name = com.android.internal.R.string.default_audio_route_name_headphones;
            } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_DOCK_SPEAKERS) != 0) {
                type = TYPE_DOCK;
                name = com.android.internal.R.string.default_audio_route_name_dock_speakers;
            }
        }

        int volumeHandling = mAudioManager.isVolumeFixed()
                ? MediaRoute2Info.PLAYBACK_VOLUME_FIXED
                : MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE;
        int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        mDeviceRoute = new MediaRoute2Info.Builder(
                DEVICE_ROUTE_ID, mContext.getResources().getText(name).toString())
                .setVolumeHandling(volumeHandling)
                .setVolume(mDeviceVolume)
                .setVolumeMax(maxVolume)
                .setType(type)
                .addFeature(FEATURE_LIVE_AUDIO)
                .addFeature(FEATURE_LIVE_VIDEO)
                .addFeature(FEATURE_LOCAL_PLAYBACK)
                .setConnectionState(MediaRoute2Info.CONNECTION_STATE_CONNECTED)
                .build();

        mHdmiRoute = new MediaRoute2Info.Builder(
                HDMI_ROUTE_ID, mContext.getResources().getText(
                        com.android.internal.R.string.default_audio_route_name_hdmi).toString())
                .setVolumeHandling(volumeHandling)
                .setVolume(mDeviceVolume)
                .setVolumeMax(maxVolume)
                .setType(TYPE_HDMI)
                .addFeature(FEATURE_LIVE_AUDIO)
                .addFeature(FEATURE_LIVE_VIDEO)
                .addFeature(FEATURE_LOCAL_PLAYBACK)
                .setConnectionState(MediaRoute2Info.CONNECTION_STATE_CONNECTED)
                .build();

        mUsbRoute = new MediaRoute2Info.Builder(
                USB_ROUTE_ID, mContext.getResources().getText(
                        com.android.internal.R.string.default_audio_route_name_usb).toString())
                .setVolumeHandling(volumeHandling)
                .setVolume(mDeviceVolume)
                .setVolumeMax(maxVolume)
                .setType(TYPE_USB_DEVICE)
                .addFeature(FEATURE_LIVE_AUDIO)
                .addFeature(FEATURE_LIVE_VIDEO)
                .addFeature(FEATURE_LOCAL_PLAYBACK)
                .setConnectionState(MediaRoute2Info.CONNECTION_STATE_CONNECTED)
                .build();

        updateProviderState();
    }

    private void updateProviderState() {
        MediaRoute2ProviderInfo.Builder builder = new MediaRoute2ProviderInfo.Builder();
        builder.addRoute(mDeviceRoute);
        if (mHdmiRoute != null) {
            builder.addRoute(mHdmiRoute);
        }
        if (mUsbRoute != null) {
            builder.addRoute(mUsbRoute);
        }
        if (mBtRouteProvider != null) {
            for (MediaRoute2Info route : mBtRouteProvider.getAllBluetoothRoutes()) {
                builder.addRoute(route);
            }
        }
        MediaRoute2ProviderInfo providerInfo = builder.build();
        setProviderState(providerInfo);
        if (DEBUG) {
            Slog.d(TAG, "Updating system provider info : " + providerInfo);
        }
    }

    /**
     * Updates the mSessionInfo. Returns true if the session info is changed.
     */
    boolean updateSessionInfosIfNeeded() {
        synchronized (mLock) {
            RoutingSessionInfo oldSessionInfo = mSessionInfos.isEmpty() ? null : mSessionInfos.get(
                    0);

            RoutingSessionInfo.Builder builder = new RoutingSessionInfo.Builder(
                    SYSTEM_SESSION_ID, "" /* clientPackageName */)
                    .setSystemSession(true);

            MediaRoute2Info selectedRoute = mDeviceRoute;
            if (TextUtils.equals(mSelectedRouteId, HDMI_ROUTE_ID) && mHdmiRoute != null) {
                selectedRoute = mHdmiRoute;
            } else if (TextUtils.equals(mSelectedRouteId, USB_ROUTE_ID) && mUsbRoute != null) {
                selectedRoute = mUsbRoute;
            }
            if (mBtRouteProvider != null) {
                MediaRoute2Info selectedBtRoute = mBtRouteProvider.getSelectedRoute();
                if (selectedBtRoute != null) {
                    selectedRoute = selectedBtRoute;
                }
            }
            mSelectedRouteId = selectedRoute.getId();
            mDefaultRoute = new MediaRoute2Info.Builder(DEFAULT_ROUTE_ID, selectedRoute)
                    .setSystemRoute(true)
                    .setProviderId(mUniqueId)
                    .build();
            builder.addSelectedRoute(mSelectedRouteId);

            if (!TextUtils.equals(mSelectedRouteId, DEVICE_ROUTE_ID)) {
                builder.addTransferableRoute(DEVICE_ROUTE_ID);
            }
            if (mHdmiRoute != null && !TextUtils.equals(mSelectedRouteId, HDMI_ROUTE_ID)) {
                builder.addTransferableRoute(HDMI_ROUTE_ID);
            }
            if (mUsbRoute != null && !TextUtils.equals(mSelectedRouteId, USB_ROUTE_ID)) {
                builder.addTransferableRoute(USB_ROUTE_ID);
            }

            if (mBtRouteProvider != null) {
                for (MediaRoute2Info route : mBtRouteProvider.getTransferableRoutes()) {
                    builder.addTransferableRoute(route.getId());
                }
            }

            RoutingSessionInfo newSessionInfo = builder.setProviderId(mUniqueId).build();

            if (mPendingSessionCreationRequest != null) {
                SessionCreationRequest sessionCreationRequest;
                synchronized (mRequestLock) {
                    sessionCreationRequest = mPendingSessionCreationRequest;
                    mPendingSessionCreationRequest = null;
                }
                if (sessionCreationRequest != null) {
                    if (TextUtils.equals(mSelectedRouteId, sessionCreationRequest.mRouteId)) {
                        mCallback.onSessionCreated(this,
                                sessionCreationRequest.mRequestId, newSessionInfo);
                    } else {
                        mCallback.onRequestFailed(this, sessionCreationRequest.mRequestId,
                                MediaRoute2ProviderService.REASON_UNKNOWN_ERROR);
                    }
                }
            }

            if (Objects.equals(oldSessionInfo, newSessionInfo)) {
                return false;
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Updating system routing session info : " + newSessionInfo);
                }
                mSessionInfos.clear();
                mSessionInfos.add(newSessionInfo);
                mDefaultSessionInfo = new RoutingSessionInfo.Builder(
                        SYSTEM_SESSION_ID, "" /* clientPackageName */)
                        .setProviderId(mUniqueId)
                        .setSystemSession(true)
                        .addSelectedRoute(DEFAULT_ROUTE_ID)
                        .build();
                return true;
            }
        }
    }

    void publishProviderState() {
        updateProviderState();
        notifyProviderState();
    }

    void notifySessionInfoUpdated() {
        if (mCallback == null) {
            return;
        }

        RoutingSessionInfo sessionInfo;
        synchronized (mLock) {
            sessionInfo = mSessionInfos.get(0);
        }

        mCallback.onSessionUpdated(this, sessionInfo);
    }

    private static class SessionCreationRequest {
        final long mRequestId;
        final String mRouteId;

        SessionCreationRequest(long requestId, String routeId) {
            this.mRequestId = requestId;
            this.mRouteId = routeId;
        }
    }

    void updateVolume() {
        int devices = mAudioManager.getDevicesForStream(AudioManager.STREAM_MUSIC);
        int volume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        if (mDefaultRoute.getVolume() != volume) {
            mDefaultRoute = new MediaRoute2Info.Builder(mDefaultRoute)
                    .setVolume(volume)
                    .build();
        }

        if (mBtRouteProvider != null && mBtRouteProvider.updateVolumeForDevices(devices, volume)) {
            return;
        }
        if (mDeviceVolume != volume) {
            mDeviceVolume = volume;
            mDeviceRoute = new MediaRoute2Info.Builder(mDeviceRoute)
                    .setVolume(volume)
                    .build();
            if (mHdmiRoute != null) {
                mHdmiRoute = new MediaRoute2Info.Builder(mHdmiRoute)
                        .setVolume(volume)
                        .build();
            }
            if (mUsbRoute != null) {
                mUsbRoute = new MediaRoute2Info.Builder(mUsbRoute)
                        .setVolume(volume)
                        .build();
            }
        }
        publishProviderState();
    }

    private class AudioManagerBroadcastReceiver extends BroadcastReceiver {
        // This will be called in the main thread.
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.getAction().equals(AudioManager.VOLUME_CHANGED_ACTION)
                    && !intent.getAction().equals(AudioManager.STREAM_DEVICES_CHANGED_ACTION)) {
                return;
            }

            int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
            if (streamType != AudioManager.STREAM_MUSIC) {
                return;
            }

            updateVolume();
        }
    }
}
