// Copyright 2019 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.androidbrowserhelper.trusted.splashscreens;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabsSession;
import androidx.browser.customtabs.TrustedWebUtils;
import androidx.core.content.FileProvider;

/**
 * Saves the splash image to a file and transfers it to Custom Tabs provider.
 */
public class SplashImageTransferTask {

    private static final String TAG = "SplashImageTransferTask";

    private static final String FOLDER_NAME = "twa_splash";
    private static final String FILE_NAME = "splash_image.png";
    private static final String PREFS_FILE = "splashImagePrefs";
    private static final String PREF_LAST_UPDATE_TIME = "lastUpdateTime";

    private final Context mContext;
    private final Bitmap mBitmap;
    private final String mAuthority;
    private final CustomTabsSession mSession;
    private final String mProviderPackage;

    @Nullable
    private Callback mCallback;

    /**
     * @param context {@link Context} to use.
     * @param bitmap image to transfer.
     * @param authority {@link FileProvider} authority.
     * @param session {@link CustomTabsSession} to use for transferring the file.
     * @param providerPackage Package name of the Custom Tabs provider.
     */
    public SplashImageTransferTask(Context context, Bitmap bitmap, String authority,
            CustomTabsSession session, String providerPackage) {
        mContext = context.getApplicationContext();
        mBitmap = bitmap;
        mAuthority = authority;
        mSession = session;
        mProviderPackage = providerPackage;
    }

    /**
     * Executes the task. Should be called only once.
     * @param callback {@link Callback} to be called when done.
     */
    public void execute(Callback callback) {
        assert mAsyncTask.getStatus() == AsyncTask.Status.PENDING;
        mCallback = callback;
        mAsyncTask.execute();
    }

    /**
     * Cancels the execution. The callback passed into {@link #execute} won't be called, and
     * the references to it will be released.
     */
    public void cancel() {
        mAsyncTask.cancel(true);
        mCallback = null;
    }

    @SuppressLint("StaticFieldLeak") // No leaking should happen
    private final AsyncTask<Void, Void, Boolean> mAsyncTask = new AsyncTask<Void, Void, Boolean>() {

        @Override
        protected Boolean doInBackground(Void... args) {
            if (isCancelled()) return false;
            File dir = new File(mContext.getFilesDir(), FOLDER_NAME);
            if (!dir.exists()) {
                boolean mkDirSuccessful = dir.mkdir();
                if (!mkDirSuccessful) {
                    Log.w(TAG, "Failed to create a directory for storing a splash image");
                    return false;
                }
            }
            File file = new File(dir, FILE_NAME);
            SharedPreferences prefs =
                    mContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
            long lastUpdateTime = getLastAppUpdateTime();
            if (file.exists() && lastUpdateTime == prefs.getLong(PREF_LAST_UPDATE_TIME, 0)) {
                // Don't overwrite existing file, if it was saved later than the last time app was
                // updated
                return transferToCustomTabsProvider(file);
            }
            try(OutputStream os = new FileOutputStream(file)) {
                if (isCancelled()) return false;
                mBitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.flush();
                prefs.edit().putLong(PREF_LAST_UPDATE_TIME, lastUpdateTime).commit();

                if (isCancelled()) return false;
                return transferToCustomTabsProvider(file);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private boolean transferToCustomTabsProvider(File file) {
            grantUriPermissionToSelf(file);
            return TrustedWebUtils.transferSplashImage(mContext, file, mAuthority, mProviderPackage,
                    mSession);
        }

        /**
         * Grants this app read access to its own splash image URI.
         *
         * Chrome 152 added a check to CustomTabsConnection#receiveFile which rejects the transfer
         * unless the *calling* app holds a granted read permission on the URI:
         *
         *   checkUriPermission(uri, Binder.getCallingPid(), Binder.getCallingUid(),
         *           FLAG_GRANT_READ_URI_PERMISSION) != PERMISSION_GRANTED  ->  return false
         *
         * At that point the framework consults only explicitly granted permissions
         * (UriGrantsManagerService#checkUriPermissionLocked); owning the FileProvider is not
         * sufficient, as the owner shortcut applies to the grant path rather than to
         * checkUriPermission. Because an app never needed to grant itself access to its own
         * provider, the check fails and the splash screen is silently dropped. Granting
         * explicitly satisfies it.
         *
         * This is additive: older Chrome versions never perform the check, and a redundant
         * self-grant has no effect on them.
         */
        private void grantUriPermissionToSelf(File file) {
            try {
                Uri uri = FileProvider.getUriForFile(mContext, mAuthority, file);
                mContext.grantUriPermission(mContext.getPackageName(), uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                // Never let this prevent the transfer attempt itself, which still succeeds on
                // Chrome versions that do not perform the check.
                Log.w(TAG, "Failed to grant splash image URI permission to self", e);
            }
        }

        private long getLastAppUpdateTime() {
            try {
                return mContext.getPackageManager()
                        .getPackageInfo(mContext.getPackageName(), 0).lastUpdateTime;
            } catch (PackageManager.NameNotFoundException e) {
                // Should not happen
                throw new RuntimeException(e);
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (mCallback != null && !isCancelled()) {
                mCallback.onFinished(success);
            }
        }
    };

    /** Callback to be called when the file is saved and transferred to Custom Tabs provider. */
    public interface Callback {
        void onFinished(boolean successfully);
    }
}