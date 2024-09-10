/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.theme;

import static android.util.TypedValue.TYPE_INT_COLOR_ARGB8;

import static com.android.systemui.Flags.themeOverlayControllerWakefulnessDeprecation;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_ASLEEP;
import static com.android.systemui.shared.Flags.enableHomeDelay;
import static com.android.systemui.theme.ThemeOverlayApplier.COLOR_SOURCE_HOME;
import static com.android.systemui.theme.ThemeOverlayApplier.COLOR_SOURCE_LOCK;
import static com.android.systemui.theme.ThemeOverlayApplier.COLOR_SOURCE_PRESET;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_ACCENT_COLOR;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_DYNAMIC_COLOR;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_SYSTEM_PALETTE;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_COLOR_BOTH;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_COLOR_INDEX;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_COLOR_SOURCE;
import static com.android.systemui.theme.ThemeOverlayApplier.TIMESTAMP_FIELD;

import android.app.ActivityManager;
import android.app.UiModeManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.app.WallpaperManager.OnColorsChangedListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.CoreStartable;
import com.android.systemui.Dumpable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.model.KeyguardState;
import com.android.systemui.monet.ColorScheme;
import com.android.systemui.monet.Style;
import com.android.systemui.monet.TonalPalette;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.settings.SystemSettings;

import com.google.ux.material.libmonet.dynamiccolor.MaterialDynamicColors;
import com.google.ux.material.libmonet.hct.Hct;
import com.google.ux.material.libmonet.scheme.DynamicScheme;
import com.google.ux.material.libmonet.scheme.SchemeExpressive;
import com.google.ux.material.libmonet.scheme.SchemeFruitSalad;
import com.google.ux.material.libmonet.scheme.SchemeMonochrome;
import com.google.ux.material.libmonet.scheme.SchemeNeutral;
import com.google.ux.material.libmonet.scheme.SchemeRainbow;
import com.google.ux.material.libmonet.scheme.SchemeTonalSpot;
import com.google.ux.material.libmonet.scheme.SchemeVibrant;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * Controls the application of theme overlays across the system for all users.
 * This service is responsible for:
 * - Observing changes to Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES and applying the
 * corresponding overlays across the system
 * - Observing user switches, applying the overlays for the current user to user 0 (for systemui)
 * - Observing work profile changes and applying overlays from the primary user to their
 * associated work profiles
 */
@SysUISingleton
public class ThemeOverlayController implements CoreStartable, Dumpable, TunerService.Tunable {
    protected static final String TAG = "ThemeOverlayController";
    private static final boolean DEBUG = false;
    protected static final String OVERLAY_BERRY_BLACK_THEME =
            "com.android.system.theme.black";

    private static final String PREF_CHROMA_FACTOR ="monet_engine_chroma_factor";
    private static final String PREF_LUMINANCE_FACTOR ="monet_engine_luminance_factor";
    private static final String PREF_TINT_BACKGROUND ="monet_engine_tint_background";
    private static final String PREF_CUSTOM_COLOR ="monet_engine_custom_color";
    private static final String PREF_COLOR_OVERRIDE ="monet_engine_color_override";
    private static final String PREF_CUSTOM_BGCOLOR ="monet_engine_custom_bgcolor";
    private static final String PREF_BGCOLOR_OVERRIDE ="monet_engine_bgcolor_override";

    private final ThemeOverlayApplier mThemeManager;
    private final UserManager mUserManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Executor mBgExecutor;
    private final SecureSettings mSecureSettings;
    private final SystemSettings mSystemSettings;
    private final Executor mMainExecutor;
    private final Handler mBgHandler;
    private final Context mContext;
    private final boolean mIsMonetEnabled;
    private final boolean mIsFidelityEnabled;
    private final UserTracker mUserTracker;
    private final ConfigurationController mConfigurationController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final Resources mResources;
    // Current wallpaper colors associated to a user.
    private final SparseArray<WallpaperColors> mCurrentColors = new SparseArray<>();
    private final WallpaperManager mWallpaperManager;
    private final ActivityManager mActivityManager;
    @VisibleForTesting
    protected ColorScheme mColorScheme;
    // If fabricated overlays were already created for the current theme.
    private boolean mNeedsOverlayCreation;
    // Dominant color extracted from wallpaper, NOT the color used on the overlay
    protected int mMainWallpaperColor = Color.TRANSPARENT;
    // UI contrast as reported by UiModeManager
    private float mContrast = 0;
    // Theme variant: Vibrant, Tonal, Expressive, etc
    @VisibleForTesting
    protected Style mThemeStyle = Style.TONAL_SPOT;
    // Accent colors overlay
    private FabricatedOverlay mSecondaryOverlay;
    // Neutral system colors overlay
    private FabricatedOverlay mNeutralOverlay;
    // Dynamic colors overlay
    private FabricatedOverlay mDynamicOverlay;
    // If wallpaper color event will be accepted and change the UI colors.
    private boolean mAcceptColorEvents = true;
    // If non-null (per user), colors that were sent to the framework, and processing was deferred
    // until the next time the screen is off.
    private final SparseArray<WallpaperColors> mDeferredWallpaperColors = new SparseArray<>();
    private final SparseIntArray mDeferredWallpaperColorsFlags = new SparseIntArray();
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final JavaAdapter mJavaAdapter;
    private final KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    private final UiModeManager mUiModeManager;
    private DynamicScheme mDynamicSchemeDark;
    private DynamicScheme mDynamicSchemeLight;
    private final TunerService mTunerService;
    
