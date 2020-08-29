/*
 * Copyright (C) 2020 The boringdroid Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal;

import android.app.WindowConfiguration;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Environment;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BoringdroidManager {
    // TODO: Rename property name
    public static boolean IS_SYSTEMUI_PLUGIN_ENABLED =
            SystemProperties.getBoolean("persist.sys.systemuiplugin.enabled", false);

    private static final String PACKAGE_WINDOW_BOUNDS_NAME = "package-window-bounds";
    private static final String PACKAGE_WINDOWING_MODE_NAME = "package-windowing-mode";
    private static final List<String> DISALLOWED_LIST = new ArrayList<>();
    private static final String TAG = "BoringdroidConfig";

    static {
        DISALLOWED_LIST.add("android");
        DISALLOWED_LIST.add("com.android.systemui");
    }

    public static boolean isPCModeEnabled() {
        return SystemProperties.getBoolean("persist.sys.pcmode.enabled", true);
    }

    private static boolean isInPCModeDisallowedList(String packageName) {
        return packageName != null && DISALLOWED_LIST.contains(packageName);
    }

    private static boolean isDataSystemDirNotReady(Context context) {
        UserManager userManager = context.getSystemService(UserManager.class);
        return !(userManager != null && userManager.isUserUnlockingOrUnlocked(UserHandle.myUserId()));
    }

    private static File getPackageWindowingModeFile() {
        return new File(
                Environment.getDataSystemCeDirectory(UserHandle.myUserId())
                        + File.separator + PACKAGE_WINDOWING_MODE_NAME
        );
    }

    private static File getPackageWindowBoundsName() {
        return new File(
                Environment.getDataSystemCeDirectory(UserHandle.myUserId())
                        + File.separator + PACKAGE_WINDOW_BOUNDS_NAME
        );
    }

    public static void savePackageWindowingMode(Context context,
                                                String packageName,
                                                @WindowConfiguration.WindowingMode
                                                        int windowingMode) {
        if (BoringdroidManager.isDataSystemDirNotReady(context)) {
            Slog.e(TAG, "Calling savePackageWindowingMode with package " + packageName
                    + ", and mode " + windowingMode + ", before file is ready");
            return;
        }
        SharedPreferences sharedPreferences =
                context.getSharedPreferences(getPackageWindowingModeFile(), Context.MODE_PRIVATE);
        sharedPreferences.edit().putInt(packageName, windowingMode).apply();
    }

    public static @WindowConfiguration.WindowingMode
    int getPackageWindowingMode(Context context, String packageName) {
        if (BoringdroidManager.isDataSystemDirNotReady(context)) {
            Slog.e(TAG, "Calling getPackageWindowingMode with package " + packageName
                    + ", before file is ready");
            return WindowConfiguration.WINDOWING_MODE_UNDEFINED;
        }
        if (!BoringdroidManager.isPCModeEnabled()) {
            return WindowConfiguration.WINDOWING_MODE_UNDEFINED;
        }
        // If the package is in the multi window black list, it will run in default
        // windowing mode.
        if (isInPCModeDisallowedList(packageName)) {
            return WindowConfiguration.WINDOWING_MODE_UNDEFINED;
        }
        SharedPreferences sharedPreferences =
                context.getSharedPreferences(getPackageWindowingModeFile(), Context.MODE_PRIVATE);
        // We hope the default windowing mode is freeform.
        return sharedPreferences.getInt(packageName, WindowConfiguration.WINDOWING_MODE_FREEFORM);
    }

    public static void savePackageWindowBounds(Context context, String packageName, Rect bounds) {
        if (BoringdroidManager.isDataSystemDirNotReady(context)) {
            Slog.e(TAG, "Calling savePackageWindowBounds with package " + packageName
                    + ", and bounds " + bounds + ", before file is ready");
            return;
        }
        SharedPreferences sharedPreferences =
                context.getSharedPreferences(getPackageWindowBoundsName(), Context.MODE_PRIVATE);
        Rect tempBounds = new Rect(bounds);
        sharedPreferences
                .edit()
                .putInt(packageName + "-left", tempBounds.left)
                .putInt(packageName + "-top", tempBounds.top)
                .putInt(packageName + "-right", tempBounds.right)
                .putInt(packageName + "-bottom", tempBounds.bottom)
                .apply();
    }

    public static Rect getPackageWindowBounds(Context context, String packageName) {
        if (BoringdroidManager.isDataSystemDirNotReady(context)) {
            Slog.e(TAG, "Calling getPackageWindowBounds with package " + packageName
                    + ", before file is ready");
            return new Rect();
        }
        SharedPreferences sharedPreferences =
                context.getSharedPreferences(getPackageWindowBoundsName(), Context.MODE_PRIVATE);
        return new Rect(
                sharedPreferences.getInt(packageName + "-left", 0),
                sharedPreferences.getInt(packageName + "-top", 0),
                sharedPreferences.getInt(packageName + "-right", 0),
                sharedPreferences.getInt(packageName + "-bottom", 0)
        );
    }
}