    private ContentObserver mThemeCustomizationObserver;
    private ContentObserver mBerryBlackThemeObserver;
    private ContentObserver mStatusBarBatteryStyleObserver;
    private ContentObserver mQSBatteryStyleObserver;
    private ContentObserver mQSTileVerticalLayoutObserver;
    private ContentObserver mQSTileLabelHideObserver;
    private ContentObserver mQSUISyleObserver;
    private ContentObserver mQSShowBatteryPercentObserver;
    private ContentObserver mClockMarginTopObserver;
    private ContentObserver mQSTileShapeObserver;
    private ContentObserver mStatusBarClockChipObserver;
    private ContentObserver mLockscreenClockColoredObserver;
    private ContentObserver mQSTileLabelSizeObserver;
    private ContentObserver mQSTileSecondaryLabelSizeObserver;
    private ContentObserver mBrightnessSliderStyleObserver;
    private UiModeManager.ContrastChangeListener mContrastChangeListener;

    // Defers changing themes until Setup Wizard is done.
    private boolean mDeferredThemeEvaluation;
    // Determines if we should ignore THEME_CUSTOMIZATION_OVERLAY_PACKAGES setting changes.
    private boolean mSkipSettingChange;
    
    private float mChromaFactor = 1.0f;
    private float mLuminanceFactor = 1.0f;
    private boolean mTintBackground;
    private boolean mCustomColor;
    private int mColorOverride;
    private boolean mCustomBgColor;
    private int mBgColorOverride;

    private final ConfigurationListener mConfigurationListener =
            new ConfigurationListener() {
                @Override
                public void onUiModeChanged() {
                    if (isBlackThemeEnabled()) {
                        Log.i(TAG, "Re-applying theme on UI change");
                        reevaluateSystemTheme(true /* forceReload */);
                    }
                }
            };

    private final DeviceProvisionedListener mDeviceProvisionedListener =
            new DeviceProvisionedListener() {
                @Override
                public void onUserSetupChanged() {
                    if (!mDeviceProvisionedController.isCurrentUserSetup()) {
                        return;
                    }
                    if (!mDeferredThemeEvaluation) {
                        return;
                    }
                    Log.i(TAG, "Applying deferred theme");
                    mDeferredThemeEvaluation = false;
                    reevaluateSystemTheme(true /* forceReload */);
                }
            };

    private final OnColorsChangedListener mOnColorsChangedListener = new OnColorsChangedListener() {
        @Override
        public void onColorsChanged(WallpaperColors wallpaperColors, int which) {
            throw new IllegalStateException("This should never be invoked, all messages should "
                    + "arrive on the overload that has a user id");
        }

        @Override
        public void onColorsChanged(WallpaperColors wallpaperColors, int which, int userId) {
            WallpaperColors currentColors = mCurrentColors.get(userId);
            if (wallpaperColors != null && wallpaperColors.equals(currentColors)) {
                return;
            }
            boolean currentUser = userId == mUserTracker.getUserId();
            boolean isAsleep = themeOverlayControllerWakefulnessDeprecation()
                    ? mKeyguardTransitionInteractor.isFinishedInStateWhereValue(
                        state -> KeyguardState.Companion.deviceIsAsleepInState(state))
                    : mWakefulnessLifecycle.getWakefulness() != WAKEFULNESS_ASLEEP;

            if (currentUser && !mAcceptColorEvents && isAsleep) {
                mDeferredWallpaperColors.put(userId, wallpaperColors);
                mDeferredWallpaperColorsFlags.put(userId, which);
                Log.i(TAG, "colors received; processing deferred until screen off: "
                        + wallpaperColors + " user: " + userId);
                return;
            }

            if (currentUser && wallpaperColors != null) {
                mAcceptColorEvents = false;
                // Any cache of colors deferred for process is now stale.
                mDeferredWallpaperColors.put(userId, null);
                mDeferredWallpaperColorsFlags.put(userId, 0);
            }

            handleWallpaperColors(wallpaperColors, which, userId);
        }
    };

    private final UserTracker.Callback mUserTrackerCallback = new UserTracker.Callback() {
        @Override
        public void onUserChanged(int newUser, @NonNull Context userContext) {
            boolean isManagedProfile = mUserManager.isManagedProfile(newUser);
            if (!mDeviceProvisionedController.isCurrentUserSetup() && isManagedProfile) {
                Log.i(TAG, "User setup not finished when new user event was received. "
                        + "Deferring... Managed profile? " + isManagedProfile);
                return;
            }
            if (DEBUG) Log.d(TAG, "Updating overlays for user switch / profile added.");
            reevaluateSystemTheme(true /* forceReload */);
        }
    };

    private int getLatestWallpaperType(int userId) {
        return mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, userId)
                > mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_SYSTEM, userId)
                ? WallpaperManager.FLAG_LOCK : WallpaperManager.FLAG_SYSTEM;
    }

    private boolean isSeedColorSet(JSONObject jsonObject, WallpaperColors newWallpaperColors) {
        if (newWallpaperColors == null) {
            return false;
        }
        // Gets the color that was overridden in the theme setting if any.
        String sysPaletteColor = (String) jsonObject.opt(OVERLAY_CATEGORY_SYSTEM_PALETTE);
        if (sysPaletteColor == null) {
            return false;
        }
        if (!sysPaletteColor.startsWith("#")) {
            sysPaletteColor = "#" + sysPaletteColor;
        }
        final int systemPaletteColorArgb = Color.parseColor(sysPaletteColor);
        // Gets seed colors from incoming {@link WallpaperColors} instance.
        List<Integer> seedColors = ColorScheme.getSeedColors(newWallpaperColors);
        for (int seedColor : seedColors) {
            // The seed color from incoming {@link WallpaperColors} instance
            // was set as color override.
            if (seedColor == systemPaletteColorArgb) {
                if (DEBUG) {
                    Log.d(TAG, "Same as previous set system palette: " + sysPaletteColor);
                }
                return true;
            }
        }
        return false;
    }

    private void handleWallpaperColors(WallpaperColors wallpaperColors, int flags, int userId) {
        final int currentUser = mUserTracker.getUserId();
        final boolean hadWallpaperColors = mCurrentColors.get(userId) != null;
        int latestWallpaperType = getLatestWallpaperType(userId);
        boolean eventForLatestWallpaper = (flags & latestWallpaperType) != 0;
        if (eventForLatestWallpaper) {
            mCurrentColors.put(userId, wallpaperColors);
            if (DEBUG) Log.d(TAG, "got new colors: " + wallpaperColors + " where: " + flags);
        }

        if (userId != currentUser) {
            Log.d(TAG, "Colors " + wallpaperColors + " for user " + userId + ". "
                    + "Not for current user: " + currentUser);
            return;
        }

        if (mDeviceProvisionedController != null
                && !mDeviceProvisionedController.isCurrentUserSetup()) {
            if (hadWallpaperColors) {
                Log.i(TAG, "Wallpaper color event deferred until setup is finished: "
                        + wallpaperColors);
                mDeferredThemeEvaluation = true;
                return;
            } else if (mDeferredThemeEvaluation) {
                Log.i(TAG, "Wallpaper color event received, but we already were deferring eval: "
                        + wallpaperColors);
                return;
            } else {
                if (DEBUG) {
                    Log.i(TAG, "During user setup, but allowing first color event: had? "
                            + hadWallpaperColors + " has? " + (mCurrentColors.get(userId) != null));
                }
            }
        }
        // Check if we need to reset to default colors (if a color override was set that is sourced
        // from the wallpaper)
        String overlayPackageJson = mSecureSettings.getStringForUser(
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                currentUser);
        boolean isDestinationBoth = (flags == (WallpaperManager.FLAG_SYSTEM
                | WallpaperManager.FLAG_LOCK));
        boolean isDestinationHomeOnly = (flags == WallpaperManager.FLAG_SYSTEM);
        try {
            JSONObject jsonObject = (overlayPackageJson == null) ? new JSONObject()
                    : new JSONObject(overlayPackageJson);
            // The latest applied wallpaper should be the source of system colors when:
            // There is not preset color applied and the incoming wallpaper color is not applied
            String wallpaperPickerColorSource = jsonObject.optString(OVERLAY_COLOR_SOURCE);
            boolean userChosePresetColor = COLOR_SOURCE_PRESET.equals(wallpaperPickerColorSource);
            boolean userChoseLockScreenColor = COLOR_SOURCE_LOCK.equals(wallpaperPickerColorSource);
            boolean preserveLockScreenColor = isDestinationHomeOnly && userChoseLockScreenColor;

            if (!userChosePresetColor && !preserveLockScreenColor && eventForLatestWallpaper
                    && !isSeedColorSet(jsonObject, wallpaperColors)) {
                mSkipSettingChange = true;
                if (jsonObject.has(OVERLAY_CATEGORY_ACCENT_COLOR) || jsonObject.has(
                        OVERLAY_CATEGORY_SYSTEM_PALETTE)) {
                    jsonObject.remove(OVERLAY_CATEGORY_DYNAMIC_COLOR);
                    jsonObject.remove(OVERLAY_CATEGORY_ACCENT_COLOR);
                    jsonObject.remove(OVERLAY_CATEGORY_SYSTEM_PALETTE);
                    jsonObject.remove(OVERLAY_COLOR_INDEX);
                }
                // Keep color_both value because users can change either or both home and
                // lock screen wallpapers.
                jsonObject.put(OVERLAY_COLOR_BOTH, isDestinationBoth ? "1" : "0");

                jsonObject.put(OVERLAY_COLOR_SOURCE,
                        (flags == WallpaperManager.FLAG_LOCK) ? COLOR_SOURCE_LOCK
                                : COLOR_SOURCE_HOME);
                jsonObject.put(TIMESTAMP_FIELD, System.currentTimeMillis());
                if (DEBUG) {
                    Log.d(TAG, "Updating theme setting from "
                            + overlayPackageJson + " to " + jsonObject.toString());
                }
                mSecureSettings.putStringForUser(
                        Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                        jsonObject.toString(), UserHandle.USER_CURRENT);
            }
        } catch (JSONException e) {
            Log.i(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e);
        }
        reevaluateSystemTheme(false /* forceReload */);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean newProfile = Intent.ACTION_PROFILE_ADDED.equals(intent.getAction());
            if (newProfile) {
                UserHandle newUserHandle = intent.getParcelableExtra(Intent.EXTRA_USER,
                        android.os.UserHandle.class);
                boolean isManagedProfile =
                        mUserManager.isManagedProfile(newUserHandle.getIdentifier());
                if (!mDeviceProvisionedController.isUserSetup(newUserHandle.getIdentifier())
                        && isManagedProfile) {
                    Log.i(TAG, "User setup not finished when " + intent.getAction()
                            + " was received. Deferring... Managed profile? " + isManagedProfile);
                    return;
                }
                if (android.os.Flags.allowPrivateProfile() && isPrivateProfile(newUserHandle)) {
                    mDeferredThemeEvaluation = true;
                    Log.i(TAG, "Deferring theme for private profile till user setup is complete");
                    return;
                }
                if (DEBUG) Log.d(TAG, "Updating overlays for user switch / profile added.");
                reevaluateSystemTheme(true /* forceReload */);
            } else if (Intent.ACTION_WALLPAPER_CHANGED.equals(intent.getAction())) {
                if (intent.getBooleanExtra(WallpaperManager.EXTRA_FROM_FOREGROUND_APP, false)) {
                    mAcceptColorEvents = true;
                    Log.i(TAG, "Wallpaper changed, allowing color events again");
                } else {
                    Log.i(TAG, "Wallpaper changed from background app, "
                            + "keep deferring color events. Accepting: " + mAcceptColorEvents);
                }
            }
        }
    };

    @Inject
    public ThemeOverlayController(
            Context context,
            BroadcastDispatcher broadcastDispatcher,
            @Background Handler bgHandler,
            @Main Executor mainExecutor,
            @Background Executor bgExecutor,
            ThemeOverlayApplier themeOverlayApplier,
            SecureSettings secureSettings,
            WallpaperManager wallpaperManager,
            UserManager userManager,
            DeviceProvisionedController deviceProvisionedController,
            UserTracker userTracker,
            DumpManager dumpManager,
            FeatureFlags featureFlags,
            @Main Resources resources,
            WakefulnessLifecycle wakefulnessLifecycle,
            JavaAdapter javaAdapter,
            KeyguardTransitionInteractor keyguardTransitionInteractor,
            UiModeManager uiModeManager,
            ActivityManager activityManager,
            ConfigurationController configurationController,
            SystemSettings systemSettings,
            TunerService tunerService) {
        mContext = context;
        mIsMonetEnabled = featureFlags.isEnabled(Flags.MONET);
        mIsFidelityEnabled = featureFlags.isEnabled(Flags.COLOR_FIDELITY);
        mConfigurationController = configurationController;
        mDeviceProvisionedController = deviceProvisionedController;
        mBroadcastDispatcher = broadcastDispatcher;
        mUserManager = userManager;
        mBgExecutor = bgExecutor;
        mMainExecutor = mainExecutor;
        mBgHandler = bgHandler;
        mThemeManager = themeOverlayApplier;
        mSecureSettings = secureSettings;
        mSystemSettings = systemSettings;
        mWallpaperManager = wallpaperManager;
        mUserTracker = userTracker;
        mResources = resources;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mJavaAdapter = javaAdapter;
        mKeyguardTransitionInteractor = keyguardTransitionInteractor;
        mUiModeManager = uiModeManager;
        mActivityManager = activityManager;
        mTunerService = tunerService;
        dumpManager.registerDumpable(TAG, this);
        
        initContentObservers();
    }
    
    private void initContentObservers() {
        mThemeCustomizationObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                handleContentChange(userId);
            }
        };

        mBerryBlackThemeObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                handleContentChange(userId);
            }
        };

        mStatusBarBatteryStyleObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                handleContentChange(userId);
            }
        };

        mQSBatteryStyleObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                handleContentChange(userId);
            }
        };

        mQSTileVerticalLayoutObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                handleContentChange(userId);
            }
        };

        mQSTileLabelHideObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                handleContentChange(userId);
            }
        };

        mQSUISyleObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                handleContentChange(userId);
            }
        };

        mQSShowBatteryPercentObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                handleContentChange(userId);
            }
        };

        mClockMarginTopObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                handleContentChange(userId);
            }
        };

        mQSTileShapeObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                handleContentChange(userId);
            }
        };

        mStatusBarClockChipObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                handleContentChange(userId);
            }
        };

        mLockscreenClockColoredObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                handleContentChange(userId);
            }
        };

        mQSTileLabelSizeObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                handleContentChange(userId);
            }
        };

        mQSTileSecondaryLabelSizeObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                handleContentChange(userId);
            }
        };

        mBrightnessSliderStyleObserver = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> collection, int flags, int userId) {
                if (DEBUG) Log.d(TAG, "Overlay changed for user: " + userId);
                if (mUserTracker.getUserId() != userId) {
                    return;
                }
                if (!mDeviceProvisionedController.isUserSetup(userId)) {
                    Log.i(TAG, "Theme application deferred when setting changed.");
                    mDeferredThemeEvaluation = true;
                    return;
                }
                int brightnessSliderStyle = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                        Settings.Secure.BRIGHTNESS_SLIDER_STYLE, 0, UserHandle.USER_CURRENT);
                mThemeManager.setBrightnessSliderStyle(brightnessSliderStyle);
            }
        };
    }

    @Override
    public void start() {
        if (DEBUG) Log.d(TAG, "Start");
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PROFILE_ADDED);
        filter.addAction(Intent.ACTION_WALLPAPER_CHANGED);
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, filter, mBgExecutor,
                UserHandle.ALL);
        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                false,
                mThemeCustomizationObserver,
                UserHandle.USER_ALL);

        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.BERRY_BLACK_THEME,
                false,
                mBerryBlackThemeObserver,
                UserHandle.USER_ALL);

        mSystemSettings.registerContentObserverForUser(
                Settings.System.STATUS_BAR_BATTERY_STYLE,
                false,
                mStatusBarBatteryStyleObserver,
                UserHandle.USER_ALL);

        mSystemSettings.registerContentObserverForUser(
                Settings.System.QS_BATTERY_STYLE,
                false,
                mQSBatteryStyleObserver,
                UserHandle.USER_ALL);

        mSystemSettings.registerContentObserverForUser(
                Settings.System.QS_TILE_VERTICAL_LAYOUT,
                false,
                mQSTileVerticalLayoutObserver,
                UserHandle.USER_ALL);

        mSystemSettings.registerContentObserverForUser(
                Settings.System.QS_TILE_LABEL_HIDE,
                false,
                mQSTileLabelHideObserver,
                UserHandle.USER_ALL);

        mSystemSettings.registerContentObserverForUser(
                Settings.System.QS_UI_STYLE,
                false,
                mQSUISyleObserver,
                UserHandle.USER_ALL);

        mSystemSettings.registerContentObserverForUser(
                Settings.System.QS_SHOW_BATTERY_PERCENT,
                false,
                mQSShowBatteryPercentObserver,
                UserHandle.USER_ALL);

        mSystemSettings.registerContentObserverForUser(
                Settings.System.CLOCK_MARGIN_TOP,
                false,
                mClockMarginTopObserver,
                UserHandle.USER_ALL);

        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.QS_TILE_SHAPE,
                false,
                mQSTileShapeObserver,
                UserHandle.USER_ALL);

        mSystemSettings.registerContentObserverForUser(
                Settings.System.STATUSBAR_CLOCK_CHIP,
                false,
                mStatusBarClockChipObserver,
                UserHandle.USER_ALL);

        mSystemSettings.registerContentObserverForUser(
                Settings.System.LOCKSCREEN_CLOCK_COLORED,
                false,
                mLockscreenClockColoredObserver,
                UserHandle.USER_ALL);

        mSystemSettings.registerContentObserverForUser(
                Settings.System.QS_TILE_LABEL_SIZE,
                false,
                mQSTileLabelSizeObserver,
                UserHandle.USER_ALL);

        mSystemSettings.registerContentObserverForUser(
                Settings.System.QS_TILE_SECONDARY_LABEL_SIZE,
                false,
                mQSTileSecondaryLabelSizeObserver,
                UserHandle.USER_ALL);

        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.BRIGHTNESS_SLIDER_STYLE,
                false,
                mBrightnessSliderStyleObserver,
                UserHandle.USER_CURRENT);

        mUserTracker.addCallback(mUserTrackerCallback, mBgExecutor);

        mConfigurationController.addCallback(mConfigurationListener);
        mDeviceProvisionedController.addCallback(mDeviceProvisionedListener);

        // All wallpaper color and keyguard logic only applies when Monet is enabled.
        if (!mIsMonetEnabled) {
            return;
        }

        mTunerService.addTunable(this, PREF_CHROMA_FACTOR);
        mTunerService.addTunable(this, PREF_LUMINANCE_FACTOR);
        mTunerService.addTunable(this, PREF_TINT_BACKGROUND);
        mTunerService.addTunable(this, PREF_CUSTOM_COLOR);
        mTunerService.addTunable(this, PREF_COLOR_OVERRIDE);
        mTunerService.addTunable(this, PREF_CUSTOM_BGCOLOR);
        mTunerService.addTunable(this, PREF_BGCOLOR_OVERRIDE);

        // Upon boot, make sure we have the most up to date colors
        Runnable updateColors = () -> {
            WallpaperColors systemColor = mWallpaperManager.getWallpaperColors(
                    getLatestWallpaperType(mUserTracker.getUserId()));
            Runnable applyColors = () -> {
                if (DEBUG) Log.d(TAG, "Boot colors: " + systemColor);
                mCurrentColors.put(mUserTracker.getUserId(), systemColor);
                reevaluateSystemTheme(false /* forceReload */);
            };
            if (mDeviceProvisionedController.isCurrentUserSetup()) {
                mMainExecutor.execute(applyColors);
            } else {
                applyColors.run();
            }
        };

        // Whenever we're going directly to setup wizard, we need to process colors synchronously,
        // otherwise we'll see some jank when the activity is recreated.
        if (!mDeviceProvisionedController.isCurrentUserSetup()) {
            updateColors.run();
        } else {
            mBgExecutor.execute(updateColors);
        }
        mWallpaperManager.addOnColorsChangedListener(mOnColorsChangedListener, null,
                UserHandle.USER_ALL);

        Runnable whenAsleepHandler = () -> {
            final int userId = mUserTracker.getUserId();
            final WallpaperColors colors = mDeferredWallpaperColors.get(userId);
            if (colors != null) {
                int flags = mDeferredWallpaperColorsFlags.get(userId);

                mDeferredWallpaperColors.put(userId, null);
                mDeferredWallpaperColorsFlags.put(userId, 0);

                handleWallpaperColors(colors, flags, userId);
            }
        };

        if (themeOverlayControllerWakefulnessDeprecation()) {
            mJavaAdapter.alwaysCollectFlow(
                    mKeyguardTransitionInteractor.isFinishedInState(KeyguardState.DOZING),
                    isFinishedInDozing -> {
                        if (isFinishedInDozing) whenAsleepHandler.run();
                    });
        } else {
            mWakefulnessLifecycle.addObserver(new WakefulnessLifecycle.Observer() {
                @Override
                public void onFinishedGoingToSleep() {
                    whenAsleepHandler.run();
                }
            });
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case PREF_CHROMA_FACTOR:
                mChromaFactor =
                        (float) TunerService.parseInteger(newValue, 100) / 100f;
                reevaluateSystemTheme(true /* forceReload */);
                break;
            case PREF_LUMINANCE_FACTOR:
                mLuminanceFactor =
                        (float) TunerService.parseInteger(newValue, 100) / 100f;
                reevaluateSystemTheme(true /* forceReload */);
                break;
            case PREF_TINT_BACKGROUND:
                mTintBackground =
                        TunerService.parseIntegerSwitch(newValue, false);
                reevaluateSystemTheme(true /* forceReload */);
                break;
            case PREF_CUSTOM_COLOR:
                mCustomColor =
                        TunerService.parseIntegerSwitch(newValue, false);
                reevaluateSystemTheme(true /* forceReload */);
                break;
            case PREF_COLOR_OVERRIDE:
                mColorOverride =
                        TunerService.parseInteger(newValue, 0xFF1b6ef3);
                reevaluateSystemTheme(true /* forceReload */);
                break;
            case PREF_CUSTOM_BGCOLOR:
                mCustomBgColor =
                        TunerService.parseIntegerSwitch(newValue, false);
                reevaluateSystemTheme(true /* forceReload */);
                break;
            case PREF_BGCOLOR_OVERRIDE:
                mBgColorOverride =
                        TunerService.parseInteger(newValue, 0xFF1b6ef3);
                reevaluateSystemTheme(true /* forceReload */);
                break;
            default:
                break;
         }
    }    

    protected void reevaluateSystemTheme(boolean forceReload) {
        final WallpaperColors currentColors = mCurrentColors.get(mUserTracker.getUserId());
        final int mainColor;
        if (currentColors == null) {
            mainColor = Color.TRANSPARENT;
        } else {
            mainColor = getNeutralColor(currentColors);
        }

        if (mMainWallpaperColor == mainColor && !forceReload) {
            return;
        }
        mMainWallpaperColor = mainColor;

        if (mIsMonetEnabled) {
            mThemeStyle = fetchThemeStyleFromSetting();
            createOverlays(mMainWallpaperColor);
            mNeedsOverlayCreation = true;
            if (DEBUG) {
                Log.d(TAG, "fetched overlays. accent: " + mSecondaryOverlay
                        + " neutral: " + mNeutralOverlay + " dynamic: " + mDynamicOverlay);
            }
        }

        updateThemeOverlays();
        cleanUpUnusedResources();
    }
    
    private void cleanUpUnusedResources() {
       for (int i = mCurrentColors.size() - 1; i >= 0; i--) {
            if (mCurrentColors.valueAt(i) == null) {
                mCurrentColors.removeAt(i);
            }
       }
    
       if (!mIsMonetEnabled) {
           mThemeStyle = null;
           mSecondaryOverlay = null;
           mNeutralOverlay = null;
           mDynamicOverlay = null;
       }
       
       mBgExecutor.execute(new Runnable() {
           @Override
           public void run() {
               System.gc();
           }
       });
    }

    /**
     * Return the main theme color from a given {@link WallpaperColors} instance.
     */
    protected int getNeutralColor(@NonNull WallpaperColors wallpaperColors) {
        return ColorScheme.getSeedColor(wallpaperColors);
    }

    protected int getAccentColor(@NonNull WallpaperColors wallpaperColors) {
        return ColorScheme.getSeedColor(wallpaperColors);
    }

    private static DynamicScheme dynamicSchemeFromStyle(Style style, int color,
            boolean isDark, double contrastLevel) {
        Hct sourceColorHct = Hct.fromInt(color);
        switch (style) {
            case EXPRESSIVE:
                return new SchemeExpressive(sourceColorHct, isDark, contrastLevel);
            case SPRITZ:
                return new SchemeNeutral(sourceColorHct, isDark, contrastLevel);
            case TONAL_SPOT:
                return new SchemeTonalSpot(sourceColorHct, isDark, contrastLevel);
            case FRUIT_SALAD:
                return new SchemeFruitSalad(sourceColorHct, isDark, contrastLevel);
            case RAINBOW:
                return new SchemeRainbow(sourceColorHct, isDark, contrastLevel);
            case VIBRANT:
                return new SchemeVibrant(sourceColorHct, isDark, contrastLevel);
            case MONOCHROMATIC:
                return new SchemeMonochrome(sourceColorHct, isDark, contrastLevel);
            default:
                return null;
        }
    }

    @VisibleForTesting
    protected boolean isNightMode() {
        return (mResources.getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @VisibleForTesting
    protected FabricatedOverlay newFabricatedOverlay(String name) {
        return new FabricatedOverlay.Builder("com.android.systemui", name, "android").build();
    }

    @VisibleForTesting
    protected boolean isPrivateProfile(UserHandle userHandle) {
        Context usercontext = mContext.createContextAsUser(userHandle,0);
        if (usercontext.getSystemService(UserManager.class).isPrivateProfile()) {
            return true;
        }
        return false;
    }

    private void createOverlays(int color) {
        boolean nightMode = isNightMode();
        mColorScheme = new ColorScheme(color, nightMode, mCustomColor ? Style.TONAL_SPOT : mThemeStyle,
                    mLuminanceFactor, mChromaFactor, mTintBackground,
                    mCustomColor ? mColorOverride : null,
                    mCustomBgColor ? mBgColorOverride : null);
        mNeutralOverlay = createNeutralOverlay();
        mSecondaryOverlay = createAccentOverlay();

        mDynamicSchemeDark = dynamicSchemeFromStyle(
                mThemeStyle, color, true /* isDark */, mContrast);
        mDynamicSchemeLight = dynamicSchemeFromStyle(
                mThemeStyle, color, false /* isDark */, mContrast);
        mDynamicOverlay = createDynamicOverlay();
    }

    protected FabricatedOverlay createNeutralOverlay() {
        FabricatedOverlay overlay = newFabricatedOverlay("neutral");
        assignTonalPaletteToOverlay("neutral1", overlay, mColorScheme.getNeutral1());
        assignTonalPaletteToOverlay("neutral2", overlay, mColorScheme.getNeutral2());
        return overlay;
    }

    protected FabricatedOverlay createAccentOverlay() {
        FabricatedOverlay overlay = newFabricatedOverlay("accent");
        assignTonalPaletteToOverlay("accent1", overlay, mColorScheme.getAccent1());
        assignTonalPaletteToOverlay("accent2", overlay, mColorScheme.getAccent2());
        assignTonalPaletteToOverlay("accent3", overlay, mColorScheme.getAccent3());
        return overlay;
    }

    private void assignTonalPaletteToOverlay(String name, FabricatedOverlay overlay,
            TonalPalette tonalPalette) {
        String resourcePrefix = "android:color/system_" + name;

        tonalPalette.getAllShadesMapped().forEach((key, value) -> {
            String resourceName = resourcePrefix + "_" + key;
            int colorValue = ColorUtils.setAlphaComponent(value, 0xFF);
            overlay.setResourceValue(resourceName, TYPE_INT_COLOR_ARGB8, colorValue,
                    null /* configuration */);
        });
    }

    protected FabricatedOverlay createDynamicOverlay() {
        FabricatedOverlay overlay = newFabricatedOverlay("dynamic");
        assignDynamicPaletteToOverlay(overlay, true /* isDark */);
        assignDynamicPaletteToOverlay(overlay, false /* isDark */);
        assignFixedColorsToOverlay(overlay);
        return overlay;
    }

    private void assignDynamicPaletteToOverlay(FabricatedOverlay overlay, boolean isDark) {
        String suffix = isDark ? "dark" : "light";
        DynamicScheme scheme = isDark ? mDynamicSchemeDark : mDynamicSchemeLight;
        DynamicColors.allDynamicColorsMapped(mIsFidelityEnabled).forEach(p -> {
            String resourceName = "android:color/system_" + p.first + "_" + suffix;
            int colorValue = p.second.getArgb(scheme);
            overlay.setResourceValue(resourceName, TYPE_INT_COLOR_ARGB8, colorValue,
                    null /* configuration */);
        });
    }

    private void assignFixedColorsToOverlay(FabricatedOverlay overlay) {
        DynamicColors.getFixedColorsMapped(mIsFidelityEnabled).forEach(p -> {
            String resourceName = "android:color/system_" + p.first;
            int colorValue = p.second.getArgb(mDynamicSchemeLight);
            overlay.setResourceValue(resourceName, TYPE_INT_COLOR_ARGB8, colorValue,
                    null /* configuration */);
        });
    }

    private void updateThemeOverlays() {
        final int currentUser = mUserTracker.getUserId();
        final String overlayPackageJson = mSecureSettings.getStringForUser(
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                currentUser);
        if (DEBUG) Log.d(TAG, "updateThemeOverlays. Setting: " + overlayPackageJson);
        final Map<String, OverlayIdentifier> categoryToPackage = new ArrayMap<>();
        if (!TextUtils.isEmpty(overlayPackageJson)) {
            try {
                JSONObject object = new JSONObject(overlayPackageJson);
                for (String category : ThemeOverlayApplier.THEME_CATEGORIES) {
                    if (object.has(category)) {
                        OverlayIdentifier identifier =
                                new OverlayIdentifier(object.getString(category));
                        categoryToPackage.put(category, identifier);
                    }
                }
            } catch (JSONException e) {
                Log.i(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e);
            }
        }

        // Let's generate system overlay if the style picker decided to override it.
        OverlayIdentifier systemPalette = categoryToPackage.get(OVERLAY_CATEGORY_SYSTEM_PALETTE);
        if (mIsMonetEnabled && systemPalette != null && systemPalette.getPackageName() != null) {
            try {
                String colorString =  systemPalette.getPackageName().toLowerCase();
                if (!colorString.startsWith("#")) {
                    colorString = "#" + colorString;
                }
                createOverlays(Color.parseColor(colorString));
                mNeedsOverlayCreation = true;
                categoryToPackage.remove(OVERLAY_CATEGORY_SYSTEM_PALETTE);
                categoryToPackage.remove(OVERLAY_CATEGORY_ACCENT_COLOR);
                categoryToPackage.remove(OVERLAY_CATEGORY_DYNAMIC_COLOR);
            } catch (Exception e) {
                // Color.parseColor doesn't catch any exceptions from the calls it makes
                Log.w(TAG, "Invalid color definition: " + systemPalette.getPackageName(), e);
            }
        } else if (!mIsMonetEnabled && systemPalette != null) {
            try {
                // It's possible that we flipped the flag off and still have a @ColorInt in the
                // setting. We need to sanitize the input, otherwise the overlay transaction will
                // fail.
                categoryToPackage.remove(OVERLAY_CATEGORY_SYSTEM_PALETTE);
                categoryToPackage.remove(OVERLAY_CATEGORY_ACCENT_COLOR);
                categoryToPackage.remove(OVERLAY_CATEGORY_DYNAMIC_COLOR);
            } catch (NumberFormatException e) {
                // This is a package name. All good, let's continue
            }
        }

        // Compatibility with legacy themes, where full packages were defined, instead of just
        // colors.
        if (!categoryToPackage.containsKey(OVERLAY_CATEGORY_SYSTEM_PALETTE)
                && mNeutralOverlay != null) {
            categoryToPackage.put(OVERLAY_CATEGORY_SYSTEM_PALETTE,
                    mNeutralOverlay.getIdentifier());
        }
        if (!categoryToPackage.containsKey(OVERLAY_CATEGORY_ACCENT_COLOR)
                && mSecondaryOverlay != null) {
            categoryToPackage.put(OVERLAY_CATEGORY_ACCENT_COLOR, mSecondaryOverlay.getIdentifier());
        }
        if (!categoryToPackage.containsKey(OVERLAY_CATEGORY_DYNAMIC_COLOR)
                && mDynamicOverlay != null) {
            categoryToPackage.put(OVERLAY_CATEGORY_DYNAMIC_COLOR, mDynamicOverlay.getIdentifier());
        }

        boolean isBlackMode = isBlackThemeEnabled() && isNightMode();
        if (categoryToPackage.containsKey(OVERLAY_CATEGORY_SYSTEM_PALETTE) && isBlackMode) {
            OverlayIdentifier blackTheme = new OverlayIdentifier(OVERLAY_BERRY_BLACK_THEME);
            categoryToPackage.put(OVERLAY_CATEGORY_SYSTEM_PALETTE, blackTheme);
        }

        Set<UserHandle> managedProfiles = new HashSet<>();
        for (UserInfo userInfo : mUserManager.getEnabledProfiles(currentUser)) {
            if (userInfo.isProfile()) {
                managedProfiles.add(userInfo.getUserHandle());
            }
        }

        final Runnable onCompleteCallback = !enableHomeDelay()
                ? () -> {}
                : () -> {
                    Log.d(TAG, "ThemeHomeDelay: ThemeOverlayController ready");
                    mActivityManager.setThemeOverlayReady(currentUser);
                };

        if (DEBUG) {
            Log.d(TAG, "Applying overlays: " + categoryToPackage.keySet().stream()
                    .map(key -> key + " -> " + categoryToPackage.get(key)).collect(
                            Collectors.joining(", ")));
        }

        FabricatedOverlay[] fOverlays = null;

        if (mNeedsOverlayCreation) {
            mNeedsOverlayCreation = false;
            fOverlays = new FabricatedOverlay[]{
                    mSecondaryOverlay, mNeutralOverlay, mDynamicOverlay
            };
            cleanupOverlayCreation();
        }

        mThemeManager.applyCurrentUserOverlays(categoryToPackage, fOverlays, currentUser,
                managedProfiles, onCompleteCallback);

    }
    
    private void cleanupOverlayCreation() {
        mSecondaryOverlay = null;
        mNeutralOverlay = null;
        mDynamicOverlay = null;
    }

    private Style fetchThemeStyleFromSetting() {
        // Allow-list of Style objects that can be created from a setting string, i.e. can be
        // used as a system-wide theme.
        // - Content intentionally excluded, intended for media player, not system-wide
        List<Style> validStyles = new ArrayList<>(Arrays.asList(Style.EXPRESSIVE, Style.SPRITZ,
                Style.TONAL_SPOT, Style.FRUIT_SALAD, Style.RAINBOW, Style.VIBRANT,
                Style.MONOCHROMATIC));

        Style style = mThemeStyle;
        final String overlayPackageJson = mSecureSettings.getStringForUser(
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                mUserTracker.getUserId());
        if (!TextUtils.isEmpty(overlayPackageJson)) {
            try {
                JSONObject object = new JSONObject(overlayPackageJson);
                style = Style.valueOf(
                        object.getString(ThemeOverlayApplier.OVERLAY_CATEGORY_THEME_STYLE));
                if (!validStyles.contains(style)) {
                    style = Style.TONAL_SPOT;
                }
            } catch (JSONException | IllegalArgumentException e) {
                Log.i(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e);
                style = Style.TONAL_SPOT;
            }
        }
        return style;
    }

    private boolean isBlackThemeEnabled() {
        return Settings.Secure.getIntForUser(
            mContext.getContentResolver(), Settings.Secure.BERRY_BLACK_THEME, 0, mUserTracker.getUserId()) == 1;
    }

    public void stop() {
        if (DEBUG) Log.d(TAG, "Stop");

        mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);

        mSecureSettings.unregisterContentObserver(mThemeCustomizationObserver);
        mSecureSettings.unregisterContentObserver(mBerryBlackThemeObserver);
        mSystemSettings.unregisterContentObserver(mStatusBarBatteryStyleObserver);
        mSystemSettings.unregisterContentObserver(mQSBatteryStyleObserver);
        mSystemSettings.unregisterContentObserver(mQSTileVerticalLayoutObserver);
        mSystemSettings.unregisterContentObserver(mQSTileLabelHideObserver);
        mSystemSettings.unregisterContentObserver(mQSUISyleObserver);
        mSystemSettings.unregisterContentObserver(mQSShowBatteryPercentObserver);
        mSystemSettings.unregisterContentObserver(mClockMarginTopObserver);
        mSecureSettings.unregisterContentObserver(mQSTileShapeObserver);
        mSystemSettings.unregisterContentObserver(mStatusBarClockChipObserver);
        mSystemSettings.unregisterContentObserver(mLockscreenClockColoredObserver);
        mSystemSettings.unregisterContentObserver(mQSTileLabelSizeObserver);
        mSystemSettings.unregisterContentObserver(mQSTileSecondaryLabelSizeObserver);
        mSecureSettings.unregisterContentObserver(mBrightnessSliderStyleObserver);
        
        mUserTracker.removeCallback(mUserTrackerCallback);
        mConfigurationController.removeCallback(mConfigurationListener);
        mDeviceProvisionedController.removeCallback(mDeviceProvisionedListener);
        
        mBgExecutor.execute(new Runnable() {
           @Override
           public void run() {
               System.gc();
           }
       });
    }

    private void handleContentChange(int userId) {
        if (DEBUG) Log.d(TAG, "Overlay changed for user: " + userId);
        if (mUserTracker.getUserId() != userId) {
            return;
        }
        if (!mDeviceProvisionedController.isUserSetup(userId)) {
            Log.i(TAG, "Theme application deferred when setting changed.");
            mDeferredThemeEvaluation = true;
            return;
        }
        reevaluateSystemTheme(true /* forceReload */);
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("mSystemColors=" + mCurrentColors);
        pw.println("mMainWallpaperColor=" + Integer.toHexString(mMainWallpaperColor));
        pw.println("mSecondaryOverlay=" + mSecondaryOverlay);
        pw.println("mNeutralOverlay=" + mNeutralOverlay);
        pw.println("mDynamicOverlay=" + mDynamicOverlay);
        pw.println("mIsMonetEnabled=" + mIsMonetEnabled);
        pw.println("mIsFidelityEnabled=" + mIsFidelityEnabled);
        pw.println("mColorScheme=" + mColorScheme);
        pw.println("mNeedsOverlayCreation=" + mNeedsOverlayCreation);
        pw.println("mAcceptColorEvents=" + mAcceptColorEvents);
        pw.println("mDeferredThemeEvaluation=" + mDeferredThemeEvaluation);
        pw.println("mThemeStyle=" + mThemeStyle);
    }
}